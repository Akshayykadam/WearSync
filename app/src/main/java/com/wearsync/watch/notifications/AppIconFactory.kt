package com.wearsync.watch.notifications

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.core.content.ContextCompat
import com.wearsync.watch.R
import com.wearsync.watch.ancs.AncsCategoryId
import kotlin.math.abs

/**
 * Generates and caches circular app icons (with brand colors & symbols) for Wear OS
 * notification largeIcon slots so each iPhone app displays its own recognizable icon.
 */
object AppIconFactory {

    private const val ICON_SIZE_PX = 128
    private const val MAX_CACHED_ICONS = 24
    private val iconCache = object : LruCache<String, Bitmap>(MAX_CACHED_ICONS) {}

    private val iosToWatchPackages = mapOf(
        "com.google.Gmail" to "com.google.android.gm",
        "net.whatsapp.WhatsApp" to "com.whatsapp",
        "net.whatsapp.WhatsAppSMB" to "com.whatsapp.w4b",
        "com.apple.MobileSMS" to "com.google.android.apps.messaging",
        "com.spotify.client" to "com.spotify.music",
        "com.apple.Maps" to "com.google.android.apps.maps",
        "com.microsoft.skype.teams" to "com.microsoft.teams"
    )

    private val fallbackColors = intArrayOf(
        Color.parseColor("#0A84FF"),
        Color.parseColor("#30D158"),
        Color.parseColor("#FF9F0A"),
        Color.parseColor("#BF5AF2"),
        Color.parseColor("#FF375F"),
        Color.parseColor("#64D2FF"),
        Color.parseColor("#5E5CE6")
    )

    fun createAppIcon(
        context: Context,
        appIdentifier: String,
        appDisplayName: String,
        categoryId: AncsCategoryId
    ): Bitmap {
        val cacheKey = "$appIdentifier|$appDisplayName|${categoryId.name}"
        iconCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        // 1. If the equivalent Wear OS app is installed on the watch, use its native icon
        iosToWatchPackages[appIdentifier]?.let { watchPkg ->
            loadInstalledAppCircle(context, watchPkg)?.let { installedBitmap ->
                iconCache.put(cacheKey, installedBitmap)
                return installedBitmap
            }
        }

        val bitmap = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = ICON_SIZE_PX / 2f
        val radius = ICON_SIZE_PX / 2f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }

        when {
            appIdentifier.equals("com.google.Gmail", ignoreCase = true) ||
                appDisplayName.equals("Gmail", ignoreCase = true) -> {
                bgPaint.color = Color.WHITE
                canvas.drawCircle(center, center, radius, bgPaint)
                drawGmailM(canvas)
            }

            appIdentifier.startsWith("net.whatsapp", ignoreCase = true) ||
                appDisplayName.contains("WhatsApp", ignoreCase = true) -> {
                bgPaint.color = Color.parseColor("#25D366")
                canvas.drawCircle(center, center, radius, bgPaint)
                drawWhiteVector(context, canvas, R.drawable.ic_whatsapp, 0.62f)
            }

            appIdentifier == "com.apple.MobileSMS" ||
                appDisplayName.equals("Messages", ignoreCase = true) -> {
                bgPaint.color = Color.parseColor("#34C759")
                canvas.drawCircle(center, center, radius, bgPaint)
                drawWhiteVector(context, canvas, R.drawable.ic_message, 0.56f)
            }

            appIdentifier == "com.apple.mobilephone" ||
                appIdentifier == "com.apple.facetime" ||
                categoryId == AncsCategoryId.INCOMING_CALL ||
                categoryId == AncsCategoryId.MISSED_CALL -> {
                bgPaint.color = Color.parseColor("#34C759")
                canvas.drawCircle(center, center, radius, bgPaint)
                drawWhiteVector(context, canvas, R.drawable.ic_call, 0.56f)
            }

            appIdentifier == "com.apple.mobilemail" ||
                appIdentifier.contains("outlook", ignoreCase = true) ||
                categoryId == AncsCategoryId.EMAIL -> {
                bgPaint.color = Color.parseColor("#0A84FF")
                canvas.drawCircle(center, center, radius, bgPaint)
                drawWhiteVector(context, canvas, R.drawable.ic_mail, 0.56f)
            }

            appIdentifier == "com.burbn.instagram" ||
                appDisplayName.equals("Instagram", ignoreCase = true) -> {
                bgPaint.shader = LinearGradient(
                    0f, ICON_SIZE_PX.toFloat(), ICON_SIZE_PX.toFloat(), 0f,
                    intArrayOf(
                        Color.parseColor("#F58529"),
                        Color.parseColor("#DD2A7B"),
                        Color.parseColor("#8134AF")
                    ),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawCircle(center, center, radius, bgPaint)
                drawInstagramGlyph(canvas)
            }

            appIdentifier == "ph.telegra.Telegraph" ||
                appDisplayName.equals("Telegram", ignoreCase = true) -> {
                bgPaint.color = Color.parseColor("#26A5E4")
                canvas.drawCircle(center, center, radius, bgPaint)
                drawMonogram(canvas, "TG", fgPaint)
            }

            appIdentifier == "com.atebits.Tweetie2" ||
                appDisplayName.equals("X", ignoreCase = true) -> {
                bgPaint.color = Color.parseColor("#1C1C1E")
                canvas.drawCircle(center, center, radius, bgPaint)
                drawMonogram(canvas, "X", fgPaint)
            }

            appIdentifier == "com.hammerandchisel.discord" ||
                appDisplayName.equals("Discord", ignoreCase = true) -> {
                bgPaint.color = Color.parseColor("#5865F2")
                canvas.drawCircle(center, center, radius, bgPaint)
                drawMonogram(canvas, "D", fgPaint)
            }

            appIdentifier.equals("com.tinyspeck.chatlyio", ignoreCase = true) ||
                appIdentifier.contains("tinyspeck", ignoreCase = true) ||
                appIdentifier.contains("slack", ignoreCase = true) ||
                appDisplayName.equals("Slack", ignoreCase = true) -> {
                bgPaint.color = Color.parseColor("#4A154B")
                canvas.drawCircle(center, center, radius, bgPaint)
                drawMonogram(canvas, "#", fgPaint)
            }

            else -> {
                val label = appDisplayName.ifBlank { "iPhone" }
                val colorIdx = abs(label.hashCode()) % fallbackColors.size
                bgPaint.color = fallbackColors[colorIdx]
                canvas.drawCircle(center, center, radius, bgPaint)
                val letter = label.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "i"
                drawMonogram(canvas, letter, fgPaint)
            }
        }

        iconCache.put(cacheKey, bitmap)
        return bitmap
    }

