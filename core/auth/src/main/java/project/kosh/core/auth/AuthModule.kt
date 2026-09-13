package project.kosh.core.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import project.kosh.core.network.TokenProvider

@Module
@InstallIn(SingletonComponent::class)
abstract class AuthModule {

    @Binds
    abstract fun bindTokenProvider(
        sessionManager: SessionManager
    ): TokenProvider
}