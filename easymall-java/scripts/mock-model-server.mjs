import http from 'node:http';

// Stage-0 transport fixture only: no inference, tool calls, external I/O or logs
// containing requests. This intentionally binds to one fixed loopback endpoint.
const HOST = '127.0.0.1';
const PORT = 18080;
const CHAT_MODEL = 'baseline-chat';
const EMBEDDING_MODEL = 'baseline-embedding';
const DIMENSIONS = 1024;
const MAX_BODY_BYTES = 1024 * 1024;
const MAX_EMBEDDING_ITEMS = 128;
const VECTOR = Object.freeze(Array(DIMENSIONS).fill(1 / 32));
const SYNTHETIC_REPLY = '【阶段0合成测试回复】本消息由本地固定替身生成，仅用于验证问答、流式传输和消息保存链路，不代表真实模型能力，也未执行任何订单或售后操作。';
const counters = {
  requestsTotal: 0,
  healthRequests: 0,
  chatRequests: 0,
  intentResponses: 0,
  streamResponses: 0,
  embeddingRequests: 0,
  embeddingItems: 0,
  errors: 0,
};

class RequestError extends Error {
  constructor(status, code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

function sendJson(response, status, value) {
  if (response.destroyed || response.writableEnded) return;
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify(value));
}

function readJson(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    request.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        tooLarge = true;
        chunks.length = 0;
      } else if (!tooLarge) {
        chunks.push(chunk);
      }
    });
    request.on('error', () => reject(new RequestError(400, 'request_failed')));
    request.on('end', () => {
      if (tooLarge) return reject(new RequestError(413, 'body_too_large'));
      try {
        const value = JSON.parse(Buffer.concat(chunks).toString('utf8'));
        if (value === null || typeof value !== 'object' || Array.isArray(value)) {
          throw new Error();
        }
        resolve(value);
      } catch {
        reject(new RequestError(400, 'invalid_json_object'));
      }
    });
  });
}

function embeddingItemCount(input) {
  if (typeof input === 'string' && input.length > 0) return 1;
  if (!Array.isArray(input) || input.length === 0) {
    throw new RequestError(400, 'invalid_embedding_input');
  }
  const isToken = (value) => Number.isInteger(value) && value >= 0;
  if (input.every(isToken)) return 1;
  if (input.every((value) => typeof value === 'string' && value.length > 0)) {
    return input.length;
  }
  if (input.every((value) => Array.isArray(value) && value.length > 0 && value.every(isToken))) {
    return input.length;
  }
  throw new RequestError(400, 'invalid_embedding_input');
}

function embeddings(response, body) {
  if (body.model !== EMBEDDING_MODEL) throw new RequestError(400, 'unknown_embedding_model');
  if (body.dimensions !== undefined && body.dimensions !== DIMENSIONS) {
    throw new RequestError(400, 'dimensions_must_be_1024');
  }
  if (body.encoding_format !== undefined && !['float', 'base64'].includes(body.encoding_format)) {
    throw new RequestError(400, 'unsupported_embedding_encoding');
  }
  const count = embeddingItemCount(body.input);
  if (count > MAX_EMBEDDING_ITEMS) throw new RequestError(400, 'too_many_embedding_items');
  let vector = VECTOR;
  if (body.encoding_format === 'base64') {
    const bytes = Buffer.alloc(DIMENSIONS * 4);
    VECTOR.forEach((value, index) => bytes.writeFloatLE(value, index * 4));
    vector = bytes.toString('base64');
  }
  counters.embeddingItems += count;
  sendJson(response, 200, {
    object: 'list',
    model: EMBEDDING_MODEL,
    data: Array.from({ length: count }, (_, index) => ({
      object: 'embedding', index, embedding: vector,
    })),
    // No measured model Token usage exists for this fixed transport fixture.
    usage: null,
  });
}

