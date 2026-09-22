package com.dskmusic.lokate.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import com.dskmusic.lokate.R

/**
 * Buscador que ocupa el título de la barra superior y filtra la lista según se escribe, sin botón
 * de confirmar: aquí se busca en memoria, así que no hay peticiones que encolar (a diferencia de
 * [PlaceSearchField], que sí pregunta a Nominatim y por eso espera a que pulses).
 */
@Composable
fun ListSearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    // Se abre con el teclado puesto: se pulsa la lupa para escribir, no para mirar.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box {
        if (query.isEmpty()) {
            Text(
                stringResource(R.string.list_search_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}
