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
  if(url.pathname==='/chen/api/auth' && body.token==='bad'){res.writeHead(403,{'Content-Type':'text/plain'});return res.end('Connection authentication failed. Check credentials and authorization.')}
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
    if(packet.type==='query_console_action') {
      calls.push({token,action:packet.data})
      if(packet.data.action==='run_sql')setTimeout(()=>{
        ws.send(JSON.stringify({type:'new_data_view',data:{title:'fixture-result'}}))
        ws.send(JSON.stringify({type:'update_data_view',data:{title:'fixture-result',data:{revision:1,fields:[{name:'id'},{name:'value'}],data:Array.from({length:20},(_,id)=>({id,value:id===0?null:id===1?'':id===2?'NULL':'<script>literal</script>'}))}}}))
        ws.send(JSON.stringify({type:'update_state',data:{title:'fixture-result',limit:50,total:20,page:1,paged:false,maxDisplayLimit:50000}}))
        ws.send(JSON.stringify({type:'update_state',data:{title:'Query',inQuery:false,canCancel:false,contexts:[]}}))
      },300)
    }
    if(packet.type==='data_view_action')calls.push({token,export:packet.data})
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
    await page.locator('.CodeMirror').waitFor()
    await page.locator('.CodeMirror').evaluate(e=>e.CodeMirror.setValue('SELECT fixture'))
    await page.locator('button:has(.icon-chen-play)').dblclick()
    await page.locator('.htCore td').first().waitFor()
    assert.equal(calls.filter(x=>x.action?.action==='run_sql').length,1,'double click sent twice')
    const nullCell=page.locator('.htCore tbody tr').nth(0).locator('td').nth(1)
    assert.equal(await nullCell.innerText(),'NULL')
    assert((await nullCell.getAttribute('class')).includes('chen-null'))
    const emptyCell=page.locator('.htCore tbody tr').nth(1).locator('td').nth(1)
    assert.equal(await emptyCell.innerText(),'')
    assert(!(await emptyCell.getAttribute('class')).includes('chen-null'))
    assert.equal(await page.locator('.htCore tbody tr').nth(3).locator('td').nth(1).innerText(),'<script>literal</script>')
    const headers=page.locator('.ht_clone_left tbody th')
    await headers.nth(0).click();await headers.nth(9).click({modifiers:['Shift']})
    await page.locator('button:has(.icon-chen-arrow-to-bottom)').click()
    await page.getByText('Export selected rows',{exact:true}).click()
    await page.getByRole('button',{name:'Confirm',exact:true}).click()
    await page.waitForTimeout(100)
    const exported=calls.find(x=>x.export?.action==='export')
    assert(exported,'no export request')
    assert.deepEqual(exported.export.data.rowIndices,Array.from({length:10},(_,i)=>i))
    assert.equal(exported.export.data.revision,1)
    const consoleSocket=[...wss.clients].find(ws=>ws.readyState===1 && ws!==[...wss.clients][0])
    for(let i=0;i<2;i++){
      consoleSocket.send(JSON.stringify({type:'message',data:{title:'Parse error',message:'Unclosed command '+i,type:'error'}}))
      await page.getByText('Unclosed command '+i,{exact:true}).waitFor({state:'visible'})
      await page.locator('.query-message .el-alert__closebtn').click()
      await page.waitForTimeout(30)
    }
    // Exact DEF-10 scenario: two statements with a 300 KB string, not the statement budget.
    const sentBefore=calls.filter(x=>x.action?.action==='run_sql').length
    await page.locator('.CodeMirror').evaluate(e=>e.CodeMirror.setValue("var s='"+'x'.repeat(300000)+"'; s.length;"))
    await page.locator('button:has(.icon-chen-play)').click()
    const tooLarge=page.getByText('Maximum command size is 256 KiB UTF-8.',{exact:true})
    await tooLarge.waitFor({state:'visible'})
    await page.waitForTimeout(5500)
    assert(await tooLarge.isVisible(),'source-limit error vanished before user dismissal')
    assert.equal(calls.filter(x=>x.action?.action==='run_sql').length,sentBefore,'oversized input sent to backend')
    await page.locator('.query-message .el-alert__closebtn').click()
    await page.locator('.CodeMirror').evaluate(e=>e.CodeMirror.setValue('SELECT recovered'))
    await page.locator('button:has(.icon-chen-play)').click()
    await page.waitForTimeout(500)
    assert.equal(calls.filter(x=>x.action?.action==='run_sql').length,sentBefore+1,'query did not recover after rejection')
    const failed=await context.newPage();await failed.goto(base+'/chen/#connectionToken=bad')
    await failed.getByText('Connection authentication failed. Check credentials and authorization.',{exact:true}).last().waitFor()
    assert(await failed.locator('.el-dialog:visible').innerText().then(x=>x.includes('Connection failed')))
    await page.screenshot({path:'/tmp/qa260911-grid.png'})
    assert.deepStrictEqual(errors,[])
    console.log('RUN-260911 Chromium: double-click, NULL/empty/literal/HTML, real row-header selection through export dialog, repeated error banners, persistent auth failure passed')
  }finally{await browser.close()}
}
main().catch(e=>{console.error(e);process.exitCode=1}).finally(()=>{for(const ws of wss.clients)ws.terminate();wss.close();server.close()})
