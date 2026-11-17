package io.embodit.fourdrop

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.Placement
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import io.embodit.fourdrop.ui.Containers
import io.embodit.fourdrop.ui.board.BoardScreen
import io.embodit.fourdrop.ui.control.ControlWindow
import io.embodit.fourdrop.ui.theme.FourDropTheme

/**
 * Two surfaces in Shared Space (design windowModel volume_plus_control_window): the rack volume (default container,
 * 0.90 x 0.76 x 0.40 m from the manifest) and the control window (Planar 560 x 440 dp) to its right. Every open (first
 * open, the S10 pinch, [重开控制窗]) uses Placement.right(volume) (profile D44 correction: Shared Space never promises
 * "in front of you"; the user moves the panel with the system handle).
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DefaultWindowContainer {
            FourDropTheme { BoardScreen() }
        }
        WindowContainer(
            Containers.CONTROL,
            form = Form.Planar,
            defaultSize = WindowContainerSize(width = 560.dp, height = 440.dp),
            placement = { containers.firstOrNull { it.id == Containers.BOARD }?.let { Placement.right(it) } ?: Placement.none() },
        ) {
            FourDropTheme {
                Box(Modifier.windowConstraints(minWidth = 520.dp, minHeight = 400.dp, maxWidth = 900.dp, maxHeight = 640.dp)) { ControlWindow() }
            }
        }
    }
