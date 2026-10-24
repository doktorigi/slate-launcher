package com.doktorigi.slate

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** Glass weather card from Open-Meteo (no key, no account). */
class WeatherWidget(ctx: Context) : LinearLayout(ctx) {
    // set in local.properties: weather.place, weather.lat, weather.lon
    private val place = BuildConfig.WEATHER_PLACE
    private val lat = BuildConfig.WEATHER_LAT
    private val lon = BuildConfig.WEATHER_LON

    private var fetchedAt = 0L
    private val now = text(15f, 0xB3FFFFFF.toInt())
    private val temp = text(46f, Color.WHITE, "sans-serif-light")
    private val summary = text(16f, 0xCCFFFFFF.toInt())
    private val days = LinearLayout(ctx)

    init {
        orientation = VERTICAL
        setPadding(dp(28), dp(16), dp(28), dp(16))
        background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(0x1FFFFFFF); setStroke(dp(1), 0x33FFFFFF) }
        now.text = place
        summary.text = "Loading weather…"
        addView(now); addView(temp); addView(summary)
        addView(days, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }

    /** Called from onResume; refetches at most every 30 minutes. */
    fun refresh() {
        if (System.currentTimeMillis() - fetchedAt < 30 * 60_000) return
        Thread {
            val json = try {
                JSONObject(URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&temperature_unit=fahrenheit&timezone=auto&forecast_days=5").readText())
            } catch (e: Exception) { null }
            post { if (json != null) render(json) else if (fetchedAt == 0L) summary.text = "Weather unavailable" }
        }.start()
    }

    private fun render(j: JSONObject) {
        fetchedAt = System.currentTimeMillis()
        val cur = j.getJSONObject("current")
        val d = j.getJSONObject("daily")
        val hi = d.getJSONArray("temperature_2m_max"); val lo = d.getJSONArray("temperature_2m_min")
        val codes = d.getJSONArray("weather_code"); val dates = d.getJSONArray("time")
        val (icon, label) = condition(cur.getInt("weather_code"))
        temp.text = "$icon ${cur.getDouble("temperature_2m").toInt()}°"
        summary.text = "$label  ·  H ${hi.getDouble(0).toInt()}°  L ${lo.getDouble(0).toInt()}°"
        days.removeAllViews()
        val parse = SimpleDateFormat("yyyy-MM-dd", Locale.US); val day = SimpleDateFormat("EEE", Locale.getDefault())
        for (i in 1 until dates.length()) {
            days.addView(LinearLayout(context).apply {
                orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(text(13f, 0x99FFFFFF.toInt()).apply { text = day.format(parse.parse(dates.getString(i))!!) })
                addView(text(18f, Color.WHITE).apply { text = condition(codes.getInt(i)).first; setPadding(0, dp(2), 0, dp(2)) })
                addView(text(13f, Color.WHITE).apply { text = "${hi.getDouble(i).toInt()}° ${lo.getDouble(i).toInt()}°" })
            }, LayoutParams(dp(72), LayoutParams.WRAP_CONTENT))
        }
    }

    // WMO weather codes, https://open-meteo.com/en/docs
    private fun condition(code: Int) = when (code) {
        0 -> "☀️" to "Clear"
        1, 2 -> "🌤️" to "Partly cloudy"
        3 -> "☁️" to "Cloudy"
        45, 48 -> "🌫️" to "Fog"
        in 51..57 -> "🌦️" to "Drizzle"
        in 61..67, in 80..82 -> "🌧️" to "Rain"
        in 71..77, 85, 86 -> "🌨️" to "Snow"
        in 95..99 -> "⛈️" to "Thunderstorms"
        else -> "🌡️" to ""
    }

    private fun text(size: Float, color: Int, family: String = "sans-serif") = TextView(context).apply {
        textSize = size; setTextColor(color); typeface = Typeface.create(family, Typeface.NORMAL)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
