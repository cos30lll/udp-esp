'use strict';
// UI only: reads the existing local API; never starts capture or sends game traffic.
const UI_VERSION = 'ui23camera1';
const $ = id => document.getElementById(id);
const canvas = $('radar');
const ctx = canvas.getContext('2d');
let live = null, data = null, liveFetched = 0, viewFetched = 0;
let connected = false, paused = false, frozenNow = 0, pollError = '';
let center = [0, 0], initialized = false, anchorKey = '', selectedKey = '';
let activePanel = 'radar', hits = [], gesture = null, framePending = false;
let tableSignature = '', optionSignature = '', toastTimer = 0;
const pointers = new Map();
const number = Number.isFinite;
const text = (id, value) => { const el = $(id), s = String(value); if (el.textContent !== s) el.textContent = s; };
const fmt = value => number(value) ? value.toFixed(2) : '—';
const clamp = (value, lo, hi) => Math.max(lo, Math.min(hi, value));
const axes = () => $('plane').value.split('');
const span = () => clamp(Number($('span').value) || 160, 5, 100000);
const liveClock = () => live ? live.now + Math.max(0, performance.now() - liveFetched) / 1000 : Date.now() / 1000;
const clock = () => paused ? frozenNow : data ? data.now + Math.max(0, performance.now() - viewFetched) / 1000 : liveClock();
const ageAt = (timestamp, now) => number(timestamp) ? Math.max(0, now - timestamp) : Infinity;
const age = entity => ageAt(entity?.timestamp, clock());
const ago = value => number(value) ? (value < 60 ? value.toFixed(1) + ' 秒前' : (value / 60).toFixed(1) + ' 分前') : '未收到';
const nameOf = entity => typeof entity?.name === 'string' && entity.name ? entity.name : '实体 ' + String(entity?.id1 ?? '未知');
const valid = entity => entity && typeof entity.key === 'string' && ['x', 'y', 'z', 'timestamp'].every(key => number(entity[key]));
const allEntities = () => (data?.entities || []).filter(valid);
const entities = () => allEntities().filter(entity => age(entity) <= ($('showStale').checked ? 30 : 3) && ($('category').value === 'all' || String(entity.category) === $('category').value));
const anchor = () => allEntities().find(entity => entity.key === anchorKey && age(entity) <= 3) || null;
const selected = () => allEntities().find(entity => entity.key === selectedKey) || null;
const distance = (a, b) => { const [u, v] = axes(); return a && b ? Math.hypot(a[u] - b[u], a[v] - b[v]) : Infinity; };
const count = (stats, key) => number(stats?.[key]) ? stats[key] : 0;
function color(key) { let hash = 0; for (const c of key) hash = (hash * 31 + c.charCodeAt(0)) | 0; return `hsl(${185 + Math.abs(hash) % 125} 72% 72%)`; }
function toast(message) { text('toast', message); $('toast').hidden = false; clearTimeout(toastTimer); toastTimer = setTimeout(() => { $('toast').hidden = true; }, 3200); }
function kv(id, entries) {
  const fragment = document.createDocumentFragment();
  for (const [label, value, bad] of entries) { const dt = document.createElement('dt'), dd = document.createElement('dd'); dt.textContent = label; dd.textContent = String(value); if (bad) dd.className = 'bad'; fragment.append(dt, dd); }
  $(id).replaceChildren(fragment);
}
function syncSelf() {
  if (!$('autoSelf').checked) return;
  const local = allEntities().filter(entity => entity.is_local === true).sort((a, b) => b.timestamp - a.timestamp);
  const fresh = local.find(entity => age(entity) <= 3);
  anchorKey = fresh?.key || local.find(entity => entity.key === anchorKey)?.key || '';
  if (!selectedKey && fresh) selectedKey = fresh.key;
}
function updateOptions() {
  const list = allEntities().filter(entity => age(entity) <= 30 || entity.key === anchorKey);
  const entries = list.map(entity => [entity.key, `${entity.is_local === true ? '[自己] ' : ''}${nameOf(entity)} · ${entity.id1} · 类${entity.category}`]);
  if (anchorKey && !entries.some(([key]) => key === anchorKey)) entries.push([anchorKey, '参考实体已移除 / 失联']);
  const signature = JSON.stringify(entries);
  if (signature !== optionSignature) { const fragment = document.createDocumentFragment(); fragment.append(new Option('无 · 世界坐标', '')); for (const [key, name] of entries) fragment.append(new Option(name, key)); $('anchor').replaceChildren(fragment); optionSignature = signature; }
  $('anchor').value = anchorKey;
}
function showPanel(panel, focus = false) {
  activePanel = panel;
  for (const id of ['radar', 'entities', 'diagnostics']) { const on = id === panel; $('panel-' + id).hidden = !on; const tab = $('tab-' + id); tab.classList.toggle('active', on); tab.setAttribute('aria-selected', String(on)); tab.tabIndex = on ? 0 : -1; if (on && focus) tab.focus(); }
  render();
}
function inspect(key, openRadar = false) { selectedKey = key; if (openRadar) showPanel('radar'); else { renderSelection(); requestDraw(); } }
function setReference(key) { anchorKey = key; $('autoSelf').checked = false; if (key) selectedKey = key; updateOptions(); tableSignature = ''; render(); }
function fit() {
  const list = entities(); if (!list.length) { toast('还没有可适配的真实坐标'); return; }
  const [u, v] = axes(), xs = list.map(e => e[u]), ys = list.map(e => e[v]);
  const ref = $('follow').checked ? anchor() : null;
  center = ref ? [ref[u], ref[v]] : [(Math.min(...xs) + Math.max(...xs)) / 2, (Math.min(...ys) + Math.max(...ys)) / 2];
  const width = Math.max(1, canvas.clientWidth), height = Math.max(1, canvas.clientHeight);
  const dx = Math.max(...xs.map(x => Math.abs(x - center[0]))) * 2;
  const dy = Math.max(...ys.map(y => Math.abs(y - center[1]))) * 2;
  $('span').value = clamp(Math.ceil(Math.max(20, dx * 1.25, dy * 1.25 * width / height)), 5, 100000);
  initialized = true; requestDraw();
}
function adopt(snapshot) {
  const before = data?.capture?.game_pid, next = snapshot?.capture?.game_pid;
  if (before != null && next != null && before !== next) { anchorKey = ''; selectedKey = ''; initialized = false; center = [0, 0]; optionSignature = ''; tableSignature = ''; }
  data = snapshot; viewFetched = liveFetched; syncSelf(); updateOptions();
  if (!initialized && entities().length && activePanel === 'radar') fit();
}
function renderStatus() {
  const cap = live?.capture || {}, stats = live?.stats || {}, dg = live?.diagnostics || {};
  const delta = ageAt(live?.last_position, liveClock());
  const fresh = connected && cap.running === true && delta <= 3;
  const label = !connected ? '后端未连接' : fresh ? '坐标更新中' : cap.running === true ? '等待有效坐标' : '抓包未运行';
  text('health', label); $('health').className = 'badge ' + (!connected ? 'off' : fresh ? 'ok' : 'warn');
  text('status', live ? `游戏 PID ${cap.game_pid ?? '—'} · UDP ${(Array.isArray(cap.ports) ? cap.ports : []).join(', ') || '等待识别'} · ${cap.reason || '等待状态'}${paused ? ' · 雷达画面冻结中' : ''}` : '请先由你运行原启动脚本，再刷新本页。');
  text('backendVersion', live ? '后端 ' + String(live.version || '未知') + ' / UI 2.3' : '/api/state');
  const actual = (live?.entities || []).filter(valid);
  const recent = actual.filter(e => ageAt(e.timestamp, liveClock()) <= 3);
  text('liveCount', live ? recent.length : '—');
  text('entityCaption', live ? `${actual.filter(e => ageAt(e.timestamp, liveClock()) <= 30).length} 个保留实体 · ${paused ? '画面已冻结' : '3 秒内为有效'}` : '等待真实坐标');
  const me = actual.filter(e => e.is_local === true).sort((a, b) => b.timestamp - a.timestamp)[0];
  const identities = Object.values(live?.identity || {});
  text('ownName', me ? nameOf(me) : '未识别');
  text('ownHint', me ? `坐标 ${ago(ageAt(me.timestamp, liveClock()))}` : identities.some(x => x.local_uid != null || x.local_entity_id != null) ? '身份已收，等待自身坐标' : '等待进场初始化');
  text('packetCount', live ? count(stats, 'packets').toLocaleString() : '—');
  text('frameCount', live ? `场景帧 ${count(stats, 'scene_frames').toLocaleString()}` : '场景帧 —');
  text('positionAge', live && number(delta) ? delta.toFixed(1) + ' s' : '未收到');
  text('captureHint', !connected ? (live ? '后端断开 · 状态待确认' : '等待连接') : cap.running === true ? (number(cap.remaining_seconds) ? `抓包中 · ${Math.max(0, cap.remaining_seconds)} 秒后停止` : '抓包中') : live ? '抓包未运行' : '等待连接');
  text('diagnosticBadge', connected ? '接口已连接' : '后端未连接');
  if (activePanel === 'diagnostics') {
    const unfinished = ['fragment_orphan', 'fragment_sequence_gap', 'fragment_expired', 'fragment_interrupted', 'fragment_limited'].reduce((sum, key) => sum + count(stats, key), 0);
    kv('metrics', [
      ['后端接口', connected ? '已连接' : '未连接', !connected], ['画面状态', paused ? '冻结，仅画面暂停' : '实时显示'],
      ['游戏 PID', cap.game_pid ?? '—'], ['游戏 UDP 端口', (Array.isArray(cap.ports) ? cap.ports : []).join(', ') || '未识别'],
      ['抓包范围', cap.capture_scope === 'device_udp' ? '全机 UDP 缓存 / 仅解码游戏端口' : cap.capture_scope || '未报告'],
      ['初始化观测', dg.identity === 'received' ? '已收到' : '未观测到'], ['姓名映射数', identities.reduce((sum, value) => sum + (number(value.players) ? value.players : 0), 0)],
      ['身份消息', count(stats, 'identity_messages')], ['HP31 观测', dg.hp31 === 'received' ? '已收到' : '未观测到'], ['HP 动作', count(stats, 'hp_actions')],
      ['坐标动作', count(stats, 'position_actions')], ['解码错误', count(stats, 'decode_errors'), count(stats, 'decode_errors') > 0],
      ['压缩消息', count(stats, 'zstd')], ['传输重组', count(stats, 'fragments_reassembled')], ['合包拆分', count(stats, 'coalesced_datagrams')],
      ['未支持 IP 分片', count(stats, 'unsupported_ip_fragments') + count(stats, 'capture_ip_fragments_unsupported')],
      ['传输分片异常 / 未完成', unfinished, unfinished > 0], ['端口发现后补解析', count(stats, 'buffer_replayed_records')],
      ['待归属缓冲', number(dg.pending_records) ? dg.pending_records : '—'], ['缓冲字节', number(dg.pending_bytes) ? dg.pending_bytes : '—'],
      ['最后坐标', ago(delta)], ['上行视角消息', count(stats, 'camera_view_messages')], ['视角解码错误', count(stats, 'camera_decode_errors')], ['实时 FOV', '来源未确认']
    ]);
    const countsText = values => Object.entries(values || {}).sort((a, b) => Number(b[1]) - Number(a[1])).map(([key, value]) => `${key.padEnd(16)} ${value}`).join('\n') || '未收到';
    text('messageCounts', '入站\n' + countsText(dg.message_counts_inbound) + '\n\n出站\n' + countsText(dg.message_counts_outbound)); text('actionCounts', countsText(dg.action_counts));
  }
  const errors = [...(live?.camera_decoder_errors || []).map(String), ...(live?.decoder_last_errors || []).map(String), ...(live?.errors || []).map(e => typeof e === 'string' ? e : String(e?.message || ''))].filter(Boolean);
  const error = pollError || errors.at(-1) || ''; text('error', error ? (pollError ? '接口提示：' : '最近诊断：') + error : ''); $('error').hidden = !error;
}
function renderCamera() {
  // Snapshot follows the same pause clock as the radar, never an offline sample.
  const cam = data?.camera;
  const hasApi = !!data && Object.prototype.hasOwnProperty.call(data, 'camera');
  const elapsed = ageAt(cam?.timestamp, clock());
  const decoded = cam?.status === 'decoded';
  const stale = elapsed > 3;
  const show = decoded && elapsed <= 30;
  text('cameraYaw', show && number(cam.yaw_deg) ? cam.yaw_deg.toFixed(1) + '°' : '未收到');
  text('cameraPitch', show && number(cam.pitch_deg) ? cam.pitch_deg.toFixed(1) + '°' : '未收到');
  // Only an explicitly verified realtime source may drive this field.
  const validFov = cam?.fov_status === 'verified_realtime' && number(cam.fov_deg) && cam.fov_deg > 0 && cam.fov_deg < 180 && ageAt(cam.fov_timestamp, clock()) <= 3;
  text('cameraFov', validFov ? cam.fov_deg.toFixed(1) + '°' : '未取得');
  text('cameraState', !hasApi ? (data ? '需重启后端' : '等待连接') : paused ? '冻结快照' : !connected ? '后端断开' : decoded && !stale ? '上行视角已接收' : decoded ? '视角已过期' : '等待上行视角');
  $('cameraState').className = 'tag' + (!decoded || stale || paused || !connected ? ' pending' : ' camera-ok');
  $('cameraCard').classList.toggle('camera-stale', decoded && (stale || !connected));
  text('cameraHint', !hasApi && data ? '当前运行的是旧后端。请先停止旧会话，再使用原启动脚本。' : decoded ? '来源：上行 3/851 field3 · 更新于 ' + ago(elapsed) + (paused ? ' · 画面冻结，非实时' : stale ? ' · 历史值，不代表当前镜头' : '') : '等待游戏自然上报 3/851；未主动发包或读取内存。');
  const stats = live?.stats || {};
  kv('cameraMetrics', [['上行视角消息', count(stats, 'camera_view_messages')], ['已应用视角', count(stats, 'camera_updates_applied')],
    ['视角解码错误', count(stats, 'camera_decode_errors'), count(stats, 'camera_decode_errors') > 0],
    ['不支持的出站分片', count(stats, 'camera_outgoing_fragments_unsupported')], ['头校验未解析', count(stats, 'camera_outgoing_checksum_unresolved')],
    ['视角时效', ago(elapsed)], ['实时 FOV', validFov ? '已验证来源' : '来源未确认，不填常量']]);
}
function renderSelection() {
  const entity = selected();
  if (!entity) { text('selectionState', selectedKey ? '已失联' : '未选择'); text('selectedName', selectedKey ? '所选实体已移除' : '点击图中实体'); text('selectedHint', selectedKey ? '不以已移除的坐标继续跟随。' : '查看详情不会改变本地身份或参考实体。'); $('selectedMetrics').replaceChildren(); $('setAnchor').disabled = true; return; }
  const old = age(entity) > 3, ref = anchor();
  text('selectionState', paused ? '冻结快照' : !connected ? '后端断开' : old ? '过期坐标' : '有效坐标');
  text('selectedName', nameOf(entity)); text('selectedHint', `${entity.is_local === true ? '协议确认：自己' : entity.is_local === false ? '协议标记：非本地，不代表敌人' : '身份尚未识别'}${anchorKey === entity.key ? ' · 当前参考' : ''}`);
  kv('selectedMetrics', [['实体编号', entity.id1 ?? '—'], ['字段 2 · 非账号 UID', entity.id2 ?? '—'], ['类别', entity.category ?? '—'],
    ['姓名', typeof entity.name === 'string' && entity.name ? entity.name : '未收到'], ['账号 UID', entity.uid ?? '未收到'],
    ['HP 原值', Number.isInteger(entity.hp) ? entity.hp : '未收到'], ['HP 更新', ago(ageAt(entity.hp_timestamp, clock()))],
    ['X / Y / Z', `${fmt(entity.x)} / ${fmt(entity.y)} / ${fmt(entity.z)}`], ['坐标更新', ago(age(entity)), old],
    ['相对参考 ΔY', ref ? fmt(entity.y - ref.y) : '—'], ['参考平面距离', ref ? fmt(distance(entity, ref)) + ' 世界单位' : '—']]);
  $('setAnchor').disabled = old;
}
function renderTable() {
  const base = entities(), query = $('search').value.trim().toLocaleLowerCase();
  text('listCount', base.length);
  if (activePanel !== 'entities') return;
  const ref = anchor(), list = base.filter(e => !query || [e.name, e.id1, e.id2, e.uid].some(v => v != null && String(v).toLocaleLowerCase().includes(query)));
  const sort = $('sort').value;
  list.sort((a, b) => {
    if (sort === 'distance') { const da = distance(a, ref), db = distance(b, ref); if (number(da) && number(db) && da !== db) return da - db; }
    if (sort === 'freshness' && a.timestamp !== b.timestamp) return b.timestamp - a.timestamp;
    if (sort === 'identity' && (a.is_local === true) !== (b.is_local === true)) return a.is_local === true ? -1 : 1;
    return String(a.id1).localeCompare(String(b.id1), undefined, { numeric: true });
  });
  const model = list.map(e => ({ key: e.key, selected: e.key === selectedKey, old: age(e) > 3, local: e.is_local === true, name: nameOf(e), id: e.id1, fields: [e.is_local === true ? '自己' : e.is_local === false ? '非本地' : '未识别', e.category, Number.isInteger(e.hp) ? e.hp : '未收到', ago(ageAt(e.hp_timestamp, clock())), fmt(e.x), fmt(e.y), fmt(e.z), ref ? fmt(distance(e, ref)) : '—', ago(age(e)), e.uid ?? '未收到'] }));
  const signature = JSON.stringify(model);
  if (signature !== tableSignature) {
    const fragment = document.createDocumentFragment();
    for (const row of model) {
      const tr = document.createElement('tr'); tr.classList.toggle('selected', row.selected); tr.classList.toggle('stale', row.old); tr.classList.toggle('local', row.local);
      const first = document.createElement('td'), button = document.createElement('button'), small = document.createElement('small'); button.type = 'button'; button.className = 'entity-link'; button.textContent = row.name; small.textContent = `编号 ${row.id}${row.old ? ' · 旧点' : ''}`; first.append(button, small); tr.append(first);
      for (const value of row.fields) { const td = document.createElement('td'); td.textContent = String(value); tr.append(td); }
      tr.addEventListener('click', () => inspect(row.key, true)); fragment.append(tr);
    }
    if (!model.length) { const tr = document.createElement('tr'), td = document.createElement('td'); td.colSpan = 11; td.textContent = query ? '没有匹配的实体' : '当前类别尚无有效坐标'; tr.append(td); fragment.append(tr); }
    $('rows').replaceChildren(fragment); tableSignature = signature;
  }
  text('tableHint', `显示 ${list.length} / ${base.length} 个实体 · 距离采用 ${axes().join('/').toUpperCase()} 平面、世界单位${paused ? ' · 冻结快照' : ''}${!connected ? ' · 后端已断开' : ''}`);
}
function requestDraw() { if (framePending) return; framePending = true; requestAnimationFrame(() => { framePending = false; draw(); }); }
function draw() {
  if (activePanel !== 'radar' || !ctx) return;
  const w = canvas.clientWidth, h = canvas.clientHeight; if (!w || !h) return;
  const dpr = Math.min(window.devicePixelRatio || 1, 2); if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.clearRect(0, 0, w, h); ctx.fillStyle = '#0c1622'; ctx.fillRect(0, 0, w, h);
  const list = entities(), [u, v] = axes(), ref = anchor(); if ($('follow').checked && ref && pointers.size === 0) center = [ref[u], ref[v]];
  const scale = w / span(), sign = $('flip').checked ? 1 : -1;
  const project = (a, b) => [w / 2 + (a - center[0]) * scale, h / 2 + sign * (b - center[1]) * scale];
  const unit = 10 ** Math.floor(Math.log10(60 / scale)); const step = [1, 2, 5, 10].map(n => n * unit).find(n => n * scale >= 55) || 10 * unit;
  const halfW = span() / 2, halfH = h / (2 * scale);
  ctx.font = '10px system-ui'; ctx.lineWidth = 1; ctx.strokeStyle = '#1d3042'; ctx.fillStyle = '#56748b';
  for (let x = Math.floor((center[0] - halfW) / step) * step; x <= center[0] + halfW; x += step) { const [px] = project(x, 0); ctx.beginPath(); ctx.moveTo(px, 0); ctx.lineTo(px, h); ctx.stroke(); ctx.fillText(x.toFixed(step < 1 ? 2 : 0), px + 4, h - 55); }
  for (let y = Math.floor((center[1] - halfH) / step) * step; y <= center[1] + halfH; y += step) { const [, py] = project(0, y); ctx.beginPath(); ctx.moveTo(0, py); ctx.lineTo(w, py); ctx.stroke(); if (py > 45 && py < h - 45) ctx.fillText(y.toFixed(step < 1 ? 2 : 0), 5, py - 4); }
  ctx.strokeStyle = '#315363'; ctx.setLineDash([3, 7]); ctx.beginPath(); ctx.moveTo(w / 2, 0); ctx.lineTo(w / 2, h); ctx.moveTo(0, h / 2); ctx.lineTo(w, h / 2); ctx.stroke(); ctx.setLineDash([]); hits = [];
  const ordered = [...list].sort((a, b) => Number(a.is_local === true) - Number(b.is_local === true));
  let onScreen = 0;
  for (const e of ordered) {
    const old = age(e) > 3, c = old ? '#65768a' : e.is_local === true ? '#67dfce' : color(e.key); ctx.strokeStyle = c; ctx.fillStyle = c; ctx.globalAlpha = !connected ? .35 : old ? .42 : .9;
    if ($('tracks').checked && Array.isArray(e.track)) { ctx.beginPath(); let start = false; for (const t of e.track) { if (!Array.isArray(t) || t.length < 4 || !t.slice(0, 4).every(number) || ageAt(t[0], clock()) > 30) continue; const p = { x: t[1], y: t[2], z: t[3] }, [tx, ty] = project(p[u], p[v]); if (!start) { ctx.moveTo(tx, ty); start = true; } else ctx.lineTo(tx, ty); } ctx.lineWidth = 1.5; ctx.stroke(); }
    const [x, y] = project(e[u], e[v]); if (x < -12 || x > w + 12 || y < -12 || y > h + 12) continue; onScreen++;
    ctx.beginPath(); ctx.arc(x, y, e.is_local === true ? 6 : 4.5, 0, Math.PI * 2); ctx.fill();
    if (e.key === anchorKey || e.key === selectedKey) { ctx.strokeStyle = e.key === selectedKey ? '#f3d395' : '#cee9ee'; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(x, y, e.key === selectedKey ? 11 : 9, 0, Math.PI * 2); ctx.stroke(); }
    if ($('labels').checked) { const label = `${e.is_local === true ? '[自己] ' : ''}${nameOf(e).slice(0, 20)}${Number.isInteger(e.hp) ? ' HP:' + e.hp + (ageAt(e.hp_timestamp, clock()) > 3 ? '旧' : '') : ''}${old ? ' · 旧点' : ''}`; ctx.font = '11px system-ui'; const tx = Math.max(4, Math.min(x + 10, w - Math.min(ctx.measureText(label).width, w - 8) - 4)), ty = y > 45 ? y - 10 : y + 18; ctx.lineWidth = 3; ctx.strokeStyle = '#0c1622'; ctx.strokeText(label, tx, ty); ctx.fillStyle = c; ctx.fillText(label, tx, ty); }
    hits.push({ x, y, key: e.key });
  }
  ctx.globalAlpha = 1;
  text('axesLabel', `${v.toUpperCase()} ${sign < 0 ? '↑' : '↓'} / ${u.toUpperCase()} →`);
  text('mapMode', paused ? '冻结快照 · 非实时' : !connected ? '后端断开 · 历史快照' : $('follow').checked && ref ? '跟随参考实体' : '世界坐标');
  text('scaleLabel', `网格 ${step.toFixed(step < 1 ? 2 : 0)} 世界单位`);
  text('plotinfo', `${onScreen} / ${list.length} 个实体在图内 · 双指缩放 / 拖动平移${anchorKey && !ref ? ' · 参考过期，停止跟随' : ''}${paused ? ' · 后台仍继续' : ''}`);
  $('emptyState').hidden = list.length > 0;
  if (!list.length) { text('emptyTitle', !connected ? '本机后端未连接' : paused ? '画面已暂停' : '等待真实坐标'); text('emptyText', !connected ? '请由你运行原启动脚本，本页不会启动抓包。' : paused ? '恢复画面后显示后台最新快照。' : '请进场或检查类别筛选；不生成示例点。'); }
}
function render() { renderCamera(); renderStatus(); updateOptions(); renderSelection(); renderTable(); requestDraw(); }
function zoom(factor) { $('span').value = Number((span() * factor).toFixed(2)); $('span').value = span(); initialized = true; requestDraw(); }
function report() {
  const cap = live?.capture || {}, dg = live?.diagnostics || {};
  return { ui_version: UI_VERSION, backend_version: live?.version ?? null, copied_at_utc: new Date().toISOString(), api_connected: connected, snapshot_age_seconds: live ? Number(((performance.now() - liveFetched) / 1000).toFixed(2)) : null, picture_paused: paused,
    capture: { running: cap.running ?? null, game_pid: cap.game_pid ?? null, ports: cap.ports ?? [], reason: cap.reason ?? null, scope: cap.capture_scope ?? null, remaining_seconds: cap.remaining_seconds ?? null },
    stats: live?.stats || {}, diagnostics: { identity: dg.identity ?? null, hp31: dg.hp31 ?? null, pending_records: dg.pending_records ?? null, pending_bytes: dg.pending_bytes ?? null, message_counts_inbound: dg.message_counts_inbound || {}, action_counts: dg.action_counts || {} },
    camera: { status: live?.camera?.status ?? 'api_missing', age_seconds: number(live?.camera?.timestamp) ? Math.max(0, liveClock() - live.camera.timestamp) : null, yaw_deg: live?.camera?.yaw_deg ?? null, pitch_deg: live?.camera?.pitch_deg ?? null, source: live?.camera?.source ?? null, realtime_fov: live?.camera?.fov_status ?? 'unknown' }, errors: { api: pollError || null, camera: live?.camera_decoder_errors || [], decoder: live?.decoder_last_errors || [], backend: live?.errors || [] },
    privacy_note: 'Entity names, UID fields and coordinates are omitted; error and reason text may contain runtime information. Review before sharing.' };
}
for (const id of ['radar', 'entities', 'diagnostics']) {
  const tab = $('tab-' + id); tab.addEventListener('click', () => showPanel(id)); tab.addEventListener('keydown', event => { const ids = ['radar', 'entities', 'diagnostics']; let index = ids.indexOf(id); if (event.key === 'ArrowRight') index = (index + 1) % ids.length; else if (event.key === 'ArrowLeft') index = (index + ids.length - 1) % ids.length; else if (event.key === 'Home') index = 0; else if (event.key === 'End') index = ids.length - 1; else return; event.preventDefault(); showPanel(ids[index], true); });
}
$('anchor').addEventListener('change', () => setReference($('anchor').value));
$('setAnchor').addEventListener('click', () => { const e = selected(); if (!e || age(e) > 3) return toast('实体坐标已过期，不能跟随'); setReference(e.key); $('follow').checked = true; requestDraw(); });
$('autoSelf').addEventListener('change', () => { syncSelf(); render(); });
$('homeSelf').addEventListener('click', () => { $('autoSelf').checked = true; $('follow').checked = true; syncSelf(); const e = anchor(); if (e && e.is_local === true) { selectedKey = e.key; initialized = true; render(); } else { render(); toast(paused ? '当前快照没有有效自身坐标，可先恢复画面' : '尚未取得有效自身坐标，不会猜选'); } });
$('fit').addEventListener('click', fit); $('zoomIn').addEventListener('click', () => zoom(1 / 1.25)); $('zoomOut').addEventListener('click', () => zoom(1.25));
$('freeze').addEventListener('click', () => { if (!paused) { frozenNow = clock(); paused = true; } else { paused = false; if (live) adopt(live); } text('freeze', paused ? '恢复画面' : '暂停画面'); $('freeze').setAttribute('aria-pressed', String(paused)); tableSignature = ''; render(); });
for (const id of ['plane', 'category', 'follow', 'tracks', 'labels', 'flip', 'span', 'showStale', 'sort']) $(id).addEventListener('change', () => { if (id === 'span') $('span').value = span(); if (id === 'plane') { initialized = false; if (entities().length) fit(); } tableSignature = ''; render(); });
$('search').addEventListener('input', renderTable);
$('fullscreen').addEventListener('click', async () => { try { if (document.fullscreenElement) await document.exitFullscreen(); else if (document.documentElement.requestFullscreen) await document.documentElement.requestFullscreen(); else toast('当前浏览器不支持网页全屏'); } catch (_) { toast('全屏未获浏览器授权，可使用浏览器全屏模式'); } });
document.addEventListener('fullscreenchange', () => { text('fullscreen', document.fullscreenElement ? '退出全屏' : '全屏'); requestDraw(); });
$('copyReport').addEventListener('click', async () => { const value = JSON.stringify(report(), null, 2); $('reportText').value = value; try { if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable'); await navigator.clipboard.writeText(value); toast('诊断已复制，请检查后再分享'); } catch (_) { $('reportBox').hidden = false; $('reportText').focus(); $('reportText').select(); toast('可在诊断文本框长按复制'); } });
$('closeReport').addEventListener('click', () => { $('reportBox').hidden = true; });
function localPoint(event) { const r = canvas.getBoundingClientRect(); return { x: event.clientX - r.left, y: event.clientY - r.top }; }
function worldAt(point) { const scale = canvas.clientWidth / span(), sign = $('flip').checked ? 1 : -1; return [center[0] + (point.x - canvas.clientWidth / 2) / scale, center[1] + (point.y - canvas.clientHeight / 2) / (sign * scale)]; }
function beginPinch() { const [a, b] = [...pointers.values()], mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }; gesture = { type: 'pinch', distance: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), span: span(), world: worldAt(mid), moved: true }; }
canvas.addEventListener('pointerdown', event => { if (event.pointerType === 'mouse' && event.button !== 0) return; const p = localPoint(event); pointers.set(event.pointerId, p); if (pointers.size === 1) gesture = { type: 'pan', point: p, center: [...center], span: span(), moved: false }; else if (pointers.size === 2) beginPinch(); try { canvas.setPointerCapture(event.pointerId); } catch (_) {} });
canvas.addEventListener('pointermove', event => {
  if (!pointers.has(event.pointerId)) return; const p = localPoint(event); pointers.set(event.pointerId, p); if (!gesture) return;
  if (pointers.size >= 2) { if (gesture.type !== 'pinch') beginPinch(); const [a, b] = [...pointers.values()], mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, d = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)); $('span').value = clamp(gesture.span * gesture.distance / d, 5, 100000).toFixed(2); const scale = canvas.clientWidth / span(), sign = $('flip').checked ? 1 : -1; center = [gesture.world[0] - (mid.x - canvas.clientWidth / 2) / scale, gesture.world[1] - (mid.y - canvas.clientHeight / 2) / (sign * scale)]; $('follow').checked = false; initialized = true; }
  else if (gesture.type === 'pan') { const dx = p.x - gesture.point.x, dy = p.y - gesture.point.y; if (Math.hypot(dx, dy) > 6) gesture.moved = true; if (gesture.moved) { $('follow').checked = false; const scale = canvas.clientWidth / gesture.span, sign = $('flip').checked ? 1 : -1; center = [gesture.center[0] - dx / scale, gesture.center[1] - dy / (sign * scale)]; initialized = true; } } requestDraw();
});
function endPointer(event, cancelled) {
  if (!pointers.has(event.pointerId)) return; const p = localPoint(event);
  if (!cancelled && pointers.size === 1 && gesture?.type === 'pan' && !gesture.moved && Math.hypot(p.x - gesture.point.x, p.y - gesture.point.y) <= 6) { const hit = hits.map(item => ({ ...item, distance: Math.hypot(item.x - p.x, item.y - p.y) })).filter(item => item.distance <= 18).sort((a, b) => a.distance - b.distance)[0]; if (hit) inspect(hit.key); }
  pointers.delete(event.pointerId); if (pointers.size >= 2) beginPinch(); else if (pointers.size === 1) gesture = { type: 'pan', point: [...pointers.values()][0], center: [...center], span: span(), moved: true }; else gesture = null; requestDraw();
}
canvas.addEventListener('pointerup', event => endPointer(event, false)); canvas.addEventListener('pointercancel', event => endPointer(event, true)); canvas.addEventListener('lostpointercapture', event => endPointer(event, true));
canvas.addEventListener('wheel', event => { event.preventDefault(); zoom(event.deltaY > 0 ? 1.15 : 1 / 1.15); }, { passive: false });
canvas.addEventListener('keydown', event => { if (event.key === '+' || event.key === '=') zoom(1 / 1.25); else if (event.key === '-') zoom(1.25); else if (event.key.toLowerCase() === 'r') fit(); else if (['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) { $('follow').checked = false; const delta = span() * .08, sign = $('flip').checked ? 1 : -1; if (event.key === 'ArrowLeft') center[0] -= delta; if (event.key === 'ArrowRight') center[0] += delta; if (event.key === 'ArrowUp') center[1] -= sign * delta; if (event.key === 'ArrowDown') center[1] += sign * delta; initialized = true; requestDraw(); } else return; event.preventDefault(); });
window.addEventListener('resize', requestDraw);
if (typeof ResizeObserver !== 'undefined') new ResizeObserver(requestDraw).observe(canvas);
async function poll() {
  const abort = new AbortController(), timeout = setTimeout(() => abort.abort(), 2500);
  try { const response = await fetch('/api/state', { cache: 'no-store', signal: abort.signal }); if (!response.ok) throw new Error('HTTP ' + response.status); const next = await response.json(); if (!next || !number(next.now) || !Array.isArray(next.entities)) throw new Error('后端快照结构不匹配'); live = next; liveFetched = performance.now(); connected = true; pollError = ''; if (!paused) adopt(next); render(); }
  catch (error) { connected = false; pollError = error.name === 'AbortError' ? '本机后端响应超时；保留的坐标不代表实时位置。' : '无法读取本机后端：' + error.message; render(); }
  finally { clearTimeout(timeout); setTimeout(poll, 500); }
}
render();
setInterval(() => { if (document.hidden) return; renderCamera(); renderStatus(); if (!paused) { syncSelf(); updateOptions(); renderSelection(); renderTable(); requestDraw(); } }, 800);
poll();
