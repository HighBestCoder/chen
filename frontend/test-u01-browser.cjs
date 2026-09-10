// Built production UI in Chromium. Core/DB replies are fixtures, not Azure acceptance.
const http=require('http'), fs=require('fs'), path=require('path'), assert=require('assert')
const {chromium}=require('playwright')
const {Server:WebSocketServer}=require('ws')
const dist=process.env.U01_DIST || path.join(__dirname,'dist')
const calls=[], sockets=[], errors=[]
const json=(res,data,status=200)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify(data))}
const server=http.createServer(async(req,res)=>{
  let raw='';for await(const chunk of req)raw+=chunk
  const body=raw?JSON.parse(raw):null, url=new URL(req.url,'http://fixture')
  calls.push({path:req.url,body,headers:req.headers})
  if(url.pathname==='/chen/api/auth')return json(res,{token:body.token==='core-B'?'chen-B':'chen-A',lang:'en-US'})
  if(url.pathname==='/chen/api/profile')return json(res,{dbType:'mongodb',assetName:req.headers.token==='chen-B'?'Instance B':'Instance A',canCopy:true,canPaste:true})
  if(url.pathname==='/chen/api/resources/children')return json(res,body?[]:[{key:'datasource:fixture',label:'Mongo',type:'datasource'}])
  if(url.pathname==='/chen/api/resources/hints')return json(res,[])
  if(url.pathname==='/api/v1/perms/users/self/assets/')return json(res,{results:[{id:'B',name:'Instance B',address:'mongo-B',type:{value:'mongodb'},is_active:true}],next:null})
  if(url.pathname==='/api/v1/perms/users/self/assets/B/')return json(res,{permed_protocols:[{name:'mongodb'}],permed_accounts:[{name:'reader',username:'reader',has_secret:true,has_username:true}]})
  if(url.pathname==='/api/v1/authentication/connection-token/'){
    assert.strictEqual(body.asset,'B');assert.strictEqual(body.account,'reader');assert.strictEqual(body.connect_method,'web_gui')
    assert.strictEqual(req.headers['x-csrftoken'],'fixture');assert.strictEqual(req.headers['x-jms-org'],'org-A');assert(!req.headers.token)
    return json(res,{id:'core-B',is_active:true})
  }
  if(url.pathname.startsWith('/api/'))return json(res,{},404)
  const relative=url.pathname.replace(/^\/chen\//,'') || 'index.html'
  const file=path.join(dist,relative)
  if(!file.startsWith(dist+'/') || !fs.existsSync(file))return json(res,{},404)
  res.writeHead(200,{'Content-Type':file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});fs.createReadStream(file).pipe(res)
})
const wss=new WebSocketServer({server})
wss.on('connection',(ws,req)=>{
  const token=req.headers['sec-websocket-protocol'];sockets.push({path:req.url,token})
  if(req.url==='/chen/ws/session')ws.send(JSON.stringify({type:'set_ready',data:{}}))
  ws.on('message',raw=>{
    const packet=JSON.parse(String(raw))
    if(packet.type==='connect'){
      ws.send(JSON.stringify({type:'init',data:{title:'Query'}}))
      ws.send(JSON.stringify({type:'update_state',data:{title:'Query',loading:false,inQuery:false,currentContext:'db_'+token,contexts:['db_'+token,'other_'+token]}}))
    }
    if(packet.type==='ping')ws.send(JSON.stringify({type:'pong'}))
    if(packet.type==='query_console_action')calls.push({token,action:packet.data})
  })
})
async function main(){
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
  const base='http://127.0.0.1:'+server.address().port
  const browser=await chromium.launch({headless:true,args:['--no-sandbox']})
  try{
    const context=await browser.newContext()
    await context.addCookies([{name:'csrftoken',value:'fixture',url:base},{name:'X-JMS-ORG',value:'org-A',url:base}])
    context.on('page',p=>p.on('pageerror',e=>errors.push(e.message)))
    const page=await context.newPage();await page.goto(base+'/chen/?oid=org-A#connectionToken=core-A')
    await page.getByRole('button',{name:'Select instance / New console'}).waitFor()
    await page.locator('.CodeMirror').evaluate(e=>e.CodeMirror.setValue('db.orders.find({original: true})'))
    await page.getByRole('button',{name:'Select instance / New console'}).click()
    const selects=page.locator('.el-dialog:visible .el-select')
    await selects.nth(0).click();await page.getByText('Instance B (mongo-B)',{exact:true}).click()
    await selects.nth(1).click();await page.getByText('reader (reader)',{exact:true}).click()
    const popupPromise=context.waitForEvent('page')
    await page.getByRole('button',{name:'Open new console',exact:true}).click()
    const popup=await popupPromise
    await popup.getByRole('button',{name:'Select instance / New console'}).waitFor()
    assert.strictEqual(await popup.evaluate(()=>window.opener),null)
    assert(!popup.url().includes('core-B'))
    assert.strictEqual(await page.locator('.CodeMirror').evaluate(e=>e.CodeMirror.getValue()),'db.orders.find({original: true})')
    assert(await popup.locator('.instance-bar').innerText().then(x=>x.includes('Instance B')))
    assert(await page.locator('.instance-bar').innerText().then(x=>x.includes('Instance A')))
    assert(sockets.some(x=>x.path==='/chen/ws/console'&&x.token==='chen-A'))
    assert(sockets.some(x=>x.path==='/chen/ws/console'&&x.token==='chen-B'))
    assert(!calls.some(x=>x.path&&x.path.includes('core-B')))
    await popup.getByText('Current Context: db_chen-B',{exact:true}).click()
    await popup.getByText('other_chen-B',{exact:true}).click()
    await popup.waitForTimeout(100)
    assert(calls.some(x=>x.token==='chen-B'&&x.action?.action==='change_current_context'&&x.action.data==='other_chen-B'))
    assert(!calls.some(x=>x.token==='chen-A'&&x.action?.data==='other_chen-B'))
    await popup.screenshot({path:process.env.U01_SCREENSHOT || '/tmp/u01-console.png'})
    await popup.close()
    assert.strictEqual(await page.locator('.CodeMirror').evaluate(e=>e.CodeMirror.getValue()),'db.orders.find({original: true})')
    assert.deepStrictEqual(errors,[])
    console.log('U01 Chromium: real selector clicks, Core user/CSRF/org request, independent session tokens, original query preservation, database selection isolation, popup close and fragment cleanup passed')
  }finally{await browser.close()}
}
main().catch(e=>{console.error(e);process.exitCode=1}).finally(()=>{for(const ws of wss.clients)ws.terminate();wss.close();server.close()})
