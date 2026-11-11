package com.radhi.invtcharger

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONArray
import java.util.Calendar

/**
 * Home-screen widget: this calendar month's kWh + cost + session count, plus the
 * lifetime meter total. Reads the same local store the app writes; tap opens the app.
 */
class InvtWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, build(context))
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            val p = context.getSharedPreferences("invt_log", Context.MODE_PRIVATE)
            p.edit().putInt("widget_mode", 1 - p.getInt("widget_mode", 0)).apply()
            refresh(context)
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_TOGGLE = "com.radhi.invtcharger.WIDGET_TOGGLE"
        /** Re-render every placed widget now (call after the app updates its data). */
        fun refresh(context: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, InvtWidget::class.java))
                if (ids == null || ids.isEmpty()) return
                val rv = build(context)
                for (id in ids) mgr.updateAppWidget(id, rv)
            } catch (_: Exception) {}
        }

        private fun build(context: Context): RemoteViews {
            val p = context.getSharedPreferences("invt_log", Context.MODE_PRIVATE)
            val rate = try { p.getFloat("rate", 0f).toDouble() } catch (_: Exception) { 0.0 }
            val life = try { p.getFloat("last_lifetime", 0f).toDouble() } catch (_: Exception) { 0.0 }

            // current calendar-month bounds
            val mStart: Long; val mEnd: Long
            run {
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                c.set(Calendar.DAY_OF_MONTH, 1); mStart = c.timeInMillis / 1000L
                c.add(Calendar.MONTH, 1); mEnd = c.timeInMillis / 1000L
            }
            // current TNB cycle bounds (24th -> 23rd)
            val tStart: Long; val tEnd: Long
            run {
                val c = Calendar.getInstance()
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                val day = c.get(Calendar.DAY_OF_MONTH)
                c.set(Calendar.DAY_OF_MONTH, 24); if (day < 24) c.add(Calendar.MONTH, -1)
                tStart = c.timeInMillis / 1000L
                c.add(Calendar.MONTH, 1); tEnd = c.timeInMillis / 1000L
            }

            var mK = 0.0; var mC = 0.0; var mN = 0
            var tK = 0.0; var tC = 0.0; var tN = 0
            try {
                val arr = JSONArray(p.getString("records", "[]") ?: "[]")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val start = o.optLong("start"); val k = o.optDouble("kwh")
                    if (start <= 0 || k <= 0) continue
                    val cost = if (rate > 0) k * rate else (o.optDouble("efee") + o.optDouble("sfee"))
                    if (start in mStart until mEnd) { mK += k; mC += cost; mN++ }
                    if (start in tStart until tEnd) { tK += k; tC += cost; tN++ }
                }
            } catch (_: Exception) {}

            val mode = p.getInt("widget_mode", 0)          // 0 = this month, 1 = TNB cycle
            val k = if (mode == 0) mK else tK
            val c = if (mode == 0) mC else tC
            val n = if (mode == 0) mN else tN

            val rv = RemoteViews(context.packageName, R.layout.widget_invt)
            rv.setTextViewText(R.id.w_label, if (mode == 0) "THIS MONTH" else "TNB CYCLE")
            rv.setTextViewText(R.id.w_dots, if (mode == 0) "\u25CF \u25CB" else "\u25CB \u25CF")
            rv.setTextViewText(R.id.w_kwh, "%.1f".format(k))
            rv.setTextViewText(R.id.w_sub,
                (if (c > 0) "RM %.2f \u00B7 ".format(c) else "") + "$n session${if (n == 1) "" else "s"}")
            rv.setTextViewText(R.id.w_life, if (life > 0) "Lifetime %.1f kWh".format(life) else "Lifetime \u2014")

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            // tap the main area -> switch between This month / TNB cycle
            val toggle = Intent(context, InvtWidget::class.java).setAction(ACTION_TOGGLE)
            rv.setOnClickPendingIntent(R.id.w_tap, PendingIntent.getBroadcast(context, 1, toggle, flags))
            // tap the lifetime row -> open the app
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
            rv.setOnClickPendingIntent(R.id.w_life, open)
            return rv
        }
    }
}
