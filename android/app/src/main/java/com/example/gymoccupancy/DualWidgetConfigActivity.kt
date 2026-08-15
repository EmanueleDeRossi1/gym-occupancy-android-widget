package com.example.gymoccupancy

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.glance.appwidget.GlanceAppWidgetManager
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class DualWidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        val targetSlot = intent?.extras?.getInt("target_slot", 0) ?: 0
        val slotsToConfigure = if (targetSlot in 1..2) listOf(targetSlot) else listOf(1, 2)

        enableEdgeToEdge()
        setContent {
            GymPickerFlow(
                appWidgetId = appWidgetId,
                slotsToConfigure = slotsToConfigure,
                onComplete = {
                    val appContext = applicationContext
                    val widgetId = appWidgetId
                    setResult(Activity.RESULT_OK, Intent().apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    })
                    MainScope().launch {
                        loadDualOccupancyIntoState(appContext, widgetId)
                        val glanceId = GlanceAppWidgetManager(appContext).getGlanceIdBy(widgetId)
                        DualGymOccupancyWidget().update(appContext, glanceId)
                        finish()
                    }
                }
            )
        }
    }
}
