package com.dskmusic.lokate.ui.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/** Esri World Imagery: teselas de satélite públicas y gratuitas (solo exigen atribución),
 * sin clave de API — misma clase base que usa osmdroid para sus propias fuentes. */
object SatelliteTileSource : OnlineTileSourceBase(
    "EsriWorldImagery",
    0,
    19,
    256,
    ".jpg",
    arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"),
    "Esri, Maxar, Earthstar Geographics",
) {
    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val x = MapTileIndex.getX(pMapTileIndex)
        val y = MapTileIndex.getY(pMapTileIndex)
        // getBaseUrl() reparte al azar entre varias URLs si hubiera más de una en el array;
        // aquí solo hay una, así que siempre es esta.
        return "${baseUrl}$zoom/$y/$x.jpg"
    }
}
