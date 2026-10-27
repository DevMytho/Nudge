package com.dev.nudge.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.dev.nudge.MainActivity
import com.dev.nudge.R
import com.dev.nudge.model.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** How many task rows the larger widget shows before collapsing to "+N more". */
private const val MAX_ROWS = 6

/**
 * 🔷 Base class for Nudge's home-screen widgets.
 *
 * Updates come from three directions:
 *  1. the system (APPWIDGET_UPDATE, every 30 min / on placement / after update),
 *  2. the app itself via [refresh] — fired after task data lands in storage,
 *     on app open and after a reboot;
 *  3. either, really — both funnel through [buildViews].
 *
 * Data loading is async, so the receiver uses `goAsync()` to keep its window
 * open until the RemoteViews are pushed.
 */
abstract class NudgeWidgetProvider : AppWidgetProvider() {

    /** RemoteViews covering every instance of this provider (null = nothing to show). */
    protected abstract suspend fun buildViews(context: Context): RemoteViews

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val interested = action == AppWidgetManager.ACTION_APPWIDGET_UPDATE ||
                action == ACTION_REFRESH ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED

        if (!interested) {
            super.onReceive(context, intent)
            return
        }

        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, javaClass))
        if (ids.isEmpty()) return // no instances on any home screen

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                manager.updateAppWidget(ids, buildViews(context))
            } catch (e: Exception) {
                Log.w(TAG, "widget update failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /** Tap anywhere on the widget → open the app. */
    protected fun openAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    companion object {
        private const val TAG = "NudgeWidget"

        /** Explicit in-app broadcast — sent to each provider class. */
        const val ACTION_REFRESH = "com.dev.nudge.widget.REFRESH"

        /**
         * Reload BOTH widgets. Call whenever data they show has changed
         * (tasks persisted, app opened, device rebooted).
         */
        fun refresh(context: Context) {
            val app = context.applicationContext
            listOf(
                StreakWidgetProvider::class.java,
                TasksWidgetProvider::class.java
            ).forEach { provider ->
                app.sendBroadcast(Intent(app, provider).setAction(ACTION_REFRESH))
            }
        }
    }
}

/**
 * 🧯 Small widget: current streak (+ today's progress line).
 */
class StreakWidgetProvider : NudgeWidgetProvider() {

    override suspend fun buildViews(context: Context): RemoteViews {
        val snap = WidgetData.load(context)
        return RemoteViews(context.packageName, R.layout.widget_streak).apply {
            setTextViewText(R.id.widget_streak_value, snap.streak.current.toString())
            setTextViewText(
                R.id.widget_streak_sub,
                context.getString(
                    R.string.widget_today_progress,
                    snap.todayDone,
                    snap.todayTotal
                )
            )
            setOnClickPendingIntent(R.id.widget_streak_root, openAppPendingIntent(context))
        }
    }
}

/**
 * 📋 Larger widget: a window onto today's tasks (up to 6 rows + overflow).
 */
class TasksWidgetProvider : NudgeWidgetProvider() {

    override suspend fun buildViews(context: Context): RemoteViews {
        val snap = WidgetData.load(context)
        val views = RemoteViews(context.packageName, R.layout.widget_tasks)

        views.setTextViewText(
            R.id.widget_tasks_count,
            context.getString(R.string.widget_day_progress, snap.todayDone, snap.todayTotal)
        )

        val shown = snap.todayTasks.take(MAX_ROWS)
        val hidden = snap.todayTasks.size - shown.size

        // 🧹 The host may reapply RemoteViews onto the EXISTING view tree
        //    (instead of re-inflating), which used to append a fresh copy of
        //    every row on each refresh — the list visibly "repeated itself"
        //    each time the app opened. Clear before adding, always.
        views.removeAllViews(R.id.widget_tasks_list)

        if (shown.isEmpty()) {
            views.setViewVisibility(R.id.widget_tasks_list, View.GONE)
            views.setViewVisibility(R.id.widget_tasks_empty, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_tasks_list, View.VISIBLE)
            views.setViewVisibility(R.id.widget_tasks_empty, View.GONE)
            shown.forEach { task ->
                views.addView(R.id.widget_tasks_list, taskRowViews(context, task))
            }
        }

        if (hidden > 0) {
            views.setViewVisibility(R.id.widget_tasks_more, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_tasks_more,
                context.getString(R.string.widget_more, hidden)
            )
        } else {
            views.setViewVisibility(R.id.widget_tasks_more, View.GONE)
        }

        views.setOnClickPendingIntent(R.id.widget_tasks_root, openAppPendingIntent(context))
        return views
    }

    /**
     * One task row. [WidgetData] only ever passes ACTIVE tasks — finished
     * tasks never appear in the widget (the header count still includes
     * them, so the done/total progress line stays truthful).
     */
    private fun taskRowViews(context: Context, task: Task): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_task_row).apply {
            setTextViewText(R.id.row_title, task.title)
            setTextViewText(R.id.row_mark, "\u25CB")
        }
}