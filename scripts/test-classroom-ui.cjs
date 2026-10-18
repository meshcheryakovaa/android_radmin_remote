// Synthetic browser test. No real VPN, computers, passwords or private keys.
// PLAYWRIGHT_MODULE=/path/to/playwright CHROME_PATH=/path/to/chrome node scripts/test-classroom-ui.cjs
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../app/src/main/assets/www'),shots=path.resolve(__dirname,'../docs/screenshots');
const delay=ms=>new Promise(r=>setTimeout(r,ms));
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||undefined,args:['--no-sandbox']});
 const page=await browser.newPage({viewport:{width:1280,height:800},deviceScaleFactor:1});
 const faults=[],requests=[];let active=0,maxActive=0,slow=false;
 page.on('pageerror',e=>faults.push(e.message));
 await page.addInitScript(()=>{
  const peers=Array.from({length:30},(_,i)=>({id:String(i+1),name:'CAB43-PC'+String(i+1).padStart(2,'0'),ip:'26.1.0.'+(i+1),state:'CONNECTED',networkIds:['synthetic-class']}));
  const s={phase:'CONNECTED',vpnReady:true,ip:'26.1.1.1',nodeName:'synthetic-teacher',rx:0,tx:0,transport:'DIRECT',networks:[{id:'synthetic-class',name:'Синтетический класс',members:31}],peers,remote:[]};
  const cfg={mode:'key',username:'',keyName:'teacher',port:11100,hasKey:true,hasPassword:false,refreshMs:60000,thumbnailWidth:240,jpegQuality:45,network:'',onlineOnly:true};
  window.__mock={s,cfg,calls:[],epoch:0,enabled:false,locked:new Set(),batch:{phase:"IDLE"},batchDelay:30,failId:null};
  window.Native={postMessage(raw){const {id,method,args}=JSON.parse(raw),m=window.__mock;m.calls.push({method,args,time:Date.now()});let result=true;
   switch(method){
    case'Radmin.getState':result=structuredClone(s);break;
    case'Proxy.getConfig':result={mode:'DIRECT',host:'',port:1080,username:'',timeoutMs:10000,profiles:[]};break;
    case'Settings.get':result={nodeName:'synthetic-teacher',autoReconnect:true};break;
    case'Remote.getConfig':result={...cfg};break;
    case'Remote.grid':m.enabled=args.enabled;result=++m.epoch;break;
    case'Remote.saveConfig':Object.assign(cfg,args);delete cfg.password;result={...cfg};break;
    case'Remote.open':{m.enabled=false;const p=peers.find(p=>p.id===args.rid);result={id:'session-'+p.id,rid:p.id,name:p.name,ip:p.ip,phase:'VIEWING',control:false,hasFrame:true,serial:1,width:1920,height:1080};s.remote=[result];break;}
    case'Remote.control':{const r=s.remote.find(s=>s.id===args.id);if(r)r.control=args.enabled;break;}
    case'Remote.close':s.remote=s.remote.filter(s=>s.id!==args.id);break;
    case'Remote.lock':if(args.enabled)m.locked.add(args.rid);else m.locked.delete(args.rid);break;
    case'Remote.batchStatus':result=structuredClone(m.batch);break;
    case'Remote.chooseFile':result={token:'synthetic-file-token',name:'lesson.txt',size:100};break;
    case'Remote.startBatch':{
     const targets=args.rids.map(rid=>peers.find(p=>p.id===rid));m.batch={id:'batch-'+Date.now(),action:args.action,phase:'RUNNING',total:targets.length,completed:0,sent:0,current:'',bytes:0,results:[]};
     result=structuredClone(m.batch);let index=0;const job=m.batch;
     const tick=()=>{if(job.phase!=='RUNNING')return;const p=targets[index++];if(!p){job.phase='FINISHED';return;}
      job.current=p.name;job.completed++;if(p.id!==m.failId)job.sent++;job.results.push({rid:p.id,name:p.name,status:p.id===m.failId?'ERROR':'SENT',error:p.id===m.failId?'CONNECTION_FAILED':null});
      if(args.action==='lock')m.locked.add(p.id);if(args.action==='unlock')m.locked.delete(p.id);
      setTimeout(tick,m.batchDelay);};setTimeout(tick,m.batchDelay);break;
    }
    case'Remote.cancelBatch':m.batch.phase='CANCELLED';break;
   }
   queueMicrotask(()=>window.Native.onmessage({data:JSON.stringify({id,result})}));
  }};
 });
 function desktop(id,large=false,locked=false){const hue=(Number(id)||0)*11%120;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${large?1920:240}" height="${large?1080:135}" viewBox="0 0 240 135"><rect width="240" height="135" fill="#141b25"/><path d="M0 90L90 15L190 135H0" fill="hsl(${hue+150} 24% 25%)"/><path d="M40 135L160 40L240 115V135" fill="hsl(${hue+180} 30% 32%)"/><rect x="22" y="29" width="150" height="75" rx="3" fill="#e6edf4"/><rect x="22" y="29" width="150" height="12" fill="#273341"/><text x="30" y="61" fill="#273341" font-size="10">${locked?'LOCKED · ТЕСТОВЫЙ ПК':'СИНТЕТИЧЕСКИЙ СТЕНД'}</text><text x="30" y="83" fill="#273341" font-size="12">ПК ${String(id).padStart(2,'0')}</text><rect y="125" width="240" height="10" fill="#0d1219"/></svg>`;
 }
 await page.route('https://appassets.androidplatform.net/**',async route=>{
  const url=new URL(route.request().url());
  if(url.pathname.startsWith('/remote/thumb/')){
   const id=url.pathname.split('/').pop();requests.push({id,time:Date.now()});active++;maxActive=Math.max(maxActive,active);
   try{await delay(slow?1000:25);const locked=await page.evaluate(id=>__mock.locked.has(id),id);await route.fulfill({status:200,contentType:'image/svg+xml',body:desktop(id,false,locked),headers:{'Cache-Control':'no-store'}});}catch{}finally{active--;}
   return;
  }
  if(url.pathname.startsWith('/remote/frame/'))return route.fulfill({contentType:'image/svg+xml',body:desktop(1,true)});
  const name=path.basename(url.pathname)||'index.html';const file=path.join(root,name);
  await route.fulfill({contentType:name.endsWith('.html')?'text/html':name.endsWith('.js')?'text/javascript':'text/css',body:fs.readFileSync(file)});
 });
 await page.goto('https://appassets.androidplatform.net/assets/www/index.html');
 await page.click('[data-page="peers"]');
 await page.waitForFunction(()=>document.querySelectorAll('.computer-card img').length===30,{timeout:15000});
 assert.equal(maxActive,1,'snapshot requests must be sequential');
 assert.equal(new Set(requests.map(r=>r.id)).size,30,'every PC must be reached fairly');
 assert.equal(await page.locator('#peers h1,#peers h2,#peers h3,.computer-card input,.computer-card .actions').count(),0,'grid has no headings or per-PC actions');
 assert.equal(await page.locator('[data-batch]').count(),6);
 const ratios=await page.locator('.monitor-preview').evaluateAll(xs=>xs.map(x=>{const r=x.getBoundingClientRect();return r.width/r.height}));assert(ratios.every(r=>Math.abs(r-16/9)<0.02),'every preview must be 16:9');
 const bounds=await page.locator('.computer-card').evaluateAll(cards=>cards.map(c=>{const r=c.getBoundingClientRect();return {bottom:r.bottom,right:r.right,top:r.top};}));
 const navTop=await page.locator('nav').evaluate(n=>n.getBoundingClientRect().top);
 assert(bounds.every(r=>r.bottom<=navTop&&r.right<=1280&&r.top>=0),'all 30 thumbnails must fit in tablet viewport');
 const rowsFill=await page.locator('#peerList').evaluate(grid=>{const bounds=grid.getBoundingClientRect(),rows=new Map();for(const c of grid.querySelectorAll('.computer-card:not([hidden])')){const r=c.getBoundingClientRect(),y=Math.round(r.top);rows.set(y,Math.max(rows.get(y)||0,r.right));}return [...rows.values()].every(right=>Math.abs(right-bounds.right)<2);});assert(rowsFill,'every row, including the last, must fill the width');
 fs.mkdirSync(shots,{recursive:true});await page.screenshot({path:path.join(shots,'v042-classroom-1280-synthetic.png')});
 const count=requests.length;await delay(400);assert.equal(requests.length,count,'minimum per-PC refresh interval must be respected');
 await page.locator('.computer-card').first().click();
 assert.equal(await page.locator('#computerMenu').isVisible(),true);
 await page.click('#computerView');await page.waitForSelector('#remoteViewer:not([hidden])');
 const viewStart=requests.length;await delay(500);assert.equal(requests.length,viewStart,'viewing must suspend thumbnail queue');
 await page.keyboard.press('a');
 assert.equal(await page.evaluate(()=>__mock.calls.filter(c=>c.method==='Remote.input').length),0,'view-only must not send keyboard');
 await page.screenshot({path:path.join(shots,'v042-viewer-1280-synthetic.png')});
 await page.click('#toggleControl');await page.keyboard.press('a');
 await page.waitForFunction(()=>__mock.calls.some(c=>c.method==='Remote.input'));
 const controlCount=requests.length;await delay(400);assert.equal(requests.length,controlCount,'control must suspend grid');
 await page.click('#closeViewer');await page.waitForFunction(()=>__mock.enabled);
 assert.equal(await page.evaluate(()=>__mock.s.remote.length),0,'viewer socket must close before grid resumes');
 const lockBefore=requests.filter(r=>r.id==='1').length;
 await page.locator('.computer-card').first().click();await page.click('#computerLock');await page.waitForFunction(()=>__mock.locked.has('1'));
 await page.waitForTimeout(2400);assert(requests.filter(r=>r.id==='1').length>=lockBefore+2,'locked PC must still fetch fresh frames immediately');
 await page.evaluate(()=>document.getElementById('toast').hidden=true);await page.screenshot({path:path.join(shots,'v042-locked-synthetic.png')});
 await page.locator('.computer-card').first().click();await page.click('#computerUnlock');await page.waitForFunction(()=>!__mock.locked.has('1'));
 // Only peers that pass the active network filter are batch targets, never hidden PCs.
 await page.evaluate(()=>{for(const p of __mock.s.peers)p.networkIds=Number(p.id)<=5?['synthetic-class']:['other-class'];__mock.cfg.network='synthetic-class';});
 await page.click('[data-page="settings"]');await page.locator('[name="network"]').selectOption('synthetic-class');await page.locator('#remoteForm button').first().click();
 await page.click('[data-page="peers"]');await page.waitForFunction(()=>[...document.querySelectorAll('.computer-card')].filter(c=>!c.hidden).length===5);
 const five=await page.locator('.computer-card:not([hidden]) .monitor-preview').first().boundingBox();assert(five.width>300,'five PCs must expand, not retain the 30-place geometry');
 await page.evaluate(()=>document.getElementById('toast').hidden=true);
 await page.screenshot({path:path.join(shots,'v042-classroom-5-1280-synthetic.png')});
 for(const action of ['shutdown','reboot','lock','unlock','website','file']){
  await page.locator(`[data-batch="${action}"]`).click();
  if(action==='website')await page.locator('#batchUrl').fill('https://example.org/lesson');
  if(action==='file'){await page.click('#chooseBatchFile');await page.waitForFunction(()=>document.getElementById('batchFileInfo').textContent.includes('lesson.txt'));}
  await page.click('#batchSubmit');
  await page.waitForFunction(()=>__mock.batch.phase==='FINISHED');await page.waitForFunction(()=>document.getElementById('batchCancel').textContent==='Закрыть');
  const args=await page.evaluate(()=>__mock.calls.filter(c=>c.method==='Remote.startBatch').at(-1).args);assert.deepEqual(args.rids,['1','2','3','4','5'],'bulk action must target exactly displayed peers');
  if(action==='website')assert.equal(args.url,'https://example.org/lesson');if(action==='file')assert.equal(args.fileToken,'synthetic-file-token');
  await page.click('#batchCancel');
 }
 // A failure at one target cannot stop other displayed targets; cancellation prevents further sends.
 await page.evaluate(()=>__mock.failId='3');await page.locator('[data-batch="reboot"]').click();await page.click('#batchSubmit');
 await page.waitForFunction(()=>__mock.batch.phase==='FINISHED');await page.waitForFunction(()=>document.getElementById('batchCancel').textContent==='Закрыть');
 assert.equal(await page.evaluate(()=>__mock.batch.sent),4);assert.equal(await page.locator('#batchResults .failed').count(),1);await page.click('#batchCancel');
 await page.evaluate(()=>{__mock.failId=null;__mock.batchDelay=1000;});await page.locator('[data-batch="shutdown"]').click();await page.click('#batchSubmit');await page.click('#batchCancel');
 await page.waitForFunction(()=>__mock.batch.phase==='CANCELLED');await page.waitForFunction(()=>document.getElementById('batchCancel').textContent==='Закрыть');
 assert(await page.evaluate(()=>__mock.batch.completed<5));await page.click('#batchCancel');await page.evaluate(()=>__mock.batchDelay=30);
 // Restore 30 peers for the original pause, timing and portrait assertions.
 await page.evaluate(()=>{for(const p of __mock.s.peers)p.networkIds=['synthetic-class'];__mock.cfg.network='';});
 await page.click('[data-page="settings"]');await page.locator('[name="network"]').selectOption('');await page.locator('[name="refreshMs"]').fill('250');
 await page.locator('[name="thumbnailWidth"]').selectOption('160');await page.locator('[name="jpegQuality"]').fill('30');
 await page.locator('#remoteForm button[type=submit]').count();await page.locator('#remoteForm button').first().click();
 await page.waitForFunction(()=>__mock.cfg.refreshMs===250&&__mock.cfg.thumbnailWidth===160&&__mock.cfg.jpegQuality===30);
 await page.click('[data-page="peers"]');
 // Cancel one slow in-flight thumbnail when opening a viewer; no stale image may be installed.
 slow=true;await page.waitForFunction(()=>document.querySelectorAll('.computer-card img').length===30);
 await delay(80);await page.locator('.computer-card').nth(1).click();await page.click('#computerControl');
 await page.waitForSelector('#remoteViewer:not([hidden])');const paused=requests.length;await delay(1200);assert.equal(requests.length,paused);
 await page.click('#closeViewer');slow=false;
 for(const width of [1536,390,320]){
  await page.setViewportSize({width,height:width>700?864:780});await delay(150);
  const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth);
  assert.equal(overflow,false,`horizontal overflow at ${width}`);
  await page.evaluate(()=>document.getElementById('toast').hidden=true);
  await page.screenshot({path:path.join(shots,`v042-classroom-${width}-synthetic.png`)});
 }
 assert.deepEqual(faults,[],'browser runtime errors');
 console.log(JSON.stringify({screens:30,maxConcurrentThumbnails:maxActive,requests:requests.length,viewControlPause:true,lockUnlock:true,lockedFramesContinue:true,bulkActions:6,filteredTargets:5,ratio:'16:9',settings:true,widths:[1280,1536,390,320],errors:faults}));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
