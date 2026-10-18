'use strict';
(() => {
  const $ = id => document.getElementById(id), api = window.ClassroomApi;
  const selected = new Set(), cards = new Map(), hardwareKeys = new Map();
  let state = {peers:[],networks:[],remote:[],vpnReady:false}, current = null, control = false, lastPoint = {x:0,y:0}, inputQueue = Promise.resolve(), queued = 0, moving = false;
  const errors = {
    VPN_REQUIRED:'Сначала подключите VPN и дождитесь готовности интерфейса.', PEER_NOT_CONNECTED:'У этого ПК ещё нет подключённого канала Radmin.',
    CREDENTIALS_REQUIRED:'Откройте «Доступ к Veyon» и сохраните ключ или учётную запись Windows.', AUTH_METHOD_UNAVAILABLE:'Способ входа отличается от настроенного на ПК. Проверьте Veyon Configurator.',
    AUTH_FAILED:'Veyon не принял данные входа.', ACCESS_DENIED:'Veyon отклонил доступ. Проверьте правила доступа или запрос подтверждения на ПК.',
    NOT_VEYON:'На этом порту нет совместимого Veyon Server. Проверьте службу и порт 11100.', CONNECTION_FAILED:'Соединение с Veyon закрыто или недоступно. Проверьте службу, порт и брандмауэр ПК.',
    SESSION_LIMIT:'Можно наблюдать до четырёх ПК. Сначала остановите один из экранов.', MEMORY_LIMIT:'Для ещё одного экрана недостаточно выделенной памяти. Остановите другой экран.',
    SCREEN_TOO_LARGE:'Разрешение экрана превышает лимит клиента.', CLOSE_SESSIONS_FIRST:'Сначала нажмите «Остановить экраны», затем меняйте доступ.',
    INVALID_KEY:'Нужен приватный RSA-ключ Veyon в PEM PKCS#1 / PKCS#8 без пароля файла.', INVALID_SETTINGS:'Проверьте порт, имя пользователя и имя ключа.',
    SESSION_NOT_READY:'Экран ещё не подключён или уже закрыт.', CONTROL_REQUIRED:'Сначала включите управление выбранным ПК.',
    INVALID_MESSAGE:'Veyon прислал неподдерживаемое сообщение протокола.', INVALID_RECTANGLE:'Некорректный размер области экрана.', UNSUPPORTED_ENCODING:'Неподдерживаемое кодирование экрана.',
    INVALID_ZLIB:'Не удалось распаковать кадр.', UNSUPPORTED_MESSAGE:'Неподдерживаемое сообщение Veyon.', INVALID_INPUT:'Некорректное событие ввода.', SETTINGS_SAVE_FAILED:'Не удалось сохранить доступ.'
  };
  function errorText(value) { const code = String(value||'').replace(/^VEYON:/,''); return errors[code] || 'Не удалось выполнить операцию Veyon. Проверьте настройки доступа.'; }
  async function call(method,args={}) { try { return await api.call(method,args); } catch(e) { throw new Error(errorText(e.message)); } }
  function act(fn) { return api.action(fn); }
  function text(tag,value,cls) { const n=document.createElement(tag);n.textContent=value;if(cls)n.className=cls;return n; }
  function button(value,fn,cls='secondary') { const b=text('button',value,cls);b.onclick=()=>act(fn);return b; }
  function sessions() { return state.remote || []; }
  function byPeer(rid) { return sessions().find(s=>s.rid===rid && !['STOPPED','ERROR'].includes(s.phase)) || sessions().find(s=>s.rid===rid); }
  function validSession(id) { return sessions().find(s=>s.id===id); }
  function visible(p) { const q=$('classSearch').value.trim().toLocaleLowerCase();return (!$('classOnline').checked || p.state==='CONNECTED') && (!$('classNetwork').value || p.networkIds?.includes($('classNetwork').value)) && (!q || (p.name+' '+p.ip).toLocaleLowerCase().includes(q)); }
  function image(img,s,width,always=false) {
    if (!s?.hasFrame || s.phase!=='VIEWING') { img.hidden=true;img.removeAttribute('src');img.dataset.pending='0';img.dataset.serial='';return; }
    if(img.dataset.pending==='1')return;
    if(!always && img.dataset.serial===s.id+':'+s.serial)return;
    img.dataset.pending='1';img.dataset.serial=s.id+':'+s.serial;
    img.onload=()=>{img.dataset.pending='0';img.hidden=false;};
    img.onerror=()=>{img.dataset.pending='0';img.hidden=true;img.dataset.serial='';};
    img.src='/remote/frame/'+encodeURIComponent(s.id)+'?width='+width+'&t='+Date.now();
  }
  async function openPeer(p,viewer) {
    const s=await call('Remote.open',{rid:p.id});
    state.remote=[...sessions().filter(x=>x.rid!==p.id),s];
    if(viewer)showViewer(s);render(state);
  }
  function makeCard(p) {
    const card=text('article','','computer-card'),head=text('div','','card-head'),box=document.createElement('input');box.type='checkbox';box.setAttribute('aria-label','Выбрать '+p.name);
    box.onchange=()=>{if(box.checked)selected.add(p.id);else selected.delete(p.id);updateSelection();card.classList.toggle('selected',box.checked);};
    const title=text('h3',p.name),badge=text('span','','badge');head.append(box,title,badge);
    const preview=text('div','','monitor-preview'),img=document.createElement('img');img.alt='Экран '+p.name;img.hidden=true;img.draggable=false;
    const empty=text('div','','monitor-placeholder'),icon=text('span','','monitor-icon'),label=text('span','Экран не подключён');empty.append(icon,label);preview.append(img,empty);
    const ip=text('small',p.ip,'mono'),status=text('small','','remote-error'),actions=text('div','','actions');
    const view=button('Просмотр',()=>openPeer(p,true)),monitor=button('Наблюдать',()=>openPeer(p,false)),ping=button('PING',()=>api.call('Radmin.pingPeer',{id:p.id}));
    const stop=button('Отключить',async()=>{const s=byPeer(p.id);if(s){await call('Remote.close',{id:s.id});s.phase='STOPPED';s.hasFrame=false;render(state);}},'danger');
    actions.append(view,monitor,ping,stop);card.append(head,preview,ip,status,actions);
    preview.ondblclick=()=>act(()=>openPeer(p,true));
    return {card,box,title,badge,img,empty,label,status,ip,view,monitor,ping,stop};
  }
  function updateSelection() {$('selectionCount').textContent='Выбрано: '+selected.size;}
  function render(s) {
    state=s;
    const opts=JSON.stringify(s.networks.map(n=>[n.id,n.name]));
    if($('classNetwork').dataset.options!==opts){const old=$('classNetwork').value;$('classNetwork').replaceChildren(new Option('Все сети',''),...s.networks.map(n=>new Option(n.name,n.id)));$('classNetwork').value=old;$('classNetwork').dataset.options=opts;}
    const peers=s.peers||[], active=sessions().filter(x=>!['STOPPED','ERROR'].includes(x.phase));
    $('classSummary').textContent=`Компьютеров: ${peers.length} · Каналов онлайн: ${peers.filter(p=>p.state==='CONNECTED').length} · Экранов: ${active.length}/4`;
    const known=new Set(peers.map(p=>p.id));for(const [id,c] of cards)if(!known.has(id)){c.card.remove();cards.delete(id);selected.delete(id);}
    for(const p of peers){let c=cards.get(p.id);if(!c){c=makeCard(p);cards.set(p.id,c);$('peerList').append(c.card);}const remote=byPeer(p.id), online=p.state==='CONNECTED';
      c.card.hidden=!visible(p);c.box.checked=selected.has(p.id);c.card.classList.toggle('selected',c.box.checked);c.card.classList.toggle('offline',!online);
      c.title.textContent=p.name;c.ip.textContent=p.ip+' · '+(p.transport||'нет канала')+(p.rttMs!=null?' · '+p.rttMs.toFixed(0)+' мс':'');
      c.badge.textContent=remote?.phase==='VIEWING'?'ЭКРАН':online?'ОНЛАЙН':'ОФЛАЙН';c.badge.classList.toggle('online',online);
      const busy=remote && !['STOPPED','ERROR'].includes(remote.phase);c.view.disabled=c.monitor.disabled=!online||!s.vpnReady;c.ping.disabled=!online;c.stop.hidden=!busy;c.monitor.hidden=!!busy;
      c.status.textContent=remote?.error?errorText(remote.error):'';c.status.hidden=!remote?.error;
      c.label.textContent=!online?'ПК недоступен':remote?.phase==='CONNECTING'?'Вход в Veyon…':remote?.phase==='VIEWING'&&!remote.hasFrame?'Ожидание кадра…':'Экран не подключён';c.empty.hidden=!(remote?.hasFrame&&remote.phase==='VIEWING');
      if(!remote?.hasFrame||remote.phase!=='VIEWING'){c.img.hidden=true;c.img.removeAttribute('src');c.img.dataset.serial='';c.img.dataset.pending='0';}
    }
    let anyVisible=peers.some(visible),empty=$('classEmpty');if(!empty){empty=text('div','','empty');empty.id='classEmpty';$('peerList').append(empty);}empty.hidden=anyVisible;empty.textContent=peers.length?'Нет компьютеров по этому фильтру.':'Подключите VPN и войдите в Radmin-сеть. Здесь появятся компьютеры.';
    updateSelection();
    if(current){const session=validSession(current.id);if(session){current=session;control=session.control===true;updateViewer();}else hideViewer(false);}
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
  function hideViewer(disable=true){const old=current;current=null;control=false;$('remoteViewer').hidden=true;document.body.classList.remove('viewer-open');$('viewerImage').removeAttribute('src');if(disable&&old)inputQueue=inputQueue.catch(()=>{}).then(()=>call('Remote.control',{id:old.id,enabled:false})).catch(()=>{});}
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
  $('monitorSelected').onclick=()=>act(async()=>{const peers=state.peers.filter(p=>selected.has(p.id)&&p.state==='CONNECTED');if(!peers.length){api.toast('Выберите включённые компьютеры.');return;}if(peers.length>4){api.toast('Выберите не более четырёх компьютеров.');return;}for(const p of peers)await openPeer(p,false);});
  $('selectOnline').onclick=()=>{selected.clear();state.peers.filter(p=>p.state==='CONNECTED'&&visible(p)).slice(0,4).forEach(p=>selected.add(p.id));render(state);};
  $('clearSelection').onclick=()=>{selected.clear();render(state);};
  $('stopRemote').onclick=()=>act(async()=>{hideViewer(false);await call('Remote.closeAll');state.remote=[];render(state);});
  for(const id of ['classNetwork','classSearch','classOnline'])$(id).addEventListener(id==='classSearch'?'input':'change',()=>render(state));
  $('tileSize').oninput=()=>$('peerList').style.setProperty('--tile-width',$('tileSize').value+'px');
  $('remoteSettingsButton').onclick=()=>$('remoteSettings').hidden=!$('remoteSettings').hidden;
  function settings(c){const f=$('remoteForm').elements;for(const k of ['mode','port','username','keyName'])if(c[k]!=null)f[k].value=c[k];f.password.value='';$('remoteCredentialsState').textContent=`Ключ: ${c.hasKey?'сохранён':'не импортирован'} · Пароль: ${c.hasPassword?'сохранён':'не задан'}`;}
  $('remoteForm').onsubmit=e=>{e.preventDefault();act(async()=>{const f=e.target.elements,args={};for(const k of ['mode','username','keyName','password'])args[k]=f[k].value;args.port=Number(f.port.value);args.clearPassword=f.clearPassword.checked;args.clearKey=f.clearKey.checked;f.password.value='';const c=await call('Remote.saveConfig',args);f.clearPassword.checked=f.clearKey.checked=false;settings(c);api.toast('Доступ к Veyon сохранён. Выберите ПК для просмотра.');});};
  $('importVeyonKey').onclick=()=>act(async()=>{const c=await call('Remote.importKey');if(!c.cancelled){settings(c);api.toast('Приватный ключ сохранён. Проверьте имя ключа и сохраните настройки.');}});
  setInterval(()=>{if(!document.hidden){if(!$('peers').hidden)for(const [id,c]of cards){if(!c.card.hidden)image(c.img,byPeer(id),480);}if(current){const s=validSession(current.id)||current;image($('viewerImage'),s,1600,true);}}},350);
  window.Classroom={render};
  call('Remote.getConfig').then(settings).catch(e=>api.toast(e.message));
})();
