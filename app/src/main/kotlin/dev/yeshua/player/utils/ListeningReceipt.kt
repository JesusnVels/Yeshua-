/*
 * LunarTune (2026)
 * © cognitiveshadows03 — github.com/cognitiveshadows03
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package dev.yeshua.player.utils

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

data class ReceiptTrack(
    val title: String,
    val artist: String,
    val plays: Int,
)

data class ReceiptArtist(
    val name: String,
    val detail: String?,
)

data class ListeningReceiptData(
    val periodLabel: String,
    val totalPlays: Int,
    val totalTimeMs: Long,
    val uniqueSongs: Int,
    val uniqueArtists: Int,
    val topTracks: List<ReceiptTrack>,
    val topArtists: List<ReceiptArtist>,
)

/**
 * Renders a printed-receipt-style Bitmap from the stats engine's top
 * tracks/artists and listening totals, then shares it as a PNG image.
 */
object ListeningReceipt {
    private const val WIDTH = 1080
    private const val MARGIN = 72f
    private val INK: Int = 0xFF1C1A15.toInt()
    private val PAPER: Int = 0xFFF7F1E3.toInt()

    private sealed interface Row {
        data class Text(
            val text: String,
            val size: Float,
            val bold: Boolean,
            val centered: Boolean,
        ) : Row

        data object Separator : Row

        data object Barcode : Row

        data class Gap(
            val height: Float,
        ) : Row
    }

    fun render(data: ListeningReceiptData): Bitmap {
        val mono = Typeface.MONOSPACE
        val rows = mutableListOf<Row>()

        rows += Row.Text("YESHUA", 64f, bold = true, centered = true)
        rows += Row.Text("LISTENING RECEIPT", 34f, bold = false, centered = true)
        rows += Row.Gap(8f)
        rows += Row.Separator
        rows += Row.Text("PERIOD  ${data.periodLabel.uppercase(Locale.ROOT)}", 30f, bold = false, centered = false)
        val printedAt =
            SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date())
        rows += Row.Text("PRINTED $printedAt", 30f, bold = false, centered = false)
        rows += Row.Separator

        if (data.topTracks.isNotEmpty()) {
            rows += Row.Text("TOP TRACKS", 34f, bold = true, centered = false)
            rows += Row.Gap(6f)
            data.topTracks.forEachIndexed { index, track ->
                rows += Row.Text("${index + 1}. ${track.title}", 30f, bold = true, centered = false)
                rows += Row.Text("   ${track.artist} - ${track.plays} plays", 28f, bold = false, centered = false)
                rows += Row.Gap(10f)
            }
            rows += Row.Separator
        }

        if (data.topArtists.isNotEmpty()) {
            rows += Row.Text("TOP ARTISTS", 34f, bold = true, centered = false)
            rows += Row.Gap(6f)
            data.topArtists.forEachIndexed { index, artist ->
                val detail = artist.detail?.let { " ($it)" } ?: ""
                rows += Row.Text("${index + 1}. ${artist.name}$detail", 30f, bold = false, centered = false)
                rows += Row.Gap(10f)
            }
            rows += Row.Separator
        }

        rows += Row.Text("TOTAL PLAYS    ${data.totalPlays}", 32f, bold = true, centered = false)
        rows += Row.Text("TIME LISTENED  ${formatDuration(data.totalTimeMs)}", 32f, bold = true, centered = false)
        rows += Row.Text("UNIQUE SONGS   ${data.uniqueSongs}", 32f, bold = false, centered = false)
        rows += Row.Text("UNIQUE ARTISTS ${data.uniqueArtists}", 32f, bold = false, centered = false)
        rows += Row.Separator
        rows += Row.Barcode
        rows += Row.Gap(8f)
        rows += Row.Text("*** THANK YOU FOR LISTENING ***", 30f, bold = false, centered = true)

