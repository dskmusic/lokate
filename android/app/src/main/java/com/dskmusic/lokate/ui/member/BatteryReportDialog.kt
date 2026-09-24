package com.dskmusic.lokate.ui.member

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dskmusic.lokate.R
import com.dskmusic.lokate.data.remote.dto.BatteryReportDto
import com.dskmusic.lokate.location.BatteryStats
import com.dskmusic.lokate.util.ConfigCheck
import com.dskmusic.lokate.util.LocationFrequency
import com.dskmusic.lokate.util.formatTimestamp

/**
 * El informe de batería de otro móvil, tal y como lo ve un admin desde la ficha de ese miembro.
 *
 * Arriba, lo que más ha gastado; en medio, los números de los que sale; abajo, si eso es lo
 * normal o hay algo que arreglar. El orden es a propósito: el que abre esto quiere el veredicto,
 * y los números están para poder discutirlo.
 *
 * Lo que se enseña arriba es una ESTIMACIÓN: Android no deja leer los mAh reales de una app
 * (ni a ella misma), así que se multiplica lo que la app hizo por un consumo típico de cada cosa.
 * Sirve para ordenar de mayor a menor y para ver bultos raros, no para dar un dato exacto.
 *
 * Y hay dos sujetos distintos en la misma pantalla, que es lo que más despista: el %/h y los
 * puntos de batería son del MÓVIL ENTERO (pantalla, otras apps, sistema), y los mAh y los
 * porcentajes del reparto de arriba son solo de esta app. Cada fila lo dice con todas las letras
 * a propósito: antes el veredicto acababa con un "gasto alto: 9%/h" que parecía acusar a la app
 * tres líneas después de decir que la app ponía el 3%.
 */
