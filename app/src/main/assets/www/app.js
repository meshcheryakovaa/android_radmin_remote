'use strict';
(() => {
  const pending = new Map(); let serial = 0, toastTimer, currentPage = 'home', searchTimer, searchGeneration = 0;
  const $ = id => document.getElementById(id);
  const native = window.Native;
  function toast(text) { $('toast').textContent = text; $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => $('toast').hidden = true, 6500); }
  function call(method, args = {}) {
    if (!native) return Promise.reject(new Error('Откройте этот интерфейс внутри Android APK. Браузер не подключается к VPN.'));
    return new Promise((resolve, reject) => {
      const id = String(++serial);
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('Нет ответа. Проверьте разрешение VPN и диагностику.')); }, method === 'Radmin.connect' ? 300000 : 50000);
      pending.set(id, {resolve, reject, timer});
      native.postMessage(JSON.stringify({id, method, args}));
    });
  }
  if (native) native.onmessage = event => {
    let data; try { data = JSON.parse(event.data); } catch { return; }
    const p = pending.get(data.id); if (!p) return;
    pending.delete(data.id); clearTimeout(p.timer);
    if (data.error) p.reject(new Error(data.error)); else p.resolve(data.result);
  };
  // Asynchronous public API, available only to this bundled document.
  window.Radmin = Object.fromEntries(['connect','disconnect','reconnect','getState','getNetworks','getPeers'].map(m => [m, () => call('Radmin.' + m)]));
  window.Radmin.joinNetwork = (name,password,privateNetwork = true) => call('Radmin.joinNetwork',{name,password,private:privateNetwork});
  window.Radmin.leaveNetwork = id => call('Radmin.leaveNetwork',{id});
  window.Radmin.pingPeer = id => call('Radmin.pingPeer',{id});
  window.Proxy = {getConfig:() => call('Proxy.getConfig'),setConfig:config => call('Proxy.setConfig',config),test:() => call('Proxy.test'),enable:() => call('Proxy.enable'),disable:() => call('Proxy.disable')};
  window.Diagnostics = {getLogs:() => call('Diagnostics.getLogs'),clearLogs:() => call('Diagnostics.clearLogs')};
  async function action(fn) { try { return await fn(); } catch (e) { toast(e.message); } }
  function button(text, fn, cls = 'secondary') { const b = document.createElement('button'); b.textContent = text; b.className = cls; b.onclick = () => action(async () => { b.disabled = true; try { await fn(); } finally { b.disabled = false; } }); return b; }
  function el(tag, text, cls) { const n = document.createElement(tag); n.textContent = text; if (cls) n.className = cls; return n; }
  function empty(container, text) { container.replaceChildren(el('div', text, 'empty')); }
  function bytes(n) { if (n < 1024) return n + ' B'; if (n < 1048576) return (n/1024).toFixed(1) + ' KB'; return (n/1048576).toFixed(1) + ' MB'; }
  function render(s) {
    $('phase').textContent = s.phase; $('phase').classList.toggle('online', s.phase === 'CONNECTED' && s.vpnReady);
    $('vpnIp').textContent = s.ip || '—';
    $('status').textContent = s.phase === 'CONNECTED' ? (s.vpnReady ? 'VPN подключён · TUN готов' : 'Control подключён · TUN не готов: ' + (s.interfaceError || 'ожидание')) : (s.message || s.phase);
    $('transport').textContent = s.transport;
    $('proxyStatus').textContent = s.transport === 'SOCKS5' ? 'SOCKS5 TCP · peer через relay' : s.transport === 'DIRECT' ? 'Обычный Android uplink' : 'Нет активной сессии';
    $('peerCount').textContent = s.peers.filter(p => p.state === 'CONNECTED').length;
    $('rx').textContent = bytes(s.rx); $('tx').textContent = bytes(s.tx); $('device').textContent = s.nodeName;
    $('activeNetworks').textContent = s.networks.map(n => n.name).join(', ') || 'Нет подключённых сетей';
    const nlist = $('networkList'); nlist.replaceChildren();
    if (!s.networks.length) empty(nlist, 'Список сетей появится после подключения.');
    for (const n of s.networks) { const card = el('article',''); card.append(el('h3',n.name),el('p',`${n.members} устройств · ${n.pendingApproval ? 'ожидает одобрения' : 'участник сети'}`),button('Выйти',async () => { if (confirm('Выйти из сети ' + n.name + '?')) await call('Radmin.leaveNetwork',{id:n.id}); },'danger')); nlist.append(card); }
    const plist = $('peerList'); plist.replaceChildren(); if (!s.peers.length) empty(plist, 'В подключённых сетях пока нет устройств.');
    for (const p of s.peers) {
      const card = el('article',''), head = el('div','','peer-head'); head.append(el('h3',p.name),el('span',p.state,'badge')); card.append(head,el('p',p.ip,'mono'),el('small',`${p.transport || '—'} · ${p.rttRunning ? 'PING…' : p.rttMs != null ? p.rttMs.toFixed(1) + ' ms' : p.rttFailed ? 'Нет ответа — возможно, строгий firewall' : 'RTT не измерен'}`));
      if (p.detail) card.append(el('small',p.detail));
      const controls = el('div','','actions'); controls.append(button('PING',() => call('Radmin.pingPeer',{id:p.id})),button('Скопировать IP',() => call('Radmin.copyIp',{id:p.id}))); card.append(controls); plist.append(card);
    }
  }
  document.querySelectorAll('[data-page]').forEach(b => b.onclick = () => action(async () => {
    currentPage = b.dataset.page;
    document.querySelectorAll('.page').forEach(p => p.hidden = p.id !== currentPage);
    document.querySelectorAll('[data-page]').forEach(n => { if(n===b)n.setAttribute('aria-current','page');else n.removeAttribute('aria-current'); });
    if (currentPage === 'diagnostics') await diagnostics();
  }));
  document.querySelectorAll('[data-action]').forEach(b => b.onclick = () => action(async () => { b.disabled = true; try { await call('Radmin.' + b.dataset.action); } finally { b.disabled = false; } }));
  $('joinForm').onsubmit = event => { event.preventDefault(); const f = event.target, data = {name:f.elements.name.value, password:f.elements.password.value,private:f.elements.private.checked}; f.elements.password.value = ''; action(async () => { await call('Radmin.joinNetwork',data); toast('Запрос обработан. Результат — в списке сетей и диагностике.'); }); };
  function publicList(result) {
    const list = $('publicList'); list.replaceChildren(); $('more').hidden = !result.hasMore;
    if (!result.items.length) empty(list,'Ничего не найдено. Сначала подключите VPN.');
    for(const n of result.items) { const row = el('article',''); row.append(el('p',n.name),el('small',`${n.members} участников`),button(n.joined ? 'Уже подключено' : 'Присоединиться',() => call('Radmin.joinNetwork',{name:n.name,private:false}))); list.append(row); }
  }
  $('search').oninput = () => { clearTimeout(searchTimer); const gen = ++searchGeneration; searchTimer = setTimeout(() => action(async () => { const result = await call('Radmin.searchPublic',{query:$('search').value}); if(gen===searchGeneration)publicList(result); }),300); };
  $('more').onclick = () => action(async () => publicList(await call('Radmin.loadMorePublic')));
  function proxyArgs() { const f = $('proxyForm').elements, a = {mode:f.mode.value,host:f.host.value,port:Number(f.port.value),username:f.username.value,timeoutMs:Number(f.timeoutMs.value),clearPassword:f.clearPassword.checked}; if(f.password.value)a.password=f.password.value; return a; }
  $('proxyForm').onsubmit = event => { event.preventDefault(); action(async () => { await call('Proxy.setConfig',proxyArgs()); $('proxyForm').elements.password.value=''; $('proxyForm').elements.clearPassword.checked=false; toast('Сохранено. Теперь можно подключить VPN.'); }); };
  $('testProxy').onclick = () => action(async () => { const result = await call('Proxy.test',proxyArgs()); $('proxyTest').textContent = `TCP CONNECT работает: ${result.latencyMs} ms. Вход в Radmin проверяется подключением VPN.`; });
  $('settingsForm').onsubmit = event => { event.preventDefault(); const f = event.target.elements; action(async () => { await call('Settings.save',{nodeName:f.nodeName.value,autoReconnect:f.autoReconnect.checked}); toast('Настройки сохранены'); }); };
  async function diagnostics() { $('diag').textContent = JSON.stringify(await call('Diagnostics.get'),null,2); }
  $('copyDiag').onclick = () => action(async () => { await call('Diagnostics.copy'); toast('Диагностика скопирована'); });
  $('exportDiag').onclick = () => action(() => call('Diagnostics.export'));
  $('clearLogs').onclick = () => action(async () => { await call('Diagnostics.clearLogs'); await diagnostics(); });
  async function poll() { try { render(await call('Radmin.getState')); if(currentPage==='diagnostics')await diagnostics(); } catch(e) { $('status').textContent=e.message; } finally { setTimeout(poll,1500); } }
  async function init() {
    try { const [p,s] = await Promise.all([call('Proxy.getConfig'),call('Settings.get')]);
      const f = $('proxyForm').elements; for(const key of ['mode','host','port','username','timeoutMs'])f[key].value=p[key];
      f.password.placeholder = p.hasPassword ? 'Пароль сохранён; пусто — оставить' : 'Пароль не сохранён';
      $('settingsForm').elements.nodeName.value=s.nodeName; $('settingsForm').elements.autoReconnect.checked=s.autoReconnect;
    } catch(e) { toast(e.message); }
    if(native)poll(); else $('status').textContent='Для VPN установите Android APK. Этот файл — интерфейс приложения.';
  }
  init();
})();
