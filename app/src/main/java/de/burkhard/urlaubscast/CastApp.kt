package de.burkhard.urlaubscast

import android.app.Application
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

class CastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Store.init(this)
        CastControl.init(this)
        Slideshow.init(this)
    }

    companion object {
        lateinit var app: CastApp
            private set
    }
}

/** Wird vom Cast-Framework über das Manifest gefunden. Empfänger: der eingebaute „Default Media Receiver“. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: android.content.Context): CastOptions {
        // Eigene Benachrichtigung (KeepAliveService) statt der Standard-Medienbenachrichtigung
        val media = CastMediaOptions.Builder()
            .setNotificationOptions(null)
            .build()
        return CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setCastMediaOptions(media)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: android.content.Context): List<SessionProvider>? = null
}

class UserError(msg: String) : Exception(msg)
