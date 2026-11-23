package com.aether.tv.ui.common

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aether.tv.core.designsystem.AIcon
import com.aether.tv.core.designsystem.AText
import com.aether.tv.core.designsystem.Aether
import com.aether.tv.core.designsystem.AetherIcons
import com.aether.tv.core.designsystem.GlassTile
import com.aether.tv.core.designsystem.TogglePill
import com.aether.tv.core.designsystem.ValueBar
import com.aether.tv.core.designsystem.glass
import kotlin.math.roundToInt

/**
 * Text entry that behaves well with a remote: navigating over it never pops up the keyboard; pressing OK starts
 * editing (system keyboard), and Done/Back returns focus to the field.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    startEditing: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onSubmit: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(startEditing) }
    // A field on a page that goes into the background stops editing (its page is kept alive, just hidden).
    val pageActive = com.aether.tv.ui.LocalPageActive.current
    LaunchedEffect(pageActive) { if (!pageActive && editing) editing = false }
    var returnFocus by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val shape = Aether.shapes.pill
    if (!editing) {
        GlassTile(onClick = { editing = true }, focusRequester = focusRequester, shape = shape, scaleOnFocus = false,
            modifier = modifier.heightIn(min = 36.dp).semantics { contentDescription = placeholder }) { focused ->
            Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { AIcon(icon, size = 17.dp, tint = Aether.colors.textSecondary); Spacer(Modifier.width(10.dp)) }
                val shown = if (value.isEmpty()) placeholder else if (password) "•".repeat(value.length) else value
                AText(shown, Aether.type.body, color = if (value.isEmpty()) Aether.colors.textTertiary else Aether.colors.textPrimary)
            }
        }
        LaunchedEffect(returnFocus) { if (returnFocus) { runCatching { focusRequester.requestFocus() }; returnFocus = false } }
    } else {
        Box(modifier.heightIn(min = 36.dp).glass(shape, { 1f })) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) { AIcon(icon, size = 17.dp, tint = Aether.colors.textPrimary); Spacer(Modifier.width(10.dp)) }
                Box(Modifier.weight(1f)) {
                if (value.isEmpty()) AText(placeholder, Aether.type.body, color = Aether.colors.textTertiary)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = Aether.type.body.copy(color = Aether.colors.textPrimary),
                    cursorBrush = SolidColor(Aether.colors.accent),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
                    keyboardActions = KeyboardActions(onAny = { editing = false; returnFocus = true; keyboard?.hide(); onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(field)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyUp && e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_BACK) { editing = false; returnFocus = true; true }
                            else if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN && e.type == KeyEventType.KeyDown) { editing = false; returnFocus = true; true }
                            else false
                        }
                        .onFocusChanged { if (!it.isFocused && editing && !it.hasFocus) Unit },
                )
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { field.requestFocus() }; keyboard?.show() }
    }
}

/** A row inside menus and settings lists. */
@Composable
fun OptionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    detail: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    chevron: Boolean = false,
    minHeight: Dp = 44.dp,
    onKey: ((Int) -> Boolean)? = null,
    stateText: String? = null,
) {
    GlassTile(
        onClick = { if (enabled) onClick() },
        focusRequester = focusRequester,
        scaleOnFocus = false,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .then(if (onKey != null) Modifier.onPreviewKeyEvent { e -> e.type == KeyEventType.KeyDown && onKey(e.nativeKeyEvent.keyCode) } else Modifier)
            .semantics { contentDescription = label; if (stateText != null) stateDescription = stateText },
    ) { focused ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                AIcon(icon, size = 19.dp, tint = if (enabled) Aether.colors.textPrimary.copy(alpha = if (focused) 1f else 0.85f) else Aether.colors.textTertiary)
                Spacer(Modifier.width(13.dp))
            }
            Column(Modifier.weight(1f)) {
                AText(label, Aether.type.bodyStrong, color = if (enabled) Aether.colors.textPrimary else Aether.colors.textTertiary)
                if (detail != null) AText(detail, Aether.type.caption, color = Aether.colors.textSecondary, maxLines = 3, modifier = Modifier.padding(top = 1.dp))
            }
            if (trailing != null) { Spacer(Modifier.width(10.dp)); trailing() }
            if (chevron) { Spacer(Modifier.width(8.dp)); AIcon(AetherIcons.ChevronRight, size = 16.dp, tint = Aether.colors.textSecondary) }
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, detail: String? = null, icon: ImageVector? = null, enabled: Boolean = true, focusRequester: FocusRequester? = null) =
    OptionRow(label, { onChange(!checked) }, icon = icon, detail = detail, enabled = enabled, trailing = { TogglePill(checked) }, stateText = if (checked) "On" else "Off", focusRequester = focusRequester)

/** Choice row: OK opens a picker; ◀ ▶ cycle the value in place. */
@Composable
fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onChange: (T) -> Unit,
    onOpenPicker: (() -> Unit)? = null,
    detail: String? = null,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    // Settings are saved asynchronously: quick presses build on the value just chosen, not the last one saved.
    var pending by remember { mutableStateOf<T?>(null) }
    LaunchedEffect(selected) { pending = null }
    val shown = pending ?: selected
    val idx = options.indexOf(shown).coerceAtLeast(0)
    fun pick(v: T) { pending = v; onChange(v) }
    OptionRow(
        label, onClick = { onOpenPicker?.invoke() ?: pick(options[(idx + 1) % options.size]) }, icon = icon, detail = detail, focusRequester = focusRequester,
        stateText = optionLabel(shown),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AIcon(AetherIcons.ChevronLeft, size = 13.dp, tint = Aether.colors.textTertiary)
                AText(optionLabel(shown), Aether.type.body, color = Aether.colors.textSecondary, modifier = Modifier.padding(horizontal = 6.dp))
                AIcon(AetherIcons.ChevronRight, size = 13.dp, tint = Aether.colors.textTertiary)
            }
        },
        onKey = { code ->
            when (code) {
                NativeKeyEvent.KEYCODE_DPAD_LEFT -> { pick(options[(idx - 1 + options.size) % options.size]); true }
                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { pick(options[(idx + 1) % options.size]); true }
                else -> false
            }
        },
    )
}

/** Slider row adjusted with ◀ ▶ (no touch needed). */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onChange: (Float) -> Unit,
    format: (Float) -> String = { "${(it * 100).roundToInt()}%" },
    detail: String? = null,
    icon: ImageVector? = null,
    focusRequester: FocusRequester? = null,
) {
    fun clamp(v: Float) = ((v / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    // Quick presses build on the value just set, not on the last value saved (settings are saved asynchronously).
    var pending by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(value) { pending = null }
    val shown = pending ?: value
    val fraction = (shown - range.start) / (range.endInclusive - range.start)
    fun set(v: Float) { pending = v; onChange(v) }
    OptionRow(
        label, onClick = { }, icon = icon, detail = detail, stateText = format(shown), focusRequester = focusRequester,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ValueBar(fraction)
                AText(format(shown), Aether.type.body, color = Aether.colors.textSecondary, modifier = Modifier.widthIn(min = 52.dp).padding(start = 10.dp))
            }
        },
        onKey = { code ->
            when (code) {
                NativeKeyEvent.KEYCODE_DPAD_LEFT -> { set(clamp(shown - step)); true }
                NativeKeyEvent.KEYCODE_DPAD_RIGHT -> { set(clamp(shown + step)); true }
                else -> false
            }
        },
    )
}

@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    AText(text.uppercase(), Aether.type.caption, color = Aether.colors.textTertiary, modifier = modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
fun VSpace(h: Dp) = Spacer(Modifier.height(h))
