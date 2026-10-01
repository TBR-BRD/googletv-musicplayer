package de.tbrbd.onradiotv

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            TvScreen(
                state = state,
                onSelectStation = viewModel::selectStation,
                onToggleFavorite = viewModel::toggleFavorite,
                lastStationForGroup = viewModel::lastStationForGroup,
            )
        }
    }
}
