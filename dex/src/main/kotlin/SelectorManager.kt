package org.zwylair.nfldex

import android.util.SparseArray
import android.view.View
import com.exteragram.messenger.utils.AppUtils
import de.robv.android.xposed.XC_MethodHook
import org.telegram.messenger.BuildVars
import org.telegram.messenger.MessageObject
import org.telegram.tgnet.TLRPC
import java.lang.reflect.Field

class SelectorManager {
    private var messagesArray: Any? = null
    private val isNewButtonsLayout = AppUtils.compareVersions(">=",BuildVars.BUILD_VERSION_STRING, "12.2.0")

    private var isProcessingMsgGroup = false
    private var isLastInGroup = false
    private var handleEndOfSelectMsgGroupCall = false

    private var selectedMessagesIdsField: Field? = null
    private var chatModeField: Field? = null
    private var currentChatField: Field? = null

    private var originalSparseArray: Any? = null

    fun setupHook(verified: Boolean) {
        messagesArray = Class.forName("org.telegram.ui.ChatActivity\$SparseArrayWithTouch")
            .getDeclaredConstructor().newInstance()

        hook("org.telegram.ui.ChatActivity", "addToSelectedMessages", 3, object : XC_MethodHook() {
            val doBurn = !verified

            override fun beforeHookedMethod(param: MethodHookParam) {
                if (doBurn) burnCpu(25_000)

                val chatActivity = param.thisObject
                val selectedMsgObj = param.args[0] as MessageObject
                val tappedOutside = param.args[1] as Boolean
                val isLast = param.args[2] as Boolean
                val isGrouped = selectedMsgObj.groupId != 0L
                @Suppress("UNCHECKED_CAST")
                val javaMsgsSparse = chatActivity.getFieldValue("selectedMessagesIds") as Array<Any?>

                originalSparseArray = originalSparseArray?: javaMsgsSparse[0]

                if (selectedMessagesIdsField == null) {
                    selectedMessagesIdsField = chatActivity.findField("selectedMessagesIds")
                    chatModeField = chatActivity.findField("chatMode")
                    currentChatField = chatActivity.findField("currentChat")
                }

                if (isProcessingMsgGroup) {
                    isLastInGroup = isLast
                    return
                }

                if (tappedOutside && isLast && isGrouped)
                    isProcessingMsgGroup = true

                javaMsgsSparse[0] = originalSparseArray
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    if (doBurn) burnCpu(30_000)

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
                    val fakeMessagesArray = javaMsgsSparse[0] as SparseArray<MessageObject>
                    @Suppress("UNCHECKED_CAST")
                    val realMessagesArray = messagesArray as SparseArray<MessageObject>

                    val chatMode = chatActivity.getFieldValue("chatMode") as Int
                    val currentChat = chatActivity.getFieldValue("currentChat") as TLRPC.Chat
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
                    PluginCore.jLog("SelectorHook (after) failed:", exception=e)
                }
            }
        })

        hook("org.telegram.ui.ChatActivity", "hideActionMode", 0, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = clearMessageStorage()
        })
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
            PluginCore.jLog("Error in updateSelectButtonVisibility:", exception = e)
        }
    }

    private fun cancelSelectionMode(chatActivity: Any) {
        try {
            chatActivity.safeInvoke("hideActionMode")
            chatActivity.safeInvoke("updatePinnedMessageView", 1, true)
        } catch (e: Exception) {
            PluginCore.jLog("Error in cancelSelectionMode:", exception=e)
        }
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
        if (messagesArray != null)
            (messagesArray as SparseArray<*>).clear()
        if (originalSparseArray != null)
            (originalSparseArray as SparseArray<*>).clear()
    }
}
