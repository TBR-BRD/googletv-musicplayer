package de.tbrbd.onradiotv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import de.tbrbd.onradiotv.ui.TvScreen
import de.tbrbd.onradiotv.ui.TvViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: TvViewModel by viewModels()

    // There's no "TV was turned off" callback - ACTION_SCREEN_OFF (the
    // display actually going dark, i.e. standby) is the closest reliable
    // signal, and unlike onStop()/onPause() it doesn't also fire just from
    // switching to another app while the screen stays on, which should keep
    // playing like a real radio would.
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewModel.stopPlayback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        setContent {
            val state by viewModel.state.collectAsState()
            TvScreen(
                state = state,
                onSelectStation = viewModel::selectStation,
                onToggleFavorite = viewModel::toggleFavorite,
                lastStationForGroup = viewModel::lastStationForGroup,
                onSetWeatherLocation = viewModel::setWeatherLocationName,
                onRefreshOutputs = viewModel::refreshUpnpRenderers,
                onSelectOutput = viewModel::selectOutput,
                onAdjustOutputVolume = viewModel::adjustActiveOutputVolume,
            )
        }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOffReceiver)
        super.onDestroy()
    }

    // The remote's physical volume keys were tried for controlling the
    // active UPnP/Sonos output (both via dispatchKeyEvent() and a
    // MediaSession RemoteVolumeProvider - the mechanism Cast-style apps
    // use), but on this TV they never reach Android as a KeyEvent at all
    // (confirmed via logging - D-pad keys arrive fine, volume keys don't),
    // meaning the TV's firmware handles them itself (likely HDMI-CEC
    // straight to its amp) before the OS input pipeline ever sees them. No
    // app-level API can intercept that, so volume for a WLAN-Lautsprecher
    // output is controlled on-screen instead (◀ ▶ on its row in the
    // "Ausgabe" picker - see OutputRow in TvScreen.kt).
}
