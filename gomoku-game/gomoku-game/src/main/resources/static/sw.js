/* 棋战大厅 Service Worker
 *
 * 策略：
 * - 导航请求（index.html）走网络优先，拿不到网络时回落缓存外壳 —— 新版本能立即生效；
 * - 静态资源 stale-while-revalidate，弱网下秒开，同时后台更新；
 * - API 与 WebSocket 一律不缓存，避免把对局状态缓存成过期数据。
 */
const CACHE_NAME = 'gomoku-shell-v1';
const SHELL = [
  '/',
  '/index.html',
  '/style.css?v=51',
  '/js/games.js?v=51',
  '/app.js?v=51',
  '/manifest.json',
  '/icons/icon-192.png',
  '/icons/icon-512.png'
];

self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE_NAME).then(cache => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE_NAME).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== location.origin) return;
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/actuator/') || url.pathname === '/ws') return;

  // 页面导航：网络优先，离线时回落到缓存外壳
  if (request.mode === 'navigate') {
    event.respondWith(fetch(request).catch(() => caches.match('/index.html')));
    return;
  }

  if (/\.(css|js|png|svg|webmanifest|woff2?)$/.test(url.pathname)) {
    event.respondWith((async () => {
      const cache = await caches.open(CACHE_NAME);
      const cached = await cache.match(request);
      const network = fetch(request)
        .then(response => {
          if (response && response.ok) cache.put(request, response.clone());
          return response;
        })
        .catch(() => cached);
      return cached || network;
    })());
  }
});
