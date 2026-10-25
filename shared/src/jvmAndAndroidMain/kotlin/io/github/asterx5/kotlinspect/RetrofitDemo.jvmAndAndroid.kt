package io.github.asterx5.kotlinspect

import io.github.asterx5.kotlinspect.okhttp.KotlinspectEventListenerFactory
import io.github.asterx5.kotlinspect.okhttp.kotlinspect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

private interface HttpBinApi {
    @GET("json")
    suspend fun json(): String

    @POST("post")
    suspend fun post(@Body body: RequestBody): String

    @GET("status/404")
    suspend fun missing(): Response<String>

    @GET("delay/5")
    suspend fun slow(): String
}

private object RetrofitDemoImpl : RetrofitDemo {
    // The interceptor records every call; the event listener adds DNS, connect and TLS timings.
    private val client = OkHttpClient.Builder()
        .kotlinspect()
        .eventListenerFactory(KotlinspectEventListenerFactory())
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val api = Retrofit.Builder()
        .baseUrl("https://httpbin.org/")
        .client(client)
        .addConverterFactory(ScalarsConverterFactory.create())
        .build()
        .create(HttpBinApi::class.java)

    override suspend fun getJson(): String = "${api.json().length} bytes"

    override suspend fun postJson(): String =
        "${api.post("""{"username":"demo","items":[1,2,3]}""".toRequestBody("application/json".toMediaType())).length} bytes"

    override suspend fun notFound(): String = "HTTP ${api.missing().code()}"

    override suspend fun timeout(): String = api.slow()
}

actual fun retrofitDemo(): RetrofitDemo? = RetrofitDemoImpl
