package com.shilapi.xcertplay

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DiPlaySessionServiceTaskRemovedTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun closingTheVideoPlayerTaskKeepsTheSession() {
        assertFalse(DiPlaySessionService.endsSession(Intent(app, OwnTaskCarPlayVideoActivity::class.java)))
    }

    @Test fun removingTheCarPlayScreenTaskEndsTheSession() {
        assertTrue(DiPlaySessionService.endsSession(Intent(app, CarPlayHostActivity::class.java)))
        assertTrue(DiPlaySessionService.endsSession(Intent(app, CarPlayVideoActivity::class.java)))
        assertTrue(DiPlaySessionService.endsSession(null))
    }
}
