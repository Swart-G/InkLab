package dev.swart.inklab.core.input

/**
 * Tracks the S Pen side button independently from pointer contact.
 *
 * Samsung devices may deliver ACTION_BUTTON_PRESS/RELEASE before the stylus touches the screen, so
 * relying only on MotionEvent.buttonState at ACTION_DOWN is not sufficient. This state never changes
 * the user's selected base tool; it only controls a temporary override for the current contact.
 */
class StylusButtonState {
    companion object {
        /** Keep a pre-contact button press across a few hover frames that omit buttonState. */
        const val OMITTED_FLAG_GRACE_MS = 180L
    }

    var pressed: Boolean = false
        private set
    private var lastPositiveEventTime = Long.MIN_VALUE

    fun press(eventTime: Long = 0L) {
        pressed = true
        lastPositiveEventTime = eventTime
    }

    fun release() {
        pressed = false
    }

    /**
     * A positive buttonState is authoritative. Some S Pen firmware omits the flag for the first
     * hover frames after BUTTON_PRESS, so a short grace period preserves a pre-contact override.
     * A later zero clears the latch even when BUTTON_RELEASE was lost; otherwise the temporary
     * eraser remains active until the pen leaves hover range.
     */
    fun observePressedFlag(isPressed: Boolean, eventTime: Long = 0L) {
        if (isPressed) {
            press(eventTime)
        } else if (pressed && elapsedSincePositive(eventTime) >= OMITTED_FLAG_GRACE_MS) {
            release()
        }
    }

    fun reset() {
        pressed = false
        lastPositiveEventTime = Long.MIN_VALUE
    }

    private fun elapsedSincePositive(eventTime: Long): Long = when {
        lastPositiveEventTime == Long.MIN_VALUE -> Long.MAX_VALUE
        eventTime < lastPositiveEventTime -> Long.MAX_VALUE
        else -> eventTime - lastPositiveEventTime
    }
}
