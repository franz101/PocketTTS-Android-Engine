package org.pockettts.android.engine

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat

/**
 * Reading text with a rounded highlight drawn behind the current sentence (across line
 * wraps) and tap-to-offset reporting for jumping playback.
 */
internal class ReaderTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.player_highlight) }
    private val baseAlpha = highlightPaint.alpha
    private val rect = RectF()
    private val radius = resources.displayMetrics.density * 8f
    private val padX = resources.displayMetrics.density * 5f
    private val padY = resources.displayMetrics.density * 2f
    private var highlightStart = -1
    private var highlightEnd = -1
    private var highlightAlpha = 1f
    private var animator: ValueAnimator? = null

    /** Called with the character offset of a single tap. */
    var onOffsetTapped: ((Int) -> Unit)? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val callback = onOffsetTapped ?: return false
            callback(getOffsetForPosition(e.x, e.y))
            performClick()
            return true
        }
    })

    fun setHighlight(start: Int, end: Int, animate: Boolean) {
        if (start == highlightStart && end == highlightEnd) return
        highlightStart = start
        highlightEnd = end
        animator?.cancel()
        if (animate && start >= 0) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 260
                interpolator = UiKit.emphasized
                addUpdateListener { highlightAlpha = it.animatedValue as Float; invalidate() }
                start()
            }
        } else {
            highlightAlpha = 1f
            invalidate()
        }
    }

    /** Top of the line containing [offset], relative to this view. */
    fun lineTopFor(offset: Int): Int {
        val layout = layout ?: return 0
        return layout.getLineTop(layout.getLineForOffset(offset.coerceIn(0, text.length))) + totalPaddingTop
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (layout != null && highlightStart >= 0 && highlightEnd > highlightStart && highlightEnd <= text.length) {
            highlightPaint.alpha = (baseAlpha * highlightAlpha).toInt().coerceIn(0, 255)
            val first = layout.getLineForOffset(highlightStart)
            val last = layout.getLineForOffset(highlightEnd - 1)
            val descent = paint.fontMetrics.descent
            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            for (line in first..last) {
                val start = maxOf(highlightStart, layout.getLineStart(line))
                val end = minOf(highlightEnd, layout.getLineEnd(line))
                val left = if (line == first) layout.getPrimaryHorizontal(start) else layout.getLineLeft(line)
                val right = if (line == last) layout.getPrimaryHorizontal(end).let {
                    if (it <= left) layout.getLineRight(line) else it
                } else layout.getLineRight(line)
                // Exclude the extra line spacing so stacked rows keep a small gap.
                val top = layout.getLineTop(line).toFloat()
                val bottom = layout.getLineBaseline(line) + descent
                rect.set(left - padX, top + (bottom - top) * 0.06f - padY, right + padX, bottom + padY)
                if (end > start) canvas.drawRoundRect(rect, radius, radius, highlightPaint)
            }
            canvas.restore()
        }
        super.onDraw(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (onOffsetTapped != null) gestures.onTouchEvent(event) || super.onTouchEvent(event) else super.onTouchEvent(event)
}
