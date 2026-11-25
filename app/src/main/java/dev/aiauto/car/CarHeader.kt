package dev.aiauto.car

import androidx.car.app.model.Action
import androidx.car.app.model.Header

/**
 * The header every car screen uses: a title, the back arrow (or the app icon on a top screen) and up to two actions
 * at the end. Car App 1.7 moved these from each template's own title, header action and action strip into [Header].
 */
internal fun carHeader(title: CharSequence, start: Action = Action.BACK, end: List<Action> = emptyList()): Header =
    Header.Builder()
        .setTitle(title)
        .setStartHeaderAction(start)
        .apply { end.forEach { addEndHeaderAction(it) } }
        .build()
