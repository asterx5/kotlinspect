package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.internal.KotlinspectRuntime
import io.github.asterx5.kotlinspect.internal.capture.CallRule
import io.github.asterx5.kotlinspect.internal.capture.HttpCapture
import io.github.asterx5.kotlinspect.internal.capture.RuleChain
import io.ktor.client.plugins.api.ClientHook
import io.ktor.client.plugins.api.ClientPlugin
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.SetupRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpSendPipeline
import io.ktor.client.statement.HttpReceivePipeline
import io.ktor.client.statement.HttpResponse
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import io.ktor.client.HttpClient
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Configuration for [KotlinspectPlugin]. */
public class KotlinspectPluginConfig {
    /** Set to false to skip capture for this client only. */
    public var enabled: Boolean = true

    /** Puts every call made by this client in the session with this name. */
    public var session: String? = null

    internal var runtimeProvider: () -> KotlinspectRuntime? = { Kotlinspect.runtimeOrNull() }
    internal val rules: MutableList<CallRule> = mutableListOf()
}

/**
 * Ktor client plugin that records every call made by the client.
 *
 * ```
 * val client = HttpClient {
 *     install(KotlinspectPlugin)
 * }
 * ```
 *
 * Capture is a no-op when [Kotlinspect.isEnabled] is false, which is the default in release builds.
 */
public val KotlinspectPlugin: ClientPlugin<KotlinspectPluginConfig> =
    createClientPlugin("Kotlinspect", ::KotlinspectPluginConfig) {
        if (!pluginConfig.enabled) return@createClientPlugin
        val runtimeProvider = pluginConfig.runtimeProvider
        val clientSession = pluginConfig.session
        val rules = pluginConfig.rules.toList()

        on(SetupRequest) { request ->
            if (!request.attributes.contains(SessionNameKey)) {
                currentCoroutineContext()[KotlinspectSessionContext]?.let {
                    request.attributes.put(SessionNameKey, it.name)
                }
            }
        }

        on(MonitoringHook) { request, body ->
            val runtime = runtimeProvider()
            if (runtime == null || !runtime.config.enabled) {
                proceed()
                return@on
            }
            val sessionName = request.attributes.getOrNull(SessionNameKey)
                ?: currentCoroutineContext()[KotlinspectSessionContext]?.name
                ?: clientSession
            val capture = HttpCapture.begin(runtime, request, body as? OutgoingContent, sessionName)
            request.attributes.put(CaptureKey, capture)
            try {
                proceed()
                capture.onExchangeReturned()
            } catch (e: Throwable) {
                capture.onFailure(e)
                throw e
            }
        }

        on(AfterReceiveHook) { response ->
            val capture = response.call.attributes.getOrNull(CaptureKey) ?: return@on
            val replacement = capture.onResponse(response)
            if (replacement != null) proceedWith(replacement)
        }

        if (rules.isNotEmpty()) {
            on(Send) { request -> RuleChain(rules) { proceed(it) }.proceed(request) }
        }
    }

/** Puts this call in the session named [name], creating the session if needed. */
public fun HttpRequestBuilder.kotlinspectSession(name: String) {
    attributes.put(SessionNameKey, name)
}

/**
 * Coroutine context element that puts every call made inside it in the session named [name].
 *
 * ```
 * withContext(KotlinspectSessionContext("checkout")) { api.placeOrder() }
 * ```
 */
public class KotlinspectSessionContext(public val name: String) :
    AbstractCoroutineContextElement(KotlinspectSessionContext) {
    public companion object Key : CoroutineContext.Key<KotlinspectSessionContext>
}

/** Runs [block] with every call made inside it recorded in the session named [name]. */
public suspend fun <T> withKotlinspectSession(name: String, block: suspend () -> T): T =
    withContext(KotlinspectSessionContext(name)) { block() }

private val SessionNameKey = AttributeKey<String>("KotlinspectSession")
private val CaptureKey = AttributeKey<HttpCapture>("KotlinspectCapture")

/** Wraps the send pipeline, which covers the engine call and reading the response body. */
private object MonitoringHook : ClientHook<suspend MonitoringHook.Context.(HttpRequestBuilder, Any) -> Unit> {
    class Context(private val context: PipelineContext<Any, HttpRequestBuilder>) {
        suspend fun proceed() {
            context.proceed()
        }
    }

    override fun install(client: HttpClient, handler: suspend Context.(HttpRequestBuilder, Any) -> Unit) {
        client.sendPipeline.intercept(HttpSendPipeline.Monitoring) {
            handler(Context(this), context, subject)
        }
    }
}

/** Runs after the body was saved (non-streaming) or before it is consumed (streaming). */
private object AfterReceiveHook : ClientHook<suspend AfterReceiveHook.Context.(HttpResponse) -> Unit> {
    class Context(private val context: PipelineContext<HttpResponse, Unit>) {
        suspend fun proceedWith(response: HttpResponse) {
            context.proceedWith(response)
        }
    }

    override fun install(client: HttpClient, handler: suspend Context.(HttpResponse) -> Unit) {
        client.receivePipeline.intercept(HttpReceivePipeline.After) {
            handler(Context(this), subject)
        }
    }
}
