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

fun saveDualGymId(context: Context, appWidgetId: Int, slot: Int, gymId: String) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit { putString("dual_gym_id_${slot}_$appWidgetId", gymId) }
}

fun saveDualOperatorId(context: Context, appWidgetId: Int, slot: Int, operatorId: String) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit { putString("dual_operator_id_${slot}_$appWidgetId", operatorId) }
}

fun saveDualGymName(context: Context, appWidgetId: Int, slot: Int, gymName: String) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit { putString("dual_gym_name_${slot}_$appWidgetId", gymName) }
}

fun saveDualLogoUrl(context: Context, appWidgetId: Int, slot: Int, logoUrl: String?) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    prefs.edit {
        if (logoUrl != null) putString("dual_logo_url_${slot}_$appWidgetId", logoUrl)
        else remove("dual_logo_url_${slot}_$appWidgetId")
    }
}

fun getDualGymId(context: Context, appWidgetId: Int, slot: Int): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    return prefs.getString("dual_gym_id_${slot}_$appWidgetId", null)
}

fun getDualOperatorId(context: Context, appWidgetId: Int, slot: Int): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    return prefs.getString("dual_operator_id_${slot}_$appWidgetId", null)
}

fun getDualGymName(context: Context, appWidgetId: Int, slot: Int): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    return prefs.getString("dual_gym_name_${slot}_$appWidgetId", null)
}

fun getDualLogoUrl(context: Context, appWidgetId: Int, slot: Int): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    return prefs.getString("dual_logo_url_${slot}_$appWidgetId", null)
}

fun dualLogoFileForWidget(context: Context, appWidgetId: Int, slot: Int): File =
    File(context.filesDir, "dual_logo_${slot}_$appWidgetId.png")

suspend fun fetchAndCacheDualLogo(context: Context, appWidgetId: Int, slot: Int): File? =
    withContext(Dispatchers.IO) {
        val logoUrl = getDualLogoUrl(context, appWidgetId, slot) ?: return@withContext null
        val file = dualLogoFileForWidget(context, appWidgetId, slot)
        try {
            val request = Request.Builder().url(logoUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                file.writeBytes(bytes)
                file
            }
        } catch (e: Exception) {
            android.util.Log.e("GymWidget", "fetchAndCacheDualLogo error: ${e.message}")
            null
        }
    }

suspend fun loadDualOccupancyIntoState(context: Context, appWidgetId: Int) = coroutineScope {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)

    val gymId1 = getDualGymId(context, appWidgetId, 1)
    val operatorId1 = getDualOperatorId(context, appWidgetId, 1)
    val gymName1 = getDualGymName(context, appWidgetId, 1)
    val cachedLogo1 = dualLogoFileForWidget(context, appWidgetId, 1)

    val gymId2 = getDualGymId(context, appWidgetId, 2)
    val operatorId2 = getDualOperatorId(context, appWidgetId, 2)
    val gymName2 = getDualGymName(context, appWidgetId, 2)
    val cachedLogo2 = dualLogoFileForWidget(context, appWidgetId, 2)

    val json1Deferred = async { if (gymId1 != null && operatorId1 != null) fetchOccupancyRaw(operatorId1, gymId1) else null }
    val json2Deferred = async { if (gymId2 != null && operatorId2 != null) fetchOccupancyRaw(operatorId2, gymId2) else null }
    val logo1Deferred = async { if (cachedLogo1.exists()) cachedLogo1 else fetchAndCacheDualLogo(context, appWidgetId, 1) }
    val logo2Deferred = async { if (cachedLogo2.exists()) cachedLogo2 else fetchAndCacheDualLogo(context, appWidgetId, 2) }

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
        // Header with title and refresh button
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = "Gym Occupancy",
                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 11.sp, fontWeight = FontWeight.Bold),
                modifier = GlanceModifier.defaultWeight()
            )
            Text(
                text = lastUpdatedText,
                style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 11.sp),
                modifier = GlanceModifier
                    .background(ImageProvider(R.drawable.refresh_button_bg))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .clickable(refreshAction)
            )
        }

        Spacer(modifier = GlanceModifier.height(6.dp))

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
                        density = density
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
                        density = density
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
                        density = density
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
                        density = density
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
    density: Float
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
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            if (logoBitmap != null) {
                Image(
                    provider = ImageProvider(logoBitmap),
                    contentDescription = gymName,
                    contentScale = ContentScale.Fit,
                    modifier = GlanceModifier.height(24.dp).width(24.dp)
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
            }
            Text(
                text = gymName,
                style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight()
            )
        }

        Spacer(modifier = GlanceModifier.height(2.dp))

        Text(
            text = occupancyText,
            style = TextStyle(color = ColorProvider(R.color.widget_text_primary), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        )

        if (dayUtilization != null) {
            Spacer(modifier = GlanceModifier.height(4.dp))
            val chartW = (size.width.value * density * 0.45f).toInt()
            val chartH = (size.height.value * density * 0.4f).toInt()
            val chartBitmap = createDualOccupancyChart(dayUtilization, chartW, chartH)
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
                    style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 10.sp)
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = dayUtilization.latestEndTime.take(5),
                    style = TextStyle(color = ColorProvider(R.color.widget_text_secondary), fontSize = 10.sp)
                )
            }
        }
    }
}

private fun createDualOccupancyChart(dayUtilization: DayUtilization, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0 || dayUtilization.totalSlots == 0) return null

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)

    val barSpacing = 3f
    val barWidth = maxOf(1f, (width.toFloat() / dayUtilization.totalSlots) - barSpacing)
    val minBarHeight = 4f
    val cornerRadius = 2.5f

    val currentIndex = dayUtilization.slots.indexOfFirst { it.isCurrent }

    for ((i, slot) in dayUtilization.slots.withIndex()) {
        val left = i * (barWidth + barSpacing)
        val right = left + barWidth
        val isFuture = currentIndex != -1 && i > currentIndex

        if (slot.occupancy != null && slot.occupancy > 0) {
            val occupancy = slot.occupancy
            val barHeight = maxOf((occupancy * height / 100f), minBarHeight)
            val top = height - barHeight

            val (startAlpha, endAlpha, baseColor) = when {
                i < currentIndex -> Triple(220, 50, "#6B6B6B".toColorInt())
                i == currentIndex -> Triple(240, 80, getOccupancyColor(occupancy))
                else -> Triple(200, 40, "#9E9E9E".toColorInt()) // Light gray matching single gym widget
            }

            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)

            val paint = Paint().apply {
                style = Paint.Style.FILL
                isAntiAlias = true
                shader = LinearGradient(
                    0f, top, 0f, height.toFloat(),
                    Color.argb(startAlpha, r, g, b),
                    Color.argb(endAlpha, r, g, b),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(left, top, right, height.toFloat(), cornerRadius, cornerRadius, paint)
        } else {
            // Future hour baseline placeholder
            val top = height - minBarHeight
            val paint = Paint().apply {
                style = Paint.Style.FILL
                isAntiAlias = true
                color = if (isFuture) Color.argb(45, 158, 158, 158) else Color.argb(35, 255, 255, 255)
            }
            canvas.drawRoundRect(left, top, right, height.toFloat(), cornerRadius, cornerRadius, paint)
        }
    }

    return bitmap
}

class DualGymOccupancyReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DualGymOccupancyWidget()
}
