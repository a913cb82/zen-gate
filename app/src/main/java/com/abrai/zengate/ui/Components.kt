package com.abrai.zengate.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun zenScreen(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Paper)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        content = content,
    )
}

@Composable
fun backHeader(
    title: String,
    onBack: () -> Unit,
    backLabel: String = "Home",
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹  $backLabel",
                color = Green,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ink,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun zenCard(
    modifier: Modifier = Modifier,
    border: Color = Border,
    container: Color = Card,
    borderWidth: Int = 1,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = BorderStroke(borderWidth.dp, border),
    ) {
        Column(content = content)
    }
}

@Composable
fun navRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    accent: Color = Ink,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Card,
        border = BorderStroke(1.dp, Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = accent)
                Spacer(Modifier.height(3.dp))
                Text(subtitle, fontSize = 12.sp, color = Muted)
            }
            if (trailing != null) {
                trailing()
            } else {
                Text("›", fontSize = 20.sp, color = Subtle)
            }
        }
    }
}

/** Visual ON/OFF pill with a full 48dp touch target. */
@Composable
fun onOffPill(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier =
            Modifier
                .size(width = 84.dp, height = 48.dp)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Switch,
                ) { onToggle(!on) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 76.dp, height = 28.dp)
                    .background(if (on) Green else Quiet, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (on) "ON" else "OFF",
                color = if (on) Color.White else Color(0xFF777777),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
fun letterTile(
    label: String,
    active: Boolean,
) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .background(if (active) QuickBg else Soft, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label
                .trim()
                .take(1)
                .uppercase()
                .ifEmpty { "?" },
            fontSize = 18.sp,
            color = Ink,
        )
    }
}

@Composable
fun overlayChip() {
    Box(
        modifier =
            Modifier
                .height(18.dp)
                .background(Amber, RoundedCornerShape(9.dp))
                .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("OVERLAY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = AmberInk)
    }
}

@Composable
fun statTile(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    sub: String,
    container: Color = Card,
    contentColor: Color = Ink,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = container,
        border = if (container == Card) BorderStroke(1.dp, Border) else null,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = contentColor)
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 12.sp, color = if (contentColor == Ink) Muted else contentColor.copy(alpha = 0.8f))
            Text(
                sub,
                fontSize = 11.sp,
                color = if (contentColor == Ink) Subtle else contentColor.copy(alpha = 0.65f),
            )
        }
    }
}

@Composable
fun fixPill(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Green,
    ) {
        Text(
            text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
