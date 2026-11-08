// Host side of the WebAssembly git build. The page or worker that creates an instance passes
// frcEnv (environment), frcSpawn (runs a child command) and optionally frcHttp, frcCorsProxy and
// frcAuth (the Authorization header for a remote URL, or undefined).
Module['preRun'] = Module['preRun'] || [];
Module['preRun'].push(function () {
  Object.assign(ENV, Module['frcEnv'] || {});
  frcRegisterHelperDevices();
  // Output shown in a terminal: standard output and error become terminals of frcColumns columns,
  // so git shows progress and colors. Bytes still go to the stdout and stderr functions.
  if (Module['frcTty']) {
    const terminal = (put) => ({
      put_char: (tty, val) => val !== null && put(val),
      fsync() {},
      ioctl_tiocgwinsz: () => [24, Module['frcColumns'] || 80]
    });
    Object.assign(TTY.default_tty_ops, terminal(Module['stdout']));
    Object.assign(TTY.default_tty1_ops, terminal(Module['stderr']));
    delete Module['stdout'];
    delete Module['stderr'];
  }
});

// Synchronous HTTP, which a worker has through XMLHttpRequest
Module['frcHttp'] =
  Module['frcHttp'] ||
  function (method, url, headers, body) {
    const x = new XMLHttpRequest();
    x.open(method, url, false);
    x.responseType = 'arraybuffer';
    for (const [k, v] of Object.entries(headers)) x.setRequestHeader(k, v);
    x.send(body);
    return { status: x.status, body: new Uint8Array(x.response || new ArrayBuffer(0)) };
  };

