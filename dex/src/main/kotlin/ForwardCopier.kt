package org.zwylair.nfldex

import org.telegram.messenger.AccountInstance
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLoader
import org.telegram.messenger.ImageLocation
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SendMessagesHelper
import org.telegram.messenger.SendMessagesHelper.SendMessageParams
import org.telegram.messenger.SendMessagesHelper.SendingMediaInfo
import org.telegram.messenger.Utilities
import org.telegram.messenger.VideoEditedInfo
import org.telegram.tgnet.TLObject
import org.telegram.tgnet.TLRPC
import org.telegram.ui.ActionBar.AlertDialog
import org.telegram.ui.LaunchActivity
import java.io.File
import java.io.RandomAccessFile

data class ForwardTarget(
    val peer: Long,
    val notify: Boolean,
    val scheduleDate: Int,
    val replyToTopMsg: MessageObject?,
)

object ForwardCopier {
    fun send(account: AccountInstance, messages: List<MessageObject>, target: ForwardTarget) {
        if (messages.isEmpty()) return

        val batches = messages
            .groupBy<MessageObject, Any> { msg -> msg.groupId.takeIf { it != 0L } ?: "single_${msg.id}" }
            .values
            .toList()

        sendSequentially(account, batches, target, 0)
    }

    private fun sendSequentially(
        account: AccountInstance,
        batches: List<List<MessageObject>>,
        target: ForwardTarget,
        index: Int,
    ) {
        val batch = batches.getOrNull(index) ?: return

        ensureMediaReady(account, batch) {
            try {
                sendBatch(account, batch, target)
            } catch (e: Throwable) {
                PluginCore.log("ForwardCopier: failed to send batch #$index:", exception = e)
            }

            sendSequentially(account, batches, target, index + 1)
        }
    }

    // region sending

    private fun sendBatch(account: AccountInstance, batch: List<MessageObject>, target: ForwardTarget) {
        val (visualMedia, others) = batch.partition { it.messageOwner.isPhoto() || it.messageOwner.isVideo() }

        others.forEach { sendSingle(account, it, target) }

        val mediaInfos = visualMedia.mapNotNull { msg ->
            val owner = msg.messageOwner
            val file = resolveMediaFile(account, owner) ?: return@mapNotNull null

            SendingMediaInfo().apply {
                path = file.absolutePath
                isVideo = owner.isVideo()

                if (!owner.message.isNullOrEmpty()) {
                    caption = owner.message
                    entities = owner.entities
                }
            }
        }

        if (mediaInfos.isEmpty()) return

        SendMessagesHelper.prepareSendingMedia(
            account, ArrayList(mediaInfos), target.peer,
            target.replyToTopMsg, target.replyToTopMsg, null, null,
            false, mediaInfos.size > 1, null,
            target.notify, target.scheduleDate, 0, 0, false,
            null, null, 0L, false, 0L, 0L, null,
        )
    }

    private fun sendSingle(account: AccountInstance, msg: MessageObject, target: ForwardTarget) {
        val owner = msg.messageOwner
        val helper = account.sendMessagesHelper
        val replyTo = target.replyToTopMsg

        when (val media = owner.media) {
            is TLRPC.TL_messageMediaGeo,
            is TLRPC.TL_messageMediaVenue -> helper.sendMessage(
                SendMessageParams.of(media, target.peer, replyTo, replyTo, null, null, target.notify, target.scheduleDate, 0)
            )

            is TLRPC.TL_messageMediaPoll -> helper.sendMessage(
                SendMessageParams.of(media, target.peer, replyTo, replyTo, null, null, target.notify, target.scheduleDate, 0)
            )

            is TLRPC.TL_messageMediaContact -> {
                val user = TLRPC.TL_user().apply {
                    id = media.user_id
                    first_name = media.first_name
                    last_name = media.last_name
                    phone = media.phone_number
                }

                helper.sendMessage(
                    SendMessageParams.of(user, target.peer, replyTo, replyTo, null, null, target.notify, target.scheduleDate, 0)
                )
            }

            is TLRPC.TL_messageMediaDocument -> sendDocument(account, msg, target)

            else -> sendText(account, owner, target)
        }
    }

    private fun sendText(account: AccountInstance, owner: TLRPC.Message, target: ForwardTarget) {
        val text = owner.message
        if (text.isNullOrEmpty()) return

        account.sendMessagesHelper.sendMessage(
            SendMessageParams.of(
                text, target.peer, target.replyToTopMsg, target.replyToTopMsg, null, true,
                owner.entities, null, null, target.notify, target.scheduleDate, 0, null, false,
            )
        )
    }

