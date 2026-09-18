// Service worker mínimo: solo existe para que Chrome considere el panel de admin "instalable"
// (uno de sus requisitos, junto al manifest.json). No cachea nada a propósito — es una app
// autenticada y dinámica, cachear páginas podría servir contenido viejo o de otra sesión.
//
// Importante: NO intercepta navegaciones (abrir/recargar una página completa, que es
// exactamente lo que pasa al abrir la PWA). Aunque este handler solo reenvía la petición tal
// cual sin cachear nada, pasar la navegación POR el service worker puede acabar sirviendo un
// primer frame con datos antiguos (un fetch dentro de un fetch event no siempre hereda el
// mismo comportamiento de caché que la navegación normal del navegador) antes de que la
// página real, ya con Cache-Control: no-store, sustituya ese contenido. Dejando pasar las
// navegaciones sin tocar, el navegador las trata exactamente igual que si no hubiera SW.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("fetch", (event) => {
  if (event.request.mode === "navigate") return;
  event.respondWith(fetch(event.request));
});
