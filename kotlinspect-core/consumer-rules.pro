# Applied to apps that use Kotlinspect.
# OkHttp and Okio are optional: the OkHttp interceptor is only loaded by apps that use OkHttp.
# Apps without them must still pass R8's missing-class check.
-dontwarn okhttp3.**
-dontwarn okio.**
