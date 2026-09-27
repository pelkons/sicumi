package app.sicumi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

object SicumiShapes {
    val Button = RoundedCornerShape(16.dp)
    val Card = RoundedCornerShape(24.dp)
    val Block = RoundedCornerShape(32.dp)
    val Pill = RoundedCornerShape(percent = 50)

    /** «Капля» — только для главного действия (запись, диктовка). */
    val Blob = RoundedCornerShape(
        topStartPercent = 40,
        topEndPercent = 50,
        bottomEndPercent = 45,
        bottomStartPercent = 35,
    )
}
