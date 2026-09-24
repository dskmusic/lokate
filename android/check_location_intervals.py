# -*- coding: utf-8 -*-
"""Comprueba el ritmo de ubicacion SIN compilar nada: lee las constantes reales de
LocationForegroundService.kt y simula los casos que provocaron el 41% de bateria en 12 horas.

Falla si alguno de ellos vuelve a caer por debajo de MIN_MOVE_FROM_INTERVAL_MS, que es el
umbral donde el sistema enciende el GPS fino, deja de agrupar entregas y deja de filtrar por
distancia -- las tres cosas caras a la vez.

Run (desde la carpeta android/):  python check_location_intervals.py
"""
import io
import os
import re

SRC = io.open(
    os.path.join(os.path.dirname(os.path.abspath(__file__)),
                 'app/src/main/java/com/dskmusic/lokate/location/LocationForegroundService.kt'),
    encoding='utf-8',
).read()

K = {}
for name, value in re.findall(r'const val (\w+) = ([0-9_.*\s]+)[LfF]?\n', SRC):
    K[name] = eval(value.replace('_', ''))

MIN_SAVER = K['MIN_MOVE_FROM_INTERVAL_MS']
BALANCED = 120_000  # LocationFrequency.BALANCED, el modo de las capturas


def moving_interval(configured, speed_mps, to_edge_m):
    """Espejo de LocationForegroundService.movingIntervalMs()."""
    interval = configured
    relaxable = configured >= MIN_SAVER
    if relaxable and speed_mps > K['MIN_SPEED_MPS']:
        by_speed = int(K['POINT_EVERY_METERS'] / speed_mps * 1000)
        interval = min(max(by_speed, K['MIN_SPEED_INTERVAL_MS']),
                       min(configured * 2, K['IDLE_INTERVAL_MS']))
    if to_edge_m <= K['NEAR_ZONE_METERS']:
        interval = min(interval, K['NEAR_ZONE_INTERVAL_MS'])
    elif relaxable and to_edge_m >= K['FAR_ZONE_METERS']:
        interval = max(interval, min(K['FAR_ZONE_INTERVAL_MS'], configured * 2))
    return interval


def worth_requesting(current, new):
    """Espejo de LocationForegroundService.worthReRequesting()."""
    if current <= 0:
        return True
    if current == new:
        return False
    if (current < MIN_SAVER) != (new < MIN_SAVER):
        return True
    return max(current, new) / min(current, new) >= K['INTERVAL_CHANGE_RATIO']


def fine_gps(interval):
    return interval < MIN_SAVER


# 1) En casa, dentro de una zona: el caso de las 12 h de GPS. "Cerca del borde" se mide en
#    valor absoluto, asi que estar dentro de la zona tambien lo cumple.
en_casa = moving_interval(BALANCED, speed_mps=0.0, to_edge_m=120)
assert not fine_gps(en_casa), en_casa
# Y a los STILL_AFTER_MS sin moverse entra el reposo deducido, mucho mas espaciado.
assert K['STATIONARY_INTERVAL_MS'] >= 5 * 60_000
assert K['STILL_AFTER_MS'] >= 5 * 60_000

# 2) Andando por el barrio, pegado al borde de una zona.
andando = moving_interval(BALANCED, speed_mps=1.4, to_edge_m=50)
assert not fine_gps(andando), andando

# 3) En coche a 90 km/h, lejos de cualquier zona y cruzando cerca de una.
coche_lejos = moving_interval(BALANCED, speed_mps=25.0, to_edge_m=5000)
coche_cerca = moving_interval(BALANCED, speed_mps=25.0, to_edge_m=200)
assert not fine_gps(coche_lejos) and not fine_gps(coche_cerca), (coche_lejos, coche_cerca)

# 4) "Tiempo real" SI enciende el GPS fino: es exactamente lo que se le pide.
assert fine_gps(3_000)

# 5) El desgaste de rehacer la peticion: diez fixes seguidos de un paseo, cada uno con su
#    velocidad. Antes se re-registraba en casi todos (y cada re-registro tira la sesion de
#    satelites en marcha); ahora solo cuando el ritmo cambia de verdad.
current, rehechas = -1, 0
for speed in [1.2, 1.35, 1.1, 1.4, 1.25, 1.3, 1.15, 1.45, 1.2, 1.38]:
    nuevo = moving_interval(BALANCED, speed, to_edge_m=5000)
    if worth_requesting(current, nuevo):
        current = nuevo
        rehechas += 1
assert rehechas == 1, rehechas

# 6) Pero entrar en reposo, salir de el o empezar un seguimiento en vivo se aplica siempre.
assert worth_requesting(BALANCED, K['IDLE_INTERVAL_MS'])
assert worth_requesting(K['STATIONARY_INTERVAL_MS'], 3_000)
assert worth_requesting(30_000, 3_000)

# 8) Reposo con datos moviles: la posicion la ponen las antenas y baila sola cientos de metros.
#    El ancla solo se mueve si el salto pasa del margen de error del fix, o un movil en una mesa
#    no se declara quieto nunca (justo lo que pasaba: salia "en movimiento" y detras "sin senal").
def stationary(fixes):
    """Espejo de trackStillness() + stationary. fixes = [(t_ms, salto_m, precision_m)]."""
    anchor_at, anchor_ok = None, False
    quieto_en = None
    for t, salto, precision in fixes:
        if anchor_at is None or salto >= max(K['IDLE_MIN_MOVE_METERS'], precision):
            anchor_at = t
        elif t - anchor_at >= K['STILL_AFTER_MS'] and quieto_en is None:
            quieto_en = t - anchor_at
    return quieto_en


