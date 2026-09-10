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
    fun zeroButtonFlagDoesNotClearFreshLatchedHoverPress() {
        val state = StylusButtonState()
        state.press(1_000L)
        state.observePressedFlag(false, 1_100L)
        assertTrue(state.pressed)
    }

    @Test
    fun staleLatchClearsWithoutHoverExitWhenReleaseEventWasLost() {
        val state = StylusButtonState()
        state.press(1_000L)
        state.observePressedFlag(false, 1_000L + StylusButtonState.OMITTED_FLAG_GRACE_MS)
        assertFalse(state.pressed)
    }

    @Test
    fun repeatedPositiveFlagsKeepHeldButtonLatched() {
        val state = StylusButtonState()
        state.press(1_000L)
        state.observePressedFlag(true, 1_150L)
        state.observePressedFlag(false, 1_250L)
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
