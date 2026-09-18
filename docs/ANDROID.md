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
  cuánto se actualizó.
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

## Gente

Lista de todos los miembros del grupo con su estado (batería, wifi/datos, última actualización).
Tocar a alguien abre su **Detalle**.

## Detalle de un miembro

- Última ubicación, batería, conexión.
- **Actualizar** (icono de refrescar): pide al dispositivo de esa persona una ubicación fresca
  ahora mismo (no solo recarga la última guardada) — muestra "Un momento…" mientras espera
  (hasta ~20s) y termina con un diálogo de resultado: si se consigue, opción de ver esa posición
  directamente en el mapa; si no, opción de reintentar.
- **Ver en Google Maps**, **Historial** (ver más abajo).
- **Hacer sonar el dispositivo**: envía una alerta sonora que fuerza el volumen de alarma y
  vibración fuerte en el móvil de esa persona, saltándose el modo silencio — pensado para
  localizar el teléfono físicamente.
- **Enviar mensaje prioritario**: mensaje de texto opcional + foto/vídeo/archivo adjunto que
  siempre suena y vibra fuerte en el destino, sin mirar sus preferencias de notificaciones — para
  avisos importantes de verdad.

## Zonas

Zonas guardadas (radio en metros) con aviso al entrar y/o salir. **Por defecto, los avisos de
una zona nueva están desactivados** — hay que activarlos explícitamente por zona y por
dirección (entrada/salida) desde esta pantalla. Para evitar avisos repetidos por el ruido normal
del GPS cerca del borde de una zona, hay un margen de 30 m: una vez dentro, hace falta alejarse
más de esa distancia extra para contar como salida.

Crear una zona respeta la posición actual del mapa si vienes de ahí, o se puede buscar una
dirección / tocar el mapa directamente.

## Historial

Recorrido de ubicaciones de una persona por día (Ayer/Hoy/fecha concreta), con el trazado
pintado sobre el mapa y la lista de puntos con hora exacta.

## Ajustes

- **Tema** (claro/oscuro/AMOLED/automático), **idioma** (español/inglés/automático), **color de
  acento**.
- **Frecuencia de ubicación**: tiempo real / equilibrado (cada 2 min) / ahorro de batería (cada
  10 min).
- **Zoom inicial del mapa**, **caché de mapas** (ver tamaño y borrarla).
- **Sonido y vibración de notificaciones**: para alertas de zona — "Hacer sonar" y mensajes
  prioritarios siempre usan alarma+vibración fuerte pase lo que pase, no son personalizables.
- **Actualizaciones**: botón para buscar e instalar la última versión manualmente. La app
  también comprueba sola si hay una versión nueva cada vez que pasa a primer plano, y lo avisa
  con opción de actualizar ahora o posponer.
- Cuenta: cambiar nombre, cerrar sesión, borrar datos locales (zonas/ajustes de este
  dispositivo — no afecta al servidor).

## Panel de administración nativo (solo admins)

Se abre con el candado 🔒 del mapa. Mismas capacidades que el panel web (`/admin`), pero en
Compose nativo:

- **Resumen**: estadísticas del grupo, actividad de los últimos 7 días, actividad reciente, y
  desglose de almacenamiento en disco (BD, avatares, adjuntos, APK, web estática, copias de
  seguridad).
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
