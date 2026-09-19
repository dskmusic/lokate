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
