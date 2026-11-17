package com.example.lecteurecran

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Toast
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class OcrLine(val text: String, val rect: Rect)
data class OcrBlock(val text: String, val rect: Rect, val lines: List<OcrLine>)

/**
 * Calque plein écran affiché par-dessus l'appli lue.
 * - un tap sur un bloc de texte détecté le fait lire ;
 * - un glissement dessine un rectangle : toutes les lignes dedans sont lues (secours).
 * Les rectangles OCR sont en coordonnées écran ; on convertit avec getLocationOnScreen.
 */
class PickerView(
    context: Context,
    private val blocks: List<OcrBlock>,
    private val onRead: (text: String, rects: List<Rect>) -> Unit
) : View(context) {

    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val boxFill = Paint().apply { color = 0x222196F3 }
    private val boxStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0xCC2196F3.toInt()
    }
    private val hiFill = Paint().apply { color = 0x6600C853 }
    private val hiStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * dp; color = 0xFF00C853.toInt()
    }
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2 * dp; color = 0xFFFF9800.toInt()
        pathEffect = DashPathEffect(floatArrayOf(10 * dp, 6 * dp), 0f)
    }

    private var highlight: List<Rect> = emptyList()
    private val sel = RectF()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val loc = IntArray(2)
    private val tmp = RectF()

    fun setHighlight(rects: List<Rect>) {
        highlight = rects
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        getLocationOnScreen(loc)
        canvas.drawColor(0x22000000)
        val r = 6 * dp
        for (b in blocks) {
            tmp.set(b.rect); tmp.offset(-loc[0].toFloat(), -loc[1].toFloat())
            canvas.drawRoundRect(tmp, r, r, boxFill)
            canvas.drawRoundRect(tmp, r, r, boxStroke)
        }
        for (h in highlight) {
            tmp.set(h); tmp.offset(-loc[0].toFloat(), -loc[1].toFloat())
            canvas.drawRoundRect(tmp, r, r, hiFill)
            canvas.drawRoundRect(tmp, r, r, hiStroke)
        }
        if (!sel.isEmpty) canvas.drawRect(sel, selStroke)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop * 2) dragging = true
                if (dragging) {
                    sel.set(min(downX, e.x), min(downY, e.y), max(downX, e.x), max(downY, e.y))
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) finishSelection() else tap(e.x, e.y)
                dragging = false
                sel.setEmpty()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                sel.setEmpty()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun tap(x: Float, y: Float) {
        getLocationOnScreen(loc)
        val sx = x.toInt() + loc[0]
        val sy = y.toInt() + loc[1]
        val margin = (16 * dp).toInt()
        val hit = blocks
            .filter { b ->
                val r = Rect(b.rect)
                r.inset(-margin, -margin)
                r.contains(sx, sy)
            }
            .minByOrNull { it.rect.width().toLong() * it.rect.height() }
        if (hit == null) {
            Toast.makeText(context, "Aucun texte ici", Toast.LENGTH_SHORT).show()
        } else {
            onRead(hit.text, listOf(hit.rect))
        }
    }

    private fun finishSelection() {
        getLocationOnScreen(loc)
        val area = RectF(sel)
        area.offset(loc[0].toFloat(), loc[1].toFloat())
        val lines = blocks.flatMap { it.lines }.filter {
            area.contains(it.rect.centerX().toFloat(), it.rect.centerY().toFloat())
        }
        if (lines.isEmpty()) {
            Toast.makeText(context, "Aucun texte dans cette zone", Toast.LENGTH_SHORT).show()
        } else {
            onRead(lines.joinToString("\n") { it.text }, lines.map { it.rect })
        }
    }
}
