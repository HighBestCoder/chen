export const MESSAGES = {
  PONG: 'PONG',
  PING: 'PING',
  CLOSE: 'CLOSE',
  CONNECTED: 'CONNECTED',
  KEYBOARDEVENT: 'KEYBOARDEVENT',
  MOUSEEVENT: 'MOUSEEVENT'
}

export class LunaEvent {
  init() {
    this.destroy()
    this.listener = this.handleEventFromLuna.bind(this)
    window.addEventListener('message', this.listener, false)
  }

  destroy() {
    if (this.listener) window.removeEventListener('message', this.listener, false)
    this.listener = null
  }

  handleEventFromLuna(event) {
    if (event.source !== window.parent || !event.data || typeof event.data !== 'object') return
    const msg = event.data
    switch (msg.name) {
      case MESSAGES.PING:
        if (this.lunaId != null) {
          return
        }
        this.lunaId = msg.id
        this.origin = event.origin
        this.sendEventToLuna(MESSAGES.PONG)
        break
    }
  }

  sendEventToLuna(name, data) {
    data == null && (data = '')
    if (this.lunaId != null) {
      const msg = { name: name, id: this.lunaId, data: data }
      window.parent.postMessage(msg, this.origin)
    }
  }
}
