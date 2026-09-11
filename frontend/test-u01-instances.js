// Production API / Vue methods: controlled Core responses and popup lifecycle.
const fs = require('fs'), vm = require('vm'), assert = require('assert')
const read = p => fs.readFileSync(__dirname + '/src/' + p, 'utf8')
function api(fetch) {
  const box = { fetch, URLSearchParams, module: {} }
  vm.runInNewContext(read('api/instances.js').replace(/export /g, '') + '\nmodule.exports={coreContext,getInstances,getInstanceAccounts,createInstanceSession,consumeInstanceToken};', box)
  return box.module.exports
}
const context = { org: 'org-A', csrf: 'fixture-csrf' }
const response = data => ({ ok: true, json: async () => data })
function dialog(extra = {}) {
  const box = { module: {}, ...extra }
  vm.runInNewContext(read('components/Main/Explore/QueryConsole/InstanceDialog.vue').match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace('export default', 'module.exports ='), box)
  const component = box.module.exports
  const instance = { ...component.data(), $emit() {} }
  for (const [k, v] of Object.entries(component.methods)) instance[k] = v.bind(instance)
  return { component, instance }
}
async function main() {
  const calls = []
  const a = api(async (path, options) => { calls.push({path, options}); return response(calls.length === 1 ? { results: [{id:'a',type:{value:'mongodb'}}, {id:'x',type:'mysql'}], next:'https://untrusted.invalid' } : { results:[{id:'b',type:'mongodb'}, {id:'c',type:'mongodb',is_active:false}], next:null }) })
  assert.deepStrictEqual(JSON.parse(JSON.stringify(await a.getInstances(context))).map(x=>x.id), ['a','b'])
  assert(calls[1].path.endsWith('offset=2'))
  assert(calls.every(c=>c.path.startsWith('/api/v1/perms/users/self/')))
  assert(calls.every(c=>c.options.credentials === 'same-origin' && c.options.headers['X-JMS-ORG'] === 'org-A' && !c.options.headers.token))
  assert.strictEqual(a.coreContext('SESSION_COOKIE_NAME_PREFIX=jms_; jms_csrftoken=csrf; X-JMS-ORG=cookie', '?oid=fixed').csrf, 'csrf')
  assert.strictEqual(a.coreContext('X-JMS-ORG=cookie', '?oid=fixed').org, 'fixed')
  await assert.rejects(a.getInstances({}), /instance_org_required/)
  for (const status of [401,403,500]) await assert.rejects(api(async()=>({ok:false,status})).createInstanceSession('b','reader',context), /instance_/)
  await assert.rejects(api(async()=>response({results:[],next:'x'})).getInstances(context), /instance_failed/)
  const accounts = await api(async()=>response({permed_protocols:[{name:'mongodb'}],permed_accounts:[{name:'reader',has_secret:true,has_username:true},{name:'entra',username:'service',has_secret:false,has_username:true},{name:'@INPUT',username:'@INPUT',has_secret:false}]})).getInstanceAccounts('b',context)
  assert.strictEqual(accounts.length,2)
  assert.strictEqual((await api(async()=>response({permed_protocols:[],permed_accounts:accounts})).getInstanceAccounts('b',context)).length,0)
  let payload
  const b=api(async(path,options)=>{payload={path,options};return response({id:'new-token'})})
  const url=await b.createInstanceSession('asset-B','reader',context)
  assert.deepStrictEqual(JSON.parse(payload.options.body),{asset:'asset-B',account:'reader',protocol:'mongodb',connect_method:'web_gui'})
  assert.strictEqual(payload.options.headers['X-CSRFToken'],'fixture-csrf')
  assert(!url.split('#')[0].includes('new-token'))
  let clean
  assert.strictEqual(b.consumeInstanceToken(new URL('https://example.test'+url),{replaceState:(_,__,value)=>{clean=value}}),'new-token')
  assert.strictEqual(clean,'/chen/?oid=org-A')
  await assert.rejects(b.createInstanceSession('b','reader',{org:'org-A'}), /instance_login_required/)
  await assert.rejects(api(async()=>response({id:'pending',is_active:false})).createInstanceSession('b','reader',context), /instance_denied/)
  let resolveA, resolveB
  const race=dialog({getInstanceAccounts:id=>new Promise(resolve=>{if(id==='a')resolveA=resolve;else resolveB=resolve})})
  race.instance.asset='a'; const first=race.instance.selectAsset()
  race.instance.asset='b'; const second=race.instance.selectAsset()
  resolveB([{name:'B'}]);await second;resolveA([{name:'A'}]);await first
  assert.strictEqual(race.instance.accounts[0].name,'B')
  assert.strictEqual(race.instance.account,'')
  let issued=0, finish, navigated='', closed=0
  const page={opener:{},location:{replace:x=>{navigated=x}},close:()=>{closed++}}
  const active=dialog({window:{open:()=>page},createInstanceSession:()=>{issued++;return new Promise(r=>{finish=r})}})
  Object.assign(active.instance,{assets:[{id:'b'}],asset:'b',accounts:[{name:'reader'}],account:'reader',context})
  const opening=active.instance.connect();await active.instance.connect();assert.strictEqual(issued,1)
  assert.strictEqual(page.opener,null);assert.strictEqual(navigated,'')
  finish(url);await opening;assert.strictEqual(navigated,url)
  const denied=dialog({window:{open:()=>page},createInstanceSession:async()=>{throw new Error('instance_denied')}}).instance
  Object.assign(denied,{assets:[{id:'b'}],asset:'b',accounts:[{name:'reader'}],account:'reader',context})
  navigated='';await denied.connect();assert.strictEqual(navigated,'');assert.strictEqual(closed,1);assert.strictEqual(denied.error,'instance_denied')
  const blocked=dialog({window:{open:()=>null},createInstanceSession:()=>{throw Error('must not issue')}}).instance
  Object.assign(blocked,{assets:[{id:'b'}],asset:'b',accounts:[{name:'reader'}],account:'reader',context});await blocked.connect();assert.strictEqual(blocked.error,'instance_popup')
  console.log('U01 API/UI: pagination, user/org/CSRF binding, denial, fragment cleanup, account race, independent popup and duplicate-submit checks passed')
}
main().catch(e=>{console.error(e);process.exit(1)})
