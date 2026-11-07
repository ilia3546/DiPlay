package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** A media key that reaches DiPlay's media session is in the diagnostic report, not only in logcat (#610). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MediaKeyReportTest {
    private val zone = TimeZone.getDefault()

    @Before fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        WheelKeyLog.reset()
    }

    @After fun tearDown() {
        TimeZone.setDefault(zone)
        WheelKeyLog.reset()
        ReflectionHelpers.setField(CarPlayMediaKeys, "wheelKeyForwardedAt", Long.MIN_VALUE / 2)
    }

    @Test fun aMediaKeyWithoutASessionIsReportedAsNotSent() {
        CarPlayMediaKeys.onExternalMediaKey(CarPlayMediaButton.NEXT)
        val report = WheelKeyLog.report(bound = false)
        assertTrue(report.toString(), report.any {
            it.startsWith("Wheel keys: ") && it.endsWith("media key geely-wheel -> CarPlay ${CarPlayMediaButton.NEXT} sent=false")
        })
    }

    @Test fun aCallKeySentFromTheWheelMarksItLikeAMediaKeySoTheCarsEchoIsTakenBack() {
        val controller = mock(CarPlayController::class.java)
        GeelyWheelKeys.sendToCall(controller, CarPlayMediaButton.NEXT, GeelyCallKeys.Action.ANSWER)
        assertFalse("not sent", CarPlayMediaKeys.wheelKeyJustHandled())
        `when`(controller.answerCall()).thenReturn(true)
        GeelyWheelKeys.sendToCall(controller, CarPlayMediaButton.NEXT, GeelyCallKeys.Action.ANSWER)
        assertTrue(CarPlayMediaKeys.wheelKeyJustHandled())
    }
}
