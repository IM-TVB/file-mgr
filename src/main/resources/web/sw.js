// KPE File Manager service worker. Keeps the page files on this device, so repeat
// visits load right away and the page still opens if the connection drops.
// Only the page files and the main page are cached. API responses, login links and
// your files are never stored.
const CACHE = "kpefm-__KPEFM_VERSION__";
const SHELL = ["/app.js", "/style.css"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE)
      .then((cache) => cache.addAll(SHELL))
      .then(() => self.skipWaiting())
  );
});

// A new plugin version gets a new cache name, so old files are removed here.
self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const req = event.request;
  if (req.method !== "GET") return;
  const url = new URL(req.url);
  // Only plain same-site page requests. Login links (with ?token=) and everything else
  // go straight to the server.
  if (url.origin !== self.location.origin || url.search !== "") return;
  if (SHELL.includes(url.pathname)) {
    event.respondWith(staleWhileRevalidate(req));
  } else if (url.pathname === "/") {
    event.respondWith(networkFirst(req));
  }
});

// Serve the saved copy at once and refresh it in the background.
async function staleWhileRevalidate(req) {
  const cache = await caches.open(CACHE);
  const cached = await cache.match(req);
  const refresh = fetch(req)
    .then((res) => {
      if (res.ok) cache.put(req, res.clone());
      return res;
    })
    .catch(() => null);
  return cached || (await refresh) || Response.error();
}

// Use the server's copy when reachable, otherwise the saved page.
async function networkFirst(req) {
  const cache = await caches.open(CACHE);
  try {
    const res = await fetch(req);
    if (res.ok) cache.put(req, res.clone());
    return res;
  } catch {
    const cached = await cache.match(req);
    return cached || Response.error();
  }
}
