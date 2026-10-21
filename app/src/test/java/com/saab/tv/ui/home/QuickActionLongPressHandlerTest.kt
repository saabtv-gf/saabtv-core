package com.saab.tv.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.saab.tv.ui.components.QuickActionLongPressHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class QuickActionLongPressHandlerTest {
    private val handler = QuickActionLongPressHandler()

    private fun send(
        key: Key,
        type: KeyEventType,
        repeatCount: Int,
        eventTimeMs: Long,
        downTimeMs: Long = 100L,
        onOpen: () -> Unit
    ) = handler.handleKey(key, type, repeatCount, eventTimeMs, downTimeMs, onOpen)

    @Test fun ordinaryCenterPressRemainsARegularCardClick() {
        assertEquals(false, send(Key.DirectionCenter, KeyEventType.KeyDown, 0, 100L) { error("opened") })
        assertEquals(false, send(Key.DirectionCenter, KeyEventType.KeyUp, 0, 350L) { error("opened") })
    }

    @Test fun heldCenterIsConsumedAndOpensOnlyOnceOnRelease() {
        var opened = 0
        assertEquals(false, send(Key.Enter, KeyEventType.KeyDown, 0, 100L) { opened++ })
        assertEquals(true, send(Key.Enter, KeyEventType.KeyDown, 1, 650L) { opened++ })
        assertEquals(true, send(Key.Enter, KeyEventType.KeyDown, 2, 700L) { opened++ })
        assertEquals(true, send(Key.Enter, KeyEventType.KeyUp, 0, 720L) { opened++ })
        assertEquals(false, send(Key.Enter, KeyEventType.KeyUp, 0, 730L) { opened++ })
        assertEquals(1, opened)
    }

    @Test fun longPressWithoutRemoteRepeatEventsOpensOnReleaseOnly() {
        var opened = 0

        assertEquals(false, send(Key.DirectionCenter, KeyEventType.KeyDown, 0, 100L) { opened++ })
        assertEquals(true, send(Key.DirectionCenter, KeyEventType.KeyUp, 0, 700L) { opened++ })

        assertEquals(1, opened)
    }

    @Test fun otherKeysDoNotOpenOrClearTheHeldCenterState() {
        send(Key.DirectionCenter, KeyEventType.KeyDown, 1, 650L) { error("opened") }

        assertEquals(false, send(Key.DirectionDown, KeyEventType.KeyUp, 0, 700L) { error("opened") })
        assertEquals(true, send(Key.DirectionCenter, KeyEventType.KeyUp, 0, 700L) { })
    }

    @Test fun losingFocusCancelsThePendingLongPress() {
        send(Key.DirectionCenter, KeyEventType.KeyDown, 1, 650L) { error("opened") }
        handler.reset()

        assertEquals(false, send(Key.DirectionCenter, KeyEventType.KeyUp, 0, 700L) { error("opened") })
    }
}
