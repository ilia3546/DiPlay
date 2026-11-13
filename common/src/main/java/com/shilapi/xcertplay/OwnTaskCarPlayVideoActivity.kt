package com.shilapi.xcertplay

import android.content.Intent
import android.view.KeyEvent

/**
 * The car's video player in a task of its own (taskAffinity `.video`). Firmware that moves the top task between
 * screens with a gesture then moves only the player, and CarPlay stays where it is. [CarPlayVideo] opens it instead
 * of [CarPlayVideoActivity] while "Video on the passenger screen" is on.
 */
class OwnTaskCarPlayVideoActivity : CarPlayVideoActivity() {
    /** Only a start of this same player, such as its move to the other screen, is accepted. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val generation = intent.getLongExtra(CarPlayVideo.EXTRA_GENERATION, -1L)
        val ticket = intent.getLongExtra(CarPlayVideo.EXTRA_LAUNCH, -1L)
        if (generation != this.intent?.getLongExtra(CarPlayVideo.EXTRA_GENERATION, -2L) ||
            ticket != this.intent?.getLongExtra(CarPlayVideo.EXTRA_LAUNCH, -2L)) {
            CarPlayVideo.diagnostic("event=player_new_intent_ignored generation=$generation launch=$ticket")
            return
        }
        CarPlayVideo.onPlayerDisplay(this)
    }

    /**
     * On another screen the player has the key focus there; keys it does not use still belong to CarPlay. Media keys
     * are never passed on: through the media session they drive this video, and CarPlay would send them to the iPhone.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode in MEDIA_SESSION_KEYS || overCarPlay()) return false
        return CarPlayScreen.current()?.dispatchKeyEvent(event) == true
    }

    override fun leave() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    override fun overCarPlay(): Boolean = CarPlayVideo.onCarPlayDisplay(this)

    override val inOwnTask: Boolean get() = true

    private companion object {
        // KeyEvent.isMediaSessionKey, which needs Android 10.
        val MEDIA_SESSION_KEYS = setOf(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_RECORD, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)
    }
}