    fun badgeColorFor(appIdentifier: String, appDisplayName: String, categoryId: AncsCategoryId): Int {
        return when {
            appIdentifier.equals("com.google.Gmail", ignoreCase = true) -> Color.parseColor("#EA4335")
            appIdentifier.startsWith("net.whatsapp", ignoreCase = true) -> Color.parseColor("#25D366")
            appIdentifier == "com.apple.MobileSMS" -> Color.parseColor("#34C759")
            appIdentifier == "com.apple.mobilephone" || categoryId == AncsCategoryId.MISSED_CALL || categoryId == AncsCategoryId.INCOMING_CALL -> Color.parseColor("#34C759")
            appIdentifier == "com.apple.mobilemail" || categoryId == AncsCategoryId.EMAIL -> Color.parseColor("#0A84FF")
            appIdentifier == "com.burbn.instagram" -> Color.parseColor("#DD2A7B")
            else -> Color.parseColor("#636366")
        }
    }

    private fun loadInstalledAppCircle(context: Context, packageName: String): Bitmap? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val raw = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap
            } else {
                val b = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                drawable.setBounds(0, 0, ICON_SIZE_PX, ICON_SIZE_PX)
                drawable.draw(c)
                b
            }
            val scaled = Bitmap.createScaledBitmap(raw, ICON_SIZE_PX, ICON_SIZE_PX, true)
            val out = Bitmap.createBitmap(ICON_SIZE_PX, ICON_SIZE_PX, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawCircle(ICON_SIZE_PX / 2f, ICON_SIZE_PX / 2f, ICON_SIZE_PX / 2f, paint)
            paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
            c.drawBitmap(scaled, 0f, 0f, paint)
            out
        } catch (_: Exception) {
            null
        }
    }

    private fun drawWhiteVector(context: Context, canvas: Canvas, drawableId: Int, scale: Float) {
        val d = ContextCompat.getDrawable(context, drawableId)?.mutate() ?: return
        d.setTint(Color.WHITE)
        val pad = ((1f - scale) * ICON_SIZE_PX / 2f).toInt()
        d.setBounds(pad, pad, ICON_SIZE_PX - pad, ICON_SIZE_PX - pad)
        d.draw(canvas)
    }

    private fun drawGmailM(canvas: Canvas) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 14f
        }
        // Left vertical (Blue)
        stroke.color = Color.parseColor("#4285F4")
        canvas.drawLine(32f, 88f, 32f, 44f, stroke)
        // Left diagonal (Red)
        stroke.color = Color.parseColor("#EA4335")
        canvas.drawLine(32f, 44f, 64f, 68f, stroke)
        // Right diagonal (Yellow -> Red)
        stroke.color = Color.parseColor("#FBBC04")
        canvas.drawLine(64f, 68f, 96f, 44f, stroke)
        // Right vertical (Green)
        stroke.color = Color.parseColor("#34A853")
        canvas.drawLine(96f, 44f, 96f, 88f, stroke)
    }

    private fun drawInstagramGlyph(canvas: Canvas) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        canvas.drawRoundRect(32f, 32f, 96f, 96f, 18f, 18f, stroke)
        canvas.drawCircle(64f, 64f, 16f, stroke)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(83f, 45f, 4.5f, dot)
    }

    private fun drawMonogram(canvas: Canvas, text: String, paint: Paint) {
        val textPaint = Paint(paint).apply {
            textAlign = Paint.Align.CENTER
            textSize = if (text.length > 1) 46f else 58f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val y = ICON_SIZE_PX / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, ICON_SIZE_PX / 2f, y, textPaint)
    }
}
