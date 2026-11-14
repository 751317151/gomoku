// WebSocket 双路径冒烟：本地 Netty 同时兼容根路径 / 与反向代理路径 /ws。
// 默认验证两者；若只测 Caddy，可设置 WS_URL=wss://example.com/ws WS_PATHS=/ws。
const baseUrl = process.env.WS_URL || 'ws://127.0.0.1:19001';
const configuredPaths = process.env.WS_PATHS
  ? process.env.WS_PATHS.split(',').map((p) => p.trim()).filter(Boolean)
  : null;

function candidatePaths() {
  if (configuredPaths) return configuredPaths;
  const url = new URL(baseUrl);
  // 显式传入 /ws 时只验证该代理路径，避免误把 HTTP 入口当作 WS 根路径。
  if (url.pathname && url.pathname !== '/') return [url.pathname];
  return ['/', '/ws'];
}

function buildUrl(path) {
  const url = new URL(baseUrl);
  url.pathname = path;
  return url.toString();
}

function verifyPath(path) {
  return new Promise((resolve, reject) => {
    const target = buildUrl(path);
    const ws = new WebSocket(target);
    let opened = false;
    let settled = false;
    const timer = setTimeout(() => finish(new Error('握手超时')), 10000);

    function finish(error) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      try { ws.close(); } catch {}
      error ? reject(error) : resolve();
    }

    ws.onopen = () => {
      opened = true;
      ws.send(JSON.stringify({ type: 'PING' }));
      // PING 目前是客户端保活消息，不要求服务端回包；给传输层一点时间发现异常。
      setTimeout(() => finish(null), 150);
    };
    ws.onerror = () => finish(new Error('连接失败'));
    ws.onclose = (event) => {
      if (!opened) finish(new Error(`连接提前关闭 (code=${event.code})`));
    };
  });
}

const paths = candidatePaths();
console.log(`[ws-paths] 目标 ${baseUrl}`);
let failed = 0;

for (const path of paths) {
  try {
    await verifyPath(path);
    console.log(`[ws-paths] PASS ${path}`);
  } catch (error) {
    failed++;
    console.error(`[ws-paths] FAIL ${path}: ${error.message}`);
  }
}

if (!paths.length) {
  console.error('[ws-paths] FAIL 未配置任何待测路径');
  process.exit(1);
}
console.log(failed === 0 ? '[ws-paths] PASS' : `[ws-paths] FAIL (${failed}/${paths.length})`);
process.exit(failed === 0 ? 0 : 1);