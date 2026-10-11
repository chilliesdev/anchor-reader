package com.anchorreader.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke

object Palette {
    val Canvas = Color(0xFFF3F6F7)
    val Surface = Color.White
    val Soft = Color(0xFFE8EFF0)
    val Ink = Color(0xFF14262D)
    val Muted = Color(0xFF52666D)
    val Line = Color(0xFFD4DFE1)
    val Teal = Color(0xFF0E5962)
    val Deep = Color(0xFF093E47)
    val TealSoft = Color(0xFFD7E9E9)
    val Accent = Color(0xFFC4D84E)
    val PlayerInk = Color(0xFFF5F8F8)
    val PlayerMuted = Color(0xFFD6E0E1)
    val Success = Color(0xFFE5F0D7)
    val SuccessInk = Color(0xFF31511C)
    val Error = Color(0xFFF8E7E4)
    val ErrorInk = Color(0xFF7B3027)
}

@Composable
fun AnchorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Teal, onPrimary = Color.White,
            primaryContainer = Palette.TealSoft, onPrimaryContainer = Palette.Deep,
            secondary = Palette.Deep, secondaryContainer = Palette.TealSoft,
            background = Palette.Canvas, onBackground = Palette.Ink,
            surface = Palette.Surface, onSurface = Palette.Ink,
            surfaceVariant = Palette.Soft, onSurfaceVariant = Palette.Muted,
            outline = Palette.Line, error = Palette.ErrorInk,
            errorContainer = Palette.Error, onErrorContainer = Palette.ErrorInk,
        ),
        typography = Typography(
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 34.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 33.sp),
            headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 25.sp, lineHeight = 30.sp),
            titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 27.sp),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        ), content = content,
    )
}

@Composable
fun Heading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.headlineLarge, color = Palette.Ink)
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Palette.Teal)) {
        Text(text, Modifier.padding(vertical = 3.dp))
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, Palette.Line),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Palette.Surface, contentColor = Palette.Ink)) {
        Text(text, Modifier.padding(vertical = 3.dp))
    }
}
