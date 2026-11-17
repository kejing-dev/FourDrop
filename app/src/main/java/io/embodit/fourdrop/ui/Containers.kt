package io.embodit.fourdrop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ids of the two window containers. The rack volume is the default container (manifest). */
object Containers {
    const val BOARD = "FourDropVolume"
    const val CONTROL = "FourDropControl"
    const val CONTROL_TAG = "control"
}

/** The three pages of the control window (design panels.control: 对局 / 规则 / 设置). */
enum class Page(val stateId: String) { PLAY("S03"), RULES("S12"), SETTINGS("S11") }

/** Window-only overlays: the opponent (S02) and language pickers, the exit confirmation (S13 exit). */
enum class Dialog { OPPONENT, LANGUAGE, EXIT }

/** Window-page state shared process-wide so debug commands and the game flow can switch pages. */
object Ui {
    var page by mutableStateOf(Page.PLAY)
    var dialog by mutableStateOf<Dialog?>(null)
}
