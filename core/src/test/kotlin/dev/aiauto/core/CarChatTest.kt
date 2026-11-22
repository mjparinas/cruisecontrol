package dev.aiauto.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarChatTest {
    @Test fun dismissedChatComesBackDuringADrive() {
        assertTrue(CarChat.restoreAfterDismiss(inCar = true, configured = true))
    }

    @Test fun dismissedChatStaysGoneOutOfTheCar() {
        assertFalse(CarChat.restoreAfterDismiss(inCar = false, configured = true))
    }

    @Test fun dismissedChatStaysGoneBeforeSetup() {
        assertFalse(CarChat.restoreAfterDismiss(inCar = true, configured = false))
    }

    @Test fun greetingSaysHowToStartAChat() {
        assertTrue(CarChat.GREETING.contains("say anything to start a chat"))
    }
}
