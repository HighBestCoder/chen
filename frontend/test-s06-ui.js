// Production Vue methods with real RxJS, controlled network and lifecycle ordering.
const fs = require('fs'); const vm = require('vm'); const path = require('path'); const assert = require('assert')
const { Subject, ReplaySubject } = require('rxjs')
const store = { getters: { profile: { dbType: 'mongodb' } } }
function load(file, extra = {}) {
  let text = fs.readFileSync(path.join(__dirname, 'src', file), 'utf8')
  text = text.includes('<script>') ? text.match(/<script>([\s\S]*?)<\/script>/)[1] : text
  text = text.replace(/^import .*$/gm, '').replace('export default', 'module.exports =')
  const sandbox = { module: { exports: {} }, Subject, ReplaySubject, store, CodeMirror: {},
    Toolbar: {}, HotTable: {}, ExportDataDialog: {}, SelectSnippetDialog: {}, SaveSnippetDialog: {},
    CodeEditor: {}, ResultBar: {}, DataView: {}, Log: {}, Message: {}, SplitPane: {}, format: x => x, formatMongoCommand: x => x,
    VueCookie: { get: () => '' }, commandModuleForDb: x => x, commandCommentForDb: () => '', looksSensitiveCommand: () => false,
    WebSocket: class { send() {} close() {} }, document: { location: { protocol: "http:" } }, window: { location: { host: "test" } },
    setTimeout, clearTimeout, setInterval, clearInterval, ...extra }
  vm.runInNewContext(text, sandbox); return sandbox.module.exports
}
function mount(c, props = {}) {
  const ctx = { $t: x => x, $tc: x => x, $emit() {}, $set: (obj,key,value) => {obj[key]=value}, $delete: (obj,key)=>{delete obj[key]}, $refs: {}, ...props }
  Object.entries(c.methods || {}).forEach(([k,v]) => { ctx[k] = v.bind(ctx) })
  if(c.data) Object.assign(ctx,c.data.call(ctx))
  Object.entries(c.computed || {}).forEach(([k,v]) => Object.defineProperty(ctx,k,{get: (typeof v === 'function' ? v : v.get).bind(ctx), set: v.set && v.set.bind(ctx),configurable:true}))
  return ctx
}
const failures=[]; let count=0
async function test(name, fn) {try {await fn();count++;console.log('PASS '+name)}catch(e){failures.push(name+': '+e.message)}}
const flush = () => new Promise(resolve => setImmediate(resolve))
async function main() {
  await test('result close packet accepts primitive strings and unmount releases subscriptions', () => {
    const c=load('components/Main/Explore/QueryConsole/ResultBar.vue')
    const subjects=Object.fromEntries(['newResultSubject','updateResultSubject','deleteResultSubject','stateSubject'].map(x=>[x,new Subject()]))
    const ctx=mount(c,{subjects}); c.mounted.call(ctx)
    subjects.newResultSubject.next({title:'x'});subjects.deleteResultSubject.next('x');assert.equal(ctx.tabs.length,0)
    c.beforeDestroy.call(ctx);subjects.newResultSubject.next({title:'late'});assert.equal(ctx.tabs.length,0)
  })
  await test('query state survives arrival before result component subscribes', () => {
    const c=load('components/Main/Explore/QueryConsole/index.vue');const ctx=mount(c,{tab:{title:'Query-1'}})
    ctx.handleWSMessage({type:'update_state',data:{title:'x',limit:500}})
    assert.equal(ctx.resultStates.x.limit,500)
    ctx.handleWSMessage({type:'close_data_view',data:'x'});assert.equal(ctx.resultStates.x,undefined)
  })
  await test('initial result data initializes table without a second server update', () => {
    const c=load('components/Main/Explore/DataView/DataView.vue')
    let loaded=0;const ctx=mount(c,{data:{fields:[{name:'x'}],data:[{x:'one'}]},meta:{title:'x'},stateSubject:new Subject(),$refs:{hostTable:{hotInstance:{updateSettings(){},render(){},loadData(){loaded++}}}}})
    c.mounted.call(ctx);assert.equal(loaded,1);c.beforeDestroy.call(ctx)
  })
  await test('execution guard applies to shortcuts and selection before editor ready', () => {
    const c=load('components/Main/Explore/QueryConsole/CodeEditor.vue');const sent=[]
    const ctx=mount(c,{state:{inQuery:true},$emit:(...x)=>sent.push(x)});ctx.cm={getSelection:()=> 'selected'};ctx.statement='whole'
    ctx.onRun();assert.equal(sent.length,0)
    ctx.state.inQuery=false;ctx.onRun();assert.equal(sent[0][1].data,'selected')
    ctx.cm=null;assert.equal(ctx.selectionValue,'')
    assert(!ctx.options.extraKeys['Ctrl-C'],'copy shortcut was hijacked')
  })
  await test('late hints cannot overwrite the selected database', async () => {
    const resolves={};const c=load('components/Main/Explore/QueryConsole/CodeEditor.vue',{getHints:(node,db)=>new Promise(r=>{resolves[db]=r})})
    const ctx=mount(c,{state:{},nodeKey:'node'});ctx.refreshHints('a');ctx.refreshHints('b');resolves.b({b:['new']});await flush();resolves.a({a:['old']});await flush()
    assert.equal(JSON.stringify(ctx.options.hintOptions.tables),'{"b":["new"]}')
  })
  await test('save failure preserves dialog and pending save cannot duplicate', async () => {
    let reject, calls=0; const emitted=[]
    const c=load('components/Main/Explore/QueryConsole/SaveSnippetDialog.vue',{axios:{post:()=>{calls++;return new Promise((r,j)=>{reject=j})}}})
    const ctx=mount(c,{visible:true,content:'db.c.find({})',$emit:(...a)=>emitted.push(a),$message:{error(){},success(){}}});ctx.form.name='saved'
    ctx.onSubmit();ctx.onSubmit();assert.equal(calls,1);reject(new Error('offline'));await flush()
    assert(!emitted.some(e=>e[0]==='update:visible' && e[1]===false));assert.equal(ctx.form.name,'saved')
  })
  await test('SQL template substitutes exact names and preserves dollar text', () => {
    const text=fs.readFileSync(path.join(__dirname,'src/utils/sql.js'),'utf8').replace('export function','function')
    const s={};vm.runInNewContext(text+';this.run=compileSQL',s)
    assert.equal(s.run(':id :id2 :id',{id:'$&',id2:'two'}),'$& two $&')
  })
  await test('message replacement cancels old dismissal timer and unsubscribe', () => {
    const timers=new Map();let id=0
    const c=load('components/Main/Explore/Message.vue',{setTimeout:fn=>{timers.set(++id,fn);return id},clearTimeout:n=>timers.delete(n)})
    const subject=new Subject();const ctx=mount(c,{subject});c.mounted.call(ctx)
    subject.next({title:'old'});subject.next({title:'new'});assert.equal(timers.size,1)
    c.beforeDestroy.call(ctx);assert.equal(timers.size,0);subject.next({title:'late'});assert.equal(ctx.title,'new')
  })
  await test('console close clears loading and disables editor', () => {
    const c=load('components/Main/Explore/QueryConsole/index.vue');const ctx=mount(c,{tab:{title:'q',loading:true}})
    ctx.initWs();ctx.ws.onclose();assert.equal(ctx.tab.loading,false);assert.equal(ctx.state.inQuery,false);assert.equal(ctx.state.disconnected,true)
  })
  await test('event bus listeners are removed with the workspace', () => {
    const listeners=new Map();const bus={ $on:(key,fn)=>listeners.set(key,fn),$off:(key,fn)=>{if(listeners.get(key)===fn)listeners.delete(key)} }
    const c=load('components/Main/Explore/index.vue',{FormTemplate:{},Dialog:{}});const ctx=mount(c,{$bus:bus})
    c.mounted.call(ctx);listeners.get('new_query')('node');assert.equal(ctx.tabs.length,1)
    c.beforeDestroy.call(ctx);assert.equal(listeners.size,0)
  })
  await test('Mongo query exposes only supported limits while relational views retain five choices', () => {
    const c=load('components/Main/Explore/DataView/DataView.vue');const ctx=mount(c,{toolBarItems:{}})
    ctx.state.maxDisplayLimit=1000;assert.equal(JSON.stringify(ctx.iToolBarItems.pagination.options.map(o=>o.value)),'[50,100,500]')
    ctx.state.maxDisplayLimit=50000;assert.equal(ctx.iToolBarItems.pagination.options.length,5)
  })
  await test('command library follows paginated result shapes without changing text', async () => {
    let text=fs.readFileSync(path.join(__dirname,'src/api/jms.js'),'utf8').replace(/^import .*$/gm,'').replace(/export /g,'')
    const offsets=[];const sandbox={get:async(url,params)=>{offsets.push(params.offset);return params.offset===0?{results:[{args:'a\\b'}],next:'?offset=1'}:{results:[{args:'second'}],next:null}}}
    vm.runInNewContext(text+';this.run=getSnippets',sandbox)
    const rows=await sandbox.run();assert.equal(rows.length,2);assert.deepStrictEqual(offsets,[0,1]);assert.equal(rows[0].args,'a\\b')
  })
  await test('Mongo formatter preserves regex punctuation and repeated formatting', () => {
    const source=fs.readFileSync(path.join(__dirname,'src/utils/mongoFormatter.js'),'utf8').replace('export function','function')
    const ctx={};vm.runInNewContext(source+';this.format=formatMongoCommand',ctx)
    const once=ctx.format('db.c.find({x:/a,b[()]/i, s:"quote,colon:"})')
    assert(once.includes('/a,b[()]/i'));assert.equal(ctx.format(once),once)
  })
  await test('selected export sends deduplicated physical indices, not mutable values', () => {
    const c=load('components/Main/Explore/DataView/DataView.vue');const emitted=[]
    const ctx=mount(c,{data:{fields:[],data:[{x:'first'},{x:'second'}]},$emit:(...a)=>emitted.push(a),$refs:{hostTable:{hotInstance:{getSelectedRange:()=>[{from:{row:0},to:{row:1}}],toPhysicalRow:r=>1-r}}}})
    ctx.onExportSubmit('selected');assert.equal(JSON.stringify(emitted[0][1].data.rowIndices),'[1,0]')
  })
  await test('URL parameters preserve encoded credentials, equals and exclude fragments', () => {
    const text=fs.readFileSync(path.join(__dirname,'src/utils/field.js'),'utf8').replace(/export function/g,'function')
    const s={URLSearchParams};vm.runInNewContext(text+';this.run=getUrlParams',s)
    const params=s.run('https://host/?token=a%2Bb==&name=%E4%B8%AD#fragment')
    assert.equal(params.token,'a+b==');assert.equal(params.name,'中')
    assert.equal(Object.keys(s.run('https://host/#?token=wrong')).length,0)
  })
  await test('resource form failure preserves input and prevents duplicate submission', async () => {
    let reject, calls=0;const events=[]
    const c=load('components/Main/Explore/Dialog/FormTemplate.vue',{submitResourceForm:()=>{calls++;return new Promise((r,j)=>{reject=j})}})
    const ctx=mount(c,{visible:true,$emit:(...x)=>events.push(x)});ctx.form={name:'keep'}
    const pending=ctx.onSubmit();ctx.onSubmit();assert.equal(calls,1);reject(new Error('offline'));await pending
    assert.equal(ctx.form.name,'keep');assert.equal(ctx.saving,false);assert.equal(events.length,0)
  })
  if(failures.length)throw new Error(failures.join('\n'))
  console.log('S06 UI: '+count+' groups passed')
}
global.WebSocket=class {} // supplied below to VM by default through load
main().catch(e=>{console.error(e);process.exitCode=1})
