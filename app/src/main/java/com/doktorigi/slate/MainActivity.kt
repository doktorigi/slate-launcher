package com.doktorigi.slate

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.app.AlertDialog
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.ViewOutlineProvider
import android.widget.*
import java.io.File

class MainActivity : Activity() {
    private class App(val pkg: String, val label: String, val banner: Drawable?, val icon: Drawable, val intent: Intent)

    private val prefs by lazy { getSharedPreferences("slate", MODE_PRIVATE) }
    // Google TV and Fire TV package ids side by side; only installed ones show
    private val defaultDock = "com.netflix.ninja,com.google.android.youtube.tv,com.amazon.firetv.youtube," +
        "com.amazon.amazonvideo.livingroom,com.amazon.firebat,com.peacocktv.peacockandroid,com.peacock.peacockfiretv," +
        "com.cbs.ott,com.wbd.stream,com.hbo.hbonow"
    // Fire OS system plumbing that registers launcher icons
    private val hidden = setOf("com.amazon.tv.launcher", "com.amazon.ftv.profilepicker", "com.amazon.firetv.troubleshooting",
        "com.amazon.ssm", "com.amazon.hedwig", "com.amazon.vizzini.ftvcds", "com.amazon.minitv.android.app",
        "com.amazon.shoptv.firetv.client", "com.amazon.tv.quicksettings.ui", "com.android.documentsui",
        "com.android.providers.downloads.ui", "com.amazon.tahoe", "com.amazon.gamehub", "com.amazon.tv.ftvambient")
    private var apps = emptyList<App>()
    private var appsKey = ""
    private lateinit var dock: LinearLayout
    private lateinit var grid: GridLayout
    private lateinit var allApps: ScrollView
    private lateinit var home: FrameLayout

    /** Empty middle of the home screen, reserved for widgets. */
    lateinit var widgets: FrameLayout
    private lateinit var weather: WeatherWidget

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        root.addView(wallpaper(), MATCH_PARENT, MATCH_PARENT)

