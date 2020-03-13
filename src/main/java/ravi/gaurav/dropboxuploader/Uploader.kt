package ravi.gaurav.dropboxuploader

import com.dropbox.core.DbxException
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.NetworkIOException
import com.dropbox.core.RetryException
import com.dropbox.core.util.IOUtil
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.DbxPathV2
import com.dropbox.core.v2.files.*
import com.dropbox.core.v2.sharing.RequestedLinkAccessLevel
import com.dropbox.core.v2.sharing.RequestedVisibility
import com.dropbox.core.v2.sharing.SharedLinkSettings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.system.exitProcess


class Uploader(
        private val uploadPath: String?,
        private val dbxFilePath: String? = "/ai",
        private val fileName: String? = null
) {

    // Adjust the chunk size based on your network speed and reliability. Larger chunk sizes will
    // result in fewer network requests, which will be faster. But if an error occurs, the entire
    // chunk will be lost and have to be re-uploaded. Use a multiple of 4MiB for your chunk size.
    private val CHUNKED_UPLOAD_CHUNK_SIZE = 8L shl 20 // 8MiB

    private val CHUNKED_UPLOAD_MAX_ATTEMPTS = 5

    private var tempFile = false

    fun upload() {
        uploadPath ?: throw RuntimeException("File to be uploaded cannot be null")
        val dbxAuthInfo = Authorization().authorize()
        val localFile = zipIfRequired(uploadPath)
        val dbxFileName = fileName?.let {
            return@let if (fileName.endsWith(localFile.extension)) {
                fileName
            } else {
                "${fileName}.${localFile.extension}"
            }
        } ?: localFile.name

        val dropBoxPath = "$dbxFilePath" +
                if (dbxFilePath?.endsWith("/") == true) {
                    dbxFileName
                } else {
                    "/$dbxFileName"
                }
        val pathError = DbxPathV2.findError(dropBoxPath)
        validateFiles(pathError, localFile)

        // Create a DbxClientV2, which is what you use to make API calls.
        val requestConfig = DbxRequestConfig("aiUploader")
        val dbxClient = DbxClientV2(requestConfig, dbxAuthInfo.accessToken, dbxAuthInfo.host)

        val fullAccount = dbxClient.users().currentAccount
        println("\n${fullAccount.name.displayName}\n")

        // upload the file with simple upload API if it is small enough, otherwise use chunked
        // upload API for better performance. Arbitrarily chose 2 times our chunk size as the
        // deciding factor. This should really depend on your network.
        val metadata = if (localFile.length() <= 2 * CHUNKED_UPLOAD_CHUNK_SIZE) {
            uploadFile(dbxClient, localFile, dropBoxPath)
        } else {
            chunkedUploadFile(dbxClient, localFile, dropBoxPath)
        }

        val sharedUrl: String? = getSharableLink(dbxClient, metadata)

        if (tempFile) {
            localFile.delete()
            tempFile = false
        }

        println()
        println()
        println("The file has been uploaded... ")
        println("Share the link below ::")
        println("")
        println("")
        println("    $sharedUrl")
        println()
        println()
    }

    private fun getSharableLink(dbxClient: DbxClientV2, metadata: FileMetadata): String? {
        return try {
            val sharedLinkMetadata = dbxClient.sharing()
                    .createSharedLinkWithSettings(
                            metadata.pathLower,
                            SharedLinkSettings.newBuilder()
                                    .withRequestedVisibility(RequestedVisibility.PUBLIC)
                                    .withAccess(RequestedLinkAccessLevel.MAX)
                                    .build()
                    )
            sharedLinkMetadata.url
        } catch (e: Exception) {
            try {
                val listSharedLinkResult = dbxClient.sharing()
                        .listSharedLinksBuilder().withPath(metadata.pathLower)
                        .start()
                listSharedLinkResult.links[0].url
            } catch (ex: Exception) {
                println()
                ex.printStackTrace()
                null
            }
        }
    }

    private fun zipIfRequired(uploadPath: String): File {
        val source = File(uploadPath)
        when {
            source.isFile -> return source
            source.isDirectory -> {
                tempFile = true
                val fileList = generateFileList(uploadPath, source)
                val zipFile = "$uploadPath.zip"
                val buffer = ByteArray(1024)
                FileOutputStream(zipFile).use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        fileList.forEach { file ->
                            val ze = ZipEntry(source.name + File.separator + file)
                            zos.putNextEntry(ze)
                            FileInputStream(uploadPath + File.separator + file).use { inputStream ->
                                var len: Int
                                while (inputStream.read(buffer).also { len = it } > 0) {
                                    zos.write(buffer, 0, len)
                                }
                            }
                        }
                    }
                }
                return File(zipFile)
            }
            else -> {
                throw RuntimeException("Kuch galat ho gaya")
            }
        }
    }

    private fun generateFileList(sourceFolder: String, node: File): ArrayList<String> { // add file only
        val fileList = ArrayList<String>()
        if (node.isFile) {
            fileList.add(generateZipEntry(sourceFolder, node.toString()))
        }
        if (node.isDirectory) {
            node.list()?.forEach { filename ->
                fileList.addAll(generateFileList(sourceFolder, File(node, filename)))
            }
        }
        return fileList
    }

    private fun generateZipEntry(sourceFolder: String, file: String): String {
        return file.substring(sourceFolder.length + 1, file.length)
    }

    private fun validateFiles(pathError: String?, localFile: File) {
        if (pathError != null) {
            System.err.println("Invalid <dropbox-path>: $pathError")
            exitProcess(1)
        }

        if (!localFile.exists()) {
            System.err.println("Invalid <local-path>: file does not exist.")
            exitProcess(1)
        }

        if (!localFile.isFile) {
            System.err.println("Invalid <local-path>: not a file.")
            exitProcess(1)
        }
    }

    private fun uploadFile(
            dbxClient: DbxClientV2,
            localFile: File,
            dropBoxPath: String
    ): FileMetadata {
        try {
            FileInputStream(localFile).use { fis ->
                val progressListener = IOUtil.ProgressListener { l -> printProgress(l, localFile.length()) }

                return dbxClient.files().uploadBuilder(dropBoxPath)
                        .withMode(WriteMode.OVERWRITE)
                        .withClientModified(Date(localFile.lastModified()))
                        .uploadAndFinish(fis, progressListener)
            }
        } catch (ex: UploadErrorException) {
            System.err.println("Error uploading to Dropbox: " + ex.message)
            exitProcess(1)
        } catch (ex: DbxException) {
            System.err.println("Error uploading to Dropbox: " + ex.message)
            exitProcess(1)
        } catch (ex: IOException) {
            System.err.println("Error reading from file \"" + localFile + "\": " + ex.message)
            exitProcess(1)
        }
    }

    private fun chunkedUploadFile(
            dbxClient: DbxClientV2,
            localFile: File,
            dropBoxPath: String
    ): FileMetadata {
        val size = localFile.length()

        // assert our file is at least the chunk upload size. We make this assumption in the code
        // below to simplify the logic.
        if (size < CHUNKED_UPLOAD_CHUNK_SIZE) {
            System.err.println("File too small, use upload() instead.")
            exitProcess(1)
        }

        var uploaded = 0L
        val thrown: DbxException?

        val progressListener: IOUtil.ProgressListener = object : IOUtil.ProgressListener {
            var uploadedBytes: Long = 0
            override fun onProgress(l: Long) {
                printProgress(l + uploadedBytes, size)
                if (l == CHUNKED_UPLOAD_CHUNK_SIZE) uploadedBytes += CHUNKED_UPLOAD_CHUNK_SIZE
            }
        }

        // Chunked uploads have 3 phases, each of which can accept uploaded bytes:
        //
        //    (1)  Start: initiate the upload and get an upload session ID
        //    (2) Append: upload chunks of the file to append to our session
        //    (3) Finish: commit the upload and close the session
        //
        // We track how many bytes we uploaded to determine which phase we should be in.
        var sessionId: String? = null

        for (i in 0 until CHUNKED_UPLOAD_MAX_ATTEMPTS) {
            if (i > 0) {
                System.out.printf(
                        "Retrying chunked upload (%d / %d attempts)\n",
                        i + 1,
                        CHUNKED_UPLOAD_MAX_ATTEMPTS
                )
            }
        }

        try {
            FileInputStream(localFile).use { `in` ->
                // if this is a retry, make sure seek to the correct offset
                `in`.skip(uploaded)
                // (1) Start
                if (sessionId == null) {
                    sessionId = dbxClient.files().uploadSessionStart()
                            .uploadAndFinish(`in`, CHUNKED_UPLOAD_CHUNK_SIZE, progressListener)
                            .sessionId
                    uploaded += CHUNKED_UPLOAD_CHUNK_SIZE
                    printProgress(uploaded, size)
                }
                var cursor = UploadSessionCursor(sessionId, uploaded)
                // (2) Append
                while (size - uploaded > CHUNKED_UPLOAD_CHUNK_SIZE) {
                    dbxClient.files().uploadSessionAppendV2(cursor)
                            .uploadAndFinish(`in`, CHUNKED_UPLOAD_CHUNK_SIZE, progressListener)
                    uploaded += CHUNKED_UPLOAD_CHUNK_SIZE
                    printProgress(uploaded, size)
                    cursor = UploadSessionCursor(sessionId, uploaded)
                }
                // (3) Finish
                val remaining = size - uploaded
                val commitInfo = CommitInfo.newBuilder(dropBoxPath)
                        .withMode(WriteMode.OVERWRITE)
                        .withClientModified(Date(localFile.lastModified()))
                        .build()

                return dbxClient.files().uploadSessionFinish(cursor, commitInfo)
                        .uploadAndFinish(`in`, remaining, progressListener)
            }
        } catch (ex: RetryException) {
            thrown = ex
            // RetryExceptions are never automatically retried by the client for uploads. Must
            // catch this exception even if DbxRequestConfig.getMaxRetries() > 0.
            sleepQuietly(ex.backoffMillis)
        } catch (ex: NetworkIOException) {
            thrown = ex
            // network issue with Dropbox (maybe a timeout?) try again
        } catch (ex: UploadSessionLookupErrorException) {
            if (ex.errorValue.isIncorrectOffset) {
                thrown = ex
                // server offset into the stream doesn't match our offset (uploaded). Seek to
                // the expected offset according to the server and try again.
                uploaded = ex.errorValue
                        .incorrectOffsetValue
                        .correctOffset
            } else { // Some other error occurred, give up.
                System.err.println("Error uploading to Dropbox: " + ex.message)
                exitProcess(1)
            }
        } catch (ex: UploadSessionFinishErrorException) {
            if (ex.errorValue.isLookupFailed && ex.errorValue.lookupFailedValue.isIncorrectOffset) {
                thrown = ex
                // server offset into the stream doesn't match our offset (uploaded). Seek to
                // the expected offset according to the server and try again.
                uploaded = ex.errorValue
                        .lookupFailedValue
                        .incorrectOffsetValue
                        .correctOffset
            } else { // some other error occurred, give up.
                System.err.println("Error uploading to Dropbox: " + ex.message)
                exitProcess(1)
            }
        } catch (ex: DbxException) {
            System.err.println("Error uploading to Dropbox: " + ex.message)
            exitProcess(1)
        } catch (ex: IOException) {
            System.err.println("Error reading from file \"" + localFile.toString() + "\": " + ex.message)
            exitProcess(1)
        }

        // if we made it here, then we must have run out of attempts
        System.err.println("Maxed out upload attempts to Dropbox. Most recent error: " + thrown?.message)
        exitProcess(1)

    }

    private fun printProgress(uploaded: Long, size: Long) {
        System.out.printf(
                "Uploaded %12d / %12d bytes (%5.2f%%)\n",
                uploaded,
                size,
                100 * (uploaded / size.toDouble())
        )
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (ex: InterruptedException) { // just exit
            System.err.println("Error uploading to Dropbox: interrupted during backoff.")
            exitProcess(1)
        }
    }
}