    private fun sendDocument(account: AccountInstance, msg: MessageObject, target: ForwardTarget) {
        val owner = msg.messageOwner
        val document = owner.media?.document ?: return sendText(account, owner, target)
        val replyTo = target.replyToTopMsg

        if (MessageObject.isStickerMessage(owner) || MessageObject.isAnimatedStickerMessage(owner) || MessageObject.isVideoSticker(document)) {
            account.sendMessagesHelper.sendSticker(
                document, null, target.peer, replyTo, replyTo, null, null, null,
                target.notify, target.scheduleDate, 0, false, msg, null, 0L, 0L, null,
            )
            return
        }

        val file = resolveMediaFile(account, owner)
        if (file == null) {
            PluginCore.log("ForwardCopier: document file not found for ${document.id}")
            return
        }

        val path = file.absolutePath

        when {
            MessageObject.isVoiceMessage(owner) -> {
                val tlDocument = document as? TLRPC.TL_document ?: return

                account.sendMessagesHelper.sendMessage(
                    SendMessageParams.of(
                        tlDocument, null, path, target.peer, replyTo, replyTo,
                        owner.message, owner.entities, null, null,
                        target.notify, target.scheduleDate, 0, 0, msg, null, false, false,
                    )
                )
            }

            MessageObject.isRoundVideoMessage(owner) -> SendMessagesHelper.prepareSendingVideo(
                account, path, buildRoundVideoInfo(document, path), null, null, target.peer,
                replyTo, replyTo, null, null, null, 0, null,
                target.notify, target.scheduleDate, 0, false, false, null, null, 0L, 0L,
            )

            else -> SendMessagesHelper.prepareSendingDocument(
                account, path, path, null, owner.message, null, target.peer,
                replyTo, replyTo, null, null, null,
                target.notify, target.scheduleDate, null, null, false,
            )
        }
    }

    private fun buildRoundVideoInfo(document: TLRPC.Document, path: String): VideoEditedInfo {
        val videoAttribute = document.attributes.firstOrNull { it is TLRPC.TL_documentAttributeVideo }
        val width = videoAttribute?.w ?: -1
        val height = videoAttribute?.h ?: -1

        return VideoEditedInfo().apply {
            roundVideo = true
            startTime = -1L
            endTime = -1L
            bitrate = -1
            originalPath = path
            estimatedSize = 0L
            estimatedDuration = videoAttribute?.duration?.toLong() ?: -1L
            resultWidth = width
            resultHeight = height
            originalWidth = width
            originalHeight = height
        }
    }

    // endregion

    // region media files

    private fun ensureMediaReady(account: AccountInstance, batch: List<MessageObject>, onReady: () -> Unit) {
        val missing = batch.filter { msg ->
            msg.messageOwner.hasDownloadableMedia() && resolveMediaFile(account, msg.messageOwner) == null
        }

        if (missing.isEmpty()) {
            onReady()
            return
        }

        MediaDownloader(account, missing, onReady).start()
    }