        home = FrameLayout(this).apply {
            setPadding(dp(56), dp(40), dp(56), dp(36)); clipChildren = false; clipToPadding = false
        }
        home.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextClock(context).apply {
                format12Hour = "h:mm"; format24Hour = "H:mm"
                textSize = 46f; setTextColor(Color.WHITE); typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            })
            addView(TextClock(context).apply {
                format12Hour = "EEEE, MMMM d"; format24Hour = "EEEE, MMMM d"
                textSize = 15f; setTextColor(0xB3FFFFFF.toInt())
            })
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START))
        home.addView(LinearLayout(this).apply {
            // HDMI etc. — only on TVs that have them (Fire TV Edition, not dongles)
            val inputs = (getSystemService(TV_INPUT_SERVICE) as? TvInputManager)?.tvInputList.orEmpty().filter { it.isPassthroughInput }
            if (inputs.isNotEmpty()) addView(link("Inputs") { pickInput(inputs) })
            addView(link("All apps") { showAllApps(true) })
            addView(link("Settings") { launch(Intent(Settings.ACTION_SETTINGS)) })
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END))

        widgets = FrameLayout(this)
        weather = WeatherWidget(this)
        widgets.addView(weather, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        home.addView(widgets, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(100); bottomMargin = dp(136) })

        dock = LinearLayout(this).apply {
            setPadding(dp(14), dp(12), dp(14), dp(10)); clipChildren = false; clipToPadding = false
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat(); setColor(0x26FFFFFF); setStroke(dp(1), 0x40FFFFFF)
            }
        }
        home.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(dock)
        }, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        root.addView(home)

        grid = GridLayout(this).apply { columnCount = 6; clipChildren = false; clipToPadding = false }
        allApps = ScrollView(this).apply {
            visibility = View.GONE; isVerticalScrollBarEnabled = false; clipChildren = false
            setBackgroundColor(0xE6000000.toInt())
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
                setPadding(dp(64), dp(40), dp(64), dp(48))
                addView(TextView(context).apply {
                    text = "All apps"; textSize = 26f; setTextColor(Color.WHITE)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, 0, 0, dp(24))
                })
                addView(grid)
            })
        }
        root.addView(allApps, MATCH_PARENT, MATCH_PARENT)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        armIdle()
        weather.refresh()
        val fresh = loadApps()
        val key = fresh.joinToString(",") { it.pkg }
        if (key == appsKey) return
        apps = fresh; appsKey = key
        renderDock(); renderGrid()
        dock.getChildAt(0)?.let { (it as ViewGroup).getChildAt(0).requestFocus() }
    }

    override fun onPause() { window.decorView.removeCallbacks(idle); super.onPause() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean { armIdle(); return super.dispatchKeyEvent(event) }

    // Fire OS ignores third-party screensavers, so Slate shows its own after 5 idle minutes at home
    private val isFire by lazy { packageManager.hasSystemFeature("amazon.hardware.fire_tv") }
    private val idle = Runnable { startActivity(Intent(this, ScreensaverActivity::class.java)) }

    private fun armIdle() {
        window.decorView.removeCallbacks(idle)
        if (isFire) window.decorView.postDelayed(idle, 5 * 60_000L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        showAllApps(false) // Home pressed while browsing
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (allApps.visibility == View.VISIBLE) showAllApps(false) }

    private fun showAllApps(show: Boolean) {
        allApps.visibility = if (show) View.VISIBLE else View.GONE
        home.visibility = if (show) View.INVISIBLE else View.VISIBLE
        if (show) { allApps.scrollTo(0, 0); (grid.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus() }
        else (dock.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.requestFocus()
    }

    private fun loadApps(): List<App> {
        val pm = packageManager
        val seen = HashSet<String>()
        return listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).flatMap { cat ->
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).mapNotNull { ri ->
                val ai = ri.activityInfo
                if (ai.packageName == packageName || ai.packageName in hidden || !seen.add(ai.packageName)) return@mapNotNull null
                App(ai.packageName, ri.loadLabel(pm).toString(), ai.loadBanner(pm), ai.loadIcon(pm),
                    Intent(Intent.ACTION_MAIN).addCategory(cat).setClassName(ai.packageName, ai.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            }
        }.sortedBy { it.label.lowercase() }
    }

    private fun pickInput(inputs: List<TvInputInfo>) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Inputs")
            .setItems(inputs.map { it.loadLabel(this) }.toTypedArray()) { _, i ->
                launch(Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(inputs[i].id))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.show()
    }

    private fun pinned() = prefs.getString("dock", defaultDock)!!.split(",").filter { it.isNotEmpty() }

    private fun togglePin(app: App) {
        val p = pinned().toMutableList()
        val added = !p.remove(app.pkg)
        if (added) p.add(app.pkg)
        prefs.edit().putString("dock", p.joinToString(",")).apply()
        Toast.makeText(this, "${app.label} ${if (added) "added to" else "removed from"} dock", Toast.LENGTH_SHORT).show()
        renderDock()
    }

    private fun renderDock() {
        dock.removeAllViews()
        val byPkg = apps.associateBy { it.pkg }
        pinned().mapNotNull { byPkg[it] }.forEachIndexed { i, app ->
            dock.addView(tile(app, dp(104), labelAlways = true), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                if (i > 0) marginStart = dp(14)
            })
        }
    }

    private fun renderGrid() {
        grid.removeAllViews()
        val gap = dp(28)
        val w = (resources.displayMetrics.widthPixels - dp(128) - gap * 5) / 6
        apps.forEach { app ->
            grid.addView(tile(app, w, labelAlways = false), GridLayout.LayoutParams().apply {
                setMargins(0, 0, gap, gap)
            })
        }
    }

    private fun tile(app: App, w: Int, labelAlways: Boolean): View {
        val h = w * 9 / 16
        val label = TextView(this).apply {
            text = app.label; textSize = 12f; gravity = Gravity.CENTER; maxLines = 1
            setTextColor(Color.WHITE); alpha = if (labelAlways) 0.75f else 0f
            setPadding(0, dp(8), 0, 0)
        }
        val card = FrameLayout(this).apply {
            isFocusable = true; isClickable = true
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xFF2C2C2E.toInt()) }
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
            val img = ImageView(context)
            if (app.banner != null) {
                img.setImageDrawable(app.banner); img.scaleType = ImageView.ScaleType.CENTER_CROP
                addView(img, MATCH_PARENT, MATCH_PARENT)
            } else {
                img.setImageDrawable(app.icon)
                addView(img, FrameLayout.LayoutParams(h * 3 / 5, h * 3 / 5, Gravity.CENTER))
            }
            setOnClickListener { launch(app.intent) }
            setOnLongClickListener { togglePin(app); true }
            setOnFocusChangeListener { v, focused ->
                val s = if (focused) 1.1f else 1f
                v.animate().scaleX(s).scaleY(s).translationZ(if (focused) dp(14).toFloat() else 0f).setDuration(160).start()
                label.animate().alpha(if (focused) 1f else if (labelAlways) 0.75f else 0f)
                    .translationY(if (focused) h * 0.05f else 0f).setDuration(160).start()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            addView(card, w, h)
            addView(label, w, WRAP_CONTENT)
        }
    }

    private fun link(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; setTextColor(Color.WHITE); alpha = 0.7f
        setPadding(dp(14), dp(6), dp(14), dp(6)); isFocusable = true; isClickable = true
        background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(Color.TRANSPARENT) }
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, f ->
            v.alpha = if (f) 1f else 0.7f
            (v.background as GradientDrawable).setColor(if (f) 0x33FFFFFF else Color.TRANSPARENT)
        }
    }

    private fun launch(intent: Intent) {
        try { startActivity(intent) } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show()
        }
    }

    // ponytail: custom photo = adb push it to <external files>/wallpaper.jpg; otherwise a drawn night sky
    private fun wallpaper(): View {
        val f = File(getExternalFilesDir(null), "wallpaper.jpg")
        if (!f.exists()) return Sky(this)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, opts)
        opts.inSampleSize = maxOf(1, opts.outWidth / 1920)
        opts.inJustDecodeBounds = false
        val bmp = BitmapFactory.decodeFile(f.path, opts) ?: return Sky(this)
        return ImageView(this).apply { setImageDrawable(BitmapDrawable(resources, bmp)); scaleType = ImageView.ScaleType.CENTER_CROP }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    class Sky(ctx: Context) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stars = java.util.Random(7).let { r -> FloatArray(240 * 3) { r.nextFloat() } }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(0xFF070B18.toInt(), 0xFF142038.toInt(), 0xFF3A3346.toInt(), 0xFF6B4A3A.toInt()),
                floatArrayOf(0f, 0.5f, 0.72f, 0.82f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p); p.shader = null
            for (i in 0 until 240) {
                val b = stars[i * 3 + 2]
                p.color = Color.argb((40 + b * b * 200).toInt(), 255, 255, 255)
                c.drawCircle(stars[i * 3] * w, stars[i * 3 + 1] * h * 0.72f, 0.5f + b * b * 1.8f, p)
            }
            // two dune ridges
            dune(c, w, h, 0.74f, 0.80f, 0.70f, 0xFF3B2A22.toInt(), 0xFF1A120E.toInt())
            dune(c, w, h, 0.86f, 0.80f, 0.92f, 0xFF22170F.toInt(), 0xFF0B0806.toInt())
        }

        private fun dune(c: Canvas, w: Float, h: Float, y0: Float, peak: Float, y1: Float, top: Int, bottom: Int) {
            val path = Path().apply {
                moveTo(0f, h * y0)
                quadTo(w * 0.35f, h * (peak - 0.12f), w * 0.6f, h * peak)
                quadTo(w * 0.8f, h * (peak + 0.04f), w, h * y1)
                lineTo(w, h); lineTo(0f, h); close()
            }
            p.shader = LinearGradient(0f, h * minOf(y0, peak - 0.06f), 0f, h, top, bottom, Shader.TileMode.CLAMP)
            c.drawPath(path, p); p.shader = null
        }
    }
}
