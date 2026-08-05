@file:Suppress("DEPRECATION")

package icu.nullptr.twifucker.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import androidx.documentfile.provider.DocumentFile
import com.github.kyuubiran.ezxhelper.AndroidLogger
import com.github.kyuubiran.ezxhelper.EzXHelper.addModuleAssetPath
import com.github.kyuubiran.ezxhelper.EzXHelper.appContext
import com.github.kyuubiran.ezxhelper.Log
import icu.nullptr.twifucker.R
import icu.nullptr.twifucker.hook.TweetMedia
import icu.nullptr.twifucker.modulePrefs
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadDialog(
    context: Context, private val tweetId: Long, media: List<TweetMedia>, onDismiss: () -> Unit,
) : AlertDialog.Builder(context) {
    companion object {
        private fun contentTypeToExt(contentType: String): String {
            return when {
                contentType.contains("image/jpeg") -> ".jpg"
                contentType.contains("image/png") -> ".png"
                contentType.contains("video/mp4") -> ".mp4"
                contentType.contains("video/webm") -> ".webm"
                contentType.contains("application/x-mpegURL") -> ".m3u8"
                else -> ""
            }
        }

        private fun copyFileUri(
            context: Context, fileName: String, outputDirectory: String, contentType: String
        ) {
            DocumentFile.fromTreeUri(context, Uri.parse(outputDirectory))
                ?.createFile(contentType, fileName)?.uri?.let { uri2 ->
                    context.contentResolver.openOutputStream(uri2)?.use { out ->
                        val inputStream = File(appContext.cacheDir, fileName).inputStream()
                        inputStream.copyTo(out)
                    }
                }
        }

        private fun copyFile(fileName: String): String {
            val downloadPath = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TwiFucker"
            )
            if (!downloadPath.exists()) {
                downloadPath.mkdirs()
            }
            val outputFile = File(downloadPath, fileName)
            val inputStream = File(appContext.cacheDir, fileName).inputStream()
            try {
                val outputStream = outputFile.outputStream()
                inputStream.copyTo(outputStream)
                return outputFile.absolutePath
            } catch (t: Throwable) {
                // scoped storage (Android 10+) — fall back to MediaStore
                val mime = when {
                    fileName.endsWith(".mp4") -> "video/mp4"
                    fileName.endsWith(".webm") -> "video/webm"
                    fileName.endsWith(".m3u8") -> "application/x-mpegURL"
                    fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
                    fileName.endsWith(".png") -> "image/png"
                    fileName.endsWith(".gif") -> "image/gif"
                    else -> "application/octet-stream"
                }
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(
                        android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/TwiFucker"
                    )
                }
                val uri = appContext.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    appContext.contentResolver.openOutputStream(it)?.use { out ->
                        File(appContext.cacheDir, fileName).inputStream().copyTo(out)
                    }
                }
                return uri?.toString() ?: ""
            }
        }

        private fun download(
            context: Context,
            tweetId: Long,
            index: Int,
            url: String,
            onDownloadCompleted: (() -> Unit)? = null
        ) {
            val progressDialog = ProgressDialog(context)
            progressDialog.setTitle(R.string.downloading)
            progressDialog.setCancelable(false)
            progressDialog.show()

            Thread {
                try {
                    val downloadUrl = URL(url)
                    val httpConnection = downloadUrl.openConnection() as HttpURLConnection
                    httpConnection.connectTimeout = 15000
                    httpConnection.readTimeout = 15000
                    httpConnection.connect()
                    val inputStream = httpConnection.inputStream
                    val buffer = ByteArray(1024)
                    var len = inputStream.read(buffer)

                    val contentType = httpConnection.contentType
                    val file = File(
                        appContext.cacheDir,
                        "" + tweetId + "_" + index + contentTypeToExt(contentType)
                    )

                    val outputStream = FileOutputStream(file)
                    while (len != -1) {
                        outputStream.write(buffer, 0, len)
                        len = inputStream.read(buffer)
                    }

                    outputStream.close()
                    inputStream.close()
                    httpConnection.disconnect()

                    val downloadDirectory = modulePrefs.getString("download_directory", null) ?: ""
                    if (downloadDirectory != "") {
                        copyFileUri(context, file.name, downloadDirectory, contentType)
                    } else {
                        val outputPath = copyFile(file.name)
                        MediaScannerConnection.scanFile(
                            context, arrayOf(outputPath), null, null
                        )
                    }
                    file.delete()

                    onDownloadCompleted?.invoke()
                } catch (t: Throwable) {
                    Log.e(t)
                    AndroidLogger.toast(appContext.getString(R.string.download_failed))
                }
                progressDialog.cancel()
            }.start()
        }

        private fun toClipboard(text: String) {
            val clipboardManager =
                appContext.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("label", text)
            clipboardManager.setPrimaryClip(clip)
            AndroidLogger.toast(appContext.getString(R.string.download_link_copied))
        }
    }

    private class DownloadMediaAdapter(
        val context: Context,
        val tweetId: Long,
        val media: List<TweetMedia>
    ) : BaseAdapter() {

        override fun getCount(): Int {
            return media.size
        }

        override fun getItem(position: Int): Any {
            return media[position]
        }

        override fun getItemId(position: Int): Long {
            return position.toLong()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            // listeners are set on every call so a recycled row never keeps
            // the previous position's media
            val view = convertView as? DownloadItem ?: DownloadItem(context)
            view.setOnCopy {
                toClipboard(media[position].url)
            }
            view.setOnDownload {
                download(context, tweetId, position + 1, media[position].url) {
                    AndroidLogger.toast(context.getString(R.string.download_completed))
                }
            }
            view.setMedia(media[position], position + 1)
            return view
        }
    }

    init {
        addModuleAssetPath(context)

        val adapter = DownloadMediaAdapter(context, tweetId, media)
        setAdapter(adapter, null)

        setNeutralButton(R.string.download_all) { _, _ ->
            media.forEachIndexed { i, j ->
                download(context, tweetId, i + 1, j.url) {
                    if (i == media.size - 1) {
                        AndroidLogger.toast(context.getString(R.string.download_completed))
                    }
                }
            }
        }
        setNegativeButton(R.string.settings_dismiss) { _, _ ->
            onDismiss()
        }
        setOnDismissListener { onDismiss() }

        setTitle(R.string.download_or_copy)
        show()
    }
}