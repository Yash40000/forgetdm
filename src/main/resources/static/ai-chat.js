/* ForgeTDM Copilot — chat widget. Reuses globals from app.js: api, esc, $ (loaded first). */
const aiState = { open: false, ready: false, enabled: false, busy: false, hint: '', history: [], pending: null, providers: [], provider: '', model: '' };

/* ---------------- Agentic provisioning (Auto-Provision page) ---------------- */
const agentState = { run: null, busy: false };
const AGENT_BADGE = { PENDING: 'dim', RUNNING: 'info', AWAITING_APPROVAL: 'warn', DONE: 'good', SKIPPED: 'dim', FAILED: 'bad' };

async function loadAgent() {
  agentRender();
  await loadAgentHistory();
}

async function agentPlan() {
  const goal = ($('agent-goal')?.value || '').trim();
  if (!goal) return toast('Describe what you want to provision', 'err');
  agentState.busy = true; $('agent-run').innerHTML = '<div class="card"><div class="loading">Planning…</div></div>';
  try {
    agentState.run = await api.post('/api/agent/plan', { goal, provider: aiState.provider || null, model: aiState.model || null });
    agentRender();
  } catch (e) { $('agent-run').innerHTML = `<div class="card"><div class="error">${esc(e.message)}</div></div>`; toast(e.message, 'err'); }
  agentState.busy = false;
}

async function agentCall(path) {
  if (!agentState.run || agentState.busy) return;
  agentState.busy = true; agentRender();
  try { agentState.run = await api.post(`/api/agent/runs/${agentState.run.id}/${path}`); }
  catch (e) { toast(e.message, 'err'); }
  agentState.busy = false; agentRender();
  if (path === 'next' || path === 'approve' || path === 'reject') loadAgentHistory();
}
const agentNext = () => agentCall('next');
const agentApprove = () => agentCall('approve');
const agentReject = () => agentCall('reject');
const agentCancel = () => agentCall('cancel');

async function agentRunAll() {
  if (!agentState.run) return;
  // auto-run read steps until something needs approval (or it finishes)
  let guard = 0;
  while (agentState.run.status === 'READY' && guard++ < 12) {
    await agentCall('next');
    if (agentState.run.status !== 'READY') break;
  }
}

function agentRender() {
  const box = $('agent-run'); if (!box) return;
  const run = agentState.run;
  if (!run) { box.innerHTML = ''; return; }
  const steps = run.steps || [];
  const stepHtml = steps.map(s => {
    const badge = `<span class="pill ${AGENT_BADGE[s.status] || 'dim'}">${esc((s.status || '').replace('_', ' '))}</span>`;
    let action = '';
    if (s.status === 'AWAITING_APPROVAL') {
      action = `<div class="agent-action">
        <div class="t">Needs your approval — run <code>${esc(s.actionName || '')}</code>?</div>
        ${s.actionSummary ? `<p class="sub tight">${esc(s.actionSummary)}</p>` : ''}
        <pre>${esc(JSON.stringify(s.actionArgs || {}, null, 2))}</pre>
        <div class="agent-btns"><button class="run" onclick="agentApprove()">Approve &amp; run</button>
        <button class="ghost small" onclick="agentReject()">Skip</button></div>
      </div>`;
    }
    return `<div class="agent-step ${s.status === 'DONE' ? 'done' : ''}">
      <div class="agent-step-head"><span class="n">${s.ord}</span><b>${esc(s.title)}</b>${badge}</div>
      ${s.detail ? `<p class="sub tight">${esc(s.detail)}</p>` : ''}
      ${s.result ? `<div class="agent-result">${aiFmt(s.result)}</div>` : ''}
      ${action}
    </div>`;
  }).join('');

  let controls = '';
  if (agentState.busy) controls = `<span class="loading">Working — running the step (a local model can take a while)…</span>`;
  else if (run.status === 'READY') controls = `<button onclick="agentNext()">Run next step</button><button class="ghost" onclick="agentRunAll()">Run read steps</button>`;
  else if (run.status === 'RUNNING') controls = `<span class="loading">Working…</span>`;
  else if (run.status === 'AWAITING_APPROVAL') controls = `<span class="sub">Approve or skip the highlighted step above.</span>`;
  else if (run.status === 'DONE') controls = `<span class="pill good">Completed</span>`;
  else if (run.status === 'FAILED') controls = `<span class="pill bad">Failed</span>`;
  else if (run.status === 'CANCELED') controls = `<span class="pill dim">Canceled</span>`;

  box.innerHTML = `<div class="card">
    <div class="row push"><h3>Plan — ${esc(run.goal)}</h3><span class="pill ${AGENT_BADGE[run.status] || 'dim'}">${esc((run.status || '').replace('_', ' '))}</span></div>
    ${run.summary ? `<p class="sub tight">${esc(run.summary)}</p>` : ''}
    <div class="agent-steps">${stepHtml}</div>
    <div class="row push" style="margin-top:14px">${controls}<span class="grow"></span>
      ${['DONE', 'CANCELED', 'FAILED'].includes(run.status) ? '' : `<button class="ghost small" onclick="agentCancel()">Cancel run</button>`}</div>
  </div>`;
}

