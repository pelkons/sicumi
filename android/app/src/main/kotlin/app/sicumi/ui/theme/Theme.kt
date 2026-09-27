package app.sicumi.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * Корневая тема. RTL задаётся принудительно: приложение только на иврите,
 * и раскладка должна быть справа налево даже на телефоне с английским интерфейсом.
 */
@Composable
fun SicumiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SicumiColorScheme,
        typography = SicumiTypography,
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            content()
        }
    }
}
