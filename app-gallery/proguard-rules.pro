# ===================================================================
# Kosh Release ProGuard / R8 Optimization Configuration
# ===================================================================

# -------------------------------------------------------------------
# Source Attributes & Crash Reporting
# -------------------------------------------------------------------
# Preserve line number table and source file attribute for crash reporting and stack trace deobfuscation
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve annotations, signatures, inner classes, enclosing methods, and exceptions
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,RuntimeVisible*Annotations,RuntimeInvisible*Annotations,AnnotationDefault

# -------------------------------------------------------------------
# Logging Stripping
# -------------------------------------------------------------------
# Strip verbose and debug logging calls in optimized release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# -------------------------------------------------------------------
# Kotlinx Serialization Rules
# -------------------------------------------------------------------
# Keep @Serializable classes, allowing unused classes to be stripped by R8,
# while preserving fields, constructors, and synthetic deserialization constructors from optimization
-keep,allowshrinking @kotlinx.serialization.Serializable class * {
    <init>(...);
    synthetic <init>(...);
    <fields>;
}

# Keep companion objects and serializer() generator methods on serializable classes
-keepclassmembers class * {
    public static ** Companion;
}
-keepclassmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep generated $serializer classes and their singleton instances
-keep,allowshrinking class *$$serializer {
    public static final *$$serializer INSTANCE;
    public final kotlinx.serialization.KSerializer[] childSerializers();
    *;
}
-keep,allowshrinking class *$*$$serializer {
    public static final *$*$$serializer INSTANCE;
    public final kotlinx.serialization.KSerializer[] childSerializers();
    *;
}

# Keep synthetic constructor used by serialization for default parameters
-keepclassmembers class * {
    synthetic <init>(int, ..., kotlinx.serialization.internal.SerializationConstructorMarker);
}

# Keep classes implementing KSerializer (custom serializers like AssetTagSerializer)
-keep,allowshrinking class * implements kotlinx.serialization.KSerializer {
    *;
}

# Keep serial names and annotations
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
    @kotlinx.serialization.SerialName <methods>;
}

# Keep core wire DTOs and data models
-keep,allowshrinking class project.kosh.core.model.** { *; }

# -------------------------------------------------------------------
# Room Database Rules
# -------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep @androidx.room.Entity class * {
    *;
}
-keep @androidx.room.Dao interface * {
    *;
}
-keep class * implements androidx.room.RoomDatabase$Callback {
    *;
}
-keep class * extends androidx.room.migration.Migration {
    *;
}
-keep class * implements androidx.room.RoomDatabaseConstructor {
    *;
}
-keepclassmembers class * {
    @androidx.room.TypeConverter *;
}
-keep class * extends androidx.room.EntityDeletionOrUpdateAdapter
-keep class * extends androidx.room.EntityInsertionAdapter
-keep class * extends androidx.room.SharedSQLiteStatement
-keep class *_Impl {
    <init>(...);
    *;
}
-dontwarn androidx.room.paging.**

# -------------------------------------------------------------------
# Dagger & Hilt Rules
# -------------------------------------------------------------------
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }
-keep class * implements dagger.hilt.internal.GeneratedComponent { *; }
-keep interface dagger.hilt.internal.ComponentEntryPoint { *; }
-keep interface dagger.hilt.InstallIn { *; }

-keep @dagger.hilt.android.AndroidEntryPoint class * {
    *;
}
-keep @dagger.hilt.EntryPoint class * {
    *;
}
-keep @dagger.Module class * {
    *;
}
-keepclassmembers class * {
    @dagger.Provides *;
    @dagger.Binds *;
}
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclasseswithmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}

# -------------------------------------------------------------------
# Cryptography, JNI Native & Passkey / WebAuthn Rules
# -------------------------------------------------------------------
# Preserve JNI native method signatures across all classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Argon2Kt JNI library
-keep class com.lambdapioneer.argon2kt.** { *; }
-dontwarn com.lambdapioneer.argon2kt.**

# AndroidX Security Crypto (MasterKey, EncryptedSharedPreferences) & Google Tink
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# AndroidX Credentials & Play Services FIDO2 / WebAuthn
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**
-keep class com.google.android.gms.fido.** { *; }
-dontwarn com.google.android.gms.fido.**

# Internal FIDO Authenticator, CBOR & Cryptographic data models
-keep,allowshrinking class project.kosh.core.auth.fido.EnrolledPasskeyRecord { *; }
-keep,allowshrinking class project.kosh.core.auth.fido.CborEncoder { *; }
-keep,allowshrinking class project.kosh.core.crypto.AesGcmEnvelope { *; }
-keep,allowshrinking class project.kosh.core.crypto.MasterVaultKey { *; }
-keep,allowshrinking class project.kosh.core.crypto.X25519KeyPair { *; }
-keep,allowshrinking class project.kosh.core.crypto.Bip39Sas { *; }

# -------------------------------------------------------------------
# Networking & Third-Party Libraries
# -------------------------------------------------------------------
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

# ZXing (QR Code generation and scanning)
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**