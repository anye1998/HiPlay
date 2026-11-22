package com.shilapi.xcertplay.hicar

import android.content.Context
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.io.Closeable

/**
 * A media-button session, so steering-wheel keys reach the phone while the receiver is not visible.
 *
 * Ported from `GGBondLab/OpenHC` (`platform/.../media/MediaKeySession.kt`, GPL-3.0-only).
 *
 * The system routes media buttons to whichever app most recently played media, so an app that only
 * handles key events in its own window stops receiving the wheel the moment the driver switches to
 * the car's own screen. An active [MediaSession] with a playing state and the transport actions
 * declared keeps that routing pointed at us.
 *
 * Deliberately does not take audio focus and makes no sound: the receiver's audio sink already holds
 * focus for the phone's music, and whether a session exists at all is the caller's decision (it
 * should exist exactly while we hold that focus).
 */
class HiCarMediaKeySession(
    context: Context,
    tag: String,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        /** One media button event; down and up both arrive. True means it was consumed. */
        fun onMediaKey(event: KeyEvent): Boolean

        /** One playback command from the session (car UI, headset, voice assistant). */
        fun onCommand(command: HiCarMediaKeyForwarder.Command)
    }

    private val session = MediaSession(context.applicationContext ?: context, tag)
    private var closed = false

    init {
        session.setCallback(Callback(), Handler(Looper.getMainLooper()))
        // From Android 8 these two flags always apply and the constants are deprecated; older
        // releases need them declared or the buttons never arrive.
        @Suppress("DEPRECATION")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(ACTIONS)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        session.isActive = true
    }

    override fun close() {
        if (closed) return
        closed = true
        session.isActive = false
        session.release()
    }

    private inner class Callback : MediaSession.Callback() {
        override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
            if (closed) return false
            val event = keyEventOf(mediaButtonIntent) ?: return super.onMediaButtonEvent(mediaButtonIntent)
            return listener.onMediaKey(event) || super.onMediaButtonEvent(mediaButtonIntent)
        }

        override fun onPlay() = command(HiCarMediaKeyForwarder.Command.PLAY)

        override fun onPause() = command(HiCarMediaKeyForwarder.Command.PAUSE)

        override fun onSkipToNext() = command(HiCarMediaKeyForwarder.Command.NEXT)

        override fun onSkipToPrevious() = command(HiCarMediaKeyForwarder.Command.PREVIOUS)

        override fun onStop() = command(HiCarMediaKeyForwarder.Command.STOP)

        private fun command(command: HiCarMediaKeyForwarder.Command) {
            if (!closed) listener.onCommand(command)
        }
    }

    private companion object {
        const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP

        fun keyEventOf(intent: Intent): KeyEvent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }
    }
}
