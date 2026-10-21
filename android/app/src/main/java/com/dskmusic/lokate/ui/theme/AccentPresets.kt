package com.dskmusic.lokate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Paleta predefinida de acentos, además del selector HSV personalizado en Ajustes.
 * Tonos elegidos a propósito para que no parezcan los típicos "rojo/verde/azul" de manual —
 * el primero (y el color por defecto de la app) es un azul índigo, no verde.
 */
val AccentPresets = listOf(
    Color(0xFF4A6FE3), // azul índigo (por defecto)
    Color(0xFF8457E8), // violeta orquídea
    Color(0xFFE0654F), // terracota
    Color(0xFF2E9B8F), // verde azulado apagado
    Color(0xFFD9A441), // ámbar dorado
    Color(0xFFC85D8E), // rosa empolvado
    // En modo oscuro/AMOLED este gris se aclara solo (ver accentFor en Theme.kt): tal cual
    // no se vería sobre el fondo negro.
    Color(0xFF4A5560), // gris oscuro pizarra
)


/**
 * Paletas de los dos colores del mapa (Ajustes). El primero de cada una es el que la app ha
 * llevado siempre, así que quien no toque nada sigue viendo lo mismo. Son colores oscuros y
 * saturados a propósito: tienen que leerse sobre el satélite, no solo sobre el mapa claro.
 */
val MapZonePresets = listOf(
    Color(0xFF1565C0), // azul señal (por defecto)
    Color(0xFF2E7D32), // verde bosque
    Color(0xFF6A1B9A), // morado
    Color(0xFFC62828), // rojo teja
    Color(0xFF00838F), // turquesa oscuro
    Color(0xFFEF6C00), // naranja
    Color(0xFF37474F), // gris azulado
)

val MapAccuracyPresets = listOf(
    Color(0xFFECEFF1), // gris casi blanco (por defecto): se ve sobre satélite y de noche
    Color(0xFF757575), // gris
    Color(0xFF1E88E5), // azul
    Color(0xFF00ACC1), // cian
    Color(0xFF7E57C2), // violeta
    Color(0xFFD81B60), // frambuesa
    Color(0xFFFFB300), // ámbar
)

val MapZoneMarginPresets = listOf(
    Color(0xFF0288D1), // azul cielo (por defecto)
    Color(0xFFF9A825), // ámbar
    Color(0xFFD84315), // rojo ladrillo
    Color(0xFFAD1457), // frambuesa oscura
    Color(0xFF9E9D24), // verde oliva
    Color(0xFF5E35B1), // violeta
    Color(0xFFECEFF1), // gris casi blanco
)
