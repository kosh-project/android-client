package project.kosh.app

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import project.kosh.app.ui.theme.KoshTheme
import project.kosh.core.auth.AuthRepository
import project.kosh.core.crypto.IdentityManager
import project.kosh.feature.auth.AuthScreen
import javax.inject.Inject

/**
 * Single Activity entry point for the Kosh Android application.
 *
 * Annotated with [AndroidEntryPoint] to enable dependency injection into the activity context.
 * Responsible for inspecting the device's authentication state on launch and initializing
 * Jetpack Compose UI with edge-to-edge system bar integration.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Injected cryptographic identity manager.
     */
    @Inject
    lateinit var identityManager: IdentityManager

    /**
     * Injected authentication repository used to verify session persistence on startup.
     */
    @Inject
    lateinit var authRepo: AuthRepository

    /**
     * Activity initialization lifecycle callback.
     *
     * Queries [AuthRepository.isLoggedIn] to determine the initial navigation destination:
     * - `"gallery"` if an active session token is present.
     * - `"auth"` if unauthenticated or session has expired.
     *
     * @param savedInstanceState Persisted instance state bundle, if recreating after configuration change.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startDestination = if (authRepo.isLoggedIn()) "gallery" else "auth"

        enableEdgeToEdge()
        setContent {
            KoshTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(Modifier.padding(innerPadding)) {
                        AppRouter(startDestination)
                    }
                }
            }
        }
    }
}

/**
 * Preview and greeting composable demonstration.
 *
 * @param name The subject name to greet.
 * @param modifier Composable modifier for layout adjustment.
 */
@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

/**
 * Compose preview rendering the [Greeting] composable inside [KoshTheme].
 */
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    KoshTheme {
        Greeting("Android")
    }
}