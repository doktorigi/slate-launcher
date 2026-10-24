package com.doktorigi.slate

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import kotlin.random.Random

/** System screensaver (Google TV / onn). */
class AmbientDream : DreamService() {
    private lateinit var view: LandscapeView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false; isFullscreen = true
        view = LandscapeView(this); setContentView(view)
    }

    override fun onDreamingStarted() { super.onDreamingStarted(); view.start() }
    override fun onDreamingStopped() { view.stop(); super.onDreamingStopped() }
}

/** Fire OS only runs Amazon's screensavers, so Slate opens this itself when idle at home. */
class ScreensaverActivity : Activity() {
    private lateinit var view: LandscapeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = LandscapeView(this); setContentView(view)
    }

    override fun onResume() { super.onResume(); view.start() }
    override fun onPause() { view.stop(); super.onPause() }

    // any button wakes it, without also acting on Slate
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) finish()
        return true
    }
}

/** Featured wild-landscape photos from Wikimedia Commons, slow zoom + crossfade, clock and credit. */
class LandscapeView(ctx: Context) : FrameLayout(ctx) {
    private class Photo(val url: String, val caption: String)

    companion object {
        private const val SHOW_MS = 40_000L
        private val categories = listOf("mountains", "lakes", "waterfalls", "volcanoes", "coasts")
        private var photos: List<Photo>? = null // kept for the process lifetime
    }

    private val stage = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
    private val caption = TextView(ctx).apply {
        textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setShadowLayer(8f, 0f, 1f, Color.BLACK)
    }
    private var worker: Thread? = null

    init {
        stage.addView(MainActivity.Sky(ctx), MATCH_PARENT, MATCH_PARENT) // shown until the first photo, or offline
        val overlay = FrameLayout(ctx).apply {
            setPadding(dp(56), dp(40), dp(56), dp(36))
            // legibility scrim for clock + caption on bright photos
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x59000000, 0x00000000, 0x00000000, 0x73000000))
        }
        overlay.addView(TextClock(ctx).apply {
            format12Hour = "h:mm"; format24Hour = "H:mm"; textSize = 34f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setShadowLayer(12f, 0f, 1f, Color.BLACK)
        }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))
        overlay.addView(caption, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        addView(stage); addView(overlay)
    }

    fun start() {
        if (worker != null) return
        worker = Thread {
            try {
                val list = photos ?: loadPhotos().also { photos = it }
                for (p in list.shuffled()) {
                    val bmp = download(p.url) ?: continue
                    post { show(bmp, p.caption) }
                    Thread.sleep(SHOW_MS)
                }
            } catch (_: InterruptedException) {
            } catch (_: Exception) { /* offline: the drawn sky stays up */ }
        }.apply { start() }
    }

    fun stop() { worker?.interrupt(); worker = null }

    private fun show(bmp: Bitmap, text: String) {
        val img = ImageView(context).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        stage.addView(img, MATCH_PARENT, MATCH_PARENT)
        img.animate().alpha(1f).setDuration(2500).withEndAction {
            while (stage.childCount > 1) stage.removeViewAt(0)
        }.start()
        // Ken Burns: slow zoom toward a random corner
        val drift = height * 0.05f // stays inside the 6% per-side overscan of the 1.12x zoom, so no edge shows
        img.animate().scaleX(1.12f).scaleY(1.12f)
            .translationX(Random.nextFloat() * 2 * drift - drift).translationY(Random.nextFloat() * 2 * drift - drift)
            .setDuration(SHOW_MS + 3000).setInterpolator(LinearInterpolator()).start()
        caption.animate().alpha(0f).setDuration(600).withEndAction {
            caption.text = text; caption.animate().alpha(1f).setDuration(1200).start()
        }.start()
    }

    private fun loadPhotos(): List<Photo> = categories.flatMap { cat ->
        val title = URLEncoder.encode("Category:Featured_pictures_of_$cat", "UTF-8")
        val json = JSONObject(get("https://commons.wikimedia.org/w/api.php?action=query&format=json" +
            "&generator=categorymembers&gcmtitle=$title&gcmtype=file&gcmlimit=500" +
            "&prop=imageinfo&iiprop=size|url|extmetadata&iiextmetadatafilter=Artist&iiurlwidth=1920"))
        val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@flatMap emptyList()
        pages.keys().asSequence().mapNotNull { k ->
            val page = pages.getJSONObject(k)
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val w = info.optInt("width"); val h = info.optInt("height")
            // landscape-ish only: drops portraits and ultra-wide panoramas
            if (w < 1920 || h == 0 || w.toFloat() / h !in 1.3f..2.1f) return@mapNotNull null
            val name = page.getString("title").removePrefix("File:").substringBeforeLast('.').replace('_', ' ')
            val artist = info.optJSONObject("extmetadata")?.optJSONObject("Artist")?.optString("value")
                ?.replace(Regex("<[^>]*>"), "")?.trim().orEmpty()
            Photo(info.getString("thumburl"), if (artist.isEmpty()) name else "$name  ·  $artist / Wikimedia Commons")
        }.toList()
    }

    private fun download(url: String): Bitmap? = try {
        URL(url).openConnection().apply { setRequestProperty("User-Agent", "SlateLauncher/1.0") }.getInputStream().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
        }
    } catch (e: Exception) { null }

    // Wikimedia asks API clients to send a descriptive User-Agent
    private fun get(url: String) = URL(url).openConnection().apply { setRequestProperty("User-Agent", "SlateLauncher/1.0") }
        .getInputStream().bufferedReader().use { it.readText() }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
