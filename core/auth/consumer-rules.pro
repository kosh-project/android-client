# ProGuard / R8 consumer rules for core:auth

# AndroidX Credentials & Play Services FIDO2 / WebAuthn
-keep class androidx.credentials.** { *; }
-dontwarn androidx.credentials.**
-keep class com.google.android.gms.fido.** { *; }
-dontwarn com.google.android.gms.fido.**

# Internal FIDO Authenticator & auth models
-keep,allowshrinking class project.kosh.core.auth.fido.EnrolledPasskeyRecord { *; }
-keep,allowshrinking class project.kosh.core.auth.fido.CborEncoder { *; }
