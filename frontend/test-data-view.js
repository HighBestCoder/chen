// Run with node; exercises the production Vue method without a browser runtime.
const fs = require('fs')
const vm = require('vm')
const assert = require('assert')
const path = require('path')
const component = fs.readFileSync(path.join(__dirname,
  'src/components/Main/Explore/DataView/DataView.vue'), 'utf8')
const script = component.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '').replace('export default', 'module.exports =')
const sandbox = { module: { exports: {} }, Toolbar: {}, HotTable: {}, Subject: class {}, ExportDataDialog: {} }
vm.runInNewContext(script, sandbox)
const context = {
  data: { fields: [{ name: 'a.b' }, { name: 'a' }, { name: 'items' }, { name: 'large' }] },
  hotSettings: {},
  $refs: { hostTable: { hotInstance: { updateSettings() {}, render() {} } } },
  reloadTable() {}
}
sandbox.module.exports.methods.initTable.call(context)
const row = { 'a.b': 'literal', a: '{"b":"nested"}', items: '[{"n":1},null,{"n":2}]', large: '9007199254740993' }
const read = (column, row) => typeof column.data === 'function'
  ? column.data(row) : column.data.split('.').reduce((value, key) => value && value[key], row)
assert.deepStrictEqual(Array.from(context.hotSettings.columns, c => read(c, row)),
  ['literal', '{"b":"nested"}', '[{"n":1},null,{"n":2}]', '9007199254740993'])
assert(context.hotSettings.columns.every(c => c.readOnly && c.type === 'text'))
assert.strictEqual(read(context.hotSettings.columns[0], { 'a.b': 'refreshed' }), 'refreshed')
context.initTable = () => sandbox.module.exports.methods.initTable.call(context)
context.data.fields = [{ name: 'newPageField' }]
sandbox.module.exports.watch.data.call(context)
assert.deepStrictEqual(Array.from(context.hotSettings.colHeaders), ['newPageField'])
assert.strictEqual(read(context.hotSettings.columns[0], { newPageField: 'new value' }), 'new value')
console.log('OK: dotted literal keys, JSON cells and int64 text remain intact')

const toolbarContext = { $t: key => key, $tc: key => key, state: { paged: false, manualLimitDetected: false } }
const toolbar = sandbox.module.exports.data.call(toolbarContext).defaultToolBarItems
assert.strictEqual(toolbar.pagination.hidden(), false, 'unknown total must not hide row limit selector')
assert.strictEqual(toolbar.next.hidden(), true, 'unknown total must not offer false last/next pages')
toolbarContext.state.manualLimitDetected = true
assert.strictEqual(toolbar.pagination.hidden(), true, 'manual SQL limit takes priority')
console.log('OK: query limit remains selectable without an automatic count; manual limit takes precedence')
