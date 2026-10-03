package project.kosh.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point and Dagger Hilt root container for the Kosh Android client.
 *
 * Annotated with [HiltAndroidApp] to trigger Hilt's bytecode generation, initializing
 * application-wide singleton dependencies (such as [project.kosh.core.auth.SessionManager],
 * [project.kosh.core.crypto.IdentityManager], and OkHttp client singletons) on process creation.
 */
@HiltAndroidApp
class KoshApplication: android.app.Application()