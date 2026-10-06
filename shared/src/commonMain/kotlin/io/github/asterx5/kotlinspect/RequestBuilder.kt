package io.github.asterx5.kotlinspect

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.Parameters
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One editable key-value row: a query parameter, header or form field. */
@Stable
class KeyValueRow(key: String = "", value: String = "", enabled: Boolean = true) {
    var key by mutableStateOf(key)
    var value by mutableStateOf(value)
    var enabled by mutableStateOf(enabled)
}

enum class BodyType(val label: String) { None("None"), Json("JSON"), Text("Text"), Form("Form") }

private enum class EditorTab { Params, Headers, Body }

private class SentResponse(
    val status: Int?,
    val statusText: String,
    val durationMs: Long,
    val headers: List<Pair<String, String>>,
    val body: String,
    val error: String?,
)

private val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
private val prettyJson = Json { prettyPrint = true }

private fun prettyOrRaw(text: String): String = runCatching {
    val trimmed = text.trimStart()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return text
    prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
}.getOrDefault(text)

/** A small Postman-style screen. Every request is captured by Kotlinspect like any other call. */
@Composable
fun RequestBuilderScreen(client: HttpClient) {
    val scope = rememberCoroutineScope()
    var method by remember { mutableStateOf("POST") }
    var url by remember { mutableStateOf("https://httpbin.org/anything") }
    val params = remember { mutableStateListOf(KeyValueRow("page", "1")) }
    val headers = remember { mutableStateListOf(KeyValueRow("X-Client", "kotlinspect-sample")) }
    val formFields = remember { mutableStateListOf(KeyValueRow("username", "ahmed")) }
    var bodyType by remember { mutableStateOf(BodyType.Json) }
    var bodyText by remember { mutableStateOf("{\n  \"hello\": \"world\"\n}") }
    var session by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(EditorTab.Params) }
    var response by remember { mutableStateOf<SentResponse?>(null) }
    var sending by remember { mutableStateOf<Job?>(null) }

    fun send() {
        sending?.cancel()
        sending = scope.launch {
            val started = getTimeMillis()
            response = try {
                val result = client.request(url.trim()) {
                    this.method = HttpMethod.parse(method)
                    params.filter { it.enabled && it.key.isNotBlank() }.forEach { parameter(it.key, it.value) }
                    headers.filter { it.enabled && it.key.isNotBlank() }.forEach { header(it.key, it.value) }
                    if (session.isNotBlank()) kotlinspectSession(session.trim())
                    when (bodyType) {
                        BodyType.None -> Unit
                        BodyType.Json -> setBody(TextContent(bodyText, ContentType.Application.Json))
                        BodyType.Text -> setBody(TextContent(bodyText, ContentType.Text.Plain))
                        BodyType.Form -> setBody(
                            FormDataContent(
                                Parameters.build {
                                    formFields.filter { it.enabled && it.key.isNotBlank() }.forEach { append(it.key, it.value) }
                                },
                            ),
                        )
                    }
                }
                SentResponse(
                    status = result.status.value,
                    statusText = result.status.description,
                    durationMs = getTimeMillis() - started,
                    headers = result.headers.entries().flatMap { (k, v) -> v.map { k to it } },
                    body = prettyOrRaw(result.bodyAsText()),
                    error = null,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                SentResponse(null, "", getTimeMillis() - started, emptyList(), "", "${e::class.simpleName}: ${e.message}")
            } finally {
                sending = null
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Method + URL + Send
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MethodPicker(method) { method = it }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = session,
                onValueChange = { session = it },
                label = { Text("Session (optional)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (sending != null) {
                OutlinedButton(onClick = { sending?.cancel(); sending = null }) { Text("Cancel") }
            } else {
                Button(onClick = ::send, enabled = url.isNotBlank()) { Text("Send") }
            }
        }

        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            EditorTab.entries.forEach { t ->
                val count = when (t) {
                    EditorTab.Params -> params.count { it.enabled && it.key.isNotBlank() }
                    EditorTab.Headers -> headers.count { it.enabled && it.key.isNotBlank() }
                    EditorTab.Body -> if (bodyType == BodyType.None) 0 else -1
                }
                val label = when {
                    count > 0 -> "${t.name} ($count)"
                    count < 0 -> "${t.name} · ${bodyType.label}"
                    else -> t.name
                }
                Tab(selected = tab == t, onClick = { tab = t }, text = { Text(label) })
            }
        }

        when (tab) {
            EditorTab.Params -> KeyValueEditor(params, keyHint = "Parameter", valueHint = "Value")
            EditorTab.Headers -> KeyValueEditor(headers, keyHint = "Header", valueHint = "Value")
            EditorTab.Body -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BodyType.entries.forEach { type ->
                        FilterChip(selected = bodyType == type, onClick = { bodyType = type }, label = { Text(type.label) })
                    }
                }
                when (bodyType) {
                    BodyType.None -> Text("No body is sent.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BodyType.Form -> KeyValueEditor(formFields, keyHint = "Field", valueHint = "Value")
                    BodyType.Json, BodyType.Text -> {
                        OutlinedTextField(
                            value = bodyText,
                            onValueChange = { bodyText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            isError = bodyType == BodyType.Json && bodyText.isNotBlank() &&
                                runCatching { Json.parseToJsonElement(bodyText) }.isFailure,
                            supportingText = if (bodyType == BodyType.Json) {
                                { Text("Sent with Content-Type: application/json") }
                            } else {
                                null
                            },
                        )
                        if (bodyType == BodyType.Json) {
                            TextButton(onClick = { bodyText = prettyOrRaw(bodyText) }) { Text("Format JSON") }
                        }
                    }
                }
            }
        }

        if (sending != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp), strokeWidth = 2.dp)
                Text("  Sending…")
            }
        }
        response?.let { ResponseView(it) }
    }
}

