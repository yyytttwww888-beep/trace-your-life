package dev.tracelife.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import dev.tracelife.dashboard.DashboardPoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** Plots the dashboard's exact-date counts without interpolating unknown or interval records. */
class RecordTrendView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    var points: List<DashboardPoint> = emptyList()
        private set
    private var onPoint: (Int) -> Unit = {}
    private val linePath = Path()
    private var maximum = 1
    private var maximumLabel = "1"
    private var firstLabel = ""
    private var lastLabel = ""

    constructor(context: Context, points: List<DashboardPoint>, onPoint: (Int) -> Unit) : this(context) {
        this.points = points.toList()
        this.onPoint = onPoint
        selected = points.lastIndex.coerceAtLeast(0)
        maximum = points.maxOfOrNull { it.exactDateRecords }?.coerceAtLeast(1) ?: 1
        maximumLabel = maximum.toString()
        firstLabel = points.firstOrNull()?.from?.toString()?.substring(5).orEmpty()
        lastLabel = points.lastOrNull()?.through?.toString()?.substring(5).orEmpty()
        updateDescription()
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selected = points.lastIndex.coerceAtLeast(0)
    private var downX = 0f
    private var downY = 0f
    private val inset get() = Ui.dp(context, 30).toFloat()
    private val bottom get() = height - Ui.dp(context, 30).toFloat()
    private fun x(index: Int) = inset + (width - inset - Ui.dp(context, 12)) * index / (points.size - 1).coerceAtLeast(1)
    private fun y(index: Int): Float {
        return bottom - (bottom - Ui.dp(context, 20)) * points[index].exactDateRecords / maximum
    }
    init {
        isClickable = true
        isFocusable = true
        minimumHeight = Ui.dp(context, 190)
        tag = "recordTrendChart"
        updateDescription()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return
        paint.strokeWidth = Ui.dp(context, 1).toFloat()
        paint.color = Ui.border(context)
        canvas.drawLine(inset, bottom, width.toFloat() - Ui.dp(context, 12), bottom, paint)
        paint.color = Ui.textSecondary(context)
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        paint.style = Paint.Style.FILL
        canvas.drawText("0", 0f, bottom, paint)
        canvas.drawText(maximumLabel, 0f, Ui.dp(context, 24).toFloat(), paint)
        canvas.drawText(firstLabel, inset, height.toFloat() - Ui.dp(context, 5), paint)
        canvas.drawText(lastLabel, width - Ui.dp(context, 12) - paint.measureText(lastLabel), height.toFloat() - Ui.dp(context, 5), paint)
        paint.color = Ui.text(context)
        paint.strokeWidth = Ui.dp(context, 2).toFloat()
        paint.style = Paint.Style.STROKE
        linePath.reset()
        points.indices.forEach { index -> if (index == 0) linePath.moveTo(x(index), y(index)) else linePath.lineTo(x(index), y(index)) }
        canvas.drawPath(linePath, paint)
        points.indices.forEach { index ->
            paint.style = if (points[index].inProgress) Paint.Style.STROKE else Paint.Style.FILL
            canvas.drawCircle(x(index), y(index), Ui.dp(context, if (index == selected) 5 else 3).toFloat(), paint)
        }
        paint.style = Paint.Style.FILL
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || points.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; return true }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) < Ui.dp(context, 20) && abs(event.y - downY) < Ui.dp(context, 20)) {
                    val fraction = (event.x - inset) / (width - inset - Ui.dp(context, 12)).coerceAtLeast(1f)
                    selected = (fraction * (points.size - 1)).roundToInt().coerceIn(points.indices)
                    return performClick()
                }
                return true
            }
        }
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        if (points.isEmpty()) return false
        updateDescription(); invalidate(); onPoint(selected)
        return true
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (points.isNotEmpty() && keyCode in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)) {
            selected = (selected + if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1).coerceIn(points.indices)
            updateDescription(); invalidate(); return true
        }
        return super.onKeyDown(keyCode, event)
    }
    private fun updateDescription() {
        contentDescription = points.getOrNull(selected)?.let {
            "记录趋势，${it.from} 至 ${it.through}，${it.exactDateRecords} 条。左右键选择时段，点按查看来源；也可在按日期查看记录中逐项浏览。"
        } ?: "记录趋势，暂无记录"
    }
}
