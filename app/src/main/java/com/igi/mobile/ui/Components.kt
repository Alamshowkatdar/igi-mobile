package com.igi.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val mb = bytes / 1048576.0
    return if (mb >= 1024.0) {
        String.format(Locale.US, "%.2f GB", mb / 1024.0)
    } else {
        String.format(Locale.US, "%.0f MB", mb)
    }
}

fun formatSpeed(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0L) "" else String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0)

@Composable
fun TerminalPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(IgiSurface, RoundedCornerShape(3.dp))
            .border(1.dp, IgiGreen.copy(alpha = 0.28f), RoundedCornerShape(3.dp))
            .padding(16.dp),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = "> $text",
        modifier = modifier,
        color = IgiGreen,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        letterSpacing = 1.5.sp
    )
}

@Composable
fun IgiButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(50.dp),
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        border = if (primary) null else BorderStroke(1.dp, IgiGreen.copy(alpha = 0.55f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) IgiGreen else Color.Transparent,
            contentColor = if (primary) Color.Black else IgiGreen,
            disabledContainerColor = if (primary) IgiGreen.copy(alpha = 0.25f) else Color.Transparent,
            disabledContentColor = IgiDim
        )
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun TextLink(text: String, color: Color = IgiGold, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = text,
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun TopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onBack) {
            Text(
                text = "<",
                color = IgiGreen,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        }
        Text(
            text = title,
            color = IgiText,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun StatusPill(label: String, value: String, good: Boolean) {
    Row(
        modifier = Modifier
            .background(if (good) IgiGreen.copy(alpha = 0.12f) else IgiSurface2, RoundedCornerShape(2.dp))
            .border(1.dp, if (good) IgiGreen.copy(alpha = 0.5f) else IgiDim.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(if (good) IgiGreen else IgiDim, RoundedCornerShape(50))
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$label $value",
            color = if (good) IgiGreen else IgiDim,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = IgiText) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(key, color = IgiDim, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Text(value, color = valueColor, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
fun EmptyState(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        color = IgiDim,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        textAlign = TextAlign.Center
    )
}

@Composable
fun RowScope.Grow() {
    Spacer(Modifier.weight(1f))
}
