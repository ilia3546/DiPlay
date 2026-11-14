package com.shilapi.xcertplay

import android.content.Intent
import android.net.Uri
import com.shilapi.xcertplay.network.RootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class RootActivityStartTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private fun player() = Intent(app, OwnTaskCarPlayVideoActivity::class.java)
        .putExtra(CarPlayVideo.EXTRA_GENERATION, 7L).putExtra(CarPlayVideo.EXTRA_LAUNCH, 3L)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    @Test fun playerStartCarriesDisplayFlagsAndLaunchTicket() {
        assertEquals(
            "am start --display 2 -f 0x18000000 -n ${app.packageName}/com.shilapi.xcertplay.OwnTaskCarPlayVideoActivity" +
                " --el video_generation 7 --el video_launch 3",
            RootActivityStart.command(player(), 2),
        )
    }

    @Test fun anythingBeyondThePlayerTicketStaysWithTheApp() {
        assertNull(RootActivityStart.command(player().putExtra("other", "x"), 2))
        assertNull(RootActivityStart.command(player().setData(Uri.parse("https://example.com/v")), 2))
        assertNull(RootActivityStart.command(player(), -1))
        assertNull(RootActivityStart.command(Intent(), 2))
    }

    @Test fun onlyACleanAmAnswerCountsAsStarted() {
        assertTrue(RootActivityStart.start("am") { RootShell.Result.Completed(0, "Starting: Intent { }") })
        assertFalse(RootActivityStart.start("am") { RootShell.Result.Completed(0, "Error: Activity not started") })
        assertFalse(RootActivityStart.start("am") { RootShell.Result.Completed(1, "su: permission denied") })
        assertFalse(RootActivityStart.start("am") { RootShell.Result.Unavailable("su unavailable") })
    }
}
