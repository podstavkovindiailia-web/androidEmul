/** Minimal H.264 Annex B helpers. */

/** Builds the WebCodecs codec string (avc1.PPCCLL) from the SPS contained in an Annex B config packet. */
export function codecFromConfig(data: Uint8Array): string | null {
  for (let i = 0; i + 6 < data.length; i++) {
    // start code 00 00 01 (a 4-byte 00 00 00 01 start code contains it too)
    if (data[i] === 0 && data[i + 1] === 0 && data[i + 2] === 1) {
      const nal = i + 3
      if ((data[nal] & 0x1f) === 7) {
        const hex = (b: number) => b.toString(16).padStart(2, '0')
        return `avc1.${hex(data[nal + 1])}${hex(data[nal + 2])}${hex(data[nal + 3])}`
      }
    }
  }
  return null
}

export function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  const out = new Uint8Array(a.length + b.length)
  out.set(a, 0)
  out.set(b, a.length)
  return out
}
