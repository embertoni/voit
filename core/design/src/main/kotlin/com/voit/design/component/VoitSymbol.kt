package com.voit.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.voit.design.theme.VoitTheme

/**
 * The Voit brand symbol: a simple monochrome "sound ring" — a ring with a set of
 * concentric waveform bars radiating from the centre, evoking a voice/sound signature.
 *
 * Drawn purely with strokes in a single [tint] colour so it reads correctly in both the
 * light and dark monochrome themes. No gradients or accent hues.
 */
@Composable
fun VoitSoundRing(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    tint: Color = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
    contentDescription: String? = "Voit",
) {
    val semanticsModifier = if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier
    }
    Canvas(modifier = semanticsModifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val ringStroke = w * 0.055f
        val ringRadius = (minOf(w, h) / 2f) - ringStroke

        // Outer ring.
        drawCircle(
            color = tint,
            radius = ringRadius,
            center = Offset(cx, cy),
            style = Stroke(width = ringStroke),
        )

        // Central waveform: five vertical bars of varying height (sound signature).
        val barStroke = w * 0.07f
        val barSpacing = w * 0.11f
        val heights = floatArrayOf(0.18f, 0.34f, 0.46f, 0.30f, 0.16f)
        val startX = cx - barSpacing * 2
        for (i in heights.indices) {
            val x = startX + barSpacing * i
            val half = h * heights[i]
            drawLine(
                color = tint,
                start = Offset(x, cy - half),
                end = Offset(x, cy + half),
                strokeWidth = barStroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Convenience preview-sized symbol using theme spacing (kept small to avoid extra deps). */
@Composable
fun VoitSoundRingSmall(modifier: Modifier = Modifier, contentDescription: String? = "Voit") {
    VoitSoundRing(
        modifier = modifier,
        size = VoitTheme.spacing.xl,
        contentDescription = contentDescription,
    )
}
