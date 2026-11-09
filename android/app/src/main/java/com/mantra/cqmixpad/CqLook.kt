package com.mantra.cqmixpad

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The "CQ" interface (9.11.2026, Marko: "take a screenshot of every screen of the original CQ MixPad and copy it to our app"):
 * our own drawing of the original's layout, colours and navigation. No Allen & Heath logo, icon or image is used; the
 * screenshots we copied from are in docs/original-ui. Everything here is drawn with Compose primitives.
 */
internal val CqBg = Color(0xFF2A2E2E)
internal val CqBar = Color(0xFF333737)
internal val CqPanel = Color(0xFF222726)
internal val CqTeal = Color(0xFF1AAE93)
internal val CqLine = Color(0xFF3E4544)
internal val CqText = Color(0xFFEAECEC)
internal val CqDim = Color(0xFF8B9392)
internal val CqPeq = Color(0xFFE0A21B)
internal val CqRed = Color(0xFF8E2B2B)

/** A rectangular outlined button as in the original (flat, thin border, teal when on). */
@Composable
internal fun CqButton(label: String, modifier: Modifier = Modifier, on: Boolean = false, red: Boolean = false, onClick: () -> Unit) {
    val line = when { red -> Color(0xFFE5534B); on -> CqTeal; else -> CqLine }
    Box(modifier.clip(RoundedCornerShape(3.dp)).background(if (red) CqRed else CqPanel).border(1.5.dp, line, RoundedCornerShape(3.dp))
        .clickable { onClick() }, contentAlignment = Alignment.Center) {
        Text(label, color = if (on) CqTeal else CqText, fontSize = 12.sp, maxLines = 1)
    }
}

/**
 * The original's fader: a slim groove, a flat light cap with a centre line, the scale 10 / 0 / 10 / 20 / 40 / Off on its
 * right, the desk's meter as a thin green column on its left. Relative drag (a touch never jumps it), double tap = 0 dB.
 */
@Composable
internal fun CqFader(db: Double, muted: Boolean, meter: Float?, onDb: (Double) -> Unit, modifier: Modifier = Modifier, wide: Boolean = false) {
    val maxV = Cq.dbToValue(Cq.MAX_DB).toFloat()
    val setDb by rememberUpdatedState(onDb)
    val curDb by rememberUpdatedState(db)
    val pos = (Cq.dbToValue(db) / maxV).coerceIn(0f, 1f)
    Canvas(modifier.pointerInput(Unit) {
        var start = 0f; var acc = 0f
        detectVerticalDragGestures(onDragStart = { start = Cq.dbToValue(curDb) / maxV; acc = 0f }) { _, dy ->
            acc += dy
            setDb(Cq.valueToDb(((start - acc / size.height).coerceIn(0f, 1f) * maxV).toInt()))
        }
    }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { setDb(0.0) }) }) {
        val cx = size.width * (if (wide) 0.42f else 0.4f)
        fun frac(d: Double) = 1f - Cq.dbToValue(d.coerceIn(-85.0, Cq.MAX_DB)) / maxV
        val pad = 14f; val h = size.height - 2 * pad
        fun yOf(f: Float) = pad + h * f
        drawRect(Color(0xFF0E1111), Offset(cx - 2.5f, pad), Size(5f, h))
        if (meter != null && meter > -85f) {
            val t = yOf(frac(meter.toDouble()))
            drawRect(Color(0xFF3FD07A), Offset(cx - 14f, t), Size(5f, pad + h - t))
        }
        val paint = android.graphics.Paint().apply { color = 0xFFB9C0BF.toInt(); textSize = 22f; isAntiAlias = true }
        for ((d, s) in listOf(10.0 to "10", 0.0 to "0", -10.0 to "10", -20.0 to "20", -40.0 to "40", -85.0 to "Off")) {
            val y = yOf(frac(d))
            drawRect(Color(0xFFB9C0BF), Offset(cx + 12f, y - 1f), Size(10f, 2f))
            drawContext.canvas.nativeCanvas.drawText(s, cx + 26f, y + 8f, paint)
        }
        val y = yOf(1f - pos)
        val cw = 46f; val ch = 30f
        drawRoundRect(if (muted) Color(0xFFB86A6A) else Color(0xFFDDE2E1), Offset(cx - cw / 2, y - ch / 2), Size(cw, ch), CornerRadius(5f))
        drawRoundRect(Color(0xFF5E6665), Offset(cx - cw / 2, y - ch / 2), Size(cw, ch), CornerRadius(5f), style = Stroke(2f))
        drawRect(Color(0xFF3A4140), Offset(cx - cw / 2 + 4, y - 1f), Size(cw - 8, 2.5f))
    }
}

/** A knob drawn as a ring with a pointer (the original's gain, send and trim knobs). */
@Composable
internal fun CqKnob(frac: Float, modifier: Modifier = Modifier, color: Color = CqTeal) {
    Canvas(modifier) {
        val r = size.minDimension / 2 - 3f
        val c = Offset(size.width / 2, size.height / 2)
        drawCircle(Color(0xFF141918), r, c)
        drawArc(Color(0xFF3A4140), 135f, 270f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(5f))
        drawArc(color, 135f, 270f * frac.coerceIn(0f, 1f), false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = Stroke(5f))
        val a = Math.toRadians((135 + 270 * frac.coerceIn(0f, 1f)).toDouble())
        drawLine(Color(0xFFDDE2E1), Offset(c.x + (r * 0.35f * Math.cos(a)).toFloat(), c.y + (r * 0.35f * Math.sin(a)).toFloat()),
            Offset(c.x + (r * 0.8f * Math.cos(a)).toFloat(), c.y + (r * 0.8f * Math.sin(a)).toFloat()), 4f)
    }
}