async function loadAgentHistory() {
  const el = $('agent-history'); if (!el) return;
  try {
    const runs = await api.get('/api/agent/runs');
    el.innerHTML = runs.length ? `<table><tr><th>#</th><th>Goal</th><th>Steps</th><th>Status</th><th></th></tr>` +
      runs.map(r => `<tr><td>${r.id}</td><td>${esc(r.goal)}</td><td>${(r.steps || []).length}</td>
        <td><span class="pill ${AGENT_BADGE[r.status] || 'dim'}">${esc((r.status || '').replace('_', ' '))}</span></td>
        <td><button class="small ghost" onclick="agentOpen(${r.id})">Open</button></td></tr>`).join('') + '</table>'
      : '<div class="empty">No runs yet.</div>';
  } catch (e) { /* AI may be off */ }
}

async function agentOpen(id) {
  try { agentState.run = await api.get('/api/agent/runs/' + id); agentRender(); window.scrollTo(0, 0); }
  catch (e) { toast(e.message, 'err'); }
}

function aiToggle() {
  const panel = document.getElementById('ai-panel');
  aiState.open = !panel.classList.toggle('hidden');
  if (aiState.open) {
    if (!aiState.ready) aiInit();
    setTimeout(() => document.getElementById('ai-text')?.focus(), 60);
  }
}

async function aiInit() {
  aiState.ready = true;
  try {
    const s = await api.get('/api/ai/status');
    aiState.enabled = !!s.enabled;
    aiState.providers = s.providers || [];
    const saved = localStorage.getItem('forgetdm.ai.provider');
    const ids = aiState.providers.map(p => p.id);
    aiState.provider = (saved && ids.includes(saved)) ? saved : (s.default && ids.includes(s.default) ? s.default : (ids[0] || ''));
    aiBuildProviderPicker();
  } catch (e) { aiState.enabled = false; }
  aiState.hint = aiState.enabled
    ? "Hi — I'm the ForgeTDM Copilot. Ask about your data sources, masking, subsetting or synthetic data — or tell me what you'd like to run, and I'll set it up for your approval."
    : "No AI provider is configured yet. Add a free one to test: **Groq** (free tier — set GROQ_API_KEY) or **local Ollama** (set OLLAMA_API_KEY=ollama and run a model), or set **FORGETDM_AI_API_KEY** for OpenAI. See AI_COPILOT_SETUP.txt, then restart.";
  aiRender();
}

