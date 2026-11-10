import { codecFromConfig, concat } from './h264'

export const FLAG_CONFIG = 1
export const FLAG_KEY_FRAME = 2

export function isWebCodecsSupported(): boolean {
  return typeof window !== 'undefined' && 'VideoDecoder' in window && 'EncodedVideoChunk' in window
}

export interface DecoderCallbacks {
  /** Called with every decoded frame. The callee owns the frame and must close() it. */
  onFrame(frame: VideoFrame): void
  /** The decoder lost sync (error/backlog) and needs a fresh key frame from the device. */
  onNeedKeyFrame(): void
  onError?(message: string): void
}

/**
 * Hardware-accelerated H.264 decoding through WebCodecs, tuned for latency:
 *  - optimizeForLatency: the decoder outputs every frame as soon as it is decoded (no reordering buffer);
 *  - Annex B stream without avcC description: SPS/PPS are delivered in-band, prepended to the key frame;
 *  - if the decoder falls behind or fails, everything queued is dropped and we resync on the next key frame.
 */
export class H264Decoder {
  private decoder: VideoDecoder | null = null
  private codec: string | null = null
  private lastConfig: Uint8Array | null = null
  private pendingConfig: Uint8Array | null = null
  private waitingKeyFrame = true
  private readonly maxQueue = 6

  constructor(private readonly cb: DecoderCallbacks) {}

  push(flags: number, pts: number, data: Uint8Array): void {
    if (flags & FLAG_CONFIG) {
      const codec = codecFromConfig(data)
      if (!codec) return
      this.lastConfig = data
      this.pendingConfig = data
      this.configure(codec)
      return
    }

    const key = (flags & FLAG_KEY_FRAME) !== 0
    if (!this.decoder || this.decoder.state !== 'configured') return

    if (this.waitingKeyFrame) {
      if (!key) return
      this.waitingKeyFrame = false
    }

    if (this.decoder.decodeQueueSize > this.maxQueue) {
      // Decoder cannot keep up: drop the backlog instead of accumulating latency.
      this.resync()
      return
    }

    let payload = data
    if (key && this.pendingConfig) {
      payload = concat(this.pendingConfig, data)
      this.pendingConfig = null
    }
    try {
      this.decoder.decode(new EncodedVideoChunk({ type: key ? 'key' : 'delta', timestamp: pts, data: payload }))
    } catch (e) {
      this.cb.onError?.(String(e))
      this.resync()
    }
  }

  close(): void {
    if (this.decoder && this.decoder.state !== 'closed') this.decoder.close()
    this.decoder = null
  }

  private configure(codec: string): void {
    if (!this.decoder || this.decoder.state === 'closed') {
      this.decoder = new VideoDecoder({
        output: (frame) => this.cb.onFrame(frame),
        error: (e) => {
          this.cb.onError?.(e.message)
          // the decoder is closed after an error: rebuild it from the last config
          this.decoder = null
          if (this.codec) {
            this.pendingConfig = this.lastConfig
            this.configure(this.codec)
            this.cb.onNeedKeyFrame()
          }
        },
      })
    }
    this.codec = codec
    this.decoder.configure({ codec, optimizeForLatency: true, hardwareAcceleration: 'no-preference' })
    this.waitingKeyFrame = true
  }

  private resync(): void {
    if (this.decoder && this.decoder.state !== 'closed') this.decoder.reset()
    if (this.codec && this.lastConfig) {
      this.pendingConfig = this.lastConfig
      this.configure(this.codec)
    }
    this.cb.onNeedKeyFrame()
  }
}
