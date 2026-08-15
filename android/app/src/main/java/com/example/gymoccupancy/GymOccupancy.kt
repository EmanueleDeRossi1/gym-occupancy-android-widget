package com.example.gymoccupancy

import androidx.core.content.edit
import android.content.Context
import android.graphics.BitmapFactory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt

private val httpClient = OkHttpClient()

// Slot-aware functions to save/retrieve gym id, operator id, name, and logo url for each widget slot

fun saveGymId(context: Context, appWidgetId: Int, gymId: String, slot: Int = 1) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    val key = if (slot == 1) "gym_id_$appWidgetId" else "gym_id_${slot}_$appWidgetId"
    prefs.edit { putString(key, gymId) }
}

fun saveOperatorId(context: Context, appWidgetId: Int, operatorId: String, slot: Int = 1) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    val key = if (slot == 1) "operator_id_$appWidgetId" else "operator_id_${slot}_$appWidgetId"
    prefs.edit { putString(key, operatorId) }
}

fun saveGymName(context: Context, appWidgetId: Int, gymName: String, slot: Int = 1) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    val key = if (slot == 1) "gym_name_$appWidgetId" else "gym_name_${slot}_$appWidgetId"
    prefs.edit { putString(key, gymName) }
}

fun getGymId(context: Context, appWidgetId: Int, slot: Int = 1): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    val slotKey = "gym_id_${slot}_$appWidgetId"
    return prefs.getString(slotKey, null)
        ?: if (slot == 1) prefs.getString("gym_id_$appWidgetId", null) else null
}

fun getOperatorId(context: Context, appWidgetId: Int, slot: Int = 1): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    val slotKey = "operator_id_${slot}_$appWidgetId"
    return prefs.getString(slotKey, null)
        ?: if (slot == 1) prefs.getString("operator_id_$appWidgetId", null) else null
}

fun getGymName(context: Context, appWidgetId: Int, slot: Int = 1): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    val slotKey = "gym_name_${slot}_$appWidgetId"
    return prefs.getString(slotKey, null)
        ?: if (slot == 1) prefs.getString("gym_name_$appWidgetId", null) else null
}

fun saveLogoUrl(context: Context, appWidgetId: Int, logoUrl: String?, slot: Int = 1) {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    val key = if (slot == 1) "logo_url_$appWidgetId" else "logo_url_${slot}_$appWidgetId"
    prefs.edit {
        if (logoUrl != null) putString(key, logoUrl)
        else remove(key)
    }
}

fun getLogoUrl(context: Context, appWidgetId: Int, slot: Int = 1): String? {
    val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)
    val slotKey = "logo_url_${slot}_$appWidgetId"
    return prefs.getString(slotKey, null)
        ?: if (slot == 1) prefs.getString("logo_url_$appWidgetId", null) else null
}

fun logoFileForWidget(context: Context, appWidgetId: Int, slot: Int = 1): File =
    if (slot == 1) File(context.filesDir, "logo_$appWidgetId.png")
    else File(context.filesDir, "logo_${slot}_$appWidgetId.png")

suspend fun fetchAndCacheLogo(context: Context, appWidgetId: Int, slot: Int = 1): File? =
    withContext(Dispatchers.IO) {
        val logoUrl = getLogoUrl(context, appWidgetId, slot) ?: return@withContext null
        val file = logoFileForWidget(context, appWidgetId, slot)
        try {
            val request = Request.Builder().url(logoUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                // Validate it's a real image before writing
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                file.writeBytes(bytes)
                file
            }
        } catch (e: Exception) {
            android.util.Log.e("GymWidget", "fetchAndCacheLogo error: ${e.message}")
            null
        }
    }

fun getOccupancyColor(occupancy: Int): Int {
    return when {
        occupancy < 50 -> blendColors("#10B981", "#F59E0B", occupancy / 50f)
        else -> blendColors("#F59E0B", "#EF4444", (occupancy - 50) / 50f)
    }
}

fun blendColors(color1: String, color2: String, ratio: Float): Int {
    val c1 = color1.toColorInt()
    val c2 = color2.toColorInt()
    val r = (Color.red(c1) * (1 - ratio) + Color.red(c2) * ratio).toInt()
    val g = (Color.green(c1) * (1 - ratio) + Color.green(c2) * ratio).toInt()
    val b = (Color.blue(c1) * (1 - ratio) + Color.blue(c2) * ratio).toInt()
    return Color.rgb(r, g, b)
}

