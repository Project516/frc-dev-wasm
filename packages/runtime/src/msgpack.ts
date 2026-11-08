// The MessagePack subset NT4 uses. Numbers need an explicit encoding because
// NT4 sends a double as float 64 even when its value is a whole number.

export class Writer {
  private buf = new Uint8Array(256);
  private view = new DataView(this.buf.buffer);
  private len = 0;

  private reserve(n: number) {
    if (this.len + n <= this.buf.length) return;
    let size = this.buf.length * 2;
    while (size < this.len + n) size *= 2;
    const next = new Uint8Array(size);
    next.set(this.buf.subarray(0, this.len));
    this.buf = next;
    this.view = new DataView(next.buffer);
  }

  private byte(b: number) {
    this.reserve(1);
    this.buf[this.len++] = b;
  }

  int(v: number) {
    this.reserve(9);
    if (v >= 0 && v < 128) this.buf[this.len++] = v;
    else if (v < 0 && v >= -32) this.buf[this.len++] = v & 0xff;
    else if (v >= 0 && v <= 0xffffffff) {
      if (v < 0x100) (this.buf[this.len++] = 0xcc), (this.buf[this.len++] = v);
      else if (v < 0x10000) (this.buf[this.len++] = 0xcd), this.view.setUint16(this.len, v), (this.len += 2);
      else (this.buf[this.len++] = 0xce), this.view.setUint32(this.len, v), (this.len += 4);
    } else if (v >= -0x80000000 && v < 0) (this.buf[this.len++] = 0xd2), this.view.setInt32(this.len, v), (this.len += 4);
    else (this.buf[this.len++] = v < 0 ? 0xd3 : 0xcf), this.view.setBigInt64(this.len, BigInt(Math.trunc(v))), (this.len += 8);
  }

  f64(v: number) {
    this.reserve(9);
    this.buf[this.len++] = 0xcb;
    this.view.setFloat64(this.len, v);
    this.len += 8;
  }

  f32(v: number) {
    this.reserve(5);
    this.buf[this.len++] = 0xca;
    this.view.setFloat32(this.len, v);
    this.len += 4;
  }

  bool(v: boolean) {
    this.byte(v ? 0xc3 : 0xc2);
  }

  nil() {
    this.byte(0xc0);
  }

  str(v: string) {
    const bytes = new TextEncoder().encode(v);
    const n = bytes.length;
    this.reserve(5 + n);
    if (n < 32) this.buf[this.len++] = 0xa0 | n;
    else if (n < 0x100) (this.buf[this.len++] = 0xd9), (this.buf[this.len++] = n);
    else if (n < 0x10000) (this.buf[this.len++] = 0xda), this.view.setUint16(this.len, n), (this.len += 2);
    else (this.buf[this.len++] = 0xdb), this.view.setUint32(this.len, n), (this.len += 4);
    this.buf.set(bytes, this.len);
    this.len += n;
  }

  bin(v: Uint8Array) {
    const n = v.length;
    this.reserve(5 + n);
    if (n < 0x100) (this.buf[this.len++] = 0xc4), (this.buf[this.len++] = n);
    else if (n < 0x10000) (this.buf[this.len++] = 0xc5), this.view.setUint16(this.len, n), (this.len += 2);
    else (this.buf[this.len++] = 0xc6), this.view.setUint32(this.len, n), (this.len += 4);
    this.buf.set(v, this.len);
    this.len += n;
  }

  array(n: number) {
    this.reserve(5);
    if (n < 16) this.buf[this.len++] = 0x90 | n;
    else if (n < 0x10000) (this.buf[this.len++] = 0xdc), this.view.setUint16(this.len, n), (this.len += 2);
    else (this.buf[this.len++] = 0xdd), this.view.setUint32(this.len, n), (this.len += 4);
  }

  get size(): number {
    return this.len;
  }

  finish(): Uint8Array {
    const out = this.buf.slice(0, this.len);
    this.len = 0;
    return out;
  }
}

export type Unpacked = null | boolean | number | string | Uint8Array | Unpacked[] | Map<Unpacked, Unpacked>;

/** Decodes every MessagePack value in `data`, in order. */
export function unpackAll(data: Uint8Array): Unpacked[] {
  const view = new DataView(data.buffer, data.byteOffset, data.byteLength);
  const decoder = new TextDecoder();
  let pos = 0;

  const bytes = (n: number) => {
    const out = data.subarray(pos, pos + n);
    pos += n;
    return out;
  };
  const array = (n: number) => {
    const out: Unpacked[] = [];
    for (let i = 0; i < n; i++) out.push(read());
    return out;
  };
  const map = (n: number) => {
    const out = new Map<Unpacked, Unpacked>();
    for (let i = 0; i < n; i++) out.set(read(), read());
    return out;
  };

  function read(): Unpacked {
    if (pos >= data.length) throw new Error('truncated MessagePack data');
    const b = data[pos++];
    if (b < 0x80) return b;
    if (b >= 0xe0) return b - 0x100;
    if ((b & 0xf0) === 0x80) return map(b & 0x0f);
    if ((b & 0xf0) === 0x90) return array(b & 0x0f);
    if ((b & 0xe0) === 0xa0) return decoder.decode(bytes(b & 0x1f));
    let v: number;
    switch (b) {
      case 0xc0: return null;
      case 0xc2: return false;
      case 0xc3: return true;
      case 0xc4: return bytes(data[pos++]).slice();
      case 0xc5: v = view.getUint16(pos); pos += 2; return bytes(v).slice();
      case 0xc6: v = view.getUint32(pos); pos += 4; return bytes(v).slice();
      case 0xca: v = view.getFloat32(pos); pos += 4; return v;
      case 0xcb: v = view.getFloat64(pos); pos += 8; return v;
      case 0xcc: return data[pos++];
      case 0xcd: v = view.getUint16(pos); pos += 2; return v;
      case 0xce: v = view.getUint32(pos); pos += 4; return v;
      case 0xcf: v = Number(view.getBigUint64(pos)); pos += 8; return v;
      case 0xd0: v = view.getInt8(pos); pos += 1; return v;
      case 0xd1: v = view.getInt16(pos); pos += 2; return v;
      case 0xd2: v = view.getInt32(pos); pos += 4; return v;
      case 0xd3: v = Number(view.getBigInt64(pos)); pos += 8; return v;
      case 0xd9: return decoder.decode(bytes(data[pos++]));
      case 0xda: v = view.getUint16(pos); pos += 2; return decoder.decode(bytes(v));
      case 0xdb: v = view.getUint32(pos); pos += 4; return decoder.decode(bytes(v));
      case 0xdc: v = view.getUint16(pos); pos += 2; return array(v);
      case 0xdd: v = view.getUint32(pos); pos += 4; return array(v);
      case 0xde: v = view.getUint16(pos); pos += 2; return map(v);
      case 0xdf: v = view.getUint32(pos); pos += 4; return map(v);
      default: throw new Error(`unsupported MessagePack byte 0x${b.toString(16)}`);
    }
  }

  const out: Unpacked[] = [];
  while (pos < data.length) out.push(read());
  return out;
}
