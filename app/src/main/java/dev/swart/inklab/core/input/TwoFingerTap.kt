package dev.swart.inklab.core.input

/** A candidate can only become invalid during a gesture, never valid again. */
class TwoFingerTap(private val slop: Float) {
    private val starts = mutableMapOf<Int, Pair<Float, Float>>()
    private var startTime = 0L
    private var valid = true
    fun down(id: Int, x: Float, y: Float, time: Long) {
        if (starts.isEmpty()) startTime = time
        if (starts.isNotEmpty() && time - startTime > 100L) valid = false
        starts[id] = x to y
        if (starts.size > 2) valid = false
    }
    fun move(id: Int, x: Float, y: Float) {
        starts[id]?.let { (sx, sy) -> if ((x-sx)*(x-sx)+(y-sy)*(y-sy) >= slop*slop) valid = false }
    }
    fun cancel() { valid = false }
    fun finish(time: Long) = valid && starts.size == 2 && time-startTime <= 250L
}
