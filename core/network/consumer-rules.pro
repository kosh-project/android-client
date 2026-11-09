# ProGuard / R8 consumer rules for core:network

# OkHttp
-keepclassmembers class * extends okhttp3.Response {
    public <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
