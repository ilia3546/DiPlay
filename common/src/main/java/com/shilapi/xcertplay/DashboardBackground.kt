package com.shilapi.xcertplay

enum class DashboardBackground {
    OCEAN,
    AURORA,
    DUSK,
    NEON,
    GRAPHITE,
    MIDNIGHT,
    FOREST,
    DUNES,
    MAGMA,
    ICE,
    VIOLET,
    CARBON,
    BLACK,
    CUSTOM_PHOTO;

    companion object {
        val presets: List<DashboardBackground> = entries - CUSTOM_PHOTO
        val dimmingPercents: List<Int> = listOf(0, 20, 40, 60)
    }
}
