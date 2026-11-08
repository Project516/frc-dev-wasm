// The part of node:zlib that just-bash's gzip, gunzip and zcat use, on fflate
import { gunzipSync as gunzip, gzipSync as gzip } from 'fflate'

export const constants = { Z_BEST_SPEED: 1, Z_BEST_COMPRESSION: 9 }

export const gzipSync = (data: Uint8Array, options?: { level?: number }): Uint8Array => gzip(data, { level: (options?.level ?? 6) as 6 })

export const gunzipSync = (data: Uint8Array): Uint8Array => gunzip(data)