@Composable
fun BatteryReportDialog(
    memberName: String,
    report: BatteryReportDto,
    receivedAt: String?,
    stale: Boolean,
    onDismiss: () -> Unit,
) {
    val measuredMs = report.live_ms + report.move_ms + report.idle_ms + report.off_ms
    val hours = report.period_ms / 3_600_000.0
    val drop = if (report.battery_start_pct >= 0 && report.battery_now_pct >= 0) {
        report.battery_start_pct - report.battery_now_pct
    } else {
        null
    }
    val dropPerHour = if (drop != null && hours >= MIN_HOURS_FOR_RATE) drop / hours else null
    val consumers = remember(report) { consumers(report) }
    val estimatedMah = consumers.sumOf { it.second }
    val appPct = appShare(estimatedMah, drop)
    // Lo mismo que [appPct] pero sobre la batería entera en vez de sobre lo que bajó: "el 1% de
    // una bajada" no dice si la bajada fue de treinta puntos o de dos, y este número sí.
    val appPerHour = if (hours >= MIN_HOURS_FOR_RATE) estimatedMah / TYPICAL_BATTERY_MAH * 100.0 / hours else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.battery_report_title, memberName)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (stale) {
                    Text(
                        stringResource(R.string.battery_report_stale),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                receivedAt?.let {
                    Text(
                        stringResource(R.string.battery_report_received, formatTimestamp(it)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // ---------------------------------------------------------- lo que más gasta
                Section(stringResource(R.string.battery_top_title))
                if (estimatedMah <= 0.0) {
                    Text(
                        stringResource(R.string.battery_top_empty),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(
                        stringResource(R.string.battery_top_caption, estimatedMah.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    consumers.take(TOP_CONSUMERS).forEachIndexed { index, (labelRes, mah) ->
                        val pct = (mah / estimatedMah * 100).toInt()
                        Text(
                            stringResource(
                                R.string.battery_top_row,
                                index + 1,
                                stringResource(labelRes),
                                pct,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                // ------------------------------------------------------------------- periodo
                Section(stringResource(R.string.battery_section_period))
                // Si el periodo no empieza en un cargador (app recién instalada, por ejemplo) se
                // dice así: prometer "desde la última carga" un rato que no lo es despista más
                // que no decir nada.
                Line(
                    if (report.period_from_charge) R.string.battery_row_since_charge else R.string.battery_row_since_measure,
                    duration(report.period_ms),
                )
                if (drop != null) {
                    Line(
                        R.string.battery_row_battery,
                        stringResource(
                            R.string.battery_value_battery,
                            report.battery_start_pct,
                            report.battery_now_pct,
                            drop,
                        ),
                    )
                }
                dropPerHour?.let { Line(R.string.battery_row_drop_rate, decimal(it) + " %/h") }
                Line(
                    R.string.battery_row_app_estimate,
                    stringResource(
                        R.string.battery_value_app_estimate,
                        estimatedMah.toInt(),
                        appPct,
                    ),
                )
                if (appPerHour != null && estimatedMah > 0.0) {
                    Line(R.string.battery_row_app_rate, "~" + decimal(appPerHour, APP_RATE_DECIMALS) + " %/h")
                }
                if (report.temperature_c > 0) Line(R.string.battery_row_temperature, decimal(report.temperature_c) + " °C")
                if (report.is_charging) Line(R.string.battery_row_charging, stringResource(R.string.battery_value_yes))

                // -------------------------------------------------------------------- tiempo
                Section(stringResource(R.string.battery_section_time))
                Line(R.string.battery_row_idle, share(report.idle_ms, measuredMs))
                Line(R.string.battery_row_moving, share(report.move_ms, measuredMs))
                Line(R.string.battery_row_live, share(report.live_ms, measuredMs))
                Line(R.string.battery_row_service_off, share(report.off_ms, measuredMs))
                if (report.screen_on_ms > 0L) {
                    Line(R.string.battery_row_screen, share(report.screen_on_ms, measuredMs))
                }
                Line(R.string.battery_row_gps_high, duration(report.gps_high_ms))
                Line(R.string.battery_row_gps_balanced, duration(report.gps_balanced_ms))
                Line(
                    R.string.battery_row_measured,
                    stringResource(R.string.battery_value_measured, duration(measuredMs), duration(report.period_ms)),
                )
                // El resto del periodo: proceso muerto o dormido tan profundo que no corrió ni el
                // worker. No es de ningún modo, y sin esta fila los números de arriba parecerían
                // cubrir todo el periodo.
                val unmeasuredMs = (report.period_ms - measuredMs).coerceAtLeast(0L)
                if (unmeasuredMs > MIN_UNMEASURED_MS) {
                    Line(R.string.battery_row_unmeasured, share(unmeasuredMs, report.period_ms))
                }

                // ----------------------------------------------------------------- actividad
                Section(stringResource(R.string.battery_section_activity))
                Line(
                    R.string.battery_row_fixes,
                    stringResource(R.string.battery_value_fixes, report.fixes_ok, report.fixes_dropped),
                )
                Line(
                    R.string.battery_row_pings,
                    stringResource(R.string.battery_value_pings, report.pings_ok, report.pings_failed),
                )
                Line(R.string.battery_row_one_shots, report.one_shots.toString())
                Line(R.string.battery_row_live_sessions, report.live_sessions.toString())
                Line(R.string.battery_row_worker, report.worker_runs.toString())
                Line(
                    R.string.battery_row_geofences,
                    stringResource(R.string.battery_value_geofences, report.geofence_events, report.geofence_registers),
                )
                Line(R.string.battery_row_pushes, report.pushes.toString())

                // ------------------------------------------------------------------- sistema
                Section(stringResource(R.string.battery_section_system))
                Line(R.string.battery_row_frequency, frequencyLabel(report.frequency))
                Line(R.string.battery_row_mode, stringResource(modeLabel(report.mode)))
                Line(R.string.battery_row_service, stringResource(yesNo(report.service_running)))
                Line(R.string.battery_row_bucket, stringResource(bucketLabel(report.standby_bucket)))
                Line(
                    R.string.battery_row_optimizations,
                    stringResource(
                        if (report.ignoring_battery_optimizations) {
                            R.string.battery_value_exempt
                        } else {
                            R.string.battery_value_not_exempt
                        },
                    ),
                )
                if (report.power_save) Line(R.string.battery_row_power_save, stringResource(R.string.battery_value_yes))
                if (report.device_idle) Line(R.string.battery_row_doze, stringResource(R.string.battery_value_yes))
                if (report.exit_count > 0) {
                    Line(
                        R.string.battery_row_exits,
                        stringResource(
                            R.string.battery_value_exits,
                            report.exit_count,
                            report.last_exit_description ?: "-",
                        ),
                    )
                }
                pendingIssues(report.config_issues).takeIf { it.isNotEmpty() }?.forEach { issueRes ->
                    Text(
                        "• " + stringResource(issueRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Line(R.string.battery_row_device, listOfNotNull(report.device, report.app_version).joinToString(" · "))

                // ----------------------------------------------------------------- veredicto
                Section(stringResource(R.string.battery_section_verdict))
                verdict(report, measuredMs, dropPerHour, appPct, appPerHour ?: 0.0).forEach {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun Line(labelRes: Int, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * El veredicto de abajo del todo: primero lo que está fallando (que explica gastos raros mucho
 * mejor que cualquier número), como mucho [MAX_VERDICT] cosas; después, siempre, cómo va la
 * bajada del móvil; y al final la pantalla, si tiene algo que decir (ver [screenNote]). Es una
 * guía para decidir qué tocar, no un informe dentro del informe.
 */
@Composable
private fun verdict(
    report: BatteryReportDto,
    measuredMs: Long,
    dropPerHour: Double?,
    appPct: Int,
    appPerHour: Double,
): List<String> {
    val frequency = runCatching { LocationFrequency.valueOf(report.frequency.orEmpty()) }.getOrNull()
    val unmeasuredMs = report.period_ms - measuredMs
    val problems = buildList {
        // Va primero: si falta la mitad del periodo, cualquier otra conclusión es sobre lo que
        // se midió, no sobre el día entero.
        if (report.period_ms > 0 && unmeasuredMs * 100 / report.period_ms >= UNMEASURED_SHARE_PCT) {
            add(stringResource(R.string.battery_verdict_unmeasured, duration(unmeasuredMs)))
        }
        if (!report.ignoring_battery_optimizations) add(stringResource(R.string.battery_verdict_optimizations))
        if (report.standby_bucket >= BUCKET_RARE) add(stringResource(R.string.battery_verdict_bucket))
        if (report.exit_count >= MANY_EXITS) add(stringResource(R.string.battery_verdict_exits, report.exit_count))
        // El servicio parado solo es un problema si su modo lo necesita: en "solo bajo demanda"
        // y en "deshabilitado" está parado a propósito.
        if (!report.service_running && frequency?.sendsPeriodicUpdates == true) {
            add(stringResource(R.string.battery_verdict_service_off))
        }
        if (report.pings_failed > report.pings_ok && report.pings_failed > FEW_PINGS) {
            add(stringResource(R.string.battery_verdict_pings))
        }
        if (measuredMs > 0 && report.gps_high_ms * 100 / measuredMs >= GPS_HIGH_SHARE_PCT) {
            add(stringResource(R.string.battery_verdict_gps, (report.gps_high_ms * 100 / measuredMs).toInt()))
        }
    }
    val rate = when {
        dropPerHour == null -> stringResource(R.string.battery_verdict_rate_unknown)
        dropPerHour >= HIGH_DROP_PER_HOUR -> stringResource(
            R.string.battery_verdict_rate_high, decimal(dropPerHour), appPct, decimal(appPerHour, APP_RATE_DECIMALS),
        )
        dropPerHour <= LOW_DROP_PER_HOUR -> stringResource(
            R.string.battery_verdict_rate_low, decimal(dropPerHour), appPct, decimal(appPerHour, APP_RATE_DECIMALS),
        )
        else -> stringResource(
            R.string.battery_verdict_rate_normal, decimal(dropPerHour), appPct, decimal(appPerHour, APP_RATE_DECIMALS),
        )
    }
    val screen = screenNote(report, measuredMs, dropPerHour)
    return if (problems.isEmpty()) {
        listOfNotNull(stringResource(R.string.battery_verdict_ok), rate, screen)
    } else {
        problems.take(MAX_VERDICT) + listOfNotNull(rate, screen)
    }
}

/**
 * La línea que cierra el veredicto cuando la pantalla tiene algo que decir: o se llevó la bajada,
 * o no se encendió casi nada y aun así el móvil se vació (y entonces hay algo corriendo por
 * detrás que no es esta app, porque lo que pone esta app está dos líneas más arriba).
 *
 * Un cero no se comenta nunca: puede ser "no se encendió en tres horas" o "ese móvil lleva una
 * versión anterior a esto", y no hay forma de distinguirlos desde aquí.
 */
@Composable
private fun screenNote(report: BatteryReportDto, measuredMs: Long, dropPerHour: Double?): String? {
    if (report.screen_on_ms <= 0L || measuredMs <= 0L) return null
    val pct = (report.screen_on_ms * 100 / measuredMs).toInt()
    return when {
        pct >= SCREEN_MOST_PCT ->
            stringResource(R.string.battery_verdict_screen_high, duration(report.screen_on_ms), pct)
        // Poca pantalla solo es noticia si además se fue la batería: si no se fue, es un móvil
        // en el bolsillo portándose bien.
        pct <= SCREEN_LITTLE_PCT && (dropPerHour ?: 0.0) >= HIGH_DROP_PER_HOUR ->
            stringResource(R.string.battery_verdict_screen_low, pct)
        else -> null
    }
}

/**
 * Reparto estimado del gasto, de mayor a menor.
 *
 * El modo ahorro se cobra POR POSICIÓN y no por tiempo, que es la diferencia entre lo que la app
 * tiene pedido y lo que el móvil gasta de verdad: con el móvil quieto el servicio alarga el
 * intervalo a quince minutos, y multiplicar tres horas de suscripción por unos miliamperios fijos
 * le daba el 69% del reparto a once posiciones. El GPS fino sí va por tiempo: ahí el chip está
 * encendido de verdad todo el rato que se mide.
 *
 * ponytail: los miliamperios de cada cosa son los típicos de un móvil de gama media, no los de
 * ESTE móvil — el dato real (mAh por app) es privilegiado y no hay forma de leerlo. Si algún
 * día hay que afinarlo, se tocan estas constantes y ya: son el mando de calibración. Las
 * posiciones del rato en directo se cuentan dos veces (por fix y dentro del tiempo de GPS fino);
 * son segundos contra horas y no mueven el orden de la lista.
 */
private fun consumers(report: BatteryReportDto): List<Pair<Int, Double>> = listOf(
    R.string.battery_consumer_gps_high to report.gps_high_ms / 3_600_000.0 * MA_GPS_HIGH,
    R.string.battery_consumer_gps_balanced to (report.fixes_ok + report.fixes_dropped) * MAH_FIX_BALANCED,
    R.string.battery_consumer_pings to (report.pings_ok + report.pings_failed) * MAH_PING,
    R.string.battery_consumer_one_shots to report.one_shots * MAH_ONE_SHOT,
    R.string.battery_consumer_worker to report.worker_runs * MAH_WORKER,
    R.string.battery_consumer_geofences to
        report.geofence_registers * MAH_GEOFENCE_REGISTER + report.geofence_events * MAH_GEOFENCE_EVENT,
    R.string.battery_consumer_pushes to report.pushes * MAH_PUSH,
).filter { it.second > 0.0 }.sortedByDescending { it.second }

/** Qué parte de lo que ha bajado la batería DEL MÓVIL explica la app, en porcentaje. Sale de suponer una
 * batería de [TYPICAL_BATTERY_MAH]: el tamaño real tampoco se puede leer, y para "¿es la app o
 * es el móvil?" sobra con el orden de magnitud. */
private fun appShare(estimatedMah: Double, drop: Int?): Int {
    if (drop == null || drop <= 0) return 0
    return (estimatedMah / (drop * TYPICAL_BATTERY_MAH / 100.0) * 100).toInt().coerceIn(0, 100)
}

@Composable
private fun share(ms: Long, totalMs: Long): String =
    if (totalMs <= 0L) duration(ms) else stringResource(R.string.battery_value_share, duration(ms), (ms * 100 / totalMs).toInt())

/**
 * La frecuencia del ajuste y, entre paréntesis, la de verdad cuando el móvil lleva rato quieto:
 * ahí el servicio pide como mucho una posición cada [IDLE_INTERVAL_MS] por muy corto que sea el
 * ajuste. Sin esto el informe pone "cada 30 segundos" justo encima de
 * diez posiciones en tres horas y parece que algo va roto, cuando es exactamente lo previsto.
 */
@Composable
private fun frequencyLabel(name: String?): String {
    val frequency = runCatching { LocationFrequency.valueOf(name.orEmpty()) }.getOrNull() ?: return name.orEmpty()
    val label = stringResource(frequency.labelRes)
    if (!frequency.sendsPeriodicUpdates || frequency.intervalMs >= IDLE_INTERVAL_MS) return label
    return label + stringResource(R.string.battery_frequency_idle_note, duration(IDLE_INTERVAL_MS))
}

private fun modeLabel(mode: String?): Int = when (mode) {
    BatteryStats.MODE_LIVE -> R.string.battery_mode_live
    BatteryStats.MODE_MOVE -> R.string.battery_mode_move
    BatteryStats.MODE_IDLE -> R.string.battery_mode_idle
    else -> R.string.battery_mode_off
}

private fun bucketLabel(bucket: Int): Int = when {
    bucket <= 0 -> R.string.battery_bucket_unknown
    bucket <= 10 -> R.string.battery_bucket_active
    bucket <= 20 -> R.string.battery_bucket_working
    bucket <= 30 -> R.string.battery_bucket_frequent
    bucket <= 40 -> R.string.battery_bucket_rare
    else -> R.string.battery_bucket_restricted
}

private fun yesNo(value: Boolean): Int = if (value) R.string.battery_value_running else R.string.battery_value_stopped

/** Los mismos códigos de ConfigCheck que ya se pintan en la ficha, traducidos aquí también:
 * media docena de permisos denegados explican más consumo raro que cualquier contador. */
private fun pendingIssues(issues: String?): List<Int> =
    issues.orEmpty().split(",").mapNotNull { code ->
        when (code.trim()) {
            ConfigCheck.LOCATION -> R.string.config_issue_location
            ConfigCheck.BACKGROUND_LOCATION -> R.string.config_issue_bg_location
            ConfigCheck.NOTIFICATIONS -> R.string.config_issue_notifications
            ConfigCheck.NOTIFICATION_CHANNEL -> R.string.config_issue_notif_channel
            ConfigCheck.BATTERY -> R.string.config_issue_battery
            ConfigCheck.DND -> R.string.config_issue_dnd
            ConfigCheck.ACTIVITY -> R.string.config_issue_activity
            ConfigCheck.GPS_OFF -> R.string.config_issue_gps_off
            else -> null
        }
    }

private fun duration(ms: Long): String {
    if (ms <= 0L) return "0 min"
    val minutes = ms / 60_000L
    return when {
        minutes >= 60L -> "${minutes / 60} h ${minutes % 60} min"
        minutes > 0L -> "$minutes min"
        else -> "${ms / 1000L} s"
    }
}

private fun decimal(value: Double, digits: Int = 1): String =
    String.format(java.util.Locale.getDefault(), "%." + digits + "f", value)

/** El ritmo de reposo del móvil del OTRO, que es de quien habla el informe; por eso no se lee de
 * LocationForegroundService, que es el de este (y además lo tiene privado). Mismo caso que en
 * ui/common/UpdateStatus.kt: coinciden salvo que esa persona lleve una versión
 * distinta de la app, y entonces lo único que pasa es que el paréntesis de la fila de frecuencia
 * dice cinco minutos de más o de menos. */
private const val IDLE_INTERVAL_MS = 15 * 60_000L

/** El ritmo de la app va en centésimas: con un decimal, cualquier uso normal sale "0,1 %/h" o
 * directamente "0,0 %/h", que es justo la precisión que hace falta para creerse el dato. */
private const val APP_RATE_DECIMALS = 2

private const val TOP_CONSUMERS = 3
private const val MAX_VERDICT = 3

/** Consumo típico de cada cosa, el mando de calibración de [consumers]. El GPS fino en mA
 * (está encendido todo el rato que se mide); el modo ahorro en mAh por posición conseguida. */
private const val MA_GPS_HIGH = 90.0
private const val MAH_FIX_BALANCED = 0.6
private const val MAH_PING = 0.25
private const val MAH_ONE_SHOT = 0.5
private const val MAH_WORKER = 0.15
private const val MAH_GEOFENCE_REGISTER = 0.1
private const val MAH_GEOFENCE_EVENT = 0.2
private const val MAH_PUSH = 0.05
private const val TYPICAL_BATTERY_MAH = 4000.0

/** Un minuto de descuadre entre el periodo y lo medido es redondeo; a partir de ahí, la app
 * estuvo parada o dormida y hay que decirlo. */
private const val MIN_UNMEASURED_MS = 60_000L
private const val UNMEASURED_SHARE_PCT = 25

/** Cuánta pantalla es "ahí se fue la batería" y cuánta es "ese móvil ni se ha tocado". */
private const val SCREEN_MOST_PCT = 25
private const val SCREEN_LITTLE_PCT = 5

/** A partir de dónde se dice que algo va mal. */
private const val BUCKET_RARE = 40
private const val MANY_EXITS = 3
private const val FEW_PINGS = 5
private const val GPS_HIGH_SHARE_PCT = 40
private const val HIGH_DROP_PER_HOUR = 8.0
private const val LOW_DROP_PER_HOUR = 3.0

/** Por debajo de media hora, el %/h es ruido: se queda sin calcular. */
private const val MIN_HOURS_FOR_RATE = 0.5
