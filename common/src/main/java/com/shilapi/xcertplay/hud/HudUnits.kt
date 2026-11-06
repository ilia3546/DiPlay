package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.text.DecimalFormatSymbols

/** The unit labels and the decimal separator of the HUD line. */
data class HudUnits(
    val meters: String,
    val kilometres: String,
    val minutes: String,
    val decimalSeparator: Char,
) {
    companion object {
        /** The units of [context]'s language; give it a context in the app language. */
        fun of(context: Context): HudUnits = HudUnits(
            meters = context.getString(R.string.hud_unit_meters),
            kilometres = context.getString(R.string.hud_unit_kilometres),
            minutes = context.getString(R.string.hud_unit_minutes),
            decimalSeparator = DecimalFormatSymbols.getInstance(context.resources.configuration.locales[0]).decimalSeparator,
        )
    }
}
