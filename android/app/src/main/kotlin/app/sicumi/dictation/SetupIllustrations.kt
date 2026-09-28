package app.sicumi.dictation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.sicumi.R
import app.sicumi.ui.theme.SicumiColors
import app.sicumi.ui.theme.SicumiShapes

/**
 * Упрощённые копии системных экранов Android: показывают, куда именно нажимать.
 * Направление и язык — как у системы телефона, а не как у приложения.
 */
private object Mock {
    val Screen = Color(0xFFEDEDF2)
    val Card = Color(0xFFFFFFFF)
    val Text = Color(0xFF1C1B1F)
    val Secondary = Color(0xFF5F5F66)
    val Accent = Color(0xFF2F5FD0)
    val SwitchOff = Color(0xFFB4B4BC)
    val RowShape = RoundedCornerShape(16.dp)
    val body = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, lineHeight = 22.sp, color = Text)
    val title = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, color = Text)
}

private fun Modifier.ring(on: Boolean, shape: androidx.compose.ui.graphics.Shape): Modifier =
    if (on) this.border(2.5.dp, SicumiColors.Tangerine, shape) else this

/** Рамка «экрана телефона» с системным направлением текста. */
@Composable
fun MockFrame(rtl: Boolean, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(SicumiShapes.Card)
                .background(Mock.Screen)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun MockHeader(title: String, menuHighlighted: Boolean = false, showMenu: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Mock.title, modifier = Modifier.weight(1f))
        if (showMenu) {
            Box(
                Modifier
                    .size(40.dp)
                    .ring(menuHighlighted, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_more),
                    contentDescription = null,
                    tint = Mock.Text,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
fun MockRow(
    label: String,
    highlighted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Mock.RowShape)
            .background(Mock.Card)
            .ring(highlighted, Mock.RowShape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Mock.body, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun MockSwitch(on: Boolean, highlighted: Boolean = false) {
    Box(
        Modifier
            .ring(highlighted, SicumiShapes.Pill)
            .padding(4.dp),
    ) {
        Box(
            Modifier
                .size(width = 46.dp, height = 26.dp)
                .background(if (on) Mock.Accent else Mock.SwitchOff, SicumiShapes.Pill)
                .padding(3.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(20.dp)
                    .background(Mock.Card, CircleShape),
            )
        }
    }
}

/** «Не включать»: выключенный переключатель с оранжевым крестиком. */
@Composable
fun MockSwitchOffMarked() {
    Box(contentAlignment = Alignment.Center) {
        MockSwitch(on = false)
        Icon(
            painter = painterResource(R.drawable.ic_close),
            contentDescription = null,
            tint = SicumiColors.Tangerine,
            modifier = Modifier.size(34.dp),
        )
    }
}

/** Системный диалог с двумя кнопками; подсвечена кнопка «разрешить». */
@Composable
fun MockConfirmDialog(title: String, deny: String, allow: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(SicumiShapes.Card)
            .background(Mock.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = Mock.title, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Text(deny, style = Mock.body.copy(color = Mock.Accent), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Text(
                allow,
                style = Mock.body.copy(color = Mock.Accent, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .ring(true, SicumiShapes.Pill)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/** Системный запрос разрешения с вариантами; подсвечен нужный. */
@Composable
fun MockPermissionDialog(title: String, options: List<String>, highlighted: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(SicumiShapes.Card)
            .background(Mock.Card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            title,
            style = Mock.title,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        )
        options.forEachIndexed { i, option ->
            Text(
                option,
                style = Mock.body.copy(color = if (i == highlighted) Mock.Text else Mock.Secondary),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(SicumiShapes.Pill)
                    .background(Mock.Screen)
                    .ring(i == highlighted, SicumiShapes.Pill)
                    .padding(vertical = 12.dp),
            )
        }
    }
}

/** Нумерованный шаг инструкции с иллюстрацией. */
@Composable
fun InstructionStep(number: Int, text: String, illustration: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(28.dp)
                    .background(SicumiColors.Violet, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge, color = SicumiColors.White)
            }
            Text(text, style = MaterialTheme.typography.titleMedium, color = SicumiColors.Ink, modifier = Modifier.weight(1f))
        }
        illustration?.invoke()
    }
}
