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
    YAYA("芽芽", "绿叶和种子形伙伴"),
    XINGZI("星星", "暖黄色星形伙伴");

    companion object {
        fun from(value: String?) = entries.firstOrNull { it.name == value } ?: YUANYUAN
    }
}

enum class OrbReaction { LOOK, TAP, SWIPE }

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
    private var reaction: OrbReaction? = null
    private var reactionStarted = 0L
    private var reactionDirection = 1f

    fun react(next: OrbReaction, direction: Float = 1f) {
        if (paused || !animated) return
        reaction = next
        reactionStarted = SystemClock.uptimeMillis()
        reactionDirection = if (direction < 0f) -1f else 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val currentReaction = reaction
        val reactionDuration = if (currentReaction == OrbReaction.LOOK) 1500L else 900L
        val elapsed = now - reactionStarted
        val progress = if (currentReaction != null && elapsed in 0..reactionDuration) {
            elapsed.toFloat() / reactionDuration
        } else {
            reaction = null
            1f
        }
        val breath = if (!animated || paused) 1f else 1f + 0.025f * sin(now / 610.0).toFloat()
        canvas.save()
        canvas.scale(width / 72f, height / 72f)
        canvas.scale(breath, breath, 36f, 36f)
        if (currentReaction == OrbReaction.TAP && progress < 1f) {
            canvas.translate(0f, -6f * sin(Math.PI * progress).toFloat())
        } else if (currentReaction == OrbReaction.SWIPE && progress < 1f) {
            canvas.rotate(reactionDirection * 12f * sin(Math.PI * progress).toFloat(), 36f, 36f)
        }
        when (character) {
            OrbCharacter.YUANYUAN -> {
                paint.style = Paint.Style.FILL
                paint.color = if (paused) Color.rgb(165, 179, 190) else Color.rgb(69, 194, 241)
                canvas.drawCircle(36f, 37f, 29f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3f
                paint.color = if (paused) Color.rgb(115, 125, 137) else Color.rgb(28, 126, 172)
                canvas.drawCircle(36f, 37f, 29f, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (paused) Color.rgb(197, 207, 215) else Color.rgb(135, 224, 250)
                canvas.drawCircle(27f, 23f, 10f, paint)
            }
            OrbCharacter.YAYA -> {
                // Large leaf ears and a pointed seed body keep the silhouette recognizable.
                val leftLeaf = Path().apply {
                    moveTo(35f, 19f)
                    cubicTo(19f, 19f, 10f, 10f, 13f, 3f)
                    cubicTo(26f, 3f, 33f, 10f, 35f, 19f)
                    close()
                }
                val rightLeaf = Path().apply {
                    moveTo(37f, 19f)
                    cubicTo(42f, 7f, 53f, 2f, 62f, 5f)
                    cubicTo(58f, 15f, 48f, 21f, 37f, 19f)
                    close()
                }
                paint.style = Paint.Style.FILL
                paint.color = if (paused) Color.rgb(160, 175, 180) else Color.rgb(60, 177, 111)
                canvas.drawPath(leftLeaf, paint)
                paint.color = if (paused) Color.rgb(191, 204, 209) else Color.rgb(137, 225, 140)
                canvas.drawPath(rightLeaf, paint)
                val seed = Path().apply {
                    moveTo(36f, 15f)
                    cubicTo(42f, 24f, 54f, 26f, 59f, 39f)
                    cubicTo(64f, 54f, 51f, 66f, 35f, 66f)
                    cubicTo(18f, 66f, 10f, 53f, 14f, 40f)
                    cubicTo(17f, 28f, 29f, 23f, 36f, 15f)
                    close()
                }
                paint.color = if (paused) Color.rgb(165, 179, 190) else Color.rgb(83, 202, 146)
                canvas.drawPath(seed, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f
                paint.color = if (paused) Color.rgb(115, 125, 137) else Color.rgb(29, 132, 83)
                canvas.drawPath(seed, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (paused) Color.rgb(197, 207, 215) else Color.rgb(169, 235, 178)
                canvas.drawOval(22f, 27f, 35f, 37f, paint)
            }
            OrbCharacter.XINGZI -> {
                // The whole body is a five-pointed star, rather than a round face with a badge.
                val star = Path()
                for (i in 0 until 10) {
                    val angle = -Math.PI / 2 + i * Math.PI / 5
                    val radius = if (i % 2 == 0) 33.0 else 23.0
                    val x = 36 + cos(angle) * radius
                    val y = 37 + sin(angle) * radius
                    if (i == 0) star.moveTo(x.toFloat(), y.toFloat()) else star.lineTo(x.toFloat(), y.toFloat())
                }
                star.close()
                paint.style = Paint.Style.FILL
                paint.color = if (paused) Color.rgb(165, 179, 190) else Color.rgb(255, 211, 99)
                canvas.drawPath(star, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2.5f
                paint.color = if (paused) Color.rgb(115, 125, 137) else Color.rgb(216, 145, 39)
                canvas.drawPath(star, paint)
                paint.style = Paint.Style.FILL
                paint.color = if (paused) Color.rgb(197, 207, 215) else Color.rgb(255, 239, 177)
                canvas.drawOval(24f, 25f, 34f, 32f, paint)
            }
        }
        paint.style = Paint.Style.FILL
        val faceInk = if (character == OrbCharacter.XINGZI) Color.rgb(100, 65, 54) else Color.rgb(35, 65, 99)
        paint.color = faceInk
        val looking = currentReaction == OrbReaction.LOOK && progress < 1f
        if (animated && !paused && !looking && now % 4400L < 150L) {
            canvas.drawRoundRect(21f, 32f, 29f, 34f, 2f, 2f, paint)
            canvas.drawRoundRect(43f, 32f, 51f, 34f, 2f, 2f, paint)
        } else if (looking) {
            paint.color = Color.WHITE
            canvas.drawCircle(25f, 33f, 6f, paint)
            canvas.drawCircle(47f, 33f, 6f, paint)
            paint.color = faceInk
            val glance = 2.5f * sin(2 * Math.PI * progress).toFloat()
            canvas.drawCircle(25f + glance, 33f, 3f, paint)
            canvas.drawCircle(47f + glance, 33f, 3f, paint)
        } else {
            canvas.drawCircle(25f, 33f, 3.5f, paint)
            canvas.drawCircle(47f, 33f, 3.5f, paint)
        }
        paint.color = Color.rgb(255, 145, 158)
        canvas.drawCircle(18f, 42f, 3.8f, paint)
        canvas.drawCircle(54f, 42f, 3.8f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.color = faceInk
        canvas.drawArc(29f, 36f, 43f, 49f, 20f, 140f, false, paint)
        if (looking) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.rgb(255, 255, 255)
            val sweep = 45f + 160f * progress
            canvas.drawArc(6f, 7f, 66f, 67f, sweep, 24f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(59f, 58f, 7f, paint)
        paint.color = when {
            paused -> Color.rgb(115, 125, 137)
            connected -> Color.rgb(49, 173, 107)
            else -> Color.rgb(242, 170, 65)
        }
        canvas.drawCircle(59f, 58f, 5f, paint)
        canvas.restore()
        if (animated && !paused && power.isInteractive && isAttachedToWindow) postInvalidateDelayed(50L)
    }
}
