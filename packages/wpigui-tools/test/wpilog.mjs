// Writes WPILOG files for the tests, per
// https://github.com/wpilibsuite/allwpilib/blob/main/wpiutil/doc/datalog.adoc
// Timestamps are in microseconds.

const text = new TextEncoder();

const uint = (value, size) => Array.from({ length: size }, (_, i) => Number((BigInt(value) >> BigInt(8 * i)) & 0xffn));
const bytesFor = (value) => Math.max(1, Math.ceil(BigInt(value).toString(2).length / 8));
const string = (value) => [...uint(text.encode(value).length, 4), ...text.encode(value)];

export class WpiLog {
  #bytes = [...text.encode('WPILOG'), ...uint(0x0100, 2), ...uint(0, 4)];
  #nextId = 1;

  #record(entry, timestamp, payload) {
    const idSize = bytesFor(entry);
    const sizeSize = bytesFor(payload.length);
    const timeSize = bytesFor(timestamp);
    this.#bytes.push(idSize - 1 + ((sizeSize - 1) << 2) + ((timeSize - 1) << 4), ...uint(entry, idSize), ...uint(payload.length, sizeSize), ...uint(timestamp, timeSize), ...payload);
  }

  /** Starts an entry and returns its id. */
  start(name, type, timestamp = 0, metadata = '') {
    const id = this.#nextId++;
    this.#record(0, timestamp, [0, ...uint(id, 4), ...string(name), ...string(type), ...string(metadata)]);
    return id;
  }

  double(id, timestamp, value) {
    const buffer = new DataView(new ArrayBuffer(8));
    buffer.setFloat64(0, value, true);
    this.#record(id, timestamp, [...new Uint8Array(buffer.buffer)]);
  }

  string(id, timestamp, value) {
    this.#record(id, timestamp, [...text.encode(value)]);
  }

  boolean(id, timestamp, value) {
    this.#record(id, timestamp, [value ? 1 : 0]);
  }

  toBytes() {
    return Uint8Array.from(this.#bytes);
  }
}
