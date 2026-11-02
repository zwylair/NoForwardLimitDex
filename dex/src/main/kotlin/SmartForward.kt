package org.zwylair.nfldex

import de.robv.android.xposed.XC_MethodHook
import org.telegram.messenger.AccountInstance
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController

class SmartForwardManager {
    fun setupHook() {
        try {
            hookForward()
            hookNoForwardsChecks()
        } catch (e: Throwable) {
            PluginCore.log("SmartForward.setupHook failed:", exception = e)
        }
    }

    // SendMessagesHelper.sendMessage(messages, peer, forwardFromMyName, hideCaption, notify,
    //     scheduleDate, scheduleRepeatPeriod, replyToTopMsg, videoTimestamp, payStars,
    //     monoForumPeerId, suggestionParams)
    private fun hookForward() {
        val sendMessage = findClass("org.telegram.messenger.SendMessagesHelper")
            ?.findMaxArgsMethod("sendMessage")
            ?: return

        sendMessage.before { param ->
            @Suppress("UNCHECKED_CAST")
            val messages = param.args[0] as? ArrayList<MessageObject> ?: return@before
            if (messages.isEmpty()) return@before

            val (restricted, forwardable) = messages.partition { it.isForwardRestricted() }
            if (restricted.isEmpty()) return@before

            val target = ForwardTarget(
                peer = param.args[1] as Long,
                notify = param.args[4] as Boolean,
                scheduleDate = param.args[5] as Int,
                replyToTopMsg = param.args[7] as MessageObject?,
            )
            val account = AccountInstance.getInstance(restricted.first().currentAccount)

            ForwardCopier.send(account, restricted, target)

            if (forwardable.isEmpty()) {
                param.result = 0
                return@before
            }

            param.args[0] = ArrayList(forwardable)
        }
    }

    private fun hookNoForwardsChecks() {
        findClass("org.telegram.messenger.MessageObject")
            ?.findMethod("canForwardMessage")
            ?.let { hook(it, NoForwardsFlagLifter()) }

        findClass("org.telegram.ui.ChatActivity")
            ?.findMethod("hasSelectedNoforwardsMessage")
            ?.before { it.result = false }

        val controllerClazz = findClass("org.telegram.messenger.MessagesController") ?: return

        NO_FORWARDS_CHECKS
            .flatMap { controllerClazz.findMethods(it) }
            .forEach { method -> method.before { it.result = false } }
    }

    private class NoForwardsFlagLifter : XC_MethodHook() {
        private val lifted = ThreadLocal<Boolean>()

        override fun beforeHookedMethod(param: MethodHookParam) {
            val owner = (param.thisObject as? MessageObject)?.messageOwner ?: return
            if (!owner.noforwards) return

            owner.noforwards = false
            lifted.set(true)
        }

        override fun afterHookedMethod(param: MethodHookParam) {
            if (lifted.get() != true) return

            lifted.remove()
            (param.thisObject as? MessageObject)?.messageOwner?.noforwards = true
        }
    }

    private fun MessageObject.isForwardRestricted(): Boolean {
        if (isSecret || isSecretMedia || isVoiceOnce || isRoundOnce) return true
        if (messageOwner?.noforwards == true || messageOwner?.ttl != 0) return true

        val controller = MessagesController.getInstance(currentAccount)
        val dialogId = dialogId

        if (dialogId < 0)
            return controller.getChat(-dialogId)?.noforwards == true

        val userFull = controller.getUserFull(dialogId) ?: return false
        return userFull.noforwards_my_enabled || userFull.noforwards_peer_enabled
    }

    private companion object {
        val NO_FORWARDS_CHECKS = listOf("isChatNoForwards", "isUserNoForwards", "isPeerNoForwards")
    }
}