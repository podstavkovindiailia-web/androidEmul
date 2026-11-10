/** Android KeyEvent keycodes. */
export const Keycode = {
  HOME: 3,
  BACK: 4,
  DPAD_UP: 19,
  DPAD_DOWN: 20,
  DPAD_LEFT: 21,
  DPAD_RIGHT: 22,
  VOLUME_UP: 24,
  VOLUME_DOWN: 25,
  POWER: 26,
  TAB: 61,
  SPACE: 62,
  ENTER: 66,
  DEL: 67,
  PAGE_UP: 92,
  PAGE_DOWN: 93,
  ESCAPE: 111,
  FORWARD_DEL: 112,
  MOVE_HOME: 122,
  MOVE_END: 123,
  APP_SWITCH: 187,
} as const

export const MetaState = {
  SHIFT: 0x1 | 0x40,
  ALT: 0x2 | 0x10,
  CTRL: 0x1000 | 0x2000,
  META: 0x10000 | 0x20000,
} as const

const SPECIAL: Record<string, number> = {
  Enter: Keycode.ENTER,
  Backspace: Keycode.DEL,
  Delete: Keycode.FORWARD_DEL,
  Tab: Keycode.TAB,
  Escape: Keycode.ESCAPE,
  ArrowUp: Keycode.DPAD_UP,
  ArrowDown: Keycode.DPAD_DOWN,
  ArrowLeft: Keycode.DPAD_LEFT,
  ArrowRight: Keycode.DPAD_RIGHT,
  Home: Keycode.MOVE_HOME,
  End: Keycode.MOVE_END,
  PageUp: Keycode.PAGE_UP,
  PageDown: Keycode.PAGE_DOWN,
}

/** Maps a browser KeyboardEvent.code to an Android keycode (used for shortcuts like Ctrl+A). */
function keycodeFromCode(code: string): number | undefined {
  let m = /^Key([A-Z])$/.exec(code)
  if (m) return 29 + (m[1].charCodeAt(0) - 65) // KEYCODE_A = 29
  m = /^Digit([0-9])$/.exec(code)
  if (m) return 7 + Number(m[1]) // KEYCODE_0 = 7
  if (code === 'Space') return Keycode.SPACE
  return undefined
}

export function metaStateOf(e: KeyboardEvent): number {
  return (e.shiftKey ? MetaState.SHIFT : 0) | (e.altKey ? MetaState.ALT : 0) | (e.ctrlKey ? MetaState.CTRL : 0) | (e.metaKey ? MetaState.META : 0)
}

export type KeyAction = { kind: 'text'; text: string } | { kind: 'key'; keycode: number; metaState: number } | null

/** Decides how a keydown is forwarded: printable characters as text injection, the rest as key events. */
export function translateKeyDown(e: KeyboardEvent): KeyAction {
  const special = SPECIAL[e.key]
  if (special !== undefined) return { kind: 'key', keycode: special, metaState: metaStateOf(e) }
  if (e.ctrlKey || e.metaKey || e.altKey) {
    const kc = keycodeFromCode(e.code)
    return kc !== undefined ? { kind: 'key', keycode: kc, metaState: metaStateOf(e) } : null
  }
  if (e.key.length === 1) return { kind: 'text', text: e.key }
  return null
}

export function translateKeyUp(e: KeyboardEvent): KeyAction {
  const action = translateKeyDown(e)
  return action?.kind === 'key' ? action : null
}
