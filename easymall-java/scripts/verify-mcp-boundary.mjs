import assert from 'node:assert/strict';

const endpoint = new URL(process.argv[2] ?? 'http://127.0.0.1:8084/mcp');
assert.equal(endpoint.protocol, 'http:');
assert.equal(endpoint.hostname, '127.0.0.1');
assert.equal(endpoint.pathname, '/mcp');
const serviceToken = process.env.MCP_SERVICE_TOKEN;
assert.match(serviceToken ?? '', /^[A-Za-z0-9_-]{32,128}$/, '设置用于联调的 MCP_SERVICE_TOKEN');

const auth = { Authorization: 'Bearer ' + serviceToken };
const transport = { Accept: 'application/json, text/event-stream' };
let nextId = 1;

function request(method, headers, body) {
  return fetch(endpoint, {
    method,
    headers: { ...transport, ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(8000),
  });
}

function rpc(method, params, headers, notification = false) {
  return request('POST', { ...headers, 'Content-Type': 'application/json' }, {
    jsonrpc: '2.0',
    ...(notification ? {} : { id: nextId++ }),
    method,
    params,
  });
}

async function decode(response) {
  const body = await response.text();
  if (response.headers.get('content-type')?.includes('text/event-stream')) {
    const data = body.split(/\r?\n/).find((line) => line.startsWith('data:'));
    assert.ok(data, 'SSE 响应必须包含 JSON-RPC 消息');
    return JSON.parse(data.slice(5));
  }
  return JSON.parse(body);
}

const initialize = { protocolVersion: '2025-03-26', capabilities: {}, clientInfo: { name: 'step06-check', version: '1.0.0' } };
const invalidToken = serviceToken.startsWith('A') ? 'B'.repeat(32) : 'A'.repeat(32);
for (const [headers, expected] of [
  [{}, 401],
  [{ Authorization: 'Bearer ' + invalidToken }, 401],
  [{ Authorization: auth.Authorization + ', ' + auth.Authorization }, 401],
  [{ ...auth, Origin: 'https://example.invalid' }, 403],
]) {
  const response = await rpc('initialize', initialize, headers);
  assert.equal(response.status, expected);
}

const handshake = await rpc('initialize', initialize, auth);
assert.equal(handshake.status, 200);
const hello = (await decode(handshake)).result;
assert.equal(hello?.serverInfo?.name, 'easymall-mcp');
const sessionId = handshake.headers.get('mcp-session-id');
assert.ok(sessionId, 'Streamable HTTP 初始化必须分配会话');
const session = { 'Mcp-Session-Id': sessionId, 'MCP-Protocol-Version': hello.protocolVersion };
const initialized = await rpc('notifications/initialized', {}, { ...session, ...auth }, true);
assert.equal(initialized.status, 202);

for (const method of ['GET', 'DELETE']) {
  const response = await request(method, session);
  assert.equal(response.status, 401, method + ' 不能只使用会话 ID');
}
assert.equal((await rpc('tools/list', {}, session)).status, 401);
const list = await rpc('tools/list', {}, { ...session, ...auth });
assert.equal(list.status, 200);
assert.deepEqual((await decode(list)).result?.tools, []);

const unavailableTools = ['refund', 'cancelOrder', 'orderConfirm', 'productReview', 'queryOrderLogistics'];
for (const name of unavailableTools) {
  const response = await rpc('tools/call', { name, arguments: {} }, { ...session, ...auth });
  assert.equal(response.status, 200);
  const call = await decode(response);
  assert.ok(call.error || call.result?.isError === true, name + ' 必须在工具执行前被拒绝');
}

const closed = await request('DELETE', { ...session, ...auth });
assert.equal(closed.status, 200);
process.stdout.write(JSON.stringify({
  missingOrInvalidCredential: 401,
  browserOrigin: 403,
  initialized: true,
  sessionIdAloneRejected: true,
  advertisedTools: [],
  unavailableTools,
}) + '\n');
