package app.sicumi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.sicumi.ui.home.HomeScreen
import app.sicumi.ui.theme.SicumiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SicumiTheme {
                HomeScreen()
            }
        }
    }
}