# En la mesa 20 min, un fix de antena cada 2 min: 300 m de baile con 800 m de error.
mesa = [(i * BALANCED, 300 if i else 0, 800.0) for i in range(11)]
assert stationary(mesa) is not None, 'un movil quieto con datos moviles tiene que entrar en reposo'
# Andando de verdad: fixes finos que avanzan 150 m. Ahi no se entra en reposo ni de broma.
paseo = [(i * BALANCED, 150 if i else 0, 20.0) for i in range(11)]
assert stationary(paseo) is None, 'andando no puede salir reposo'

# 7) Un movil quieto tiene que declararse en reposo ANTES de que el resto del grupo lo de por
#    perdido: mientras dice "en movimiento" y esta parado no manda nada (filtro de distancia),
#    asi que si el aviso de "no da senales" llega primero, un movil en una mesa sale en rojo.
UI = io.open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          'app/src/main/java/com/dskmusic/lokate/ui/common/UpdateStatus.kt'),
             encoding='utf-8').read()
U = {}
for name, value in re.findall(r'private const val (\w+) = ([0-9_.*\s]+)[LfF]?\n', UI):
    U[name] = eval(value.replace('_', ''))
assert U['STILL_AFTER_MS'] == K['STILL_AFTER_MS'], (U['STILL_AFTER_MS'], K['STILL_AFTER_MS'])
factor = int(re.search(r'OVERDUE_FACTOR = (\d+)', UI).group(1))
for freq in (30_000, 60_000, BALANCED, 300_000, 600_000):
    esperado = max(freq + U['MAX_BATCH_DELAY_MS'], U['STILL_AFTER_MS'] + U['MAX_BATCH_DELAY_MS'])
    assert esperado * factor > K['STILL_AFTER_MS'], freq

# 9) Un ViewModel con bucle infinito dentro tiene que crearse con viewModel{}, NUNCA con
#    remember{}: a los de remember no se les llama onCleared jamas, asi que el bucle no se
#    cancela y cada visita a la pantalla deja uno nuevo sondeando para siempre. Ya paso una vez
#    (se arreglo en MapScreen y se quedo sin arreglar en PeopleScreen), de ahi este guardia.
UI_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'app/src/main/java/com/dskmusic/lokate/ui')
for carpeta, _, ficheros in os.walk(UI_DIR):
    for fichero in ficheros:
        if not fichero.endswith('.kt'):
            continue
        texto = io.open(os.path.join(carpeta, fichero), encoding='utf-8').read()
        assert 'remember { MapViewModel(' not in texto, fichero

# 10) La senal de "app en segundo plano" tiene que venir de la Activity y escucharla el
#     ViewModel, NO un observador puesto desde el composable del mapa: ese se destruye al cambiar
#     de pestana, y entonces seguir a alguien + irse de la app deja su movil en tiempo real toda
#     la noche (3 s de GPS fino). Es el gasto mas caro que la app puede provocar.
MAIN = io.open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                            'app/src/main/java/com/dskmusic/lokate/MainActivity.kt'),
               encoding='utf-8').read()
VM = io.open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                          'app/src/main/java/com/dskmusic/lokate/ui/map/MapViewModel.kt'),
             encoding='utf-8').read()
assert 'AppForeground.visible.value = false' in MAIN, 'MainActivity ya no avisa del segundo plano'
assert 'AppForeground.visible.collect' in VM, 'el ViewModel ya no escucha el segundo plano'
assert 'ComponentActivity' not in VM, 'el ciclo de vida de la Activity ha vuelto al composable'

# 11) Salir de un seguimiento en vivo. El modo viaja en el ping, asi que el ultimo que llego
#     durante el seguimiento decia "live", y para "live" se espera un ping cada 30 s: al soltarlo
#     salia "sin senal" en rojo al minuto, con el movil perfectamente. Dos cierres: el servicio
#     avisa al cambiar de modo, y la UI no se cree un "live" sin seguimiento en curso.
assert 'mode != announcedMode' in SRC, 'el servicio ya no avisa al cambiar de modo'
assert 'announcedMode = UpdateMode.LIVE' in SRC, 'sin apuntar el live no se detecta la salida'
assert 'location.update_mode == UpdateMode.LIVE -> UpdateMode.MOVING' in UI, \
    'la UI vuelve a creerse un live rancio'
# Y con el modo ya degradado a "en movimiento", los 4 min de la captura no pueden salir en rojo.
for freq in (30_000, 60_000, BALANCED):
    esperado = max(freq + U['MAX_BATCH_DELAY_MS'], U['STILL_AFTER_MS'] + U['MAX_BATCH_DELAY_MS'])
    assert esperado * factor > 4 * 60_000, freq

# 12) El seguro del seguimiento en vivo: se suelta solo aunque se deje la app abierta y uno se
#     olvide. Es el gasto mas caro que la app puede provocar (GPS fino cada 3 s en OTRO movil).
FOLLOW_MAX_MS = eval(re.search(r'const val FOLLOW_MAX_MS = ([0-9_* ]+)L', VM).group(1).replace('_', ''))
assert 0 < FOLLOW_MAX_MS <= 60 * 60_000, FOLLOW_MAX_MS
assert 'FollowFeedback.AUTO_STOPPED' in VM, 'el corte por tiempo ya no avisa a quien sigue'

print('ok  en casa=%ds  andando=%ds  coche=%ds (cerca de zona %ds)  re-registros por paseo=%d'
      % (en_casa / 1000, andando / 1000, coche_lejos / 1000, coche_cerca / 1000, rehechas))
