package project.kosh.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import project.kosh.feature.auth.AuthScreen
import project.kosh.feature.gallery.GalleryScreen


/**
 * Primary Jetpack Compose navigation host for the application.
 *
 * Configures the navigation graph between top-level feature destinations:
 * - `"auth"`: Renders [AuthScreen] for login and registration. Upon successful authentication,
 *   navigates to `"gallery"` and clears `"auth"` from the back stack (`popUpTo("auth") { inclusive = true }`).
 * - `"gallery"`: Renders [GalleryScreen] for media selection and CAS uploads.
 *
 * @param startDestination The initial route name (`"auth"` or `"gallery"`), evaluated from login state.
 */
@Composable
fun AppRouter(startDestination: String) {
    val navController = rememberNavController()

    NavHost(navController, startDestination) {

        composable ("auth") {
            AuthScreen(
                onAuthSuccess = {
                    navController.navigate("gallery") {
                        popUpTo("auth") { inclusive = true }
                    }
                }
            )
        }

        composable ("gallery") {
            GalleryScreen()
        }
    }
}