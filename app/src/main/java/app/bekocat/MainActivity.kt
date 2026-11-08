package app.bekocat

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.bekocat.editor.EditorScreen
import app.bekocat.home.HomeScreen
import app.bekocat.settings.SettingsScreen
import app.bekocat.ui.theme.BekocatTheme

class BekocatApp : Application()

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Bekocat) // leave the splash window theme
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BekocatTheme {
                // Route encoded as a string so it survives process death.
                var route by rememberSaveable { mutableStateOf(if (app.bekocat.onboarding.Onboarding.done(this@MainActivity)) "home" else "onboarding") }
                BackHandler(enabled = route != "home" && route != "onboarding") { route = "home" }
                when {
                    route == "onboarding" -> app.bekocat.onboarding.OnboardingScreen(onDone = { route = "home" })
                    route == "settings" -> SettingsScreen(onBack = { route = "home" })
                    route.startsWith("editor:") -> EditorScreen(route.removePrefix("editor:"), onBack = { route = "home" })
                    else -> HomeScreen(onOpen = { route = "editor:$it" }, onSettings = { route = "settings" })
                }
            }
        }
    }
}
