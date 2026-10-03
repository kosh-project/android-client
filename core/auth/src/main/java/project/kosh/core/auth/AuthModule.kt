package project.kosh.core.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import project.kosh.core.network.TokenProvider

/**
 * Dagger Hilt dependency injection module configuring authentication layer bindings.
 *
 * Exposes internal auth implementations across architectural boundaries, binding
 * [SessionManager] to the abstract [TokenProvider] interface.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    /**
     * Binds the hardware-backed [SessionManager] implementation to the [TokenProvider] interface.
     *
     * This binding allows the network layer to inject the session token provider without
     * creating a tight coupling to AndroidKeyStore mechanics or session persistence logic.
     *
     * @param sessionManager The singleton session manager instance.
     * @return The bound [TokenProvider] interface.
     */
    @Binds
    abstract fun bindTokenProvider(
        sessionManager: SessionManager
    ): TokenProvider
}