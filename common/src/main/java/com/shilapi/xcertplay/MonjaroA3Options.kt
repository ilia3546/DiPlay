package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.network.GeelyHeadUnit

/** The Geely Monjaro A3 card's choices as a connection uses them; all off on any other head unit. */
internal data class MonjaroA3Options(
    val rootHotspotDetails: Boolean = false,
    val disconnectPhoneBluetooth: Boolean = false,
    val hotspotAudioFirewall: Boolean = false,
) {
    companion object {
        fun load(context: Context, monjaroA3: Boolean = GeelyHeadUnit.monjaroA3): MonjaroA3Options {
            if (!monjaroA3) return MonjaroA3Options()
            return MonjaroA3Options(
                rootHotspotDetails = AirPlayPersistence.loadRootHotspotDetails(context),
                disconnectPhoneBluetooth = AirPlayPersistence.loadDisconnectPhoneBluetooth(context),
                hotspotAudioFirewall = AirPlayPersistence.loadHotspotAudioFirewall(context),
            )
        }
    }
}
