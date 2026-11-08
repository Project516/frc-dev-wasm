// Load this before any other script in a dashboard page. It routes NT4
// WebSocket connections (`/nt/<name>`) to the NT4 server of the frc-dev-wasm
// window that embeds or opened the page. Other WebSockets are untouched.
(() => {
  const RealWebSocket = window.WebSocket;

  function findServer() {
    let w = window;
    for (let depth = 0; depth < 8; depth++) {
      try {
        if (w.frcDevWasmNt4) return w.frcDevWasmNt4;
      } catch {
        // Cross-origin ancestor.
      }
      if (w === w.parent) break;
      w = w.parent;
    }
    try {
      return window.opener?.frcDevWasmNt4;
    } catch {
      return undefined;
    }
  }

  class VirtualWebSocket extends EventTarget {
    constructor(url, protocols, server) {
      super();
      this.url = url;
      this.protocol = '';
      this.extensions = '';
      this.binaryType = 'blob';
      this.bufferedAmount = 0;
      this.readyState = 0;
      this.onopen = this.onmessage = this.onerror = this.onclose = null;
      const requested = protocols === undefined ? [] : [].concat(protocols);
      setTimeout(() => {
        if (this.readyState !== 0) return;
        const conn = server.connect(new URL(url).pathname, requested, {
          text: (data) => this._deliver(data),
          binary: (bytes) => this._deliver(bytes),
          close: (code, reason) => this._closed(code, reason),
        });
        if (!conn) {
          this._fire(new Event('error'));
          this._closed(1006, 'connection refused');
          return;
        }
        this._conn = conn;
        this.protocol = conn.protocol;
        this.readyState = 1;
        this._fire(new Event('open'));
      }, 0);
    }

    send(data) {
      if (this.readyState !== 1) throw new DOMException('WebSocket is not open', 'InvalidStateError');
      const conn = this._conn;
      if (typeof data === 'string') conn.send(data);
      else if (data instanceof ArrayBuffer) conn.send(new Uint8Array(data));
      // Copy: views may alias a WebAssembly heap that changes after send returns.
      else if (ArrayBuffer.isView(data)) conn.send(new Uint8Array(data.buffer, data.byteOffset, data.byteLength).slice());
      else if (data instanceof Blob) data.arrayBuffer().then((buf) => this.readyState === 1 && conn.send(new Uint8Array(buf)));
    }

    close(code = 1000, reason = '') {
      if (this.readyState >= 2) return;
      this.readyState = 2;
      this._conn?.close();
      this._closed(code, reason);
    }

    _deliver(data) {
      if (this.readyState !== 1) return;
      let payload = data;
      if (typeof data !== 'string') {
        // Copy into this window's realm so instanceof ArrayBuffer checks pass.
        const bytes = new Uint8Array(data);
        payload = this.binaryType === 'arraybuffer' ? bytes.buffer : new Blob([bytes]);
      }
      // Real sockets never deliver inside send(), and clients rely on that.
      queueMicrotask(() => this.readyState === 1 && this._fire(new MessageEvent('message', { data: payload })));
    }

    _closed(code, reason) {
      if (this.readyState === 3) return;
      this.readyState = 3;
      this._conn?.close();
      queueMicrotask(() => this._fire(new CloseEvent('close', { code, reason, wasClean: code === 1000 })));
    }

    _fire(event) {
      const handler = this[`on${event.type}`];
      if (typeof handler === 'function') {
        try {
          handler.call(this, event);
        } catch (err) {
          reportError(err);
        }
      }
      this.dispatchEvent(event);
    }
  }

  function WebSocket(url, protocols) {
    const resolved = new URL(url, location.href);
    const isNt4 = /^wss?:$/.test(resolved.protocol) && /\/nt\//.test(resolved.pathname);
    const server = isNt4 ? findServer() : undefined;
    if (server) return new VirtualWebSocket(resolved.href, protocols, server);
    return protocols === undefined ? new RealWebSocket(url) : new RealWebSocket(url, protocols);
  }
  for (const [name, value] of Object.entries({ CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 })) {
    WebSocket[name] = value;
    VirtualWebSocket[name] = value;
    VirtualWebSocket.prototype[name] = value;
  }
  WebSocket.prototype = RealWebSocket.prototype;
  window.WebSocket = WebSocket;
})();
