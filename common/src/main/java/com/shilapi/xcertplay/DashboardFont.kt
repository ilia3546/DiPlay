package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Typeface
import android.os.Build

/** Onest, the custom dashboard's font: one variable file in the assets, set to each weight the dashboard uses. */
internal object DashboardFont {
    private const val ASSET = "fonts/onest/Onest-wght.ttf"
    private val cache = HashMap<Int, Typeface>()

    @Synchronized
    fun typeface(context: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        val onest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                Typeface.Builder(context.assets, ASSET)
                    .setFontVariationSettings("'wght' $weight")
                    .setWeight(weight)
                    .build()
            }.getOrNull()
        } else {
            null
        }
        onest ?: if (weight >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }
}
