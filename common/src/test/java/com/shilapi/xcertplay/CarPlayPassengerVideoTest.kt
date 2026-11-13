package com.shilapi.xcertplay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.airplay.VideoInCar.VideoScreens
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Video on a second screen the driver cannot watch, and the driver's screen blocked outside P. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayPassengerVideoTest {
    private class Screens : PassengerScreens {
        var enabled = true
        var identity: Int? = PASSENGER
        var on = true
        val shown = IdentityHashMap<Activity, Int>()
        override fun enabled(context: Context) = enabled
        override fun identity(context: Context) = identity.takeIf { enabled }
        override fun available(context: Context) = identity(context)?.takeIf { on }
        override fun displayOf(activity: Activity): Int = shown[activity] ?: DRIVER
    }

    private class Launch(val intent: Intent, val options: Bundle?) {
        val display: Int? get() = options?.getInt("android.activity.launchDisplayId", -1)?.takeIf { it >= 0 }
    }

    private lateinit var host: CarPlayHostActivity
    private val screens = Screens()
    private val launches = mutableListOf<Launch>()
    private val sent = CopyOnWriteArrayList<Map<String, Any?>>()
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val rates = IdentityHashMap<CarPlayVideoActivity, Boolean>()
    private val defaultStart = CarPlayVideo.startPlayer

    @Before fun setUp() {
        CarPlayVideo.activity = null
        CarPlayVideo.onVideoSessionEnded()
        idle()
        val controller = mock(CarPlayController::class.java)
        doAnswer { sent += it.getArgument<Map<String, Any?>>(1); true }.`when`(controller).sendVideoMessage(anyLong(), anyMap())
        doAnswer { diagnostics += it.getArgument<String>(0); Unit }.`when`(controller).recordVideoDiagnostic(anyString())
        CarPlayVideo.attach(RuntimeEnvironment.getApplication(), controller)
        CarPlayVideo.passenger = screens
        CarPlayVideo.startPlayer = { _, intent, options -> launches += Launch(intent, options) }
        host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference(host))
        park()
    }

    @After fun tearDown() {
        CarPlayVideo.activity = null
        CarPlayVideo.onVideoSessionEnded()
        idle()
        CarPlayVideo.passenger = PassengerVideoScreen
        CarPlayVideo.startPlayer = defaultStart
        ReflectionHelpers.setField(CarPlayVideo, "blocked", false)
        ReflectionHelpers.setField(CarPlayVideo, "recreatedFrom", null)
        VideoInCar.allowed = false
        VideoInCar.screens = VideoScreens()
        ReflectionHelpers.setField(CarPlayScreen, "open", null)
    }

    @Test fun inPANewVideoOpensOnTheDriversScreenInATaskOfItsOwn() {
        play()
        val launch = launches.single()
        assertEquals(OwnTaskCarPlayVideoActivity::class.java.name, launch.intent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK,
            launch.intent.flags and (Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        assertNull(launch.options)
        assertTrue(CarPlayVideo.playerOnScreen)
        assertNull(shadowOf(host).nextStartedActivity)
    }

    @Test fun withTheOptionOffTheVideoOpensOverCarPlayAsBefore() {
        screens.enabled = false
        play()
        assertTrue(launches.isEmpty())
        val started = shadowOf(host).nextStartedActivity
        assertEquals(CarPlayVideoActivity::class.java.name, started.component?.className)
        assertEquals(0, started.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test fun outsidePANewVideoOpensOnThePassengerScreenAndPlaysThere() {
        drive()
        play()
        val launch = launches.single()
        assertEquals(PASSENGER, launch.display)
        assertFalse(CarPlayVideo.playerOnScreen)
        val screen = open(launch, PASSENGER)
        assertFalse(CarPlayVideo.blocked)
        assertTrue(CarPlayVideo.playing)
        assertEquals(true, rates[screen])
        assertFalse(CarPlayVideo.playerOnScreen)
        assertEquals(screen.getString(R.string.video_close), backText(screen))
    }

    @Test fun leavingPBlocksThePlayerOnTheDriversScreenAndKeepsItsLoads() {
        play()
        val screen = open(launches.single(), DRIVER)
        assertEquals(true, rates[screen])
        val generation = CarPlayVideo.playbackGeneration
        val launch = ReflectionHelpers.getField<Long?>(CarPlayVideo, "launchId")
        clearSent()
        drive()
        assertTrue(CarPlayVideo.blocked)
        assertFalse(CarPlayVideo.playing)
        assertEquals(false, rates[screen])
        assertEquals(listOf("paused"), names())
        assertEquals(generation, CarPlayVideo.playbackGeneration)
        assertEquals(launch, ReflectionHelpers.getField<Long?>(CarPlayVideo, "launchId"))
        assertFalse(screen.playbackReleased)
        assertSame(screen, CarPlayVideo.activity)
        assertEquals(View.VISIBLE, cover(screen).visibility)
        assertEquals(View.INVISIBLE, ReflectionHelpers.getField<View>(screen, "playerView").visibility)
        assertEquals(View.GONE, ReflectionHelpers.getField<View>(screen, "transport").visibility)
        assertEquals(View.VISIBLE, ReflectionHelpers.getField<View>(screen, "controls").visibility)
        assertEquals(screen.getString(R.string.video_move_to_passenger_screen), moveText(screen))
        drive()
        assertEquals(listOf("paused"), names())
    }

    @Test fun everyWayToPlayStaysPausedWhileBlockedAndTellsTheIphone() {
        play()
        val screen = open(launches.single(), DRIVER)
        drive()
        val plays = listOf<Pair<String, () -> Unit>>(
            "car" to { CarPlayVideo.setPlaying(true) },
            "wheel" to { assertTrue(CarPlayVideo.onMediaKey(CarPlayMediaButton.PLAY_PAUSE)) },
            "iphone" to { rate() },
            "item and play" to { CarPlayVideo.onVideoMessage(3, mapOf("type" to "setRate", "rate" to 1.0)); queue("next") },
            "load" to { screen.load() },
            "seek" to { message(mapOf("type" to "seek", "time" to 12.0)) },
            "same display" to { CarPlayVideo.onPlayerDisplay(screen); idle() },
        )
        for ((name, start) in plays) {
            clearSent()
            start()
            idle()
            assertFalse(name, CarPlayVideo.playing)
            assertEquals(name, false, rates[screen])
            assertFalse(name, names().contains("playing"))
            if (name in setOf("car", "wheel", "iphone", "item and play")) assertEquals(name, listOf("paused"), names())
            assertTrue(name, CarPlayVideo.blocked)
        }
        assertTrue(diagnostics.any { it.startsWith("event=play_blocked") && it.contains("source=set_rate") })
    }

    @Test fun aPlayRequestWhileOpeningAPendingItemStaysBlocked() {
        play()
        val screen = open(launches.single(), DRIVER)
        drive()
        CarPlayVideo.onVideoUiRequested()
        CarPlayVideo.onVideoMessage(5, mapOf("type" to "setRate", "rate" to 1.0))
        CarPlayVideo.onVideoMessage(5, mapOf("type" to "insertPlayQueueItem", "item" to mapOf(
            "uuid" to "other", "Content-Location" to "https://example.com/other.mp4")))
        idle()
        assertFalse(CarPlayVideo.playing)
        assertEquals(false, rates[screen])
        assertTrue(CarPlayVideo.blocked)
    }

    @Test fun movingTheBlockedPlayerToThePassengerScreenResumesIt() {
        play()
        val screen = open(launches.single(), DRIVER)
        drive()
        clearSent()
        screens.shown[screen] = PASSENGER
        CarPlayVideo.onPlayerDisplay(screen)
        idle()
        assertFalse(CarPlayVideo.blocked)
        assertTrue(CarPlayVideo.playing)
        assertEquals(true, rates[screen])
        assertEquals(listOf("playing"), names())
        assertEquals(View.GONE, cover(screen).visibility)
        assertFalse(CarPlayVideo.playerOnScreen)
    }

    @Test fun backInPTheUnblockedPlayerOnTheDriversScreenStaysPaused() {
        play()
        val screen = open(launches.single(), DRIVER)
        drive()
        clearSent()
        park()
        tick()
        assertFalse(CarPlayVideo.blocked)
        assertFalse(CarPlayVideo.playing)
        assertEquals(false, rates[screen])
        assertTrue(names().isEmpty())
        // Paused from the car now: a later move does not start it either.
        drive()
        screens.shown[screen] = PASSENGER
        tick()
        assertFalse(CarPlayVideo.playing)
    }

    @Test fun pausingFromTheCarWhileBlockedKeepsItPausedOnThePassengerScreen() {
        play()
        val screen = open(launches.single(), DRIVER)
        drive()
        CarPlayVideo.setPlaying(false)
        screens.shown[screen] = PASSENGER
        tick()
        assertFalse(CarPlayVideo.blocked)
        assertFalse(CarPlayVideo.playing)
        assertEquals(false, rates[screen])
    }

    @Test fun aPauseFromTheIphoneWhileBlockedDoesNotCancelTheResume() {
        play()
        val screen = open(launches.single(), DRIVER)
        drive()
        message(mapOf("type" to "setRate", "rate" to 0.0))
        screens.shown[screen] = PASSENGER
        tick()
        assertTrue(CarPlayVideo.playing)
        assertTrue(diagnostics.any { it.startsWith("event=set_rate rate=0") && it.contains("driverScreen=false") })
    }

    @Test fun aBlockedPlayerRecreatedWithTheSameLaunchStaysBlockedAndResumesLater() {
        play()
        val launch = launches.single()
        val first = open(launch, DRIVER)
        drive()
        // Android destroys and creates the player in one main-thread message.
        CarPlayVideo.detachPlayer(first, changingConfigurations = true, positionMillis = 42_000,
            configuration = first.resources.configuration)
        val second = open(launch, DRIVER)
        assertSame(second, CarPlayVideo.activity)
        assertTrue(diagnostics.any { it.startsWith("event=player_attached") })
        assertTrue(diagnostics.any { it.startsWith("event=player_recreated configDiff=0x") })
        assertEquals(42_000, CarPlayVideo.startMillis)
        assertTrue(CarPlayVideo.blocked)
        assertEquals(false, rates[second])
        rate()
        assertFalse(CarPlayVideo.playing)
        screens.shown[second] = PASSENGER
        tick()
        assertTrue(CarPlayVideo.playing)
        assertEquals(true, rates[second])
    }

    @Test fun thePassengerScreenTurnedOffInPClosesThePlayerThere() {
        play()
        val screen = open(launches.single(), PASSENGER)
        screens.on = false
        park(passenger = null)
        tick()
        assertTrue(screen.playbackReleased)
        assertTrue(diagnostics.any { it.startsWith("event=playback_paused") && it.contains("reason=passenger_screen_gone") })
    }

    @Test fun aPlayerOnAnyOtherDisplayCloses() {
        play()
        val screen = open(launches.single(), DRIVER)
        screens.shown[screen] = 1
        tick()
        assertTrue(screen.playbackReleased)
        assertTrue(diagnostics.any { it.contains("reason=other_display") })
    }

    @Test fun aNewPlayerForThePassengerScreenThatOpensOnTheDriversScreenClosesAndStopsFurtherTries() {
        drive()
        play()
        val screen = open(launches.single(), DRIVER)
        assertTrue(screen.playbackReleased)
        assertFalse(CarPlayVideo.blocked)
        assertTrue(diagnostics.any { it.contains("reason=passenger_launch_refused") })
        CarPlayVideo.detachPlayer(screen, false)
        launches.clear()
        CarPlayVideo.onVideoUiRequested(); idle()
        queue("second")
        rate()
        assertTrue(launches.isEmpty())
        assertTrue(diagnostics.any { it.startsWith("event=open_skipped") && it.contains("reason=passenger_launch_unavailable") })
        park()
        rate()
        assertEquals(DRIVER, launches.single().display ?: DRIVER)
        val inP = open(launches.single(), DRIVER)
        assertNull(CarPlayVideo.playerMove())
        assertEquals(View.GONE, ReflectionHelpers.getField<TextView>(inP, "moveButton").visibility)
    }

    @Test fun theMoveButtonStartsTheSamePlayerOnThePassengerScreen() {
        play()
        val screen = open(launches.single(), DRIVER)
        assertEquals(CarPlayVideoPlacement.Move.TO_PASSENGER, CarPlayVideo.playerMove())
        launches.clear()
        moveButton(screen).performClick()
        val move = launches.single()
        assertEquals(PASSENGER, move.display)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP, move.intent.flags)
        assertEquals(screen.intent.getLongExtra(CarPlayVideo.EXTRA_LAUNCH, -1), move.intent.getLongExtra(CarPlayVideo.EXTRA_LAUNCH, -2))
        screens.shown[screen] = PASSENGER
        CarPlayVideo.onPlayerDisplay(screen)
        assertTrue(diagnostics.any { it.startsWith("event=player_moved") })
        assertEquals(CarPlayVideoPlacement.Move.TO_DRIVER, CarPlayVideo.playerMove())
        assertEquals(screen.getString(R.string.video_move_to_main_screen), moveText(screen))
        drive()
        assertNull(CarPlayVideo.playerMove())
        assertEquals(View.GONE, moveButton(screen).visibility)
    }

    @Test fun aMoveThatDoesNotHappenByTheSecondReadingHidesTheButton() {
        play()
        val screen = open(launches.single(), DRIVER)
        CarPlayVideo.movePlayer(CarPlayVideoPlacement.Move.TO_PASSENGER)
        tick()
        assertNotNull(CarPlayVideo.playerMove())
        tick()
        assertNull(CarPlayVideo.playerMove())
        assertEquals(View.GONE, moveButton(screen).visibility)
        assertTrue(diagnostics.any { it.startsWith("event=player_move_refused") })
    }

    @Test fun aRefusedMoveStartHidesTheButton() {
        play()
        val screen = open(launches.single(), DRIVER)
        CarPlayVideo.startPlayer = { _, _, _ -> throw SecurityException("display") }
        CarPlayVideo.movePlayer(CarPlayVideoPlacement.Move.TO_PASSENGER)
        assertNull(CarPlayVideo.playerMove())
        assertEquals(View.GONE, moveButton(screen).visibility)
        assertFalse(screen.playbackReleased)
    }

    @Test fun anotherRequestForAnOpenPlayerBringsItsTaskForwardInsteadOfOpeningAnother() {
        play()
        val screen = open(launches.single(), PASSENGER)
        val task = appTask(screen.taskId)
        val other = appTask(screen.taskId + 1)
        shadowOf(screen.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(other, task))
        launches.clear()
        CarPlayVideo.onVideoUiRequested()
        idle()
        assertTrue(launches.isEmpty())
        verify(task).moveToFront()
        verify(other, never()).moveToFront()
        assertTrue(diagnostics.any { it.startsWith("event=player_brought_to_front") && it.contains("moved=true") })

        diagnostics.clear()
        doThrow(IllegalStateException("gone")).`when`(task).moveToFront()
        rate()
        assertTrue(launches.isEmpty())
        assertTrue(diagnostics.any { it.startsWith("event=player_brought_to_front") && it.contains("moved=false") })
        diagnostics.clear()
        shadowOf(screen.getSystemService(ActivityManager::class.java)).setAppTasks(listOf(other))
        rate()
        assertTrue(diagnostics.any { it.startsWith("event=player_brought_to_front") && it.contains("moved=false") })
    }

    @Test fun onlyAStartOfTheSamePlayerIsAcceptedAndADuplicatePlayerIsRefused() {
        play()
        val launch = launches.single()
        val screen = open(launch, DRIVER)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "onNewIntent",
            ReflectionHelpers.ClassParameter.from(Intent::class.java, Intent(launch.intent).putExtra(CarPlayVideo.EXTRA_LAUNCH, 99L)))
        assertTrue(diagnostics.any { it.startsWith("event=player_new_intent_ignored") })
        assertEquals(launch.intent.getLongExtra(CarPlayVideo.EXTRA_LAUNCH, -1), screen.intent.getLongExtra(CarPlayVideo.EXTRA_LAUNCH, -2))
        val duplicate = Robolectric.buildActivity(OwnTaskCarPlayVideoActivity::class.java, launch.intent).get()
        assertFalse(CarPlayVideo.attachPlayer(duplicate))
        assertSame(screen, CarPlayVideo.activity)
    }

    @Test fun theCarPlayCanvasIsHeldOnlyWhileThePlayerIsOnTheCarPlayDisplay() {
        play()
        val screen = open(launches.single(), PASSENGER)
        assertFalse(CarPlayVideo.playerOnScreen)
        screens.shown[screen] = DRIVER
        CarPlayVideo.onPlayerDisplay(screen)
        assertTrue(CarPlayVideo.playerOnScreen)
        assertEquals(screen.getString(R.string.video_back_to_carplay), backText(screen))
        screens.shown[screen] = PASSENGER
        tick()
        assertFalse(CarPlayVideo.playerOnScreen)
    }

    @Test fun closingTheVideoOnThePassengerScreenDoesNotOpenCarPlayThere() {
        play()
        val screen = open(launches.single(), PASSENGER)
        val back = descendants(ReflectionHelpers.getField(screen, "controls")).filterIsInstance<TextView>()
            .single { it.text == screen.getString(R.string.video_close) }
        back.performClick()
        assertTrue(screen.isFinishing)
        assertFalse(CarPlayVideo.playing)
        assertNull(shadowOf(screen).nextStartedActivity)
    }

    @Test fun leavingParkWithoutAPassengerScreenStillClosesThePlayer() {
        play()
        val screen = open(launches.single(), DRIVER)
        VideoInCar.screens = VideoScreens()
        VideoInCar.allowed = false
        CarPlayVideo.onVideoAllowedChanged(false)
        idle()
        assertTrue(screen.playbackReleased)
        assertFalse(CarPlayVideo.blocked)
        assertTrue(diagnostics.any { it.contains("reason=left_park") })
    }

    @Test fun stopAndSessionEndReportTheTimeSinceLeavingP() {
        play()
        open(launches.single(), PASSENGER)
        drive()
        message(mapOf("type" to "stop"))
        CarPlayVideo.onVideoSessionEnded()
        idle()
        assertTrue(diagnostics.any { it.startsWith("event=stop_command") && it.contains("driverScreen=false sinceLeftParkMs=") &&
            !it.contains("sinceLeftParkMs=-1") })
        assertTrue(diagnostics.any { it.startsWith("event=video_session_ended driverScreen=false") })
    }

    @Test fun aPlayerOpeningOnTheDriversScreenAfterLeavingPIsBlockedBeforeItLoads() {
        play()
        val launch = launches.single()
        drive()
        val controller = created(launch, DRIVER)
        val screen = controller.get()
        try {
            assertSame(screen, CarPlayVideo.activity)
            assertTrue(CarPlayVideo.blocked)
            assertFalse(CarPlayVideo.playing)
            val player = ReflectionHelpers.getField<ExoPlayer>(screen, "player")
            assertFalse(player.playWhenReady)
            val blockedAt = diagnostics.indexOfFirst { it.startsWith("event=player_blocked") }
            val loadedAt = diagnostics.indexOfFirst { it.startsWith("event=media_source") }
            assertTrue("$diagnostics", blockedAt in 0 until loadedAt)
            assertEquals(View.VISIBLE, cover(screen).visibility)
            assertEquals(View.INVISIBLE, ReflectionHelpers.getField<View>(screen, "playerView").visibility)
        } finally {
            controller.destroy()
        }
    }

    @Test fun aPlayerOpeningOnAnotherDisplayClosesBeforeItLoads() {
        play()
        val controller = created(launches.single(), 1)
        val screen = controller.get()
        assertTrue(screen.playbackReleased)
        assertTrue(screen.isFinishing)
        assertTrue(diagnostics.any { it.contains("reason=other_display") })
        assertFalse(diagnostics.any { it.startsWith("event=media_source") })
        controller.destroy()
    }

    @Test fun aPlayerOpeningInPOnTheDriversScreenPlays() {
        play()
        val controller = created(launches.single(), DRIVER)
        try {
            assertTrue(ReflectionHelpers.getField<ExoPlayer>(controller.get(), "player").playWhenReady)
            assertTrue(CarPlayVideo.playerOnScreen)
        } finally {
            controller.destroy()
        }
    }

    @Test fun aRefusedPlayerInItsOwnTaskRemovesThatTask() {
        play()
        val launch = launches.single()
        message(mapOf("type" to "stop"))
        val controller = Robolectric.buildActivity(OwnTaskCarPlayVideoActivity::class.java, launch.intent).create()
        assertTrue(controller.get().isFinishing)
        assertNull(CarPlayVideo.activity)
        controller.destroy()
    }

    @Test fun theDisplayIsReadOnceTheMoveHasCompleted() {
        play()
        val controller = created(launches.single(), DRIVER).start().resume().visible()
        val screen = controller.get()
        try {
            assertTrue(CarPlayVideo.playerOnScreen)
            screens.shown[screen] = PASSENGER
            screen.onConfigurationChanged(android.content.res.Configuration(screen.resources.configuration))
            assertTrue(CarPlayVideo.playerOnScreen)
            idle()
            assertFalse(CarPlayVideo.playerOnScreen)
            assertTrue(diagnostics.any { it.startsWith("event=player_config_changed diff=0x") && it.endsWith("display=$PASSENGER") })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun closingAPlayerTheFirmwareMovedGoesByItsDisplayNow() {
        play()
        val screen = open(launches.single(), DRIVER)
        assertTrue(CarPlayVideo.playerOnScreen)
        screens.shown[screen] = PASSENGER
        descendants(ReflectionHelpers.getField(screen, "controls")).filterIsInstance<TextView>()
            .single { it.text == screen.getString(R.string.video_back_to_carplay) }.performClick()
        assertTrue(screen.isFinishing)
        assertNull(shadowOf(screen).nextStartedActivity)
    }

    @Test fun mediaKeysStayWithTheVideoAndOtherKeysReachCarPlayOnlyFromAnotherScreen() {
        play()
        val controller = created(launches.single(), PASSENGER)
        val screen = controller.get()
        val carPlay = mock(CarPlayHostActivity::class.java)
        ReflectionHelpers.setField(CarPlayScreen, "open", WeakReference(carPlay))
        try {
            fun press(code: Int) = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code)
            for (code in listOf(android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, android.view.KeyEvent.KEYCODE_MEDIA_NEXT,
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, android.view.KeyEvent.KEYCODE_HEADSETHOOK)) {
                screen.dispatchKeyEvent(press(code))
            }
            verify(carPlay, never()).dispatchKeyEvent(anyKey())
            val select = press(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
            screen.dispatchKeyEvent(select)
            verify(carPlay).dispatchKeyEvent(select)
            screens.shown[screen] = DRIVER
            screen.dispatchKeyEvent(press(android.view.KeyEvent.KEYCODE_DPAD_LEFT))
            verify(carPlay, times(1)).dispatchKeyEvent(anyKey())
        } finally {
            controller.destroy()
        }
    }

    @Test fun aPlayerInItsOwnTaskStaysPlacedByScreensAfterTheOptionIsTurnedOff() {
        play()
        val screen = open(launches.single(), DRIVER)
        screens.enabled = false
        drive()
        assertTrue(CarPlayVideo.blocked)
        assertFalse(CarPlayVideo.playing)
        assertEquals(false, rates[screen])
    }

    @Test fun aPlayerOpenedOverCarPlayClosesOutsidePAfterTheOptionIsTurnedOn() {
        screens.enabled = false
        play()
        val screen = Robolectric.buildActivity(CarPlayVideoActivity::class.java, shadowOf(host).nextStartedActivity).get()
        ReflectionHelpers.setField(screen, "player", mock(ExoPlayer::class.java))
        ReflectionHelpers.setField(screen, "controls", ReflectionHelpers.callInstanceMethod<View>(screen, "controlBar"))
        assertTrue(CarPlayVideo.attachPlayer(screen))
        screens.enabled = true
        CarPlayVideo.onPlayerDisplay(screen)
        assertTrue(CarPlayVideo.playerOnScreen)
        assertEquals(View.GONE, moveButton(screen).visibility)
        assertTrue(launches.isEmpty())
        drive()
        assertTrue(screen.playbackReleased)
        assertFalse(CarPlayVideo.blocked)
        assertTrue(diagnostics.any { it.contains("reason=left_park") })
    }

    @Test fun aNewItemAfterStopIsNotHeldByTheClosingBlockedPlayer() {
        play()
        val old = open(launches.single(), DRIVER)
        drive()
        assertTrue(CarPlayVideo.blocked)
        launches.clear()
        message(mapOf("type" to "stop"))
        queue("second")
        rate()
        assertTrue(CarPlayVideo.playing)
        assertTrue(old.playbackReleased)
        CarPlayVideo.detachPlayer(old, false)
        val launch = launches.single()
        assertEquals(PASSENGER, launch.display)
        val next = open(launch, PASSENGER)
        assertTrue(CarPlayVideo.playing)
        assertEquals(true, rates[next])
    }

    @Test fun aMoveOfAnEndedPlaybackDoesNotRefuseTheNextPlayer() {
        play()
        val old = open(launches.single(), DRIVER)
        CarPlayVideo.movePlayer(CarPlayVideoPlacement.Move.TO_PASSENGER)
        message(mapOf("type" to "stop"))
        CarPlayVideo.detachPlayer(old, false)
        launches.clear()
        queue("second")
        rate()
        val next = open(launches.single(), DRIVER)
        tick()
        tick()
        assertEquals(CarPlayVideoPlacement.Move.TO_PASSENGER, CarPlayVideo.playerMove())
        assertEquals(View.VISIBLE, moveButton(next).visibility)
        assertFalse(diagnostics.any { it.startsWith("event=player_move_refused") })
    }

    // Mockito's any() returns null, which a Kotlin parameter that is not nullable refuses.
    private fun anyKey(): android.view.KeyEvent {
        any(android.view.KeyEvent::class.java)
        return android.view.KeyEvent(0, 0)
    }

    private fun created(launch: Launch, display: Int): ActivityController<OwnTaskCarPlayVideoActivity> {
        val controller = Robolectric.buildActivity(OwnTaskCarPlayVideoActivity::class.java, launch.intent)
        screens.shown[controller.get()] = display
        return controller.create()
    }

    private fun open(launch: Launch, display: Int): CarPlayVideoActivity {
        val screen = Robolectric.buildActivity(OwnTaskCarPlayVideoActivity::class.java, launch.intent).get()
        val engine = mock(ExoPlayer::class.java)
        doAnswer { rates[screen] = it.getArgument(0); null }.`when`(engine).setPlayWhenReady(anyBoolean())
        ReflectionHelpers.setField(screen, "player", engine)
        ReflectionHelpers.setField(screen, "directMediaSources", mock(DefaultMediaSourceFactory::class.java))
        ReflectionHelpers.setField(screen, "iphoneMediaSources", mock(DefaultMediaSourceFactory::class.java))
        ReflectionHelpers.setField(screen, "playerView", View(screen))
        ReflectionHelpers.setField(screen, "controls", ReflectionHelpers.callInstanceMethod<View>(screen, "controlBar"))
        screens.shown[screen] = display
        assertTrue(CarPlayVideo.attachPlayer(screen))
        CarPlayVideo.onPlayerDisplay(screen)
        if (!screen.playbackReleased) screen.applyRate()
        return screen
    }

    private fun appTask(taskId: Int): ActivityManager.AppTask = mock(ActivityManager.AppTask::class.java).also {
        doReturn(ActivityManager.RecentTaskInfo().apply { this.taskId = taskId }).`when`(it).taskInfo
    }

    private fun park(passenger: Int? = PASSENGER) {
        VideoInCar.screens = VideoScreens(driverScreen = true, passengerDisplayId = passenger)
        VideoInCar.allowed = true
        CarPlayVideo.onVideoGateTick()
        idle()
    }

    private fun drive() {
        VideoInCar.screens = VideoScreens(driverScreen = false, passengerDisplayId = PASSENGER)
        VideoInCar.allowed = true
        tick()
    }

    private fun tick() {
        CarPlayVideo.onVideoGateTick()
        idle()
    }

    private fun names(): List<Any?> {
        flushReplies()
        return sent.filter { it["type"] == "playbackState" }.map { it["name"] }
    }

    private fun clearSent() {
        flushReplies()
        sent.clear()
    }

    private fun flushReplies() {
        val sender: ThreadPoolExecutor = ReflectionHelpers.getField(CarPlayVideo, "sender")
        sender.submit {}.get(2, TimeUnit.SECONDS)
    }

    private fun cover(screen: CarPlayVideoActivity): View = ReflectionHelpers.getField(screen, "blockedCover")
    private fun moveButton(screen: CarPlayVideoActivity): TextView = ReflectionHelpers.getField(screen, "moveButton")
    private fun moveText(screen: CarPlayVideoActivity) = moveButton(screen).text.toString()
    private fun backText(screen: CarPlayVideoActivity) =
        ReflectionHelpers.getField<TextView>(screen, "backButton").text.toString()

    private fun play() { queue(); rate() }
    private fun queue(id: String = "first") = message(mapOf(
        "type" to "insertPlayQueueItem",
        "item" to mapOf("uuid" to id, "Content-Location" to "https://example.com/$id.mp4"),
    ))
    private fun rate() = message(mapOf("type" to "setRate", "rate" to 1.0))
    private fun message(message: Map<String, Any?>) { CarPlayVideo.onVideoMessage(3, message); idle() }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private companion object {
        const val DRIVER = 0
        const val PASSENGER = 2
    }
}
