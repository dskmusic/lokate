// Service worker mínimo: solo existe para que Chrome considere el panel de admin "instalable"
// (uno de sus requisitos, junto al manifest.json). No cachea nada a propósito — es una app
// autenticada y dinámica, cachear páginas podría servir contenido viejo o de otra sesión.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
// El handler existe pero no responde NUNCA: sin respondWith, el navegador hace cada
// petición exactamente igual que si no hubiera service worker (misma cache, mismas
// credenciales, mismas redirecciones). Chrome solo exige que el handler exista para
// considerar la app instalable, no que haga nada. Antes reenviaba las subpeticiones con
// fetch(event.request) y eso sí cambia el comportamiento: un fetch dentro del SW no hereda
// del todo el de la navegación, y una carga a medias deja la página en blanco.
// ponytail: si algún día se quiere panel offline, aquí es donde iría una caché -- pero
// entonces hay que decidir qué pasa con los datos de otra sesión.
self.addEventListener("fetch", () => {});
