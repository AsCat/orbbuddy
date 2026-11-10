package dev.orbbuddy.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

enum class OrbCharacter(val label: String, val description: String) {
    YUANYUAN("圆圆", "经典蓝色圆伴"),
    YAYA("芽芽", "绿色小芽伙伴"),
    XINGZI("星仔", "紫色星光伙伴");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: YUANYUAN
    }
}

/** Small vector characters share one renderer, so previews and the overlay always match. */
class OrbFaceView(context: Context, private val animated: Boolean = true) : View(context) {
    var character = OrbCharacter.YUANYUAN
        set(value) { field = value; invalidate() }
    var paused = false
        set(value) { field = value; invalidate() }
    var connected = false
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val power = context.getSystemService(PowerManager::class.java)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val breath = if (!animated || paused) 1f else 1f + 0.025f * sin(now / 610.0).toFloat()
        canvas.save()
        canvas.scale(width / 72f, height / 72f)
        canvas.scale(breath, breath, 36f, 36f)
        paint.style = Paint.Style.FILL
        val body = when (character) {
            OrbCharacter.YUANYUAN -> Color.rgb(69, 194, 241)
            OrbCharacter.YAYA -> Color.rgb(83, 202, 146)
            OrbCharacter.XINGZI -> Color.rgb(163, 137, 238)
        }
        paint.color = if (paused) Color.rgb(165, 179, 190) else body
        canvas.drawCircle(36f, 37f, 29f, paint)
        paint.color = if (paused) Color.rgb(197, 207, 215) else when (character) {
            OrbCharacter.YUANYUAN -> Color.rgb(135, 224, 250)
            OrbCharacter.YAYA -> Color.rgb(169, 235, 178)
            OrbCharacter.XINGZI -> Color.rgb(211, 193, 255)
        }
        canvas.drawCircle(27f, 23f, 11f, paint)
        if (!paused) when (character) {
            OrbCharacter.YUANYUAN -> Unit
            OrbCharacter.YAYA -> {
                paint.color = Color.rgb(39, 147, 92)
                paint.strokeWidth = 2f
                canvas.drawLine(36f, 12f, 36f, 3f, paint)
                canvas.drawOval(23f, 0f, 36f, 11f, paint)
                canvas.drawOval(37f, 0f, 50f, 11f, paint)
            }
            OrbCharacter.XINGZI -> {
                paint.color = Color.rgb(255, 219, 103)
                val star = Path()
                for (i in 0 until 10) {
                    val angle = -Math.PI / 2 + i * Math.PI / 5
                    val radius = if (i % 2 == 0) 9.0 else 4.2
                    val x = 54 + cos(angle) * radius
                    val y = 12 + sin(angle) * radius
                    if (i == 0) star.moveTo(x.toFloat(), y.toFloat()) else star.lineTo(x.toFloat(), y.toFloat())
                }
                star.close()
                canvas.drawPath(star, paint)
            }
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = when {
            paused -> Color.rgb(115, 125, 137)
            connected -> Color.rgb(49, 173, 107)
            else -> Color.rgb(242, 170, 65)
        }
        canvas.drawCircle(36f, 37f, 31f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(35, 65, 99)
        if (animated && !paused && now % 4400L < 150L) {
            canvas.drawRoundRect(21f, 32f, 29f, 34f, 2f, 2f, paint)
            canvas.drawRoundRect(43f, 32f, 51f, 34f, 2f, 2f, paint)
        } else {
            canvas.drawCircle(25f, 33f, 3.5f, paint)
            canvas.drawCircle(47f, 33f, 3.5f, paint)
        }
        paint.color = Color.rgb(255, 145, 158)
        canvas.drawCircle(18f, 42f, 3.8f, paint)
        canvas.drawCircle(54f, 42f, 3.8f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.color = Color.rgb(35, 65, 99)
        canvas.drawArc(29f, 36f, 43f, 49f, 20f, 140f, false, paint)
        canvas.restore()
        if (animated && !paused && power.isInteractive && isAttachedToWindow) postInvalidateDelayed(50L)
    }
}
