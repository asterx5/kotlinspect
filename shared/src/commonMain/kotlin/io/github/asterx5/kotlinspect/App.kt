package io.github.asterx5.kotlinspect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val client = HttpClient {
    install(KotlinspectPlugin)
    install(HttpTimeout) { requestTimeoutMillis = 15_000 }
}

private const val HTTPBIN = "https://httpbin.org"

/** Runs [block] and reports failures in the status line instead of crashing. */
private fun CoroutineScope.call(onStatus: (String) -> Unit, label: String, block: suspend () -> Unit) = launch {
    onStatus("$label…")
    try {
        block()
        onStatus("$label done")
    } catch (e: Throwable) {
        onStatus("$label: ${e::class.simpleName}")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun App() {
    MaterialTheme {
        val scope = rememberCoroutineScope()
        var status by remember { mutableStateOf("Tap a button to make a call") }
        var bubbleVisible by remember { mutableStateOf(true) }
        var screen by remember { mutableStateOf(0) }
        val onStatus: (String) -> Unit = { status = it }

        Column(
            Modifier
                .fillMaxSize()
                .safeContentPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Kotlinspect sample", style = MaterialTheme.typography.headlineSmall)
            LiveIndicator()
            PrimaryTabRow(selectedTabIndex = screen) {
                Tab(selected = screen == 0, onClick = { screen = 0 }, text = { Text("Playground") })
                Tab(selected = screen == 1, onClick = { screen = 1 }, text = { Text("Request builder") })
            }
            if (screen == 1) {
                RequestBuilderScreen(client)
                return@Column
            }
            Text(status, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)

            Section("Calls") {
                Button(onClick = {
                    scope.call(onStatus, "GET JSON") { client.get("https://jsonplaceholder.typicode.com/todos/1").bodyAsText() }
                }) { Text("GET JSON") }
                Button(onClick = {
                    scope.call(onStatus, "POST login") {
                        client.post("$HTTPBIN/post") {
                            contentType(ContentType.Application.Json)
                            header("Authorization", "Bearer super-secret-token")
                            setBody("""{"username":"ahmed","password":"hunter2"}""")
                        }.bodyAsText()
                    }
                }) { Text("POST login") }
                Button(onClick = { scope.call(onStatus, "404") { client.get("$HTTPBIN/status/404") } }) { Text("404") }
                Button(onClick = { scope.call(onStatus, "500") { client.get("$HTTPBIN/status/500") } }) { Text("500") }
                Button(onClick = {
                    scope.call(onStatus, "Bad host") { client.get("https://does-not-exist.kotlinspect.invalid/") }
                }) { Text("Network failure") }
                Button(onClick = { scope.call(onStatus, "Slow (3s)") { client.get("$HTTPBIN/delay/3") } }) { Text("Slow 3s") }
                Button(onClick = { scope.call(onStatus, "Image") { client.get("$HTTPBIN/image/png") } }) { Text("Binary image") }
                Button(onClick = {
                    scope.call(onStatus, "Stream") {
                        client.prepareGet("$HTTPBIN/stream/20").execute { it.bodyAsChannel().readRemaining() }
                    }
                }) { Text("Streaming") }
                Button(onClick = {
                    val job = scope.call(onStatus, "Cancelled call") { client.get("$HTTPBIN/delay/5") }
                    scope.launch {
                        delay(700)
                        job.cancel()
                        status = "Cancelled call cancelled"
                    }
                }) { Text("Cancel mid-flight") }
                Button(onClick = {
                    repeat(6) { i ->
                        scope.call(onStatus, "Burst") { client.get("https://jsonplaceholder.typicode.com/posts/${i + 1}") }
                    }
                }) { Text("Burst ×6") }
            }

            Section("Sessions") {
                OutlinedButton(onClick = {
                    scope.call(onStatus, "Checkout call") {
                        client.get("https://jsonplaceholder.typicode.com/users/1") { kotlinspectSession("checkout") }
                    }
                }) { Text("Call → \"checkout\"") }
                OutlinedButton(onClick = {
                    scope.call(onStatus, "Onboarding calls") {
                        withKotlinspectSession("onboarding") {
                            client.get("https://jsonplaceholder.typicode.com/users/2")
                            client.get("https://jsonplaceholder.typicode.com/albums/2")
                        }
                    }
                }) { Text("Scope → \"onboarding\"") }
                OutlinedButton(onClick = {
                    Kotlinspect.startSession(name = "Manual test", tags = setOf("demo"), metadata = mapOf("build" to "sample"))
                    status = "Started session \"Manual test\""
                }) { Text("New session") }
            }

            Section("UI") {
                OutlinedButton(onClick = { Kotlinspect.openInspector() }) { Text("Open inspector") }
                OutlinedButton(onClick = {
                    bubbleVisible = !bubbleVisible
                    Kotlinspect.setBubbleVisible(bubbleVisible)
                }) { Text(if (bubbleVisible) "Hide bubble" else "Show bubble") }
            }
        }
    }
}

/** A custom indicator built only from the public observation API. */
@Composable
private fun LiveIndicator() {
    val count by Kotlinspect.callCount.collectAsState(0)
    val inFlight by Kotlinspect.inFlightCount.collectAsState(0)
    val errors by Kotlinspect.errorCount.collectAsState(0)
    val latest by Kotlinspect.latestCall.collectAsState(null)
    val session by Kotlinspect.currentSession.collectAsState(null)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (Kotlinspect.isEnabled) "Session: ${session?.name ?: "…"}" else "Kotlinspect is disabled",
                style = MaterialTheme.typography.titleSmall,
            )
            Text("$count calls · $inFlight in flight · $errors errors", fontFamily = FontFamily.Monospace)
            latest?.let {
                Text(
                    "Latest: ${it.method} ${it.path} → ${it.statusCode ?: it.state}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}
