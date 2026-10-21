package com.dskmusic.lokate.ui.map

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Un punto del mapa sin dueño: ni GeoPoint de osmdroid ni LatLng de MapLibre. Solo lo usa la
 * geometría de aquí — los dos renderizadores lo convierten a lo suyo en una línea.
 */
data class LatLon(val lat: Double, val lng: Double)

/** El radio que usa osmdroid (GeoConstants.RADIUS_EARTH_METERS): el mismo aquí, para que los
 * círculos de las zonas salgan donde siempre y no un metro más allá al cambiar de mapa. */
private const val EARTH_RADIUS_M = 6_378_137.0

/** A [meters] de [lat]/[lng] en la dirección [bearingDeg] (0 = norte), sobre la esfera. Es lo
 * mismo que GeoPoint.destinationPoint de osmdroid, pero sin osmdroid. */
fun destination(lat: Double, lng: Double, meters: Double, bearingDeg: Double): LatLon {
    val angular = meters / EARTH_RADIUS_M
    val bearing = Math.toRadians(bearingDeg)
    val latRad = Math.toRadians(lat)
    val lngRad = Math.toRadians(lng)
    val lat2 = asin(sin(latRad) * cos(angular) + cos(latRad) * sin(angular) * cos(bearing))
    val lng2 = lngRad + atan2(
        sin(bearing) * sin(angular) * cos(latRad),
        cos(angular) - sin(latRad) * sin(lat2),
    )
    return LatLon(Math.toDegrees(lat2), Math.toDegrees(lng2))
}

/**
 * El contorno de un círculo de [radiusMeters] alrededor de [lat]/[lng], como polígono de
 * [points] lados. Lo comparten el mapa de osmdroid (zonas, margen, precisión) y el de MapLibre
 * — ahí es la única manera: su CircleManager mide el radio en píxeles de pantalla, no en metros,
 * así que un círculo suyo cambiaría de tamaño real con cada zoom.
 */
fun circleRing(lat: Double, lng: Double, radiusMeters: Double, points: Int = 64): List<LatLon> =
    (0 until points).map { i -> destination(lat, lng, radiusMeters, 360.0 / points * i) }
