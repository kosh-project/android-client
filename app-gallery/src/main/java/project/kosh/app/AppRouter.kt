package project.kosh.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import project.kosh.feature.auth.AuthScreen
import project.kosh.feature.auth.ServerSetupScreen
import project.kosh.feature.auth.UserSetupScreen
import project.kosh.feature.gallery.GalleryScreen

/**
 * Primary Jetpack Compose navigation host for the application.
 *
 * Configures the navigation graph between feature destinations:
 * - `"server_setup"`: Locates the server via mDNS/manual input and performs TOFU certificate verification.
 * - `"auth"`: Renders [AuthScreen] for passkey sign in, registration, or phrase recovery.
 * - `"setup"`: Post-registration onboarding and passkey enrollment.
 * - `"gallery"`: Media vault and CAS operations.
 *
 * @param startDestination The initial route name evaluated from server configuration and login state.
 */
@Composable
fun AppRouter(startDestination: String) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable("server_setup") {
            ServerSetupScreen(
                onServerVerified = {
                    navController.navigate("auth") {
                        popUpTo("server_setup") { inclusive = true }
                    }
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        composable("auth") {
            AuthScreen(
                onChangeServer = {
                    navController.navigate("server_setup")
                },
                onAuthSuccess = {
                    navController.navigate("gallery") {
                        popUpTo("auth") { inclusive = true }
                    }
                },
                onRegisterSuccess = {
                    navController.navigate("setup") {
                        popUpTo("auth") { inclusive = true }
                    }
                }
            )
        }

        composable("setup") {
            UserSetupScreen(
                onFinish = {
                    navController.navigate("gallery") {
                        popUpTo("setup") { inclusive = true }
                    }
                }
            )
        }

        composable("gallery") {
            GalleryScreen(
                onLogout = {
                    navController.navigate("auth") {
                        popUpTo("gallery") { inclusive = true }
                    }
                },
                onOpenServerConfig = {
                    navController.navigate("server_setup")
                }
            )
        }
    }
}