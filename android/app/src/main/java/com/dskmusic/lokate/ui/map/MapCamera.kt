package com.dskmusic.lokate.ui.map

import com.dskmusic.lokate.util.Constants
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView

/**
 * Lleva el mapa a [target]. Anima solo los desplazamientos cortos: por encima de
 * [Constants.MAP_ANIMATE_MAX_DISTANCE_METERS] salta directo, porque animar un salto largo hace
 * que osmdroid pida todas las teselas del camino intermedio al zoom actual (cientos, si se va
 * cerca) y la zona de destino tarda una eternidad en aparecer.
 */
fun MapView.moveTo(target: GeoPoint) {
    val current = GeoPoint(mapCenter.latitude, mapCenter.longitude)
    if (current.distanceToAsDouble(target) > Constants.MAP_ANIMATE_MAX_DISTANCE_METERS) {
        controller.setCenter(target)
        // El zoom no se toca: se salta con el que llevara el usuario. Reaplicarlo sí, porque
        // osmdroid decide qué teselas pedir al dibujarse y el salto le pilla con las del sitio
        // anterior pedidas y ninguna del nuevo — mismo truco que el primer layout (ver
        // [OsmMapView]). De que haya algo que pintar muy de cerca se encarga [TileRetryEffect].
        controller.setZoom(zoomLevelDouble)
        invalidate()
    } else {
        controller.animateTo(target)
    }
}
