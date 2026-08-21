package com.agoro.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.agoro.tv.MainViewModel
import com.agoro.tv.data.EpgProgram

/**
 * The synopsis for the one programme under the cursor.
 *
 * Synopses are 70% of a guide's text and are read one at a time, so they stay
 * in the guide table and the grid's programmes arrive without them. This asks
 * for the one that is actually about to be drawn, and returns null for the
 * frame or two before it lands — a detail pane that fills a beat late, rather
 * than thirteen megabytes of text held for cells that never show it.
 *
 * Programmes that already carry their synopsis (the schedule sheet reads them
 * in bulk for a single channel) are returned unchanged and cost no query.
 */
@Composable
fun rememberProgramDescription(vm: MainViewModel, program: EpgProgram?): String? {
    var text by remember(program?.id) { mutableStateOf(program?.description) }
    LaunchedEffect(program?.id) {
        val id = program?.id ?: return@LaunchedEffect
        if (!program.description.isNullOrBlank()) return@LaunchedEffect
        text = vm.descriptionFor(id)
    }
    return text
}
