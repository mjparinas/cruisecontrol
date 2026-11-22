package dev.aiauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun TalkScreen(
    onBack: () -> Unit,
    autoListen: Boolean = false,
    onAutoListenHandled: () -> Unit = {},
    onAsk: suspend (String) -> String,
) {
    val messages = remember { mutableStateListOf<Pair<Boolean, String>>() }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(messages.size, busy) {
        val last = messages.size + if (busy) 1 else 0
        if (last > 0) list.animateScrollToItem(last - 1)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar("Talk to Claude", onBack) },
        floatingActionButton = {
            MicButton(
                enabled = !busy,
                autoStart = autoListen,
                onAutoStartHandled = onAutoListenHandled,
            ) { text ->
                if (!busy) {
                    messages += true to text
                    busy = true
                    scope.launch {
                        val answer = runCatching { withContext(Dispatchers.IO) { onAsk(text) } }.getOrElse { "Sorry, that didn't go through. Try again." }
                        messages += false to answer
                        busy = false
                    }
                }
            }
        },
    ) { padding ->
        if (messages.isEmpty() && !busy) {
            Column(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(Icons.Rounded.QuestionAnswer, BadgeHue.Accent, "Talk to Claude", "Ask about your tasks and repositories, or start a task by voice.")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(messages) { _, (fromYou, text) ->
                    SettingsCard {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(if (fromYou) "You" else "Claude", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            Text(text, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                if (busy) item { Text("Claude is thinking…", Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
