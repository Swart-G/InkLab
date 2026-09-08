package dev.swart.inklab.core.input

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StylusButtonStateTest {
    @Test
    fun pressAndReleaseAreIndependentFromPointerContact() {
        val state = StylusButtonState()
        state.press()
        assertTrue(state.pressed)
        state.release()
        assertFalse(state.pressed)
    }

    @Test
    fun positiveButtonFlagRecoversMissedPressEvent() {
        val state = StylusButtonState()
        state.observePressedFlag(true)
        assertTrue(state.pressed)
    }

    @Test
    fun zeroButtonFlagDoesNotClearLatchedHoverPress() {
        val state = StylusButtonState()
        state.press()
        state.observePressedFlag(false)
        assertTrue(state.pressed)
    }

    @Test
    fun cancelOrFocusLossResetClearsTemporaryOverride() {
        val state = StylusButtonState()
        state.press()
        state.reset()
        assertFalse(state.pressed)
    }
}