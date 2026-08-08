package org.zwylair.nfldex

import android.util.SparseArray
import android.view.View
import com.exteragram.messenger.utils.AppUtils
import de.robv.android.xposed.XC_MethodHook
import org.telegram.messenger.BuildVars
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import kotlin.math.roundToInt

class SelectorManager {
    private val isNewButtonsLayout = AppUtils.compareVersions(">=",BuildVars.BUILD_VERSION_STRING, "12.2.0")

    private var realMessagesArray: Any? = null
    private var fakeMessagesArray: Any? = null
    private var scrollStartTime = 0L
    private var lastScrollTime = 0L
    private var multiSelectorHookApplied = false

    private var isProcessingMsgGroup = false
    private var isLastInGroup = false
    private var handleEndOfSelectMsgGroupCall = false

    fun setupHook(verified: Boolean) {
        try {
            realMessagesArray = findClass("org.telegram.ui.ChatActivity\$SparseArrayWithTouch")!!
                .getDeclaredConstructor().newInstance()

            val chatActivityClazz = findClass("org.telegram.ui.ChatActivity")!!
            val selectorMethod = chatActivityClazz.findMethod("addToSelectedMessages", 3)!!

            hook(selectorMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (!verified) burnCpu(25_000)

                        val chatActivity = param.thisObject
                        val selectedMsgObj = param.args[0] as MessageObject
                        val tappedOutside = param.args[1] as Boolean
                        val isLast = param.args[2] as Boolean
                        val isGrouped = selectedMsgObj.groupId != 0L

                        @Suppress("UNCHECKED_CAST")
                        val javaMsgsSparse = chatActivity.getFieldValue("selectedMessagesIds") as Array<Any?>

                        fakeMessagesArray = fakeMessagesArray ?: javaMsgsSparse[0]

                        if (isProcessingMsgGroup) {
                            isLastInGroup = isLast
                            return
                        }

                        if (tappedOutside && isLast && isGrouped)
                            isProcessingMsgGroup = true

                        javaMsgsSparse[0] = fakeMessagesArray
                    } catch (e: Exception) {
                        PluginCore.log("SelectorHook.before failed:", exception = e)
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (!verified) burnCpu(30_000)

                        if (handleEndOfSelectMsgGroupCall) {
                            handleEndOfSelectMsgGroupCall = false
                            return
                        }

                        if (isProcessingMsgGroup) {
                            if (!isLastInGroup) return
                            isProcessingMsgGroup = false
                            isLastInGroup = false
                            handleEndOfSelectMsgGroupCall = true
                        }

                        val chatActivity = param.thisObject
                        @Suppress("UNCHECKED_CAST")
                        val javaMsgsSparse = chatActivity.getFieldValue("selectedMessagesIds") as Array<Any?>
                        @Suppress("UNCHECKED_CAST")
                        val fakeMessagesArray = fakeMessagesArray as SparseArray<MessageObject>
                        @Suppress("UNCHECKED_CAST")
                        val realMessagesArray = realMessagesArray as SparseArray<MessageObject>

                        val chatMode = chatActivity.getFieldValue("chatMode") as Int?
                        val currentChat = chatActivity.getFieldValue("currentChat") as TLRPC.Chat?
                        val isInScheduleMode = chatMode == 1

                        for (i in fakeMessagesArray.size() - 1 downTo 0) {
                            val messageId = fakeMessagesArray.keyAt(i)
                            val messageObject = fakeMessagesArray.valueAt(i)

                            if (realMessagesArray.indexOfKey(messageId) >= 0) {
                                realMessagesArray.remove(messageId)

                                if (!messageObject.canForwardMessage())
                                    decrementCantForwardCount(chatActivity)

                                if (!messageObject.canDeleteMessage(isInScheduleMode, currentChat))
                                    decrementCantDeleteCount(chatActivity)
                            } else {
                                realMessagesArray.put(messageId, messageObject)
                            }
                        }

                        if (realMessagesArray.size() == 0) {
                            cancelSelectionMode(chatActivity)
                            return
                        }

                        fakeMessagesArray.clear()
                        javaMsgsSparse[0] = realMessagesArray

                        updateReplyButtonVisibility(chatActivity, realMessagesArray.size() == 1)
                        updateSelectButtonVisibility(chatActivity)
                    } catch (e: Exception) {
                        PluginCore.log("SelectorHook.after failed:", exception = e)
                    }
                }
            })

            chatActivityClazz
                .findMethod("hideActionMode")!!
                .before { clearMessageStorage() }

            findClass("org.telegram.ui.Components.RecyclerListView")!!
                .findMethod("startMultiselect", 3)!!
                .before { multiSelectParam ->
                    if (multiSelectorHookApplied)
                        return@before

                    val multiSelectionListener = multiSelectParam.args[2]

                    multiSelectionListener.findMethod("limitReached")!!
                        .before { it.result = false }

                    multiSelectionListener.findMethod("scrollBy", 1)!!
                        .before {
                            val now = System.currentTimeMillis()

                            if (now - lastScrollTime > 100)
                                scrollStartTime = now
                            lastScrollTime = now

                            val originalDy = it.args[0] as Int
                            val duration = now - scrollStartTime

                            val factor = 1.0f + (duration / 2_000f).coerceAtMost(2.5f)
                            it.args[0] = (originalDy * factor).roundToInt()
                        }

                    multiSelectorHookApplied = true
                }
        } catch (e: Throwable) {
            PluginCore.log("SelectorHook.setupHook failed:", exception = e)
        }
    }

    private fun updateReplyButtonVisibility(chatActivity: Any, shouldShow: Boolean) {
        if (isNewButtonsLayout)
            chatActivity.getFieldValue("actionsButtonsLayout")
                ?.safeInvoke("showReplyButton", 2, shouldShow, true)
        else
            (chatActivity.getFieldValue("replyButton") as View)
                .visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun updateSelectButtonVisibility(chatActivity: Any) {
        try {
            @Suppress("UNCHECKED_CAST")
            val messages = chatActivity.getFieldValue("messages") as ArrayList<MessageObject>

            @Suppress("UNCHECKED_CAST")
            val javaMsgsMain =
                (chatActivity.getFieldValue("selectedMessagesIds") as Array<SparseArray<MessageObject>>)[0]

            var shouldShow = false
            if (javaMsgsMain.size() > 1) {
                val begin = javaMsgsMain.keyAt(0)
                val end = javaMsgsMain.keyAt(javaMsgsMain.size() - 1)

                for (message in messages) {
                    val msgId = message.messageOwner.id

                    if (msgId <= 0) continue
                    if (msgId >= end) continue
                    if (msgId <= begin) break

                    if (javaMsgsMain.indexOfKey(msgId) < 0) {
                        val contentType = message.getFieldValue("contentType") as Int
                        if (contentType == 0) {
                            shouldShow = true
                            break
                        }
                    }
                }
            }

            if (isNewButtonsLayout) {
                chatActivity.getFieldValue("actionsButtonsLayout")
                    ?.safeInvoke("showSelectButton", 2, shouldShow, true)
            } else {
                chatActivity.getFieldValue("selectButtonAnimation")?.safeInvoke("cancel")

                (chatActivity.getFieldValue("selectButton") as View).apply {
                    visibility = if (shouldShow) View.VISIBLE else View.GONE
                    alpha = if (shouldShow) 0.0f else 1.0f
                    scaleY = if (shouldShow) 0.0f else 1.0f
                }
            }
        } catch (e: Throwable) {
            PluginCore.log("Error in updateSelectButtonVisibility:", exception = e)
        }
    }

    private fun cancelSelectionMode(chatActivity: Any) {
        chatActivity.safeInvoke("hideActionMode")
        chatActivity.safeInvoke("updatePinnedMessageView", 1, true)
    }

    private fun decrementCantForwardCount(chatActivity: Any) {
        val count = (chatActivity.getFieldValue("cantForwardMessagesCount") as Int) - 2
        chatActivity.setFieldValue("cantForwardMessagesCount", count)

        if (count == 0) {
            val forwardButton = chatActivity.getFieldValue("forwardButton") as View
            forwardButton.apply {
                isEnabled = true
                alpha = 1.0f
            }
        }
    }

    private fun decrementCantDeleteCount(chatActivity: Any) {
        val count = (chatActivity.getFieldValue("cantDeleteMessagesCount") as Int) - 2
        chatActivity.setFieldValue("cantDeleteMessagesCount", count)

        if (count == 0) {
            val deleteItem = chatActivity.getFieldValue("deleteItem") as View
            deleteItem.visibility = View.VISIBLE
        }
    }

    fun clearMessageStorage() {
        @Suppress("UNCHECKED_CAST")
        if (realMessagesArray != null)
            (realMessagesArray as SparseArray<*>).clear()
        if (fakeMessagesArray != null)
            (fakeMessagesArray as SparseArray<*>).clear()
    }
}
