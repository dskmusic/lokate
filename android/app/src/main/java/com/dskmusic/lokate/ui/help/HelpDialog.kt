package com.dskmusic.lokate.ui.help

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dskmusic.lokate.ui.common.AppFooter

private data class HelpSection(val emoji: String, val title: String, val body: String)

private val sections = listOf(
    HelpSection(
        "📍", "El mapa",
        "Muestra en tiempo real dónde está cada miembro de tu grupo familiar (foto o inicial " +
            "sobre un círculo de color). El botón redondo de la izquierda (\"Dónde estoy\") centra " +
            "el mapa en tu posición actual. A la derecha: \"Invitar\" para sumar a alguien más al " +
            "grupo, y \"Compartir\" para enviar tu ubicación actual por WhatsApp, SMS, etc.",
    ),
    HelpSection(
        "👨‍👩‍👧", "Grupo familiar",
        "Solo los administradores pueden CREAR un grupo nuevo; cualquiera puede UNIRSE a uno " +
            "existente con el código de invitación. Accede desde el icono de personas arriba a la " +
            "izquierda del mapa: ahí ves el código para invitar, la lista de miembros, y puedes " +
            "salir del grupo.",
    ),
    HelpSection(
        "🔔", "Zonas guardadas y avisos — lo más importante",
        "Una zona es un círculo con un centro y un radio (ej. \"Casa\", \"Colegio\") que creas " +
            "buscando una dirección o tocando el mapa. Desde \"Zonas\" puedes activar o desactivar, " +
            "por cada zona por separado, el aviso al ENTRAR y el aviso al SALIR.\n\n" +
            "¿Quién manda estos avisos? Nadie los envía a mano: el SERVIDOR los genera solo, en " +
            "cuanto detecta que la posición de alguien ha cruzado el borde de una zona guardada.\n\n" +
            "¿A quién le llegan? A todos los demás miembros del grupo — nunca a la persona que se " +
            "ha movido (no te avisa de tus propios movimientos), y solo si esa persona no ha " +
            "desactivado ese aviso concreto para esa zona.",
    ),
    HelpSection(
        "🔋", "Tocar un miembro",
        "Abre su ficha: nivel de batería (y si está cargando), si tiene wifi o va con datos, y la " +
            "hora de la última actualización. El botón de refrescar (🔄) vuelve a consultar su " +
            "última posición conocida. \"Hacer sonar el dispositivo\" (pide confirmación) le manda " +
            "una notificación con sonido y vibración fuertes para ayudar a encontrarlo — esta sí la " +
            "envías tú explícitamente, no el sistema automático de zonas.",
    ),
    HelpSection(
        "🕘", "Historial",
        "Consulta la ruta de un miembro por días (1/3/7/30). Se ve como una línea sobre el mapa " +
            "con un punto por cada posición registrada; tocar un punto de la lista lo marca y centra " +
            "el mapa ahí.",
    ),
    HelpSection(
        "⚙️", "Ajustes",
        "Tema claro/oscuro/automático y color de acento (predefinido o personalizado con la rueda " +
            "de color). Frecuencia de actualización de ubicación y wifis de casa (los dos puntos " +
            "siguientes). Sonido y patrón de vibración para \"hacer sonar el dispositivo\". " +
            "Notificaciones por tipo. Los administradores tienen además un botón para enviar una " +
            "notificación de prueba a todo el grupo (incluido a ellos mismos), útil para comprobar " +
            "que todo funciona.",
    ),
    HelpSection(
        "⏱️", "Cada cuánto se actualiza tu posición",
        "En Ajustes eliges el ritmo: tiempo real, cada 30 segundos, cada minuto (el normal), cada " +
            "2, cada 5 o cada 10 minutos, solo bajo demanda, o desactivado.\n\n" +
            "No hace falta que te pelees con esto para que la batería aguante: la app se apaña " +
            "sola. En cuanto nota que el móvil lleva un rato quieto, pasa a mirar la posición cada " +
            "15 minutos y sin encender el GPS; al moverte vuelve al ritmo que elegiste en el acto. " +
            "Por eso el tiempo real ya no se come la batería mientras estás en casa o sentado en la " +
            "oficina: solo gasta cuando de verdad te estás moviendo.\n\n" +
            "\"Solo bajo demanda\" es el modo de menos gasto que sigue siendo localizable: tu móvil " +
            "no manda nada por su cuenta, pero si alguien pulsa 🔄 en tu ficha responde al momento " +
            "con tu posición exacta. A cambio, con este modo no llegan avisos de zona (no hay " +
            "posiciones que comprobar). \"Desactivado\" no responde ni a eso: nadie te ve.\n\n" +
            "Cuanto más espaciado sea el ritmo, más tarde pueden llegar los avisos de entrada y " +
            "salida de zona. Para notar que estás quieto, la app usa el permiso de \"actividad " +
            "física\" que se pide al instalar: solo lee el sensor de movimiento, nunca qué estás " +
            "haciendo. Si lo rechazas, todo sigue funcionando igual, solo gasta más batería.",
    ),
    HelpSection(
        "📶", "Wifi de casa",
        "En Ajustes → \"Wifi de casa\" puedes añadir las redes donde sueles estar parado (casa, " +
            "trabajo, casa de los abuelos). Mientras estés conectado a una de ellas, tu móvil " +
            "espacia las actualizaciones a una cada 15 minutos: si estás en casa, no hace falta " +
            "preguntar cada minuto dónde estás. Al salir y empezar a moverte vuelve solo al ritmo " +
            "normal.\n\n" +
            "Para añadir una: conéctate a esa wifi y pulsa \"Añadir el wifi actual\". Si el botón " +
            "sale apagado, comprueba que tienes la ubicación del sistema encendida y el permiso de " +
            "ubicación concedido — Android no deja leer el nombre de la red sin eso. La papelera de " +
            "al lado quita la que ya no quieras.",
    ),
)

@Composable
fun HelpDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.94f).fillMaxSize(0.88f),
            shape = MaterialTheme.shapes.large,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Cómo funciona Lokate", style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
                HorizontalDivider()

                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                    items(sections) { section ->
                        Spacer(Modifier.height(18.dp))
                        Text("${section.emoji}  ${section.title}", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(section.body, style = MaterialTheme.typography.bodyMedium)
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                    item { AppFooter() }
                }
            }
        }
    }
}
