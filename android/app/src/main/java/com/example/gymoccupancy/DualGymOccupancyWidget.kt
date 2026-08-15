package com.example.gymoccupancy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
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
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val httpClient = OkHttpClient()

private val AppWidgetIdKey = ActionParameters.Key<Int>("appWidgetId")
private val refreshTimestamps = mutableMapOf<Int, ArrayDeque<Long>>()
private const val RATE_LIMIT_MAX = 3
private const val RATE_LIMIT_WINDOW_MS = 60_000L

private val OccupancyJson1Key = stringPreferencesKey("occupancy_json_1")
private val OccupancyJson2Key = stringPreferencesKey("occupancy_json_2")
private val GymName1Key = stringPreferencesKey("gym_name_1")
private val GymName2Key = stringPreferencesKey("gym_name_2")
private val LogoPath1Key = stringPreferencesKey("logo_path_1")
private val LogoPath2Key = stringPreferencesKey("logo_path_2")
private val LastUpdatedKey = stringPreferencesKey("last_updated")

suspend fun loadDualOccupancyIntoState(context: Context, appWidgetId: Int) = coroutineScope {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)

    val gymId1 = getGymId(context, appWidgetId, slot = 1)
    val operatorId1 = getOperatorId(context, appWidgetId, slot = 1)
    val gymName1 = getGymName(context, appWidgetId, slot = 1)
    val cachedLogo1 = logoFileForWidget(context, appWidgetId, slot = 1)

    val gymId2 = getGymId(context, appWidgetId, slot = 2)
    val operatorId2 = getOperatorId(context, appWidgetId, slot = 2)
    val gymName2 = getGymName(context, appWidgetId, slot = 2)
    val cachedLogo2 = logoFileForWidget(context, appWidgetId, slot = 2)

    val json1Deferred = async { if (gymId1 != null && operatorId1 != null) fetchOccupancyRaw(operatorId1, gymId1) else null }
    val json2Deferred = async { if (gymId2 != null && operatorId2 != null) fetchOccupancyRaw(operatorId2, gymId2) else null }
    val logo1Deferred = async { if (cachedLogo1.exists()) cachedLogo1 else fetchAndCacheLogo(context, appWidgetId, slot = 1) }
    val logo2Deferred = async { if (cachedLogo2.exists()) cachedLogo2 else fetchAndCacheLogo(context, appWidgetId, slot = 2) }

    val json1 = json1Deferred.await()
    val json2 = json2Deferred.await()
    val logo1File = logo1Deferred.await()
    val logo2File = logo2Deferred.await()

    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    updateAppWidgetState(context, glanceId) { prefs ->
        if (json1 != null) prefs[OccupancyJson1Key] = json1 else prefs.remove(OccupancyJson1Key)
        if (json2 != null) prefs[OccupancyJson2Key] = json2 else prefs.remove(OccupancyJson2Key)
        if (gymName1 != null) prefs[GymName1Key] = gymName1 else prefs.remove(GymName1Key)
        if (gymName2 != null) prefs[GymName2Key] = gymName2 else prefs.remove(GymName2Key)
        if (logo1File != null) prefs[LogoPath1Key] = logo1File.absolutePath else prefs.remove(LogoPath1Key)
        if (logo2File != null) prefs[LogoPath2Key] = logo2File.absolutePath else prefs.remove(LogoPath2Key)
        prefs[LastUpdatedKey] = time
    }
}

class DualRefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val appWidgetId = parameters[AppWidgetIdKey] ?: return
        val now = System.currentTimeMillis()
        val timestamps = refreshTimestamps.getOrPut(appWidgetId) { ArrayDeque() }
        while (timestamps.isNotEmpty() && now - timestamps.first() > RATE_LIMIT_WINDOW_MS) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= RATE_LIMIT_MAX) return
        timestamps.addLast(now)
        loadDualOccupancyIntoState(context, appWidgetId)
        DualGymOccupancyWidget().update(context, glanceId)
    }
}

class DualGymOccupancyWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        loadDualOccupancyIntoState(context, appWidgetId)

        provideContent {
            val prefs = currentState<Preferences>()
            val json1 = prefs[OccupancyJson1Key]
            val json2 = prefs[OccupancyJson2Key]
            val data1 = remember(json1) { json1?.let { parseOccupancyJson(it) } }
            val data2 = remember(json2) { json2?.let { parseOccupancyJson(it) } }

            val gymName1 = prefs[GymName1Key]
            val gymName2 = prefs[GymName2Key]

            val logoPath1 = prefs[LogoPath1Key]
            val logoPath2 = prefs[LogoPath2Key]

            val logoFile1 = remember(logoPath1) { logoPath1?.let { File(it) } }
            val logoFile2 = remember(logoPath2) { logoPath2?.let { File(it) } }

            val lastUpdated = prefs[LastUpdatedKey]

            DualWidgetContent(
                appWidgetId = appWidgetId,
                gymName1 = gymName1,
                dayUtilization1 = data1,
                logoFile1 = logoFile1,
                gymName2 = gymName2,
                dayUtilization2 = data2,
                logoFile2 = logoFile2,
                lastUpdated = lastUpdated
            )
        }
    }
}

