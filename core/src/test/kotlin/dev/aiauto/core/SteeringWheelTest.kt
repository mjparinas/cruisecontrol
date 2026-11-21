package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SteeringWheelTest {
    @Test fun playOrPauseStartsTalk() {
        assertEquals(WheelAction.Talk, SteeringWheel.actionFor(WheelButton.PlayPause))
    }

    @Test fun nextAndPreviousRepeatTheLastUpdate() {
        assertEquals(WheelAction.Repeat, SteeringWheel.actionFor(WheelButton.Next))
        assertEquals(WheelAction.Repeat, SteeringWheel.actionFor(WheelButton.Previous))
    }

    @Test fun repeatSaysTheLastThingReadAloud() {
        assertEquals("PR 12 is open.", SteeringWheel.repeatText("PR 12 is open."))
    }

    @Test fun repeatWithNothingSaidYetSaysSo() {
        assertEquals(SteeringWheel.NOTHING_TO_REPEAT, SteeringWheel.repeatText(null))
        assertEquals(SteeringWheel.NOTHING_TO_REPEAT, SteeringWheel.repeatText("  "))
    }
}
