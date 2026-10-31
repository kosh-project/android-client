package project.kosh.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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

    @Inject
    lateinit var serverConfigStore: project.kosh.core.network.ServerConfigurationStore

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        for ((permission, isGranted) in permissions) {
            Log.d(TAG, "Permission $permission granted=$isGranted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNetworkPermissions()

        val isServerVerified = serverConfigStore.hasConfiguredHost() &&
                (!serverConfigStore.isTlsEnabled() || serverConfigStore.getPinnedFingerprint() != null)

        val startDestination = when {
            authRepo.isLoggedIn() -> "gallery"
            isServerVerified -> "auth"
            else -> "server_setup"
        }

        enableEdgeToEdge()
        setContent {
            KoshTheme {
                androidx.compose.material3.Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.background
                ) {
                    AppRouter(startDestination)
                }
            }
        }
    }

    private fun requestNetworkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        val accessLocalNetwork = "android.permission.ACCESS_LOCAL_NETWORK"
        if (ContextCompat.checkSelfPermission(this, accessLocalNetwork) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(accessLocalNetwork)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    companion object {
        private const val TAG = "MainActivity"
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