@Composable
private fun MethodPicker(method: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("$method ▾", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            methods.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m, fontFamily = FontFamily.Monospace) },
                    onClick = {
                        onChange(m)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun KeyValueEditor(rows: SnapshotStateList<KeyValueRow>, keyHint: String, valueHint: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Checkbox(checked = row.enabled, onCheckedChange = { row.enabled = it })
                OutlinedTextField(
                    value = row.key,
                    onValueChange = { row.key = it },
                    placeholder = { Text(keyHint) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { row.value = it },
                    placeholder = { Text(valueHint) },
                    singleLine = true,
                    modifier = Modifier.weight(1.3f),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                TextButton(onClick = { rows.remove(row) }) { Text("✕") }
            }
        }
        OutlinedButton(onClick = { rows.add(KeyValueRow()) }) { Text("+ Add $keyHint".lowercase().replaceFirstChar { it.uppercase() }) }
    }
}

@Composable
private fun ResponseView(r: SentResponse) {
    val color = when {
        r.error != null -> Color(0xFFC62828)
        (r.status ?: 0) >= 500 -> Color(0xFFC62828)
        (r.status ?: 0) >= 400 -> Color(0xFFEF6C00)
        (r.status ?: 0) >= 300 -> Color(0xFF1565C0)
        else -> Color(0xFF2E7D32)
    }
    var showHeaders by remember(r) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Response", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                r.error?.let { "Failed" } ?: "${r.status} ${r.statusText}",
                color = color,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            Text("  ·  ${r.durationMs} ms", fontFamily = FontFamily.Monospace)
        }
        r.error?.let { Text(it, color = color, style = MaterialTheme.typography.bodySmall) }
        if (r.headers.isNotEmpty()) {
            TextButton(onClick = { showHeaders = !showHeaders }) {
                Text((if (showHeaders) "Hide" else "Show") + " headers (${r.headers.size})")
            }
            if (showHeaders) {
                SelectionContainer {
                    Text(
                        r.headers.joinToString("\n") { "${it.first}: ${it.second}" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    )
                }
            }
        }
        if (r.body.isNotEmpty()) {
            SelectionContainer {
                Text(
                    r.body.take(20_000),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            }
        }
        Text(
            "Also captured by Kotlinspect: tap the bubble to see it in the inspector.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
