# Lokate — manual de la app

Guía de uso de la app Android. Para cómo funciona el servidor por dentro, ver `BACKEND.md`.

## Primeros pasos

1. **Registro**: usuario (3-30 caracteres, letras/números/`.`/`_`), contraseña (mínimo 8
   caracteres) y una foto de perfil — es obligatoria, se usa para identificarte en el mapa y en
   las notificaciones.
2. **Permisos**: la app pide, en este orden, ubicación, ubicación en segundo plano ("Permitir
   todo el tiempo"), notificaciones, ignorar optimización de batería y, en fabricantes
   especialmente agresivos cerrando apps en segundo plano (Xiaomi/MIUI y similares), un ajuste
   extra del propio fabricante. Sin estos permisos la app no puede compartir tu ubicación de
   forma fiable estando cerrada.
3. **Grupo familiar**: solo los administradores pueden crear un grupo nuevo. Si no eres admin,
   pide el código de invitación a quien lo sea y únete con él. Todos los miembros de un grupo se
   ven entre sí en el mapa.

## Mapa (pantalla principal)

- Muestra a todos los miembros del grupo con su última ubicación conocida, su avatar y hace
  cuánto se actualizó. Cada marcador lleva un **anillo de batería** por fuera del círculo (no tapa
  la foto): el arco crece con el nivel y cambia de color (rojo hasta 15%, ámbar hasta 35%, verde
  por encima); si no se sabe la batería de esa persona, el marcador se queda con su borde blanco
  de siempre, sin anillo.
- **Buscador** (arriba): direcciones y sitios por nombre, pero también coordenadas pegadas de
  otra app (`40.4168, -3.7038`, con punto o con coma decimal) y enlaces de Google Maps,
  OpenStreetMap, Apple Maps o `geo:` — incluidos los enlaces cortos de "compartir"
  (`maps.app.goo.gl/…`). **La búsqueda solo arranca al pulsar la lupa** (o "buscar" en el
  teclado), nunca al teclear. Al elegir un resultado el mapa salta ahí y, si estabas siguiendo
  a alguien en vivo, el seguimiento se desactiva para que no te devuelva el mapa al instante.
- **Estilo de mapa** (icono de capas): Estándar, Satélite u Oscuro.
- **Seguir en vivo**: mantén centrado el mapa en un miembro concreto (o en ti mismo) mientras
  lleguen ubicaciones nuevas suyas — se recuerda al volver a esta pestaña, pero se olvida al
  cerrar sesión o cambiar de cuenta.
- **Dónde estoy**: recentra en tu propia posición.
- Al abrir la app, el mapa recuerda la posición/zoom donde lo dejaste — si es la primera vez en
  esa sesión, se centra en tu ubicación actual.
- Las teselas del mapa se cachean en el dispositivo (más rápido en aperturas siguientes) y se
  refrescan solas cada 14 días; se puede borrar la caché a mano desde Ajustes.
- El candado 🔒 arriba a la derecha (solo visible si eres admin) abre el **panel de
  administración nativo** — ver más abajo.
- **Modo prueba** (solo admins): mantén pulsado el candado **1,5 s** (vibra al entrar). El
  nombre de la app pasa a llevar debajo un `MODO PRUEBA` en rojo y aparecen dos botones: la
  campana, para elegir a quién le llegan los avisos de la prueba (se recuerda entre sesiones), y
  la ✕ para salir. Con el modo activo se puede **mantener pulsado el marcador de otro miembro y
  arrastrarlo** dentro o fuera de una zona: al soltar, el servidor manda el aviso de entrada/
  salida de verdad (el mismo texto y el mismo canal que uno real) a los destinatarios elegidos y
  la app dice qué ha enviado. El arrastre **no se guarda** en el historial de nadie y el sondeo
  se para mientras dura, para que los marcadores no vuelvan solos a su sitio. Al salir, todos
  vuelven a su posición real sin que suene ningún aviso.

## Gente

Lista de todos los miembros del grupo con su estado (batería, wifi/datos, última actualización)
y **a qué distancia está de ti** en línea recta. Arriba hay un selector de orden: **Nombre**,
**Cercanía** o **Reciente** (la más recientemente actualizada primero); el orden se elige por
visita, no se guarda. Si la app no sabe todavía dónde estás (sin permiso de ubicación o sin
ninguna posición guardada), no se muestran distancias y "Cercanía" aparece deshabilitado.
Tocar a alguien abre su **Detalle**.

## Detalle de un miembro

- Última ubicación, batería, conexión y distancia desde donde estás tú.
- **Actualizar** (icono de refrescar): pide al dispositivo de esa persona una ubicación fresca
  ahora mismo (no solo recarga la última guardada) — muestra "Un momento…" mientras espera
  (hasta ~20s) y termina con un diálogo de resultado: si se consigue, opción de ver esa posición
  directamente en el mapa; si no, opción de reintentar.
- Frecuencia de actualización que esa persona tiene configurada en sus Ajustes (o
  "actualizaciones desactivadas" si las ha apagado).
- **Historial** (ver más abajo).
- **Hacer sonar el dispositivo**: envía una alerta sonora que fuerza el volumen de alarma y
  vibración fuerte en el móvil de esa persona, saltándose el modo silencio — pensado para
  localizar el teléfono físicamente.
- **Enviar mensaje prioritario**: mensaje de texto opcional + foto/vídeo/archivo adjunto que
  siempre suena y vibra fuerte en el destino, sin mirar sus preferencias de notificaciones — para
  avisos importantes de verdad.
- **Enviar notificación de prueba** (solo admins, con confirmación): una notificación normal al
  dispositivo de esa persona, para comprobar que le llegan.
- **Ver en Google Maps** (el último de la lista).

## Zonas

Zonas guardadas (radio en metros) con aviso al entrar y/o salir. **Por defecto, los avisos de
una zona nueva están desactivados** — hay que activarlos explícitamente por zona y por
dirección (entrada/salida) desde esta pantalla. Para evitar avisos repetidos por el ruido normal
del GPS cerca del borde de una zona, hay un margen de 30 m: una vez dentro, hace falta alejarse
más de esa distancia extra para contar como salida.

Crear una zona respeta la posición actual del mapa si vienes de ahí, o se puede tocar el mapa
directamente o usar el mismo buscador que el mapa (dirección, coordenadas o enlace, buscando
solo al pulsar la lupa).

## Historial

Recorrido de ubicaciones de una persona por día (Ayer/Hoy/fecha concreta), con el trazado
pintado sobre el mapa y la lista de puntos con hora exacta.

## Ajustes

- **Tema** (claro/oscuro/AMOLED/automático), **idioma** (español/inglés/automático), **color de
  acento** (paleta — incluido un gris oscuro — o selector libre; un acento demasiado oscuro se
  aclara solo en modo oscuro/AMOLED, y uno casi blanco se oscurece en modo claro, para que nunca
  se pierda contra el fondo).
- **Frecuencia de ubicación**: tiempo real / equilibrado (cada 2 min) / ahorro de batería (cada
  10 min) / **deshabilitado**. Esta última pide confirmación y, al aceptar, para el servicio en
  segundo plano en el momento: el dispositivo deja de enviar ubicación y tampoco responde a las
  peticiones puntuales de "Actualizar" de los demás, que ven "actualizaciones desactivadas" en tu
  ficha. Se reactiva eligiendo cualquier otra frecuencia.
- **Zoom inicial del mapa**: del nivel 3 (mundo) al 24 (máximo), por defecto 18. Ojo: 19 es el
  último nivel con teselas reales, del 20 en adelante el mapa se amplía escalando la tesela de
  19, así que se ve borroso y tarda algo más en cargar — de ahí que el valor por defecto se
  quede por debajo. **Restablecer** (con confirmación) vuelve al valor por defecto y además
  hace que el mapa olvide la posición y el zoom que tenía recordados, así que la próxima vez
  se abre centrado en tu ubicación.
- **Caché de mapas** (ver tamaño y borrarla).
- **Sonido y vibración de notificaciones**: para alertas de zona — "Hacer sonar" y mensajes
  prioritarios siempre usan alarma+vibración fuerte pase lo que pase, no son personalizables.
- **Actualizaciones**: botón para buscar e instalar la última versión manualmente. La app
  también comprueba sola si hay una versión nueva cada vez que pasa a primer plano, y lo avisa
  con opción de actualizar ahora o posponer.
- Cuenta: cambiar nombre, cerrar sesión, borrar datos locales (zonas/ajustes de este
  dispositivo — no afecta al servidor).
- Solo admins: **enviar notificación de prueba**, eligiendo a una persona concreta del grupo o a
  todo el grupo (esto último pide confirmación antes de enviarse), e interruptor de aviso de
  actualización para todos.

## Panel de administración nativo (solo admins)

Se abre con el candado 🔒 del mapa. Mismas capacidades que el panel web (`/admin`), pero en
Compose nativo:

- **Resumen**: estadísticas del grupo, actividad de los últimos 7 días, actividad reciente, y
  desglose de almacenamiento en disco (BD, avatares, adjuntos, APK, web estática, copias de
  seguridad). Debajo del desglose, **Administrar espacio** abre un explorador de los archivos
  borrables del servidor (avatares y adjuntos): se previsualizan imágenes, vídeo y audio, y se
  pueden eliminar uno a uno con confirmación, o vaciar de golpe la carpeta de adjuntos con el
  icono de la barra superior (también con confirmación). Tras cada borrado el desglose se
  recalcula solo.
  Los avatares que están en uso se marcan como tales y, si se borran, el perfil correspondiente
  se queda sin foto en vez de apuntar a un archivo que ya no existe.
- **Gente**: crear/editar/eliminar usuarios, subir avatar, enviar notificación de prueba,
  localizar, marcar/quitar admin.
- **Grupos**: crear/eliminar grupos familiares.
- **Zonas**: crear/editar/eliminar zonas de cualquier grupo.
- **Copias**: crear copias de seguridad (con descripción opcional), descargarlas, restaurarlas o
  eliminarlas — ver el detalle de qué hace cada acción en `BACKEND.md`.

## Notificaciones push — qué hace cada tipo

| Evento | Sonido/vibración | Se puede desactivar |
|---|---|---|
| Entrada/salida de zona | El que elijas en Ajustes | Sí, por zona y dirección (ver "Zonas") |
| Hacer sonar el dispositivo | Alarma + vibración fuerte, siempre | No |
| Mensaje prioritario | Alarma + vibración fuerte, siempre | No |
| Actualizar ubicación (desde Detalle) | Ninguno — totalmente silencioso | No aplica |
| Aviso de actualización de la app | Notificación normal | Sí, en Ajustes → Notificaciones |
