export type ServerMessage =
  | { type: 'starting'; serial: string }
  | { type: 'started'; deviceName: string; codec: string; width: number; height: number; input?: string }
  | { type: 'closed'; reason: string }
  | { type: 'pong'; t: number }

export type ClientMessage =
  | { type: 'touch'; action: number; pointerId: number; x: number; y: number; width: number; height: number; pressure?: number }
  | { type: 'scroll'; x: number; y: number; width: number; height: number; hScroll: number; vScroll: number }
  | { type: 'key'; action: number; keycode: number; repeat?: number; metaState?: number }
  | { type: 'text'; text: string }
  | { type: 'backOrScreenOn'; action: number }
  | { type: 'command'; name: string }
  | { type: 'ping'; t: number }

export interface StreamHandlers {
  onMessage(msg: ServerMessage): void
  onPacket(flags: number, pts: number, data: Uint8Array): void
  onDisconnect(): void
}

/** WebSocket transport. Video arrives as binary [flags:1][pts:8 BE][H.264 Annex B]; control goes out as JSON. */
export class StreamClient {
  private ws: WebSocket | null = null
  private pingTimer: number | undefined
  rtt = 0

  constructor(private readonly handlers: StreamHandlers) {}

  connect(serial: string): void {
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const ws = new WebSocket(`${proto}://${location.host}/ws/stream/${encodeURIComponent(serial)}`)
    ws.binaryType = 'arraybuffer'
    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') {
        const msg = JSON.parse(ev.data) as ServerMessage
        if (msg.type === 'pong') this.rtt = performance.now() - msg.t
        this.handlers.onMessage(msg)
        return
      }
      const buf = ev.data as ArrayBuffer
      if (buf.byteLength < 9) return
      const view = new DataView(buf)
      const flags = view.getUint8(0)
      const pts = Number(view.getBigUint64(1))
      this.handlers.onPacket(flags, pts, new Uint8Array(buf, 9))
    }
    ws.onclose = () => {
      window.clearInterval(this.pingTimer)
      this.handlers.onDisconnect()
    }
    ws.onopen = () => {
      this.pingTimer = window.setInterval(() => this.send({ type: 'ping', t: performance.now() }), 1000)
    }
    this.ws = ws
  }

  send(msg: ClientMessage): void {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg))
  }

  close(): void {
    window.clearInterval(this.pingTimer)
    if (this.ws) {
      this.ws.onclose = null
      this.ws.close()
      this.ws = null
    }
  }
}
