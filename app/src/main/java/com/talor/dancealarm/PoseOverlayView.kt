package com.talor.dancealarm

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.max

/** Draws the tracked skeleton on top of the camera preview (FILL_CENTER mapping). */
class PoseOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var pose: Pose? = null
    private var imgW = 1
    private var imgH = 1
    private var mirror = true
    private var dancing = false

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val bones = arrayOf(
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
        PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
        PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
        PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
        PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
        PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
        PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
        PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE,
    )

    fun setPose(pose: Pose?, imageWidth: Int, imageHeight: Int, mirror: Boolean, dancing: Boolean) {
        this.pose = pose
        this.imgW = max(1, imageWidth)
        this.imgH = max(1, imageHeight)
        this.mirror = mirror
        this.dancing = dancing
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = pose ?: return
        val scale = max(width.toFloat() / imgW, height.toFloat() / imgH)
        val offX = (width - imgW * scale) / 2f
        val offY = (height - imgH * scale) / 2f

        fun mapX(x: Float): Float {
            val v = x * scale + offX
            return if (mirror) width - v else v
        }
        fun mapY(y: Float) = y * scale + offY

        line.color = if (dancing) Color.parseColor("#FF3D7F") else Color.parseColor("#B388FF")

        for ((a, b) in bones) {
            val la = p.getPoseLandmark(a) ?: continue
            val lb = p.getPoseLandmark(b) ?: continue
            if (la.inFrameLikelihood < 0.5f || lb.inFrameLikelihood < 0.5f) continue
            canvas.drawLine(
                mapX(la.position.x), mapY(la.position.y),
                mapX(lb.position.x), mapY(lb.position.y), line
            )
        }
        for (lm in p.allPoseLandmarks) {
            if (lm.inFrameLikelihood < 0.5f) continue
            canvas.drawCircle(mapX(lm.position.x), mapY(lm.position.y), 8f, dot)
        }
    }
}