fun createOccupancyChart(
    dayUtilization: DayUtilization,
    width: Int,
    height: Int,
    barSpacing: Float = 6f,
    minBarHeight: Float = 4f,
    cornerRadius: Float = 3f
): Bitmap? {
    if (width <= 0 || height <= 0 || dayUtilization.totalSlots == 0) return null

    val bitmap = createBitmap(width, height)
    val canvas = Canvas(bitmap)
    canvas.drawColor("#353535".toColorInt())

    val effectiveBarWidth = maxOf(1f, (width.toFloat() / dayUtilization.totalSlots) - barSpacing)
    val currentIndex = dayUtilization.slots.indexOfFirst { it.isCurrent }

    for ((i, slot) in dayUtilization.slots.withIndex()) {
        val left = i * (effectiveBarWidth + barSpacing)
        val right = left + effectiveBarWidth

        if (slot.occupancy != null && slot.occupancy > 0) {
            val occupancy = slot.occupancy
            val barHeight = maxOf((occupancy * height / 100f), minBarHeight)
            val top = height - barHeight

            val baseColor = when {
                i < currentIndex -> "#6B6B6B".toColorInt()
                i == currentIndex -> getOccupancyColor(occupancy)
                else -> "#9E9E9E".toColorInt()
            }

            val r = Color.red(baseColor)
            val g = Color.green(baseColor)
            val b = Color.blue(baseColor)

            val paint = Paint().apply {
                style = Paint.Style.FILL
                isAntiAlias = true
                shader = LinearGradient(
                    0f, top, 0f, height.toFloat(),
                    Color.argb(200, r, g, b),
                    Color.argb(40, r, g, b),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(left, top, right, height.toFloat(), cornerRadius, cornerRadius, paint)
        } else {
            val top = height - minBarHeight
            val paint = Paint().apply {
                style = Paint.Style.FILL
                isAntiAlias = true
                color = Color.argb(35, 255, 255, 255)
            }
            canvas.drawRoundRect(left, top, right, height.toFloat(), cornerRadius, cornerRadius, paint)
        }
    }

    return bitmap
}

data class UtilizationSlot(
    val startTime: String,
    val endTime: String,
    // null = unknown (future hour with no forecast yet); 0 = genuinely empty
    val occupancy: Int?,
    val isCurrent: Boolean,
    val index: Int
    )

data class DayUtilization(
    val slots: List<UtilizationSlot>,
    val currentOccupancy: Int,
    val earliestStartTime: String,
    val latestEndTime: String,
    val totalSlots: Int,
    val isClosed: Boolean
)

// Fetch the raw occupancy JSON body from the proxy. We store this raw string in
// Glance state and parse it at render time, so the network call is decoupled from
// the widget composition lifecycle.
suspend fun fetchOccupancyRaw(operatorId: String, gymId: String): String? =
    withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://gym-occupancy-proxy.ederossi.workers.dev/$operatorId/$gymId/occupancy")
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string()
            }
        } catch (e: Exception) {
            android.util.Log.e("GymWidget", "fetchOccupancyRaw error: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

// Parse the proxy's occupancy JSON body into a DayUtilization. Pure/synchronous so
// it can run inside the Glance composition when reading from state.
fun parseOccupancyJson(body: String): DayUtilization? {
    return try {
        val jsonArray = JSONArray(body)
        val allSlots = mutableListOf<UtilizationSlot>()
        var currentOccupancy = 0

        for (i in 0 until jsonArray.length()) {
            val jsonSlot = jsonArray.optJSONObject(i) ?: continue

            // null in JSON = unknown future hour; keep it null (optInt would coerce to 0)
            val occupancy = if (jsonSlot.isNull("occupancy")) null else jsonSlot.optInt("occupancy", 0)
            val isCurrent = jsonSlot.optBoolean("isCurrent", false)
            val startTime = jsonSlot.optString("startTime", "")
            val endTime = jsonSlot.optString("endTime", "")
            val slot = UtilizationSlot(startTime, endTime, occupancy, isCurrent, i)
            allSlots.add(slot)

            if (isCurrent) {
                currentOccupancy = occupancy ?: 0
            }
        }
        if (allSlots.isEmpty()) return null

        // what if there is no current (the gym may be closed at this time)
        val currentStartTime = allSlots.find { it.isCurrent }?.startTime
        val earliestStartTime = allSlots.minOf { it.startTime }
        val latestEndTime = allSlots.maxOf { it.endTime }

        // Keep the full day: future hours arrive with occupancy = null
        // (unknown) and render as empty until forecasts are added.
        DayUtilization(
            slots = allSlots,
            currentOccupancy = currentOccupancy,
            earliestStartTime = earliestStartTime,
            latestEndTime = latestEndTime,
            totalSlots = allSlots.size,
            isClosed = currentStartTime == null
        )
    } catch (e: Exception) {
        android.util.Log.e("GymWidget", "parseOccupancyJson error: ${e.javaClass.simpleName}: ${e.message}", e)
        null
    }
}

suspend fun fetchOccupancyData(operatorId: String, gymId: String): DayUtilization? =
    fetchOccupancyRaw(operatorId, gymId)?.let { parseOccupancyJson(it) }

