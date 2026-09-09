const fs = require('fs')
const vm = require('vm')
const path = require('path')
const assert = require('assert')
const read = file => fs.readFileSync(path.join(__dirname, 'src', file), 'utf8').replace(/^import .*$/gm, '')
async function main() {
  let rejectResponse
  const messages = []
  const axios = { create: () => ({ interceptors: {
    request: { use() {} }, response: { use(_, reject) { rejectResponse = reject } }
  } }) }
  const script = read('request/index.js').replace(/export const /g, 'const ').replace('export default', 'const exported =')
  class TestBlob { async text() { return 'blob error' } }
  class TestFileReader {
    readAsText(value) {
      if (!(value instanceof TestBlob)) throw new TypeError('readAsText requires Blob')
      value.text().then(text => this.onload({ target: { result: text } }))
    }
  }
  vm.runInNewContext(script, { axios, store: {}, Message: { error: s => messages.push(s) }, Blob: TestBlob, FileReader: TestFileReader })
  const errors = [
    { message: 'Network Error' },
    { message: 'timeout', response: { data: 'Unauthorized' } },
    { response: { data: { detail: 'invalid input' } } },
    { response: { data: new TestBlob() } }
  ]
  for (const error of errors) await assert.rejects(rejectResponse(error), e => e === error)
  assert.deepStrictEqual(messages, ['Network Error', 'Unauthorized', 'invalid input', 'blob error'])

  const listeners = new Map()
  const sandbox = { module: { exports: {} }, auth() {}, getProfile() {},
    Message: { error() {} }, i18n: { t: x => x }, navigator: {},
    document: {
      addEventListener: (event, fn) => { if (!listeners.has(event)) listeners.set(event, new Set()); listeners.get(event).add(fn) },
      removeEventListener: (event, fn) => listeners.get(event).delete(fn)
    }
  }
  vm.runInNewContext(read('store/modules/app.js').replace('export default', 'module.exports ='), sandbox)
  const { state, mutations } = sandbox.module.exports
  mutations.PROFILE(state, { canCopy: false, canPaste: false })
  mutations.PROFILE(state, { canCopy: false, canPaste: false })
  assert.strictEqual(listeners.get('copy').size, 1)
  let prevented = 0
  for (const fn of listeners.get('paste')) fn({ preventDefault: () => prevented++ })
  assert.strictEqual(prevented, 1)
  mutations.PROFILE(state, { canCopy: true, canPaste: true })
  assert.strictEqual(listeners.get('copy').size + listeners.get('paste').size, 0)

  const parent = { postMessage() {} }
  const luna = { module: { exports: {} }, window: { parent } }
  vm.runInNewContext(read('utils/luna.js').replace('export const ', 'const ').replace('export class LunaEvent', 'module.exports = class LunaEvent'), luna)
  const eventHandler = new luna.module.exports()
  eventHandler.handleEventFromLuna({ source: {}, data: { name: 'PING', id: 'bad' }, origin: 'bad' })
  assert.strictEqual(eventHandler.lunaId, undefined)
  eventHandler.handleEventFromLuna({ source: parent, data: null })
  eventHandler.handleEventFromLuna({ source: parent, data: { name: 'PING', id: 'parent' }, origin: 'https://parent.test' })
  assert.strictEqual(eventHandler.lunaId, 'parent')
  console.log('OK: network/string/JSON/blob failures preserve rejection; clipboard guards refresh; Luna accepts parent only')
}
main().catch(e => { console.error(e); process.exitCode = 1 })
