package dev.aiauto.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.lifecycleScope
import dev.aiauto.graph
import kotlinx.coroutines.launch

/** Saved task templates in the car: tap one, pick the repo if there's more than one, and the task starts. */
class TemplatesScreen(carContext: CarContext) : Screen(carContext) {
    private val g = carContext.graph

    init {
        lifecycleScope.launch { g.templates.templates.collect { invalidate() } }
    }

    override fun onGetTemplate(): Template {
        val items = ItemList.Builder().setNoItemsMessage("Save templates in New task on your phone.")
        g.templates.templates.value.take(dev.aiauto.core.RepoHistory.CAR_ROWS).forEach { template ->
            items.addItem(
                Row.Builder()
                    .setTitle(template.name)
                    .addText(template.prompt)
                    .setOnClickListener {
                        screenManager.pop()
                        PickRepoScreen.startTemplate(carContext, screenManager, template)
                    }
                    .build(),
            )
        }
        return ListTemplate.Builder()
            .setTitle("Templates")
            .setHeaderAction(Action.BACK)
            .setSingleList(items.build())
            .build()
    }
}
