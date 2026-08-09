package com.example.gymoccupancy

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

        enableEdgeToEdge()
        setContent {
            DualGymPickerFlow(
                appWidgetId = appWidgetId,
                targetSlot = targetSlot,
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

@Composable
fun DualGymPickerFlow(appWidgetId: Int, targetSlot: Int, onComplete: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val singleSlotMode = targetSlot in 1..2
    var currentSlot by remember { mutableIntStateOf(if (singleSlotMode) targetSlot else 1) }
    var gyms by remember { mutableStateOf<List<Gym>?>(null) }
    var selectedBrand by remember { mutableStateOf<Brand?>(null) }

    LaunchedEffect(Unit) {
        gyms = fetchGyms()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
    ) {
        when {
            gyms == null -> {
                CircularProgressIndicator(
                    color = Accent,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            selectedBrand == null -> {
                val brands = remember(gyms) {
                    gyms!!
                        .groupBy { it.brand }
                        .map { (brand, list) -> Brand(brand, brand, list.first().logoUrl) }
                        .sortedBy { it.label }
                }
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                    val titleText = if (singleSlotMode) "Select Gym $currentSlot" else "Select Gym $currentSlot of 2"
                    Text(
                        text = titleText,
                        color = TextPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp)
                    )
                    BrandScreenContent(brands = brands, onBrandSelected = { selectedBrand = it })
                }
            }
            else -> {
                val filtered = remember(gyms, selectedBrand) {
                    gyms!!.filter { it.brand == selectedBrand!!.id }.sortedBy { it.displayName }
                }
                GymScreen(
                    brand = selectedBrand!!,
                    gyms = filtered,
                    onBack = { selectedBrand = null },
                    onGymSelected = { gym ->
                        saveDualGymId(context, appWidgetId, currentSlot, gym.id)
                        saveDualOperatorId(context, appWidgetId, currentSlot, gym.operatorId)
                        saveDualGymName(context, appWidgetId, currentSlot, gym.location)
                        saveDualLogoUrl(context, appWidgetId, currentSlot, gym.logoUrl)
                        dualLogoFileForWidget(context, appWidgetId, currentSlot).delete()

                        if (!singleSlotMode && currentSlot == 1) {
                            currentSlot = 2
                            selectedBrand = null
                        } else {
                            onComplete()
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun BrandScreenContent(brands: List<Brand>, onBrandSelected: (Brand) -> Unit) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        items(brands.size) { index ->
            val brand = brands[index]
            BrandCard(brand = brand, onClick = { onBrandSelected(brand) })
        }
    }
}
