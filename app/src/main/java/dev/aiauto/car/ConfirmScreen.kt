package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template

/** Asks once before a step that can't be undone from the car, like merging. */
class ConfirmScreen(
    carContext: CarContext,
    private val question: String,
    private val confirmLabel: String,
    private val onConfirm: () -> Unit,
) : Screen(carContext) {
    override fun onGetTemplate(): Template = MessageTemplate.Builder(question)
        .setHeader(carHeader("Are you sure?"))
        .addAction(
            Action.Builder()
                .setTitle(confirmLabel)
                .setBackgroundColor(CarColor.PRIMARY)
                .setOnClickListener {
                    screenManager.pop()
                    onConfirm()
                }
                .build(),
        )
        .addAction(Action.Builder().setTitle("Cancel").setOnClickListener { screenManager.pop() }.build())
        .build()
}
