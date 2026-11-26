/* 港口靠泊协同服务前端（原生 JS，无构建） */
const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));

const state = {
  token: localStorage.getItem('pb_token') || null,
  user: null,
  meta: null,
  voyages: [],
  agentSelected: null,
  plannerSelected: null,
  boardTimer: null,
};

const STATUS_LABEL = {
  SUBMITTED: '已提交', ETA_UPDATED: 'ETA 已补报', WITHDRAWN: '已撤回', CONFIRMED: '已有确认计划',
  CANDIDATE: '候选', STAGED: '已暂存·资源暂留中', EXPIRED: '暂留过期', CANCELLED: '已取消',
  SUPERSEDED: '已被新候选取代', REPLANNED: '已被重排替代',
};
const SEG_LABEL = {
  PILOTAGE: '引航', TUG_INBOUND: '拖轮靠泊', BERTHING: '靠泊操纵', CARGO_OP: '装卸作业',
  TUG_OUTBOUND: '拖轮离泊', UNBERTHING: '离泊',
};

function esc(o) {
  if (o === null || o === undefined) return '';
  return String(o).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
function fmtDT(iso) {
  if (!iso) return '-';
  const s = String(iso);
  return s.replace('T', ' ').slice(5, 16);
}
function fmtFull(iso) { return iso ? String(iso).replace('T', ' ').slice(0, 19) : '-'; }
function toInputDT(iso) { return iso ? String(iso).slice(0, 16) : ''; }

function toast(msg, type = '') {
  const t = $('#toast');
  t.className = 'toast ' + type;
  t.innerHTML = msg;
  t.classList.remove('hidden');
  clearTimeout(toast._timer);
  toast._timer = setTimeout(() => t.classList.add('hidden'), 5200);
}

async function api(path, options = {}) {
  const opts = { headers: { 'Content-Type': 'application/json' }, ...options };
  if (state.token) opts.headers.Authorization = 'Bearer ' + state.token;
  if (opts.body && typeof opts.body !== 'string') opts.body = JSON.stringify(opts.body);
  const res = await fetch(path, opts);
  if (res.status === 401) { logout(); throw new Error('未登录或登录已失效'); }
  let data = null;
  const text = await res.text();
  if (text) { try { data = JSON.parse(text); } catch { data = text; } }
  if (!res.ok) {
    const err = new Error((data && data.message) || ('HTTP ' + res.status));
    err.status = res.status; err.data = data;
    throw err;
  }
  return data;
}

function showError(err) {
  let extra = '';
  if (err.data && err.data.details && Array.isArray(err.data.details)) {
    extra = '<div style="margin-top:6px">' + err.data.details.slice(0, 8).map((r) =>
      `<div>· [${esc(r.code)}] ${esc(r.message)}</div>`).join('') + '</div>';
  }
  toast(`❌ ${esc(err.message)}${extra}`, 'error');
}

/* ---------------- 登录 ---------------- */
async function boot() {
  try {
    state.meta = await api('/api/meta');
    renderAccountChips();
    initMetaForms();
  } catch (e) { /* 未登录也允许渲染登录页账号提示？meta 需要 token，忽略 */ }

  if (state.token) {
    try {
      state.user = await api('/api/auth/me');
      enterApp();
      return;
    } catch { localStorage.removeItem('pb_token'); state.token = null; }
  }
  $('#login-view').classList.remove('hidden');
}

function renderAccountChips() {
  const accounts = (state.meta && state.meta.accounts) || [
    { username: 'agent', password: 'agent123', displayName: '船代' },
    { username: 'dispatch', password: 'dispatch123', displayName: '调度' },
  ];
  $('#account-chips').innerHTML = accounts.map((a) =>
    `<span class="chip" onclick="fillAccount('${a.username}','${a.password}')">${esc(a.username)} / ${esc(a.password)} · ${esc(a.displayName)}</span>`
  ).join('');
}
function fillAccount(u, p) { $('#login-username').value = u; $('#login-password').value = p; }

async function doLogin() {
  try {
    const r = await api('/api/auth/login', {
      method: 'POST',
      body: { username: $('#login-username').value.trim(), password: $('#login-password').value },
    });
    state.token = r.token; state.user = r.user;
    localStorage.setItem('pb_token', r.token);
    $('#login-view').classList.add('hidden');
    enterApp();
  } catch (e) { showError(e); }
}
function logout() {
  state.token = null; state.user = null;
  localStorage.removeItem('pb_token');
  location.reload();
}

async function enterApp() {
  $('#login-view').classList.add('hidden');
  $('#app-view').classList.remove('hidden');
  $('#current-user').textContent = `${state.user.displayName}（${state.user.role}）`;
  state.meta = await api('/api/meta');
  initMetaForms();
  await loadVoyages();
  loadTideTable();
  loadDisruptions();
  switchTab('agent');
}

function initMetaForms() {
  if (!state.meta) return;
  $('#f-ship').innerHTML = state.meta.ships.map((s) =>
    `<option value="${s.id}">${esc(s.code)} ${esc(s.name)}｜${s.lengthM}m/吃水${s.draftM}m/${esc(cargoLabel(s.cargoType))}</option>`).join('');
  $('#fault-crane').innerHTML = state.meta.cranes.map((c) =>
    `<option value="${c.id}" ${c.code === 'QC-2' ? 'selected' : ''}>${esc(c.code)} ${esc(c.name)}</option>`).join('');
  // 默认故障窗口：样例日 20:45-22:30
  if (!$('#fault-start').value) $('#fault-start').value = '2026-11-26T20:45';
  if (!$('#fault-end').value) $('#fault-end').value = '2026-11-26T22:30';
  if (!$('#f-eta-start').value) { $('#f-eta-start').value = '2026-11-26T18:00'; $('#f-eta-end').value = '2026-11-26T19:00'; }
}
function cargoLabel(c) {
  return { CONTAINER: '集装箱', BULK: '散货', TANKER: '液货', RORO: '滚装', GENERAL: '件杂' }[c] || c;
}

/* ---------------- Tabs ---------------- */
function switchTab(tab) {
  $$('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === tab));
  $$('.tab-panel').forEach((p) => p.classList.remove('active'));
  $('#tab-' + tab).classList.add('active');
  if (tab === 'planner') { loadVoyages().then(renderPlannerSelect); }
  if (tab === 'team') { loadBoard(); startTeamTimer(); } else stopTeamTimer();
  if (tab === 'sim') { loadTideTable(); loadDisruptions(); }
  if (tab === 'power') { loadPowerOverview(); }
}
function startTeamTimer() {
  stopTeamTimer();
  if ($('#team-auto').checked) state.boardTimer = setInterval(loadBoard, 5000);
}
function stopTeamTimer() { if (state.boardTimer) clearInterval(state.boardTimer); state.boardTimer = null; }
function toggleTeamTimer() { $('#team-auto').checked ? startTeamTimer() : stopTeamTimer(); }

/* ---------------- 船次 ---------------- */
async function loadVoyages() {
  state.voyages = await api('/api/voyages');
  renderVoyageList();
  renderPlannerSelect();
}

function renderVoyageList() {
  $('#voyage-list').innerHTML = state.voyages.map((v) => `
    <div class="voyage-item ${state.agentSelected === v.id ? 'selected' : ''}" onclick="selectAgentVoyage(${v.id})">
      <div>
        <div class="no">${esc(v.voyageNo)} <span class="badge ${v.status}">${STATUS_LABEL[v.status] || v.status}</span></div>
        <div class="meta">${esc(v.ship.name)} · ${v.ship.lengthM}m / 吃水 ${v.ship.draftM}m · ${cargoLabel(v.ship.cargoType)}</div>
        <div class="meta">ETA ${fmtDT(v.etaStart)} ~ ${fmtDT(v.etaEnd)} · 作业 ${v.workDurationMin}′ · 岸桥 ×${v.craneCount}</div>
      </div>
      <div class="meta">${esc(v.agentName || '')}</div>
    </div>`).join('');
}

function renderPlannerSelect() {
  const sel = $('#planner-voyage');
  if (!sel) return;
  const keep = state.plannerSelected || sel.value;
  sel.innerHTML = '<option value="">— 选择船次 —</option>' + state.voyages.map((v) =>
    `<option value="${v.id}">${esc(v.voyageNo)} · ${esc(v.ship.name)} · ETA ${fmtDT(v.etaStart)}</option>`).join('');
  if (keep) { sel.value = keep; state.plannerSelected = Number(keep) || keep; }
}

async function submitVoyage(ev) {
  ev.preventDefault();
  try {
    await api('/api/voyages', {
      method: 'POST',
      body: {
        shipId: Number($('#f-ship').value),
        voyageNo: $('#f-no').value || null,
        etaStart: $('#f-eta-start').value,
        etaEnd: $('#f-eta-end').value,
        workDurationMin: Number($('#f-duration').value),
        craneCount: Number($('#f-cranes').value),
        remark: $('#f-remark').value || null,
      },
    });
    toast('✅ 船次已提交', 'success');
    $('#f-no').value = '';
    await loadVoyages();
  } catch (e) { showError(e); }
  return false;
}

async function selectAgentVoyage(id) {
  state.agentSelected = id;
  renderVoyageList();
  const d = await api(`/api/voyages/${id}`);
  const v = d.voyage;
  $('#agent-detail').innerHTML = `
    <div class="card">
      <div class="row-between">
        <h2>${esc(v.voyageNo)} · ${esc(v.ship.name)}
          <span class="badge ${v.status}">${STATUS_LABEL[v.status] || v.status}</span></h2>
        <div class="row gap">
          <button class="btn" onclick="etaForm(${id})">补报 ETA</button>
          <button class="btn danger" onclick="withdraw(${id})">撤回授权</button>
        </div>
      </div>
      <div class="kv">
        船舶参数：${v.ship.lengthM}m / 吃水 ${v.ship.draftM}m / ${cargoLabel(v.ship.cargoType)}　|
        ETA <b>${fmtFull(v.etaStart)} ~ ${fmtFull(v.etaEnd)}</b>（修订 ${v.etaRevision} 次）　|
        作业 ${v.workDurationMin} 分钟，需岸桥 ${v.craneCount} 台<br/>
        船代：${esc(v.agentName)}　|　备注：${esc(v.remark || '-')}
      </div>
      <h3 class="section-title">不可变作业记录（引航 / 装卸台账）</h3>
      ${recordsTable(d.records)}
      <h3 class="section-title">事件流</h3>
      <div class="seg-list">${(d.events || []).map((ev) => `
        <div class="seg"><span>•</span><div><b>${esc(ev.event_type)}</b> ${esc(ev.message || '')}</div>
        <div class="when">${fmtFull(ev.created_at)}</div><div></div></div>`).join('') || '<span class="hint">暂无事件</span>'}
      </div>
    </div>`;
}

function recordsTable(records) {
  if (!records || !records.length) return '<p class="hint">暂无已完成记录。记录一旦写入，数据库触发器禁止修改/删除。</p>';
  return `<table class="data"><thead><tr><th>#</th><th>类型</th><th>班组/操作人</th><th>作业分钟</th><th>箱量</th><th>结果</th><th>完成时间</th></tr></thead><tbody>
    ${records.map((r) => `<tr><td>${r.id}</td><td>${r.record_type}</td><td>${esc(r.operator_name || '')}</td>
      <td>${r.work_minutes}</td><td>${r.moved_units}</td><td>${esc(r.result_note || '')}</td>
      <td class="mono">${fmtFull(r.completed_at)}</td></tr>`).join('')}
  </tbody></table>`;
}

async function etaForm(id) {
  const vals = await openForm({
    title: '补报 / 修订 ETA',
    fields: [
      { key: 'etaStart', label: '新 ETA 开始', type: 'datetime-local', required: true },
      { key: 'etaEnd', label: '新 ETA 结束', type: 'datetime-local', required: true },
      { key: 'note', label: '说明', type: 'text', value: '引航/潮位变化' },
    ],
  });
  if (!vals) return;
  try {
    const r = await api(`/api/voyages/${id}/eta`, { method: 'PATCH', body: vals });
    if (r.etaImpact) toast('⚠️ ETA 迟到已冲击已确认计划，受影响工作段已标记，请前往调度工作台重排', 'error');
    else toast('✅ ETA 已补报', 'success');
    selectAgentVoyage(id); loadVoyages();
  } catch (e) { showError(e); }
}

async function withdraw(id) {
  if (!confirm('确认撤回该船次授权？相关暂存/确认方案将取消并释放资源，已完成记录仍保留。')) return;
  try { await api(`/api/voyages/${id}/withdraw`, { method: 'POST' }); toast('已撤回授权并释放资源'); selectAgentVoyage(id); loadVoyages(); }
  catch (e) { showError(e); }
}

/* ---------------- 调度工作台 ---------------- */
async function renderPlannerDetail() {
  const id = Number($('#planner-voyage').value);
  state.plannerSelected = id || null;
  if (!id) { $('#planner-detail').innerHTML = '<p class="hint">请选择船次并生成候选。</p>'; return; }
  const d = await api(`/api/voyages/${id}`);
  const plans = d.plans || [];
  $('#planner-detail').innerHTML = `
    <div class="kv" style="margin:8px 0">
      ${esc(d.voyage.voyageNo)} · ${esc(d.voyage.ship.name)} · 吃水 ${d.voyage.ship.draftM}m ·
      ETA ${fmtFull(d.voyage.etaStart)} ~ ${fmtFull(d.voyage.etaEnd)} · 状态
      <span class="badge ${d.voyage.status}">${STATUS_LABEL[d.voyage.status]}</span>
    </div>
    ${plans.length ? `<div class="cand-list">${plans.map(planCard).join('')}</div>`
      : '<p class="hint">尚未生成候选，点击右上角“生成/刷新候选计划”。</p>'}`;
}

function planCard(p) {
  const good = p.feasible;
  const isCandidate = ['CANDIDATE', 'EXPIRED', 'CANCELLED'].includes(p.status);
  return `
  <div class="cand ${good ? 'good' : 'bad'} ${p.status === 'CONFIRMED' ? 'recommended' : ''}">
    <div class="cand-head">
      <div class="cand-title">${esc(p.planNo)} · ${esc(p.berthCode || '')} ${esc(p.berthName || '')}</div>
      <div class="row gap">
        <span class="badge feasible-${p.feasible}">${p.feasible ? '可行' : '不可行/有等待'}</span>
        <span class="badge ${p.status}">${STATUS_LABEL[p.status] || p.status}</span>
      </div>
    </div>
    <div class="time-grid">
      <span>靠泊起点：<b>${fmtFull(p.berthFrom)}</b></span>
      <span>引航开始：<b>${fmtFull(p.pilotStart)}</b></span>
      <span>装卸窗口：<b>${fmtDT(p.opsStart)} ~ ${fmtDT(p.opsEnd)}</b></span>
      <span>离泊结束：<b>${fmtFull(p.berthTo)}</b></span>
      ${p.waitMinutes ? `<span>ETA 后等待：<b>${p.waitMinutes} 分钟</b></span>` : ''}
      ${p.holdExpiresAt ? `<span>暂留到期：<b style="color:#9a6b00">${fmtFull(p.holdExpiresAt)}</b></span>` : ''}
      ${p.releasedAt ? `<span>释放时间：<b>${fmtFull(p.releasedAt)}</b>（${esc(p.releaseReason || '')}）</span>` : ''}
      ${p.versionNo > 1 ? `<span>版本：<b>v${p.versionNo}</b> 替代 #${p.replanOfId}</span>` : ''}
    </div>
    <div class="assign">${(p.assignments || []).map((a) =>
      `<span class="res-chip">${({ BERTH: '泊位', PILOT: '引航', TUG: '拖轮', CRANE: '岸桥' })[a.resourceType] || a.resourceType} ${esc(a.code)} · ${esc(a.role || '')}</span>`).join('')}
    </div>
    ${(p.reasons || []).length ? `<div class="reasons">${p.reasons.map((r) =>
      `<div class="reason"><span class="code">[${esc(r.code)}]</span> ${esc(r.message)}
        ${r.holderVoyageNo ? `<br/>&nbsp;&nbsp;↳ 占用方 ${esc(r.holderVoyageNo)} / ${esc(r.holderPlanNo || '')}（${r.holderKind === 'TENTATIVE' ? '暂留' : '已确认'}${r.holdExpiresAt ? '，暂留至 ' + fmtFull(r.holdExpiresAt) : ''}）` : ''}
      </div>`).join('')}</div>` : ''}
    ${segmentTimeline(p.segments)}
    <div class="cand-actions">
      ${isCandidate ? `
        <label class="hint">TTL <input class="ttl-input" type="number" id="ttl-${p.id}" value="120" min="2" max="3600"/> 秒</label>
        <button class="btn primary tiny" onclick="stagePlan(${p.id}, true)">暂存并暂留资源</button>
        <button class="btn tiny" title="只保存候选不占资源，确认时原子锁定" onclick="stagePlan(${p.id}, false)">仅暂存不占资源</button>
        <button class="btn success tiny" onclick="confirmPlan(${p.id})">直接原子确认</button>` : ''}
      ${p.status === 'STAGED' ? `
        <button class="btn success tiny" onclick="confirmPlan(${p.id})">确认（转 FIRM 锁定）</button>
        <button class="btn danger tiny" onclick="cancelPlan(${p.id})">取消暂存并释放</button>` : ''}
      ${p.status === 'CONFIRMED' ? `
        <span class="badge CONFIRMED">✅ 已对引航/拖轮/岸桥班组生效</span>
        <button class="btn primary tiny" onclick="changeBerthPlan(${p.id},${p.berthId},'${toInputDT(p.berthFrom)}')">改靠/改时</button>
        <button class="btn warn tiny" onclick="replanPlan(${p.id})">扰动后重排（自动确认）</button>` : ''}
      <button class="btn tiny" onclick="toggleRaw(${p.id})">原始 JSON</button>
    </div>
    <pre class="json hidden" id="raw-${p.id}">${esc(JSON.stringify(p, null, 2))}</pre>
  </div>`;
}

function segmentTimeline(segs) {
  if (!segs || !segs.length) return '';
  return `<div class="seg-list">${segs.map((s) => `
    <div class="seg ${s.status} ${s.affected ? 'affected' : ''}">
      <span class="dot"></span>
      <div><b>${SEG_LABEL[s.segType] || s.segType}</b> ${esc(s.title)}
        <div class="res">${s.resourceCode ? '资源 ' + esc(s.resourceCode) : '（待分配）'}</div>
      </div>
      <div class="when">${fmtDT(s.startAt)}<br/>→ ${fmtDT(s.endAt)}</div>
      <span class="badge seg-${s.status}">${({ PLANNED: '计划', IN_PROGRESS: '进行中', DONE: '已完成', CANCELLED: '已取消' })[s.status] || s.status}</span>
      ${s.affectReason ? `<div class="affect-note">⚠️ ${esc(s.affectReason)}</div>` : ''}
    </div>`).join('')}</div>`;
}

function toggleRaw(id) { $('#raw-' + id).classList.toggle('hidden'); }

async function generateCandidates() {
  const id = Number($('#planner-voyage').value);
  if (!id) return toast('请先选择船次', 'error');
  try {
    toast('⏳ 正在枚举泊位/潮位/人员/设备组合…');
    await api(`/api/voyages/${id}/candidates/generate`, { method: 'POST' });
    await renderPlannerDetail();
    toast('✅ 候选已生成', 'success');
  } catch (e) { showError(e); }
}

async function stagePlan(id, hold) {
  const ttlEl = $('#ttl-' + id);
  const ttl = ttlEl ? Number(ttlEl.value) : 120;
  try {
    await api(`/api/plans/${id}/stage`, { method: 'POST', body: { holdResources: hold, holdTtlSeconds: ttl } });
    toast(hold ? `✅ 已暂存并暂留全套资源，TTL ${ttl}s` : '✅ 已仅暂存候选（未占资源）', 'success');
    await renderPlannerDetail();
  } catch (e) { showError(e); await renderPlannerDetail(); }
}
async function confirmPlan(id) {
  try {
    await api(`/api/plans/${id}/confirm`, { method: 'POST' });
    toast('✅ 已确认：全套资源原子锁定，班组看板已生效', 'success');
    await renderPlannerDetail(); loadVoyages();
  } catch (e) { showError(e); await renderPlannerDetail(); }
}
async function cancelPlan(id) {
  if (!confirm('取消该暂存方案并释放资源？')) return;
  try { await api(`/api/plans/${id}/cancel`, { method: 'POST', body: { reason: '调度手动取消' } }); await renderPlannerDetail(); }
  catch (e) { showError(e); }
}
async function replanPlan(id) {
  if (!confirm('按当前最新潮汐/设备状态重排该计划？未完成段将被新版本替代，已完成记录保留。')) return;
  try {
    const r = await api(`/api/plans/${id}/replan`, { method: 'POST', body: { autoConfirm: true } });
    toast(`✅ 重排完成（${r.mode}），剩余 ${r.remainingMinutes} 分钟已排入新版本并确认`, 'success');
    await renderPlannerDetail();
  } catch (e) { showError(e); }
}
async function changeBerthPlan(id, currentBerthId, currentFrom) {
  const vals = await openForm({
    title: '下达改靠 / 改时指令',
    fields: [
      {
        key: 'newBerthId', label: '改靠泊位', type: 'select', value: String(currentBerthId),
        options: (state.meta.berths || []).map((b) => ({ value: String(b.id), label: `${b.code} ${b.name}` })),
      },
      { key: 'newBerthFrom', label: '新靠泊起点', type: 'datetime-local', value: currentFrom },
    ],
  });
  if (!vals) return;
  try {
    const r = await api(`/api/plans/${id}/change-berth`, {
      method: 'POST',
      body: { newBerthId: Number(vals.newBerthId), newBerthFrom: vals.newBerthFrom, autoConfirm: true },
    });
    toast(`✅ 改靠已执行：${r.targetBerthCode} ${fmtDT(r.targetBerthFrom)}，受影响工作段已生成新版本`, 'success');
    await renderPlannerDetail();
  } catch (e) { showError(e); }
}

/* ---------------- 班组看板 ---------------- */
async function loadBoard() {
  let b;
  try { b = await api('/api/teams/board'); } catch { return; }
  const card = (c, kind) => `
    <div class="work-card ${c.affected ? 'affected' : ''}">
      <div class="wc-title">${esc(c.voyageNo)} · ${esc(c.shipName || '')}</div>
      <div class="wc-meta">
        ${esc(c.title || '')} ${c.resourceCode ? '· <b>' + esc(c.resourceCode) + '</b>' : ''}<br/>
        ${fmtDT(c.startAt || c.opsStart)} → ${fmtDT(c.endAt || c.opsEnd)}
        ${c.berthCode ? ' · 泊位 ' + esc(c.berthCode) : ''}
        ${c.craneCode ? ' · ' + esc(c.craneCode) : ''}
      </div>
      ${c.affected ? `<div class="reason" style="color:#b3261e;font-size:12px">⚠️ ${esc(c.affectReason || '受扰动影响')}</div>` : ''}
      ${c.segmentId ? segmentActions(c) : ''}
    </div>`;

  const segmentActions = (c) => {
    if (c.status === 'DONE') return `<span class="badge seg-DONE">已完成 · 记录 #${c.recordId || ''}</span>`;
    return `<div class="wc-actions">
      ${c.status !== 'IN_PROGRESS' ? `<button class="btn tiny" onclick="segStart(${c.segmentId})">开工</button>` : ''}
      ${c.segType === 'CARGO_OP' ? `<button class="btn tiny" onclick="segLog(${c.segmentId})">登记部分作业</button>` : ''}
      <button class="btn success tiny" onclick="segComplete(${c.segmentId},'${c.segType}')">完成并写记录</button>
    </div>`;
  };
  window.__segmentActions = segmentActions;

  $('#team-board').innerHTML = `
    <p class="hint">${esc(b.notice)}　当前生效确认方案：<b>${b.effectivePlans}</b></p>
    <div class="board-grid">
      <div class="team-col"><h3>🧑‍✈️ 引航 <span>${b.pilot.length}</span></h3>${b.pilot.map((c) => card(c, 'pilot')).join('') || '<p class="hint">暂无</p>'}</div>
      <div class="team-col"><h3>🚢 拖轮 <span>${b.tug.length}</span></h3>${b.tug.map((c) => card(c, 'tug')).join('') || '<p class="hint">暂无</p>'}</div>
      <div class="team-col"><h3>🏗️ 岸桥 <span>${b.crane.length}</span></h3>${b.crane.map((c) => card(c, 'crane')).join('') || '<p class="hint">暂无</p>'}</div>
      <div class="team-col"><h3>🟫 泊位 <span>${b.berth.length}</span></h3>${b.berth.map((c) => card(c, 'berth')).join('') || '<p class="hint">暂无</p>'}</div>
    </div>`;
}

async function segStart(id) {
  try { await api(`/api/segments/${id}/start`, { method: 'POST', body: { operatorName: state.user.displayName } }); toast('▶️ 已开工'); loadBoard(); }
  catch (e) { showError(e); }
}
async function segLog(id) {
  const vals = await openForm({
    title: '登记部分装卸作业（写入不可变台账）',
    fields: [
      { key: 'operatorName', label: '班组/操作人', value: state.user.displayName },
      { key: 'workMinutes', label: '本次作业分钟', type: 'number', value: '30' },
      { key: 'movedUnits', label: '完成箱量', type: 'number', value: '36' },
      { key: 'resultNote', label: '备注', type: 'text', value: '作业正常' },
    ],
  });
  if (!vals) return;
  vals.workMinutes = Number(vals.workMinutes); vals.movedUnits = Number(vals.movedUnits);
  try { await api(`/api/segments/${id}/log`, { method: 'POST', body: vals }); toast('✅ 台账已登记（不可修改）', 'success'); loadBoard(); }
  catch (e) { showError(e); }
}
async function segComplete(id, segType) {
  const fields = [{ key: 'operatorName', label: '操作人', value: state.user.displayName }];
  if (segType === 'CARGO_OP') {
    fields.push({ key: 'workMinutes', label: '作业分钟', type: 'number', value: '60' });
    fields.push({ key: 'movedUnits', label: '完成箱量', type: 'number', value: '72' });
  }
  fields.push({ key: 'resultNote', label: '结果备注', type: 'text', value: '完成' });
  const vals = await openForm({ title: '完成工作段（写不可变记录）', fields });
  if (!vals) return;
  if (vals.workMinutes) vals.workMinutes = Number(vals.workMinutes);
  if (vals.movedUnits) vals.movedUnits = Number(vals.movedUnits);
  try { await api(`/api/segments/${id}/complete`, { method: 'POST', body: vals }); toast('✅ 已完成，记录固化', 'success'); loadBoard(); }
  catch (e) { showError(e); }
}

/* ---------------- 模态表单 ---------------- */
function openForm({ title, fields }) {
  return new Promise((resolve) => {
    const box = $('#modal-box');
    box.innerHTML = `<h3>${esc(title)}</h3>
      ${fields.map((f) => `
        <label style="display:block;margin:10px 0 4px;font-weight:600;font-size:13px">${esc(f.label)}</label>
        ${f.type === 'select' ? `<select id="m-${f.key}" style="width:100%">
          ${(f.options || []).map((o) => `<option value="${esc(o.value)}" ${String(o.value) === String(f.value) ? 'selected' : ''}>${esc(o.label)}</option>`).join('')}
        </select>`
        : `<input id="m-${f.key}" type="${f.type || 'text'}" value="${esc(f.value || '')}"
          ${f.required ? 'required' : ''} style="width:100%"/>`}`).join('')}
      <div class="actions">
        <button class="btn" id="m-cancel">取消</button>
        <button class="btn primary" id="m-ok">确定</button>
      </div>`;
    $('#modal-mask').classList.remove('hidden');
    const close = (v) => { $('#modal-mask').classList.add('hidden'); resolve(v); };
    $('#m-cancel').onclick = () => close(null);
    $('#m-ok').onclick = () => {
      const v = {};
      for (const f of fields) v[f.key] = $('#m-' + f.key).value;
      close(v);
    };
  });
}
function closeModal() { $('#modal-mask').classList.add('hidden'); }

/* ---------------- 潮汐 / 扰动 ---------------- */
function loadTideTable() {
  if (!state.meta) return;
  // 重新拉取以反映修订
  api('/api/meta').then((m) => { state.meta = m; renderTideTable(); });
}
function renderTideTable() {
  const threshold = 3.0; // 样例深水船所需最低潮位（9.5 图示水深 + h - 11.5 >= 1）
  $('#tide-table').innerHTML = `<table class="data"><thead><tr><th>时段</th><th>水位(m)</th><th>深水船 UKC</th><th></th></tr></thead><tbody>
    ${state.meta.tides.map((t) => {
      const h = Number(t.height);
      const ok = h >= threshold;
      return `<tr><td class="mono">${fmtDT(t.start)}–${fmtDT(t.end).slice(6)}</td>
        <td class="tide-h ${ok ? 'deep' : 'shallow'}">${h.toFixed(2)}${t.revised ? ' ✏️' : ''}</td>
        <td>${ok ? '≥1.0m ✅' : '<1.0m ⛔'}</td>
        <td><button class="btn tiny" onclick="reviseTide(${t.id},${h})">修订</button></td></tr>`;
    }).join('')}</tbody></table>`;
}
async function reviseTide(id, oldH) {
  const vals = await openForm({
    title: `修订潮汐时段 #${id}（原水位 ${oldH}m）`,
    fields: [
      { key: 'heightM', label: '新水位（米）', type: 'number', value: oldH.toFixed(2), step: '0.05' },
      { key: 'reason', label: '原因', type: 'text', value: '模拟气象/径流更新' },
    ],
  });
  if (!vals) return;
  try {
    const r = await api(`/api/simulation/tides/${id}`, { method: 'PUT', body: { heightM: Number(vals.heightM), reason: vals.reason } });
    const n = (r.impacts || []).length;
    toast(n ? `⚠️ 潮汐已修订，产生 ${n} 条影响项，可到调度工作台重排` : '潮汐已修订，当前无确认计划受影响', n ? 'error' : 'success');
    loadTideTable(); loadDisruptions();
  } catch (e) { showError(e); }
}
async function submitFault() {
  try {
    const r = await api('/api/simulation/crane-faults', {
      method: 'POST',
      body: {
        craneId: Number($('#fault-crane').value),
        startAt: $('#fault-start').value, endAt: $('#fault-end').value,
        reason: $('#fault-reason').value,
      },
    });
    toast(`⚠️ 故障已插入，影响项 ${(r.impacts || []).length} 条`, 'error');
    loadDisruptions();
  } catch (e) { showError(e); }
}
async function loadDisruptions() {
  const el = $('#disruption-list');
  if (!el) return;
  try {
    const list = await api('/api/disruptions');
    if (!list.length) { el.innerHTML = '<p class="hint">暂无扰动记录。</p>'; return; }
    el.innerHTML = list.slice(0, 12).map((d) => `
      <div class="work-card"><div class="wc-title">#${d.id} ${esc(d.dtype)}</div>
        <div class="wc-meta">${esc(d.summary || '')}<br/><span class="mono">${fmtFull(d.created_at)}</span></div>
        <button class="btn tiny" onclick="showDisruption(${d.id})">查看影响项</button>
        <div id="disp-${d.id}" class="hidden"></div>
      </div>`).join('');
  } catch { /* ignore */ }
}
async function showDisruption(id) {
  const box = $('#disp-' + id);
  if (!box.classList.contains('hidden')) { box.classList.add('hidden'); return; }
  const d = await api('/api/disruptions/' + id);
  box.classList.remove('hidden');
  box.innerHTML = (d.impacts || []).map((i) =>
    `<div class="reason" style="color:#8c2b25;margin-top:6px"><b>[${esc(i.code)}]</b> ${esc(i.message)}</div>`).join('')
    || '<p class="hint">无影响项</p>';
}
async function resetScenario() {
  if (!confirm('重置全部数据为题目固定样例？')) return;
  try { await api('/api/admin/reset', { method: 'POST' }); toast('✅ 样例数据已重置', 'success'); location.reload(); }
  catch (e) { showError(e); }
}

/* ---------------- 回放渲染 ---------------- */
async function runReplay(kind) {
  const btn = $('#btn-' + kind);
  const kinds = ['orderings', 'disruptions', 'power', 'full'];
  kinds.forEach((k) => { const b = $('#btn-' + k); if (b) b.disabled = true; });
  $('#replay-summary').innerHTML = '<div class="verdict ok">⏳ 回放执行中，包含真实 TTL 等待（约 10 秒）与并发竞速…</div>';
  $('#replay-result').innerHTML = '';
  try {
    const path = kind === 'full' ? '/api/replays/full' : '/api/replays/' + kind;
    const data = await api(path, { method: 'POST' });
    state.lastReplay = data;
    renderReplay(data, kind);
  } catch (e) { showError(e); $('#replay-summary').innerHTML = '<div class="verdict bad">回放失败：' + esc(e.message) + '</div>'; }
  finally { kinds.forEach((k) => { const b = $('#btn-' + k); if (b) b.disabled = false; }); }
}

function renderReplay(data, kind) {
  const pass = data.allPassed;
  $('#replay-summary').innerHTML = `<div class="verdict ${pass ? 'ok' : 'bad'}">
    ${pass ? '✅ 全部断言通过（ALL CHECKS PASSED）' : '❌ 存在未通过断言'}　<button class="btn tiny" onclick="downloadReplay()">下载完整 JSON</button></div>`;

  // /api/replays/power 直接返回一个块
  if (kind === 'power' || (Array.isArray(data.checks) && !data.orderings && !data.disruptions)) {
    $('#replay-result').innerHTML = renderReplayBlock('power', data);
    return;
  }

  let html = '';
  const renderBlock = renderReplayBlock;

  if (data.orderings) {
    for (const [k, v] of Object.entries(data.orderings)) if (k !== 'allPassed') html += renderBlock(k, v);
  }
  if (data.disruptions) {
    for (const [k, v] of Object.entries(data.disruptions)) if (k !== 'allPassed') html += renderBlock(k, v);
  }
  for (const [k, v] of Object.entries(data)) {
    if (['orderings', 'disruptions', 'allPassed'].includes(k)) continue;
    html += renderBlock(k, v);
  }
  $('#replay-result').innerHTML = html;
}

function renderReplayBlock(key, b) {
  if (!b || !b.checks) return '';
  return `<div class="replay-block">
    <div class="rb-head" onclick="this.nextElementSibling.classList.toggle('hidden')">
      <h3>${b.allPassed ? '✅' : '❌'} ${esc(b.title || key)}</h3><span class="badge ${b.allPassed ? 'CONFIRMED' : 'WITHDRAWN'}">${b.checks.filter((c) => c.pass).length}/${b.checks.length}</span>
    </div>
    <div class="rb-body">
      ${b.checks.map((c) => `<div class="check-row ${c.pass ? 'pass' : 'fail'}">
        <span class="check-icon">${c.pass ? '✓' : '✗'}</span>
        <div><div>${esc(c.name)}</div>
        ${!c.pass ? `<div class="check-detail">期望：${esc(JSON.stringify(c.expected))}<br/>实际：${esc(JSON.stringify(c.actual))}</div>` : ''}
        </div></div>`).join('')}
      <details class="raw"><summary>查看该场景完整 JSON（候选原因 / 时间点 / 资源占用 / 回执自证）</summary>
        <pre class="json">${esc(JSON.stringify(b, null, 2))}</pre></details>
    </div></div>`;
}
function downloadReplay() {
  const blob = new Blob([JSON.stringify(state.lastReplay, null, 2)], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = 'replay-result.json'; a.click();
}

/* ---------------- feature-4 供电调度 ---------------- */
let pwDetail = null;
const PW_STRATEGY_LABEL = {
  CONFIRMATION_ORDER: '顺序一 · 按确认时间（保护既有承诺）',
  CARGO_SAFETY: '顺序二 · 按货物最低安全负荷（货运安全优先）',
  FULL_RESTORE: '告警撤销 · 全额恢复新方案',
};
const PW_RISK_LABEL = { CRITICAL: '严重·必须转移冷藏箱', WARNING: '警告·计划削减/顺延', OK: '正常' };
const mw = (kw) => (Number(kw || 0) / 1000).toFixed(2);
function hm(iso) { return iso ? String(iso).replace('T', ' ').slice(11, 16) : ''; }

async function loadPowerOverview() {
  try {
    state.power = await api('/api/power/overview');
    // 每次进入都与服务端核对当前查看的告警，避免回放/重置后残留旧 id
    if (pwDetail && pwDetail.alarm) {
      try { pwDetail = await api('/api/power/alarms/' + pwDetail.alarm.id); }
      catch { pwDetail = state.power.activeAlarm || null; }
    } else if (state.power.activeAlarm) {
      pwDetail = state.power.activeAlarm;
    }
    renderPowerAll();
  } catch (e) {
    const root = $('#pw-baseline');
    if (root) root.innerHTML = '<div class="verdict bad">供电数据加载失败：' + esc(e.message) + '</div>';
  }
}

function renderPowerAll() {
  renderPwFeeder();
  renderPwReceipts();
  renderPwMain();
}

function renderPwFeeder() {
  const p = state.power;
  if (!p || !p.feeder) return;
  const f = p.feeder;
  $('#pw-feeder-code').textContent = f.code;
  const derated = f.status === 'DERATED';
  $('#pw-feeder-status').innerHTML = `
    <span class="pw-stat ${derated ? 'alarm' : 'ok'}">
      馈线状态：<b>${derated ? '⚠ 临时降容运行' : '✅ 正常'}</b></span>
    <span class="pw-stat">正常额定：<b>${mw(f.normalCapacityKw)}MW</b></span>
    <span class="pw-stat ${derated ? 'alarm' : ''}">当前安全上限：<b>${mw(f.safetyCapacityKw)}MW</b></span>
    <span class="pw-stat">在泊受电船：<b>${(p.accounts || []).length}</b></span>`;
  $('#pw-raise-btn').classList.toggle('hidden', !!pwDetail && pwDetail.alarm.status === 'ACTIVE');
  $('#pw-revoke-btn').classList.toggle('hidden', !(pwDetail && pwDetail.alarm.status === 'ACTIVE'));
}

function renderPwMain() {
  const base = $('#pw-baseline');
  const cmp = $('#pw-compare');
  const banner = $('#pw-active-banner');
  if (!state.power) return;

  if (!pwDetail) {
    banner.innerHTML = '';
    cmp.innerHTML = '';
    base.innerHTML = baselineHtml(state.power);
    return;
  }
  base.innerHTML = '';
  const d = pwDetail;
  const a = d.alarm;
  const revoked = a.status === 'REVOKED';
  banner.innerHTML = revoked ? `
    <div class="verdict ok pw-banner">✅ 告警 <b>${esc(a.alarmNo)}</b> 已于 ${fmtFull(a.revokedAt)} 撤销：
      ${esc(a.revokeReason || '')}。已生成 FULL_RESTORE 新方案与新回执；<b>下方保留告警期间的两套旧方案，已签发回执逐字未改</b>。</div>`
    : `
    <div class="verdict bad pw-banner">⚡ 告警 <b>${esc(a.alarmNo)}</b> 生效中：${esc(a.reason || '')}
      （录入 ${fmtFull(a.alarmAt)}，窗口 ${fmtDT(a.windowStart)}–${fmtDT(a.windowEnd)}，${a.slotMinutes} 分钟/档，上限 <b>${mw(a.deratedCapacityKw)}MW</b>）</div>`;

  const plans = d.plans || [];
  const conf = plans.find((x) => x.strategy === 'CONFIRMATION_ORDER');
  const safe = plans.find((x) => x.strategy === 'CARGO_SAFETY');
  const restore = plans.find((x) => x.strategy === 'FULL_RESTORE');
  const trap = (safe && safe.naiveTrap) || (conf && conf.naiveTrap) || state.power.naiveDeratingTrapPreview;

  cmp.innerHTML = `
    ${trap ? trapHtml(trap) : ''}
    <div class="pw-plan-grid">
      ${conf ? powerPlanCard(conf, d.accounts) : ''}
      ${safe ? powerPlanCard(safe, d.accounts, true) : ''}
    </div>
    ${restore ? `<div class="pw-restore-wrap">${powerPlanCard(restore, d.accounts, true)}</div>` : ''}`;
}

function baselineHtml(p) {
  const rows = (p.accounts || []).map((a) => `
    <tr>
      <td><b>${esc(a.code)}</b><br/><span class="hint">${esc(a.berthCode || '')} 泊位</span></td>
      <td>${esc(a.shipName)}<br/><span class="hint">${esc(a.cargoDetail || '')}</span></td>
      <td>${fmtFull(a.confirmedAt)}</td>
      <td class="num"><b>${mw(a.demandedKw)}</b></td>
      <td class="num ${a.safetyFloorKw > 0 ? 'danger-text' : ''}"><b>${mw(a.safetyFloorKw)}</b>${a.continuous ? ' 连续' : ''}</td>
      <td>${(a.commitments || []).map((c) => `<span class="res-chip">${esc(c.operationName)} · ${mw(c.requiredKw)}MW${c.flexible ? ' · 可错峰' : ''}</span>`).join(' ')}</td>
    </tr>`).join('');
  const trap = p.naiveDeratingTrapPreview;
  return `
    <div class="card">
      <h2>在泊受电船舶与已确认用电承诺（告警前基线）</h2>
      <table class="data"><thead><tr>
        <th>账户</th><th>船舶 / 货物</th><th>承诺确认时间</th><th>需量 MW</th><th>安全下限 MW</th><th>已确认用电作业</th>
      </tr></thead><tbody>${rows}</tbody></table>
      <p class="hint" style="margin-top:8px">确认顺序刻意为：普通船 PA-01（18:05）→ 普通船 PA-02（18:25）→ 冷藏船 PA-03（18:40，最晚）。
        点击右上角“输入馈线告警并出方案”，系统会同时计算两种顺序并核对馈线总量。</p>
    </div>
    ${trap ? `<div class="card">${trapHtml(trap)}</div>` : ''}`;
}

function trapHtml(t) {
  const alloc = (t.allocationsKw || []).map(mw).join(' + ');
  return `<div class="pw-trap">
    <b>⚠ 不能用“逐船独立降额”代替馈线总量核验：</b>
    若简单规定每船独立降到 ≤${mw(t.perShipCapKw)}MW，得到 ${alloc} =
    <b class="${t.feederTotalPass ? 'ok-text' : 'danger-text'}">${mw(t.totalKw)}MW</b>，
    馈线上限 ${mw(t.feederLimitKw)}MW ——
    <b class="${t.feederTotalPass ? 'ok-text' : 'danger-text'}">${t.feederTotalPass ? '不超限' : '仍超载 ' + mw(t.totalKw - t.feederLimitKw) + 'MW'}</b>。
    ${esc(t.lesson || '')}
  </div>`;
}

function powerPlanCard(plan, accounts, recommended) {
  const accById = Object.fromEntries((accounts || []).map((a) => [a.id, a]));
  const slots = plan.slotChecks || [];
  const slotCount = plan.slotCount || slots.length || 1;
  const alloc = plan.allocations || [];
  const cell = (accId, s) => alloc.find((x) => Number(x.accountId) === Number(accId) && x.slotIndex === s) || {};
  const isRestore = plan.strategy === 'FULL_RESTORE';

  const headCols = Array.from({ length: slotCount }, (_, s) => {
    const a0 = alloc.find((x) => x.slotIndex === s);
    const label = a0 ? `${hm(a0.slotStart)}<br/><span class="hint">–${hm(a0.slotEnd)}</span>` : `档${s + 1}`;
    return `<th class="num pw-slot-head">${label}</th>`;
  }).join('');

  const accRows = (accounts || []).map((a) => {
    let minV = Infinity;
    const tds = Array.from({ length: slotCount }, (_, s) => {
      const c = cell(a.id, s);
      const v = c.allocatedKw == null ? null : Number(c.allocatedKw);
      if (v != null) minV = Math.min(minV, v);
      const cut = Number(c.curtailedKw || 0);
      const viol = c.safetyViolation === true;
      const full = v != null && cut === 0;
      return `<td class="num pw-alloc-cell ${viol ? 'violation' : cut > 0 ? 'cut' : full ? 'full' : ''}">
        <b>${v == null ? '-' : mw(v)}</b>
        ${cut > 0 ? `<div class="pw-cut">▼${mw(cut)}</div>` : ''}
      </td>`;
    }).join('');
    const minLabel = minV === Infinity ? '-' : mw(minV);
    return `<tr>
      <td><b>${esc(a.code)}</b> ${recommended && a.cargoClass === 'REEFER' ? '<span class="pw-reefer-tag">冷藏</span>' : (a.cargoClass === 'REEFER' ? '<span class="pw-reefer-tag">冷藏</span>' : '')}<br/>
        <span class="hint">${esc(a.shipName)}</span></td>
      <td class="num">${mw(a.demandedKw)}</td>
      <td class="num ${a.safetyFloorKw > 0 ? 'danger-text' : ''}">${a.safetyFloorKw > 0 ? mw(a.safetyFloorKw) : '—'}</td>
      ${tds}
      <td class="num"><b class="${minV < a.safetyFloorKw ? 'danger-text' : 'ok-text'}">${minLabel}</b></td>
    </tr>`;
  }).join('');

  const totalRow = Array.from({ length: slotCount }, (_, s) => {
    const c = slots.find((x) => x.slot === s) || {};
    const ok = c.withinLimit !== false;
    return `<td class="num pw-total-cell ${ok ? 'ok' : 'bad'}">
      <b>${mw(c.totalAllocatedKw)}</b><div class="hint">/ ${mw(plan.capacityLimitKw)}</div></td>`;
  }).join('');

  const impacts = (plan.cargoImpacts || []).map((i) => `
    <div class="pw-impact ${(i.riskLevel || '').toLowerCase()}">
      <span class="pw-risk-dot"></span>
      <div><b>[${PW_RISK_LABEL[i.riskLevel] || i.riskLevel}]</b> ${esc(i.message || '')}</div>
    </div>`).join('');

  const cuts = plan.curtailments || [];
  const cutRows = cuts.map((c) => `
    <tr class="${c.basisCode === 'UNSAFE_SAFETY_FLOOR_BREACH' ? 'unsafe-row' : ''}">
      <td class="mono">${fmtDT(c.slotStart)}<br/><span class="hint">–${fmtDT(c.slotEnd)}</span></td>
      <td>${esc(accById[c.accountId] ? accById[c.accountId].shipName : c.accountId)}</td>
      <td>${esc(c.operationName || '')} ${c.commitmentNo ? `<span class="hint">(${esc(c.commitmentNo)})</span>` : ''}</td>
      <td class="num">${mw(c.curtailedKw)}</td>
      <td class="num">${c.durationMin}′</td>
      <td class="num">${Number(c.curtailedEnergyMwh || 0).toFixed(3)}</td>
      <td class="num">${c.compensationRatePerMwh || 0}</td>
      <td class="num"><b>${Number(c.compensationAmountCny || 0).toFixed(2)}</b></td>
    </tr>
    <tr class="reason-row"><td colspan="8" class="hint">${esc(c.reason || '')}</td></tr>`).join('');

  const restoreSteps = (plan.restoration || []).map((r) => `
    <li class="pw-restore-step ${r.withinAlarmLimit ? '' : 'need-restore'}">
      <div class="pw-step-seq">${r.seq}</div>
      <div class="pw-step-body">
        <b>${esc(r.shipName)}</b>
        <span class="pw-prio">${r.priority === 'CARGO_SAFETY_FIRST' ? '货物安全优先' : r.priority === 'THEN_CONFIRMATION_TIME' ? '其后按确认时间' : '按确认时间'}</span>
        <div class="hint">${esc(r.note || '')}</div>
        <div class="pw-cum">本步 +${mw(r.stepLoadKw)}MW · 累计 <b>${mw(r.cumulativeKw)}MW</b>
          · 降容上限内：${r.withinAlarmLimit ? '✅' : '⛔（需撤销告警/容量恢复）'} · 正常容量内：${r.withinNormalLimit ? '✅' : '⛔'}</div>
      </div>
    </li>`).join('');

  return `
  <div class="card pw-plan ${recommended ? 'recommended' : 'contrast'} ${isRestore ? 'restore-plan' : ''}">
    <div class="row-between wrap">
      <h2>${recommended && !isRestore ? '✅ ' : isRestore ? '🔌 ' : '📋 '}${esc(PW_STRATEGY_LABEL[plan.strategy] || plan.strategy)}
        ${recommended && !isRestore ? '<span class="pw-rec-tag">推荐下达</span>' : ''}</h2>
      <div class="row gap">
        <span class="pw-badge ${plan.feederTotalCheckPass ? 'pass' : 'fail'}">馈线总量核验 ${plan.feederTotalCheckPass ? '通过' : '未过'}</span>
        <span class="pw-badge ${plan.safetyFeasible ? 'pass' : 'fail'}">货物安全 ${plan.safetyFeasible ? '可保障' : '失守'}</span>
        ${plan.issued ? '<span class="pw-badge issued">已下达回执</span>' : ''}
      </div>
    </div>
    <p class="pw-note">${esc(plan.comparisonNote || '')}</p>

    <h3 class="section-title">分时分配表（MW；▼ 为相对需求的削减）与馈线总量核验</h3>
    <div class="pw-table-scroll">
    <table class="data pw-alloc-table">
      <thead><tr><th>船舶</th><th>需求</th><th>安全下限</th>${headCols}<th>全窗口最低</th></tr></thead>
      <tbody>${accRows}
        <tr class="pw-total-row"><td><b>馈线合计 / 上限</b></td><td class="num">${mw(plan.totalDemandKw)}</td><td></td>
          ${totalRow}<td class="num">峰值 ${mw(plan.peakTotalKw)}</td></tr>
      </tbody>
    </table>
    </div>

    <h3 class="section-title">受影响货物</h3>
    <div class="pw-impacts">${impacts || '<p class="hint">无</p>'}</div>

    <h3 class="section-title">被削减的已确认作业 与 补偿依据
      ${cuts.length ? `<span class="hint">（削减电量合计 ${plan.curtailedEnergyMwhTotal}MWh，补偿合计 <b class="danger-text">¥${plan.compensationTotalCny}</b>）</span>` : ''}</h3>
    ${cuts.length ? `
    <div class="pw-table-scroll">
    <table class="data pw-cut-table">
      <thead><tr><th>时段</th><th>船舶</th><th>被改动的已确认作业</th><th>削减kW</th><th>时长</th><th>电量MWh</th><th>元/MWh</th><th>补偿元</th></tr></thead>
      <tbody>${cutRows}</tbody>
    </table></div>` : '<p class="hint">本方案无削减。</p>'}

    <h3 class="section-title">恢复次序</h3>
    <ol class="pw-restore-list">${restoreSteps}</ol>

    <div class="cand-actions">
      ${plan.issued
        ? '<span class="pw-badge issued">🧾 已签发不可变回执（见底部台账）</span>'
        : `<button class="btn primary tiny" onclick="powerIssue(${plan.id})">对该方案下达并生成回执</button>`}
      <button class="btn tiny" onclick="togglePwRaw(${plan.id})">原始 JSON</button>
    </div>
    <pre class="json hidden" id="pw-raw-${plan.id}">${esc(JSON.stringify(plan, null, 2))}</pre>
  </div>`;
}

function renderPwReceipts() {
  const el = $('#pw-receipts');
  if (!el || !state.power) return;
  const rs = state.power.receipts || [];
  if (!rs.length) { el.innerHTML = '<p class="hint">暂无回执。下达方案后在此形成只增不改的回执台账。</p>'; return; }
  el.innerHTML = `<table class="data"><thead><tr>
      <th>回执号</th><th>签发时间</th><th>馈线</th><th>方案/阶段</th><th>上限</th><th>签发人</th><th>备注</th><th></th>
    </tr></thead><tbody>
    ${rs.map((r) => `
      <tr>
        <td class="mono"><b>${esc(r.receiptNo)}</b></td>
        <td class="mono">${fmtFull(r.issuedAt)}</td>
        <td>${esc(r.feederCode || '')}</td>
        <td>${esc(PW_STRATEGY_LABEL[r.strategy] || r.strategy)}<br/><span class="hint">${esc(r.phase || '')} · 方案 #${r.planId}</span></td>
        <td class="num">${mw(r.capacityLimitKw)}MW</td>
        <td>${esc(r.issuedBy || '')}</td>
        <td>${esc(r.note || '')}</td>
        <td><button class="btn tiny" onclick="toggleReceiptSnapshot(${r.id}, this)">查看快照</button></td>
      </tr>
      <tr class="pw-snap-row hidden" id="rcp-${r.id}"><td colspan="8"><pre class="json">加载中…</pre></td></tr>`).join('')}
    </tbody></table>
    <p class="hint" style="margin-top:6px">数据库触发器 <code>trg_power_receipt_immutable</code> 禁止 UPDATE/DELETE；回放中含直接改库/删库被拒的自证。</p>`;
}

async function toggleReceiptSnapshot(id, btn) {
  const tr = $('#rcp-' + id);
  if (!tr.classList.contains('hidden')) { tr.classList.add('hidden'); return; }
  tr.classList.remove('hidden');
  try {
    const r = await api('/api/power/receipts/' + id);
    tr.querySelector('pre').textContent = JSON.stringify(r.snapshot || r, null, 2);
  } catch (e) { tr.querySelector('pre').textContent = '加载失败：' + e.message; }
}

function togglePwRaw(id) { $('#pw-raw-' + id).classList.toggle('hidden'); }

async function powerRaiseAlarm() {
  const body = {
    deratedCapacityMw: Number($('#pw-derated').value) || 1.2,
    slotMinutes: Number($('#pw-slot').value) || 30,
  };
  try {
    toast('⏳ 正在按两种顺序计算分时分配并做馈线总量核验…');
    pwDetail = await api('/api/power/alarms', { method: 'POST', body });
    await loadPowerOverview();
    toast('✅ 已生成两种顺序对照方案，并自动下达推荐的货物安全方案回执', 'success');
  } catch (e) { showError(e); }
}

async function powerRevoke() {
  if (!pwDetail) return;
  if (!confirm('确认告警撤销、馈线恢复正常容量？将生成全额恢复新方案与新回执，旧方案/回执保持不变。')) return;
  try {
    pwDetail = await api(`/api/power/alarms/${pwDetail.alarm.id}/revoke`,
      { method: 'POST', body: { reason: '值班电工确认现场恢复，撤销降容告警' } });
    await loadPowerOverview();
    toast('✅ 告警已撤销，已生成 FULL_RESTORE 新方案与新回执；旧回执未改动', 'success');
  } catch (e) { showError(e); }
}

async function powerIssue(planId) {
  try {
    await api(`/api/power/plans/${planId}/issue`,
      { method: 'POST', body: { note: '值班电工核对馈线总量后下达' } });
    toast('🧾 已签发不可变回执', 'success');
    pwDetail = await api('/api/power/alarms/' + pwDetail.alarm.id);
    await loadPowerOverview();
  } catch (e) { showError(e); }
}

boot();
