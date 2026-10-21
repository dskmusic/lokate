package com.dskmusic.lokate.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.dskmusic.lokate.R

/**
 * Radio de una zona escrito a mano, en metros.
 *
 * Guarda su propio texto en vez de pintar el del estado: si el campo se dibuja a partir del
 * radio guardado, al borrar el último dígito no queda número que guardar, el campo vuelve a
 * pintar el anterior y el siguiente dígito se pega a él (80 -> borrar -> escribir 60 -> 608).
 * Mientras el texto esté vacío o a medias el radio guardado no cambia; es lo que sigue
 * enseñando el slider de arriba.
 */
@Composable
fun RadiusField(radiusM: Double, onRadiusChange: (Double) -> Unit, modifier: Modifier = Modifier) {
    var texto by remember { mutableStateOf(radiusM.toInt().toString()) }

    // El radio también se cambia con el slider: ahí sí hay que repintar el texto.
    LaunchedEffect(radiusM) {
        if (texto.toDoubleOrNull() != radiusM) texto = radiusM.toInt().toString()
    }

    OutlinedTextField(
        value = texto,
        // Filtrado a dígitos a mano: el teclado numérico de algunos móviles deja colar el
        // punto y el signo menos.
        onValueChange = { entrada ->
            texto = entrada.filter { it.isDigit() }
            texto.toDoubleOrNull()?.let(onRadiusChange)
        },
        label = { Text(stringResource(R.string.zone_radius_manual_label)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}
