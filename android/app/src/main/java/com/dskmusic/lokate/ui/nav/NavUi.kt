package com.dskmusic.lokate.ui.nav

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.ForkLeft
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.prefs.SettingsDataStore
import com.dskmusic.lokate.data.remote.dto.RouteStepDto
import com.dskmusic.lokate.nav.NavState
import com.dskmusic.lokate.nav.NavVerbosity
import com.dskmusic.lokate.nav.NavVoice
import com.dskmusic.lokate.ui.common.apaisado
import com.dskmusic.lokate.ui.common.cabe
import com.dskmusic.lokate.ui.common.cuadroAncho
import com.dskmusic.lokate.ui.common.propiedadesCuadro
import com.dskmusic.lokate.ui.map.PlannedRoute
import com.dskmusic.lokate.ui.map.summary
import com.dskmusic.lokate.util.formatDistance
import com.dskmusic.lokate.util.formatDuration
import java.util.Date
import kotlinx.coroutines.launch

/**
 * La flecha de la maniobra, según el código de ORS (0 izquierda, 1 derecha, 6 recto, 7/8
 * rotonda, 9 cambio de sentido, 10 llegada, 11 salida, 12/13 mantenerse). Un código que no esté
 * en la lista pinta la flecha genérica: es un dato que llega de fuera, y una ruta por un país
 * con maniobras raras no se queda sin dibujo por eso.
 */
fun maneuverIcon(type: Int): ImageVector = when (type) {
    0 -> Icons.Filled.TurnLeft
    1 -> Icons.Filled.TurnRight
    2 -> Icons.Filled.TurnSharpLeft
    3 -> Icons.Filled.TurnSharpRight
    4 -> Icons.Filled.TurnSlightLeft
    5 -> Icons.Filled.TurnSlightRight
    6 -> Icons.Filled.Straight
    // ORS no dice en qué sentido gira la rotonda; se pinta la de circulación por la derecha.
    7, 8 -> Icons.Filled.RoundaboutRight
    9 -> Icons.Filled.UTurnLeft
    10 -> Icons.Filled.Flag
    12 -> Icons.Filled.ForkLeft
    13 -> Icons.Filled.ForkRight
    else -> Icons.Filled.Navigation
}

/**
 * El cartel de arriba mientras se navega: la flecha, a cuánto está la maniobra y por dónde.
 *
 * Lo que manda es el tamaño: esto se lee de un vistazo con el móvil en el soporte, así que la
 * distancia va grande y la calle pequeña, y no al revés.
 */
