package me.rhunk.snapenhance.download

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rhunk.snapenhance.RemoteSideContext
import me.rhunk.snapenhance.bridge.DownloadCallback
import me.rhunk.snapenhance.common.Constants
import me.rhunk.snapenhance.common.ReceiversConfig
import me.rhunk.snapenhance.common.data.FileType
import me.rhunk.snapenhance.common.data.download.*
import me.rhunk.snapenhance.common.util.snap.MediaDownloaderHelper
import me.rhunk.snapenhance.common.util.snap.RemoteMediaResolver
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.coroutines.coroutineContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class DownloadedFile(
    val file: File,
    val fileType: FileType
)

/**
 * DownloadProcessor handles the download requests of the user
 */
@OptIn(ExperimentalEncodingApi::class)
class DownloadProcessor (
    private val remoteSideContext: RemoteSideContext,
    private val callback: DownloadCallback
) {

    private val translation by lazy {
        remoteSideContext.translation.getCategory("download_processor")
    }

    private val ffmpegProcessor by lazy {
        FFMpegProcessor(
            remoteSideContext.log,
            remoteSideContext.config.root.downloader.ffmpegOptions
        )
    }
    private val gson by lazy { GsonBuilder().setPrettyPrinting().create() }

    private fun fallbackToast(message: Any) {
        android.os.Handler(remoteSideContext.androidContext.mainLooper).post {
            Toast.makeText(remoteSideContext.androidContext, message.toString(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun callbackOnSuccess(path: String) = runCatching {
        callback.onSuccess(path)
    }.onFailure {
        fallbackToast(it)
    }

    private fun callbackOnFailure(message: String, throwable: String? = null) = runCatching {
        callback.onFailure(message, throwable)
    }.onFailure {
        fallbackToast("$message\n$throwable")
    }

    private fun callbackOnProgress(message: String) = runCatching {
        callback.onProgress(message)
    }.onFailure {
        fallbackToast(it)
    }

    private fun extractZip(inputStream: InputStream): List<File> {
        val files = mutableListOf<File>()
        val zipInputStream = ZipInputStream(inputStream)
        var entry = zipInputStream.nextEntry

        while (entry != null) {
            createMediaTempFile().also { file ->
                file.outputStream().use { outputStream ->
                    zipInputStream.copyTo(outputStream)
                }
                files += file
            }
            entry = zipInputStream.nextEntry
        }

        return files
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private suspend fun saveMediaToGallery(inputFile: File, downloadObject: DownloadObject) {
        if (coroutineContext.job.isCancelled) return

        runCatching {
            var fileType = FileType.fromFile(inputFile)

            if (fileType == FileType.UNKNOWN) {
                callbackOnFailure(translation.format("failed_gallery_toast", "error" to "Unknown media type"), null)
                return
            }

            if (fileType.isImage) {
                remoteSideContext.config.root.downloader.forceImageFormat.getNullable()?.let { format ->
                    val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath) ?: throw Exception("Failed to decode bitmap")
                    @Suppress("DEPRECATION") val compressFormat = when (format) {
                        "png" -> Bitmap.CompressFormat.PNG
                        "jpg" -> Bitmap.CompressFormat.JPEG
                        "webp" -> Bitmap.CompressFormat.WEBP
                        else -> throw Exception("Invalid image format")
                    }

                    val outputStream = inputFile.outputStream()
                    bitmap.compress(compressFormat, 100, outputStream)
                    outputStream.close()

                    fileType = FileType.fromFile(inputFile)
                }
            }

            val fileName = downloadObject.metadata.outputPath.substringAfterLast("/") + "." + fileType.fileExtension

            val outputFolder = DocumentFile.fromTreeUri(remoteSideContext.androidContext, Uri.parse(remoteSideContext.config.root.downloader.saveFolder.get()))
                ?: throw Exception("Failed to open output folder")

            val outputFileFolder = downloadObject.metadata.outputPath.let {
                if (it.contains("/")) {
                    it.substringBeforeLast("/").split("/").fold(outputFolder) { folder, name ->
                        folder.findFile(name) ?: folder.createDirectory(name)!!
                    }
                } else {
                    outputFolder
                }
            }

            val outputFile = outputFileFolder.createFile(fileType.mimeType, fileName)!!
            val outputStream = remoteSideContext.androidContext.contentResolver.openOutputStream(outputFile.uri)!!

            inputFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }

            downloadObject.outputFile = outputFile.uri.toString()
            downloadObject.downloadStage = DownloadStage.SAVED

            runCatching {
                val mediaScanIntent = Intent("android.intent.action.MEDIA_SCANNER_SCAN_FILE")
                mediaScanIntent.setData(outputFile.uri)
                remoteSideContext.androidContext.sendBroadcast(mediaScanIntent)
            }.onFailure {
                remoteSideContext.log.error("Failed to scan media file", it)
                callbackOnFailure(translation.format("failed_gallery_toast", "error" to it.toString()), it.message)
            }

            remoteSideContext.log.verbose("download complete")
            callbackOnSuccess(fileName)
        }.onFailure { exception ->
            remoteSideContext.log.error("Failed to save media to gallery", exception)
            callbackOnFailure(translation.format("failed_gallery_toast", "error" to exception.toString()), exception.message)
            downloadObject.downloadStage = DownloadStage.FAILED
        }
    }

    private fun createMediaTempFile(): File {
        return File.createTempFile("media", ".tmp")
    }

    private fun downloadInputMedias(downloadRequest: DownloadRequest) = runBlocking {
        val jobs = mutableListOf<Job>()
        val downloadedMedias = mutableMapOf<InputMedia, File>()

        downloadRequest.inputMedias.forEach { inputMedia ->
            fun handleInputStream(inputStream: InputStream) {
                createMediaTempFile().apply {
                    (inputMedia.encryption?.decryptInputStream(inputStream) ?: inputStream).copyTo(outputStream())
                }.also { downloadedMedias[inputMedia] = it }
            }

            launch {
                when (inputMedia.type) {
                    DownloadMediaType.PROTO_MEDIA -> {
                        RemoteMediaResolver.downloadBoltMedia(Base64.UrlSafe.decode(inputMedia.content), decryptionCallback = { it }, resultCallback = {
                            handleInputStream(it)
                        })
                    }
                    DownloadMediaType.DIRECT_MEDIA -> {
                        val decoded = Base64.UrlSafe.decode(inputMedia.content)
                        createMediaTempFile().apply {
                            writeBytes(decoded)
                        }.also { downloadedMedias[inputMedia] = it }
                    }
                    DownloadMediaType.REMOTE_MEDIA -> {
                        with(URL(inputMedia.content).openConnection() as HttpURLConnection) {
                            requestMethod = "GET"
                            setRequestProperty("User-Agent", Constants.USER_AGENT)
                            connect()
                            handleInputStream(inputStream)
                        }
                    }
                    else -> {
                        downloadedMedias[inputMedia] = File(inputMedia.content)
                    }
                }
            }.also { jobs.add(it) }
        }

        jobs.joinAll()
        downloadedMedias
    }

    private suspend fun downloadRemoteMedia(downloadObjectObject: DownloadObject, downloadedMedias: Map<InputMedia, DownloadedFile>, downloadRequest: DownloadRequest) {
        downloadRequest.inputMedias.first().let { inputMedia ->
            val mediaType = inputMedia.type
            val media = downloadedMedias[inputMedia]!!

            if (!downloadRequest.isDashPlaylist) {
                if (inputMedia.attachmentType == "NOTE") {
                    remoteSideContext.config.root.downloader.forceVoiceNoteFormat.getNullable()?.let { format ->
                        val outputFile = File.createTempFile("voice_note", ".$format")
                        ffmpegProcessor.execute(FFMpegProcessor.Request(
                            action = FFMpegProcessor.Action.AUDIO_CONVERSION,
                            input = media.file,
                            output = outputFile
                        ))
                        media.file.delete()
                        saveMediaToGallery(outputFile, downloadObjectObject)
                        outputFile.delete()
                        return
                    }
                }

                saveMediaToGallery(media.file, downloadObjectObject)
                media.file.delete()
                return
            }

            assert(mediaType == DownloadMediaType.REMOTE_MEDIA)

            val playlistXml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(media.file)
            val baseUrlNodeList = playlistXml.getElementsByTagName("BaseURL")
            for (i in 0 until baseUrlNodeList.length) {
                val baseUrlNode = baseUrlNodeList.item(i)
                val baseUrl = baseUrlNode.textContent
                baseUrlNode.textContent = "${RemoteMediaResolver.CF_ST_CDN_D}$baseUrl"
            }

            val dashOptions = downloadRequest.dashOptions!!

            val dashPlaylistFile = renameFromFileType(media.file, FileType.MPD)
            val xmlData = dashPlaylistFile.outputStream()
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(playlistXml), StreamResult(xmlData))

            callbackOnProgress(translation.format("download_toast", "path" to dashPlaylistFile.nameWithoutExtension))
            val outputFile = File.createTempFile("dash", ".mp4")
            runCatching {
                ffmpegProcessor.execute(FFMpegProcessor.Request(
                    action = FFMpegProcessor.Action.DOWNLOAD_DASH,
                    input = dashPlaylistFile,
                    output = outputFile,
                    startTime = dashOptions.offsetTime,
                    duration = dashOptions.duration
                ))
                saveMediaToGallery(outputFile, downloadObjectObject)
            }.onFailure { exception ->
                if (coroutineContext.job.isCancelled) return@onFailure
                remoteSideContext.log.error("Failed to download dash media", exception)
                callbackOnFailure(translation.format("failed_processing_toast", "error" to exception.toString()), exception.message)
                downloadObjectObject.downloadStage = DownloadStage.FAILED
            }

            dashPlaylistFile.delete()
            outputFile.delete()
            media.file.delete()
        }
    }

    private fun renameFromFileType(file: File, fileType: FileType): File {
        val newFile = File(file.parentFile, file.nameWithoutExtension + "." + fileType.fileExtension)
        file.renameTo(newFile)
        return newFile
    }

    fun onReceive(intent: Intent) {
        remoteSideContext.coroutineScope.launch {
            val downloadMetadata = gson.fromJson(intent.getStringExtra(ReceiversConfig.DOWNLOAD_METADATA_EXTRA)!!, DownloadMetadata::class.java)
            val downloadRequest = gson.fromJson(intent.getStringExtra(ReceiversConfig.DOWNLOAD_REQUEST_EXTRA)!!, DownloadRequest::class.java)

            remoteSideContext.downloadTaskManager.canDownloadMedia(downloadMetadata.mediaIdentifier)?.let { downloadStage ->
                translation[if (downloadStage.isFinalStage) {
                    "already_downloaded_toast"
                } else {
                    "already_queued_toast"
                }].let {
                    callbackOnFailure(it, null)
                }
                return@launch
            }

            val downloadObjectObject = DownloadObject(
                metadata = downloadMetadata
            ).apply {
                updateTaskCallback = {
                    remoteSideContext.downloadTaskManager.updateTask(it)
                }
            }

            downloadObjectObject.also {
                remoteSideContext.downloadTaskManager.addTask(it)
            }.apply {
                job = coroutineContext.job
                downloadStage = DownloadStage.DOWNLOADING
            }

            runCatching {
                //first download all input medias into cache
                val downloadedMedias = downloadInputMedias(downloadRequest).map {
                    it.key to DownloadedFile(it.value, FileType.fromFile(it.value))
                }.toMap().toMutableMap()
                remoteSideContext.log.verbose("downloaded ${downloadedMedias.size} medias")

                var shouldMergeOverlay = downloadRequest.shouldMergeOverlay

                //if there is a zip file, extract it and replace the downloaded media with the extracted ones
                downloadedMedias.values.find { it.fileType == FileType.ZIP }?.let { zipFile ->
                    val oldDownloadedMedias = downloadedMedias.toMap()
                    downloadedMedias.clear()

                    MediaDownloaderHelper.getSplitElements(zipFile.file.inputStream()) { type, inputStream ->
                        createMediaTempFile().apply {
                            inputStream.copyTo(outputStream())
                        }.also {
                            downloadedMedias[InputMedia(
                                type = DownloadMediaType.LOCAL_MEDIA,
                                content = it.absolutePath,
                                isOverlay = type == SplitMediaAssetType.OVERLAY
                            )] = DownloadedFile(it, FileType.fromFile(it))
                        }
                    }

                    oldDownloadedMedias.forEach { (_, value) ->
                        value.file.delete()
                    }

                    shouldMergeOverlay = true
                }

                if (shouldMergeOverlay) {
                    assert(downloadedMedias.size == 2)
                    //TODO: convert "mp4 images" into real images
                    val media = downloadedMedias.entries.first { !it.key.isOverlay }.value
                    val overlayMedia = downloadedMedias.entries.first { it.key.isOverlay }.value

                    val renamedMedia = renameFromFileType(media.file, media.fileType)
                    val renamedOverlayMedia = renameFromFileType(overlayMedia.file, overlayMedia.fileType)
                    val mergedOverlay: File = File.createTempFile("merged", ".mp4")
                    runCatching {
                        callbackOnProgress(translation.format("processing_toast", "path" to media.file.nameWithoutExtension))
                        downloadObjectObject.downloadStage = DownloadStage.MERGING

                        ffmpegProcessor.execute(FFMpegProcessor.Request(
                            action = FFMpegProcessor.Action.MERGE_OVERLAY,
                            input = renamedMedia,
                            output = mergedOverlay,
                            overlay = renamedOverlayMedia
                        ))

                        saveMediaToGallery(mergedOverlay, downloadObjectObject)
                    }.onFailure { exception ->
                        if (coroutineContext.job.isCancelled) return@onFailure
                        remoteSideContext.log.error("Failed to merge overlay", exception)
                        callbackOnFailure(translation.format("failed_processing_toast", "error" to exception.toString()), exception.message)
                        downloadObjectObject.downloadStage = DownloadStage.MERGE_FAILED
                    }

                    mergedOverlay.delete()
                    renamedOverlayMedia.delete()
                    renamedMedia.delete()
                    return@launch
                }

                downloadRemoteMedia(downloadObjectObject, downloadedMedias, downloadRequest)
            }.onFailure { exception ->
                downloadObjectObject.downloadStage = DownloadStage.FAILED
                remoteSideContext.log.error("Failed to download media", exception)
                callbackOnFailure(translation["failed_generic_toast"], exception.message)
            }
        }
    }
}