// A stand-in for git-remote-https: speaks the remote helper protocol with stateless-connect (git
// protocol v2) to git over two devices, and sends each request to the server with synchronous HTTP.
// The helper's devices may belong to another instance than the command that opened the session, so
// the session is kept in frcShared, one object for all instances of a command.
const frcSession = () => Module['frcShared'].session;
function frcRegisterHelperDevices() {
  const dec = new TextDecoder();
  const enc = new TextEncoder();
  const cat = (a, b) => {
    const r = new Uint8Array(a.length + b.length);
    r.set(a);
    r.set(b, a.length);
    return r;
  };
  // End of the packet that starts at `start`, or -1 when it is not complete yet
  const packetEnd = (buf, start) => {
    if (buf.length - start < 4) return -1;
    const len = parseInt(dec.decode(buf.subarray(start, start + 4)), 16);
    if (len < 4) return start + 4;
    return buf.length - start >= len ? start + len : -1;
  };
  const target = (url, suffix) => {
    const proxy = Module['frcCorsProxy'];
    const base = proxy ? `${proxy.replace(/\/$/, '')}/${url.replace(/^https?:\/\//, '')}` : url;
    return base.replace(/\/$/, '') + suffix;
  };
  // Protocol v2 is for fetching only: receive-pack speaks v0
  const headers = (auth, extra, v2 = true) => Object.assign(v2 ? { 'Git-Protocol': 'version=2' } : {}, auth ? { Authorization: auth } : {}, extra);
  const send = (method, url, headers, body) => {
    try {
      return Module['frcHttp'](method, url, headers, body);
    } catch (e) {
      return { status: 0 };
    }
  };

  // A request to a remote, or the message git should die with when it fails. As git-remote-https
  // does, credentials go only after the server asks for them, and then with every later request, so
  // a public repository never sees a token it could reject.
  const request = (method, remote, suffix, extra, body, v2) => {
    const name = `${remote.replace(/\/$/, '')}/`;
    const shared = Module['frcShared'];
    const auth = Module['frcAuth'] && Module['frcAuth'](remote);
    let response = send(method, target(remote, suffix), headers(shared.authorized && auth, extra, v2), body);
    if (response.status === 401 && auth && !shared.authorized) {
      shared.authorized = true;
      response = send(method, target(remote, suffix), headers(auth, extra, v2), body);
    }
    if (response.status === 200) return response;
    if (response.status === 401 || response.status === 403) return { error: `Authentication failed for '${name}'` };
    if (response.status === 404) return { error: `repository '${name}' not found` };
    if (response.status === 0) return { error: `unable to access '${name}': the CORS proxy could not be reached` };
    return { error: `unable to access '${name}': The requested URL returned error: ${response.status}` };
  };
  // git dies with "fatal: remote error: <message>" on an ERR packet, and cleans up as it does so
  const packet = (text) => {
    const payload = typeof text === 'string' ? enc.encode(text) : text;
    return cat(enc.encode((payload.length + 4).toString(16).padStart(4, '0')), payload);
  };
  const errPacket = (message) => packet(`ERR ${message}\n`);
  // The packets of an advertisement, without the "# service=..." packet and flush servers may put first
  const advertisement = (body) => {
    let pos = 0;
    if (dec.decode(body.subarray(4, 13)) === '# service') for (let i = 0; i < 2; i++) pos = packetEnd(body, pos);
    return body.subarray(pos);
  };

  class Session {
    constructor(url) {
      this.url = url;
      this.input = new Uint8Array(0);
      this.output = [];
      this.rpc = false;
    }
    // Each send is read separately: git reads the answer to a command through a buffered FILE and
    // then reads packets from the raw descriptor, so what follows an answer must not be in its buffer
    send(text) {
      this.output.push(typeof text === 'string' ? enc.encode(text) : text);
    }
    write(bytes) {
      this.input = cat(this.input, bytes);
      this.pump();
    }
    pump() {
      for (;;) {
        if (!this.rpc) {
          const nl = this.input.indexOf(10);
          if (nl < 0) return;
          const line = dec.decode(this.input.subarray(0, nl));
          this.input = this.input.slice(nl + 1);
          this.command(line);
        } else {
          let pos = 0;
          let flush = false;
          while (!flush) {
            const end = packetEnd(this.input, pos);
            if (end < 0) return;
            flush = end - pos === 4 && dec.decode(this.input.subarray(pos, end)) === '0000';
            pos = end;
          }
          const body = this.input.slice(0, pos);
          this.input = this.input.slice(pos);
          if (body.length === 4) continue;
          const response = request('POST', this.url, '/git-upload-pack', { 'Content-Type': 'application/x-git-upload-pack-request', Accept: 'application/x-git-upload-pack-result' }, body);
          this.send(response.error ? errPacket(response.error) : response.body);
          this.send('0002');
        }
      }
    }
    option(text) {
      const [name, value] = [text.slice(0, text.indexOf(' ')), text.slice(text.indexOf(' ') + 1)];
      this.options ??= {};
      if (['verbosity', 'progress', 'dry-run', 'atomic'].includes(name)) {
        this.options[name] = value;
        this.send('ok\n');
      } else this.send('unsupported\n');
    }
    // The refs a push would update, from the receive-pack advertisement, which the push then reuses
    listForPush() {
      const response = request('GET', this.url, '/info/refs?service=git-receive-pack', { Accept: '*/*' }, undefined, false);
      if (response.error) {
        for (const byte of enc.encode(`fatal: ${response.error}\n`)) Module['stderr'](byte);
        exitJS(128);
      }
      const packets = [];
      const body = advertisement(response.body);
      for (let pos = 0, end; (end = packetEnd(body, pos)) > 0 && end - pos > 4; pos = end) packets.push(body.subarray(pos + 4, end));
      // send-pack would read the reply through a side band in a second process
      if (packets.length) {
        const first = dec.decode(packets[0]);
        const nul = first.indexOf('\0');
        const caps = first.slice(nul + 1).trim().split(' ').filter((c) => c !== 'side-band' && c !== 'side-band-64k');
        packets[0] = enc.encode(`${first.slice(0, nul)}\0${caps.join(' ')}\n`);
      }
      this.receivePack = cat(packets.reduce((all, p) => cat(all, packet(p)), new Uint8Array(0)), enc.encode('0000'));
      let list = '';
      for (const p of packets) {
        const [oid, ref] = dec.decode(p).split('\0')[0].trim().split(' ');
        if (ref !== 'capabilities^{}') list += `${oid} ${ref}\n`;
      }
      this.send(`${list}\n`);
    }
    // As git-remote-https does: send-pack runs in stateless RPC mode, and its request goes out in
    // one POST when it starts reading the reply
    push(specs) {
      const preamble = cat(specs.reduce((all, spec) => cat(all, packet(`${spec}\n`)), new Uint8Array(0)), enc.encode('0000'));
      let input = cat(preamble, this.receivePack || enc.encode('0000'));
      let at = 0;
      const out = [];
      let posted = false;
      // send-pack wraps the request in packets of its own, up to a flush packet. What it writes
      // after that is the status of each ref.
      let reportStart = 0;
      const unwrap = (bytes) => {
        const parts = [];
        let pos = 0;
        for (let end; (end = packetEnd(bytes, pos)) > 0; pos = end) {
          if (end - pos === 4) {
            reportStart = end;
            break;
          }
          parts.push(bytes.subarray(pos + 4, end));
        }
        return parts.reduce(cat, new Uint8Array(0));
      };
      const stdin = () => {
        if (at === input.length && !posted) {
          posted = true;
          const body = unwrap(new Uint8Array(out));
          const response = request('POST', this.url, '/git-receive-pack', { 'Content-Type': 'application/x-git-receive-pack-request', Accept: 'application/x-git-receive-pack-result' }, body, false);
          input = cat(input, response.error ? errPacket(response.error) : response.body);
        }
        return at < input.length ? input[at++] : null;
      };
      const o = this.options || {};
      const args = ['send-pack', '--stateless-rpc', '--helper-status', '--thin'];
      if (o['dry-run'] === 'true') args.push('--dry-run');
      if (o.atomic === 'true') args.push('--atomic');
      if (o.verbosity === '0') args.push('--quiet');
      else if (Number(o.verbosity) > 1) args.push('--verbose');
      args.push(o.progress === 'true' ? '--progress' : '--no-progress', this.url, '--stdin');
      Module['frcRun'](args, { stdin, stdout: (byte) => out.push(byte), stderr: Module['stderr'] });
      // After the reply send-pack writes one more flush, an empty request, then the status
      const report = posted ? dec.decode(new Uint8Array(out.slice(reportStart))).replace(/^0000/, '') : '';
      this.send(`${report}\n`);
    }
    command(line) {
      if (line === 'capabilities') this.send('stateless-connect\npush\noption\n\n');
      else if (line.startsWith('option ')) this.option(line.slice(7));
      else if (line === 'list for-push') this.listForPush();
      else if (line.startsWith('push ')) (this.specs ??= []).push(line.slice(5));
      else if (line === '' && this.specs) {
        this.push(this.specs);
        this.specs = undefined;
      } else if (line.startsWith('stateless-connect ')) {
        const response = request('GET', this.url, '/info/refs?service=git-upload-pack', { Accept: '*/*' });
        if (response.error) {
          this.send('\n');
          this.send(errPacket(response.error));
          return;
        }
        const body = advertisement(response.body);
        if (!dec.decode(body.subarray(0, 24)).includes('version 2')) {
          this.send('fallback\n');
          return;
        }
        this.send('\n');
        this.send(body);
        this.rpc = true;
      }
    }
  }

  const major = { in: 64, out: 65 };
  FS.registerDevice(FS.makedev(major.in, 0), {
    open() {},
    close() {},
    read() {
      return 0;
    },
    write(stream, buffer, offset, length) {
      frcSession().write(buffer.slice(offset, offset + length));
      return length;
    }
  });
  FS.registerDevice(FS.makedev(major.out, 0), {
    open() {},
    close() {},
    read(stream, buffer, offset, length) {
      const segment = frcSession().output[0];
      if (!segment) return 0;
      const n = Math.min(length, segment.length);
      buffer.set(segment.subarray(0, n), offset);
      if (n === segment.length) frcSession().output.shift();
      else frcSession().output[0] = segment.subarray(n);
      return n;
    },
    write() {
      return 0;
    }
  });
  FS.mkdev('/dev/frc-helper-in', 0o666, FS.makedev(major.in, 0));
  FS.mkdev('/dev/frc-helper-out', 0o666, FS.makedev(major.out, 0));
  Module['frcNewSession'] = (url) => (Module['frcShared'].session = new Session(url));
}
