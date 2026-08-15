package com.example.gymoccupancy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.toColorInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.currentState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.core.graphics.createBitmap

private val AppWidgetIdKey = ActionParameters.Key<Int>("appWidgetId")
private val refreshTimestamps = mutableMapOf<Int, ArrayDeque<Long>>()
private const val RATE_LIMIT_MAX = 3
private const val RATE_LIMIT_WINDOW_MS = 60_000L

// Glance state keys. The occupancy data is fetched into this reactive state and
// read at render time, so update()/recompose reflects new data without needing
// provideGlance (and its one-shot fetch) to re-run.
private val OccupancyJsonKey = stringPreferencesKey("occupancy_json")
private val LastUpdatedKey = stringPreferencesKey("last_updated")
private val GymNameKey = stringPreferencesKey("gym_name")
private val LogoPathKey = stringPreferencesKey("logo_path")

suspend fun loadOccupancyIntoState(context: Context, appWidgetId: Int) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    val gymId = getGymId(context, appWidgetId)
    val operatorId = getOperatorId(context, appWidgetId)
    val json = if (gymId != null && operatorId != null) fetchOccupancyRaw(operatorId, gymId) else null
    val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    val gymName = getGymName(context, appWidgetId)
    val cachedLogo = logoFileForWidget(context, appWidgetId)
    val logoFile = if (cachedLogo.exists()) cachedLogo else fetchAndCacheLogo(context, appWidgetId)
    updateAppWidgetState(context, glanceId) { prefs ->
        if (json != null) prefs[OccupancyJsonKey] = json else prefs.remove(OccupancyJsonKey)
        prefs[LastUpdatedKey] = time
        if (gymName != null) prefs[GymNameKey] = gymName else prefs.remove(GymNameKey)
        if (logoFile != null) prefs[LogoPathKey] = logoFile.absolutePath else prefs.remove(LogoPathKey)
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[AppWidgetIdKey] ?: return
        val now = System.currentTimeMillis()
        val timestamps = refreshTimestamps.getOrPut(appWidgetId) { ArrayDeque() }
        while (timestamps.isNotEmpty() && now - timestamps.first() > RATE_LIMIT_WINDOW_MS) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= RATE_LIMIT_MAX) return
        timestamps.addLast(now)
        loadOccupancyIntoState(context, appWidgetId)
        GymOccupancyWidget().update(context, glanceId)
    }
}



class GymOccupancyWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        loadOccupancyIntoState(context, appWidgetId)

        provideContent {
            val prefs = currentState<Preferences>()
            val json = prefs[OccupancyJsonKey]
            val data = remember(json) { json?.let { parseOccupancyJson(it) } }
            val lastUpdated = prefs[LastUpdatedKey]
            val gymName = prefs[GymNameKey]
            val logoPath = prefs[LogoPathKey]
            val logoFile = remember(logoPath) { logoPath?.let { java.io.File(it) } }
            WidgetContent(appWidgetId, gymName, dayUtilization = data, logoFile, lastUpdated = lastUpdated)
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun WidgetContent(
    appWidgetId: Int,
    gymName: String?,
    dayUtilization: DayUtilization?,
    logoFile: java.io.File?,
    lastUpdated: String? = null
) {
    val size = LocalSize.current
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density

    val occupancyText = when {
        dayUtilization?.isClosed == true -> "Closed"
        dayUtilization != null -> "${dayUtilization.currentOccupancy}%"
        else -> "—"
    }
    val isWide = size.width > size.height * 1.5f
    val isTall = size.height > 100.dp

    val logoBitmap = if (logoFile != null && logoFile.exists()) {
        android.graphics.BitmapFactory.decodeFile(logoFile.absolutePath)
    } else null

    val configIntent = Intent(context, WidgetConfigActivity::class.java).apply {
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val refreshAction = actionRunCallback<RefreshAction>(actionParametersOf(AppWidgetIdKey to appWidgetId))
    val configAction = actionStartActivity(configIntent)
    val lastUpdatedText = if (lastUpdated != null) "↻ $lastUpdated" else "↻"

    if (isWide && !isTall) {
        // 1x4 single-row layout
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(R.color.widget_background)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clickable(configAction),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = occupancyText,
                style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 24.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = GlanceModifier.width(12.dp))
            if (gymName != null) {
                Text(
                    text = gymName,
                    style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 16.sp),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
            } else {
                Spacer(modifier = GlanceModifier.defaultWeight())
            }
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = lastUpdatedText,
                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 11.sp),
                modifier = GlanceModifier
                    .background(ImageProvider(R.drawable.refresh_button_bg))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .clickable(refreshAction)
            )
            if (logoBitmap != null) {
                Spacer(modifier = GlanceModifier.width(8.dp))
                Image(
                    provider = ImageProvider(logoBitmap),
                    contentDescription = gymName,
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.height(36.dp).width(36.dp)
                )
            }
        }
    } else {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(R.color.widget_background)
                .padding(12.dp)
                .clickable(configAction),
            verticalAlignment = Alignment.Vertical.Top
        ) {
            // Top row: gym name | logo
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                if (gymName != null) {
                    Text(
                        text = gymName,
                        style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 18.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                } else {
                    Spacer(modifier = GlanceModifier.defaultWeight())
                }
                if (logoBitmap != null) {
                    Image(
                        provider = ImageProvider(logoBitmap),
                        contentDescription = gymName,
                        contentScale = ContentScale.Fit,
                        modifier = GlanceModifier.height(44.dp).width(44.dp)
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(4.dp))

            if (isWide && isTall && dayUtilization != null) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    // Left: % stacked above ↻ time
                    Column(verticalAlignment = Alignment.Vertical.CenterVertically) {
                        Text(
                            text = occupancyText,
                            style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = GlanceModifier.height(16.dp))
                        Text(
                            text = lastUpdatedText,
                            style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 14.sp),
                            modifier = GlanceModifier
                                .background(ImageProvider(R.drawable.refresh_button_bg))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clickable(refreshAction)
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(12.dp))

                    // Right: chart above 07:00 / 22:00
                    Column(modifier = GlanceModifier.defaultWeight().fillMaxSize()) {
                        val chartW = (size.width.value * density * 0.65f).toInt()
                        val chartH = (size.height.value * density * 0.55f).toInt()
                        val chartBitmap = createOccupancyChart(dayUtilization, chartW, chartH)
                        if (chartBitmap != null) {
                            Image(
                                provider = ImageProvider(chartBitmap),
                                contentDescription = "Occupancy chart",
                                contentScale = ContentScale.FillBounds,
                                modifier = GlanceModifier.fillMaxWidth().defaultWeight()
                            )
                        }
                        Row(modifier = GlanceModifier.fillMaxWidth()) {
                            Text(
                                text = dayUtilization.earliestStartTime.take(5),
                                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 11.sp)
                            )
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Text(
                                text = dayUtilization.latestEndTime.take(5),
                                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 11.sp)
                            )
                        }
                    }
                }
            } else {
                // Occupancy percentage
                Text(
                    text = occupancyText,
                    style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                )

                Spacer(modifier = GlanceModifier.height(2.dp))

                Text(
                    text = lastUpdatedText,
                    style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 18.sp),
                    modifier = GlanceModifier
                        .background(ImageProvider(R.drawable.refresh_button_bg))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .clickable(refreshAction)
                )
            }
        }
    }
}

class GymOccupancyReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = GymOccupancyWidget()
}
