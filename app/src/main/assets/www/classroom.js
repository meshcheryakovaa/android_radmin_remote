'use strict';
(() => {
  const $ = id => document.getElementById(id), api = window.ClassroomApi;
  const cards = new Map(), hardwareKeys = new Map();
  let state = {peers:[],networks:[],remote:[],vpnReady:false}, current = null, control = false, opening=false, menuPeer=null;
  let lastPoint={x:0,y:0}, inputQueue=Promise.resolve(), queued=0, moving=false, page='home';
  let config=null, gridDesired=false, gridToken=null, gridVersion=0, gridQueue=Promise.resolve(), activeLoad=null, cursor=0, lastIdentity=null;
  let batchState={phase:"IDLE"}, batchAction=null, batchTargets=[], batchFile=null, batchPicking=false, batchStarting=false, progressOpen=false;
  let displayAll=true, displayIds=new Set(), selectionLoaded=false, selectionVersion=0, selectionQueue=Promise.resolve();
  const pickerRows=new Map();
  const urgent=new Map(), completedBatches=new Set();
  const errors = {
    BATCH_BUSY:'Дождитесь завершения общего действия или отмените его.', INVALID_COMMAND:'Некорректная команда или список ПК.', INVALID_URL:'Введите адрес сайта с http:// или https://.', INVALID_FILE:'Не удалось прочитать выбранный файл.', FILE_TOO_LARGE:'Файл превышает 200 МБ.', FILE_REQUIRED:'Сначала выберите файл.', CANCELLED:'Действие отменено.',
    VPN_REQUIRED:'Сначала подключите VPN и дождитесь готовности интерфейса.', PEER_NOT_CONNECTED:'ПК недоступен через Radmin.',
    CREDENTIALS_REQUIRED:'Откройте «Настройки» → «Доступ к установленному Veyon» и сохраните ключ или учётную запись Windows.',
    AUTH_METHOD_UNAVAILABLE:'Проверьте способ входа в Veyon Configurator.', AUTH_FAILED:'Veyon не принял данные входа.',
    ACCESS_DENIED:'Veyon отклонил доступ. Проверьте правила доступа или запрос подтверждения на ПК.',
    NOT_VEYON:'На этом порту нет совместимого Veyon Server. Проверьте службу и порт 11100.',
    CONNECTION_FAILED:'Соединение с Veyon закрыто или недоступно. Проверьте службу, порт и брандмауэр ПК.',
    MEMORY_LIMIT:'Недостаточно памяти для разрешения этого экрана.', SCREEN_TOO_LARGE:'Разрешение экрана превышает лимит клиента.',
    CLOSE_SESSIONS_FIRST:'Сначала закройте удалённый экран, затем меняйте доступ.',
    INVALID_KEY:'Нужен приватный RSA-ключ Veyon в PEM PKCS#1 / PKCS#8 без пароля файла.',
    INVALID_SETTINGS:'Проверьте настройки: интервал 250–60000 мс, ширина 160–480, качество 25–80%.',
    SESSION_NOT_READY:'Экран ещё не подключён или уже закрыт.', CONTROL_REQUIRED:'Сначала включите управление выбранным ПК.',
    INVALID_MESSAGE:'Veyon прислал неподдерживаемое сообщение протокола.', INVALID_RECTANGLE:'Некорректный размер области экрана.',
    UNSUPPORTED_ENCODING:'Неподдерживаемое кодирование экрана.', INVALID_ZLIB:'Не удалось распаковать кадр.',
    UNSUPPORTED_MESSAGE:'Неподдерживаемое сообщение Veyon.', INVALID_INPUT:'Некорректное событие ввода.', SETTINGS_SAVE_FAILED:'Не удалось сохранить настройки.'
  };
  function errorText(value){return errors[String(value||'').replace(/^VEYON:/,'')]||'Не удалось выполнить операцию Veyon. Проверьте настройки доступа.';}
  async function call(method,args={}){try{return await api.call(method,args);}catch(e){throw new Error(errorText(e.message));}}
  function act(fn){return api.action(fn);}
  function text(tag,value,cls){const n=document.createElement(tag);n.textContent=value;if(cls)n.className=cls;return n;}
  function sessions(){return state.remote||[];}
  function validSession(id){return sessions().find(s=>s.id===id);}
  function allowed(p){return (displayAll||displayIds.has(p.id))&&(!config?.onlineOnly||p.state==='CONNECTED')&&(!config?.network||p.networkIds?.includes(config.network));}
  function visiblePeers(){return (state.peers||[]).filter(allowed);}
  function pickerPeers(){return (state.peers||[]).filter(p=>!config?.network||p.networkIds?.includes(config.network));}
  function renderPicker(){
    const busy=batchStarting||batchState.phase==='RUNNING',query=$('computerPickerSearch').value.trim().toLocaleLowerCase();
    const peers=pickerPeers(),known=new Set(peers.map(p=>p.id));
    $('computerPickerSummary').textContent='ПК · '+visiblePeers().length+' ▾';
    for(const[id,row]of pickerRows)if(!known.has(id)){row.label.remove();pickerRows.delete(id);}
    for(const p of peers){let row=pickerRows.get(p.id);if(!row){const label=text('label','','picker-option'),input=document.createElement('input'),name=text('span',p.name);
      input.type='checkbox';input.setAttribute('aria-label',p.name);input.onchange=()=>{
        const ids=displayAll?new Set((state.peers||[]).map(p=>p.id)):new Set(displayIds);
        if(input.checked)ids.add(p.id);else ids.delete(p.id);changeDisplay(false,ids);
      };label.append(input,name);$('computerPickerList').append(label);row={label,input,name};pickerRows.set(p.id,row);}
      row.label.hidden=!!query&&!(p.name+' '+p.ip).toLocaleLowerCase().includes(query);row.input.checked=displayAll||displayIds.has(p.id);row.input.disabled=busy;
      row.name.textContent=p.name;row.input.setAttribute('aria-label',p.name);row.label.title=p.name+' · '+p.ip;row.label.classList.toggle('offline',p.state!=='CONNECTED');
    }
    $('computerPickerAll').disabled=$('computerPickerNone').disabled=busy;
  }
  function changeDisplay(all,ids){
    if(batchStarting||batchState.phase==='RUNNING'){renderPicker();return;}
    const previous={all:displayAll,ids:displayIds},version=++selectionVersion;
    displayAll=all;displayIds=ids;updateGrid(true);render(state);
    const args={displayAll:all,displayIds:all?[]:[...ids]};
    selectionQueue=selectionQueue.catch(()=>{}).then(()=>call('Remote.saveDisplay',args)).catch(e=>{
      if(version===selectionVersion){displayAll=previous.all;displayIds=previous.ids;render(state);updateGrid(true);}api.toast(e.message);
    });
  }
  $('computerPickerAll').onclick=()=>changeDisplay(true,new Set());
  $('computerPickerNone').onclick=()=>changeDisplay(false,new Set());
  $('computerPickerSearch').oninput=renderPicker;
  document.addEventListener('pointerdown',e=>{if(!$('computerPicker').contains(e.target))$('computerPicker').open=false;});
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&$('computerPicker').open){e.preventDefault();$('computerPicker').open=false;$('computerPickerSummary').focus();}});
  function credentialsReady(){return config&&(config.mode==='key'?config.hasKey:config.hasPassword&&config.username);}
  function setGrid(enabled){
    gridDesired=enabled;gridToken=null;const version=++gridVersion;
    if(activeLoad)activeLoad.cancel();
    gridQueue=gridQueue.catch(()=>{}).then(()=>call('Remote.grid',{enabled})).then(token=>{if(version===gridVersion&&enabled)gridToken=token;}).catch(()=>{});
    return gridQueue;
  }
  function updateGrid(force=false){const desired=page==='peers'&&!document.hidden&&!current&&!opening&&!batchPicking&&!(batchState.phase==='RUNNING'&&batchState.action==='file')&&state.vpnReady&&!!credentialsReady();if(force||desired!==gridDesired)setGrid(desired);}
  function layout(){
    if(page!=='peers')return;
    document.body.style.setProperty('--class-nav',Math.ceil(document.querySelector('nav').getBoundingClientRect().height)+'px');
    const grid=$('peerList'),r=grid.getBoundingClientRect(),peers=visiblePeers(),n=Math.max(1,Math.min(30,peers.length));
    const gap=parseFloat(getComputedStyle(grid).columnGap)||0,label=parseFloat(getComputedStyle(document.querySelector('.computer-name')||grid).lineHeight)||12;
    let counts=[n];
    // Balance each row independently: the last row also spans the full width.
    for(let rows=1;rows<=n;rows++){
      const base=Math.floor(n/rows),extra=n%rows,list=Array.from({length:rows},(_,i)=>base+(i<extra?1:0));
      const height=list.reduce((sum,count)=>sum+(r.width-gap*(count-1))/count*9/16+label+2,0)+gap*(rows-1);
      if(height<=r.height+1)counts=list;
    }
    let index=0;
    for(const count of counts)for(let j=0;j<count&&index<peers.length;j++,index++){
      const card=cards.get(peers[index].id)?.card;if(card)card.style.width=`calc((100% - ${gap*(count-1)}px) / ${count})`;
    }
    const continuation=counts[0];
    while(index<peers.length){const count=Math.min(continuation,peers.length-index);
      for(let j=0;j<count;j++,index++){const card=cards.get(peers[index].id)?.card;if(card)card.style.width=`calc((100% - ${gap*(count-1)}px) / ${count})`;}}

  }
  function makeCard(p){
    const card=text('button','','computer-card');card.type='button';
    const preview=text('span','','monitor-preview'),icon=text('span','','monitor-icon'),name=text('span',p.name,'computer-name');
    preview.append(icon);card.append(preview,name);card.onclick=()=>showMenu(p.id);
    return{card,preview,icon,name,img:null,at:0,retry:0,ip:p.ip};
  }
  function clearImage(c){if(c.img)c.img.remove();c.img=null;c.at=0;c.retry=0;c.icon.hidden=false;}
  function render(s){
    state=s;
    const identity=s.vpnReady?s.ip:null;
    if(identity!==lastIdentity){urgent.clear();lastIdentity=identity;for(const c of cards.values())clearImage(c);updateGrid(true);}
    const networks=s.networks||[],select=$('classNetwork'),opts=JSON.stringify(networks.map(n=>[n.id,n.name]));
    if(select.dataset.options!==opts){const value=select.value;select.replaceChildren(new Option('Все сети',''),...networks.map(n=>new Option(n.name,n.id)));if([...select.options].some(o=>o.value===value))select.value=value;select.dataset.options=opts;}
    const peers=s.peers||[],shown=new Set(visiblePeers().map(p=>p.id)),known=new Set(peers.map(p=>p.id));
    for(const[id,c]of cards)if(!known.has(id)){c.card.remove();cards.delete(id);}
    for(const p of peers){let c=cards.get(p.id);if(!c){c=makeCard(p);cards.set(p.id,c);$('peerList').append(c.card);}if(c.ip!==p.ip){clearImage(c);c.ip=p.ip;}
      c.card.hidden=!shown.has(p.id);c.name.textContent=p.name;c.card.classList.toggle('offline',p.state!=='CONNECTED');
      c.card.setAttribute('aria-label',p.name+(p.state==='CONNECTED'?'':' · недоступен'));c.card.title=p.name;
    }
    if(current){const session=validSession(current.id);if(session){current=session;control=session.control===true;
      if(wantControl&&session.phase==='VIEWING'&&session.hasFrame){wantControl=false;act(async()=>{await call('Remote.control',{id:session.id,enabled:true});if(current?.id===session.id){control=true;current.control=true;updateViewer();}});}
      updateViewer();}}
    if(menuPeer)updateMenu();
    renderBatch();updateGrid();layout();
  }
  function showMenu(rid){menuPeer=rid;updateMenu();$('computerMenu').hidden=false;$('computerCancel').focus();}
  function closeMenu(){const id=menuPeer;menuPeer=null;$('computerMenu').hidden=true;cards.get(id)?.card.focus({preventScroll:true});}
  function updateMenu(){const p=state.peers.find(p=>p.id===menuPeer);$('computerTitle').textContent=p?.name||'Компьютер';
    $('computerStatus').textContent=p?.state==='CONNECTED'?'':'ПК недоступен';
    for(const id of ['computerView','computerControl','computerLock','computerUnlock'])$(id).disabled=!state.vpnReady||p?.state!=='CONNECTED';}
  let wantControl=false;
  async function openPeer(rid,enableControl){
    opening=true;wantControl=enableControl;closeMenu();await setGrid(false);
    try{const s=await call('Remote.open',{rid});state.remote=[s];showViewer(s);}finally{opening=false;updateGrid();}
  }
  $('computerView').onclick=()=>act(()=>openPeer(menuPeer,false));
  $('computerControl').onclick=()=>act(()=>openPeer(menuPeer,true));
  function refreshPeer(rid){const c=cards.get(rid);if(c){c.at=0;c.retry=0;urgent.set(rid,{next:Date.now()+500,left:3});}}
  for(const [id,enabled]of [['computerLock',true],['computerUnlock',false]])$(id).onclick=()=>act(async()=>{
    const rid=menuPeer;closeMenu();
    // Keep reading actual images during and after lock; a command is never a fake screenshot.
    await call('Remote.lock',{rid,enabled});refreshPeer(rid);updateGrid(true);
    api.toast(enabled?'Команда блокировки отправлена.':'Команда разблокировки отправлена.');
  });
  $('computerCancel').onclick=closeMenu;
  $('computerMenu').onclick=e=>{if(e.target===$('computerMenu'))closeMenu();};
  window.addEventListener('keydown',e=>{if(e.key==='Escape'&&menuPeer){e.preventDefault();closeMenu();}});
  function image(img,s,width){
    if(!s?.hasFrame||s.phase!=='VIEWING')return;if(img.dataset.pending==='1')return;
    img.dataset.pending='1';img.onload=()=>{img.dataset.pending='0';img.hidden=false;};img.onerror=()=>{img.dataset.pending='0';};
    img.src='/remote/frame/'+encodeURIComponent(s.id)+'?width='+width+'&t='+Date.now();
  }
  function updateViewer(){
    if(!current)return;
    $('viewerTitle').textContent=current.name+' · '+current.ip;
    $('viewerStatus').textContent=current.error?errorText(current.error):current.phase==='CONNECTING'?'Подключение к Veyon…':current.phase==='STOPPED'?'Экран отключён':control?'УПРАВЛЕНИЕ · мышь и клавиатура активны':'ТОЛЬКО ПРОСМОТР';
    $('toggleControl').textContent=control?'Выключить управление':'Включить управление';$('toggleControl').disabled=current.phase!=='VIEWING'||!current.hasFrame;
    $('viewerStage').classList.toggle('control',control);$('viewerEmpty').hidden=!!current.hasFrame;$('viewerEmpty').textContent=current.error?errorText(current.error):current.phase==='STOPPED'?'Экран отключён':'Ожидание первого кадра…';
    $('remoteText').disabled=!control;document.querySelectorAll('[data-remote-key],[data-wheel],#rightClick,#remoteTextForm button').forEach(b=>b.disabled=!control);
    if(!current.hasFrame){$('viewerImage').hidden=true;$('viewerImage').removeAttribute('src');}
  }
  function showViewer(s){current=s;control=s.control===true;$('remoteViewer').hidden=false;document.body.classList.add('viewer-open');$('viewerImage').dataset.pending='0';lastPoint={x:0,y:0};updateViewer();}
  function hideViewer(){
    const old=current;current=null;control=false;hardwareKeys.clear();
    $('remoteViewer').hidden=true;document.body.classList.remove('viewer-open');$('viewerImage').removeAttribute('src');
    opening=true;
    inputQueue=inputQueue.catch(()=>{}).then(async()=>{if(old)await call('Remote.close',{id:old.id});})
      .catch(e=>api.toast(e.message)).finally(()=>{if(old)state.remote=sessions().filter(s=>s.id!==old.id);opening=false;updateGrid();});
  }
  function send(events){if(!current||!control||!events.length||queued>=24)return Promise.resolve();const id=current.id;queued++;inputQueue=inputQueue.catch(()=>{}).then(()=>{if(current?.id!==id||!control)return;return call('Remote.input',{id,events});}).catch(e=>{control=false;updateViewer();api.toast(e.message);}).finally(()=>queued--);return inputQueue;}
  function point(e){if(!current)return null;const r=$('viewerImage').getBoundingClientRect(),scale=Math.min(r.width/current.width,r.height/current.height);if(!Number.isFinite(scale)||scale<=0)return null;const x=(e.clientX-r.left-(r.width-current.width*scale)/2)/scale,y=(e.clientY-r.top-(r.height-current.height*scale)/2)/scale;if(x<0||y<0||x>=current.width||y>=current.height)return null;return{x:Math.floor(x),y:Math.floor(y)};}
  function pointer(p,buttons){lastPoint=p;return send([{type:'pointer',...p,buttons}]);}
  const img=$('viewerImage');img.tabIndex=0;
  img.oncontextmenu=e=>{if(control)e.preventDefault();};
  img.onpointerdown=e=>{if(!control||!current?.hasFrame)return;const p=point(e);if(!p)return;e.preventDefault();img.focus({preventScroll:true});img.setPointerCapture(e.pointerId);pointer(p,e.button===2?4:e.button===1?2:1);};
  img.onpointermove=e=>{if(!control||moving)return;const p=point(e);if(!p)return;moving=true;setTimeout(()=>moving=false,50);const buttons=(e.buttons&1?1:0)|(e.buttons&2?4:0)|(e.buttons&4?2:0);pointer(p,buttons);};
  img.onpointerup=img.onpointercancel=e=>{if(!control)return;e.preventDefault();pointer(point(e)||lastPoint,0);if(img.hasPointerCapture(e.pointerId))img.releasePointerCapture(e.pointerId);};
  img.addEventListener('wheel',e=>{if(!control)return;e.preventDefault();const p=point(e)||lastPoint;send([{type:'pointer',...p,buttons:e.deltaY<0?8:16},{type:'pointer',...p,buttons:0}]);},{passive:false});
  $('rightClick').onclick=()=>send([{type:'pointer',...lastPoint,buttons:4},{type:'pointer',...lastPoint,buttons:0}]);
  document.querySelectorAll('[data-wheel]').forEach(b=>b.onclick=()=>send([{type:'pointer',...lastPoint,buttons:b.dataset.wheel==='up'?8:16},{type:'pointer',...lastPoint,buttons:0}]));
  const keys={escape:0xff1b,tab:0xff09,enter:0xff0d,backspace:0xff08,win:0xffeb};
  function combo(list){return send([...list.map(key=>({type:'key',key,down:true})),...list.slice().reverse().map(key=>({type:'key',key,down:false}))]);}
  document.querySelectorAll('[data-remote-key]').forEach(b=>b.onclick=()=>combo(b.dataset.remoteKey==='cad'?[0xffe3,0xffe9,0xffff]:[keys[b.dataset.remoteKey]]));
  $('toggleControl').onclick=()=>act(async()=>{if(!current)return;const id=current.id,enabled=!control;control=false;await inputQueue.catch(()=>{});await call('Remote.control',{id,enabled});if(current?.id===id){control=enabled;current.control=enabled;hardwareKeys.clear();updateViewer();if(enabled)img.focus({preventScroll:true});}});
  $('closeViewer').onclick=()=>hideViewer();
  const keyMap={Escape:0xff1b,Tab:0xff09,Enter:0xff0d,Backspace:0xff08,Delete:0xffff,Insert:0xff63,Home:0xff50,End:0xff57,PageUp:0xff55,PageDown:0xff56,ArrowLeft:0xff51,ArrowUp:0xff52,ArrowRight:0xff53,ArrowDown:0xff54,Control:0xffe3,Alt:0xffe9,Shift:0xffe1,Meta:0xffeb};
  function keySymbol(e){if(keyMap[e.key])return keyMap[e.key];if(/^F([1-9]|1[0-2])$/.test(e.key))return 0xffbd+Number(e.key.slice(1));if([...e.key].length===1){const c=e.key.codePointAt(0);return c<=255?c:0x01000000+c;}return null;}
  for(const type of ['keydown','keyup'])window.addEventListener(type,e=>{if(!current||!control||['INPUT','TEXTAREA','SELECT','BUTTON'].includes(e.target.tagName)||e.isComposing)return;const key=type==='keyup'?(hardwareKeys.get(e.code)||keySymbol(e)):keySymbol(e);if(key){e.preventDefault();if(type==='keydown')hardwareKeys.set(e.code,key);else hardwareKeys.delete(e.code);send([{type:'key',key,down:type==='keydown'}]);}});
  window.addEventListener('blur',()=>{hardwareKeys.clear();if(current&&control){control=false;const id=current.id;inputQueue=inputQueue.catch(()=>{}).then(()=>call('Remote.control',{id,enabled:false})).catch(()=>{});updateViewer();}});
  $('remoteTextForm').onsubmit=e=>{e.preventDefault();const value=$('remoteText').value;$('remoteText').value='';act(async()=>{for(const char of value){if(!control)break;const c=char.codePointAt(0);await combo([c<=255?c:0x01000000+c]);}});};

  function settings(c){config=c;
    if(!selectionLoaded){displayAll=c.displayAll!==false;displayIds=new Set(c.displayIds||[]);selectionLoaded=true;}const f=$('remoteForm').elements;
    for(const k of ['mode','port','username','keyName','refreshMs','thumbnailWidth','jpegQuality','network'])if(c[k]!=null)f[k].value=c[k];
    f.onlineOnly.checked=c.onlineOnly!==false;f.password.value='';
    $('remoteCredentialsState').textContent=`Ключ: ${c.hasKey?'сохранён':'не импортирован'} · Пароль: ${c.hasPassword?'сохранён':'не задан'}`;
    render(state);updateGrid(true);
  }
  $('remoteForm').onsubmit=e=>{e.preventDefault();act(async()=>{const f=e.target.elements,args={};
    for(const k of ['mode','username','keyName','password','network'])args[k]=f[k].value;
    for(const k of ['port','refreshMs','thumbnailWidth','jpegQuality'])args[k]=Number(f[k].value);
    args.onlineOnly=f.onlineOnly.checked;args.clearPassword=f.clearPassword.checked;args.clearKey=f.clearKey.checked;f.password.value='';
    const c=await call('Remote.saveConfig',args);f.clearPassword.checked=f.clearKey.checked=false;
    for(const c of cards.values()){c.at=0;c.retry=0;}settings(c);api.toast('Настройки класса сохранены.');
  });};
  $('importVeyonKey').onclick=()=>act(async()=>{const c=await call('Remote.importKey');if(!c.cancelled){settings(c);api.toast('Приватный ключ сохранён. Проверьте имя ключа и сохраните настройки.');}});
  function loadThumbnail(p,token){return new Promise(resolve=>{
    const c=cards.get(p.id),loader=new Image();loader.alt='Экран '+p.name;loader.draggable=false;let done=false;
    const timeout=setTimeout(()=>finish(false),17000);
    const job={cancel:()=>{loader.removeAttribute('src');finish(false);}};
    function finish(ok){if(done)return;done=true;clearTimeout(timeout);loader.onload=loader.onerror=null;if(activeLoad===job)activeLoad=null;
      if(token===gridToken&&gridDesired&&cards.get(p.id)===c&&c.ip===p.ip){
        if(ok){if(c.img)c.img.remove();c.img=loader;c.preview.append(loader);c.icon.hidden=true;c.at=Date.now();c.retry=0;c.card.classList.remove('failed');}
        else{c.retry=Date.now()+Math.max(15000,config.refreshMs);c.card.classList.add('failed');}
      }resolve();
    }
    activeLoad=job;loader.onload=()=>finish(true);loader.onerror=()=>finish(false);
    loader.src='/remote/thumb/'+encodeURIComponent(p.id)+'?epoch='+encodeURIComponent(token)+'&t='+Date.now();
  });}
  async function pump(){
    try{if(gridDesired&&gridToken!=null&&!activeLoad){const bounds=$('peerList').getBoundingClientRect(),peers=visiblePeers().filter(p=>{const c=cards.get(p.id),r=c?.card.getBoundingClientRect();return p.state==='CONNECTED'&&r&&r.bottom>bounds.top&&r.top<bounds.bottom;}),now=Date.now();
      const priority=peers.find(p=>{const u=urgent.get(p.id);return u&&now>=u.next;});
      if(priority){const u=urgent.get(priority.id);u.left--;u.next=now+1500;if(u.left<=0)urgent.delete(priority.id);await loadThumbnail(priority,gridToken);return;}
      for(let n=0;n<peers.length;n++){const p=peers[cursor++%peers.length],c=cards.get(p.id);if(c&&now>=c.retry&&(!c.at||now-c.at>=config.refreshMs)){await loadThumbnail(p,gridToken);break;}}
    }}finally{setTimeout(pump,60);}
  }
  const actionLabels={shutdown:'Выключить',reboot:'Перезагрузить',lock:'Заблокировать',unlock:'Разблокировать',website:'Открыть сайт',file:'Передать файл'};
  function renderBatch(){
    renderPicker();
    const running=batchState.phase==='RUNNING',available=state.vpnReady&&visiblePeers().some(p=>p.state==='CONNECTED');
    document.querySelectorAll('[data-batch]').forEach(b=>b.disabled=!available||running||batchStarting);
    const progress=$('batchProgressButton');progress.hidden=!batchState.id;progress.textContent=(running?'Выполняется ':'Результат ')+(batchState.completed||0)+'/'+(batchState.total||0);
    if(!progressOpen)return;
    $('batchTitle').textContent=actionLabels[batchState.action]||'Общее действие';
    $('batchStatus').textContent=(running?'Отправка: '+(batchState.current||'ожидание')+(batchState.bytes?' · '+Math.round(batchState.bytes/1024)+' КБ':''):
      batchState.phase==='CANCELLED'?'Остановлено.':'Отправка завершена.')+` · Отправлено ${batchState.sent||0} из ${batchState.total||0}`;
    $('batchSubmit').hidden=true;$('batchCancel').textContent=running?'Остановить':'Закрыть';
    $('batchResults').replaceChildren(...(batchState.results||[]).map(r=>text('p',r.name+' · '+(r.status==='SENT'?'команда отправлена':errorText(r.error)),r.status==='SENT'?'':'failed')));
  }
  function showBatch(action){
    batchTargets=visiblePeers().map(p=>({id:p.id,name:p.name}));
    if(!batchTargets.length){api.toast('Нет доступных отображаемых ПК.');return;}
    batchAction=action;progressOpen=false;batchFile=null;
    $('batchTitle').textContent=actionLabels[action]+(action==='website'?' на ПК':'');
    $('batchTargets').textContent=`Для ${batchTargets.length} отображаемых ПК: `+batchTargets.map(p=>p.name).join(', ');
    $('batchUrlField').hidden=action!=='website';$('batchUrl').required=action==='website';$('batchUrl').value='';
    $('batchFileField').hidden=action!=='file';$('batchFileInfo').textContent='Файл не выбран · до 200 МБ';
    $('batchStatus').textContent=action==='file'?'Папка получения задаётся в Veyon на ПК. Существующие файлы не перезаписываются.':'';
    $('batchResults').replaceChildren();$('batchSubmit').hidden=false;$('batchSubmit').disabled=action==='file';$('batchSubmit').textContent=actionLabels[action];
    $('batchCancel').textContent='Отмена';$('batchDialog').hidden=false;
    if(action==='website')$('batchUrl').focus();else $('batchCancel').focus();
  }
  document.querySelectorAll('[data-batch]').forEach(b=>b.onclick=()=>showBatch(b.dataset.batch));
  $('chooseBatchFile').onclick=()=>act(async()=>{
    batchPicking=true;await setGrid(false);$('chooseBatchFile').disabled=true;
    try{const f=await call('Remote.chooseFile');if(!f.cancelled){batchFile=f;$('batchFileInfo').textContent=f.name+(f.size>=0?' · '+Math.round(f.size/1024)+' КБ':'');$('batchSubmit').disabled=false;}}
    finally{batchPicking=false;$('chooseBatchFile').disabled=false;updateGrid(true);}
  });
  $('batchForm').onsubmit=e=>{e.preventDefault();if(batchStarting)return;act(async()=>{
    batchStarting=true;$('batchSubmit').disabled=true;
    try{
      const args={action:batchAction,rids:batchTargets.map(p=>p.id)};
      if(batchAction==='website')args.url=$('batchUrl').value.trim();
      if(batchAction==='file')args.fileToken=batchFile?.token;
      batchState=await call('Remote.startBatch',args);batchFile=null;progressOpen=true;
      $('batchUrlField').hidden=$('batchFileField').hidden=true;renderBatch();updateGrid();
      // Short commands run while the real thumbnail queue continues. File transfer gets the link.
    }finally{batchStarting=false;if(!progressOpen)$('batchSubmit').disabled=false;renderBatch();}
  });};
  $('batchCancel').onclick=()=>act(async()=>{
    if(progressOpen&&batchState.phase==='RUNNING'){await call('Remote.cancelBatch');return;}
    if(!progressOpen)await call('Remote.discardFile');
    batchAction=null;batchFile=null;progressOpen=false;$('batchDialog').hidden=true;
  });
  $('batchProgressButton').onclick=()=>{progressOpen=true;$('batchDialog').hidden=false;$('batchUrlField').hidden=$('batchFileField').hidden=true;$('batchTargets').textContent='';renderBatch();};
  let pollingBatch=false;
  setInterval(async()=>{if(pollingBatch||document.hidden||batchStarting||(page!=='peers'&&!progressOpen&&batchState.phase!=='RUNNING'))return;pollingBatch=true;
    try{const status=await call('Remote.batchStatus');if(batchStarting)return;batchState=status;
      if(batchState.id&&batchState.phase!=='RUNNING'&&!completedBatches.has(batchState.id)){
        completedBatches.add(batchState.id);if(completedBatches.size>32)completedBatches.delete(completedBatches.values().next().value);
        if(['lock','unlock','website'].includes(batchState.action))for(const r of batchState.results||[])if(r.status==='SENT')refreshPeer(r.rid);
      }
      renderBatch();updateGrid();
    }catch{}finally{pollingBatch=false;}
  },700);
  function pageChanged(next){$('computerPicker').open=false;page=next;document.body.classList.toggle('classroom-open',next==='peers');updateGrid();layout();}
  document.addEventListener('visibilitychange',()=>updateGrid(true));window.addEventListener('resize',layout);
  setInterval(()=>{if(!document.hidden&&current)image($('viewerImage'),validSession(current.id)||current,1600);},250);
  window.Classroom={render,pageChanged};
  call('Remote.getConfig').then(settings).catch(e=>api.toast(e.message));pump();
})();