@Composable
fun NavBanner(state: NavState, modifier: Modifier = Modifier, compacto: Boolean = false) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = if (compacto) 8.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                maneuverIcon(state.maneuver),
                contentDescription = null,
                modifier = Modifier.size(if (compacto) 34.dp else 44.dp).padding(end = 12.dp),
            )
            Column(Modifier.weight(1f)) {
                val cabecera = when {
                    state.arrived -> stringResource(R.string.nav_arrived)
                    state.recalculating -> stringResource(R.string.nav_recalculating)
                    state.offRoute -> stringResource(R.string.nav_off_route)
                    else -> stringResource(
                        R.string.nav_in,
                        formatDistance(state.metersToManeuver.toFloat()),
                    )
                }
                Text(
                    cabecera,
                    style = if (compacto) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!state.arrived) {
                    Text(state.instruction, style = MaterialTheme.typography.bodyMedium)
                    // El "después" se queda incluso compacto: solo sale con la maniobra encima,
                    // y es cuando hace falta saber que detrás del giro viene otro.
                    if (state.nextInstruction.isNotBlank()) {
                        Text(
                            stringResource(R.string.nav_then, state.nextInstruction),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    // La calle solo si la tiene: las de ORS sin nombre llegan vacías a propósito.
                    // Compacto se cae: es el dato que menos falta hace y son 18 dp de mapa.
                    if (!compacto && state.street.isNotBlank()) {
                        Text(state.street, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** La barra de abajo: lo que queda, la hora de llegada y los cuatro botones del viaje.
 *
 * La pausa ocupa el sitio que tenían los ajustes: en marcha es lo que se busca con el móvil en
 * el soporte, y los ajustes siguen a un toque de distancia dentro de los pasos de la ruta. */
@Composable
fun NavBottomBar(
    state: NavState,
    paused: Boolean,
    onMute: () -> Unit,
    onPause: () -> Unit,
    onSteps: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Tumbado se aprieta el alto: lo mismo, pero sin aire de sobra. */
    compacto: Boolean = false,
) {
    val context = LocalContext.current
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(
                start = 16.dp,
                end = 4.dp,
                top = if (compacto) 2.dp else 8.dp,
                bottom = if (compacto) 2.dp else 8.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    formatDistance(state.remainingMeters.toFloat()) + " · " +
                        formatDuration(state.remainingSeconds / 60),
                    style = MaterialTheme.typography.titleMedium,
                )
                // La hora de llegada con el formato del móvil (12 h o 24 h, el que tenga puesto).
                val llegada = DateFormat.getTimeFormat(context)
                    .format(Date(System.currentTimeMillis() + state.remainingSeconds * 1_000L))
                Text(
                    // En pausa se dice, que si no la hora de llegada miente: no se acerca.
                    if (paused) stringResource(R.string.nav_paused) else {
                        stringResource(R.string.nav_eta, llegada) +
                            if (state.simulating) " · " + stringResource(R.string.nav_simulating) else ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onMute) {
                Icon(
                    if (state.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = stringResource(
                        if (state.muted) R.string.nav_unmute else R.string.nav_mute,
                    ),
                )
            }
            IconButton(onClick = onPause) {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = stringResource(if (paused) R.string.nav_resume else R.string.nav_pause),
                )
            }
            IconButton(onClick = onSteps) {
                Icon(Icons.Filled.ListAlt, contentDescription = stringResource(R.string.nav_steps))
            }
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.nav_exit))
            }
        }
    }
}

/**
 * La lista de pasos de la ruta, y desde aquí se arranca a navegar o a simular.
 *
 * [canNavigate] en false = esta ruta la hace otro, no quien tiene el móvil. Entonces los pasos
 * se pueden leer (para decírselos por teléfono, que es justo para lo que sirve) pero no se
 * ofrece navegar: nadie puede ser guiado por voz por el móvil de otro.
 */
@Composable
fun RouteStepsDialog(
    route: PlannedRoute,
    canNavigate: Boolean,
    activeStep: Int?,
    onNavigate: () -> Unit,
    onSimulate: () -> Unit,
    onOptions: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tumbado = apaisado()
    // Quién ha trazado esta ruta, debajo del "a pie · 259 m · 3 min". Informativo y nada más:
    // con dos proveedores, saber cuál ha contestado es la diferencia entre "algo falla" y "el
    // principal está caído". Vacío = servidor anterior a esto, y entonces no se dice nada.
    val porQuien = route.provider.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.route_provider, it) }
    // La lista se mueve sola hasta donde se va ahora, con el paso anterior asomando por arriba
    // para saber de dónde se viene. Sin esto, en una ruta de veinte pasos el que está encendido
    // se queda fuera de la ventana y hay que buscarlo a mano cada vez que cambia.
    val estadoLista = rememberLazyListState()
    LaunchedEffect(activeStep) {
        activeStep?.let { estadoLista.animateScrollToItem((it - 1).coerceAtLeast(0)) }
    }
    // Los pasos: lo único que puede pedir scroll, porque una ruta larga no cabe en ningún sitio.
    val pasos: @Composable (Modifier) -> Unit = { mod ->
        LazyColumn(
            state = estadoLista,
            modifier = mod.heightIn(max = cabe(340.dp, if (tumbado) 0.45f else 0.55f)),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(route.steps) { index, step ->
                StepRow(step, activo = index == activeStep)
            }
        }
    }
    // Arrancar el viaje va dentro del cuadro y no abajo con los botones de texto: es lo que se
    // viene a hacer, y abajo se perdía entre "ajustes" y "cerrar".
    val botonSimular: @Composable (Modifier) -> Unit = { mod ->
        OutlinedButton(onClick = onSimulate, modifier = mod) {
            Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.nav_simulate))
        }
    }
    val botonNavegar: @Composable (Modifier) -> Unit = { mod ->
        if (canNavigate) {
            Button(onClick = onNavigate, modifier = mod) {
                Icon(Icons.Filled.Navigation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.nav_navigate))
            }
        }
    }
    // Ajustes y cerrar: de pie van abajo del cuadro, tumbado suben a la columna de la derecha
    // con los otros dos, que es donde está el pulgar y donde queda sitio.
    val botonesMenores: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onOptions) {
                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.settings_title))
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.cuadroAncho(),
        properties = propiedadesCuadro(),
        // Cabecera propia, distinta de los demás cuadros de la app: esto no pregunta nada, es
        // la hoja de ruta del viaje, y lo primero que se quiere saber es de cuánto va. Tumbado
        // se cae el icono y el resumen se pone en la misma línea: 60 dp de alto que se van a
        // la lista de pasos, que es lo que se ha venido a leer.
        icon = if (tumbado) null else {
            { Icon(Icons.Filled.Navigation, contentDescription = null) }
        },
        title = {
            if (tumbado) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(stringResource(R.string.nav_steps_title))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        route.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Tumbado no sobra alto: va en la misma línea, detrás del resumen.
                    porQuien?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.nav_steps_title))
                Text(
                    route.summary(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                porQuien?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            if (route.steps.isEmpty()) {
                Text(stringResource(R.string.nav_no_steps))
            } else if (tumbado) {
                // Tumbado: los pasos a la izquierda y todos los botones a la derecha. En fila
                // debajo eran los primeros en quedarse fuera de la pantalla, y de alto no sobra
                // nada: lo que no quepa ahí hay que ir a buscarlo con scroll.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pasos(Modifier.weight(1f))
                    // Ancho fijo a propósito: los botones de dentro van a lo ancho de la
                    // columna, y una columna que se mide por su contenido se lo toma como "todo
                    // el cuadro" y deja la lista de pasos en cero (ahí desaparecía).
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.width(190.dp).align(Alignment.CenterVertically),
                    ) {
                        botonSimular(Modifier.fillMaxWidth())
                        botonNavegar(Modifier.fillMaxWidth())
                        botonesMenores()
                    }
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                pasos(Modifier)
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    botonSimular(Modifier.weight(1f))
                    botonNavegar(Modifier.weight(1f))
                }
            }
        },
        // De pie, centrados abajo y "ajustes" con su palabra: un icono solo ahí no se
        // entendía. Tumbado este hueco va vacío porque los botones están arriba a la derecha.
        confirmButton = {
            if (!tumbado) botonesMenores()
        },
    )
}

