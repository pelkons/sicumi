package app.sicumi.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.sicumi.R

/** Secular One — заголовки. Heebo (вариативный) — всё остальное. */
val SecularOne = FontFamily(Font(R.font.secular_one))

val Heebo = FontFamily(
    Font(R.font.heebo, FontWeight.Normal),
    Font(R.font.heebo, FontWeight.Medium),
    Font(R.font.heebo, FontWeight.SemiBold),
    Font(R.font.heebo, FontWeight.Bold),
)

internal val SicumiTypography = Typography(
    headlineLarge = TextStyle(fontFamily = SecularOne, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = SecularOne, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = SecularOne, fontSize = 26.sp, lineHeight = 32.sp),
    titleMedium = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.Bold, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = Heebo, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Heebo, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = Heebo, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = Heebo, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp),
)
