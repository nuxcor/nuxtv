@file:OptIn(androidx.tv.material3.ExperimentalTvMaterial3Api::class)

package com.agoro.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.agoro.tv.ui.theme.NuxColors
import com.agoro.tv.ui.theme.Space

/**
 * The screen-level heading — the headlineSmall/SemiBold treatment Settings and
 * Recordings each used to hand-roll, in one place so they cannot drift.
 */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        color = NuxColors.OnSurface,
        modifier = modifier,
    )
}

/**
 * One titled group of settings: title, an optional explanatory description, and
 * whatever controls belong to it.
 *
 * Rhythm is fixed here rather than per call site — titleSmall title,
 * labelMedium description (the old groups mixed labelSmall and labelMedium at
 * random), Space.s between description and controls. The leading spacer lifts
 * the list's Space.m item gap to Space.l between groups; [divider] adds a soft
 * hairline for the sections that used to fake one with an extra Spacer.
 */
@Composable
fun SettingsGroup(
    title: String,
    description: String? = null,
    modifier: Modifier = Modifier,
    divider: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Spacer(Modifier.height(Space.s))
        if (divider) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(NuxColors.StrokeSoft)
            )
            Spacer(Modifier.height(Space.s))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = NuxColors.OnSurface,
        )
        if (description != null) {
            Spacer(Modifier.height(Space.xs))
            Text(
                description,
                style = MaterialTheme.typography.labelMedium,
                color = NuxColors.OnSurfaceDim,
            )
        }
        if (content != null) {
            Spacer(Modifier.height(Space.s))
            content()
        }
    }
}

/**
 * The commonest settings shape: a [SettingsGroup] whose control is a
 * [SegmentedControl]. [footer] is for the small print that follows the control
 * (connection-count warnings and the like); it supplies its own top spacing.
 */
@Composable
fun SettingsChoiceRow(
    title: String,
    description: String? = null,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = false,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    SettingsGroup(
        title = title,
        description = description,
        modifier = modifier,
        divider = divider,
    ) {
        SegmentedControl(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
        )
        footer?.invoke(this)
    }
}
