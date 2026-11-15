package dev.scanx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours sampled from the logo: warm paper, charcoal ink, electric blue. */
object Pal {
    val Paper = Color(0xFFF2F1EF)
    val Sheet = Color(0xFFFFFFFF)
    val Ink = Color(0xFF262625)
    val InkRaised = Color(0xFF3A3A39)
    val Graphite = Color(0xFF6B6B69)
    val Mist = Color(0xFFE1E0DC)
    val Blue = Color(0xFF0B6CE8)
    val Cyan = Color(0xFF1BD5FD)
    val RedOnDark = Color(0xFFFF7A7E)
    val Red = Color(0xFFD13438)
}

@OptIn(ExperimentalTextApi::class)
val Sans = FontFamily(
    listOf(400, 500, 600, 800).map { w ->
        Font(R.font.inter_tight, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

@Composable
fun T(
    s: String, size: Int, weight: Int = 500, color: Color = Pal.Ink, modifier: Modifier = Modifier,
    track: Float = 0f, align: TextAlign? = null,
) = Text(
    s, modifier, color, size.sp, fontFamily = Sans, fontWeight = FontWeight(weight),
    letterSpacing = track.sp, textAlign = align, lineHeight = (size * 1.35f).sp,
)

@Composable
fun Pill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, bg: Color, fg: Color, enabled: Boolean = true) =
    Box(
        modifier.heightIn(min = 48.dp).clip(CircleShape)
            .background(bg.copy(alpha = bg.alpha * if (enabled) 1f else .4f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) { T(label, 15, 600, fg) }

/** Three-way filter switch. [selected] is null when pages disagree, so nothing is highlighted. */
@Composable
fun FilterSwitch(selected: Filter?, onSelect: (Filter) -> Unit, dark: Boolean) {
    val track = if (dark) Pal.InkRaised else Pal.Mist
    val on = if (dark) Pal.Paper else Pal.Ink
    val onText = if (dark) Pal.Ink else Pal.Paper
    val off = if (dark) Pal.Paper else Pal.Ink
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(track).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Filter.entries.forEach { f ->
            val sel = f == selected
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (sel) on else Color.Transparent)
                    .selectable(sel, role = Role.RadioButton, onClick = { onSelect(f) })
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { T(f.label, 14, 600, if (sel) onText else off) }
        }
    }
}

/** A sheet of paper with the logo's dog-eared top-right corner. */
val SheetShape = GenericShape { s, _ ->
    val r = s.width * .06f
    val c = s.width * .22f
    val w = s.width
    val h = s.height
    moveTo(r, 0f); lineTo(w - c, 0f); lineTo(w, c); lineTo(w, h - r)
    quadraticBezierTo(w, h, w - r, h); lineTo(r, h)
    quadraticBezierTo(0f, h, 0f, h - r); lineTo(0f, r)
    quadraticBezierTo(0f, 0f, r, 0f); close()
}
