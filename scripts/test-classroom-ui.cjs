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
  window.__mock={s,cfg,calls:[],epoch:0,enabled:false};
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
    case'Remote.lock':m.enabled=false;break;
   }
   queueMicrotask(()=>window.Native.onmessage({data:JSON.stringify({id,result})}));
  }};
 });
 function desktop(id,large=false){const hue=(Number(id)||0)*11%120;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${large?1920:240}" height="${large?1080:135}" viewBox="0 0 240 135"><rect width="240" height="135" fill="#141b25"/><path d="M0 90L90 15L190 135H0" fill="hsl(${hue+150} 24% 25%)"/><path d="M40 135L160 40L240 115V135" fill="hsl(${hue+180} 30% 32%)"/><rect x="22" y="29" width="150" height="75" rx="3" fill="#e6edf4"/><rect x="22" y="29" width="150" height="12" fill="#273341"/><text x="30" y="61" fill="#273341" font-size="10">СИНТЕТИЧЕСКИЙ СТЕНД</text><text x="30" y="83" fill="#273341" font-size="12">ПК ${String(id).padStart(2,'0')}</text><rect y="125" width="240" height="10" fill="#0d1219"/></svg>`;
 }
 await page.route('https://appassets.androidplatform.net/**',async route=>{
  const url=new URL(route.request().url());
  if(url.pathname.startsWith('/remote/thumb/')){
   const id=url.pathname.split('/').pop();requests.push({id,time:Date.now()});active++;maxActive=Math.max(maxActive,active);
   try{await delay(slow?1000:25);await route.fulfill({status:200,contentType:'image/svg+xml',body:desktop(id),headers:{'Cache-Control':'no-store'}});}catch{}finally{active--;}
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
 assert.equal(await page.locator('#peers h1,#peers h2,#peers h3,#peers input,#peers .actions').count(),0,'grid has no toolbar, headings or per-PC actions');
 const bounds=await page.locator('.computer-card').evaluateAll(cards=>cards.map(c=>{const r=c.getBoundingClientRect();return {bottom:r.bottom,right:r.right,top:r.top};}));
 const navTop=await page.locator('nav').evaluate(n=>n.getBoundingClientRect().top);
 assert(bounds.every(r=>r.bottom<=navTop&&r.right<=1280&&r.top>=0),'all 30 thumbnails must fit in tablet viewport');
 fs.mkdirSync(shots,{recursive:true});await page.screenshot({path:path.join(shots,'v041-classroom-1280-synthetic.png')});
 const count=requests.length;await delay(400);assert.equal(requests.length,count,'minimum per-PC refresh interval must be respected');
 await page.locator('.computer-card').first().click();
 assert.equal(await page.locator('#computerMenu').isVisible(),true);
 await page.click('#computerView');await page.waitForSelector('#remoteViewer:not([hidden])');
 const viewStart=requests.length;await delay(500);assert.equal(requests.length,viewStart,'viewing must suspend thumbnail queue');
 await page.keyboard.press('a');
 assert.equal(await page.evaluate(()=>__mock.calls.filter(c=>c.method==='Remote.input').length),0,'view-only must not send keyboard');
 await page.screenshot({path:path.join(shots,'v041-viewer-1280-synthetic.png')});
 await page.click('#toggleControl');await page.keyboard.press('a');
 await page.waitForFunction(()=>__mock.calls.some(c=>c.method==='Remote.input'));
 const controlCount=requests.length;await delay(400);assert.equal(requests.length,controlCount,'control must suspend grid');
 await page.click('#closeViewer');await page.waitForFunction(()=>__mock.enabled);
 assert.equal(await page.evaluate(()=>__mock.s.remote.length),0,'viewer socket must close before grid resumes');
 await page.locator('.computer-card').first().click();await page.click('#computerLock');await page.waitForFunction(()=>__mock.calls.some(c=>c.method==='Remote.lock'&&c.args.enabled));
 await page.locator('.computer-card').first().click();await page.click('#computerUnlock');await page.waitForFunction(()=>__mock.calls.some(c=>c.method==='Remote.lock'&&!c.args.enabled));
 await page.click('[data-page="settings"]');await page.locator('[name="refreshMs"]').fill('250');
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
  await page.screenshot({path:path.join(shots,`v041-classroom-${width}-synthetic.png`)});
 }
 assert.deepEqual(faults,[],'browser runtime errors');
 console.log(JSON.stringify({screens:30,maxConcurrentThumbnails:maxActive,requests:requests.length,viewControlPause:true,lockUnlock:true,settings:true,widths:[1280,1536,390,320],errors:faults}));
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1);});
