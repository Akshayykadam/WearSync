package com.wearsync.watch.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.wearsync.watch.R
import com.wearsync.watch.ancs.ConnectionStatus
import com.wearsync.watch.data.DeviceStore
import com.wearsync.watch.presentation.MainActivity
import com.wearsync.watch.service.WearSyncService

/**
 * Watch face complication exposing the connected iPhone's live battery level
 * over BLE Battery Service (0x180F).
 */
class PhoneBatteryComplicationService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "PhoneBatteryComp"

        fun requestUpdate(context: Context) {
            try {
                val requester = ComplicationDataSourceUpdateRequester.create(
                    context = context.applicationContext,
                    complicationDataSourceComponent = ComponentName(
                        context.applicationContext,
                        PhoneBatteryComplicationService::class.java
                    )
                )
                requester.requestUpdateAll()
            } catch (e: Exception) {
                Log.d(TAG, "Unable to request complication update: ${e.message}")
            }
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return buildComplicationData(
            type = type,
            batteryLevel = 84,
            isConnected = true
        )
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val service = WearSyncService.activeInstance
        val isConnected = service?.bleManager?.connectionState?.value == ConnectionStatus.CONNECTED
        val battery = service?.bleManager?.phoneBatteryLevel?.value
            ?: DeviceStore(this).getPhoneBatteryLevel()?.takeIf { isConnected }

        return buildComplicationData(
            type = request.complicationType,
            batteryLevel = battery,
            isConnected = isConnected
        )
    }

    private fun buildComplicationData(
        type: ComplicationType,
        batteryLevel: Int?,
        isConnected: Boolean
    ): ComplicationData? {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(this, R.drawable.ic_iphone_badge)
        ).build()

        val displayLevel = batteryLevel?.coerceIn(0, 100)
        val valueText = when {
            displayLevel != null -> "$displayLevel%"
            isConnected -> "…"
            else -> "--"
        }
        val contentDesc = when {
            displayLevel != null -> "iPhone battery $displayLevel percent"
            isConnected -> "iPhone connected"
            else -> "iPhone disconnected"
        }

        return when (type) {
            ComplicationType.RANGED_VALUE -> {
                RangedValueComplicationData.Builder(
                    value = (displayLevel ?: 0).toFloat(),
                    min = 0f,
                    max = 100f,
                    contentDescription = PlainComplicationText.Builder(contentDesc).build()
                )
                    .setText(PlainComplicationText.Builder(valueText).build())
                    .setMonochromaticImage(icon)
                    .setTapAction(tapPendingIntent)
                    .build()
            }

            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    text = PlainComplicationText.Builder(valueText).build(),
                    contentDescription = PlainComplicationText.Builder(contentDesc).build()
                )
                    .setMonochromaticImage(icon)
                    .setTapAction(tapPendingIntent)
                    .build()
            }

            else -> null
        }
    }
}