@SuppressLint("RestrictedApi")
@Composable
private fun DualWidgetContent(
    appWidgetId: Int,
    gymName1: String?,
    dayUtilization1: DayUtilization?,
    logoFile1: File?,
    gymName2: String?,
    dayUtilization2: DayUtilization?,
    logoFile2: File?,
    lastUpdated: String? = null
) {
    val size = LocalSize.current
    val context = LocalContext.current
    val density = context.resources.displayMetrics.density

    val configSlot1Intent = Intent(context, DualWidgetConfigActivity::class.java).apply {
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra("target_slot", 1)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val configSlot2Intent = Intent(context, DualWidgetConfigActivity::class.java).apply {
        putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        putExtra("target_slot", 2)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val refreshAction = actionRunCallback<DualRefreshAction>(actionParametersOf(AppWidgetIdKey to appWidgetId))
    val configSlot1Action = actionStartActivity(configSlot1Intent)
    val configSlot2Action = actionStartActivity(configSlot2Intent)
    val lastUpdatedText = if (lastUpdated != null) "↻ $lastUpdated" else "↻"

    val isWide = size.width >= 240.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(R.color.widget_background)
            .padding(10.dp)
    ) {
        if (isWide) {
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.Vertical.Top
            ) {
                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .clickable(configSlot1Action),
                    verticalAlignment = Alignment.Vertical.Top
                ) {
                    SingleGymPanel(
                        gymName = gymName1 ?: "Gym 1",
                        dayUtilization = dayUtilization1,
                        logoFile = logoFile1,
                        size = size,
                        density = density,
                        lastUpdatedText = lastUpdatedText,
                        refreshAction = refreshAction
                    )
                }

                Spacer(modifier = GlanceModifier.width(8.dp))

                Spacer(
                    modifier = GlanceModifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(ColorProvider(R.color.widget_text_secondary))
                )

                Spacer(modifier = GlanceModifier.width(8.dp))

                Column(
                    modifier = GlanceModifier
                        .defaultWeight()
                        .fillMaxHeight()
                        .clickable(configSlot2Action),
                    verticalAlignment = Alignment.Vertical.Top
                ) {
                    SingleGymPanel(
                        gymName = gymName2 ?: "Gym 2",
                        dayUtilization = dayUtilization2,
                        logoFile = logoFile2,
                        size = size,
                        density = density,
                        lastUpdatedText = lastUpdatedText,
                        refreshAction = refreshAction
                    )
                }
            }
        } else {
            Column(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.Vertical.Top
            ) {
                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                        .clickable(configSlot1Action)
                ) {
                    SingleGymPanel(
                        gymName = gymName1 ?: "Gym 1",
                        dayUtilization = dayUtilization1,
                        logoFile = logoFile1,
                        size = size,
                        density = density,
                        lastUpdatedText = lastUpdatedText,
                        refreshAction = refreshAction
                    )
                }

                Spacer(modifier = GlanceModifier.height(6.dp))
                Spacer(
                    modifier = GlanceModifier
                        .height(1.dp)
                        .fillMaxWidth()
                        .background(ColorProvider(R.color.widget_text_secondary))
                )
                Spacer(modifier = GlanceModifier.height(6.dp))

                Column(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .defaultWeight()
                        .clickable(configSlot2Action)
                ) {
                    SingleGymPanel(
                        gymName = gymName2 ?: "Gym 2",
                        dayUtilization = dayUtilization2,
                        logoFile = logoFile2,
                        size = size,
                        density = density,
                        lastUpdatedText = lastUpdatedText,
                        refreshAction = refreshAction
                    )
                }
            }
        }
    }
}

@Composable
private fun SingleGymPanel(
    gymName: String,
    dayUtilization: DayUtilization?,
    logoFile: File?,
    size: androidx.compose.ui.unit.DpSize,
    density: Float,
    lastUpdatedText: String,
    refreshAction: androidx.glance.action.Action
) {
    val occupancyText = when {
        dayUtilization?.isClosed == true -> "Closed"
        dayUtilization != null -> "${dayUtilization.currentOccupancy}%"
        else -> "—"
    }

    val logoBitmap = if (logoFile != null && logoFile.exists()) {
        BitmapFactory.decodeFile(logoFile.absolutePath)
    } else null

    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.Top
    ) {
        // Top row: gym name | logo
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = gymName,
                style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
            if (logoBitmap != null) {
                Spacer(modifier = GlanceModifier.width(4.dp))
                Image(
                    provider = ImageProvider(logoBitmap),
                    contentDescription = gymName,
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.height(20.dp).width(20.dp)
                )
            }
        }

        Spacer(modifier = GlanceModifier.height(4.dp))

        // Occupancy % (left) and refresh button (right under logo)
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = occupancyText,
                style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = lastUpdatedText,
                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 10.sp),
                modifier = GlanceModifier
                    .background(ImageProvider(R.drawable.refresh_button_bg))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clickable(refreshAction)
            )
        }

        if (dayUtilization != null) {
            Spacer(modifier = GlanceModifier.height(2.dp))
            val chartW = (size.width.value * density * 0.45f).toInt()
            val chartH = (size.height.value * density * 0.65f).toInt()
            val chartBitmap = createOccupancyChart(dayUtilization, chartW, chartH, barSpacing = 3f, cornerRadius = 2.5f)
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
                    style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 9.sp)
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = dayUtilization.latestEndTime.take(5),
                    style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 9.sp)
                )
            }
        }
    }
}

class DualGymOccupancyReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DualGymOccupancyWidget()
}
