package de.tbrbd.onradiotv.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Required glue for the Cast SDK (referenced from AndroidManifest.xml's
 * OPTIONS_PROVIDER_CLASS meta-data) - this app casts to the stock default
 * media receiver (plain audio/video playback, no custom receiver app of our
 * own to register or host), so there's nothing else to configure here. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
