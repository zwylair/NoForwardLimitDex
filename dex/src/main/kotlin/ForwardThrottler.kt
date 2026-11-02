package org.zwylair.nfldex

import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.MessageObject
import java.lang.reflect.Method

class ForwardThrottler {
    fun setupHook() {
        try {
            val sendMessage = findClass("org.telegram.messenger.SendMessagesHelper")
                ?.findMethod("sendMessage", FORWARD_ARGS_COUNT)
                ?: return

            sendMessage.before { param ->
                @Suppress("UNCHECKED_CAST")
                val messages = param.args[0] as? ArrayList<MessageObject> ?: return@before
                if (messages.size <= BATCH_SIZE) return@before

                param.args[0] = ArrayList(messages.subList(0, BATCH_SIZE))

                val restArgs = param.args.clone()
                restArgs[0] = ArrayList(messages.subList(BATCH_SIZE, messages.size))
                scheduleRest(sendMessage, param.thisObject, restArgs)
            }
        } catch (e: Throwable) {
            PluginCore.log("ForwardThrottler.setupHook failed:", exception = e)
        }
    }

    private fun scheduleRest(sendMessage: Method, helper: Any, args: Array<Any?>) {
        AndroidUtilities.runOnUIThread({
            try {
                sendMessage.invoke(helper, *args)
            } catch (e: Throwable) {
                PluginCore.log("ForwardThrottler: failed to send the next batch:", exception = e)
            }
        }, BATCH_DELAY_MS)
    }

    private companion object {
        const val FORWARD_ARGS_COUNT = 12
        const val BATCH_SIZE = 100
        const val BATCH_DELAY_MS = 3_000L
    }
}
