package project.kosh.app

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import project.kosh.feature.auth.AuthScreen
import project.kosh.feature.gallery.GalleryScreen


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