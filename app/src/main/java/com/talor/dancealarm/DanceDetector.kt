package com.talor.dancealarm

import android.graphics.PointF
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Decides whether the person in front of the camera is actually dancing.
 *
 * Every limb's speed is measured in "torso lengths per second" relative to the hips, so it
 * works the same whether you stand 1 m or 4 m from the phone. You only count as dancing
 * when BOTH your arms and your lower body (legs or bouncing hips) are moving, and it has to
 * be sustained — the meter only fills while you keep it up, and slowly drains if you stop.
 */
class DanceDetector(private val requiredSeconds: Int, difficulty: Int) {

    data class State(
        val bodyVisible: Boolean,
        val arms: Float,
        val legs: Float,
        val dancing: Boolean,
        val progress: Float,
    )

    private val mult = when (difficulty) {
        0 -> 0.6f
        2 -> 1.5f
        else -> 1f
    }
    val armThreshold = 0.9f * mult
    val legThreshold = 0.45f * mult
    private val motionThreshold = 3.0f * mult // m/s² for the no-camera fallback

    private var prevPoints: Map<Int, PointF>? = null
    private var prevHip: PointF? = null
    private var prevT = -1.0
    private var armE = 0f
    private var legE = 0f
    private var danceTime = 0f
    var lastState = State(false, 0f, 0f, false, 0f)
        private set

    private val tracked = intArrayOf(
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
        PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
    )
    private val armPoints = listOf(
        PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
        PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
    )
    private val legPoints = listOf(
        PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
        PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE,
    )

    private fun point(pose: Pose, type: Int): PointF? {
        val lm = pose.getPoseLandmark(type) ?: return null
        return if (lm.inFrameLikelihood >= 0.5f) lm.position else null
    }

    private fun mid(a: PointF, b: PointF) = PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)

    /** [t] is in seconds. */
    fun update(pose: Pose, t: Double): State {
        val ls = point(pose, PoseLandmark.LEFT_SHOULDER)
        val rs = point(pose, PoseLandmark.RIGHT_SHOULDER)
        val lh = point(pose, PoseLandmark.LEFT_HIP)
        val rh = point(pose, PoseLandmark.RIGHT_HIP)

        val dtRaw = if (prevT < 0) 0.0 else t - prevT
        prevT = t

        if (ls == null || rs == null || lh == null || rh == null) {
            prevPoints = null
            prevHip = null
            decay(dtRaw.toFloat())
            return publish(false, false)
        }

        val shoulder = mid(ls, rs)
        val hip = mid(lh, rh)
        val torso = hypot(shoulder.x - hip.x, shoulder.y - hip.y)
        if (torso < 10f) {
            prevPoints = null
            return publish(false, false)
        }

        val points = HashMap<Int, PointF>()
        for (type in tracked) {
            point(pose, type)?.let { points[type] = PointF(it.x - hip.x, it.y - hip.y) }
        }

        val pp = prevPoints
        val ph = prevHip
        prevPoints = points
        prevHip = hip

        val dt = dtRaw.toFloat()
        if (pp == null || ph == null || dt <= 0f || dt > 0.6f) {
            return publish(true, false)
        }

        fun speed(type: Int): Float? {
            val a = points[type] ?: return null
            val b = pp[type] ?: return null
            return min(6f, hypot(a.x - b.x, a.y - b.y) / torso / dt)
        }

        val armSpeeds = armPoints.mapNotNull { speed(it) }
        val legSpeeds = legPoints.mapNotNull { speed(it) }
        val arm = if (armSpeeds.isEmpty()) 0f else armSpeeds.average().toFloat()
        val legRel = if (legSpeeds.isEmpty()) 0f else legSpeeds.average().toFloat()
        val bounce = min(6f, hypot(hip.x - ph.x, hip.y - ph.y) / torso / dt)
        val leg = max(legRel, bounce)

        val alpha = 1f - exp(-dt / 0.5f)
        armE += alpha * (arm - armE)
        legE += alpha * (leg - legE)

        val dancing = armE > armThreshold && legE > legThreshold
        accumulate(dancing, dt)
        return publish(true, dancing)
    }

    /** Fallback when the camera can't be used: phone held while dancing. [magnitude] in m/s². */
    fun updateMotion(magnitude: Float, t: Double): State {
        val dt = if (prevT < 0) 0f else (t - prevT).toFloat()
        prevT = t
        if (dt <= 0f || dt > 0.6f) return publish(true, false)
        val alpha = 1f - exp(-dt / 0.7f)
        armE += alpha * (min(magnitude, 20f) - armE)
        legE = armE
        val dancing = armE > motionThreshold
        accumulate(dancing, dt)
        return publish(true, dancing)
    }

    val motionDisplayThreshold get() = motionThreshold

    private fun accumulate(dancing: Boolean, dt: Float) {
        danceTime = if (dancing) danceTime + dt else max(0f, danceTime - dt * 0.25f)
    }

    private fun decay(dt: Float) {
        if (dt in 0f..1f) {
            armE *= 0.9f
            legE *= 0.9f
            danceTime = max(0f, danceTime - dt * 0.25f)
        }
    }

    private fun publish(visible: Boolean, dancing: Boolean): State {
        lastState = State(visible, armE, legE, dancing, min(1f, danceTime / requiredSeconds))
        return lastState
    }
}
