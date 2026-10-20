package dev.tracelife.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import dev.tracelife.metrics.ActivityMeasure
import dev.tracelife.metrics.ActivityMetricPoint
import kotlin.math.roundToInt

/** Explicit missing values break the line; a genuine entered zero remains selectable. */
class ActivityTrendView(context: Context, val points: List<ActivityMetricPoint>, val measure: ActivityMeasure,
    private val onPoint: (Int) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val ink = Ui.text(context)
    private var selected = points.lastIndex.coerceAtLeast(0)
    private var downX = 0f
    private var downY = 0f

    init { tag = "activityTrendChart"; isFocusable = true; isClickable = true; describe() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        val left = 38f * density; val right = width - 14f * density
        val top = 16f * density; val bottom = height - 30f * density
        val maximum = (points.mapNotNull { it.value(measure) }.maxOrNull() ?: 0).coerceAtLeast(1)
        fun x(index: Int) = left + (right - left) * index / (points.size - 1).coerceAtLeast(1)
        fun y(value: Int) = bottom - (bottom - top) * value / maximum
        paint.color = ink; paint.strokeWidth = density; paint.style = Paint.Style.STROKE; paint.alpha = 55
        canvas.drawLine(left, bottom, right, bottom, paint)
        paint.style = Paint.Style.FILL; paint.textSize = 10f * density; paint.alpha = 180
        canvas.drawText("0", 4f * density, bottom, paint)
        canvas.drawText(maximum.toString(), 4f * density, top + 8f * density, paint)
        points.forEachIndexed { index, point ->
            val value = point.value(measure)
            if (value != null) {
                val previous = points.getOrNull(index - 1)?.value(measure)
                paint.alpha = 220; paint.strokeWidth = 2f * density
                if (previous != null) canvas.drawLine(x(index - 1), y(previous), x(index), y(value), paint)
                paint.style = if (point.inProgress) Paint.Style.STROKE else Paint.Style.FILL
                canvas.drawCircle(x(index), y(value), (if (index == selected) 4.5f else 3f) * density, paint)
            }
        }
        paint.style = Paint.Style.FILL; paint.alpha = 180
        canvas.drawText(points.first().from.toString().takeLast(5), left, height - 8f * density, paint)
        paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(points.last().through.toString().takeLast(5), right, height - 8f * density, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || points.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y; return true }
        if (event.action == MotionEvent.ACTION_UP && kotlin.math.abs(event.x - downX) < 20f * density && kotlin.math.abs(event.y - downY) < 20f * density) {
            val fraction = ((event.x - 38f * density) / (width - 52f * density).coerceAtLeast(1f)).coerceIn(0f, 1f)
            selected = (fraction * (points.size - 1)).roundToInt(); return performClick()
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        if (points.isEmpty()) return false
        describe(); invalidate(); onPoint(selected); return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (points.isNotEmpty() && keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            selected = (selected + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1).coerceIn(points.indices)
            describe(); invalidate(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    private fun describe() {
        contentDescription = points.getOrNull(selected)?.let {
            "${measure.label}，${it.from} 至 ${it.through}，${it.value(measure)?.let { value -> "$value ${measure.unit}" } ?: "未记录"}。左右键选择，点按查看来源；也可逐项查看。"
        } ?: "暂无记录"
    }
}
