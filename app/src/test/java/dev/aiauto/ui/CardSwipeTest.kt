package dev.aiauto.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBoxValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalMaterial3Api::class)
class CardSwipeTest {
    @Test fun swipingRightArchives() {
        assertEquals(CardSwipe.Archive, cardSwipe(SwipeToDismissBoxValue.StartToEnd))
    }

    @Test fun swipingLeftClears() {
        assertEquals(CardSwipe.Clear, cardSwipe(SwipeToDismissBoxValue.EndToStart))
    }

    @Test fun aSettledCardDoesNothing() {
        assertNull(cardSwipe(SwipeToDismissBoxValue.Settled))
    }
}