async function chat(response, body) {
  if (body.model !== CHAT_MODEL) throw new RequestError(400, 'unknown_chat_model');
  if (!Array.isArray(body.messages) || body.messages.length === 0) {
    throw new RequestError(400, 'messages_required');
  }
  if (body.stream !== undefined && typeof body.stream !== 'boolean') {
    throw new RequestError(400, 'invalid_stream_flag');
  }
  const id = `chatcmpl-baseline-${counters.chatRequests}`;
  const created = Math.floor(Date.now() / 1000);
  if (!body.stream) {
    // ChatComponent.analyzeUserIntent().call().entity(UserIntent.class) expects
    // these exact fields and the uppercase PromptTypeEnum name. Always route
    // into CHAT/FAQ; never select product, order, refund or other tool actions.
    counters.intentResponses += 1;
    sendJson(response, 200, {
      id, object: 'chat.completion', created, model: CHAT_MODEL,
      choices: [{
        index: 0,
        message: { role: 'assistant', content: JSON.stringify({ intent: 'CHAT', data: null }) },
        finish_reason: 'stop',
      }],
      usage: null,
    });
    return;
  }

  counters.streamResponses += 1;
  response.writeHead(200, {
    'Content-Type': 'text/event-stream; charset=utf-8',
    'Cache-Control': 'no-cache',
    Connection: 'keep-alive',
    'X-Accel-Buffering': 'no',
  });
  response.flushHeaders();
  const writeChunk = (delta, finishReason = null) => {
    if (response.destroyed || response.writableEnded) return;
    response.write(`data: ${JSON.stringify({
      id, object: 'chat.completion.chunk', created, model: CHAT_MODEL,
      choices: [{ index: 0, delta, finish_reason: finishReason }],
    })}\n\n`);
  };
  writeChunk({ role: 'assistant', content: '' });
  const characters = Array.from(SYNTHETIC_REPLY);
  for (let offset = 0; offset < characters.length; offset += 12) {
    if (response.destroyed || response.writableEnded) return;
    writeChunk({ content: characters.slice(offset, offset + 12).join('') });
    await new Promise((resolve) => setTimeout(resolve, 15));
  }
  writeChunk({}, 'stop');
  if (!response.destroyed && !response.writableEnded) {
    response.end('data: [DONE]\n\n');
  }
}

const server = http.createServer(async (request, response) => {
  counters.requestsTotal += 1;
  try {
    // Read only the path; request headers and bodies are never logged or echoed.
    const path = new URL(request.url, `http://${HOST}:${PORT}`).pathname;
    if (request.method === 'GET' && path === '/health') {
      counters.healthRequests += 1;
      sendJson(response, 200, {
        status: 'ok', synthetic: true,
        models: { chat: CHAT_MODEL, embedding: EMBEDDING_MODEL },
        embeddingDimensions: DIMENSIONS,
        counters: { ...counters },
      });
      return;
    }
    if (request.method !== 'POST' || !['/v1/chat/completions', '/v1/embeddings'].includes(path)) {
      throw new RequestError(404, 'route_not_found');
    }
    const body = await readJson(request);
    if (path === '/v1/embeddings') {
      counters.embeddingRequests += 1;
      embeddings(response, body);
    } else {
      counters.chatRequests += 1;
      await chat(response, body);
    }
  } catch (error) {
    counters.errors += 1;
    if (response.headersSent) {
      response.end();
      return;
    }
    const known = error instanceof RequestError;
    sendJson(response, known ? error.status : 500, {
      error: {
        message: 'Baseline mock request rejected.',
        type: 'invalid_request_error',
        code: known ? error.code : 'internal_mock_error',
      },
    });
  }
});

server.requestTimeout = 30_000;
server.headersTimeout = 10_000;
server.on('clientError', (_error, socket) => {
  counters.errors += 1;
  if (socket.writable) socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
});
server.on('error', () => {
  process.stderr.write('Baseline model mock could not listen on 127.0.0.1:18080.\n');
  process.exitCode = 1;
});
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    server.close(() => process.exit(0));
    server.closeAllConnections();
  });
}
server.listen(PORT, HOST, () => {
  process.stdout.write('Synthetic baseline model fixture: http://127.0.0.1:18080 (loopback only).\n');
});
