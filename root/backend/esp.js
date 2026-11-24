'use strict';
// Screen-coordinate preview only. Game state comes exclusively from /api/state.
const P = window.UdpProjection;
const $ = id => document.getElementById(id);
const canvas = $('espCanvas'), ctx = canvas.getContext('2d');
const profileIds = { viewport_width: 'espWidth', viewport_height: 'espHeight', vertical_fov_deg: 'espVFov', eye_height: 'espEye', boom_distance: 'espBoom', shoulder_offset: 'espShoulder', marker_height: 'espMarker', roll_deg: 'espRoll' };
const reasons = {
  waiting_api: '等待现有 UDP 后端状态。', automatic_camera_not_reconstructed: '自动相机原点与最终 FOV 尚未还原，不绘制。可明确选择手动标定近似预览。',
  manual_approximation_not_acknowledged: '手动模式须明确确认近似投影的限制。', manual_profile_required: '请填写并应用全部手动参数；没有默认 FOV 或镜头偏移。',
  choose_udp_stream: '收到多个有效视角分流，请明确选择一个，不混合不同连接。', fresh_camera_required: '缺少 1 秒内的 UDP 视角；暂停转动时游戏可能不持续上报。',
  camera_angles_missing: 'UDP 视角缺少可用角度。', udp_local_identity_required: '缺少同分流、1 秒内且由协议确认的自身实体；不使用手选参考代替。',
  ambiguous_local_identity: '同一分流出现多个自身候选，停止投影。', local_camera_time_skew: '自身坐标与视角时间差超过 0.35 秒，停止投影。',
  disconnected: '接口断开，已清空投影；旧快照不会当作实时数据。', draft_profile_changed: '参数已修改，重新应用前停止投影。', internal_error: '投影出现异常，已停止绘制。'
};
let latest = null, fetchedAt = 0, connected = false, pollError = '', paused = false, frozen = null, frozenTime = 0;
let appliedProfile = null, dirty = false, lastResult = null, streamSignature = '', runtimeError = '';
const now = () => latest && Number.isFinite(latest.now) ? latest.now + Math.max(0, performance.now() - fetchedAt) / 1000 : Date.now() / 1000;
function setText(id, value) { const el = $(id); if (el.textContent !== String(value)) el.textContent = String(value); }
function options() { return { mode: $('espMode').value, stream: $('espStream').value, acknowledged: $('espAck').checked, profile: appliedProfile }; }
function syncStreams(snapshot) {
  const streams = P.cameras(snapshot).map(c => c.stream).sort();
  const selected = $('espStream').value;
  if (selected && !streams.includes(selected)) streams.push(selected);
  const signature = JSON.stringify(streams);
  if (signature === streamSignature) return;
  streamSignature = signature;
  const all = document.createDocumentFragment();
  const auto = document.createElement('option'); auto.value = ''; auto.textContent = '仅一个有效分流时自动选择'; all.append(auto);
  for (const stream of streams) { const option = document.createElement('option'); option.value = stream; option.textContent = stream; all.append(option); }
  $('espStream').replaceChildren(all); $('espStream').value = selected;
}
function draw(result) {
  const width = result.pose?.width || appliedProfile?.viewport_width || 960;
  const height = result.pose?.height || appliedProfile?.viewport_height || 540;
  // Cap backing store independently of game viewport; preserve identical aspect.
  const backingScale = Math.min(1, 2560 / width, 1440 / height);
  const cw = Math.max(1, Math.round(width * backingScale)), ch = Math.max(1, Math.round(height * backingScale));
  if (canvas.width !== cw || canvas.height !== ch) { canvas.width = cw; canvas.height = ch; }
  ctx.setTransform(1, 0, 0, 1, 0, 0); ctx.clearRect(0, 0, cw, ch);
  ctx.fillStyle = '#060c14'; ctx.fillRect(0, 0, cw, ch);
  const scale = cw / width; ctx.setTransform(scale, 0, 0, scale, 0, 0);
  const pixel = width / Math.max(320, canvas.clientWidth || 960);
  ctx.strokeStyle = '#29445f'; ctx.lineWidth = pixel;
  ctx.beginPath(); ctx.moveTo(width / 2 - 9 * pixel, height / 2); ctx.lineTo(width / 2 + 9 * pixel, height / 2); ctx.moveTo(width / 2, height / 2 - 9 * pixel); ctx.lineTo(width / 2, height / 2 + 9 * pixel); ctx.stroke();
  ctx.font = `${12 * pixel}px system-ui,sans-serif`; ctx.textBaseline = 'top'; ctx.fillStyle = '#f3c985';
  ctx.fillText(paused ? '冻结快照 · 非实时' : result.status === 'manual_approximate' ? '手动标定 · 近似投影 · 非游戏悬浮窗' : '投影已阻塞 · 无假数据', 12 * pixel, 12 * pixel);
  if (result.status !== 'manual_approximate') return;
  ctx.save(); ctx.beginPath(); ctx.rect(0, 0, width, height); ctx.clip();
  for (const point of result.points) {
    ctx.strokeStyle = '#68d9ff'; ctx.lineWidth = 1.5 * pixel;
    ctx.beginPath(); ctx.arc(point.x, point.y, 5 * pixel, 0, Math.PI * 2); ctx.stroke();
    const label = point.name + ' · ' + point.distance_world_units.toFixed(1) + ' 世界单位';
    const metrics = ctx.measureText(label), labelWidth = Math.min(width - 12 * pixel, metrics.width + 10 * pixel);
    const x = Math.max(6 * pixel, Math.min(width - labelWidth - 6 * pixel, point.x - labelWidth / 2));
    const y = Math.max(28 * pixel, Math.min(height - 22 * pixel, point.y - 23 * pixel));
    ctx.fillStyle = '#07101fdd'; ctx.fillRect(x, y, labelWidth, 18 * pixel); ctx.fillStyle = '#d3f2ff';
    ctx.fillText(label, x + 5 * pixel, y + 2 * pixel, labelWidth - 8 * pixel);
  }
  ctx.restore();
}
function selectedContext(snapshot, result) {
  const items = Array.isArray(snapshot?.camera_context) ? snapshot.camera_context : [];
  const stream = $('espStream').value || result?.pose?.stream || result?.stream;
  const item = stream ? items.find(value => value.stream === stream) : items.length === 1 ? items[0] : null;
  if (!item) return { status: items.length ? 'choose_matching_stream' : 'not_observed' };
  return { gameplay_type: item.gameplay_type, gameplay_id: item.gameplay_id, gameplay_name: item.gameplay_name,
    login_timestamp: item.login_timestamp, initial_dictionary_entries: item.initial_dictionary_entries,
    perspective_status: item.perspective_status, reported_perspective: item.reported_perspective_name,
    perspective_source: item.reported_perspective?.source ?? null,
    perspective_timestamp: item.reported_perspective?.timestamp ?? null,
    render_camera_status: item.render_camera_status, missing_inputs: item.missing_inputs };
}
function render() {
  try {
    runtimeError = '';
    const snapshot = paused ? frozen : latest;
    syncStreams(snapshot);
    let result;
    if (!paused && !connected) result = { status: 'blocked', reason: latest ? 'disconnected' : 'waiting_api', points: [] };
    else if (dirty) result = { status: 'blocked', reason: 'draft_profile_changed', points: [] };
    else result = P.projectSnapshot(snapshot, options(), paused ? frozenTime : now());
    lastResult = result;
    setText('espState', paused ? '冻结快照 · 非实时' : result.status === 'manual_approximate' ? '手动近似 · ' + result.points.length + ' 个屏幕标记' : '未绘制');
    setText('espHint', result.status === 'manual_approximate' ? 'UDP 坐标/视角 + 手动固定镜头模型；变焦、碰撞或动作覆盖时可能偏离实际画面。' : reasons[result.reason] || result.reason);
    setText('espMetrics', result.pose ? `视口 ${result.pose.width} × ${result.pose.height} · 手动垂直 FOV ${result.profile.vertical_fov_deg}° · 视角时效 ${result.camera_age_seconds.toFixed(2)}s · 自身时效 ${result.local_age_seconds.toFixed(2)}s` : '自动镜头未完成；本页不修改后端 fov_deg，也不将手动输入伪装成 UDP 测量。');
    setText('espDetails', JSON.stringify({ status: result.status, reason: result.reason, invalid_fields: result.invalid_fields, rejected: result.rejected, unsupported: result.unsupported, camera_context: selectedContext(snapshot, result) }, null, 2));
    draw(result);
  } catch (error) {
    runtimeError = String(error?.message || error).slice(0, 240); lastResult = { status: 'blocked', reason: 'internal_error', points: [] };
    setText('espState', '投影异常'); setText('espHint', reasons.internal_error); setText('espDetails', runtimeError); draw(lastResult);
  }
}
function diagnostic() {
  return { feature: 'udp-screen-projection-r1', copied_at_utc: new Date().toISOString(), backend_version: latest?.version ?? null,
    api_connected: connected, frozen: paused, mode: $('espMode').value, manual_profile_applied: !!appliedProfile, draft_changed: dirty,
    manual_profile: appliedProfile, status: lastResult?.status, reason: lastResult?.reason, invalid_fields: lastResult?.invalid_fields,
    visible_count: lastResult?.points?.length || 0, rejected: lastResult?.rejected || {}, unsupported: lastResult?.unsupported || [],
    camera_age_seconds: lastResult?.camera_age_seconds ?? null, local_age_seconds: lastResult?.local_age_seconds ?? null,
    camera_context: selectedContext(paused ? frozen : latest, lastResult),
    measured_realtime_fov_implemented: false, automatic_camera_reconstruction: false, android_overlay_implemented: false,
    errors: { api: pollError || null, projection: runtimeError || null },
    privacy_note: 'No entity names, IDs, stream addresses, UID, coordinates or camera origin included. Review error text before sharing.' };
}
function markDirty() { dirty = true; appliedProfile = null; setText('espProfileState', '参数已修改，请重新应用；当前不投影。'); render(); }
for (const id of Object.values(profileIds)) $(id).addEventListener('input', markDirty);
$('espAck').addEventListener('change', () => { if (!$('espAck').checked) markDirty(); else render(); });
$('espMode').addEventListener('change', render); $('espStream').addEventListener('change', render);
$('espProfile').addEventListener('submit', event => {
  event.preventDefault(); const raw = Object.fromEntries(Object.entries(profileIds).map(([key, id]) => [key, $(id).value]));
  const check = P.validateProfile(raw);
  if (!check.ok || !$('espAck').checked) { appliedProfile = null; dirty = false; setText('espProfileState', check.ok ? '请先确认手动近似模式限制。' : '参数无效：' + check.issues.join(', ')); render(); return; }
  appliedProfile = check.profile; dirty = false; setText('espProfileState', '手动固定镜头参数已应用，仅本页内存保存；不是实时测量。'); render();
});
$('espClear').addEventListener('click', () => { $('espProfile').reset(); appliedProfile = null; dirty = false; $('espMode').value = 'automatic'; setText('espProfileState', '标定已清除'); render(); });
$('espPause').addEventListener('click', () => {
  if (!paused) { frozen = latest; frozenTime = now(); paused = true; } else { paused = false; frozen = null; }
  $('espPause').textContent = paused ? '恢复预览' : '冻结预览'; $('espPause').setAttribute('aria-pressed', String(paused)); render();
});
$('espFull').addEventListener('click', async () => { try { if (document.fullscreenElement) await document.exitFullscreen(); else await document.documentElement.requestFullscreen(); } catch (_) { setText('espHint', '浏览器未允许页面全屏；此功能不是 Android 游戏悬浮窗。'); } });
$('espCopy').addEventListener('click', async () => {
  const text = JSON.stringify(diagnostic(), null, 2); $('espReport').value = text; $('espReportBox').open = true;
  try { await navigator.clipboard.writeText(text); setText('espHint', '投影诊断已复制，包含手动参数，不含实体名单或坐标；分享前请检查。'); } catch (_) { setText('espHint', '浏览器未允许复制，可长按下方诊断文本手动复制。'); }
});
async function poll() {
  const abort = new AbortController(), timeout = setTimeout(() => abort.abort(), 2500);
  try {
    const response = await fetch('/api/state', { cache: 'no-store', signal: abort.signal });
    if (!response.ok) throw new Error('HTTP ' + response.status);
    const snapshot = await response.json();
    if (!snapshot || !Number.isFinite(snapshot.now) || !Array.isArray(snapshot.entities)) throw new Error('后端快照结构不匹配');
    latest = snapshot; fetchedAt = performance.now(); connected = true; pollError = '';
  } catch (error) { connected = false; pollError = error.name === 'AbortError' ? '本机接口超时' : String(error.message).slice(0, 240); }
  finally { clearTimeout(timeout); render(); setTimeout(poll, 250); }
}
if (!P) { setText('espHint', '投影模块缺失，请使用完整成品并重启后端。'); }
else { render(); setInterval(() => { if (!document.hidden) render(); }, 200); poll(); }