    private fun resolveMediaFile(account: AccountInstance, message: TLRPC.Message): File? {
        val attach = message.attachObject() ?: return null
        val fileLoader = FileLoader.getInstance(account.currentAccount)

        fileLoader.getPathToAttach(attach, false).takeIfNotEmpty()?.let { return it }
        fileLoader.getPathToAttach(attach, true).takeIfNotEmpty()?.let { return it }

        val fileName = FileLoader.getAttachFileName(attach)
        val cacheDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE)
        val imageDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_IMAGE)

        File(cacheDir, "$fileName.enc").takeIfNotEmpty()
            ?.let { decryptCacheFile(it, fileName) }
            ?.takeIfNotEmpty()
            ?.let { return it }

        File(imageDir, fileName).takeIfNotEmpty()?.let { return it }

        return File(imageDir, "$fileName.enc").takeIfNotEmpty()
            ?.let { decryptCacheFile(it, fileName) }
            ?.takeIfNotEmpty()
    }

    // Telegram's encrypted cache: <name>.enc + <name>.enc.key (32-byte key, 16-byte IV), AES-CTR.
    private fun decryptCacheFile(encFile: File, outputFileName: String): File? = runCatching {
        val keyFile = File(FileLoader.getInternalCacheDir(), "${encFile.name}.key")
        if (!keyFile.exists()) {
            PluginCore.log("ForwardCopier: key file not found: ${keyFile.name}")
            return null
        }

        val key = ByteArray(32)
        val iv = ByteArray(16)

        RandomAccessFile(keyFile, "r").use { raf ->
            if (raf.length() < key.size + iv.size) return null

            raf.readFully(key)
            raf.readFully(iv)
        }

        val data = encFile.readBytes()
        val counterIv = iv.copyOf().apply { fill(0, 12, 16) }
        Utilities.aesCtrDecryptionByteArray(data, key, counterIv, 0, data.size.toLong(), 0)

        File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "forward_$outputFileName")
            .apply { writeBytes(data) }
    }.onFailure {
        PluginCore.log("ForwardCopier: decrypt error:", exception = it)
    }.getOrNull()

    private fun File?.takeIfNotEmpty(): File? = this?.takeIf { it.exists() && it.length() > 0 }

    private fun TLRPC.Message.isPhoto() = media is TLRPC.TL_messageMediaPhoto

    private fun TLRPC.Message.isVideo(): Boolean {
        val document = (media as? TLRPC.TL_messageMediaDocument)?.document ?: return false
        return MessageObject.isVideoDocument(document) && !MessageObject.isVideoSticker(document)
    }

    private fun TLRPC.Message.hasDownloadableMedia() =
        media is TLRPC.TL_messageMediaPhoto || media is TLRPC.TL_messageMediaDocument

    private fun TLRPC.Message.photoSize(): TLRPC.PhotoSize? {
        val photo = (media as? TLRPC.TL_messageMediaPhoto)?.photo ?: return null
        return FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize())
    }

    private fun TLRPC.Message.attachObject(): TLObject? = when (val media = media) {
        is TLRPC.TL_messageMediaPhoto -> photoSize()
        is TLRPC.TL_messageMediaDocument -> media.document
        else -> null
    }

    // endregion

    private class MediaDownloader(
        account: AccountInstance,
        messages: List<MessageObject>,
        private val onReady: () -> Unit,
    ) : NotificationCenter.NotificationCenterDelegate {
        private val pending: MutableMap<String, MessageObject> = messages
            .mapNotNull { msg -> msg.messageOwner.attachObject()?.let { FileLoader.getAttachFileName(it) to msg } }
            .toMap(LinkedHashMap())

        private val total = pending.size
        private val notificationCenter = NotificationCenter.getInstance(account.currentAccount)
        private val fileLoader = FileLoader.getInstance(account.currentAccount)
        private var dialog: AlertDialog? = null

        fun start() {
            if (pending.isEmpty()) {
                onReady()
                return
            }

            OBSERVED_EVENTS.forEach { notificationCenter.addObserver(this, it) }
            pending.values.forEach(::startLoading)
        }

        override fun didReceivedNotification(id: Int, account: Int, vararg args: Any?) {
            val fileName = args.getOrNull(0) as? String ?: return
            if (fileName !in pending) return

            when (id) {
                NotificationCenter.fileLoadProgressChanged -> {
                    val loaded = args.getOrNull(1) as? Long ?: return
                    val size = args.getOrNull(2) as? Long ?: return
                    if (size <= 0) return

                    updateProgress((loaded * 100 / size).toInt())
                }

                NotificationCenter.fileLoaded, NotificationCenter.fileLoadFailed -> {
                    pending.remove(fileName)
                    if (pending.isNotEmpty()) return

                    release()
                    AndroidUtilities.runOnUIThread { onReady() }
                }
            }
        }

        private fun startLoading(msg: MessageObject) {
            val owner = msg.messageOwner

            when (val media = owner.media) {
                is TLRPC.TL_messageMediaDocument ->
                    fileLoader.loadFile(media.document, msg, FileLoader.PRIORITY_HIGH, 0)

                is TLRPC.TL_messageMediaPhoto -> {
                    val size = owner.photoSize() ?: return
                    fileLoader.loadFile(ImageLocation.getForPhoto(size, media.photo), msg, null, FileLoader.PRIORITY_HIGH, 0)
                }
            }
        }

        private fun updateProgress(fileProgress: Int) {
            val done = total - pending.size
            val overall = (done * 100 + fileProgress) / total

            dialog?.let {
                it.setProgress(overall)
                return
            }

            val context = LaunchActivity.getLastFragment()?.parentActivity ?: return

            dialog = runCatching {
                AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING).apply {
                    setMessage(LocaleController.getString(R.string.Loading))
                    setProgress(overall)
                    setCanCancel(false)
                    setCancelDialog(false)
                    setCanceledOnTouchOutside(false)
                    setCancelable(false)
                    setNegativeButton(LocaleController.getString(R.string.Cancel)) { _, _ -> cancel() }
                    show()
                }
            }.onFailure {
                PluginCore.log("ForwardCopier: failed to show progress dialog:", exception = it)
            }.getOrNull()
        }

        private fun cancel() {
            pending.values.forEach { msg ->
                val owner = msg.messageOwner

                when (val media = owner.media) {
                    is TLRPC.TL_messageMediaDocument -> fileLoader.cancelLoadFile(media.document, false)
                    is TLRPC.TL_messageMediaPhoto -> owner.photoSize()?.let { fileLoader.cancelLoadFile(it, false) }
                }
            }

            pending.clear()
            release()
        }

        private fun release() {
            OBSERVED_EVENTS.forEach { notificationCenter.removeObserver(this, it) }

            runCatching { dialog?.dismiss() }
            dialog = null
        }

        private companion object {
            val OBSERVED_EVENTS = intArrayOf(
                NotificationCenter.fileLoaded,
                NotificationCenter.fileLoadFailed,
                NotificationCenter.fileLoadProgressChanged,
            )
        }
    }
}