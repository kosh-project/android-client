package project.kosh.core.auth

import android.content.Context
import androidx.credentials.CredentialManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import project.kosh.core.auth.fido.AndroidKeyStoreProvider
import project.kosh.core.auth.fido.BiometricPromptProvider
import project.kosh.core.auth.fido.DefaultBiometricPromptProvider
import project.kosh.core.auth.fido.KeyStoreProvider
import project.kosh.core.network.TokenProvider
import javax.inject.Singleton

/**
 * Dagger Hilt dependency injection module configuring authentication layer bindings.
 *
 * Exposes internal auth implementations across architectural boundaries, binding
 * [SessionManager] to the abstract [TokenProvider] interface and providing hardware
 * KeyStore and biometric providers for [project.kosh.core.auth.fido.InternalFidoAuthenticator].
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    /**
     * Binds the hardware-backed [SessionManager] implementation to the [TokenProvider] interface.
     */
    @Binds
    abstract fun bindTokenProvider(
        sessionManager: SessionManager
    ): TokenProvider

    /**
     * Binds the hardware-backed [AndroidKeyStoreProvider] to [KeyStoreProvider].
     */
    @Binds
    @Singleton
    abstract fun bindKeyStoreProvider(
        provider: AndroidKeyStoreProvider
    ): KeyStoreProvider

    /**
     * Binds [DefaultBiometricPromptProvider] to [BiometricPromptProvider].
     */
    @Binds
    @Singleton
    abstract fun bindBiometricPromptProvider(
        provider: DefaultBiometricPromptProvider
    ): BiometricPromptProvider

    companion object {
        /**
         * Provides the singleton Android [CredentialManager] instance for backwards compatibility.
         */
        @Provides
        @Singleton
        fun provideCredentialManager(
            @ApplicationContext context: Context
        ): CredentialManager = CredentialManager.create(context)
    }
}