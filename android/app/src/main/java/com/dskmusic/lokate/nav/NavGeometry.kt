package com.dskmusic.lokate.nav

import org.osmdroid.util.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Metros por grado. A la escala de una maniobra —unos cientos de metros— tratar el trozo de
 * mundo como un plano se equivoca en centímetros, y ahorra trigonometría en un bucle que corre
 * una vez por segundo con la pantalla encendida y el móvil en un soporte.
 *
 * ponytail: plano local, no geodésica. Si algún día esto navegara rutas transoceánicas habría
 * que volver a Location.distanceBetween; para una calle, no.
 */
private const val METERS_PER_DEG_LAT = 110_574.0
private const val METERS_PER_DEG_LNG = 111_320.0

/** Dónde va uno respecto a la línea de la ruta. */
data class RouteProgress(
    /** Índice del punto donde empieza el tramo en el que está. */
    val index: Int,
    /** Metros recorridos de ruta, contados desde el principio. */
    val metersFromStart: Double,
    /** A cuánto está de la línea. Esto es lo que dice si se ha salido de la ruta. */
    val offRouteMeters: Double,
)

/**
 * La línea de la ruta con las distancias ya sumadas.
 *
 * Todo lo que necesita la navegación sale de aquí: cuánto llevas, cuánto falta para la próxima
 * maniobra y si te has salido. Se suma una vez al trazar la ruta y después cada posición cuesta
 * un recorrido corto, no volver a medir la ruta entera.
 */
class RouteTrack(val points: List<GeoPoint>) {

    /** Metros del principio de la ruta a cada punto. */
    private val cumulative = DoubleArray(points.size)

    val totalMeters: Double

    init {
        var sum = 0.0
        for (i in 1 until points.size) {
            sum += flatMeters(points[i - 1], points[i])
            cumulative[i] = sum
        }
        totalMeters = sum
    }

    /** Metros del principio al punto [index] — el de una maniobra, normalmente. */
    fun metersTo(index: Int): Double =
        if (cumulative.isEmpty()) 0.0 else cumulative[index.coerceIn(0, cumulative.lastIndex)]

    /**
     * Pega la posición a la línea: en qué tramo va, cuánto lleva recorrido y a qué distancia
     * está de la ruta.
     *
     * Busca desde [fromIndex] hacia delante a propósito. Una ruta que pasa dos veces por la
     * misma calle (dar la vuelta a la manzana) haría retroceder el progreso si se cogiera
     * siempre el tramo más cercano de toda la línea, y la navegación se volvería loca justo en
     * el sitio donde más falta hace que no lo haga.
     */
    fun snap(lat: Double, lng: Double, fromIndex: Int): RouteProgress {
        if (points.size < 2) return RouteProgress(0, 0.0, 0.0)
        // Un tramo de margen hacia atrás: el GPS da bandazos de unos metros y sin esto un fix
        // malo justo al cruzar al tramo siguiente deja el progreso clavado.
        val start = (fromIndex - 1).coerceIn(0, points.size - 2)
        var best = RouteProgress(start, cumulative[start], Double.MAX_VALUE)
        for (i in start until points.size - 1) {
            val (along, away) = project(lat, lng, points[i], points[i + 1])
            if (away < best.offRouteMeters) best = RouteProgress(i, cumulative[i] + along, away)
        }
        return best
    }

    /**
     * El punto que está a [meters] del principio de la ruta, interpolado dentro de su tramo.
     * Es lo que mueve al muñeco en la simulación; null = esos metros se pasan del final.
     */
    fun pointAt(meters: Double): GeoPoint? {
        if (points.isEmpty()) return null
        if (meters <= 0.0) return points.first()
        if (meters >= totalMeters) return null
        var i = 0
        while (i < cumulative.lastIndex && cumulative[i + 1] < meters) i++
        val tramo = cumulative[i + 1] - cumulative[i]
        val t = if (tramo <= 0.0) 0.0 else (meters - cumulative[i]) / tramo
        val a = points[i]
        val b = points[i + 1]
        return GeoPoint(
            a.latitude + (b.latitude - a.latitude) * t,
            a.longitude + (b.longitude - a.longitude) * t,
        )
    }
}

/** Metros entre dos puntos, en el plano local. */
private fun flatMeters(a: GeoPoint, b: GeoPoint): Double {
    val scale = cos(Math.toRadians(a.latitude))
    val dx = (b.longitude - a.longitude) * METERS_PER_DEG_LNG * scale
    val dy = (b.latitude - a.latitude) * METERS_PER_DEG_LAT
    return sqrt(dx * dx + dy * dy)
}

/**
 * Proyecta la posición sobre el tramo a→b. Devuelve (metros recorridos dentro del tramo,
 * metros de separación de la línea). El punto se agarra a los extremos: pasado el final del
 * tramo, lo que cuenta es la distancia al extremo, no a la recta infinita que lo prolonga.
 */
private fun project(lat: Double, lng: Double, a: GeoPoint, b: GeoPoint): Pair<Double, Double> {
    val scale = cos(Math.toRadians(a.latitude))
    val bx = (b.longitude - a.longitude) * METERS_PER_DEG_LNG * scale
    val by = (b.latitude - a.latitude) * METERS_PER_DEG_LAT
    val px = (lng - a.longitude) * METERS_PER_DEG_LNG * scale
    val py = (lat - a.latitude) * METERS_PER_DEG_LAT
    val len2 = bx * bx + by * by
    val t = if (len2 == 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
    val dx = px - t * bx
    val dy = py - t * by
    return Pair(t * sqrt(len2), sqrt(dx * dx + dy * dy))
}