        val contentWidth = WIDTH - MARGIN * 2
        var height = 96f // top padding
        val measured =
            rows.map { row ->
                when (row) {
                    is Row.Text -> {
                        val paint = newPaint(mono, row.size, row.bold)
                        val wrapped = wrapText(row.text, paint, contentWidth)
                        val lineHeight = lineHeight(paint)
                        height += wrapped.size * lineHeight + 6f
                        row to wrapped.map { it to paint }
                    }

                    is Row.Separator -> {
                        height += 44f
                        row to emptyList()
                    }

                    is Row.Barcode -> {
                        height += 150f
                        row to emptyList()
                    }

                    is Row.Gap -> {
                        height += row.height
                        row to emptyList()
                    }
                }
            }
        height += 96f // bottom padding

        val bitmap = createBitmap(WIDTH, height.toInt().coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)

        val dashPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                style = Paint.Style.STROKE
                strokeWidth = 3f
                pathEffect = DashPathEffect(floatArrayOf(20f, 14f), 0f)
            }

        var y = 96f
        val barcodeRandom = Random(data.hashCode())
        measured.forEach { (row, wrapped) ->
            when (row) {
                is Row.Text -> {
                    @Suppress("UNCHECKED_CAST")
                    val lines = wrapped as List<Pair<String, Paint>>
                    lines.forEach { (text, paint) ->
                        paint.textAlign = if (row.centered) Paint.Align.CENTER else Paint.Align.LEFT
                        val x = if (row.centered) WIDTH / 2f else MARGIN
                        val fm = paint.fontMetrics
                        y += (fm.descent - fm.ascent)
                        canvas.drawText(text, x, y - fm.descent, paint)
                        y += 6f
                    }
                }

                is Row.Separator -> {
                    val path = Path()
                    path.moveTo(MARGIN, y + 22f)
                    path.lineTo(WIDTH - MARGIN, y + 22f)
                    canvas.drawPath(path, dashPaint)
                    y += 44f
                }

                is Row.Barcode -> {
                    var x = MARGIN
                    val barTop = y + 20f
                    val barBottom = y + 120f
                    while (x < WIDTH - MARGIN - 8f) {
                        val barWidth = barcodeRandom.nextInt(4, 14).toFloat()
                        if (barcodeRandom.nextBoolean()) {
                            canvas.drawRect(x, barTop, x + barWidth, barBottom, Paint().apply { color = INK })
                        }
                        x += barWidth + barcodeRandom.nextInt(3, 10)
                    }
                    y += 150f
                }

                is Row.Gap -> {
                    y += row.height
                }
            }
        }

        return bitmap
    }

    suspend fun share(
        context: Context,
        data: ListeningReceiptData,
    ) {
        val bitmap =
            withContext(Dispatchers.Default) {
                render(data)
            }
        val uri =
            withContext(Dispatchers.IO) {
                val receiptsDir = File(context.cacheDir, "receipts").apply { mkdirs() }
                val file = File(receiptsDir, "yeshua-receipt-${System.currentTimeMillis()}.png")
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.FileProvider",
                    file,
                )
            }
        withContext(Dispatchers.Main) {
            val intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            context.startActivity(
                Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun newPaint(
        mono: Typeface,
        size: Float,
        bold: Boolean,
    ): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            textSize = size
            typeface = Typeface.create(mono, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun lineHeight(paint: Paint): Float {
        val fm = paint.fontMetrics
        return fm.descent - fm.ascent
    }

    private fun wrapText(
        text: String,
        paint: Paint,
        maxWidth: Float,
    ): List<String> {
        if (paint.measureText(text) <= maxWidth) return listOf(text)
        val words = text.split(' ')
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val trial = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(trial) <= maxWidth) {
                current = StringBuilder(trial)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(word)
                while (current.isNotEmpty() && paint.measureText(current.toString()) > maxWidth) {
                    val count = paint.breakText(current.toString(), true, maxWidth, null).coerceAtLeast(1)
                    lines += current.substring(0, count)
                    current = StringBuilder(current.substring(count))
                }
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines.ifEmpty { listOf("") }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000).toInt().coerceAtLeast(0)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }
}
