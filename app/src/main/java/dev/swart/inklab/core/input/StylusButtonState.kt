package dev.swart.inklab.core.input

/**
 * Tracks the S Pen side button independently from pointer contact.
 *
 * Samsung devices may deliver ACTION_BUTTON_PRESS/RELEASE before the stylus touches the screen, so
 * relying only on MotionEvent.buttonState at ACTION_DOWN is not sufficient. This state never changes
 * the user's selected base tool; it only controls a temporary override for the current contact.
 */
class StylusButtonState {
    var pressed: Boolean = false
        private set

    fun press() {
        pressed = true
    }

    fun release() {
        pressed = false
    }

    /** A positive buttonState is authoritative; zero is not, because some hover events omit it. */
    fun observePressedFlag(isPressed: Boolean) {
        if (isPressed) pressed = true
    }

    fun reset() {
        pressed = false
    }
}