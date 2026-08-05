package icu.nullptr.twifucker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import icu.nullptr.twifucker.R
import icu.nullptr.twifucker.hook.TweetMedia
import java.net.HttpURLConnection
import java.net.URL

class DownloadItem(context: Context) : CustomLayout(context) {

    private val selectableItemBackground = TypedValue().also {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }

    private val thumb = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        layoutParams = LayoutParams(THUMB_SIZE, THUMB_SIZE).also {
            it.marginStart = 16.dp
            it.marginEnd = 8.dp
        }
        setClipToOutline(true)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 12.dp.toFloat())
            }
        }
        addView(this)
    }

    private val itemText = TextView(context).apply {
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        layoutParams = LayoutParams(WRAP_CONTENT, WRAP_CONTENT).also {
            it.marginStart = 24.dp
            setPadding(18.dp, 0, 18.dp, 0)
        }
        addView(this)
    }

    private val btnCopy = ImageButton(context).apply {
        setImageResource(R.drawable.baseline_copy_24)
        setBackgroundColor(Color.TRANSPARENT)
        foreground = context.getDrawable(selectableItemBackground.resourceId)
        layoutParams = LayoutParams(96.dp, 96.dp).also {
            it.marginStart = 8.dp
        }
        addView(this)
    }

    private val btnDownload = ImageButton(context).apply {
        setImageResource(R.drawable.baseline_download_24)
        setBackgroundColor(Color.TRANSPARENT)
        foreground = context.getDrawable(selectableItemBackground.resourceId)
        layoutParams = LayoutParams(96.dp, 96.dp).also {
            it.marginStart = 8.dp
            it.marginEnd = 24.dp
        }
        addView(this)
    }

    /** guards against stale thumbnails when a recycled row is reused */
    private var thumbToken = ""

    fun setTitle(title: String) {
        itemText.text = title
    }

    fun setMedia(media: TweetMedia, index: Int) {
        setTitle(labelOf(media, index))
        setThumb(media.thumbUrl)
    }

    fun setOnCopy(onCopy: () -> Unit) {
        btnCopy.setOnClickListener { onCopy() }
    }

    fun setOnDownload(onDownload: () -> Unit) {
        btnDownload.setOnClickListener { onDownload() }
    }

    private fun setThumb(url: String?) {
        thumbToken = url ?: ""
        if (url == null) {
            thumb.visibility = View.GONE
            return
        }
        thumb.visibility = View.VISIBLE
        thumb.setImageDrawable(null)
        val token = url
        Thread {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                // pbs.twimg.com thumbnails are fine without auth, but a UA
                // header avoids generic 403s from CDN edge rules
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
                )
                conn.connect()
                val stream = conn.inputStream
                val bmp = BitmapFactory.decodeStream(
                    stream, null, BitmapFactory.Options().apply { inSampleSize = 2 }
                )
                stream.close()
                conn.disconnect()
                if (bmp != null) thumb.post {
                    if (thumbToken == token) thumb.setImageBitmap(bmp)
                }
            } catch (t: Throwable) {
                thumb.post { if (thumbToken == token) thumb.visibility = View.GONE }
            }
        }.start()
    }

    companion object {
        private const val THUMB_SIZE = 64 // dp

        /** "1 · Image · 1200×675" / "1 · Video · 720p · 0:42" */
        fun labelOf(media: TweetMedia, index: Int): String {
            val sb = StringBuilder("$index · ")
            sb.append(if (media.kind == "video") "Video" else "Image")
            if (media.kind == "video") {
                val dims = Regex("(\\d{2,4})x(\\d{2,4})").find(media.url)
                val height = dims?.let { minOf(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
                if (height != null && height > 0) {
                    sb.append(" · ${height}p")
                } else if (media.bitRate > 0) {
                    sb.append(" · ${"%.1f".format(media.bitRate / 1_000_000f)} Mbps")
                }
                if (media.durationMillis > 0) {
                    val totalSecs = media.durationMillis / 1000
                    sb.append(" · ${totalSecs / 60}:${(totalSecs % 60).toString().padStart(2, '0')}")
                }
            } else {
                if (media.width > 0 && media.height > 0) {
                    sb.append(" · ${media.width}×${media.height}")
                }
            }
            return sb.toString()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        thumb.autoMeasure()
        btnCopy.autoMeasure()
        btnDownload.autoMeasure()

        val itemTextWidth =
            measuredWidth - itemText.marginStart - itemText.paddingLeft - itemText.paddingRight - itemText.marginEnd - thumb.measuredWidthWithMargins - btnCopy.measuredWidthWithMargins - btnDownload.measuredWidthWithMargins
        itemText.measure(
            itemTextWidth.toExactlyMeasureSpec(), itemText.defaultHeightMeasureSpec(this)
        )

        val maxWidth =
            (itemTextWidth + thumb.measuredWidthWithMargins + btnCopy.measuredWidthWithMargins + btnDownload.measuredWidthWithMargins).coerceAtLeast(
                measuredWidth
            )
        val maxHeight = (itemText.measuredHeightWithMargins).coerceAtLeast(
            btnCopy.measuredHeightWithMargins
        ).coerceAtLeast(thumb.measuredHeightWithMargins)
        setMeasuredDimension(maxWidth, maxHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (!isRTL) {
            thumb.let {
                it.layout(
                    x = it.marginStart, y = (this.measuredHeight / 2) - (it.measuredHeight / 2)
                )
            }
            itemText.let {
                it.layout(
                    x = thumb.right + it.marginStart, y = (this.measuredHeight / 2) - (it.measuredHeight / 2)
                )
            }
            btnCopy.let {
                it.layout(x = itemText.right + it.marginStart, y = 0)
            }
            btnDownload.let {
                it.layout(x = btnCopy.right + it.marginStart, y = 0)
            }
        } else {
            thumb.let {
                it.layout(x = it.marginEnd, y = (this.measuredHeight / 2) - (it.measuredHeight / 2), fromRight = true)
            }
            itemText.let {
                it.layout(
                    x = thumb.right + it.marginEnd,
                    y = (this.measuredHeight / 2) - (it.measuredHeight / 2),
                    fromRight = true
                )
            }
            btnDownload.let {
                it.layout(x = it.marginStart, y = 0)
            }
            btnCopy.let {
                it.layout(x = it.marginStart + btnDownload.right, y = 0)
            }
        }
    }
}
