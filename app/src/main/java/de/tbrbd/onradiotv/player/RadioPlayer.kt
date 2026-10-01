package de.tbrbd.onradiotv.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/** Thin wrapper around Media3/ExoPlayer for a single audio stream. */
class RadioPlayer(context: Context) {

    private val player = ExoPlayer.Builder(context).build()
    private var currentUrl: String? = null

    fun play(url: String) {
        if (url == currentUrl && player.isPlaying) return
        currentUrl = url
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        player.stop()
        currentUrl = null
    }

    fun release() {
        player.release()
    }
}
