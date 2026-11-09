# ProGuard / R8 consumer rules for core:crypto

# Preserve JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Argon2Kt JNI library
-keep class com.lambdapioneer.argon2kt.** { *; }
-dontwarn com.lambdapioneer.argon2kt.**

# Google Tink (used by crypto primitives / envelope)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Cryptographic data models
-keep,allowshrinking class project.kosh.core.crypto.AesGcmEnvelope { *; }
-keep,allowshrinking class project.kosh.core.crypto.MasterVaultKey { *; }
-keep,allowshrinking class project.kosh.core.crypto.X25519KeyPair { *; }
-keep,allowshrinking class project.kosh.core.crypto.Bip39Sas { *; }
