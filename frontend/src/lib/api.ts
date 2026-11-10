export interface Device {
  serial: string
  state: string
  model?: string
  emulator: boolean
  streaming: boolean
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  const body = await res.json().catch(() => ({}))
  if (!res.ok) throw new Error((body as { error?: string }).error ?? `HTTP ${res.status}`)
  return body as T
}

export const api = {
  devices: () => request<Device[]>('/api/devices'),
  connect: (address: string) =>
    request<{ result: string }>(`/api/devices/connect?address=${encodeURIComponent(address)}`, { method: 'POST' }),
}
