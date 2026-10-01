package de.tbrbd.onradiotv.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

/** Thin wrapper around Media3/ExoPlayer for a single audio stream. */
class RadioPlayer(context: Context) {

    private val player = ExoPlayer.Builder(context).build()
    private var currentUrl: String? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build(),
        )
        .setOnAudioFocusChangeListener { focusChange ->
            // Covers both a genuinely different app taking over and the TV
            // standby/wake case where a leftover instance of this same app
            // (its process frozen rather than killed) is still holding a
            // stream open when a fresh instance starts and requests focus -
            // that fresh request sends this listener AUDIOFOCUS_LOSS, so the
            // stale instance stops instead of playing on top of the new one.
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> stop()
                else -> Unit
            }
        }
        .build()

    fun play(url: String) {
        if (url == currentUrl && player.isPlaying) return
        currentUrl = url
        audioManager.requestAudioFocus(focusRequest)
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    fun stop() {
        player.stop()
        currentUrl = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    fun release() {
        stop()
        player.release()
    }
}
