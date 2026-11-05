package com.shilapi.xcertplay.media

import android.media.AudioDeviceInfo
import android.media.MediaRecorder

/** The recorder input chosen in Settings for CarPlay calls and Siri; [AUTO] keeps DiPlay's own choice. */
enum class MicrophoneInput(internal val source: Int?) {
    AUTO(null),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    MIC(MediaRecorder.AudioSource.MIC),
    CAMCORDER(MediaRecorder.AudioSource.CAMCORDER),
    UNPROCESSED(MediaRecorder.AudioSource.UNPROCESSED),

    /** The head unit's voice-assistant input bus, opened as the preferred device of a MIC recording. */
    VOICE_ASSISTANT_BUS(MediaRecorder.AudioSource.MIC),

    /** Every channel the microphone input offers, recorded by channel index and mixed to mono ([MicrophoneDownmix]). */
    ALL_CABIN_MICROPHONES(MediaRecorder.AudioSource.MIC);

    companion object {
        const val VOICE_ASSISTANT_BUS_ADDRESS = "bus14_vr_in"

        /** Channel index masks tried in turn for [ALL_CABIN_MICROPHONES]: eight, four, then two channels. */
        internal val CABIN_CHANNEL_INDEX_MASKS = listOf(0xFF, 0x0F, 0x03)

        fun isVoiceAssistantBus(type: Int, address: String?): Boolean =
            type == AudioDeviceInfo.TYPE_BUS && address == VOICE_ASSISTANT_BUS_ADDRESS
    }

    /** The choice applies to calls and Siri only; any other stream keeps [AUTO]. */
    internal fun forStream(audioType: String): MicrophoneInput =
        if (audioType == "telephony" || audioType == "speechrecognition") this else AUTO
}

/** The recorder source for a CarPlay microphone stream; a chosen [input] replaces DiPlay's own choice. */
internal fun microphoneSource(
    audioType: String,
    input: MicrophoneInput,
    speakerphoneCall: Boolean,
    carPhoneState: Boolean,
    geelyHeadUnit: Boolean,
): Int = input.source ?: when (audioType) {
    "telephony" -> telephonyMicrophoneSource(speakerphoneCall, carPhoneState, geelyHeadUnit)
    // The Geely/Lynk head unit only opens the microphone for the voice-communication
    // source; its voice-recognition source records silence, so Siri never hears.
    "speechrecognition" -> if (geelyHeadUnit) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION
    } else {
        MediaRecorder.AudioSource.VOICE_RECOGNITION
    }
    else -> MediaRecorder.AudioSource.MIC
}
