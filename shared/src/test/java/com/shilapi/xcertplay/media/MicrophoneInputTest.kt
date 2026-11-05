package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophoneInputTest {
    private val flags = listOf(false, true)

    @Test fun autoKeepsTheSourcesDiPlayChoseBefore() {
        for (speakerphone in flags) for (carPhoneState in flags) for (geely in flags) {
            assertEquals(telephonyMicrophoneSource(speakerphone, carPhoneState, geely),
                microphoneSource("telephony", MicrophoneInput.AUTO, speakerphone, carPhoneState, geely))
        }
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            microphoneSource("speechrecognition", MicrophoneInput.AUTO, false, false, geelyHeadUnit = false))
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            microphoneSource("speechrecognition", MicrophoneInput.AUTO, false, false, geelyHeadUnit = true))
        assertEquals(MediaRecorder.AudioSource.MIC, microphoneSource("other", MicrophoneInput.AUTO, false, false, true))
    }

    @Test fun aChosenInputReplacesTheSourceForCallsAndSiri() {
        val expected = mapOf(
            MicrophoneInput.VOICE_RECOGNITION to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MicrophoneInput.VOICE_COMMUNICATION to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MicrophoneInput.MIC to MediaRecorder.AudioSource.MIC,
            MicrophoneInput.CAMCORDER to MediaRecorder.AudioSource.CAMCORDER,
            MicrophoneInput.UNPROCESSED to MediaRecorder.AudioSource.UNPROCESSED,
            MicrophoneInput.VOICE_ASSISTANT_BUS to MediaRecorder.AudioSource.MIC,
            MicrophoneInput.ALL_CABIN_MICROPHONES to MediaRecorder.AudioSource.MIC,
        )
        for ((input, source) in expected) for (type in listOf("telephony", "speechrecognition")) {
            assertEquals("$input $type", source, microphoneSource(type, input.forStream(type), false, true, true))
        }
    }

    @Test fun otherStreamsIgnoreTheChoice() {
        for (input in MicrophoneInput.entries) assertEquals(MicrophoneInput.AUTO, input.forStream("alert"))
        assertEquals(MicrophoneInput.CAMCORDER, MicrophoneInput.CAMCORDER.forStream("telephony"))
    }

    @Test fun onlyTheVoiceAssistantBusMatches() {
        assertTrue(MicrophoneInput.isVoiceAssistantBus(AudioDeviceInfo.TYPE_BUS, "bus14_vr_in"))
        assertFalse(MicrophoneInput.isVoiceAssistantBus(AudioDeviceInfo.TYPE_BUS, "bus13_in"))
        assertFalse(MicrophoneInput.isVoiceAssistantBus(AudioDeviceInfo.TYPE_BUILTIN_MIC, "bus14_vr_in"))
        assertFalse(MicrophoneInput.isVoiceAssistantBus(AudioDeviceInfo.TYPE_BUS, null))
    }
}
