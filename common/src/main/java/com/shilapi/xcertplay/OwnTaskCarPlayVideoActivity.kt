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

    // On another screen the player has the key focus there; keys it does not use still belong to CarPlay.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (super.dispatchKeyEvent(event)) return true
        return event.keyCode != KeyEvent.KEYCODE_BACK && CarPlayScreen.current()?.dispatchKeyEvent(event) == true
    }

    override fun leave() {
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }
}
