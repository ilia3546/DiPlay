package com.shilapi.xcertplay

import android.os.Bundle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DesayWheelKeysTest {

    @Test fun theSubscriptionAsksForTheDashboardViewKeyOnly() {
        assertArrayEquals(intArrayOf(DesayWheelKeys.KEY_DASHBOARD_VIEW),
            DesayWheelKeys.subscribePayload().getIntArray("type"))
    }

    @Test fun aKeyEventGivesItsKeyAndAction() {
        val payload = Bundle().apply {
            putInt("type", DesayWheelKeys.KEY_DASHBOARD_VIEW)
            putInt("action", DesayWheelKeys.ACTION_RELEASE)
        }
        assertEquals(DesayWheelKeys.KEY_DASHBOARD_VIEW to DesayWheelKeys.ACTION_RELEASE, DesayWheelKeys.keyOf(payload))
    }

    @Test fun anEventWithoutKeyOrActionIsIgnored() {
        assertNull(DesayWheelKeys.keyOf(null))
        assertNull(DesayWheelKeys.keyOf(Bundle()))
        assertNull(DesayWheelKeys.keyOf(Bundle().apply { putInt("type", DesayWheelKeys.KEY_DASHBOARD_VIEW) }))
        assertNull(DesayWheelKeys.keyOf(Bundle().apply { putInt("action", DesayWheelKeys.ACTION_PRESS) }))
    }
}
