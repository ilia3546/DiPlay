package com.shilapi.xcertplay.media

import android.Manifest
import android.media.AudioManager
import android.media.MediaRecorder
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord

/** The Settings choice reaches the real sink and uplink, and an input the head unit lacks falls back to AUTO. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [MicrophoneSourceFallbackTest.RecorderBuilder::class, ShadowAudioModeRequests::class])
class MicrophoneInputChoiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val id = AudioStreamId(100, "speechrecognition")
    private val diagnostics = CopyOnWriteArrayList<String>()
    private val captureStarted = CountDownLatch(1)
    private var choice = MicrophoneInput.AUTO
    private lateinit var sink: AndroidMediaSink

    @Before fun setUp() {
        MicrophoneSourceFallbackTest.RecorderBuilder.attempts.clear()
        MicrophoneSourceFallbackTest.RecorderBuilder.built.clear()
        MicrophoneSourceFallbackTest.RecorderBuilder.rejectSources = emptySet()
        MicrophoneSourceFallbackTest.RecorderBuilder.uninitializedSources = emptySet()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS)
        ShadowAudioModeRequests.clearRequests()
        ShadowAudioModeRequests.otherProcess(AudioManager.MODE_RINGTONE)
        sink = AndroidMediaSink(context = context, onAudioDiagnostic = diagnostics::add, microphoneInput = { choice })
        ShadowAudioRecord.setSourceProvider {
            captureStarted.countDown()
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    Thread.sleep(5)
                    return 0
                }
            }
        }
    }

    @After fun tearDown() {
        sink.close()
        ShadowAudioRecord.clearSource()
    }

    private fun start(): List<Int> {
        sink.onMicrophoneStarted(id, MicrophoneConfig(audioType = "speechrecognition", sampleRate = 16_000,
            channels = 1, payloadType = 100, frameMillis = 20, host = InetAddress.getLoopbackAddress(),
            port = 9, key = ByteArray(32)))
        assertTrue(captureStarted.await(5, TimeUnit.SECONDS))
        return MicrophoneSourceFallbackTest.RecorderBuilder.attempts.toList()
    }

    @Test fun autoRecordsSiriFromTheSourceDiPlayChoseBefore() {
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION), start())
        assertTrue(diagnostics.any { it.startsWith("Microphone: start type=speechrecognition source=VOICE_RECOGNITION") && it.endsWith("input=AUTO") })
    }

    @Test fun aChosenInputRecordsSiriFromThatSource() {
        choice = MicrophoneInput.CAMCORDER
        assertEquals(listOf(MediaRecorder.AudioSource.CAMCORDER), start())
        assertTrue(diagnostics.any { it.startsWith("Microphone: start type=speechrecognition source=CAMCORDER") && it.endsWith("input=CAMCORDER") })
    }

    @Test fun aMissingVoiceAssistantBusFallsBackToAuto() {
        choice = MicrophoneInput.VOICE_ASSISTANT_BUS
        assertEquals(listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION), start())
        assertTrue(diagnostics.contains("Microphone: no bus14_vr_in input on this head unit; using AUTO"))
        assertTrue(diagnostics.any { it.startsWith("Microphone: start") && it.endsWith("input=AUTO") })
    }

    @Test fun refusedCabinMicrophonesFallBackToAutoAfterEveryChannelCount() {
        choice = MicrophoneInput.ALL_CABIN_MICROPHONES
        MicrophoneSourceFallbackTest.RecorderBuilder.rejectSources = setOf(MediaRecorder.AudioSource.MIC)
        assertEquals(listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION), start())
        assertTrue(diagnostics.any {
            it == "Microphone: all cabin microphones unavailable (0xFF build UnsupportedOperationException; " +
                "0x0F build UnsupportedOperationException; 0x03 build UnsupportedOperationException); using AUTO"
        })
        assertTrue(diagnostics.any { it.startsWith("Microphone: start") && it.endsWith("input=AUTO") })
    }
}
