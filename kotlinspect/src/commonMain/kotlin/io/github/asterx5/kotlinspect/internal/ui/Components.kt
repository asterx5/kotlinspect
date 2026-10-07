package io.github.asterx5.kotlinspect.internal.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun KsText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        maxLines = maxLines,
        overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis,
    )
}

@Composable
internal fun KsText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier, softWrap: Boolean = true) {
    BasicText(text = text, modifier = modifier, style = style, softWrap = softWrap)
}

/** Small rounded label with a tinted background, e.g. method badges and status pills. */
@Composable
internal fun Tag(text: String, color: Color, modifier: Modifier = Modifier, minWidth: Dp = 0.dp) {
    Box(
        modifier
            .widthIn(min = minWidth)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        KsText(text, Ks.type.monoSmall.copy(color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), maxLines = 1)
    }
}

@Composable
internal fun MethodBadge(method: String, modifier: Modifier = Modifier) {
    Tag(method.take(6), Ks.colors.methodColor(method), modifier, minWidth = 54.dp)
}

/** Selectable filter chip with an optional count. */
@Composable
internal fun Chip(label: String, selected: Boolean, count: Int? = null, tint: Color? = null, onClick: () -> Unit) {
    val c = Ks.colors
    val accent = tint ?: c.accent
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent else c.surfaceAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KsText(label, Ks.type.caption, color = if (selected) c.onAccent else c.text, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            KsText(
                count.toString(),
                Ks.type.monoSmall,
                color = if (selected) c.onAccent.copy(alpha = 0.75f) else c.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** Text button used in top bars and toolbars. */
@Composable
internal fun Action(label: String, modifier: Modifier = Modifier, emphasized: Boolean = false, onClick: () -> Unit) {
    val c = Ks.colors
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (emphasized) Modifier.background(c.accent.copy(alpha = 0.14f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        KsText(label, Ks.type.bodyStrong, color = c.accent, maxLines = 1)
    }
}

@Composable
internal fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = Ks.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceAlt)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KsText("⌕", Ks.type.body, color = c.textMuted)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) KsText(placeholder, Ks.type.body, color = c.textFaint, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = Ks.type.body,
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            KsText("✕", Ks.type.caption, color = c.textMuted, modifier = Modifier.clip(CircleShape).clickable { onValueChange("") }.padding(4.dp))
        }
    }
}

/** Segmented tabs with an accent underline. */
@Composable
internal fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Ks.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surfaceAlt)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, label ->
            val isSelected = index == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (isSelected) c.surface else Color.Transparent)
                    .then(if (isSelected) Modifier.border(1.dp, c.line, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                KsText(label, Ks.type.bodyStrong, color = if (isSelected) c.text else c.textMuted, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Ks.colors.line))
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        KsText(text.uppercase(), Ks.type.label, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Rotating arc used for in-flight indicators. Cheaper than a Material progress indicator. */
@Composable
internal fun Spinner(color: Color, size: Dp, stroke: Dp = 2.dp, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
    )
    Canvas(modifier.size(size).graphicsLayer { rotationZ = angle }) {
        drawArc(color, startAngle = 0f, sweepAngle = 270f, useCenter = false, style = Stroke(stroke.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
internal fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}
