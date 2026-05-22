package com.playerbrowser.app.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * Wired into the manifest via `OPTIONS_PROVIDER_CLASS_NAME` meta-data. The
 * Cast framework reflectively instantiates this on first use to discover the
 * receiver application id and any custom session providers.
 *
 * We target the Default Media Receiver so any standard HTML5 / HLS / MP4
 * stream we sniff from the WebView works without a custom Cast app id.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> =
        emptyList()
}
