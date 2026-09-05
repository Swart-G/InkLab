package dev.swart.inklab.core.input

import org.junit.Assert.*
import org.junit.Test

class TwoFingerTapTest {
    @Test fun intentionalTap() { val tap=TwoFingerTap(10f);tap.down(4,0f,0f,1000);tap.down(8,30f,0f,1070);assertTrue(tap.finish(1180)) }
    @Test fun restingPalmBeforeFingerIsRejected() { val tap=TwoFingerTap(10f);tap.down(0,0f,0f,1000);tap.down(1,30f,0f,1200);assertFalse(tap.finish(1230)) }
    @Test fun cumulativeSlowMovementCannotBecomeUndo() { val tap=TwoFingerTap(10f);tap.down(0,0f,0f,1000);tap.down(1,30f,0f,1000);for(x in 1..20) tap.move(0,x.toFloat(),0f);assertFalse(tap.finish(1200)) }
    @Test fun returningToStartDoesNotRevalidate() {val tap=TwoFingerTap(10f);tap.down(0,0f,0f,1000);tap.down(1,30f,0f,1000);tap.move(0,11f,0f);tap.move(0,0f,0f);assertFalse(tap.finish(1200))}
    @Test fun thirdFingerInvalidates() {val tap=TwoFingerTap(10f);repeat(3) {tap.down(it,it*20f,0f,1000)};assertFalse(tap.finish(1100))}
    @Test fun systemCancellationInvalidates() {val tap=TwoFingerTap(10f);tap.down(0,0f,0f,1000);tap.down(1,30f,0f,1000);tap.cancel();assertFalse(tap.finish(1100))}
    @Test fun longHoldIsNotTap() {val tap=TwoFingerTap(10f);tap.down(0,0f,0f,1000);tap.down(1,30f,0f,1000);assertFalse(tap.finish(1251))}
    @Test fun singleFingerIsNotUndo() {val tap=TwoFingerTap(10f);tap.down(0,0f,0f,1000);assertFalse(tap.finish(1100))}
}
