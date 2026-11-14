package com.dharmil.mirrordash.render

import com.dharmil.mirrordash.navigation.GeoPoint

data class TftRenderState(
    val center: GeoPoint? = null,
    val routeSegments: List<List<GeoPoint>> = emptyList(),
    val rider: GeoPoint? = null,
    val zoom: Double = 12.0,
    val nextCueText: String? = null,
    val remainingMeters: Double? = null,
    val etaMinutes: Int? = null,
    val status: String = "Renderer test",
)
