package org.zwylair.nfldex

import android.os.SystemClock
import android.view.View
import com.exteragram.messenger.utils.AppUtils
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildVars
import org.telegram.messenger.ChatObject
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ChatActivity
import java.lang.reflect.Field
import java.lang.reflect.Member

private typealias SelectedMessages = ChatActivity.SparseArrayWithTouch<MessageObject>

class SelectorManager {
    private val isNewButtonsLayout = AppUtils.compareVersions(">=",BuildVars.BUILD_VERSION_STRING, "12.2.0")

    @Volatile
    var scrollSpeed = 1.0

    private var scrollStartTime = 0L
    private var lastScrollTime = 0L
    private var scrollRemainder = 0.0
    private var multiSelectorHookApplied = false

    private lateinit var selectedMessagesIdsField: Field
    private var limitCheckPending = false
    private var actionModeUpdateTarget: ChatActivity? = null
    private var lastActionModeUpdate = 0L

    fun setupHook(verified: Boolean) {
        try {
            selectedMessagesIdsField = ChatActivity::class.java
                .getDeclaredField("selectedMessagesIds")
                .apply { isAccessible = true }

            val selectorMethod = findClass("org.telegram.ui.ChatActivity")!!
                .findMethod("addToSelectedMessages", 3)!!

            hook(selectorMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!verified) burnCpu(25_000)

                        bypassSelectionLimit(param)
                    } catch (e: Throwable) {
                        PluginCore.log("SelectorHook.before failed:", exception = e)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!verified) burnCpu(30_000)

                        limitCheckPending = false
                        updateReplyButtonVisibility(param.thisObject as ChatActivity)
                    } catch (e: Throwable) {
                        PluginCore.log("SelectorHook.after failed:", exception = e)
                    }
                }
            })

            findClass("org.telegram.ui.Components.RecyclerListView")!!
                .findMethod("startMultiselect", 3)!!
                .before { multiSelectParam ->
                    if (multiSelectorHookApplied)
                        return@before

                    val multiSelectionListener = multiSelectParam.args[2]
                    if (!multiSelectionListener.javaClass.name.startsWith(CHAT_ACTIVITY_INNER_PREFIX))
                        return@before

                    multiSelectionListener.findMethod("limitReached")!!
                        .before { it.result = false }

                    multiSelectionListener.findMethod("scrollBy", 1)!!
                        .before {
                            val now = System.currentTimeMillis()

                            if (now - lastScrollTime > 100) {
                                scrollStartTime = now
                                scrollRemainder = 0.0
                            }
                            lastScrollTime = now

                            val originalDy = it.args[0] as Int
                            val duration = now - scrollStartTime

                            val factor = scrollSpeed * (1.0 + (duration / 2_000.0).coerceAtMost(2.5))
                            val scaled = originalDy * factor + scrollRemainder
                            val step = scaled.toInt()

                            scrollRemainder = scaled - step
                            it.args[0] = step
                        }

                    multiSelectorHookApplied = true
                }
        } catch (e: Throwable) {
            PluginCore.log("SelectorHook.setupHook failed:", exception = e)
        }
    }

    private inner class UncappedMessages(source: SelectedMessages) : SelectedMessages() {
        val realSize get() = super.size()

        init {
            for (i in 0 until source.size())
                append(source.keyAt(i), source.valueAt(i))
            withTouch.addAll(source.withTouch)
        }

        override fun size() = if (limitCheckPending) 0 else super.size()

        override fun put(key: Int, value: MessageObject?) {
            limitCheckPending = false
            super.put(key, value)
        }

        override fun delete(key: Int) {
            limitCheckPending = false
            super.delete(key)
        }

        override fun remove(key: Int) {
            limitCheckPending = false
            super.remove(key)
        }
    }

    private fun bypassSelectionLimit(param: XC_MethodHook.MethodHookParam) {
        if (param.args[0] == null) return

        val chatActivity = param.thisObject as ChatActivity
        if (uncapSelection(chatActivity) < SELECTION_LIMIT) return

        limitCheckPending = true

        if (param.args[2] == true) {
            param.args[2] = false
            scheduleActionModeUpdate(chatActivity, param.method)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun selectedMessagesOf(chatActivity: ChatActivity) =
        selectedMessagesIdsField.get(chatActivity) as Array<SelectedMessages>

    private fun uncapSelection(chatActivity: ChatActivity): Int {
        val selected = selectedMessagesOf(chatActivity)
        var selectedCount = 0

        for (i in selected.indices) {
            val messages = selected[i] as? UncappedMessages
                ?: UncappedMessages(selected[i]).also { selected[i] = it }
            selectedCount += messages.realSize
        }

        return selectedCount
    }

    private fun scheduleActionModeUpdate(chatActivity: ChatActivity, selectorMethod: Member) {
        if (actionModeUpdateTarget === chatActivity) return
        actionModeUpdateTarget = chatActivity

        val delay = lastActionModeUpdate + ACTION_MODE_UPDATE_INTERVAL - SystemClock.uptimeMillis()

        AndroidUtilities.runOnUIThread({
            actionModeUpdateTarget = null
            lastActionModeUpdate = SystemClock.uptimeMillis()

            try {
                // addToSelectedMessages(null, false, true) only updates the action mode
                if (chatActivity.actionBar?.isActionModeShowed == true)
                    XposedBridge.invokeOriginalMethod(selectorMethod, chatActivity, arrayOf<Any?>(null, false, true))
            } catch (e: Throwable) {
                PluginCore.log("SelectorHook: action mode update failed:", exception = e)
            }
        }, delay.coerceAtLeast(0))
    }

    private fun canReplyIn(chat: TLRPC.Chat?) =
        chat == null || !chat.noforwards || ChatObject.canWriteToChat(chat)

    private fun updateReplyButtonVisibility(chatActivity: ChatActivity) {
        if (selectedMessagesOf(chatActivity)[0].size() != 1) return

        val shouldShow = chatActivity.chatMode != ChatActivity.MODE_SCHEDULED
            && canReplyIn(chatActivity.currentChat)

        if (isNewButtonsLayout)
            chatActivity.getFieldValue("actionsButtonsLayout")
                ?.safeInvoke("showReplyButton", 2, shouldShow, true)
        else
            (chatActivity.getFieldValue("replyButton") as View)
                .visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private companion object {
        const val SELECTION_LIMIT = 100
        const val ACTION_MODE_UPDATE_INTERVAL = 100L
        const val CHAT_ACTIVITY_INNER_PREFIX = "org.telegram.ui.ChatActivity$"
    }
}