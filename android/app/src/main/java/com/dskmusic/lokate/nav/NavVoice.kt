package com.dskmusic.lokate.nav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * La voz de la navegación, con el motor de texto a voz que tenga puesto el móvil. No se instala
 * nada ni se baja nada: se usa el del sistema, que es el que el usuario ya ha elegido en los
 * ajustes de Android y el que tiene las voces descargadas.
 *
 * Dos cosas que no son adorno:
 *  - las indicaciones se marcan como "guía de navegación": con eso el sistema le baja el volumen
 *    a la música en vez de pisarla, y en el coche salen por el manos libres y no por el altavoz;
 *  - el foco de audio se pide al hablar y se suelta al terminar. Mantenerlo todo el viaje
 *    dejaría la música agachada de principio a fin.
 */
class NavVoice(
    context: Context,
    enginePackage: String = "",
    private val onReady: () -> Unit = {},
) {

    private val audio = context.getSystemService(AudioManager::class.java)

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest
        .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    private var engine: TextToSpeech? = null
    private val pending = AtomicInteger(0)
    private var holdingFocus = false

    /** El motor ya está en pie. Hasta entonces [say] no dice nada: hablar antes de esto no
     * encola, se pierde. */
    var ready = false
        private set

    init {
        // El paquete va en el constructor y no hay forma de cambiarlo después: para otro motor,
        // otra instancia. Nulo = el que el usuario tenga puesto en los ajustes de Android.
        engine = TextToSpeech(
            context.applicationContext,
            { status ->
                ready = status == TextToSpeech.SUCCESS
                onReady()
            },
            enginePackage.ifBlank { null },
        )
    }

    /** Los motores de texto a voz instalados (el de Google, el del fabricante, alguno que se haya
     * bajado el usuario). La lista es la misma salga de la instancia que salga. */
    fun engines(): List<TextToSpeech.EngineInfo> = runCatching {
        engine?.engines.orEmpty().sortedBy { it.label }
    }.getOrDefault(emptyList())

    /** El motor que tiene puesto el usuario en los ajustes de Android, que es el que se enlaza
     * cuando la preferencia de la app está vacía: hay que saber cuál es para marcarlo en la lista.
     * (El que esta instancia tiene enlazado no se puede preguntar: getCurrentEngine no es público.) */
    fun defaultEngine(): String = runCatching { engine?.defaultEngine.orEmpty() }.getOrDefault("")

    /** Las voces instaladas para el idioma de la app, que son las que tiene sentido ofrecer.
     * Vacío = el motor aún no está listo, o no hay ninguna (hay móviles así). */
    fun voices(): List<Voice> {
        val tts = engine ?: return emptyList()
        val lang = Locale.getDefault().language
        // runCatching: tts.voices revienta en algunos motores de fabricante en vez de devolver
        // vacío, y quedarse sin selector de voz es mejor que llevarse la pantalla por delante.
        return runCatching {
            tts.voices.orEmpty()
                .filter { it.locale.language == lang }
                .sortedBy { it.name }
        }.getOrDefault(emptyList())
    }

    /** Voz, velocidad y tono. [voiceName] vacío = la que traiga el motor por defecto. */
    fun configure(voiceName: String, rate: Float, pitch: Float) {
        val tts = engine ?: return
        runCatching {
            tts.setAudioAttributes(attributes)
            tts.language = Locale.getDefault()
            tts.setSpeechRate(rate)
            tts.setPitch(pitch)
            if (voiceName.isNotBlank()) {
                tts.voices.orEmpty().find { it.name == voiceName }?.let { tts.voice = it }
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = finished()
                @Deprecated("Lo pide la clase abstracta; el que se usa es el de abajo")
                override fun onError(utteranceId: String?) = finished()
                override fun onError(utteranceId: String?, errorCode: Int) = finished()
            })
        }
    }

    /**
     * Lo dice. [urgent] vacía la cola: un "gire ya" no puede esperar a que termine un
     * "continúe dos kilómetros por la nacional" que ya no sirve para nada.
     */
    fun say(text: String, urgent: Boolean = false) {
        val tts = engine ?: return
        if (!ready || text.isBlank()) return
        requestFocus()
        pending.incrementAndGet()
        val mode = if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (tts.speak(text, mode, null, UTTERANCE_ID) != TextToSpeech.SUCCESS) finished()
    }

    fun shutdown() {
        runCatching {
            engine?.stop()
            engine?.shutdown()
        }
        engine = null
        ready = false
        pending.set(0)
        abandonFocus()
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            abandonFocus()
        }
    }

    private fun requestFocus() {
        if (holdingFocus) return
        holdingFocus = audio?.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        if (!holdingFocus) return
        holdingFocus = false
        audio?.abandonAudioFocusRequest(focusRequest)
    }

    private companion object {
        const val UTTERANCE_ID = "lokate_nav"
    }
}