/** Un paso de la ruta: la flecha en su chapa redonda, la indicación y debajo cuánto dura ese
 * trozo y por qué calle. El paso en el que se va ahora mismo se pinta encendido, que es como se
 * sigue la lista mientras se conduce sin tener que ir contando. */
@Composable
private fun StepRow(step: RouteStepDto, activo: Boolean) {
    Surface(
        color = if (activo) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (activo) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = if (activo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (activo) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = CircleShape,
            ) {
                Icon(
                    maneuverIcon(step.type),
                    contentDescription = null,
                    modifier = Modifier.padding(6.dp).size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    step.instruction,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (activo) FontWeight.Bold else null,
                )
                Text(
                    formatDistance(step.distance_m.toFloat()) +
                        if (step.name.isNotBlank()) " · " + step.name else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Las opciones de la voz: cuál, cómo habla, cuánto habla y a qué velocidad simula.
 *
 * Las voces son las del motor del sistema, filtradas al idioma de la app. El botón de probar
 * habla con la configuración que hay puesta en ese momento, que es la única forma de elegir una
 * voz y una velocidad sin salir a la calle a comprobarlo.
 */
@Composable
fun NavOptionsDialog(settings: SettingsDataStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voiceName by settings.navVoiceName.collectAsState(initial = "")
    val enginePkg by settings.navVoiceEngine.collectAsState(initial = "")
    val verbosity by settings.navVerbosity.collectAsState(initial = NavVerbosity.NORMAL)
    val simSpeed by settings.navSimulationSpeed.collectAsState(initial = 1)
    val rotateMap by settings.navRotateMap.collectAsState(initial = true)
    val savedRate by settings.navSpeechRate.collectAsState(initial = 1f)
    val savedPitch by settings.navSpeechPitch.collectAsState(initial = 1f)

    // Los deslizadores se mueven en local y se guardan al soltar: escribir en disco en cada
    // píxel del arrastre no tiene ningún sentido.
    var rate by remember(savedRate) { mutableStateOf(savedRate) }
    var pitch by remember(savedPitch) { mutableStateOf(savedPitch) }

    // Un motor de voz solo para este cuadro, para listar las voces y para probarlas. El de la
    // navegación es otro y sigue con lo suyo; dos instancias de TTS no se estorban.
    //
    // Va por [enginePkg]: el paquete del motor solo se puede dar al construir el TextToSpeech,
    // así que elegir otro motor tira esta instancia y hace otra (y con ella, otra lista de voces).
    var listo by remember(enginePkg) { mutableStateOf(false) }
    val voice = remember(enginePkg) { NavVoice(context, enginePkg) { listo = true } }
    DisposableEffect(voice) { onDispose { voice.shutdown() } }
    val motores = remember(voice, listo) { voice.engines() }
    // Qué motor marcar: la preferencia, y si está vacía ("el de Android") hay que preguntar cuál
    // es ese.
    val motorPorDefecto = remember(voice, listo) { voice.defaultEngine() }
    val motorActual = enginePkg.ifBlank { motorPorDefecto }
    val voces = remember(voice, listo) { voice.voices() }

    val tumbado = apaisado()
    // Lo que se ajusta de una vez: tumbado es la columna de la izquierda, de pie la primera
    // tanda de la lista.
    val ajustes: @Composable () -> Unit = {
        Column {
            Text(stringResource(R.string.nav_verbosity), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NavVerbosity.entries.forEach { nivel ->
                    FilterChip(
                        selected = nivel == verbosity,
                        onClick = { scope.launch { settings.setNavVerbosity(nivel) } },
                        label = { Text(stringResource(nivel.label)) },
                    )
                }
            }
            Text(
                stringResource(R.string.nav_rate, rate),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            Slider(
                value = rate,
                onValueChange = { rate = it },
                onValueChangeFinished = {
                    scope.launch { settings.setNavSpeech(rate, pitch) }
                    voice.configure(voiceName, rate, pitch)
                },
                valueRange = 0.5f..2f,
                steps = 5,
            )
            Text(stringResource(R.string.nav_pitch, pitch), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = pitch,
                onValueChange = { pitch = it },
                onValueChangeFinished = {
                    scope.launch { settings.setNavSpeech(rate, pitch) }
                    voice.configure(voiceName, rate, pitch)
                },
                valueRange = 0.5f..2f,
                steps = 5,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    stringResource(R.string.nav_sim_speed),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                listOf(1, 2, 4).forEach { x ->
                    FilterChip(
                        selected = x == simSpeed,
                        onClick = { scope.launch { settings.setNavSimulationSpeed(x) } },
                        label = { Text("×$x") },
                        leadingIcon = if (x == simSpeed) {
                            { Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(16.dp)) }
                        } else {
                            null
                        },
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(
                stringResource(R.string.nav_map),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.nav_rotate_map)) },
                supportingContent = {
                    Text(
                        stringResource(R.string.nav_rotate_map_desc),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                trailingContent = {
                    Switch(
                        checked = rotateMap,
                        onCheckedChange = { scope.launch { settings.setNavRotateMap(it) } },
                    )
                },
            )
        }
    }
    // El motor y sus voces son lo único que crece aquí (un móvil con el paquete entero del
    // sistema trae decenas), así que van en lista perezosa y aparte: tumbado son la columna de
    // la derecha. Dos listas, y en ese orden: la voz depende del motor que esté elegido arriba.
    val vocesItems: LazyListScope.() -> Unit = {
        item { EtiquetaVoz(stringResource(R.string.nav_voice_engine)) }
        items(motores.size) { i ->
            val m = motores[i]
            FilaVoz(m.label, m.name == motorActual) {
                // setNavVoiceEngine borra también la voz: los nombres son de cada motor y el de
                // uno no existe en el otro.
                scope.launch { settings.setNavVoiceEngine(m.name) }
            }
        }
        item { EtiquetaVoz(stringResource(R.string.nav_voice)) }
        item {
            FilaVoz(stringResource(R.string.nav_voice_default), voiceName.isBlank()) {
                scope.launch { settings.setNavVoiceName("") }
                voice.configure("", rate, pitch)
            }
        }
        // Hay motores sin ninguna voz instalada para el idioma. Decirlo es más útil que una
        // lista vacía: lo que hay que hacer es bajarla en los ajustes de Android.
        if (voces.isEmpty()) {
            item { Text(stringResource(R.string.nav_no_voices), style = MaterialTheme.typography.bodySmall) }
        }
        items(voces.size) { i ->
            val v = voces[i]
            FilaVoz(v.name, v.name == voiceName) {
                scope.launch { settings.setNavVoiceName(v.name) }
                voice.configure(v.name, rate, pitch)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Tumbado, ancho: es a dónde lleva el "ajustes" del cuadro de pasos y, estrecho, cada
        // interruptor se comía tres líneas de las pocas que caben.
        modifier = Modifier.cuadroAncho(),
        properties = propiedadesCuadro(),
        title = { Text(stringResource(R.string.nav_options_title)) },
        text = {
            if (tumbado) {
                // Dos columnas, y cada una con su scroll: en una sola, tumbado, no se llegaba
                // ni al primer deslizador sin arrastrar.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        modifier = Modifier.weight(1f)
                            .heightIn(max = cabe(460.dp, 0.5f))
                            .verticalScroll(rememberScrollState()),
                    ) { ajustes() }
                    LazyColumn(
                        modifier = Modifier.weight(1f).heightIn(max = cabe(460.dp, 0.5f)),
                        content = vocesItems,
                    )
                }
            } else LazyColumn(Modifier.heightIn(max = cabe(460.dp))) {
                item { ajustes() }
                vocesItems()
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    voice.configure(voiceName, rate, pitch)
                    voice.say(context.getString(R.string.nav_test_phrase), urgent = true)
                },
            ) { Text(stringResource(R.string.nav_test_voice)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

/** Cabecera de cada lista del cuadro de voz ("Motor", "Voz"). */
@Composable
private fun EtiquetaVoz(texto: String) {
    Text(
        texto,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 12.dp),
    )
}

/** Una fila de las listas de motor y de voz. Lo elegido va en el color de acento y con su marca:
 * entre nombres como "es-es-x-eed-local" es lo único que se distingue de un vistazo. */
@Composable
private fun FilaVoz(texto: String, elegido: Boolean, onClick: () -> Unit) {
    val acento = MaterialTheme.colorScheme.primary
    ListItem(
        headlineContent = {
            Text(
                texto,
                color = if (elegido) acento else Color.Unspecified,
                fontWeight = if (elegido) FontWeight.Bold else null,
            )
        },
        trailingContent = if (elegido) {
            { Icon(Icons.Filled.Check, contentDescription = null, tint = acento) }
        } else {
            null
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
