package com.shilapi.xcertplay.location

import kotlin.math.hypot

object NmeaSentenceParser {
    sealed interface Sentence {
        val utcTimeOfDayMillis: Long?
    }

    data class Gga(
        val fixQuality: Int?,
        val satellitesUsed: Int?,
        val hdop: Double?,
        override val utcTimeOfDayMillis: Long? = null,
    ) : Sentence

    data class Gst(
        val latitudeErrorMeters: Double,
        val longitudeErrorMeters: Double,
        override val utcTimeOfDayMillis: Long? = null,
    ) : Sentence {
        val horizontalErrorMeters: Double get() = hypot(latitudeErrorMeters, longitudeErrorMeters)
    }

    fun parseAll(message: String): List<Sentence> =
        message.split('\r', '\n', '$').filter { it.isNotBlank() }.mapNotNull { parse("$$it") }

    fun parse(line: String): Sentence? {
        val sentence = line.trim()
        if (!sentence.startsWith('$')) return null
        val star = sentence.lastIndexOf('*')
        if (star < 0 || sentence.length != star + 3) return null
        val body = sentence.substring(1, star)
        val expected = sentence.substring(star + 1).toIntOrNull(16) ?: return null
        if (body.fold(0) { checksum, character -> checksum xor character.code } != expected) return null
        val fields = body.split(',')
        val address = fields[0]
        if (address.length != ADDRESS_LENGTH) return null
        return when (address.substring(TALKER_LENGTH)) {
            "GGA" -> gga(fields)
            "GST" -> gst(fields)
            else -> null
        }
    }

    private fun gga(fields: List<String>): Gga? {
        if (fields.size <= GGA_HDOP) return null
        return Gga(
            fixQuality = fields[GGA_FIX_QUALITY].toIntOrNull()?.takeIf { it >= 0 },
            satellitesUsed = fields[GGA_SATELLITES_USED].toIntOrNull()?.takeIf { it >= 0 },
            hdop = fields[GGA_HDOP].toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 },
            utcTimeOfDayMillis = timeOfDayMillis(fields[TIME]),
        )
    }

    private fun gst(fields: List<String>): Gst? {
        if (fields.size <= GST_LONGITUDE_ERROR) return null
        val latitude = fields[GST_LATITUDE_ERROR].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        val longitude = fields[GST_LONGITUDE_ERROR].toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: return null
        return Gst(latitude, longitude, timeOfDayMillis(fields[TIME]))
    }

    private fun timeOfDayMillis(field: String): Long? {
        if (field.length < HHMMSS_LENGTH || !field.take(HHMMSS_LENGTH).all(Char::isDigit)) return null
        val hours = field.substring(0, 2).toInt()
        val minutes = field.substring(2, 4).toInt()
        val seconds = field.substring(4, HHMMSS_LENGTH).toInt()
        if (hours > 23 || minutes > 59 || seconds > 59) return null
        val fraction = field.substring(HHMMSS_LENGTH)
        val millis = when {
            fraction.isEmpty() -> 0L
            fraction.length > 1 && fraction[0] == '.' && fraction.drop(1).all(Char::isDigit) ->
                Math.round(("0$fraction").toDouble() * 1000).coerceAtMost(999)
            else -> return null
        }
        return ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis
    }

    private const val TIME = 1
    private const val HHMMSS_LENGTH = 6
    private const val TALKER_LENGTH = 2
    private const val ADDRESS_LENGTH = 5
    private const val GGA_FIX_QUALITY = 6
    private const val GGA_SATELLITES_USED = 7
    private const val GGA_HDOP = 8
    private const val GST_LATITUDE_ERROR = 6
    private const val GST_LONGITUDE_ERROR = 7
}
