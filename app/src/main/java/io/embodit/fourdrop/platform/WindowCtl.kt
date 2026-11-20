package io.embodit.fourdrop.platform

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pico.spatial.ui.platform.containers.SpatialNavigator
import io.embodit.fourdrop.ui.Containers

/**
 * Opens / reopens the control window (native-window-move.md §3.5). Profile D9 / D10 (2026-11-19, gate head-gated-ui):
 * openWindowContainer is called straight away, never held back until the head is level; the window is moved with the
 * system handle.
 *
 * Profile D44 (correction): a Shared Space volume app does not promise "in front of you". Settings [重开控制窗] closes
 * the window, waits for ON_DESTROY and opens it again beside the box (Placement.right), like a pinch in S10; the user
 * moves it with the system handle. Exit closes both containers before the tasks are removed (D44).
 */
class WindowCtl {
    var panelState by mutableStateOf("never")
        private set
    val panelOpen: Boolean get() = panelState != "never" && panelState != "ON_DESTROY"

    private var navigator: SpatialNavigator? = null
    /** The control window's own navigator: the only one left when the system [×] closed the box volume (S10b). */
    private var panelNavigator: SpatialNavigator? = null
    var volumeState by mutableStateOf("ON_CREATE")
        private set
    val volumeOpen: Boolean get() = volumeState != "ON_DESTROY"
    private var reopenPending = false
    private val main = Handler(Looper.getMainLooper())

    /** The board screen hands over its navigator (the window's own composition can't reopen itself). */
    fun attach(nav: SpatialNavigator) { navigator = nav }

    fun detach() {
        navigator = null
    }

    /** Open the window now. */
    fun request(why: String) {
        Log.i(TAG, "WINDOW ask why=$why state=$panelState")
        openNow(why)
    }

    fun attachPanel(nav: SpatialNavigator) { panelNavigator = nav }

    /** Settings [重开控制窗]: close and reopen beside the box (Placement.right). */
    fun reopen() {
        if (!panelOpen) request("reopen")
        else { reopenPending = true; Log.i(TAG, "WINDOW reopen close (beside the box)"); runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }
    }

    private var volumeReopenPending = false

    fun onVolumeLifecycle(event: String) {
        volumeState = event
        Log.i(TAG, "VOLUME lifecycle $event")
        if (event == "ON_CREATE" && volumeReopenPending) {
            // the system brings the box back where the panel stands; move the panel beside it again (close, Placement.right)
            volumeReopenPending = false
            main.postDelayed({
                if (panelOpen) { reopenPending = true; Log.i(TAG, "WINDOW move beside the returned box"); runCatching { (navigator ?: panelNavigator)?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }
            }, 800)
        }
    }

    /** S10b [把木盒放回来]: ask the system to open the default container again from the control window. */
    fun reopenVolume(): Boolean {
        val nav = panelNavigator ?: navigator ?: return false
        volumeReopenPending = true
        val ok = runCatching { nav.openWindowContainer(Containers.BOARD, "box", android.os.Bundle()) }
            .onFailure { Log.w(TAG, "VOLUME reopen failed: $it") }.isSuccess
        Log.i(TAG, "VOLUME reopen requested ok=$ok")
        return ok
    }

    fun onPanelLifecycle(event: String) {
        panelState = event
        Log.i(TAG, "WINDOW lifecycle $event")
        if (reopenPending && event == "ON_DESTROY") { reopenPending = false; request("reopen") }
    }

    /** Debug: the system [×] on the control window (the seat tag then offers "open panel"). */
    fun closeControlOnly() { runCatching { navigator?.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) } }

    /** Debug: the system [×] on the box volume (S10b). */
    fun closeVolumeOnly() { runCatching { navigator?.closeWindowContainer() }.onFailure { Log.w(TAG, "VOLUME close: $it") } }

    /** Exit (D26 / D44): close the control window and the board volume itself. */
    fun closeAll() {
        val nav = navigator ?: panelNavigator ?: return
        runCatching { nav.closeWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.w(TAG, "EXIT close control: $it") }
        runCatching { navigator?.closeWindowContainer() }.onFailure { Log.w(TAG, "EXIT close board: $it") }
        Log.i(TAG, "EXIT closed all window containers")
    }

    private fun openNow(why: String) {
        Log.i(TAG, "WINDOW open why=$why")
        runCatching { (navigator ?: panelNavigator)?.openWindowContainer(Containers.CONTROL, Containers.CONTROL_TAG) }.onFailure { Log.e(TAG, "WINDOW open failed", it) }
    }
}
