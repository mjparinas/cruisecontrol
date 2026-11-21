package dev.aiauto.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBoxValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalMaterial3Api::class)
class CardSwipeTest {
    @Test fun swipingLeftArchives() {
        assertEquals(CardSwipe.Archive, cardSwipe(SwipeToDismissBoxValue.EndToStart))
    }

    @Test fun swipingRightClears() {
        assertEquals(CardSwipe.Clear, cardSwipe(SwipeToDismissBoxValue.StartToEnd))
    }

    @Test fun aSettledCardDoesNothing() {
        assertNull(cardSwipe(SwipeToDismissBoxValue.Settled))
    }

    @Test fun anActiveCardCanBeArchivedOrCleared() {
        assertEquals(setOf(CardSwipe.Archive, CardSwipe.Clear), cardSwipes(archived = false))
    }

    @Test fun anArchivedCardCanOnlyBeCleared() {
        assertEquals(setOf(CardSwipe.Clear), cardSwipes(archived = true))
    }
}
