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

    companion object {
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

            fun sub(c: Double, n: Int) = (if (c > 0) "RM %.2f · ".format(c) else "") + "$n"

            val rv = RemoteViews(context.packageName, R.layout.widget_invt)
            rv.setTextViewText(R.id.w_m_kwh, "%.1f kWh".format(mK))
            rv.setTextViewText(R.id.w_m_sub, sub(mC, mN))
            rv.setTextViewText(R.id.w_t_kwh, "%.1f kWh".format(tK))
            rv.setTextViewText(R.id.w_t_sub, sub(tC, tN))
            rv.setTextViewText(R.id.w_life, "Lifetime  %.1f kWh".format(life))

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
            rv.setOnClickPendingIntent(R.id.w_root, pi)
            return rv
        }
    }
}
