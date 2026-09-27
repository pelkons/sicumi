package app.sicumi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.sicumi.dictation.DictationSetupScreen
import app.sicumi.settings.SettingsScreen
import app.sicumi.ui.home.HomeScreen
import app.sicumi.ui.theme.SicumiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SicumiTheme {
                // Временная навигация до появления Navigation Compose.
                var screen by rememberSaveable { mutableStateOf(SCREEN_HOME) }
                when (screen) {
                    SCREEN_DICTATION -> {
                        BackHandler { screen = SCREEN_HOME }
                        DictationSetupScreen(onBack = { screen = SCREEN_HOME })
                    }
                    SCREEN_SETTINGS -> {
                        BackHandler { screen = SCREEN_HOME }
                        SettingsScreen(onBack = { screen = SCREEN_HOME })
                    }
                    else -> HomeScreen(
                        onOpenDictation = { screen = SCREEN_DICTATION },
                        onOpenSettings = { screen = SCREEN_SETTINGS },
                    )
                }
            }
        }
    }

    private companion object {
        const val SCREEN_HOME = "home"
        const val SCREEN_DICTATION = "dictation"
        const val SCREEN_SETTINGS = "settings"
    }
}
