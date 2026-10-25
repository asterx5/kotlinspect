package io.github.asterx5.kotlinspect

/** Calls made through Retrofit on an OkHttp client with the Kotlinspect interceptor. */
interface RetrofitDemo {
    suspend fun getJson(): String
    suspend fun postJson(): String
    suspend fun notFound(): String
    suspend fun timeout(): String
}

/** Retrofit runs on Android and desktop; iOS has no Retrofit tab. */
expect fun retrofitDemo(): RetrofitDemo?