function aiBuildProviderPicker() {
  const sub = document.getElementById('ai-sub');
  const sel = document.getElementById('ai-provider');
  const mod = document.getElementById('ai-model-input');
  if (!sub || !sel) return;
  if (!aiState.providers.length) { sub.classList.add('hidden'); return; }
  sub.classList.remove('hidden');
  sel.innerHTML = aiState.providers
    .map(p => `<option value="${esc(p.id)}"${p.id === aiState.provider ? ' selected' : ''} title="${p.local ? 'On-prem endpoint — nothing leaves your network' : 'Remote API provider'}">${esc(p.label || p.id)}${p.local ? ' 🔒' : ''}</option>`)
    .join('');
  const cur = aiState.providers.find(p => p.id === aiState.provider) || aiState.providers[0];
  aiState.model = cur ? (cur.model || '') : '';
  if (mod) mod.value = aiState.model;
}

function aiPickProvider(id) {
  aiState.provider = id;
  localStorage.setItem('forgetdm.ai.provider', id);
  const cur = aiState.providers.find(p => p.id === id);
  aiState.model = cur ? (cur.model || '') : '';
  const mod = document.getElementById('ai-model-input');
  if (mod) mod.value = aiState.model;
}

function aiPickModel(v) { aiState.model = (v || '').trim(); }

function aiKey(e) { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); aiSend(); } }

async function aiSend() {
  const ta = document.getElementById('ai-text');
  const text = (ta.value || '').trim();
  if (!text || aiState.busy) return;
  ta.value = '';
  aiState.history.push({ role: 'user', content: text });
  aiState.pending = null;
  aiState.busy = true; aiRender();
  try {
    const r = await api.post('/api/ai/chat', { messages: aiState.history, provider: aiState.provider, model: aiState.model });
    aiHandle(r);
  } catch (e) { aiState.history.push({ role: 'assistant', content: '⚠ ' + e.message }); }
  aiState.busy = false; aiRender();
}

function aiHandle(r) {
  if (r && r.type === 'action') {
    aiState.history.push({ role: 'assistant', content: r.summary || 'I have an action to propose.' });
    aiState.pending = { name: r.name, arguments: r.arguments || {} };
  } else {
    aiState.history.push({ role: 'assistant', content: (r && r.content) || '(no response)' });
    aiState.pending = null;
  }
}

async function aiConfirm() {
  if (!aiState.pending || aiState.busy) return;
  const act = aiState.pending;
  aiState.pending = null; aiState.busy = true; aiRender();
  try {
    const r = await api.post('/api/ai/act', { name: act.name, arguments: act.arguments, messages: aiState.history, provider: aiState.provider, model: aiState.model });
    aiState.history.push({ role: 'assistant', content: (r && r.content) || 'Done.' });
  } catch (e) { aiState.history.push({ role: 'assistant', content: '⚠ ' + e.message }); }
  aiState.busy = false; aiRender();
}

function aiCancel() {
  aiState.pending = null;
  aiState.history.push({ role: 'assistant', content: 'Okay — cancelled. Nothing was run.' });
  aiRender();
}

function aiFmt(s) {
  return esc(s || '')
    .replace(/\*\*([^*]+)\*\*/g, '<b>$1</b>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\n/g, '<br>');
}

function aiRender() {
  const box = document.getElementById('ai-msgs');
  if (!box) return;
  let html = '';
  if (aiState.hint) html += `<div class="ai-hint">${aiFmt(aiState.hint)}</div>`;
  for (const m of aiState.history)
    html += `<div class="ai-msg ${m.role === 'user' ? 'user' : 'bot'}">${aiFmt(m.content)}</div>`;
  if (aiState.pending) {
    const a = aiState.pending;
    html += `<div class="ai-act">
      <div class="t">Action — needs your OK</div>
      <div class="nm">Run <code>${esc(a.name)}</code>?</div>
      <pre>${esc(JSON.stringify(a.arguments, null, 2))}</pre>
      <div class="btns">
        <button class="run" onclick="aiConfirm()">Run it</button>
        <button class="cancel" onclick="aiCancel()">Cancel</button>
      </div></div>`;
  }
  if (aiState.busy) html += `<div class="ai-typing">Copilot is thinking…</div>`;
  box.innerHTML = html;
  box.scrollTop = box.scrollHeight;
  const send = document.getElementById('ai-send');
  if (send) send.disabled = aiState.busy;
}
