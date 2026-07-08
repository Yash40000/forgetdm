/* Synthetic Data — guided 3-step wizard (overrides the legacy DOM-driven builder in app.js).
   Reuses globals from app.js: api, esc, $, toast, refreshShared, dataSources, loadSchemas, loadTables,
   loadGeneratorCatalog, GENERATORS, genOptions, SYN_DATASETS, suggestGeneratorForColumn,
   defaultParamForGenerator, defaultParam2ForGenerator, synSqlTypeForGenerator, sqlTypeFromDbType,
   synLastFiles, synDownload, synDownloadIdx. */

let synState = {
  step: 1,
  dataset: 'customer360', seed: 42, receiver: 'DB',
  targetDsId: '', targetSchema: '', createTable: false, dropTable: false, prepMode: 'APPEND',
  loadAction: 'INSERT', targetPrep: 'NONE', keyColumns: '', batchSize: '',
  commitEveryRows: '', continueOnError: false, maxRejects: '',
  executionMode: 'SINGLE', partitionCount: '', partitionSize: '',
  targetSystems: [],
  srcDsId: '', srcSchema: '',
  autoBlueprint: false,
  tables: []
};
let synJobPollTimer = null;
let synSavedJobs = [];
let synPendingRunJob = null;

function synMakeCol(name, gen, p1, p2, pk, fk) {
  let fkTable = '', fkColumn = '';
  if (fk && fk.includes('.')) { const i = fk.indexOf('.'); fkTable = fk.slice(0, i); fkColumn = fk.slice(i + 1); }
  return {
    name: name || '', generator: gen || 'SEQUENCE', param1: p1 || '', param2: p2 || '',
    primaryKey: !!pk, fkTable, fkColumn, sqlType: synSqlTypeForGenerator(gen), open: false, typeLocked: false
  };
}

function synGoalProgressHtml(percent, message, state = '', stages = []) {
  const pct = Math.max(0, Math.min(100, Math.round(percent || 0)));
  const runner = Math.max(56, Math.min(676, pct * 6.2 + 56));
  const cls = state ? ` ${state}` : '';
  const active = synStageForProgress(stages, pct).index;
  const stageHtml = stages.length ? `<div class="syn-stage-row">${stages.map((s, i) =>
    `<span class="syn-stage ${i < active ? 'done' : i === active ? 'active' : ''}">${esc(s.label)}</span>`).join('')}</div>` : '';
  const ballHtml = state === 'done'
    ? `<g transform="translate(${runner + 62} 137)"><g class="syn-ball-svg syn-ball-shot"><circle r="8" fill="#fff" stroke="#111827" stroke-width="2"/><path d="M-5,-1 L0,-6 L6,-1 L4,6 L-4,6 Z" fill="#111827" opacity=".9"/></g></g>`
    : '';
  return `<div class="syn-goal-progress${cls}">
    <div class="syn-goal-head"><b>${esc(message || 'Generating synthetic data')}</b><span>${pct}%</span></div>
    ${stageHtml}
    <div class="syn-pitch" role="progressbar" aria-label="${esc(message || 'Generating synthetic data')}" aria-valuemin="0" aria-valuemax="100" aria-valuenow="${pct}">
      <div class="syn-progress-fill" style="width:${pct}%"></div>
      <svg class="syn-soccer-scene" viewBox="0 0 920 180" aria-hidden="true">
        <defs>
          <pattern id="syn-net" width="12" height="12" patternUnits="userSpaceOnUse">
            <path d="M0 0H12M0 0V12" stroke="rgba(17,24,39,.20)" stroke-width="1"/>
          </pattern>
        </defs>
        <rect x="18" y="22" width="760" height="136" rx="10" fill="none" stroke="rgba(47,125,89,.36)" stroke-dasharray="7 6"/>
        <line x1="398" y1="22" x2="398" y2="158" stroke="rgba(47,125,89,.25)" stroke-width="2"/>
        <circle cx="398" cy="90" r="28" fill="none" stroke="rgba(47,125,89,.25)" stroke-width="2"/>
        <g class="syn-goal-svg">
          <rect x="796" y="42" width="88" height="96" rx="4" fill="url(#syn-net)" stroke="#111827" stroke-width="4"/>
          <path d="M796 42H884M796 138H884M796 42V138" fill="none" stroke="#111827" stroke-width="8" stroke-linecap="round"/>
          <path d="M884 42L904 58V154L884 138" fill="rgba(255,255,255,.25)" stroke="#111827" stroke-width="3" stroke-linejoin="round"/>
        </g>
        <g class="syn-cartoon-player${state === 'done' ? ' kick' : ''}" transform="translate(${runner} 50)">
          <ellipse cx="31" cy="98" rx="35" ry="6" fill="rgba(0,0,0,.16)"/>
          <circle cx="30" cy="15" r="13" fill="#D79B63" stroke="#8A5A37" stroke-width="1.5"/>
          <path d="M18 12C20 1 34 -2 43 8C35 5 27 7 22 15Z" fill="#2B1B12"/>
          <path class="syn-svg-arm left" d="M18 42L4 58" stroke="#D79B63" stroke-width="7" stroke-linecap="round"/>
          <path class="syn-svg-arm right" d="M42 43L58 55" stroke="#D79B63" stroke-width="7" stroke-linecap="round"/>
          <path d="M17 29Q30 20 44 29L49 61Q30 72 12 61Z" fill="#FFD342" stroke="#C99B00" stroke-width="2"/>
          <path d="M18 58H44L48 78H14Z" fill="#148A45" stroke="#0B5F2D" stroke-width="2"/>
          <path class="syn-svg-leg left" d="M22 76L12 99" stroke="#D79B63" stroke-width="8" stroke-linecap="round"/>
          <path class="syn-svg-leg right" d="M39 76L60 94" stroke="#D79B63" stroke-width="8" stroke-linecap="round"/>
          <path d="M4 101H22" stroke="#111827" stroke-width="7" stroke-linecap="round"/>
          <path d="M53 98H72" stroke="#111827" stroke-width="7" stroke-linecap="round"/>
          ${state === 'done' ? '' : `<g class="syn-ball-svg" transform="translate(74 98)"><circle r="8" fill="#fff" stroke="#111827" stroke-width="2"/><path d="M-5,-1 L0,-6 L6,-1 L4,6 L-4,6 Z" fill="#111827" opacity=".9"/></g>`}
        </g>
        ${ballHtml}
      </svg>
    </div>
  </div>`;
}

function synGenerationStages(plan) {
  const action = (plan.loadAction || 'INSERT').toUpperCase();
  const prep = (plan.targetPrep || 'NONE').toUpperCase();
  if (plan.receiver === 'DB') {
    const prepLabel = plan.dropTable ? 'Drop/recreate target'
      : action === 'TRUNCATE_ONLY' || prep === 'TRUNCATE' ? 'Truncate target'
      : prep === 'DELETE' ? 'Delete target rows'
      : 'Prepare target';
    const loadLabel = action === 'TRUNCATE_ONLY' ? 'Clear target'
      : action === 'UPDATE' ? 'Update rows'
      : action === 'INSERT_UPDATE' ? 'Upsert rows'
      : 'Load rows';
    if (action === 'TRUNCATE_ONLY') {
      return [
        { label: prepLabel },
        { label: 'Target cleared' }
      ];
    }
    return [
      { label: 'Generate rows' },
      { label: prepLabel },
      { label: loadLabel },
      { label: 'Loaded' }
    ];
  }
  return [
    { label: 'Generate rows' },
    { label: `Build ${plan.receiver || 'files'}` },
    { label: 'Download files' },
    { label: 'Generated' }
  ];
}

function synStageForProgress(stages, percent) {
  if (!stages || !stages.length) return { index: 0, label: synProgressMessage(percent) };
  const pct = Math.max(0, Math.min(100, percent || 0));
  const index = pct >= 100 ? stages.length - 1 : Math.min(stages.length - 1, Math.floor((pct / 100) * stages.length));
  return { index, label: stages[index].label };
}

function synStageIndexFor(stages, stage, message, percent) {
  if (!stages || !stages.length) return 0;
  const text = `${stage || ''} ${message || ''}`.toLowerCase();
  const labels = stages.map(s => String(s.label || '').toLowerCase());
  let exact = labels.findIndex(label => label && text.includes(label));
  if (exact >= 0) return exact;
  if (/generate/.test(text)) exact = labels.findIndex(label => /generate/.test(label));
  else if (/delete|truncate|drop|create|prepare|clear target|target ready/.test(text)) exact = labels.findIndex(label => /delete|truncate|drop|prepare|clear/.test(label));
  else if (/upsert/.test(text)) exact = labels.findIndex(label => /upsert|load/.test(label));
  else if (/update/.test(text)) exact = labels.findIndex(label => /update|load/.test(label));
  else if (/insert|load/.test(text)) exact = labels.findIndex(label => /insert|load/.test(label));
  else if (/commit|complete|loaded|generated|cleared/.test(text)) exact = stages.length - 1;
  return exact >= 0 ? exact : synStageForProgress(stages, percent).index;
}

function synProgressMessage(percent) {
  if (percent < 25) return 'Preparing target';
  if (percent < 55) return 'Generating rows';
  if (percent < 82) return 'Loading output';
  return 'Almost loaded';
}

function synFormatRows(n) {
  const v = Number(n || 0);
  return Number.isFinite(v) ? v.toLocaleString() : '0';
}

function synProgressDetailText(status) {
  if (!status) return '';
  if (status.detail) return status.detail;
  if (status.currentTable) {
    const hasTableTotal = status.tableRowsTotal !== undefined && status.tableRowsTotal !== null;
    const table = hasTableTotal
      ? `${status.currentTable}: ${synFormatRows(status.tableRowsDone)} / ${synFormatRows(status.tableRowsTotal)} rows processed`
      : `${status.currentTable}: in progress`;
    const total = status.rowsTotal ? `total ${synFormatRows(status.rowsDone)} / ${synFormatRows(status.rowsTotal)}` : '';
    return [table, total].filter(Boolean).join(' | ');
  }
  if (Array.isArray(status.partitions) && status.partitions.length) {
    const complete = status.partitions.filter(p => String(p.status).toUpperCase() === 'COMPLETED').length;
    return `${complete} / ${status.partitions.length} partitions completed`;
  }
  return '';
}

function synJobDone(status) {
  return ['COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(String(status || '').toUpperCase());
}

function synJobStatusClass(status) {
  const s = String(status || '').toUpperCase();
  if (s === 'COMPLETED') return 'good';
  if (s === 'FAILED') return 'bad';
  if (s === 'CANCELLED') return 'dim';
  if (s === 'CANCELLING') return 'warn';
  return 'info';
}

function synJobTime(value) {
  if (!value) return '';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

function synRenderSyntheticJobs(jobs) {
  const boxes = ['syn-jobs', 'syn-jobs-page'].map(id => $(id)).filter(Boolean);
  if (!boxes.length) return;
  const all = Array.isArray(jobs) ? jobs : [];
  const rows = all.slice(0, 12);
  const active = all.filter(j => !synJobDone(j.status)).length;
  ['syn-job-count', 'syn-job-count-page'].forEach(id => {
    const count = $(id);
    if (count) count.textContent = active ? `${active} active` : `${rows.length} recent`;
  });
  if (!rows.length) {
    boxes.forEach(box => { box.innerHTML = '<div class="empty">No run history yet.</div>'; });
    return;
  }
  const html = rows.map(job => {
    const pct = Math.max(0, Math.min(100, Math.round(job.percent || 0)));
    const detail = synProgressDetailText(job) || job.message || job.stage || '';
    const title = [job.dataset || 'synthetic', job.receiver || 'DB'].filter(Boolean).join(' -> ');
    const owner = job.ownerUsername ? `owner ${job.ownerUsername}` : '';
    const meta = [synFormatRows(job.plannedRows) + ' planned rows', `${job.tableCount || 0} table(s)`, owner, synJobTime(job.startedAt)]
      .filter(Boolean).join(' | ');
    const cancelling = String(job.status || '').toUpperCase() === 'CANCELLING';
    const cancel = synJobDone(job.status)
      ? ''
      : `<button class="ghost small danger" onclick="synCancelSyntheticJob(${js(job.id)})">${cancelling ? 'Cancel again' : 'Cancel'}</button>`;
    const partitions = Array.isArray(job.partitions) ? job.partitions : [];
    const partitionHtml = partitions.length ? `<details class="syn-partitions">
      <summary>${partitions.filter(p => String(p.status).toUpperCase() === 'COMPLETED').length} of ${partitions.length} partitions complete</summary>
      <div class="syn-partition-grid">${partitions.map(p => {
        const pp = p.plannedRows ? Math.max(0, Math.min(100, Math.round((Number(p.rowsCompleted || 0) / Number(p.plannedRows)) * 100))) : 0;
        const ps = String(p.status || 'QUEUED').toUpperCase();
        const canCancel = !['COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(ps);
        const canRetry = ['FAILED', 'CANCELLED', 'CANCELED'].includes(ps);
        return `<div class="syn-partition-row">
          <div><b>${esc(p.table)} #${p.number}</b><span>Wave ${Number(p.wave || 0) + 1} &middot; rows ${synFormatRows(p.rowStart)}-${synFormatRows(Math.max(p.rowStart, Number(p.rowEnd || 1) - 1))}</span></div>
          <div class="syn-partition-meter"><span style="width:${pp}%"></span></div>
          <span class="pill ${synJobStatusClass(ps)}">${esc(ps)}</span>
          <span class="syn-partition-worker">${esc(p.workerId || 'waiting')}</span>
          <div>${canCancel ? `<button class="ghost small danger" onclick="synCancelPartition(${js(job.id)},${js(p.id)})">Cancel</button>` : ''}${canRetry ? `<button class="ghost small" onclick="synRetryPartition(${js(job.id)},${js(p.id)})">Retry</button>` : ''}</div>
        </div>`;
      }).join('')}</div>
    </details>` : '';
    return `<div class="syn-job-row">
      <div class="syn-job-main">
        <div class="syn-job-title"><b>${esc(title)}</b><span class="pill ${synJobStatusClass(job.status)}">${esc(job.status || 'PENDING')}</span><span>${pct}%</span></div>
        <div class="syn-job-bar"><span style="width:${pct}%"></span></div>
        <div class="syn-job-detail">${esc(detail)}</div>
        <div class="syn-job-meta">${esc(meta)}</div>
        ${partitionHtml}
      </div>
      <div class="syn-job-actions">${cancel}</div>
    </div>`;
  }).join('');
  boxes.forEach(box => { box.innerHTML = html; });
}

async function synRefreshSyntheticJobs(silent = false) {
  const boxes = ['syn-jobs', 'syn-jobs-page'].map(id => $(id)).filter(Boolean);
  if (!boxes.length) return;
  try {
    const jobs = await api.get('/api/synthetic/jobs');
    synRenderSyntheticJobs(jobs || []);
  } catch (e) {
    if (!silent) toast(e.message, 'err');
    if (!silent) boxes.forEach(box => { box.innerHTML = `<div class="error">${esc(e.message)}</div>`; });
  }
}

function synEnsureSyntheticJobPolling() {
  if (synJobPollTimer) return;
  synJobPollTimer = setInterval(() => {
    if ($('syn-jobs') || $('syn-jobs-page')) synRefreshSyntheticJobs(true);
  }, 2000);
}

async function synCancelSyntheticJob(id) {
  if (!id) return;
  try {
    await api.post(`/api/synthetic/jobs/${encodeURIComponent(id)}/cancel`, {});
    toast('Synthetic generation cancel requested', 'ok');
    await synRefreshSyntheticJobs(true);
  } catch (e) {
    toast(e.message, 'err');
  }
}

async function synCancelPartition(jobId, partitionId) {
  try {
    await api.post(`/api/synthetic/jobs/${encodeURIComponent(jobId)}/partitions/${encodeURIComponent(partitionId)}/cancel`, {});
    toast('Partition cancel requested', 'ok');
    await synRefreshSyntheticJobs(true);
  } catch (e) { toast(e.message, 'err'); }
}

async function synRetryPartition(jobId, partitionId) {
  try {
    await api.post(`/api/synthetic/jobs/${encodeURIComponent(jobId)}/partitions/${encodeURIComponent(partitionId)}/retry`, {});
    toast('Partition retry queued', 'ok');
    await synRefreshSyntheticJobs(true);
  } catch (e) { toast(e.message, 'err'); }
}

function synSavedJobDate(value) {
  if (!value) return '';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? '' : d.toLocaleString();
}

function synApprovalKind(status) {
  const s = String(status || 'DRAFT').toUpperCase();
  if (s === 'APPROVED') return 'good';
  if (s === 'PENDING_APPROVAL') return 'warn';
  if (s === 'REJECTED') return 'bad';
  if (s === 'DIRECT_RUN') return 'dim';
  return 'info';
}

function synApprovalLabel(status) {
  return String(status || 'DRAFT').replace(/_/g, ' ');
}

function synRenderSavedJobs(jobs) {
  synSavedJobs = Array.isArray(jobs) ? jobs : [];
  const box = $('syn-saved-jobs');
  const count = $('syn-saved-count');
  if (count) count.textContent = `${synSavedJobs.length} saved`;
  if (!box) return;
  if (!synSavedJobs.length) {
    box.innerHTML = '<div class="empty">No saved synthetic jobs yet. Build a design, then click Save Current Design.</div>';
    return;
  }
  box.innerHTML = synSavedJobs.map(job => {
    const approval = job.approvalStatus || 'DRAFT';
    const needsDbApproval = String(job.receiver || 'DB').toUpperCase() === 'DB' && approval !== 'APPROVED';
    const meta = [
      job.dataset || 'synthetic',
      job.receiver || 'DB',
      `${synFormatRows(job.plannedRows)} planned rows`,
      `${job.tableCount || 0} table(s)`,
      `approval ${synApprovalLabel(approval)}`,
      job.approvedBy ? `signed by ${job.approvedBy}` : '',
      job.ownerUsername ? `owner ${job.ownerUsername}` : '',
      job.updatedAt ? `updated ${synSavedJobDate(job.updatedAt)}` : ''
    ].filter(Boolean).join(' | ');
    const approvalButtons = [
      approval !== 'PENDING_APPROVAL' && approval !== 'APPROVED'
        ? `<button type="button" class="ghost small" data-syn-saved-action="request-approval" data-syn-saved-id="${esc(job.id)}">Request</button>` : '',
      approval !== 'APPROVED'
        ? `<button type="button" class="ghost small" data-syn-saved-action="approve" data-syn-saved-id="${esc(job.id)}">Approve</button>` : '',
      approval === 'PENDING_APPROVAL'
        ? `<button type="button" class="ghost small danger" data-syn-saved-action="reject" data-syn-saved-id="${esc(job.id)}">Reject</button>` : ''
    ].filter(Boolean).join('');
    return `<div class="syn-job-row">
      <div class="syn-job-main">
        <div class="syn-job-title"><b>${esc(job.name || 'Saved job')}</b><span class="pill ${synApprovalKind(approval)}">${esc(synApprovalLabel(approval))}</span></div>
        ${job.description ? `<div class="syn-job-detail">${esc(job.description)}</div>` : ''}
        <div class="syn-job-meta">${esc(meta)}</div>
      </div>
      <div class="syn-job-actions">
        <button type="button" class="small" data-syn-saved-action="run" data-syn-saved-id="${esc(job.id)}" ${needsDbApproval ? 'disabled title="Database saved jobs require approval before run"' : ''}>Run</button>
        <button type="button" class="ghost small" data-syn-saved-action="load" data-syn-saved-id="${esc(job.id)}">Load</button>
        ${approvalButtons}
        <button type="button" class="ghost small" title="${needsDbApproval ? 'Approve database saved job before exporting runner' : 'Download PowerShell runner'}" data-syn-saved-action="download-ps1" data-syn-saved-id="${esc(job.id)}" ${needsDbApproval ? 'disabled' : ''}>PS1</button>
        <button type="button" class="ghost small" title="${needsDbApproval ? 'Approve database saved job before exporting runner' : 'Download Bash runner'}" data-syn-saved-action="download-sh" data-syn-saved-id="${esc(job.id)}" ${needsDbApproval ? 'disabled' : ''}>SH</button>
        <button type="button" class="ghost small danger" data-syn-saved-action="delete" data-syn-saved-id="${esc(job.id)}" data-syn-saved-name="${esc(job.name || '')}">Delete</button>
      </div>
    </div>`;
  }).join('');
}

async function synRefreshSavedJobs(silent = false) {
  if (!$('syn-saved-jobs')) return;
  try {
    const jobs = await api.get('/api/synthetic/saved-jobs');
    synRenderSavedJobs(jobs || []);
  } catch (e) {
    if (!silent) toast(e.message, 'err');
    const box = $('syn-saved-jobs');
    if (!silent && box) box.innerHTML = `<div class="error">${esc(e.message)}</div>`;
  }
}

function synValidatePlanForSave(plan) {
  if (!plan.tables || !plan.tables.length) throw new Error('Add at least one table before saving');
  if (plan.receiver === 'DB' && !synHasDbTarget(plan)) throw new Error('Pick a target data source before saving a database job');
}

function synHasDbTarget(plan) {
  return !!(plan && (plan.targetDataSourceId || (Array.isArray(plan.targetSystems) && plan.targetSystems.length)));
}

function synNormalizeName(v) {
  return String(v || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function synDefaultTargetSystem() {
  return {
    name: '',
    targetDataSourceId: synState.targetDsId ? parseInt(synState.targetDsId) : null,
    targetSchema: synState.targetSchema || '',
    loadAction: synState.loadAction || 'INSERT',
    targetPrep: synState.targetPrep || 'NONE',
    batchSize: synState.batchSize ? parseInt(synState.batchSize) : null,
    commitEveryRows: parseInt(synState.commitEveryRows || '0') || 0,
    continueOnError: !!synState.continueOnError,
    maxRejects: synState.maxRejects === '' || synState.maxRejects == null ? null : (parseInt(synState.maxRejects) || 0),
    fastLoad: !!synState.fastLoad,
    tables: synState.tables.map(t => ({
      logicalTable: t.name,
      physicalTable: t.name,
      columns: (t.columns || []).map(c => ({ logicalColumn: c.name, physicalColumn: c.name, sqlType: c.sqlType || 'VARCHAR' }))
    }))
  };
}

function synEnsureTargetMappings(target) {
  target.tables = Array.isArray(target.tables) ? target.tables : [];
  const byTable = {};
  target.tables.forEach(t => { if (t.logicalTable) byTable[String(t.logicalTable).toLowerCase()] = t; });
  synState.tables.forEach(logical => {
    const key = String(logical.name || '').toLowerCase();
    if (!key) return;
    let mapped = byTable[key];
    if (!mapped) {
      mapped = { logicalTable: logical.name, physicalTable: logical.name, columns: [] };
      target.tables.push(mapped);
    }
    mapped.columns = Array.isArray(mapped.columns) ? mapped.columns : [];
    const byCol = {};
    mapped.columns.forEach(c => { if (c.logicalColumn) byCol[String(c.logicalColumn).toLowerCase()] = c; });
    (logical.columns || []).forEach(col => {
      const ck = String(col.name || '').toLowerCase();
      if (!ck || byCol[ck]) return;
      mapped.columns.push({ logicalColumn: col.name, physicalColumn: col.name, sqlType: col.sqlType || 'VARCHAR' });
    });
  });
  return target;
}

function synAddTargetSystem() {
  synState.targetSystems = Array.isArray(synState.targetSystems) ? synState.targetSystems : [];
  synState.targetSystems.push(synDefaultTargetSystem());
  synRender();
}

function synRemoveTargetSystem(i) {
  synState.targetSystems.splice(i, 1);
  synRender();
}

function synSetTargetSystem(i, key, value) {
  const target = synState.targetSystems[i]; if (!target) return;
  if (key === 'targetDataSourceId') target[key] = value ? parseInt(value) : null;
  else if (['batchSize', 'commitEveryRows', 'maxRejects'].includes(key)) target[key] = value === '' ? null : (parseInt(value) || 0);
  else if (['continueOnError', 'fastLoad', 'createTable', 'dropTable'].includes(key)) target[key] = !!value;
  else target[key] = value;
  if (key === 'targetDataSourceId') target.targetSchema = '';
  synRender();
}

function synSetTargetTable(i, ti, key, value) {
  const table = synState.targetSystems?.[i]?.tables?.[ti]; if (!table) return;
  table[key] = value;
}

function synSetTargetColumn(i, ti, ci, key, value) {
  const column = synState.targetSystems?.[i]?.tables?.[ti]?.columns?.[ci]; if (!column) return;
  column[key] = value;
}

function synUseSingleTargetAsSystem() {
  if (!synState.targetDsId) return toast('Pick a target data source first', 'err');
  synState.targetSystems = [synDefaultTargetSystem()];
  synRender();
}

function synClearTargetSystems() {
  synState.targetSystems = [];
  synRender();
}

async function synFillTargetSystemSchema(i) {
  const sel = $(`syn-mt-schema-${i}`);
  const target = synState.targetSystems?.[i];
  if (!sel || !target?.targetDataSourceId) return;
  try {
    const rows = await loadSchemas(target.targetDataSourceId);
    sel.innerHTML = '<option value="">Default</option>' + rows.map(r => `<option value="${esc(r.schema)}">${esc(r.schema)}</option>`).join('');
    sel.value = target.targetSchema || '';
  } catch (e) {
    sel.innerHTML = '<option value="">Could not load schemas</option>';
  }
}

async function synAutoMapTargetTables(i) {
  const target = synState.targetSystems?.[i]; if (!target?.targetDataSourceId || !target.targetSchema) return toast('Pick target schema first', 'err');
  try {
    const tables = await loadTables(target.targetDataSourceId, target.targetSchema);
    const byNorm = Object.fromEntries(tables.map(t => [synNormalizeName(t), t]));
    synEnsureTargetMappings(target).tables.forEach(t => {
      t.physicalTable = byNorm[synNormalizeName(t.logicalTable)] || t.physicalTable || t.logicalTable;
    });
    synRender();
    toast('Mapped target tables by name where possible', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function synLoadTargetColumns(i, ti) {
  const target = synState.targetSystems?.[i];
  const table = target?.tables?.[ti];
  if (!target?.targetDataSourceId || !target.targetSchema || !table?.physicalTable) return toast('Pick target schema and table first', 'err');
  try {
    const cols = await api.get(`/api/datasources/${target.targetDataSourceId}/tables/${encodeURIComponent(table.physicalTable)}/columns?schema=${encodeURIComponent(target.targetSchema)}`);
    const byNorm = Object.fromEntries((cols || []).map(c => [synNormalizeName(c.column), c]));
    table.columns = (table.columns || []).map(c => {
      const match = byNorm[synNormalizeName(c.logicalColumn)] || byNorm[synNormalizeName(c.physicalColumn)];
      return match ? { ...c, physicalColumn: match.column, sqlType: (typeof sqlTypeFromDbType === 'function') ? sqlTypeFromDbType(match.type) : (match.type || c.sqlType || 'VARCHAR') } : c;
    });
    synRender();
    toast(`Loaded columns from ${table.physicalTable}`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

function synTargetSystemPlan() {
  return (Array.isArray(synState.targetSystems) ? synState.targetSystems : [])
    .map(t => synEnsureTargetMappings(t))
    .filter(t => t.targetDataSourceId)
    .map(t => ({
      name: t.name || dataSourceName(t.targetDataSourceId),
      targetDataSourceId: t.targetDataSourceId,
      targetSchema: t.targetSchema || null,
      createTable: t.createTable == null ? !!synState.createTable : !!t.createTable,
      dropTable: t.dropTable == null ? !!synState.dropTable : !!t.dropTable,
      loadAction: t.loadAction || synState.loadAction || 'INSERT',
      targetPrep: t.targetPrep || synState.targetPrep || 'NONE',
      batchSize: t.batchSize || null,
      commitEveryRows: t.commitEveryRows || 0,
      continueOnError: !!t.continueOnError,
      maxRejects: t.maxRejects == null ? null : t.maxRejects,
      fastLoad: !!t.fastLoad,
      tables: (t.tables || []).filter(mt => mt.logicalTable && mt.physicalTable).map(mt => ({
        logicalTable: mt.logicalTable,
        physicalTable: mt.physicalTable,
        columns: (mt.columns || []).filter(c => c.logicalColumn && c.physicalColumn).map(c => ({
          logicalColumn: c.logicalColumn,
          physicalColumn: c.physicalColumn,
          sqlType: c.sqlType || null
        }))
      }))
    }));
}

async function synSaveCurrentJob() {
  try {
    const plan = collectSynPlan();
    synValidatePlanForSave(plan);
    const suggested = (plan.dataset || 'synthetic') + ' job';
    const name = prompt('Saved job name', suggested);
    if (name == null) return;
    if (!name.trim()) return toast('Saved job name is required', 'err');
    const description = prompt('Description (optional)', '') || '';
    await api.post('/api/synthetic/saved-jobs', { name: name.trim(), description: description.trim(), plan });
    await synRefreshSavedJobs(true);
    if (typeof activatePageTab === 'function') activatePageTab('synthetic', 'syn-saved-card', true);
    toast('Synthetic job saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function synFetchPlanSummary(plan) {
  if (!plan) return null;
  try {
    return await api.post('/api/synthetic/plan-summary', plan);
  } catch (e) {
    return { error: e.message || 'Plan summary is unavailable' };
  }
}

async function synConfirmRunSavedJob(id) {
  try {
    let job = synSavedJobs.find(j => j.id === id);
    if (!job || !job.plan) job = await api.get(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}`);
    if (!job?.plan) throw new Error('Saved job does not include a runnable plan');
    job.planSummary = await synFetchPlanSummary(job.plan);
    synPendingRunJob = job;
    synShowRunConfirm(job, job.planSummary);
  } catch (e) { toast(e.message, 'err'); }
}

function synShowRunConfirm(job, summary = null) {
  synCloseRunConfirm();
  const modal = document.createElement('div');
  modal.id = 'syn-run-confirm-modal';
  modal.className = 'modal-backdrop';
  modal.innerHTML = `<div class="modal-panel modal-wide syn-run-confirm">
    <div class="modal-head">
      <div>
        <h3>Run saved synthetic job?</h3>
        <p class="sub tight">Review the plan before ForgeTDM changes data or generates files.</p>
      </div>
      <button type="button" class="small ghost" data-syn-saved-action="close-run-confirm">X</button>
    </div>
    ${synSavedJobFlowHtml(job, summary)}
    <div class="modal-actions">
      <button type="button" class="ghost" data-syn-saved-action="close-run-confirm">Cancel</button>
      <button type="button" data-syn-saved-action="confirm-run">Confirm and Run</button>
    </div>
  </div>`;
  document.body.appendChild(modal);
}

function synCloseRunConfirm() {
  const modal = $('syn-run-confirm-modal');
  if (modal) modal.remove();
}

async function synRunPendingSavedJob() {
  const job = synPendingRunJob;
  synPendingRunJob = null;
  synCloseRunConfirm();
  if (!job?.id) return toast('No saved job selected', 'err');
  await synRunSavedJob(job.id, job);
}

function synPlanTableRows(summary) {
  const tables = Array.isArray(summary?.tables) ? summary.tables : (Array.isArray(summary?.logicalTables) ? summary.logicalTables : []);
  if (!tables.length) return '';
  return `<div class="syn-plan-table-wrap">
    <table class="syn-plan-table">
      <thead><tr><th>Table</th><th>Rows</th><th>Memory</th><th>Write mode</th><th>Prep</th><th>Constraints</th><th>Notes</th></tr></thead>
      <tbody>${tables.map(t => {
        const notes = [
          t.hasApiGenerator ? 'API calls' : '',
          t.hasLookupGenerator ? 'LOOKUP' : '',
          (t.foreignKeyColumns || []).length ? `${t.foreignKeyColumns.length} FK` : ''
        ].filter(Boolean).join(', ') || '-';
        const constraintText = Number(t.constraintCount || 0)
          ? `${t.constraintCount || 0} captured / ${t.enforcedConstraintCount || 0} enforced`
          : '-';
        return `<tr>
          <td><b>${esc(t.table || t.name || '')}</b></td>
          <td class="num">${esc(synFormatRows(t.rows))}</td>
          <td>${pill(t.memoryMode || '-', 'dim')}</td>
          <td><code>${esc(t.writeMode || '-')}</code></td>
          <td>${esc(t.targetPrep || '-')}</td>
          <td>${esc(constraintText)}</td>
          <td>${esc(notes)}</td>
        </tr>`;
      }).join('')}</tbody>
    </table>
  </div>`;
}

function synPlanWarnings(summary) {
  const tables = Array.isArray(summary?.tables) ? summary.tables : (Array.isArray(summary?.logicalTables) ? summary.logicalTables : []);
  const warnings = [];
  if (summary?.error) warnings.push(`Plan summary fallback: ${summary.error}`);
  if (summary?.warning) warnings.push(summary.warning);
  if (summary?.constraintCaptureWarning) warnings.push(summary.constraintCaptureWarning);
  if (Number(summary?.constraintsCaptured || 0) > Number(summary?.constraintsEnforced || 0)) {
    warnings.push(`${summary.constraintsCaptured} CHECK constraint(s) captured; ${summary.constraintsEnforced || 0} simple rule(s) will be enforced during generation. Unsupported expressions remain in lineage for review.`);
  }
  tables.forEach(t => {
    if (t.hasApiGenerator) warnings.push(`${t.table}: API generator will call an allowlisted HTTP endpoint during generation.`);
    if (t.hasLookupGenerator) warnings.push(`${t.table}: LOOKUP values depend on parent rows already generated or available.`);
  });
  (summary?.targets || []).forEach(t => {
    if (t.constraintCapture?.warning) warnings.push(`${t.name}: ${t.constraintCapture.warning}`);
  });
  const readiness = summary?.bankingReadiness;
  if (readiness && Number(readiness.score || 0) < 88) {
    warnings.push(`Banking readiness is ${readiness.score || 0}/100 (${readiness.rating || 'NEEDS_REVIEW'}). ${(readiness.gaps || []).slice(0, 3).join(' ')}`);
  }
  if (!warnings.length) return '';
  return `<div class="syn-run-english warn"><b>Checks</b><p>${warnings.map(esc).join('<br>')}</p></div>`;
}

function synPlanTargetLabel(plan, summary, receiver) {
  if (receiver !== 'DB') return receiver + ' output';
  const targets = summary?.targets || plan.targetSystems || [];
  if (Array.isArray(targets) && targets.length) {
    return targets.map(t => t.name || dataSourceName(t.targetDataSourceId) || `#${t.targetDataSourceId}`).join(', ');
  }
  return dataSourceName(plan.targetDataSourceId) + (plan.targetSchema ? ' / ' + plan.targetSchema : '');
}

function synSavedJobFlowHtml(job, summary = null) {
  const plan = job.plan || {};
  const approval = job.approvalStatus || 'DRAFT';
  const receiver = summary?.receiver || plan.receiver || job.receiver || 'DB';
  const action = String(summary?.loadAction || plan.loadAction || 'INSERT').toUpperCase();
  const prep = String(summary?.targetPrep || plan.targetPrep || 'NONE').toUpperCase();
  const target = synPlanTargetLabel(plan, summary, receiver);
  const tables = plan.tables || [];
  const tableNames = tables.slice(0, 6).map(t => t.name).filter(Boolean).join(', ') + (tables.length > 6 ? `, +${tables.length - 6} more` : '');
  const prepText = plan.dropTable ? 'drop and recreate target tables'
    : action === 'TRUNCATE_ONLY' || prep === 'TRUNCATE' ? 'truncate selected target tables'
    : prep === 'DELETE' ? 'delete rows from selected target tables first'
    : 'leave existing target rows in place before loading';
  const loadText = receiver === 'DB'
    ? action === 'TRUNCATE_ONLY' ? 'clear the target only; no rows will be generated for load'
      : action === 'UPDATE' ? 'update matching target rows using key columns'
      : action === 'INSERT_UPDATE' ? 'update matching rows and insert missing rows'
      : action === 'REPLACE' ? 'insert generated rows after target prep'
      : 'insert generated rows into the target'
    : `build ${receiver} files and start the download`;
  const plannedRows = summary?.plannedRows ?? job.plannedRows;
  const memoryMode = summary?.memoryMode || (receiver === 'DB' && Number(plannedRows || 0) > 500000 ? 'STREAMING' : 'IN_MEMORY');
  const executionMode = summary?.executionMode || plan.executionMode || 'SINGLE';
  const executionText = executionMode === 'SINGLE' ? memoryMode
    : `${String(executionMode).replaceAll('_', ' ')} / ${summary?.partitionWorkers || plan.partitionCount || 'auto'} workers`;
  const writeModes = Array.isArray(summary?.tables) ? [...new Set(summary.tables.map(t => t.writeMode).filter(Boolean))] : [];
  const writeMode = summary?.writeMode || (writeModes.length === 1 ? writeModes[0] : writeModes.length ? writeModes.join(', ') : (receiver === 'DB' ? action : receiver));
  const readiness = summary?.bankingReadiness || {};
  const steps = receiver === 'DB'
    ? [
        ['PLAN', 'Saved plan', `Use "${job.name || plan.dataset || 'synthetic'}" with seed ${plan.seed ?? 42}.`],
        ['MODE', executionText, `Backend selected ${executionText} with ${writeMode}.`],
        ['GEN', 'Generate rows', `Create ${synFormatRows(plannedRows)} rows across ${job.tableCount || tables.length} table(s).`],
        ['PREP', prep, prepText],
        ['LOAD', action === 'TRUNCATE_ONLY' ? 'Clear target' : 'Load target', `${loadText}. Write mode: ${writeMode}.`],
        ['DONE', 'Record run', 'Write progress and final result to Run History.']
      ]
    : [
        ['PLAN', 'Saved plan', `Use "${job.name || plan.dataset || 'synthetic'}" with seed ${plan.seed ?? 42}.`],
        ['MODE', memoryMode, `Backend selected ${memoryMode} output generation.`],
        ['GEN', 'Generate rows', `Create ${synFormatRows(plannedRows)} rows across ${job.tableCount || tables.length} table(s).`],
        ['FILE', `Build ${receiver}`, loadText],
        ['DONE', 'Record run', 'Write progress and final result to Run History.']
      ];
  return `<div class="syn-run-summary">
      <div><span>Saved job</span><b>${esc(job.name || 'Synthetic job')}</b></div>
      <div><span>Dataset</span><b>${esc(plan.dataset || job.dataset || 'synthetic')}</b></div>
      <div><span>Target / output</span><b>${esc(target)}</b></div>
      <div><span>Tables</span><b>${esc(tableNames || 'No table names')}</b></div>
      <div><span>Rows</span><b>${esc(synFormatRows(plannedRows))}</b></div>
      <div><span>Execution</span><b>${esc(executionText)}</b></div>
      <div><span>Write mode</span><b>${esc(writeMode)}</b></div>
      <div><span>Target prep</span><b>${esc(prep)}</b></div>
      <div><span>Approval</span><b>${esc(synApprovalLabel(approval))}${job.approvedBy ? ` by ${esc(job.approvedBy)}` : ''}</b></div>
      <div><span>Constraints</span><b>${esc(`${summary?.constraintsCaptured || 0} captured / ${summary?.constraintsEnforced || 0} enforced`)}</b></div>
      <div><span>Banking readiness</span><b>${esc(readiness.score != null ? `${readiness.score}/100 ${readiness.rating || ''}` : 'Not scored')}</b></div>
    </div>
    <div class="syn-flow-preview">
      ${steps.map((s, i) => `<div class="syn-flow-step"><span>${esc(s[0])}</span><b>${esc(s[1])}</b><p>${esc(s[2])}</p></div>${i < steps.length - 1 ? '<div class="syn-flow-arrow">-&gt;</div>' : ''}`).join('')}
    </div>
    ${synPlanTableRows(summary)}
    ${Array.isArray(summary?.targets) && summary.targets.length ? `<div class="syn-plan-table-wrap">
      <table class="syn-plan-table"><thead><tr><th>Target</th><th>DB</th><th>Prep</th><th>Constraints</th><th>Mapped tables</th></tr></thead>
      <tbody>${summary.targets.map(t => `<tr><td><b>${esc(t.name || '')}</b></td><td>${esc(t.targetKind || '')} #${esc(t.targetDataSourceId || '')}</td><td>${esc(t.targetPrep || '-')}</td><td>${esc(`${t.constraintsCaptured || 0} / ${t.constraintsEnforced || 0}`)}</td><td>${esc((t.mappedTables || []).map(m => m.physicalTable).join(', '))}</td></tr>`).join('')}</tbody></table>
    </div>` : ''}
    ${approval !== 'APPROVED' ? `<div class="syn-run-english warn"><b>Governance</b><p>This saved design is ${esc(synApprovalLabel(approval))}. The run is allowed, but lineage will record that it was not approved at launch.</p></div>` : ''}
    ${synPlanWarnings(summary)}
    <div class="syn-run-english">
      <b>Plain English</b>
      <p>This will run the saved synthetic design exactly as stored. ${receiver === 'DB' ? `ForgeTDM will ${prepText}, then ${loadText} using ${writeMode}.` : `ForgeTDM will ${loadText}.`} You can watch progress in the Run History tab and cancel while it is active.</p>
    </div>`;
}

async function synRunSavedJob(id, savedJob = null) {
  const job = savedJob || synSavedJobs.find(j => j.id === id) || await api.get(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}`);
  const plan = job?.plan || {};
  if (typeof activatePageTab === 'function') activatePageTab('synthetic', 'syn-history-card', true);
  const out = $('syn-history-run-result');
  const controller = out ? synStartGameProgress(out, synGenerationStages(plan)) : null;
  if (controller) controller.setProgress(1, 'Launching saved job', 'Starting');
  try {
    let status = await api.post(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}/run`, {});
    toast('Saved synthetic job launched', 'ok');
    await synRefreshSavedJobs(true);
    await synRefreshSyntheticJobs(true);
    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
    while (status?.id && !synJobDone(status.status)) {
      if (controller) controller.setProgress(status.percent || 1, status.message || status.stage || 'Running', status.stage, synProgressDetailText(status));
      await sleep(700);
      status = await api.get(`/api/synthetic/jobs/${encodeURIComponent(status.id)}`);
    }
    if (status.status === 'FAILED') throw new Error(status.error || status.message || 'Synthetic generation failed');
    if (/^CANCEL+ED$/i.test(status.status || '')) throw new Error('Synthetic generation cancelled');
    const r = status.result || {};
    const summary = synResultTableSummary(r);
    if (r.files && r.files.length) {
      synLastFiles = r.files;
      r.files.forEach(f => synDownload(f.name, f.content));
      if (controller) {
        controller.finish('Goal! Files generated.');
        controller.setResult(`<p class="sub tight">Generated ${r.files.length} file(s) - downloads started.</p><p class="sub tight">${summary}</p>
          <ul>${r.files.map((f, i) => `<li><code>${esc(f.name)}</code> (${f.content.length} bytes) <button class="ghost small" onclick="synDownloadIdx(${i})">Download again</button></li>`).join('')}</ul>`);
      }
    } else if (controller) {
      controller.finish('Goal! Loaded.');
      controller.setResult(`<p class="sub tight">Saved job completed.</p><p class="sub tight">${summary}</p>`);
    }
    await synRefreshSyntheticJobs(true);
  } catch (e) {
    if (controller) {
      controller.fail(/cancel/i.test(e.message || '') ? 'Generation cancelled.' : 'Generation failed.');
      controller.setResult(`<div class="error">${esc(e.message)}</div>`);
    } else if (out) {
      out.innerHTML = `<div class="error">${esc(e.message)}</div>`;
    }
    await synRefreshSyntheticJobs(true);
    toast(e.message, /cancel/i.test(e.message || '') ? 'ok' : 'err');
  }
}

async function synDeleteSavedJob(id, name) {
  if (!confirm('Delete saved job ' + (name || id) + '?')) return;
  try {
    await api.del(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}`);
    await synRefreshSavedJobs(true);
    toast('Saved job deleted', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function synRequestSavedJobApproval(id) {
  try {
    const note = prompt('Approval request note (optional)', '') || '';
    await api.post(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}/approval/request`, { note });
    await synRefreshSavedJobs(true);
    toast('Approval requested', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function synApproveSavedJob(id) {
  try {
    const note = (prompt('Approval note / e-signature reason (required)', '') || '').trim();
    if (!note) return toast('Approval note is required', 'err');
    await api.post(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}/approval/approve`, { note });
    await synRefreshSavedJobs(true);
    toast('Saved job approved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function synRejectSavedJob(id) {
  try {
    const note = (prompt('Reject note / reason (required)', '') || '').trim();
    if (!note) return toast('Rejection note is required', 'err');
    await api.post(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}/approval/reject`, { note });
    await synRefreshSavedJobs(true);
    toast('Saved job rejected', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function synLoadSavedJob(id) {
  try {
    let job = synSavedJobs.find(j => j.id === id);
    if (!job || !job.plan) job = await api.get(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}`);
    if (!job?.plan) throw new Error('Saved job does not include a plan');
    synApplySavedPlan(job.plan);
    if (typeof activatePageTab === 'function') activatePageTab('synthetic', 'syn-design-card', true);
    toast('Saved job loaded into builder', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function synDownloadSavedJobScript(id, kind) {
  try {
    let job = synSavedJobs.find(j => j.id === id);
    if (!job || !job.plan) job = await api.get(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}`);
    if (!job?.id) throw new Error('Saved job was not found');
    const ext = kind === 'sh' ? 'sh' : 'ps1';
    await api.post(`/api/synthetic/saved-jobs/${encodeURIComponent(id)}/export`, { kind: ext });
    const content = ext === 'sh' ? synSavedJobBashScript(job) : synSavedJobPowerShellScript(job);
    const fileName = `forgetdm-${synFileSlug(job.name || job.id)}-runner.${ext}`;
    synDownload(fileName, content);
    toast(`Downloaded ${ext.toUpperCase()} runner`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

function synFileSlug(value) {
  return String(value || 'synthetic-job')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 80) || 'synthetic-job';
}

function synScriptBaseUrl() {
  try { return window.location.origin || 'http://localhost:8088'; }
  catch { return 'http://localhost:8088'; }
}

function synPsQuote(value) {
  return "'" + String(value ?? '').replace(/'/g, "''") + "'";
}

function synShQuote(value) {
  return "'" + String(value ?? '').replace(/'/g, "'\"'\"'") + "'";
}

function synOneLine(value) {
  return String(value ?? '').replace(/\s+/g, ' ').trim();
}

function synScriptTargetComments(job) {
  const targets = job?.plan?.targetSystems || [];
  if (!Array.isArray(targets) || !targets.length) return [];
  return [
    '# Target systems:',
    ...targets.map(t => '#   - ' + synOneLine(`${t.name || dataSourceName(t.targetDataSourceId) || 'target'}: ${dataSourceName(t.targetDataSourceId)}${t.targetSchema ? ' / ' + t.targetSchema : ''}; tables ${(t.tables || []).map(m => `${m.logicalTable}->${m.physicalTable}`).join(', ')}`))
  ];
}

function synSavedJobPowerShellScript(job) {
  const baseUrl = synScriptBaseUrl();
  const jobId = String(job.id || '');
  const jobName = synOneLine(job.name || 'Synthetic job');
  const rows = synFormatRows(job.plannedRows || 0);
  const tables = job.tableCount || 0;
  const lines = [
    '# ForgeTDM saved synthetic job runner',
    '# Saved job: ' + jobName,
    '# Plan: ' + rows + ' rows across ' + tables + ' table(s)',
    ...synScriptTargetComments(job),
    '# Scheduler usage: set FORGETDM_URL plus FORGETDM_TOKEN, or FORGETDM_USER/FORGETDM_PASS as a fallback.',
    '# Task Scheduler command: powershell -ExecutionPolicy Bypass -File .\\' + `forgetdm-${synFileSlug(job.name || job.id)}-runner.ps1`,
    '',
    'param(',
    '  [string]$BaseUrl = $env:FORGETDM_URL,',
    '  [string]$Token = $env:FORGETDM_TOKEN,',
    '  [string]$Username = $env:FORGETDM_USER,',
    '  [string]$Password = $env:FORGETDM_PASS,',
    '  [string]$OutputDir = $env:FORGETDM_OUTPUT_DIR,',
    '  [int]$PollSeconds = 2',
    ')',
    '',
    '$ErrorActionPreference = "Stop"',
    'if ($env:FORGETDM_POLL_SECONDS) { $PollSeconds = [int]$env:FORGETDM_POLL_SECONDS }',
    '$JobId = ' + synPsQuote(jobId),
    '$JobName = ' + synPsQuote(jobName),
    'if ([string]::IsNullOrWhiteSpace($BaseUrl)) { $BaseUrl = ' + synPsQuote(baseUrl) + ' }',
    'if ([string]::IsNullOrWhiteSpace($OutputDir)) { $OutputDir = (Get-Location).Path }',
    '$BaseUrl = $BaseUrl.TrimEnd("/")',
    '',
    '$Session = New-Object Microsoft.PowerShell.Commands.WebRequestSession',
    '$Headers = @{}',
    'if (![string]::IsNullOrWhiteSpace($Token)) {',
    '  $Headers["Authorization"] = "Bearer $Token"',
    '} else {',
    '  if ([string]::IsNullOrWhiteSpace($Username)) { $Username = Read-Host "ForgeTDM username" }',
    '  if ([string]::IsNullOrWhiteSpace($Password)) {',
    '    $SecurePassword = Read-Host "ForgeTDM password" -AsSecureString',
    '    $Bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecurePassword)',
    '    try { $Password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($Bstr) }',
    '    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($Bstr) }',
    '  }',
    '  $LoginBody = @{ username = $Username; password = $Password } | ConvertTo-Json -Compress',
    '  Invoke-RestMethod -Uri "$BaseUrl/api/auth/login" -Method Post -ContentType "application/json" -Body $LoginBody -WebSession $Session | Out-Null',
    '}',
    '',
    '$EncodedJobId = [System.Uri]::EscapeDataString($JobId)',
    'Write-Host "Launching ForgeTDM saved job $JobName ($JobId)"',
    '$Run = Invoke-RestMethod -Uri "$BaseUrl/api/synthetic/saved-jobs/$EncodedJobId/run" -Method Post -ContentType "application/json" -Body "{}" -WebSession $Session -Headers $Headers',
    '$RunId = [string]$Run.id',
    'if ([string]::IsNullOrWhiteSpace($RunId)) { throw "ForgeTDM did not return a synthetic run id." }',
    '$EncodedRunId = [System.Uri]::EscapeDataString($RunId)',
    'Write-Host "Synthetic run id: $RunId"',
    '',
    'do {',
    '  Start-Sleep -Seconds $PollSeconds',
    '  $Status = Invoke-RestMethod -Uri "$BaseUrl/api/synthetic/jobs/$EncodedRunId" -Method Get -WebSession $Session -Headers $Headers',
    '  $Line = "{0}% {1} - {2}" -f $Status.percent, $Status.stage, $Status.message',
    '  if ($Status.detail) { $Line = "$Line | $($Status.detail)" }',
    '  Write-Host $Line',
    '} while (@("COMPLETED","FAILED","CANCELLED","CANCELED") -notcontains [string]$Status.status)',
    '',
    'if ([string]$Status.status -eq "FAILED") {',
    '  $FailureMessage = $Status.error',
    '  if ([string]::IsNullOrWhiteSpace($FailureMessage)) { $FailureMessage = $Status.message }',
    '  Write-Error ("ForgeTDM job failed: " + $FailureMessage)',
    '  exit 1',
    '}',
    'if (@("CANCELLED","CANCELED") -contains [string]$Status.status) {',
    '  Write-Error "ForgeTDM job was cancelled."',
    '  exit 2',
    '}',
    '',
    'if ($Status.result -and $Status.result.files) {',
    '  New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null',
    '  $Utf8NoBom = New-Object System.Text.UTF8Encoding $false',
    '  foreach ($File in $Status.result.files) {',
    '    $SafeName = [System.IO.Path]::GetFileName([string]$File.name)',
    '    if ([string]::IsNullOrWhiteSpace($SafeName)) { $SafeName = "synthetic-output.txt" }',
    '    $Path = Join-Path $OutputDir $SafeName',
    '    [System.IO.File]::WriteAllText($Path, [string]$File.content, $Utf8NoBom)',
    '    Write-Host "Saved file: $Path"',
    '  }',
    '}',
    '',
    'Write-Host "ForgeTDM saved job completed successfully."',
    'exit 0'
  ];
  return lines.join('\r\n') + '\r\n';
}

function synSavedJobBashScript(job) {
  const baseUrl = synScriptBaseUrl();
  const jobId = String(job.id || '');
  const jobName = synOneLine(job.name || 'Synthetic job');
  const rows = synFormatRows(job.plannedRows || 0);
  const tables = job.tableCount || 0;
  const lines = [
    '#!/usr/bin/env bash',
    '# ForgeTDM saved synthetic job runner',
    '# Saved job: ' + jobName,
    '# Plan: ' + rows + ' rows across ' + tables + ' table(s)',
    ...synScriptTargetComments(job),
    '# Scheduler usage: set FORGETDM_URL plus FORGETDM_TOKEN, or FORGETDM_USER/FORGETDM_PASS as a fallback.',
    '# Cron example: FORGETDM_URL=http://server:8088 FORGETDM_TOKEN=... /path/' + `forgetdm-${synFileSlug(job.name || job.id)}-runner.sh`,
    '',
    'set -euo pipefail',
    '',
    'JOB_ID=' + synShQuote(jobId),
    'JOB_NAME=' + synShQuote(jobName),
    'BASE_URL="${FORGETDM_URL:-' + baseUrl.replace(/"/g, '\\"') + '}"',
    'OUTPUT_DIR="${FORGETDM_OUTPUT_DIR:-$(pwd)}"',
    'POLL_SECONDS="${FORGETDM_POLL_SECONDS:-2}"',
    'TOKEN="${FORGETDM_TOKEN:-}"',
    'USER_NAME="${FORGETDM_USER:-}"',
    'PASSWORD="${FORGETDM_PASS:-}"',
    '',
    'command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }',
    'command -v python3 >/dev/null 2>&1 || { echo "python3 is required for JSON parsing" >&2; exit 1; }',
    '',
    'BASE_URL="${BASE_URL%/}"',
    '',
    'COOKIE_JAR="$(mktemp)"',
    'STATUS_FILE="$(mktemp)"',
    'cleanup() { rm -f "$COOKIE_JAR" "$STATUS_FILE"; }',
    'trap cleanup EXIT',
    '',
    'AUTH_ARGS=()',
    'if [[ -n "$TOKEN" ]]; then',
    '  AUTH_ARGS=(-H "Authorization: Bearer $TOKEN")',
    'else',
    '  if [[ -z "$USER_NAME" ]]; then read -r -p "ForgeTDM username: " USER_NAME; fi',
    '  if [[ -z "$PASSWORD" ]]; then read -r -s -p "ForgeTDM password: " PASSWORD; echo; fi',
    '  export USER_NAME PASSWORD',
    '  LOGIN_BODY="$(python3 - <<\'PY\'',
    'import json, os',
    'print(json.dumps({"username": os.environ["USER_NAME"], "password": os.environ["PASSWORD"]}))',
    'PY',
    ')"',
    '  curl -fsS -c "$COOKIE_JAR" -b "$COOKIE_JAR" -H "Content-Type: application/json" -d "$LOGIN_BODY" "$BASE_URL/api/auth/login" >/dev/null',
    'fi',
    '',
    'JOB_ID_ENC="$(python3 - "$JOB_ID" <<\'PY\'',
    'import sys, urllib.parse',
    'print(urllib.parse.quote(sys.argv[1], safe=""))',
    'PY',
    ')"',
    '',
    'echo "Launching ForgeTDM saved job $JOB_NAME ($JOB_ID)"',
    'curl -fsS -b "$COOKIE_JAR" "${AUTH_ARGS[@]}" -H "Content-Type: application/json" -X POST -d \'{}\' "$BASE_URL/api/synthetic/saved-jobs/$JOB_ID_ENC/run" > "$STATUS_FILE"',
    '',
    'RUN_ID="$(python3 - "$STATUS_FILE" <<\'PY\'',
    'import json, sys',
    'with open(sys.argv[1], encoding="utf-8") as f:',
    '    data = json.load(f)',
    'print(data.get("id", ""))',
    'PY',
    ')"',
    'if [[ -z "$RUN_ID" ]]; then echo "ForgeTDM did not return a synthetic run id." >&2; cat "$STATUS_FILE" >&2; exit 1; fi',
    '',
    'RUN_ID_ENC="$(python3 - "$RUN_ID" <<\'PY\'',
    'import sys, urllib.parse',
    'print(urllib.parse.quote(sys.argv[1], safe=""))',
    'PY',
    ')"',
    'echo "Synthetic run id: $RUN_ID"',
    '',
    'while true; do',
    '  sleep "$POLL_SECONDS"',
    '  curl -fsS -b "$COOKIE_JAR" "${AUTH_ARGS[@]}" "$BASE_URL/api/synthetic/jobs/$RUN_ID_ENC" > "$STATUS_FILE"',
    '  IFS=$\'\\t\' read -r STATUS PERCENT STAGE MESSAGE DETAIL ERROR < <(python3 - "$STATUS_FILE" <<\'PY\'',
    'import json, sys',
    'with open(sys.argv[1], encoding="utf-8") as f:',
    '    data = json.load(f)',
    'def clean(value):',
    '    return "" if value is None else str(value).replace("\\t", " ").replace("\\n", " ")',
    'fields = [data.get("status"), data.get("percent"), data.get("stage"), data.get("message"), data.get("detail"), data.get("error")]',
    'print("\\t".join(clean(v) for v in fields))',
    'PY',
    '  )',
    '  LINE="${PERCENT:-0}% ${STAGE:-Running} - ${MESSAGE:-Running}"',
    '  if [[ -n "${DETAIL:-}" ]]; then LINE="$LINE | $DETAIL"; fi',
    '  echo "$LINE"',
    '  case "$STATUS" in COMPLETED|FAILED|CANCELLED|CANCELED) break ;; esac',
    'done',
    '',
    'if [[ "$STATUS" == "FAILED" ]]; then echo "ForgeTDM job failed: ${ERROR:-$MESSAGE}" >&2; exit 1; fi',
    'if [[ "$STATUS" == "CANCELLED" || "$STATUS" == "CANCELED" ]]; then echo "ForgeTDM job was cancelled." >&2; exit 2; fi',
    '',
    'python3 - "$STATUS_FILE" "$OUTPUT_DIR" <<\'PY\'',
    'import json, os, pathlib, sys',
    'with open(sys.argv[1], encoding="utf-8") as f:',
    '    data = json.load(f)',
    'files = ((data.get("result") or {}).get("files") or [])',
    'if files:',
    '    out = pathlib.Path(sys.argv[2])',
    '    out.mkdir(parents=True, exist_ok=True)',
    '    for file in files:',
    '        name = os.path.basename(str(file.get("name") or "synthetic-output.txt")) or "synthetic-output.txt"',
    '        path = out / name',
    '        path.write_text(str(file.get("content") or ""), encoding="utf-8")',
    '        print(f"Saved file: {path}")',
    'PY',
    '',
    'echo "ForgeTDM saved job completed successfully."'
  ];
  return lines.join('\n') + '\n';
}

function synApplySavedPlan(plan) {
  synState.dataset = plan.dataset || 'synthetic';
  synState.seed = plan.seed ?? 42;
  synState.receiver = plan.receiver || 'DB';
  synState.targetDsId = plan.targetDataSourceId ? String(plan.targetDataSourceId) : '';
  synState.targetSchema = plan.targetSchema || '';
  synState.createTable = !!plan.createTable;
  synState.dropTable = !!plan.dropTable;
  synState.prepMode = plan.prepMode || 'APPEND';
  synState.loadAction = plan.loadAction || 'INSERT';
  synState.targetPrep = plan.targetPrep || 'NONE';
  synState.keyColumns = Array.isArray(plan.keyColumns) ? plan.keyColumns.join(',') : '';
  synState.batchSize = plan.batchSize || '';
  synState.commitEveryRows = plan.commitEveryRows || '';
  synState.continueOnError = !!plan.continueOnError;
  synState.maxRejects = plan.maxRejects == null ? '' : plan.maxRejects;
  synState.fastLoad = !!plan.fastLoad;
  synState.executionMode = plan.executionMode || 'SINGLE';
  synState.partitionCount = plan.partitionCount || '';
  synState.partitionSize = plan.partitionSize || '';
  synState.targetSystems = Array.isArray(plan.targetSystems) ? plan.targetSystems : [];
  synState.tables = (plan.tables || []).map(t => ({
    name: t.name || '',
    rowCount: t.rowCount ?? 100,
    columns: (t.columns || []).map(c => ({
      name: c.name || '',
      generator: c.generator || 'SEQUENCE',
      param1: c.param1 || '',
      param2: c.param2 || '',
      primaryKey: !!c.primaryKey,
      fkTable: c.fkTable || '',
      fkColumn: c.fkColumn || '',
      sqlType: c.sqlType || synSqlTypeForGenerator(c.generator || 'SEQUENCE'),
      open: false,
      typeLocked: false
    }))
  }));
  synState.autoBlueprint = false;
  synState.step = 3;
  synRender();
}

async function synGenerateWithGoal() {
  return synGenerateWithGame();
  const plan = collectSynPlan();
  if (!plan.tables.length) return toast('Add at least one table with fields', 'err');
  if (plan.receiver === 'DB' && !synHasDbTarget(plan)) return toast('Pick a target data source', 'err');

  const out = $('syn-result');
  const stages = synGenerationStages(plan);
  let progress = 8;
  let progressTimer = null;
  if (out) {
    out.classList.remove('empty');
    out.innerHTML = synGoalProgressHtml(progress, synStageForProgress(stages, progress).label, '', stages);
    progressTimer = setInterval(() => {
      progress = Math.min(94, progress + (progress < 45 ? 7 : progress < 75 ? 4 : 2));
      out.innerHTML = synGoalProgressHtml(progress, synStageForProgress(stages, progress).label, '', stages);
    }, 650);
  }

  try {
    let status = await api.post('/api/synthetic/generate/start', plan);
    if (!status || !status.id) throw new Error('Synthetic job did not return a job id');
    synRefreshSyntheticJobs(true);
    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
    while (!synJobDone(status.status)) {
      if (controller) controller.setProgress(status.percent || 1, status.message || status.stage || 'Running', status.stage, synProgressDetailText(status));
      await sleep(700);
      status = await api.get(`/api/synthetic/jobs/${encodeURIComponent(status.id)}`);
    }
    if (status.status === 'FAILED') throw new Error(status.error || status.message || 'Synthetic generation failed');
    if (status.status === 'CANCELLED') throw new Error('Synthetic generation cancelled');

    const r = status.result || {};
    const summary = synResultTableSummary(r);
    if (r.files && r.files.length) {
      synLastFiles = r.files;
      r.files.forEach(f => synDownload(f.name, f.content));
      if (out) out.innerHTML = `${synGoalProgressHtml(100, 'Goal! Files generated.', 'done', stages)}
        <p class="sub tight">Generated ${r.files.length} file(s) â€” downloads started.</p><p class="sub tight">${summary}</p>
        <ul>${r.files.map((f, i) => `<li><code>${esc(f.name)}</code> (${f.content.length} bytes) <button class="ghost small" onclick="synDownloadIdx(${i})">Download again</button></li>`).join('')}</ul>`;
    } else {
      if (out) out.innerHTML = `${synGoalProgressHtml(100, 'Goal! Loaded.', 'done', stages)}
        <p class="sub tight">Loaded into the target database.</p><p class="sub tight">${summary}</p>`;
      toast('Synthetic data loaded', 'ok');
    }
  } catch (e) {
    if (progressTimer) clearInterval(progressTimer);
    if (out) out.innerHTML = `${synGoalProgressHtml(Math.max(progress, 18), 'Generation failed.', 'error', stages)}<div class="error">${esc(e.message)}</div>`;
    toast(e.message, 'err');
  }
}

function synGameProgressMarkup(stages) {
  const chips = stages.length ? stages.map((s, i) =>
    `<span class="syn-stage ${i === 0 ? 'active' : ''}" data-syn-stage="${i}">${esc(s.label)}</span>`).join('') : '';
  return `<div class="syn-game-progress">
    <div class="syn-game-head"><b id="syn-game-status">Preparing target</b><span id="syn-game-percent">8%</span></div>
    <div class="syn-game-detail" id="syn-game-detail"></div>
    ${chips ? `<div class="syn-stage-row">${chips}</div>` : ''}
    <canvas class="syn-game-canvas" width="1200" height="220" role="img" aria-label="Synthetic data generation football progress"></canvas>
    <div class="syn-game-result"></div>
  </div>`;
}

// Freestyle tricks shown (at random) whenever the player is stuck. Each maps to a motion template
// (t) parameterised by height (h), side (s: 1 near / -1 rear / 0 alternate) and phase period (p).
const SYN_TRICKS = [
  { n: 'Keepie-uppies', t: 'foot', s: 0 }, { n: 'Low keepie-uppies', t: 'foot', s: 0, h: .6 },
  { n: 'High keepie-uppies', t: 'foot', s: 0, h: 1.4, p: 340 }, { n: 'Right-foot juggle', t: 'foot', s: 1 },
  { n: 'Left-foot juggle', t: 'foot', s: -1 }, { n: 'Fast juggle', t: 'foot', s: 0, p: 190 },
  { n: 'Slow juggle', t: 'foot', s: 0, p: 380, h: 1.2 }, { n: 'Inside juggle', t: 'foot', s: 0, h: .8 },
  { n: 'Outside juggle', t: 'foot', s: 1, h: .9 }, { n: 'Toe juggle', t: 'foot', s: 0, h: .7, p: 230 },
  { n: 'Toe taps', t: 'toe', p: 180 }, { n: 'Quick toe taps', t: 'toe', p: 140 }, { n: 'Sole taps', t: 'toe', p: 200, h: .8 },
  { n: 'Knee juggle', t: 'knee', s: 0 }, { n: 'Right knee', t: 'knee', s: 1 }, { n: 'Left knee', t: 'knee', s: -1 },
  { n: 'High knees', t: 'knee', s: 0, h: 1.3, p: 340 }, { n: 'Quick knees', t: 'knee', s: 0, p: 240 }, { n: 'Alternate knees', t: 'knee', s: 0 },
  { n: 'Header keep-ups', t: 'head' }, { n: 'Quick headers', t: 'head', p: 220 }, { n: 'High headers', t: 'head', h: 1.4, p: 360 },
  { n: 'Soft headers', t: 'head', h: .7 }, { n: 'Head nods', t: 'head', p: 200, h: .6 }, { n: 'Neck stall', t: 'head', h: .12, p: 460 },
  { n: 'Head stall', t: 'head', h: .2, p: 520 },
  { n: 'Chest juggle', t: 'chest' }, { n: 'Chest bounce', t: 'chest', p: 360, h: 1.2 }, { n: 'Soft chest', t: 'chest', h: .7 },
  { n: 'Shoulder juggle', t: 'shoulder', s: 1 }, { n: 'Left shoulder', t: 'shoulder', s: -1 }, { n: 'Quick shoulder', t: 'shoulder', s: 1, p: 240 },
  { n: 'Sole rolls', t: 'roll' }, { n: 'Crossover', t: 'roll', h: 1.2, p: 240 }, { n: 'Step-over', t: 'roll', h: 1.4, p: 260 },
  { n: 'Inside-outside', t: 'roll', p: 200 }, { n: 'Elastico', t: 'roll', h: 1.5, p: 220 }, { n: 'Flip-flap', t: 'roll', h: 1.3, p: 200 },
  { n: 'Drag-back', t: 'roll', h: .7, p: 300 }, { n: 'Scissors', t: 'roll', h: 1.4, p: 230 }, { n: 'La Croqueta', t: 'roll', h: 1.1, p: 210 },
  { n: 'V-pull', t: 'roll', h: .8, p: 280 }, { n: 'Body feint', t: 'roll', h: 1.2, p: 250 },
  { n: 'Around the World', t: 'atw', s: 1 }, { n: 'Reverse ATW', t: 'atw', s: -1 }, { n: 'Double ATW', t: 'atw', s: 1, p: 160 },
  { n: 'Slow ATW', t: 'atw', s: 1, p: 280 }, { n: 'Hocus pocus', t: 'atw', s: 1, p: 180 }, { n: 'Mitchy bounce', t: 'atw', s: -1, p: 200 },
  { n: 'Rainbow flick', t: 'arc' }, { n: 'Sombrero', t: 'arc', h: 1.4 }, { n: 'Lemmens', t: 'arc', h: 1.2, p: 1000 },
  { n: 'Around-the-head', t: 'arc', h: 1.1 }, { n: 'Over-the-top', t: 'arc', h: 1.3 }, { n: 'Sombrero pop', t: 'arc', h: 1.5, p: 900 },
  { n: 'Heel flicks', t: 'heel' }, { n: 'Back-heel', t: 'heel', p: 340 }, { n: 'Heel juggle', t: 'heel', p: 260, h: 1.1 }, { n: 'Around the back', t: 'heel', p: 320 },
  { n: 'Hop juggle', t: 'hop' }, { n: 'Double hop', t: 'hop', p: 240 }, { n: 'Bunny hops', t: 'hop', p: 200, h: 1.2 },
  { n: 'Foot stall', t: 'stallFoot' }, { n: 'V-stall', t: 'stallFoot' }, { n: 'Around-the-moon', t: 'stallFoot' },
  { n: 'Thigh stall', t: 'stallKnee' }, { n: 'Knee stall', t: 'stallKnee' }
];

function synStartGameProgress(out, stages) {
  out.classList.remove('empty');
  out.innerHTML = synGameProgressMarkup(stages);

  const wrap = out.querySelector('.syn-game-progress');
  const canvas = out.querySelector('.syn-game-canvas');
  const ctx = canvas && canvas.getContext ? canvas.getContext('2d') : null;
  const statusEl = out.querySelector('#syn-game-status');
  const percentEl = out.querySelector('#syn-game-percent');
  const detailEl = out.querySelector('#syn-game-detail');
  const resultEl = out.querySelector('.syn-game-result');
  const stageEls = Array.from(out.querySelectorAll('[data-syn-stage]'));
  if (!canvas || !ctx) return null;

  const W = 1200, H = 220, ground = 174;
  const PITCH_X = 18, PITCH_W = W - 36, CENTER_X = PITCH_X + PITCH_W / 2;
  const GOAL_X = W - 150;                 // left edge of the goal posts
  const RUN_START = 92, RUN_END = GOAL_X - 86;
  const BALL_GOAL_X = GOAL_X + 71;        // goal-mouth centre
  const state = {
    progress: 8,
    target: 8,
    message: 'Preparing target',
    detail: '',
    done: false,
    failed: false,
    kickStart: 0,
    finalText: 'LOADED',
    stageIndex: 0,
    lastTarget: 8,
    targetAt: performance.now(),
    juggling: false,
    wasJuggling: false,
    skillIndex: -1,
    raf: 0
  };
  const clamp = (v, min, max) => Math.max(min, Math.min(max, v));
  const easeOut = v => 1 - Math.pow(1 - clamp(v, 0, 1), 3);

  function setHiDpiCanvas() {
    const dpr = window.devicePixelRatio || 1;
    canvas.width = W * dpr;
    canvas.height = H * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  }

  function updateDom() {
    const pct = Math.round(state.target);
    const active = state.stageIndex == null ? synStageForProgress(stages, pct).index : state.stageIndex;
    if (statusEl) statusEl.textContent = state.message || synStageForProgress(stages, pct).label;
    if (percentEl) percentEl.textContent = `${pct}%`;
    if (detailEl) {
      detailEl.textContent = state.detail || '';
      detailEl.style.display = state.detail ? 'block' : 'none';
    }
    stageEls.forEach((el, i) => {
      el.classList.toggle('done', state.done || i < active);
      el.classList.toggle('active', !state.done && !state.failed && i === active);
    });
  }

  function roundedRect(x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.lineTo(x + w - r, y);
    ctx.quadraticCurveTo(x + w, y, x + w, y + r);
    ctx.lineTo(x + w, y + h - r);
    ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
    ctx.lineTo(x + r, y + h);
    ctx.quadraticCurveTo(x, y + h, x, y + h - r);
    ctx.lineTo(x, y + r);
    ctx.quadraticCurveTo(x, y, x + r, y);
  }

  function line(points, color, width) {
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';
    ctx.beginPath();
    ctx.moveTo(points[0][0], points[0][1]);
    for (let i = 1; i < points.length; i++) ctx.lineTo(points[i][0], points[i][1]);
    ctx.stroke();
  }

  function drawPitch() {
    const grass = ctx.createLinearGradient(0, 0, W, H);
    grass.addColorStop(0, '#1f8f55');
    grass.addColorStop(1, '#0f5f39');
    ctx.fillStyle = grass;
    roundedRect(0, 0, W, H, 16);
    ctx.fill();

    for (let x = -40; x < W; x += 92) {
      ctx.fillStyle = 'rgba(255,255,255,.045)';
      ctx.fillRect(x, 0, 46, H);
    }

    ctx.strokeStyle = 'rgba(255,255,255,.42)';
    ctx.lineWidth = 2;
    roundedRect(PITCH_X, 22, PITCH_W, 174, 10);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(CENTER_X, 22);
    ctx.lineTo(CENTER_X, 196);
    ctx.stroke();
    ctx.beginPath();
    ctx.arc(CENTER_X, 109, 32, 0, Math.PI * 2);
    ctx.stroke();
    ctx.strokeRect(GOAL_X - 32, 62, 116, 96);
    ctx.strokeRect(GOAL_X + 22, 86, 62, 48);

    const progressX = 48 + (state.progress / 100) * (GOAL_X - 48);
    const run = ctx.createLinearGradient(48, 0, progressX, 0);
    run.addColorStop(0, 'rgba(255,211,66,.28)');
    run.addColorStop(1, 'rgba(255,255,255,.10)');
    ctx.fillStyle = run;
    roundedRect(48, 156, Math.max(12, progressX - 48), 10, 999);
    ctx.fill();
  }

  function drawGoal(ts) {
    const x = GOAL_X, y = 50, w = 104, h = 122, d = 44;
    ctx.save();
    ctx.shadowColor = 'rgba(0,0,0,.25)';
    ctx.shadowBlur = 10;
    ctx.shadowOffsetY = 4;

    ctx.fillStyle = 'rgba(225,242,255,.18)';
    ctx.beginPath();
    ctx.moveTo(x + w, y);
    ctx.lineTo(x + w + d, y + 20);
    ctx.lineTo(x + w + d, y + h + 4);
    ctx.lineTo(x + w, y + h);
    ctx.closePath();
    ctx.fill();

    ctx.strokeStyle = 'rgba(255,255,255,.48)';
    ctx.lineWidth = 1;
    for (let i = 1; i < 8; i++) {
      const a = i / 8;
      ctx.beginPath();
      ctx.moveTo(x + w + d * a, y + 20 * a);
      ctx.lineTo(x + w + d * a, y + h + 4 * a);
      ctx.stroke();
    }
    for (let i = 1; i < 7; i++) {
      const yy = y + (h / 7) * i;
      ctx.beginPath();
      ctx.moveTo(x, yy);
      ctx.lineTo(x + w + d, yy + 12);
      ctx.stroke();
    }
    for (let i = 1; i < 9; i++) {
      const xx = x + (w / 9) * i;
      ctx.beginPath();
      ctx.moveTo(xx, y);
      ctx.lineTo(xx + d, y + 20);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(xx, y);
      ctx.lineTo(xx, y + h);
      ctx.stroke();
    }

    line([[x, y], [x + w, y], [x + w + d, y + 20]], '#f8fafc', 7);
    line([[x, y], [x, y + h]], '#f8fafc', 8);
    line([[x + w, y], [x + w, y + h]], '#e5edf6', 6);
    line([[x, y + h], [x + w, y + h], [x + w + d, y + h + 4]], '#d7e3ef', 5);
    line([[x + w + d, y + 20], [x + w + d, y + h + 4]], '#d7e3ef', 4);
    ctx.restore();

    if (state.done) {
      const kick = clamp((ts - state.kickStart) / 1200, 0, 1);
      if (kick > .7) {
        ctx.save();
        ctx.globalAlpha = easeOut((kick - .7) / .3);
        ctx.fillStyle = 'rgba(15,95,57,.80)';
        roundedRect(x + 30, 90, 86, 30, 8);
        ctx.fill();
        ctx.fillStyle = '#fff';
        ctx.font = '800 13px system-ui, Segoe UI, sans-serif';
        ctx.textAlign = 'center';
        ctx.fillText(state.finalText, x + 73, 110);
        ctx.restore();
      }
    }
  }

  function drawBall(cx, cy, r, spin) {
    ctx.save();
    ctx.fillStyle = 'rgba(0,0,0,.18)';
    ctx.beginPath();
    ctx.ellipse(cx + 2, cy + r + 5, r + 3, 4, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.translate(cx, cy);
    ctx.rotate(spin);
    ctx.fillStyle = '#f8fafc';
    ctx.strokeStyle = '#111827';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(0, 0, r, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = '#111827';
    ctx.beginPath();
    for (let i = 0; i < 5; i++) {
      const a = -Math.PI / 2 + i * Math.PI * 2 / 5;
      const rr = i === 0 ? r * .36 : r * .38;
      ctx.lineTo(Math.cos(a) * rr, Math.sin(a) * rr);
    }
    ctx.closePath();
    ctx.fill();
    for (let i = 0; i < 5; i++) {
      const a = -Math.PI / 2 + i * Math.PI * 2 / 5;
      ctx.beginPath();
      ctx.arc(Math.cos(a) * r * .72, Math.sin(a) * r * .72, r * .18, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.restore();
  }

  function drawPlayer(cx, ts) {
    const run = state.done || state.failed ? 0 : Math.sin(ts / 95);
    const counter = state.done || state.failed ? 0 : Math.cos(ts / 95);
    const kick = state.done ? Math.sin(clamp((ts - state.kickStart) / 620, 0, 1) * Math.PI) : 0;
    const bob = state.done || state.failed ? 0 : Math.abs(counter) * 2.2;
    const y = ground - bob;
    const skin = '#d69a63';
    const skinLight = 'rgba(255,214,170,.62)';
    const skinShade = '#9b5d34';
    const hair = '#21130d';
    const yellow = '#ffd23f';
    const yellowDark = '#c99600';
    const green = '#16823f';
    const greenDark = '#0b552a';
    const sock = '#f7fafc';

    const rearHip = [cx - 12, y - 58];
    const nearHip = [cx + 16, y - 57];
    const rearKnee = [cx - 29 - run * 10, y - 31 + counter * 2];
    const nearKnee = [cx + 33 + run * 7 + kick * 10, y - 31 - kick * 12];
    const rearAnkle = [cx - 20 - run * 18, y - 9];
    const nearAnkle = [cx + 44 + run * 10 + kick * 22, y - 10 - kick * 6];
    const rearFoot = [rearAnkle[0] + 9, y - 2];
    const rightFoot = [nearAnkle[0] + 17, nearAnkle[1] + 4];

    function kitLine(points, color, width, highlight) {
      line(points, 'rgba(0,0,0,.20)', width + 2);
      line(points, color, width);
      if (highlight) line(points, highlight, Math.max(2, width * .28));
    }

    function drawBoot(x, y0, angle, scale, alpha) {
      ctx.save();
      ctx.globalAlpha *= alpha == null ? 1 : alpha;
      ctx.translate(x, y0);
      ctx.rotate(angle);
      ctx.scale(scale || 1, scale || 1);
      ctx.fillStyle = '#101827';
      roundedRect(-12, -6, 29, 9, 5);
      ctx.fill();
      ctx.fillStyle = '#202c3b';
      roundedRect(-7, -8, 18, 6, 4);
      ctx.fill();
      ctx.strokeStyle = '#f8fafc';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(-5, -5);
      ctx.lineTo(8, -5);
      ctx.stroke();
      ctx.fillStyle = '#e11d48';
      ctx.beginPath();
      ctx.moveTo(10, 2);
      ctx.lineTo(19, 1);
      ctx.lineTo(14, 6);
      ctx.closePath();
      ctx.fill();
      ctx.restore();
    }

    function drawLeg(hip, knee, ankle, foot, alpha, isNear) {
      ctx.save();
      ctx.globalAlpha *= alpha;
      kitLine([hip, knee], skin, isNear ? 11 : 9, skinLight);
      kitLine([knee, ankle], sock, isNear ? 9 : 8, 'rgba(255,255,255,.75)');
      line([[knee[0] - 5, knee[1] - 1], [knee[0] + 5, knee[1] + 1]], skinShade, 2);
      line([[ankle[0] - 4, ankle[1] - 10], [ankle[0] + 5, ankle[1] - 9]], green, 3);
      drawBoot(foot[0], foot[1], isNear ? -.02 - kick * .2 : .03, isNear ? 1.05 : .92, 1);
      ctx.restore();
    }

    function drawArm(shoulder, elbow, hand, alpha, sleeveColor) {
      ctx.save();
      ctx.globalAlpha *= alpha;
      kitLine([shoulder, elbow], sleeveColor, 10, 'rgba(255,238,160,.62)');
      kitLine([elbow, hand], skin, 8, skinLight);
      ctx.fillStyle = skin;
      ctx.beginPath();
      ctx.arc(hand[0], hand[1], 4.2, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
    }

    ctx.save();
    ctx.globalAlpha = state.failed ? .78 : 1;

    ctx.fillStyle = 'rgba(0,0,0,.20)';
    ctx.beginPath();
    ctx.ellipse(cx + 16, ground + 4, 56, 8, 0, 0, Math.PI * 2);
    ctx.fill();

    drawArm([cx - 16, y - 101], [cx - 42 - run * 6, y - 86], [cx - 39 - run * 9, y - 68], .62, yellowDark);
    drawLeg(rearHip, rearKnee, rearAnkle, rearFoot, .72, false);

    const jersey = ctx.createLinearGradient(cx - 30, y - 116, cx + 40, y - 56);
    jersey.addColorStop(0, '#fff07d');
    jersey.addColorStop(.45, yellow);
    jersey.addColorStop(1, yellowDark);
    ctx.fillStyle = jersey;
    ctx.strokeStyle = '#a77a00';
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(cx - 29, y - 104);
    ctx.quadraticCurveTo(cx - 4, y - 120, cx + 31, y - 104);
    ctx.lineTo(cx + 39, y - 64);
    ctx.quadraticCurveTo(cx + 10, y - 51, cx - 27, y - 63);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();

    ctx.strokeStyle = 'rgba(167,122,0,.55)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(cx + 2, y - 110);
    ctx.lineTo(cx + 6, y - 58);
    ctx.moveTo(cx - 17, y - 88);
    ctx.quadraticCurveTo(cx + 8, y - 79, cx + 32, y - 88);
    ctx.stroke();

    ctx.fillStyle = '#fff9c4';
    ctx.font = '800 16px system-ui, Segoe UI, sans-serif';
    ctx.textAlign = 'center';
    ctx.fillText('10', cx + 6, y - 78);

    ctx.fillStyle = green;
    ctx.strokeStyle = greenDark;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(cx - 21, y - 62);
    ctx.lineTo(cx + 31, y - 62);
    ctx.lineTo(cx + 40, y - 43);
    ctx.lineTo(cx + 12, y - 38);
    ctx.lineTo(cx - 2, y - 47);
    ctx.lineTo(cx - 25, y - 39);
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    ctx.strokeStyle = 'rgba(255,255,255,.28)';
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.moveTo(cx + 5, y - 60);
    ctx.lineTo(cx + 7, y - 42);
    ctx.stroke();

    drawLeg(nearHip, nearKnee, nearAnkle, rightFoot, 1, true);
    drawArm([cx + 24, y - 99], [cx + 46 + run * 7, y - 84], [cx + 39 + run * 10, y - 66], 1, yellow);

    ctx.fillStyle = skin;
    roundedRect(cx - 4, y - 122, 13, 17, 5);
    ctx.fill();
    ctx.strokeStyle = skinShade;
    ctx.lineWidth = 1;
    ctx.stroke();

    const headX = cx + 7, headY = y - 134;
    ctx.fillStyle = skin;
    ctx.strokeStyle = skinShade;
    ctx.lineWidth = 1.5;
    ctx.beginPath();
    ctx.ellipse(headX, headY, 15, 18, .05, 0, Math.PI * 2);
    ctx.fill();
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(headX + 12, headY - 2);
    ctx.quadraticCurveTo(headX + 23, headY + 1, headX + 13, headY + 6);
    ctx.quadraticCurveTo(headX + 17, headY + 3, headX + 12, headY - 2);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = hair;
    ctx.beginPath();
    ctx.moveTo(headX - 15, headY - 8);
    ctx.quadraticCurveTo(headX - 8, headY - 25, headX + 13, headY - 18);
    ctx.quadraticCurveTo(headX + 20, headY - 10, headX + 7, headY - 7);
    ctx.quadraticCurveTo(headX - 4, headY - 14, headX - 15, headY - 8);
    ctx.fill();
    ctx.fillStyle = '#111827';
    ctx.beginPath();
    ctx.arc(headX + 8, headY - 2, 2, 0, Math.PI * 2);
    ctx.fill();
    ctx.strokeStyle = '#6f3f22';
    ctx.lineWidth = 1.8;
    ctx.beginPath();
    ctx.moveTo(headX + 5, headY + 8);
    ctx.quadraticCurveTo(headX + 12, headY + 12, headX + 18, headY + 7);
    ctx.stroke();
    ctx.fillStyle = skinShade;
    ctx.beginPath();
    ctx.arc(headX - 11, headY + 1, 3.5, 0, Math.PI * 2);
    ctx.fill();

    ctx.restore();
    return { rightFoot };
  }

  function drawPlayerGameAvatar(cx, ts) {
    const running = !state.done && !state.failed;
    const phase = ts / 112;
    const stride = running ? Math.sin(phase) : 0;
    const counter = running ? Math.cos(phase) : 0;
    const kickT = state.done ? clamp((ts - state.kickStart) / 720, 0, 1) : 0;
    const kick = Math.sin(kickT * Math.PI);
    const bob = running ? Math.abs(counter) * 2.4 : 0;
    const y = ground - bob;

    const skin = '#d99f6c';
    const skinEdge = '#8d5635';
    const skinHi = 'rgba(255,224,184,.75)';
    const hair = '#302015';
    const hairHi = '#7a654d';
    const kit = '#ffd53e';
    const kitHi = '#fff29a';
    const kitEdge = '#a97900';
    const green = '#117c3a';
    const greenDark = '#073d21';
    const sock = '#f7fafc';

    const rearShoulder = [cx - 24, y - 111];
    const nearShoulder = [cx + 27, y - 108];
    const rearHip = [cx - 15, y - 57];
    const nearHip = [cx + 18, y - 56];
    const rearKnee = [cx - 37 - stride * 12, y - 31 + counter * 2];
    const nearKnee = [cx + 33 + stride * 8 + kick * 11, y - 32 - kick * 12];
    const rearAnkle = [cx - 25 - stride * 20, y - 8];
    const nearAnkle = [cx + 45 + stride * 9 + kick * 27, y - 9 - kick * 5];
    const rearFoot = [rearAnkle[0] + 8, y - 1];
    const nearFoot = [nearAnkle[0] + 13 + kick * 7, nearAnkle[1] + 5];
    const ballContact = [nearFoot[0] + 21, nearFoot[1] - 3];

    function tapered(a, b, w1, w2, fill, edge, hi, alpha) {
      const dx = b[0] - a[0], dy = b[1] - a[1];
      const len = Math.max(1, Math.hypot(dx, dy));
      const nx = -dy / len, ny = dx / len;
      ctx.save();
      ctx.globalAlpha *= alpha == null ? 1 : alpha;
      ctx.fillStyle = fill;
      ctx.strokeStyle = edge || 'rgba(0,0,0,.22)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(a[0] + nx * w1, a[1] + ny * w1);
      ctx.quadraticCurveTo((a[0] + b[0]) / 2 + nx * Math.max(w1, w2) * .45, (a[1] + b[1]) / 2 + ny * Math.max(w1, w2) * .45, b[0] + nx * w2, b[1] + ny * w2);
      ctx.quadraticCurveTo(b[0], b[1], b[0] - nx * w2, b[1] - ny * w2);
      ctx.quadraticCurveTo((a[0] + b[0]) / 2 - nx * Math.max(w1, w2) * .35, (a[1] + b[1]) / 2 - ny * Math.max(w1, w2) * .35, a[0] - nx * w1, a[1] - ny * w1);
      ctx.quadraticCurveTo(a[0], a[1], a[0] + nx * w1, a[1] + ny * w1);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      if (hi) {
        ctx.strokeStyle = hi;
        ctx.lineWidth = Math.max(1.2, Math.min(w1, w2) * .28);
        ctx.beginPath();
        ctx.moveTo(a[0] + nx * w1 * .35, a[1] + ny * w1 * .35);
        ctx.lineTo(b[0] + nx * w2 * .35, b[1] + ny * w2 * .35);
        ctx.stroke();
      }
      ctx.restore();
    }

    function joint(p, r, fill, alpha) {
      ctx.save();
      ctx.globalAlpha *= alpha == null ? 1 : alpha;
      ctx.fillStyle = fill;
      ctx.strokeStyle = 'rgba(0,0,0,.22)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.ellipse(p[0], p[1], r * 1.05, r * .82, -.2, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.restore();
    }

    function drawBoot(p, angle, scale, alpha) {
      ctx.save();
      ctx.globalAlpha *= alpha == null ? 1 : alpha;
      ctx.translate(p[0], p[1]);
      ctx.rotate(angle);
      ctx.scale(scale || 1, scale || 1);
      const bootGrad = ctx.createLinearGradient(-15, -8, 24, 7);
      bootGrad.addColorStop(0, '#0c1220');
      bootGrad.addColorStop(.55, '#1d293a');
      bootGrad.addColorStop(1, '#f97316');
      ctx.fillStyle = bootGrad;
      ctx.strokeStyle = '#050816';
      ctx.lineWidth = 1.4;
      ctx.beginPath();
      ctx.moveTo(-15, -5);
      ctx.quadraticCurveTo(-3, -12, 12, -7);
      ctx.quadraticCurveTo(23, -3, 26, 2);
      ctx.lineTo(12, 7);
      ctx.lineTo(-13, 5);
      ctx.quadraticCurveTo(-18, 1, -15, -5);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      ctx.strokeStyle = 'rgba(255,255,255,.82)';
      ctx.lineWidth = 1.1;
      for (let i = 0; i < 3; i++) {
        ctx.beginPath();
        ctx.moveTo(-4 + i * 5, -6);
        ctx.lineTo(1 + i * 5, -2);
        ctx.stroke();
      }
      ctx.fillStyle = '#facc15';
      for (let i = -7; i <= 9; i += 8) {
        ctx.beginPath();
        ctx.ellipse(i, 8, 2.3, 1.5, 0, 0, Math.PI * 2);
        ctx.fill();
      }
      ctx.restore();
    }

    function drawHand(p, angle, scale, alpha) {
      ctx.save();
      ctx.globalAlpha *= alpha == null ? 1 : alpha;
      ctx.translate(p[0], p[1]);
      ctx.rotate(angle);
      ctx.scale(scale || 1, scale || 1);
      ctx.fillStyle = skin;
      ctx.strokeStyle = skinEdge;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.ellipse(0, 0, 5.2, 3.8, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      for (let i = -2; i <= 2; i += 2) {
        ctx.beginPath();
        ctx.moveTo(3, i * .7);
        ctx.lineTo(10, i);
        ctx.stroke();
      }
      ctx.restore();
    }

    function drawTorso() {
      const jersey = ctx.createLinearGradient(cx - 34, y - 124, cx + 45, y - 56);
      jersey.addColorStop(0, kitHi);
      jersey.addColorStop(.42, kit);
      jersey.addColorStop(1, '#d39b07');
      ctx.save();
      ctx.fillStyle = jersey;
      ctx.strokeStyle = kitEdge;
      ctx.lineWidth = 1.8;
      ctx.beginPath();
      ctx.moveTo(cx - 31, y - 102);
      ctx.quadraticCurveTo(cx - 10, y - 122, cx + 31, y - 107);
      ctx.quadraticCurveTo(cx + 43, y - 88, cx + 41, y - 66);
      ctx.quadraticCurveTo(cx + 12, y - 50, cx - 27, y - 63);
      ctx.quadraticCurveTo(cx - 35, y - 82, cx - 31, y - 102);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      ctx.clip();

      ctx.fillStyle = 'rgba(17,124,58,.24)';
      for (let x = cx - 58; x < cx + 56; x += 20) {
        ctx.beginPath();
        ctx.moveTo(x, y - 124);
        ctx.lineTo(x + 14, y - 124);
        ctx.lineTo(x + 58, y - 50);
        ctx.lineTo(x + 44, y - 50);
        ctx.closePath();
        ctx.fill();
      }
      ctx.strokeStyle = 'rgba(255,255,255,.23)';
      ctx.lineWidth = 1;
      for (let x = cx - 18; x <= cx + 30; x += 8) {
        ctx.beginPath();
        ctx.moveTo(x, y - 108);
        ctx.quadraticCurveTo(x + 4, y - 86, x + 1, y - 62);
        ctx.stroke();
      }
      ctx.fillStyle = 'rgba(81,52,0,.16)';
      ctx.beginPath();
      ctx.ellipse(cx + 16, y - 79, 29, 8, -.15, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();

      ctx.strokeStyle = 'rgba(255,255,255,.82)';
      ctx.lineWidth = 2.2;
      ctx.beginPath();
      ctx.moveTo(cx - 3, y - 111);
      ctx.lineTo(cx + 8, y - 100);
      ctx.lineTo(cx + 18, y - 111);
      ctx.stroke();
      ctx.fillStyle = '#fff8c5';
      ctx.font = '800 18px system-ui, Segoe UI, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('9', cx + 9, y - 78);
    }

    function drawShorts() {
      const shorts = ctx.createLinearGradient(cx - 24, y - 65, cx + 42, y - 36);
      shorts.addColorStop(0, '#1f9a4e');
      shorts.addColorStop(1, greenDark);
      ctx.fillStyle = shorts;
      ctx.strokeStyle = greenDark;
      ctx.lineWidth = 1.8;
      ctx.beginPath();
      ctx.moveTo(cx - 24, y - 62);
      ctx.lineTo(cx + 34, y - 63);
      ctx.lineTo(cx + 42, y - 43);
      ctx.lineTo(cx + 14, y - 37);
      ctx.lineTo(cx + 2, y - 48);
      ctx.lineTo(cx - 25, y - 38);
      ctx.closePath();
      ctx.fill();
      ctx.stroke();
      ctx.strokeStyle = 'rgba(255,255,255,.25)';
      ctx.lineWidth = 1.2;
      ctx.beginPath();
      ctx.moveTo(cx + 5, y - 61);
      ctx.lineTo(cx + 7, y - 41);
      ctx.stroke();
    }

    function drawHeadAndNeck() {
      const neck = ctx.createLinearGradient(cx, y - 126, cx + 16, y - 105);
      neck.addColorStop(0, skinHi);
      neck.addColorStop(1, skin);
      ctx.fillStyle = neck;
      roundedRect(cx - 2, y - 124, 15, 19, 5);
      ctx.fill();
      ctx.strokeStyle = skinEdge;
      ctx.lineWidth = 1;
      ctx.stroke();

      const headX = cx + 10, headY = y - 137;
      const face = ctx.createRadialGradient(headX + 3, headY - 7, 4, headX, headY, 22);
      face.addColorStop(0, '#ffd9b3');
      face.addColorStop(.48, skin);
      face.addColorStop(1, '#b9774b');
      ctx.fillStyle = face;
      ctx.strokeStyle = skinEdge;
      ctx.lineWidth = 1.3;
      ctx.beginPath();
      ctx.ellipse(headX, headY, 15.5, 19.5, .08, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(headX + 11, headY - 3);
      ctx.quadraticCurveTo(headX + 23, headY, headX + 13, headY + 6);
      ctx.quadraticCurveTo(headX + 18, headY + 2, headX + 11, headY - 3);
      ctx.fill();
      ctx.stroke();

      const hairGrad = ctx.createLinearGradient(headX - 15, headY - 23, headX + 17, headY - 5);
      hairGrad.addColorStop(0, hairHi);
      hairGrad.addColorStop(.7, hair);
      ctx.fillStyle = hairGrad;
      ctx.beginPath();
      ctx.moveTo(headX - 15, headY - 9);
      ctx.quadraticCurveTo(headX - 7, headY - 28, headX + 16, headY - 18);
      ctx.quadraticCurveTo(headX + 21, headY - 10, headX + 7, headY - 7);
      ctx.quadraticCurveTo(headX - 3, headY - 14, headX - 15, headY - 9);
      ctx.fill();
      ctx.strokeStyle = 'rgba(255,255,255,.18)';
      ctx.lineWidth = 1;
      for (let i = -10; i <= 8; i += 4) {
        ctx.beginPath();
        ctx.moveTo(headX + i, headY - 18 + Math.abs(i) * .25);
        ctx.quadraticCurveTo(headX + i + 9, headY - 18, headX + i + 14, headY - 8);
        ctx.stroke();
      }
      ctx.fillStyle = '#111827';
      ctx.beginPath();
      ctx.arc(headX + 8, headY - 3, 2.1, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#6b3b22';
      ctx.lineWidth = 1.7;
      ctx.beginPath();
      ctx.moveTo(headX + 5, headY + 8);
      ctx.quadraticCurveTo(headX + 13, headY + 12, headX + 19, headY + 7);
      ctx.stroke();
    }

    ctx.save();
    ctx.globalAlpha = state.failed ? .78 : 1;
    ctx.fillStyle = 'rgba(0,0,0,.24)';
    ctx.beginPath();
    ctx.ellipse(cx + 18, ground + 5, 62, 9, 0, 0, Math.PI * 2);
    ctx.fill();

    const rearElbow = [cx - 49 - stride * 6, y - 88];
    const rearHand = [cx - 43 - stride * 9, y - 69];
    tapered(rearShoulder, rearElbow, 6.8, 5.3, '#d9a80a', kitEdge, 'rgba(255,255,255,.35)', .62);
    tapered(rearElbow, rearHand, 5.1, 3.7, skin, skinEdge, skinHi, .62);
    drawHand(rearHand, .2, .86, .62);

    tapered(rearHip, rearKnee, 8, 6.4, skin, skinEdge, skinHi, .7);
    joint(rearKnee, 5.3, skin, .7);
    tapered(rearKnee, rearAnkle, 6.1, 4.7, sock, 'rgba(85,85,85,.35)', 'rgba(255,255,255,.7)', .7);
    tapered([rearAnkle[0] - 2, rearAnkle[1] - 7], [rearAnkle[0] + 6, rearAnkle[1] - 8], 2.2, 2.1, green, greenDark, null, .75);
    drawBoot(rearFoot, .02, .92, .72);

    drawTorso();
    drawShorts();

    tapered(nearHip, nearKnee, 9.5, 7.2, skin, skinEdge, skinHi, 1);
    joint(nearKnee, 6.1, skin, 1);
    tapered(nearKnee, nearAnkle, 7, 5, sock, 'rgba(85,85,85,.38)', 'rgba(255,255,255,.78)', 1);
    tapered([nearAnkle[0] - 3, nearAnkle[1] - 8], [nearAnkle[0] + 7, nearAnkle[1] - 9], 2.3, 2.2, green, greenDark, null, 1);
    drawBoot(nearFoot, -.04 - kick * .22, 1.08, 1);

    const nearElbow = [cx + 52 + stride * 5, y - 87 - kick * 2];
    const nearHand = [cx + 52 + stride * 8, y - 65 - kick * 4];
    tapered(nearShoulder, nearElbow, 7.2, 5.4, kit, kitEdge, 'rgba(255,255,255,.42)', 1);
    tapered(nearElbow, nearHand, 5.4, 3.8, skin, skinEdge, skinHi, 1);
    drawHand(nearHand, -.1, 1, 1);

    drawHeadAndNeck();
    ctx.restore();
    return { rightFoot: ballContact };
  }

  // FIFA loading-screen style: a tall dark player silhouette with curly hair, running boots,
  // a proper sprint cycle (opposite arm/leg swing, knee lift, push-off, forward lean) + rim light.
  function drawSilhouettePlayer(cx, ts) {
    const juggling = !state.done && !state.failed && state.juggling;
    const running = !state.done && !state.failed && !juggling;
    const skill = juggling ? (((state.skillIndex % SYN_TRICKS.length) + SYN_TRICKS.length) % SYN_TRICKS.length) : -1;
    const y0 = ground;
    const sp = ts / 96;                         // stride speed
    const sw = running ? Math.sin(sp) : 0;
    const co = running ? Math.cos(sp) : 0;
    const liftF = running ? Math.max(0, sw) : 0;   // front knee lift
    const liftB = running ? Math.max(0, -sw) : 0;  // rear knee lift
    const kickT = state.done ? clamp((ts - state.kickStart) / 720, 0, 1) : 0;
    const kick = Math.sin(kickT * Math.PI);
    const bob = juggling ? Math.abs(Math.sin(ts / 300)) * 1.3 : (running ? Math.abs(co) * 2.2 : 0);
    const SC = 0.72;                                         // keep the player shorter than the goal posts
    const trk = juggling ? (SYN_TRICKS[skill] || SYN_TRICKS[0]) : null;
    let jlean = 0.03;                                        // most tricks: head down, slightly forward
    if (trk) {
      if (trk.t === 'head') jlean = -0.03;                  // look up
      else if (trk.t === 'chest' || trk.t === 'stallFoot' || trk.t === 'stallKnee') jlean = -0.05;  // lean back for balance
      else if (trk.t === 'arc' || trk.t === 'heel') jlean = 0.06;  // lean forward
    }
    const lean = juggling ? jlean : (running ? 0.08 : (state.done ? 0.05 : 0));  // lean forward, toward the goal

    // transform design-space point: scale + forward lean about the feet, plus vertical bob
    const T = p => {
      let x = cx + (p[0] - cx) * SC, yy = y0 + (p[1] - y0) * SC;
      const dx = x - cx, dy = yy - y0;
      x = cx + dx * Math.cos(lean) - dy * Math.sin(lean);
      yy = y0 + dx * Math.sin(lean) + dy * Math.cos(lean);
      return [x, yy - bob * SC];
    };

    // joints (design space, baseline = ground)
    let hipN, hipR, kneeN, kneeR, ankN, ankR, shN, shR, elbN, handN, elbR, handR, headC;
    if (juggling) {
      // defaults: planted feet, hands by the sides (no hand movement), head down
      hipN = [cx + 13, y0 - 57]; hipR = [cx - 13, y0 - 58];
      kneeN = [cx + 16, y0 - 33]; kneeR = [cx - 16, y0 - 33];
      ankN = [cx + 16, y0 - 8]; ankR = [cx - 16, y0 - 8];
      shN = [cx + 20, y0 - 108]; shR = [cx - 16, y0 - 110];
      elbN = [cx + 23, y0 - 84]; handN = [cx + 19, y0 - 62];
      elbR = [cx - 21, y0 - 84]; handR = [cx - 17, y0 - 62];
      headC = [cx + 5, y0 - 127];
      const armsOut = () => { elbN = [cx + 27, y0 - 92]; handN = [cx + 30, y0 - 76]; elbR = [cx - 25, y0 - 92]; handR = [cx - 28, y0 - 76]; };
      const tr = SYN_TRICKS[skill] || SYN_TRICKS[0];
      const h = tr.h == null ? 1 : tr.h, side = tr.s == null ? 0 : tr.s, per = tr.p || 280;
      const S1 = Math.sin(ts / per);
      switch (tr.t) {
        case 'foot':
          if (side === 0) { const n = Math.max(0, S1), r = Math.max(0, -S1);
            kneeN = [cx + 16, y0 - 33 - n * 13 * h]; kneeR = [cx - 16, y0 - 33 - r * 13 * h];
            ankN = [cx + 16, y0 - 8 - n * 30 * h]; ankR = [cx - 16, y0 - 8 - r * 30 * h];
          } else { const a = Math.abs(S1);
            if (side > 0) { kneeN = [cx + 16, y0 - 33 - a * 16 * h]; ankN = [cx + 16, y0 - 8 - a * 34 * h]; }
            else { kneeR = [cx - 16, y0 - 33 - a * 16 * h]; ankR = [cx - 16, y0 - 8 - a * 34 * h]; } }
          break;
        case 'toe': { const n = Math.max(0, S1), r = Math.max(0, -S1); ankN = [cx + 16, y0 - 8 - n * 9 * h]; ankR = [cx - 16, y0 - 8 - r * 9 * h]; break; }
        case 'knee':
          if (side === 0) { const n = Math.max(0, S1), r = Math.max(0, -S1);
            kneeN = [cx + 13, y0 - 33 - n * 30 * h]; kneeR = [cx - 13, y0 - 33 - r * 30 * h];
            ankN = [cx + 15, y0 - 18 - n * 22 * h]; ankR = [cx - 15, y0 - 18 - r * 22 * h];
          } else { const a = Math.abs(S1);
            if (side > 0) { kneeN = [cx + 13, y0 - 33 - a * 30 * h]; ankN = [cx + 15, y0 - 18 - a * 22 * h]; }
            else { kneeR = [cx - 13, y0 - 33 - a * 30 * h]; ankR = [cx - 15, y0 - 18 - a * 22 * h]; } }
          break;
        case 'head': { const hb = Math.abs(S1); kneeN = [cx + 16, y0 - 31]; kneeR = [cx - 16, y0 - 31]; headC = [cx + 7, y0 - 136 - (1 - hb) * 8 * Math.max(.3, h)]; armsOut(); break; }
        case 'chest': headC = [cx + 5, y0 - 124]; armsOut(); break;
        case 'shoulder':
          if (side < 0) { headC = [cx - 5, y0 - 129]; elbR = [cx - 27, y0 - 94]; handR = [cx - 26, y0 - 78]; }
          else { headC = [cx + 9, y0 - 129]; elbN = [cx + 27, y0 - 94]; handN = [cx + 26, y0 - 78]; }
          break;
        case 'roll': ankN = [cx + S1 * 20 * h, y0 - 9]; kneeN = [cx + S1 * 8 * h, y0 - 34]; break;
        case 'atw': { const a = (ts / per) * (side < 0 ? -1 : 1); ankN = [cx + 10 + Math.cos(a) * 16, y0 - 14 + Math.sin(a) * 14]; kneeN = [cx + 17, y0 - 34]; break; }
        case 'arc': { const a = (ts / (tr.p || 900)) % 1; ankR = [cx - 20 + a * 8, y0 - 10 - Math.sin(a * Math.PI) * 10]; kneeR = [cx - 14, y0 - 36]; headC = [cx + 8, y0 - 130]; break; }
        case 'heel': { const t = Math.abs(S1); ankN = [cx - 4 - t * 10, y0 - 10 - t * 22 * h]; kneeN = [cx + 6, y0 - 34]; break; }
        case 'hop': { const hp = Math.abs(S1); kneeN = [cx + 16, y0 - 33 - hp * 14 * h]; kneeR = [cx - 16, y0 - 33 - hp * 14 * h]; ankN = [cx + 16, y0 - 8 - hp * 16 * h]; ankR = [cx - 16, y0 - 8 - hp * 16 * h]; break; }
        case 'stallFoot': ankN = [cx + 18, y0 - 30]; kneeN = [cx + 16, y0 - 44]; armsOut(); break;
        case 'stallKnee': kneeN = [cx + 12, y0 - 48]; ankN = [cx + 16, y0 - 30]; armsOut(); break;
      }
    } else {
      hipN = [cx + 15, y0 - 57]; hipR = [cx - 13, y0 - 58];
      kneeN = [cx + 19 + sw * 14, y0 - 33 - liftF * 20 - kick * 10]; kneeR = [cx - 17 - sw * 14, y0 - 33 - liftB * 20];
      ankN = [cx + 24 + sw * 30 + kick * 26, y0 - 8 - liftF * 14 - kick * 8]; ankR = [cx - 22 - sw * 30, y0 - 8 - liftB * 14];
      shN = [cx + 22, y0 - 108]; shR = [cx - 18, y0 - 110];
      elbN = [cx + 30 - sw * 16, y0 - 86]; handN = [cx + 24 - sw * 24, y0 - 66];
      elbR = [cx - 26 + sw * 16, y0 - 86]; handR = [cx - 20 + sw * 24, y0 - 66];
      headC = [cx + 8, y0 - 134];
    }

    const gT = T([cx, y0 - 152]), gB = T([cx, y0 - 28]);
    const grad = ctx.createLinearGradient(gT[0], gT[1], gB[0], gB[1]);
    grad.addColorStop(0, '#2a374c'); grad.addColorStop(1, '#070b12');
    const rim = 'rgba(125,205,255,.6)';
    const limb = (a, b, w) => { const A = T(a), B = T(b); ctx.lineWidth = w * SC; ctx.beginPath(); ctx.moveTo(A[0], A[1]); ctx.lineTo(B[0], B[1]); ctx.stroke(); };
    const fillPoly = pts => { const P = pts.map(T); ctx.beginPath(); ctx.moveTo(P[0][0], P[0][1]); for (let i = 1; i < P.length; i++) ctx.lineTo(P[i][0], P[i][1]); ctx.closePath(); ctx.fill(); };
    // sleek running boot pointing in the run direction (toe-down when pushing off)
    const boot = (ank, push) => {
      const yd = push ? 3 : 0;
      fillPoly([[ank[0] - 7, ank[1] - 3], [ank[0] + 2, ank[1] - 5], [ank[0] + 17, ank[1] - 1 + yd], [ank[0] + 19, ank[1] + 4 + yd], [ank[0] - 6, ank[1] + 4]]);
      ctx.save(); ctx.strokeStyle = rim; ctx.lineWidth = 1.4 * SC;
      const s1 = T([ank[0] - 6, ank[1] + 4]), s2 = T([ank[0] + 19, ank[1] + 4 + yd]);
      ctx.beginPath(); ctx.moveTo(s1[0], s1[1]); ctx.lineTo(s2[0], s2[1]); ctx.stroke(); ctx.restore();
    };

    // shadow (unscaled, on the ground)
    ctx.save();
    ctx.fillStyle = 'rgba(0,0,0,.26)';
    ctx.beginPath(); ctx.ellipse(cx + 10, ground + 5, 52, 8, 0, 0, Math.PI * 2); ctx.fill();
    ctx.restore();

    ctx.save();
    ctx.globalAlpha = state.failed ? .8 : 1;
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    ctx.fillStyle = grad; ctx.strokeStyle = grad;

    // rear arm + leg (behind)
    limb(shR, elbR, 11); limb(elbR, handR, 8);
    limb(hipR, kneeR, 15); limb(kneeR, ankR, 11); boot(ankR, liftB < .15);

    // torso
    fillPoly([[cx - 22, y0 - 108], [cx + 4, y0 - 116], [cx + 24, y0 - 108], [cx + 26, y0 - 80], [cx + 22, y0 - 56], [cx - 2, y0 - 50], [cx - 24, y0 - 56], [cx - 27, y0 - 82]]);

    // near leg + arm (front)
    limb(hipN, kneeN, 16); limb(kneeN, ankN, 12); boot(ankN, false);
    limb(shN, elbN, 12); limb(elbN, handN, 9);

    // neck + head
    limb([cx + 4, y0 - 112], [headC[0], headC[1] + 12], 10);
    ctx.beginPath(); const hc = T(headC); ctx.arc(hc[0], hc[1], 14 * SC, 0, Math.PI * 2); ctx.fill();
    // curly hair — a cluster of bumps over the crown
    [[-11, -9, 7], [-4, -15, 8], [5, -16, 8], [13, -9, 7], [-13, -1, 5], [11, 0, 5], [0, -19, 6], [-7, -17, 6]].forEach(c => {
      const P = T([headC[0] + c[0], headC[1] + c[1]]); ctx.beginPath(); ctx.arc(P[0], P[1], c[2] * SC, 0, Math.PI * 2); ctx.fill();
    });
    // nose — points toward the goal so the player clearly faces it
    const nz = T([headC[0] + 14, headC[1] + 2]);
    ctx.beginPath(); ctx.moveTo(nz[0], nz[1] - 3 * SC); ctx.lineTo(nz[0] + 5 * SC, nz[1]); ctx.lineTo(nz[0], nz[1] + 3 * SC); ctx.closePath(); ctx.fill();

    // cool rim light along the leading edge + crown
    ctx.strokeStyle = rim; ctx.lineWidth = 2 * SC;
    const r1 = T([cx + 24, y0 - 108]), r2 = T([cx + 26, y0 - 80]), r3 = T([cx + 22, y0 - 56]);
    ctx.beginPath(); ctx.moveTo(r1[0], r1[1]); ctx.quadraticCurveTo(r2[0], r2[1], r3[0], r3[1]); ctx.stroke();
    ctx.beginPath(); ctx.arc(hc[0], hc[1], 13 * SC, -1.2, .35); ctx.stroke();
    ctx.restore();

    return {
      rightFoot: T([ankN[0] + 15, ankN[1] + 1]),
      head: hc, headR: 14 * SC,
      nearAnk: T(ankN), nearKnee: T(kneeN), shoulder: T(shN), chest: T([cx + 4, y0 - 90])
    };
  }

  function draw(ts) {
    state.progress += (state.target - state.progress) * .075;
    // stuck on the same % for a moment (and the runner has caught up) → keep-ups instead of standing idle
    const stalledMs = performance.now() - (state.targetAt || 0);
    state.juggling = !state.done && !state.failed && stalledMs > 1300 && state.progress >= state.target - 1.2;
    if (state.juggling && !state.wasJuggling) {            // new stall → a random trick (not the same one twice in a row)
      let idx; do { idx = Math.floor(Math.random() * SYN_TRICKS.length); } while (SYN_TRICKS.length > 1 && idx === state.skillIndex);
      state.skillIndex = idx;
    }
    state.wasJuggling = state.juggling;

    ctx.clearRect(0, 0, W, H);
    drawPitch();
    drawGoal(ts);

    const p = clamp(state.progress / 100, 0, 1);
    const cx = RUN_START + p * (RUN_END - RUN_START);
    const avatar = drawSilhouettePlayer(cx, ts);
    const foot = avatar.rightFoot;
    const spin = ts / 105 + state.progress / 10;
    if (state.done) {
      const k = easeOut((ts - state.kickStart) / 950);
      const startX = foot[0] + 6, startY = foot[1] - 8;
      const bx = startX + (BALL_GOAL_X - startX) * k;
      const by = startY + (112 - startY) * k - Math.sin(k * Math.PI) * 50;
      drawBall(bx, by, 9 * (1 - k * .18), spin + k * 7);
    } else if (state.juggling) {
      const skill = (((state.skillIndex % SYN_TRICKS.length) + SYN_TRICKS.length) % SYN_TRICKS.length);
      const tr = SYN_TRICKS[skill] || SYN_TRICKS[0];
      const h = tr.h == null ? 1 : tr.h, side = tr.s == null ? 0 : tr.s, per = tr.p || 280;
      const ph = ts / per, A = Math.abs(Math.sin(ph)), S1 = Math.sin(ph);
      const footY = avatar.nearAnk[1], kneeY = avatar.nearKnee[1], headTop = avatar.head[1] - avatar.headR, shY = avatar.shoulder[1], chY = avatar.chest[1];
      let bx = cx + 2, by = footY - 14;
      switch (tr.t) {
        case 'foot': bx = cx + (side > 0 ? 6 : side < 0 ? -6 : 2); by = footY - 12 - A * 44 * h; break;
        case 'toe': bx = cx + 2; by = footY - 8 - A * 10 * h; break;
        case 'knee': bx = cx + S1 * 12; by = kneeY - 6 - A * 22 * h; break;
        case 'head': bx = avatar.head[0]; by = headTop - 4 - A * 30 * Math.max(.25, h); break;
        case 'chest': bx = cx + 4; by = chY - 10 - A * 26 * h; break;
        case 'shoulder': bx = avatar.shoulder[0] + (side < 0 ? -6 : 6); by = shY - 8 - A * 22; break;
        case 'roll': bx = cx + S1 * 22 * h; by = footY - 2; break;
        case 'atw': bx = cx + 10; by = footY - 8 - A * 6; break;
        case 'arc': { const a = (ts / (tr.p || 900)) % 1; bx = cx - 22 + a * 54; by = footY - 18 - Math.sin(a * Math.PI) * 110 * h; break; }
        case 'heel': bx = cx - 10; by = footY - 12 - A * 30 * h; break;
        case 'hop': bx = cx + 2; by = footY - 14 - A * 40 * h; break;
        case 'stallFoot': { const w = Math.sin(ts / 400) * 2; bx = avatar.nearAnk[0] + 8; by = avatar.nearAnk[1] - 9 + w; break; }
        case 'stallKnee': { const w = Math.sin(ts / 400) * 2; bx = avatar.nearKnee[0] + 4; by = avatar.nearKnee[1] - 9 + w; break; }
      }
      drawBall(bx, by, 8, spin);
      ctx.save();
      ctx.fillStyle = 'rgba(255,255,255,.92)';
      ctx.font = '700 13px system-ui, Segoe UI, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(tr.n, clamp(cx, 70, W - 70), ground + 26);
      ctx.restore();
    } else {
      const bounce = state.failed ? 0 : Math.abs(Math.sin(ts / 110)) * 3;
      drawBall(foot[0] + 6, foot[1] - 8 - bounce, 9, spin);
    }

    if (state.failed) {
      ctx.save();
      ctx.fillStyle = 'rgba(127,29,29,.78)';
      roundedRect(CENTER_X - 130, 88, 260, 36, 10);
      ctx.fill();
      ctx.fillStyle = '#fff';
      ctx.font = '800 14px system-ui, Segoe UI, sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText('Generation failed', CENTER_X, 111);
      ctx.restore();
    }
  }

  function loop(ts) {
    draw(ts);
    if (!state.failed && (!state.done || ts - state.kickStart < 2200)) {
      state.raf = requestAnimationFrame(loop);
    }
  }

  setHiDpiCanvas();
  updateDom();
  state.raf = requestAnimationFrame(loop);

  return {
    setProgress(percent, message, stage, detail) {
      const p = clamp(percent, 0, 99);
      if (p > state.lastTarget + 0.4) { state.lastTarget = p; state.targetAt = performance.now(); }  // progress moved → reset the stall timer
      state.target = p;
      state.message = message || synStageForProgress(stages, percent).label;
      state.detail = detail == null ? '' : String(detail);
      state.stageIndex = synStageIndexFor(stages, stage, state.message, percent);
      updateDom();
    },
    finish(message) {
      state.target = 100;
      state.message = message || 'Goal! Loaded.';
      state.detail = '';
      state.done = true;
      state.failed = false;
      state.stageIndex = stages && stages.length ? stages.length - 1 : 0;
      state.kickStart = performance.now();
      state.finalText = /file|generated/i.test(state.message) ? 'GENERATED' : 'LOADED';
      if (wrap) wrap.classList.add('done');
      updateDom();
      if (!state.raf) state.raf = requestAnimationFrame(loop);
    },
    fail(message) {
      state.message = message || 'Generation failed.';
      state.detail = '';
      state.failed = true;
      state.target = Math.max(12, Math.round(state.progress));
      if (wrap) wrap.classList.add('failed');
      if (state.raf) cancelAnimationFrame(state.raf);
      state.raf = 0;
      updateDom();
      draw(performance.now());
    },
    setResult(html) {
      if (resultEl) resultEl.innerHTML = html || '';
    }
  };
}

async function synGenerateWithGame() {
  const plan = collectSynPlan();
  if (!plan.tables.length) return toast('Add at least one table with fields', 'err');
  if (plan.receiver === 'DB' && !synHasDbTarget(plan)) return toast('Pick a target data source', 'err');

  const out = $('syn-result');
  const stages = synGenerationStages(plan);
  const controller = out ? synStartGameProgress(out, stages) : null;
  if (controller) {
    controller.setProgress(1, 'Starting synthetic generation', 'Starting');
  }

  try {
    let status = await api.post('/api/synthetic/generate/start', plan);
    if (!status || !status.id) throw new Error('Synthetic job did not return a job id');
    synRefreshSyntheticJobs(true);
    const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
    while (!synJobDone(status.status)) {
      if (controller) controller.setProgress(status.percent || 1, status.message || status.stage || 'Running', status.stage, synProgressDetailText(status));
      await sleep(700);
      status = await api.get(`/api/synthetic/jobs/${encodeURIComponent(status.id)}`);
    }
    if (status.status === 'FAILED') throw new Error(status.error || status.message || 'Synthetic generation failed');
    if (/^CANCEL+ED$/i.test(status.status || '')) throw new Error('Synthetic generation cancelled');

    const r = status.result || {};
    const summary = synResultTableSummary(r);
    if (r.files && r.files.length) {
      synLastFiles = r.files;
      r.files.forEach(f => synDownload(f.name, f.content));
      if (controller) {
        controller.finish('Goal! Files generated.');
        controller.setResult(`<p class="sub tight">Generated ${r.files.length} file(s) - downloads started.</p><p class="sub tight">${summary}</p>
          <ul>${r.files.map((f, i) => `<li><code>${esc(f.name)}</code> (${f.content.length} bytes) <button class="ghost small" onclick="synDownloadIdx(${i})">Download again</button></li>`).join('')}</ul>`);
      } else if (out) {
        out.innerHTML = `<p class="sub tight">Generated ${r.files.length} file(s) - downloads started.</p><p class="sub tight">${summary}</p>`;
      }
    } else {
      if (controller) {
        controller.finish('Goal! Loaded.');
        controller.setResult(`<p class="sub tight">Loaded into the target database.</p><p class="sub tight">${summary}</p>${synValidationReport(r)}`);
      } else if (out) {
        out.innerHTML = `<p class="sub tight">Loaded into the target database.</p><p class="sub tight">${summary}</p>${synValidationReport(r)}`;
      }
      toast('Synthetic data loaded', 'ok');
    }
    synRefreshSyntheticJobs(true);
  } catch (e) {
    const cancelled = /cancel/i.test(e.message || '');
    if (controller) {
      controller.fail(cancelled ? 'Generation cancelled.' : 'Generation failed.');
      controller.setResult(`<div class="error">${esc(e.message)}</div>`);
    } else if (out) {
      out.innerHTML = `<div class="error">${esc(e.message)}</div>`;
    }
    synRefreshSyntheticJobs(true);
    toast(e.message, cancelled ? 'ok' : 'err');
  }
}

/* ---- type-aware generator compatibility (so a date column only accepts date generators, etc.) ---- */
function synColTypeCat(t) {
  t = (t || '').toUpperCase();
  if (['INTEGER', 'BIGINT', 'SMALLINT', 'TINYINT', 'DECIMAL', 'NUMERIC', 'DOUBLE', 'FLOAT', 'REAL'].includes(t)) return 'number';
  if (t === 'DATE') return 'date';
  if (t === 'TIMESTAMP') return 'timestamp';
  if (t === 'BOOLEAN' || t === 'BIT') return 'boolean';
  return 'text';
}
function synGenFits(gen, cat) {
  const g = (gen || '').toUpperCase();
  if (g === 'LITERAL' || g === 'NULL' || g === 'CONSTANT') return true;   // constants suit any type
  if (cat === 'text') return true;                                        // text can hold any representation
  const out = synSqlTypeForGenerator(gen);
  if (cat === 'number')    return out === 'INTEGER' || out === 'DECIMAL' || g === 'SEQUENCE' || /(?:^|_)(AGE|YEAR|COUNT|QTY|QUANTITY|RANGE|AMOUNT|BALANCE|PRICE|RATE|SCORE|PERCENT|NUMBER|NUM)(?:_|$)/.test(g);
  if (cat === 'date')      return out === 'DATE' || /(?:DATE|DOB|BIRTH)/.test(g);
  if (cat === 'timestamp') return out === 'DATE' || out === 'TIMESTAMP' || /(?:DATE|DOB|BIRTH|TIME)/.test(g);
  if (cat === 'boolean')   return out === 'BOOLEAN' || /(?:BOOL|FLAG|^IS_|^HAS_)/.test(g);
  return true;
}
function synDefaultGenForType(sqlType) {
  switch (synColTypeCat(sqlType)) {
    case 'number':    return (sqlType || '').toUpperCase() === 'INTEGER' ? 'INT_RANGE' : 'DECIMAL_RANGE';
    case 'date':      return 'DATE_RECENT';
    case 'timestamp': return 'TIMESTAMP_RECENT';
    case 'boolean':   return 'BOOLEAN';
    default:          return 'ALPHANUMERIC';
  }
}
// Render the post-load validation (row counts + FK orphans) and reject summary returned by the backend.
function synResultTableSummary(r) {
  const tables = r?.tables || r?.logicalTables || [];
  if (Array.isArray(r?.targets) && r.targets.length) {
    const targetText = r.targets.map(t => `${esc(t.name || 'target')}: ${(t.tables || []).map(x => `${esc(x.name)} ${synFormatRows(x.rows)}`).join(', ')}`).join(' | ');
    return targetText || tables.map(t => `${esc(t.name)}: ${synFormatRows(t.rows)} rows`).join(' - ');
  }
  return (tables || []).map(t => `${esc(t.name)}: ${synFormatRows(t.rows)} rows`).join(' - ');
}

function synValidationReport(r) {
  if (!r) return '';
  let html = '';
  if (Array.isArray(r.targets) && r.targets.length) {
    html += r.targets.map(target => {
      const targetName = esc(target.name || 'Target');
      const targetStatus = target.status ? ` ${pill(target.status, target.status === 'COMPLETED' ? 'good' : 'bad')}` : '';
      return `<div class="syn-report"><div class="syn-report-h">${targetName}${targetStatus}</div>
        ${synValidationReport({ validation: target.validation, rejects: target.rejects })}
        ${(target.rangeWarnings || []).length ? `<div class="syn-run-english warn"><b>Range warnings</b><p>${target.rangeWarnings.map(esc).join('<br>')}</p></div>` : ''}
      </div>`;
    }).join('');
    return html;
  }
  if (r.validation && r.validation.length) {
    const rows = r.validation.map(v => {
      const fks = v.foreignKeys || [];
      const fkCell = fks.length
        ? fks.map(f => `${esc(f.column)} &rarr; ${esc(f.references)}: ${f.orphans > 0 ? `<span class="syn-vbad">${f.orphans} orphan(s)</span>` : '<span class="syn-vok">ok</span>'}`).join('<br>')
        : '<span class="muted">&mdash;</span>';
      const rc = v.rowCount != null ? Number(v.rowCount).toLocaleString() : (v.rowCountError ? '<span class="syn-vbad">error</span>' : '&mdash;');
      return `<tr><td>${esc(v.table)}</td><td class="num">${rc}</td><td>${fkCell}</td></tr>`;
    }).join('');
    html += `<div class="syn-report"><div class="syn-report-h">Validation</div>
      <table class="syn-report-tbl"><thead><tr><th>Table</th><th class="num">Rows loaded</th><th>Foreign-key integrity</th></tr></thead><tbody>${rows}</tbody></table></div>`;
  }
  if (r.rejects && r.rejects.rejected) {
    const samples = (r.rejects.samples || []).slice(0, 10);
    html += `<div class="syn-report"><div class="syn-report-h warn">${Number(r.rejects.rejected).toLocaleString()} row(s) rejected (skipped)</div>`
      + (samples.length ? `<ul class="syn-reject-list">${samples.map(s => `<li><b>${esc(s.table || '')}</b> — ${esc(s.error || '')}</li>`).join('')}</ul>` : '')
      + `</div>`;
  }
  return html;
}

function synGenSpec(name) {
  const n = (name || '').toUpperCase();
  const cat = (typeof generatorCatalog !== 'undefined' ? generatorCatalog : []) || [];
  return cat.find(g => (g.name || '').toUpperCase() === n) || null;
}
function synGenOptionsFor(col) {
  const cat = synColTypeCat(col.sqlType);
  let list = (GENERATORS || []).filter(g => synGenFits(g, cat));
  if (col.generator && !list.includes(col.generator)) list = [col.generator, ...list];  // never drop the current choice
  if (!list.length) list = [col.generator || 'ALPHANUMERIC'];
  return list.map(g => `<option ${g === col.generator ? 'selected' : ''}>${g}</option>`).join('');
}

async function initSynthetic() {
  await refreshShared();
  await loadGeneratorCatalog();
  if (!synState.tables.length) applyBlueprint('customer360', false, true);
  synRender();
  synEnsureSyntheticJobPolling();
  synRefreshSavedJobs(true);
  synRefreshSyntheticJobs(true);
}

function applyBlueprint(key, announce = true, auto = false) {
  const bp = SYN_DATASETS[key] || SYN_DATASETS.customer360;
  synState.dataset = bp.dataset;
  synState.tables = bp.tables.map(t => ({
    name: t.name, rowCount: t.rows,
    columns: t.cols.map(([name, gen, p1, p2, pk, fk]) => synMakeCol(name, gen, p1, p2, pk, fk))
  }));
  synState.autoBlueprint = !!auto;
  synState.step = 1;
  if (announce) toast('Blueprint applied: ' + key, 'ok');
  if ($('syn-wizard')) synRender();
}

function synReceiverChanged() { /* legacy no-op; the wizard handles the receiver in step 3 */ }
function synSet(key, val) { synState[key] = val; }

/* Generator-catalog "Add" button → add a field to the most recent table (overrides the old DOM version). */
function addSynCol(name, gen, p1, p2) {
  synState.autoBlueprint = false;
  if (!synState.tables.length) synState.tables.push({ name: 'table1', rowCount: 100, columns: [] });
  const t = synState.tables[synState.tables.length - 1];
  t.columns.push(synMakeCol(name || 'field', gen, p1 || '', p2 || '', false, ''));
  toast(`Added ${gen} to ${t.name}`, 'ok');
  if (synState.step === 2) synRender(); else synRenderChips();
}

/* ---------- step bar + dispatch ---------- */
function synRender() {
  synStepBar();
  const w = $('syn-wizard'); if (!w) return;
  if (synState.step === 1) synRenderStep1(w);
  else if (synState.step === 2) synRenderStep2(w);
  else synRenderStep3(w);
}

function synStepBar() {
  const bar = $('syn-steps'); if (!bar) return;
  const steps = ['Source & Tables', 'Fields', 'Output & Generate'];
  bar.innerHTML = steps.map((label, i) => {
    const n = i + 1, cls = synState.step === n ? 'active' : (synState.step > n ? 'done' : '');
    return `<button class="syn-step ${cls}" onclick="synGoStep(${n})"><span class="n">${n}</span>${label}</button>`;
  }).join('');
}

function synGoStep(n) {
  if (n >= 2 && !synState.tables.length) return toast('Add at least one table first', 'err');
  synState.step = n; synRender();
}

/* ---------- step 1: source & tables ---------- */
function synRenderStep1(w) {
  w.innerHTML = `<div class="syn-pane">
    <div class="row push">
      <div><label>Dataset name</label><input value="${esc(synState.dataset)}" oninput="synSet('dataset',this.value)"></div>
      <div><label>Seed</label><input type="number" value="${esc(synState.seed)}" oninput="synSet('seed',this.value)"></div>
    </div>

    <h3 class="section-title">Quick start</h3>
    <div class="prompt-row">
      <button class="ghost" onclick="applyBlueprint('customer360')">Customer 360</button>
      <button class="ghost" onclick="applyBlueprint('banking')">Customers + Accounts</button>
      <button class="ghost" onclick="applyBlueprint('orders')">Customers + Orders</button>
      <button class="ghost" onclick="applyBlueprint('api')">API Contract</button>
    </div>

    <h3 class="section-title">Import from an existing schema</h3>
    <div class="row push">
      <div><label>Data source</label><select id="syn-src-ds" onchange="synSrcDsChanged(this.value)"></select></div>
      <div><label>Schema</label><select id="syn-src-schema" onchange="synSrcSchemaChanged(this.value)"><option value="">Schema</option></select></div>
    </div>
    <div id="syn-src-tables" class="syn-pick"></div>
    <p class="sub tight">Importing auto-detects foreign keys — child columns lock to their parent so values stay referentially intact, and parents load before children.</p>

    <h3 class="section-title">Tables in this dataset</h3>
    <div id="syn-chips" class="syn-chips"></div>
    <div class="row push" style="margin-top:8px"><button class="ghost small" onclick="synAddBlankTable()">+ Blank table</button></div>

    <h3 class="section-title">Relationships</h3>
    <div id="syn-er" class="syn-er empty">Add related tables to see the ER graph.</div>

    <div class="syn-nav"><span class="grow"></span><button onclick="synGoStep(2)">Next: Fields &rarr;</button></div>
  </div>`;
  synFillSrcDs();
  synRenderChips();
  renderSynER();
}

function synFillSrcDs() {
  const sel = $('syn-src-ds'); if (!sel) return;
  sel.innerHTML = '<option value="">Data source</option>' + (dataSources || []).map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
  sel.value = synState.srcDsId || '';
  if (synState.srcDsId) synLoadSrcSchemas();
}
async function synLoadSrcSchemas() {
  const sel = $('syn-src-schema'); if (!sel) return;
  if (!synState.srcDsId) { sel.innerHTML = '<option value="">Schema</option>'; return; }
  try {
    const rows = await loadSchemas(synState.srcDsId);
    sel.innerHTML = '<option value="">Schema</option>' + rows.map(r => `<option value="${esc(r.schema)}">${esc(r.schema)}</option>`).join('');
    sel.value = synState.srcSchema || '';
    if (synState.srcSchema) synLoadSrcTables();
  } catch (e) {
    sel.innerHTML = '<option value="">Could not load schemas</option>';
    toast(`Could not load schemas: ${e.message}`, 'err');
  }
}
async function synLoadSrcTables() {
  const box = $('syn-src-tables'); if (!box) return;
  if (!synState.srcDsId || !synState.srcSchema) { box.innerHTML = ''; return; }
  box.innerHTML = '<div class="loading">Loading tables…</div>';
  try {
    const tables = await loadTables(synState.srcDsId, synState.srcSchema);
    if (!tables.length) { box.innerHTML = '<div class="empty">No tables in this schema.</div>'; return; }
    box.innerHTML = `<div class="syn-pick-list">${tables.map(t => `<label class="syn-pick-item"><input type="checkbox" value="${esc(t)}"> ${esc(t)}</label>`).join('')}</div>
      <div class="row push" style="margin-top:8px">
        <button type="button" class="ghost small" data-syn-import-action="selected">+ Add selected</button>
        <button type="button" class="ghost small" data-syn-import-action="all">+ Add all</button>
        <button type="button" class="ghost small" data-syn-import-action="profile" title="Import selected table(s) and learn realistic generators &amp; distributions from their real data">+ Add &amp; learn distributions</button>
      </div>
      <div id="syn-import-status" class="sub tight"></div>`;
  } catch (e) {
    box.innerHTML = `<div class="error">Could not load tables: ${esc(e.message)}</div>`;
    toast(e.message, 'err');
  }
}
function synSrcDsChanged(v) {
  synState.srcDsId = v; synState.srcSchema = '';
  if ($('syn-src-schema')) $('syn-src-schema').innerHTML = '<option value="">Schema</option>';
  if ($('syn-src-tables')) $('syn-src-tables').innerHTML = '';
  if (v) synLoadSrcSchemas();
}
function synSrcSchemaChanged(v) { synState.srcSchema = v; if (v) synLoadSrcTables(); else if ($('syn-src-tables')) $('syn-src-tables').innerHTML = ''; }
function synImportNamesFromChecked() {
  return [...document.querySelectorAll('#syn-src-tables input:checked')].map(i => i.value);
}
function synSetImportBusy(busy, message = '') {
  document.querySelectorAll('#syn-src-tables [data-syn-import-action]').forEach(b => { b.disabled = !!busy; });
  const status = $('syn-import-status');
  if (status) {
    status.className = busy ? 'loading' : 'sub tight';
    status.textContent = message;
  }
}
async function synImportChecked() {
  await synImportTables(synImportNamesFromChecked());
}
async function synProfileChecked() {
  const names = synImportNamesFromChecked();
  if (!names.length) return toast('Select one or more tables', 'err');
  const ds = synState.srcDsId, schema = synState.srcSchema;
  await synImportTables(names);   // import with FK auto-detection first
  let learned = 0, anyRandom = false;
  const profileWarnings = [];
  for (const table of names) {
    try {
      const prof = await api.post('/api/synthetic/profile', { dataSourceId: parseInt(ds), schema, table });
      (prof.warnings || []).forEach(w => profileWarnings.push(`${table}: ${w}`));
      const t = synState.tables.find(x => (x.name || '').toLowerCase() === table.toLowerCase());
      if (!t) continue;
      if (prof.sampling === 'random') anyRandom = true;
      if (prof.rowCount) t.rowCount = Math.min(prof.rowCount, 100000);
      const byName = {}; (prof.columns || []).forEach(c => byName[(c.name || '').toLowerCase()] = c);
      t.columns.forEach(col => {
        const p = byName[(col.name || '').toLowerCase()];
        if (!p || col.fkTable) return;          // keep FK-locked columns referentially intact
        col.generator = p.generator || col.generator;
        col.param1 = p.param1 || '';
        col.param2 = p.param2 || '';
        col.typeLocked = false;
      });
      learned++;
    } catch (e) { toast(`Profile ${table}: ${e.message}`, 'err'); }
  }
  synRender();
  if (learned) toast(`Learned distributions for ${learned} table(s)${anyRandom ? ' (random sample)' : ''}`, 'ok');
  if (profileWarnings.length) toast(`Banking-safe profile warnings: ${profileWarnings.slice(0, 3).join(' | ')}`, 'warn');
}
async function synImportAll() {
  if (!synState.srcDsId || !synState.srcSchema) return toast('Select a data source and schema first', 'err');
  try {
    synSetImportBusy(true, 'Loading table list...');
    const rows = await api.get(`/api/datasources/${synState.srcDsId}/tables?schema=${encodeURIComponent(synState.srcSchema)}`);
    const names = rows.map(r => r.table).filter(Boolean);
    tableCache[`${synState.srcDsId}|${synState.srcSchema}`] = names;
    await synImportTables(names);
  } catch (e) {
    toast('Could not load all tables: ' + e.message, 'err');
  } finally {
    synSetImportBusy(false);
  }
}

function synStarterBlueprintActive() {
  const bp = SYN_DATASETS.customer360;
  if (!bp || synState.tables.length !== bp.tables.length) return false;
  return bp.tables.every((bt, i) => {
    const t = synState.tables[i];
    return t && t.name === bt.name && Number(t.rowCount) === Number(bt.rows)
      && (t.columns || []).length === bt.cols.length
      && bt.cols.every((bc, ci) => (t.columns[ci] || {}).name === bc[0]);
  });
}

function synClearStarterBlueprintForImport() {
  if (synState.autoBlueprint || synStarterBlueprintActive()) {
    synState.tables = [];
    synState.autoBlueprint = false;
  }
}

async function synImportTables(names) {
  names = [...new Set((names || []).filter(Boolean))];
  if (!names.length) return toast('Select one or more tables', 'err');
  synClearStarterBlueprintForImport();
  const ds = synState.srcDsId, schema = synState.srcSchema;
  const existing = new Set(synState.tables.map(t => t.name.toLowerCase()));
  let added = 0, skipped = 0, failed = 0;
  synSetImportBusy(true, `Adding ${names.length} table(s)...`);
  for (const [idx, table] of names.entries()) {
    if (existing.has(table.toLowerCase())) { skipped++; continue; }
    try {
      synSetImportBusy(true, `Reading ${table} (${idx + 1}/${names.length})...`);
      const [colsMeta, fks] = await Promise.all([
        api.get(`/api/datasources/${ds}/tables/${encodeURIComponent(table)}/columns?schema=${encodeURIComponent(schema)}`),
        api.get(`/api/datasources/${ds}/tables/${encodeURIComponent(table)}/fks?schema=${encodeURIComponent(schema)}`).catch(() => [])
      ]);
      const fkByCol = {}; (fks || []).forEach(f => { fkByCol[(f.column || '').toLowerCase()] = f; });
      const columns = (colsMeta || []).map(c => {
        const f = fkByCol[(c.column || '').toLowerCase()];
        const gen = suggestGeneratorForColumn(c.column, c.type);
        const col = synMakeCol(c.column, gen, defaultParamForGenerator(gen, c.column), defaultParam2ForGenerator(gen), false, f ? f.refTable + '.' + f.refColumn : '');
        col.sqlType = sqlTypeFromDbType(c.type);
        col.typeLocked = true;   // real DB column — its data type constrains which generators are allowed
        // make sure the chosen generator actually produces this column's type
        if (!synGenFits(col.generator, synColTypeCat(col.sqlType))) col.generator = synDefaultGenForType(col.sqlType);
        // numeric columns must not carry a text prefix (e.g. SEQUENCE "C-") — it would clash with the column type
        if (col.generator === 'SEQUENCE' && (col.sqlType === 'INTEGER' || col.sqlType === 'DECIMAL')) col.param1 = '';
        return col;
      });
      if (!columns.length) throw new Error('No columns returned for this table');
      synState.tables.push({ name: table, rowCount: 100, columns });
      existing.add(table.toLowerCase());
      added++;
    } catch (e) {
      failed++;
      toast(`${table}: ${e.message}`, 'err');
    }
  }
  if (added) {
    if (!synState.targetDsId) { synState.targetDsId = ds; synState.targetSchema = schema; }   // default DB target to source
    toast(`Added ${added} table(s)${failed ? `, ${failed} failed` : ''}${skipped ? `, ${skipped} already existed` : ''}`, failed ? 'warn' : 'ok');
    synRenderChips(); renderSynER();
  } else if (failed) {
    toast(`No tables added. ${failed} table(s) failed to import.`, 'err');
  } else {
    toast('Those tables are already added', 'info');
  }
  synSetImportBusy(false, failed ? `${failed} table(s) failed. See the toast message for details.` : '');
}

function synRenderChips() {
  const box = $('syn-chips'); if (!box) return;
  if (!synState.tables.length) { box.innerHTML = '<div class="empty">No tables yet. Use a Quick start blueprint or import from a schema above.</div>'; return; }
  box.innerHTML = synState.tables.map((t, i) => {
    const fkCount = t.columns.filter(c => c.fkTable).length;
    return `<div class="syn-chip">
      <div class="syn-chip-main"><b>${esc(t.name)}</b><span class="sub">${t.columns.length} fields${fkCount ? ` · ${fkCount} FK` : ''}</span></div>
      <input class="syn-chip-rows" type="number" min="0" value="${esc(t.rowCount)}" title="rows" oninput="synTableMeta(${i},'rowCount',this.value)">
      <button class="small ghost" onclick="synGoStep(2)">Fields</button>
      <button class="small danger" onclick="synRemoveTable(${i})">&times;</button>
    </div>`;
  }).join('');
}
function synRemoveTable(i) { synState.autoBlueprint = false; synState.tables.splice(i, 1); synRender(); }
function synAddBlankTable() {
  synState.autoBlueprint = false;
  synState.tables.push({ name: 'table' + (synState.tables.length + 1), rowCount: 100, columns: [synMakeCol('id', 'SEQUENCE', '', '', true, '')] });
  synRenderChips(); renderSynER();
}
function synTableMeta(ti, key, val) { const t = synState.tables[ti]; if (t) { synState.autoBlueprint = false; t[key] = val; if (key === 'name') renderSynER(); } }

/* ---------- step 2: fields ---------- */
function synRenderStep2(w) {
  if (!synState.tables.length) { synState.step = 1; return synRender(); }
  w.innerHTML = `<div class="syn-pane">
    ${synState.tables.map((t, ti) => synTableEditor(t, ti)).join('')}
    <div class="syn-nav"><button class="ghost" onclick="synGoStep(1)">&larr; Back</button><span class="grow"></span><button onclick="synGoStep(3)">Next: Output &rarr;</button></div>
  </div>`;
}

function synTableEditor(t, ti) {
  return `<div class="syn-tedit">
    <div class="syn-tedit-head">
      <input class="syn-tedit-name" value="${esc(t.name)}" oninput="synTableMeta(${ti},'name',this.value)">
      <span class="sub">rows</span><input class="syn-tedit-rows" type="number" min="0" value="${esc(t.rowCount)}" oninput="synTableMeta(${ti},'rowCount',this.value)">
      <span class="grow"></span>
      <button class="ghost small" onclick="synAddField(${ti})">+ Field</button>
      <button class="small danger" onclick="synRemoveTable(${ti})">Remove table</button>
    </div>
    <div class="syn-fields">
      <div class="syn-fhead"><span>Field</span><span>Generates as</span><span></span><span></span></div>
      ${t.columns.map((c, ci) => synFieldRow(c, ti, ci)).join('')}
    </div>
  </div>`;
}

function synFieldRow(c, ti, ci) {
  const isFk = !!(c.fkTable && c.fkColumn);
  const gen = String(c.generator || '').toUpperCase();
  const badges = (c.typeLocked ? `<span class="syn-badge type" title="Column data type — only compatible generators are offered">${esc(c.sqlType)}</span>` : '')
    + (c.primaryKey ? '<span class="syn-badge pk">PK</span>' : '')
    + (isFk ? `<span class="syn-badge fk" title="Child column — values come from ${esc(c.fkTable)}.${esc(c.fkColumn)} so they stay referentially intact">&#8627; ${esc(c.fkTable)}.${esc(c.fkColumn)}</span>` : '');
  const genCell = isFk
    ? `<span class="syn-genlocked" title="Locked: generated from the parent key">&#128274; references parent</span>`
    : `<select onchange="synGenChange(${ti},${ci},this.value)">${c.typeLocked ? synGenOptionsFor(c) : genOptions(c.generator)}</select>
       ${gen === 'LITERAL' ? `<input class="syn-literal-input" value="${esc(c.param1 || '')}" placeholder="Literal value for every row" oninput="synFld(${ti},${ci},'param1',this.value)">` : ''}`;
  let adv = '';
  if (c.open) {
    const spec = synGenSpec(gen);
    const hint = spec && spec.description ? `<span class="sub syn-gen-hint">${esc(spec.description)}${spec.example ? ` &middot; e.g. <code>${esc(spec.example)}</code>` : ''}</span>` : '';
    const p1ph = spec && spec.param1 ? spec.param1 : 'optional';
    const p2ph = spec && spec.param2 ? spec.param2 : 'optional';
    const paramControls = gen === 'LITERAL'
      ? `<span class="sub">Literal uses the value shown beside the generator dropdown.</span>`
      : gen === 'NULL'
        ? `<span class="sub">NULL always writes a database null/blank value.</span>`
        : `${hint}
           <div><label>Param 1</label><input value="${esc(c.param1 || '')}" placeholder="${esc(p1ph)}" title="${esc(p1ph)}" oninput="synFld(${ti},${ci},'param1',this.value)"></div>
           <div><label>Param 2</label><input value="${esc(c.param2 || '')}" placeholder="${esc(p2ph)}" title="${esc(p2ph)}" oninput="synFld(${ti},${ci},'param2',this.value)"></div>`;
    adv = `<div class="syn-adv">` + (isFk
      ? `<span class="sub">This field is referentially generated from <code>${esc(c.fkTable)}.${esc(c.fkColumn)}</code>.</span>
         <div title="Children per parent: each parent gets between min and max child rows. Blank = uniform random."><label>Children/parent min</label><input type="number" min="0" value="${esc(c.fkMin || '')}" placeholder="any" oninput="synFld(${ti},${ci},'fkMin',this.value)"></div>
         <div title="Children per parent (max)."><label>max</label><input type="number" min="0" value="${esc(c.fkMax || '')}" placeholder="any" oninput="synFld(${ti},${ci},'fkMax',this.value)"></div>
         <button class="ghost small" onclick="synUnlinkFk(${ti},${ci})">Unlink &amp; edit manually</button>`
      : `${paramControls}
         <label class="check"><input type="checkbox" ${c.primaryKey ? 'checked' : ''} onchange="synFld(${ti},${ci},'primaryKey',this.checked)"> Primary key</label>
         <div class="grow"><label>FK &rarr; table.column</label><input value="" placeholder="customers.customer_id" oninput="synFkInput(${ti},${ci},this.value)" onchange="synRender()"></div>`)
      + `</div>`;
  }
  return `<div class="syn-frow${isFk ? ' fk' : ''}">
    <input class="syn-fname" value="${esc(c.name)}" oninput="synFld(${ti},${ci},'name',this.value)">
    <div class="syn-fgen">${genCell}</div>
    <div class="syn-fbadge">${badges}</div>
    <div class="syn-fact"><button class="iconbtn" title="Advanced options" onclick="synToggleAdv(${ti},${ci})">&#9881;</button><button class="iconbtn danger" title="Remove field" onclick="synRemoveField(${ti},${ci})">&times;</button></div>
    ${adv}
  </div>`;
}

function synFld(ti, ci, key, val) { const c = synState.tables[ti] && synState.tables[ti].columns[ci]; if (c) { synState.autoBlueprint = false; c[key] = val; } }
function synGenChange(ti, ci, val) {
  const c = synState.tables[ti] && synState.tables[ti].columns[ci];
  if (!c) return;
  synState.autoBlueprint = false;
  const prior = String(c.generator || '').toUpperCase();
  c.generator = val;
  if (!c.typeLocked) c.sqlType = synSqlTypeForGenerator(val);   // keep a real column's type; only follow the generator for free-form columns
  const next = String(val || '').toUpperCase();
  if (prior === 'LITERAL' || next === 'LITERAL' || prior === 'NULL' || next === 'NULL') synRender();
}
function synFkInput(ti, ci, val) {
  const c = synState.tables[ti] && synState.tables[ti].columns[ci]; if (!c) return;
  synState.autoBlueprint = false;
  if (val && val.includes('.')) { const i = val.indexOf('.'); c.fkTable = val.slice(0, i); c.fkColumn = val.slice(i + 1); }
  else { c.fkTable = ''; c.fkColumn = ''; }
}
function synUnlinkFk(ti, ci) { const c = synState.tables[ti] && synState.tables[ti].columns[ci]; if (c) { synState.autoBlueprint = false; c.fkTable = ''; c.fkColumn = ''; c.open = true; synRender(); renderSynER(); } }
function synToggleAdv(ti, ci) { const c = synState.tables[ti] && synState.tables[ti].columns[ci]; if (c) { c.open = !c.open; synRender(); } }
function synAddField(ti) { synState.autoBlueprint = false; synState.tables[ti].columns.push(synMakeCol('field' + (synState.tables[ti].columns.length + 1), 'ALPHANUMERIC', '', '', false, '')); synRender(); }
function synRemoveField(ti, ci) { synState.autoBlueprint = false; synState.tables[ti].columns.splice(ci, 1); synRender(); }

/* ---------- step 3: output & generate ---------- */
function synNormalizeLoadState() {
  const legacy = (synState.prepMode || '').toUpperCase();
  if (!synState.loadAction) {
    synState.loadAction = (legacy === 'DELETE' || legacy === 'TRUNCATE_CASCADE' || legacy === 'DROP_RECREATE') ? 'REPLACE' : 'INSERT';
  }
  if (!synState.targetPrep) {
    if (legacy === 'DELETE') synState.targetPrep = 'DELETE';
    else if (legacy === 'TRUNCATE_CASCADE') synState.targetPrep = 'TRUNCATE';
    else synState.targetPrep = synState.loadAction === 'REPLACE' ? 'DELETE' : 'NONE';
  }
  if (legacy === 'DROP_RECREATE') {
    synState.dropTable = true;
    synState.createTable = true;
  }
  if (synState.loadAction === 'TRUNCATE_ONLY') synState.targetPrep = 'TRUNCATE';
  synState.prepMode = synPrepModeFromLoad();
}

function synPrepModeFromLoad() {
  if (synState.dropTable) return 'DROP_RECREATE';
  if (synState.targetPrep === 'TRUNCATE') return 'TRUNCATE_CASCADE';
  if (synState.targetPrep === 'DELETE') return 'DELETE';
  return 'APPEND';
}

function synLoadActionChanged(v) {
  synState.loadAction = v;
  if (v === 'REPLACE' && synState.targetPrep === 'NONE') synState.targetPrep = 'DELETE';
  if (v === 'INSERT' && synState.targetPrep === 'DELETE') synState.targetPrep = 'NONE';
  if (v === 'TRUNCATE_ONLY') synState.targetPrep = 'TRUNCATE';
  synState.prepMode = synPrepModeFromLoad();
  synRender();
}

function synTargetPrepChanged(v) {
  synState.targetPrep = v;
  synState.prepMode = synPrepModeFromLoad();
  synRender();
}

function synExecutionChanged(v) {
  synState.executionMode = v || 'SINGLE';
  if (synState.executionMode === 'SINGLE') {
    synState.partitionCount = '';
    synState.partitionSize = '';
  }
  synRender();
}

function synExecutionHint() {
  if (synState.executionMode === 'DISTRIBUTED') {
    return 'Workers claim persisted ranges through the shared ForgeTDM database. Every worker node must have access to the configured target data source.';
  }
  if (synState.executionMode === 'LOCAL_PARTITIONED') {
    return 'One job is divided into deterministic row ranges and processed across local CPU workers. Target preparation runs once.';
  }
  return 'Runs tables through one generation stream. Best for small loads and file output.';
}

function synNeedsKeys() {
  return ['UPDATE', 'INSERT_UPDATE'].includes(synState.loadAction || 'INSERT');
}

function synLoadHint() {
  return ({
    REPLACE: 'Load replace clears the target using Target prep, then inserts generated rows.',
    INSERT: 'Insert only appends generated rows. Choose Target prep if you want to clear first.',
    UPDATE: 'Update only changes rows that match key columns; missing rows are skipped.',
    INSERT_UPDATE: 'Insert-update updates matching keys and inserts rows that are not already in the target.',
    TRUNCATE_ONLY: 'Truncate only clears the selected synthetic target tables without loading rows.'
  })[synState.loadAction || 'INSERT'] || '';
}

function synRenderStep3(w) {
  const r = synState.receiver;
  synNormalizeLoadState();
  const pm = synState.prepMode || 'APPEND';
  const la = synState.loadAction || 'INSERT';
  const tp = synState.targetPrep || 'NONE';
  const keyDisabled = synNeedsKeys() ? '' : 'disabled';
  const recOpts = { DB: 'Database load', CSV: 'CSV files', JSON: 'JSON files', SQL: 'SQL script (CREATE + INSERT)' };
  w.innerHTML = `<div class="syn-pane">
    <div class="row push">
      <div><label>Output</label><select onchange="synSet('receiver',this.value);synRender()">
        ${['DB', 'CSV', 'JSON', 'SQL'].map(v => `<option value="${v}" ${r === v ? 'selected' : ''}>${recOpts[v]}</option>`).join('')}
      </select></div>
    </div>
    ${r === 'DB' ? `<div class="row push">
      <div><label>Target data source</label><select id="syn-ds2" onchange="synSet('targetDsId',this.value);synLoadTargetSchemas()"></select></div>
      <div><label>Target schema</label><select id="syn-schema2" onchange="synSet('targetSchema',this.value)"><option value="">Default</option></select></div>
    </div>` : ''}
    ${r === 'DB' ? synTargetSystemsHtml() : ''}
    ${r === 'DB' ? `<div class="row push">
      <div><label>Load action</label><select onchange="synLoadActionChanged(this.value)">
        <option value="REPLACE" ${la === 'REPLACE' ? 'selected' : ''}>Load replace</option>
        <option value="INSERT" ${la === 'INSERT' ? 'selected' : ''}>Insert only</option>
        <option value="UPDATE" ${la === 'UPDATE' ? 'selected' : ''}>Update only</option>
        <option value="INSERT_UPDATE" ${la === 'INSERT_UPDATE' ? 'selected' : ''}>Insert-update</option>
        <option value="TRUNCATE_ONLY" ${la === 'TRUNCATE_ONLY' ? 'selected' : ''}>Truncate only</option>
      </select></div>
      <div style="display:none"><label>Legacy prep</label><select onchange="synSet('prepMode',this.value);synRender()">
        <option value="APPEND" ${pm === 'APPEND' ? 'selected' : ''}>Append — keep existing rows</option>
        <option value="INSERT" ${la === 'INSERT' ? 'selected' : ''}>Insert only</option>
        <option value="TRUNCATE_CASCADE" ${pm === 'TRUNCATE_CASCADE' ? 'selected' : ''}>Truncate cascade — load replace</option>
        <option value="TRUNCATE_ONLY" ${la === 'TRUNCATE_ONLY' ? 'selected' : ''}>Truncate only</option>
      </select></div>
      <div><label>Target prep</label><select onchange="synTargetPrepChanged(this.value)" ${la === 'TRUNCATE_ONLY' ? 'disabled' : ''}>
        <option value="NONE" ${tp === 'NONE' ? 'selected' : ''}>Do not clear target</option>
        <option value="DELETE" ${tp === 'DELETE' ? 'selected' : ''}>Delete rows first</option>
        <option value="TRUNCATE" ${tp === 'TRUNCATE' ? 'selected' : ''}>Truncate selected tables</option>
      </select></div>
      <div><label>Key columns</label><input value="${esc(synState.keyColumns || '')}" placeholder="blank = target/generated PK" ${keyDisabled} oninput="synSet('keyColumns',this.value)"></div>
      <div><label>Batch size</label><input type="number" min="1" max="50000" value="${esc(synState.batchSize || '')}" placeholder="default 5000" oninput="synSet('batchSize',this.value)"></div>
      <div><label title="Commit every N rows so huge loads don't run in one giant transaction. 0 = single transaction.">Commit every (rows)</label><input type="number" min="0" max="100000000" value="${esc(synState.commitEveryRows || '')}" placeholder="0 = one transaction" oninput="synSet('commitEveryRows',this.value)"></div>
      <div><label title="Maximum rejected rows before the load fails (only when 'Skip bad rows' is on).">Max rejects</label><input type="number" min="0" value="${synState.maxRejects == null ? '' : esc(synState.maxRejects)}" placeholder="default 1000" oninput="synSet('maxRejects',this.value)" ${synState.continueOnError ? '' : 'disabled'}></div>
      <label class="check"><input type="checkbox" ${synState.createTable ? 'checked' : ''} onchange="synSet('createTable',this.checked)"> Create missing tables</label>
      <label class="check"><input type="checkbox" ${synState.dropTable ? 'checked' : ''} onchange="synDropToggle(this.checked)"> Drop &amp; recreate first</label>
      <label class="check" title="On a row error, skip that row (recording it as a reject) instead of failing the whole load. Streaming loads only."><input type="checkbox" ${synState.continueOnError ? 'checked' : ''} onchange="synSet('continueOnError',this.checked);synRender()"> Skip bad rows (continue on error)</label>
      <label class="check" title="Bulk-load for maximum throughput. Postgres uses COPY (fastest; aborts on the first bad row). MySQL, SQL Server, DB2 and H2 use multi-row INSERT (keeps per-row reject handling). Oracle and others use large JDBC batches."><input type="checkbox" ${synState.fastLoad ? 'checked' : ''} onchange="synSet('fastLoad',this.checked)"> Fast load (bulk)</label>
      <span class="form-hint">${esc(synLoadHint())}</span>
      <!--
      <span class="form-hint">Delete / Truncate only touch tables that already exist — brand-new tables are created empty.</span>
      -->
    </div>` : ''}
    ${r === 'DB' ? `<section class="syn-execution">
      <div class="syn-execution-head"><div><b>Execution</b><span>Choose how this job uses workers.</span></div><span class="pill ${synState.executionMode === 'SINGLE' ? 'dim' : 'info'}">${esc((synState.executionMode || 'SINGLE').replaceAll('_', ' '))}</span></div>
      <div class="row">
        <div><label>Mode</label><select onchange="synExecutionChanged(this.value)">
          <option value="SINGLE" ${synState.executionMode === 'SINGLE' ? 'selected' : ''}>Single worker</option>
          <option value="LOCAL_PARTITIONED" ${synState.executionMode === 'LOCAL_PARTITIONED' ? 'selected' : ''}>Parallel on this server</option>
          <option value="DISTRIBUTED" ${synState.executionMode === 'DISTRIBUTED' ? 'selected' : ''}>Distributed workers</option>
        </select></div>
        <div><label>Worker count</label><input type="number" min="1" max="32" value="${esc(synState.partitionCount || '')}" placeholder="auto" ${synState.executionMode === 'SINGLE' ? 'disabled' : ''} oninput="synSet('partitionCount',this.value)"></div>
        <div><label>Rows per partition</label><input type="number" min="1000" value="${esc(synState.partitionSize || '')}" placeholder="auto balance" ${synState.executionMode === 'SINGLE' ? 'disabled' : ''} oninput="synSet('partitionSize',this.value)"></div>
      </div>
      <p class="form-hint">${esc(synExecutionHint())}</p>
    </section>` : ''}
    ${r === 'SQL' ? `<div class="row push">
      <label class="check"><input type="checkbox" ${synState.createTable ? 'checked' : ''} onchange="synSet('createTable',this.checked)"> Include CREATE TABLE</label>
      <label class="check"><input type="checkbox" ${pm === 'DROP_RECREATE' ? 'checked' : ''} onchange="synDropToggle(this.checked)"> Include DROP &amp; recreate</label>
    </div>` : ''}
    <div class="syn-summary">${synSummary()}</div>
    <div class="syn-nav"><button class="ghost" onclick="synGoStep(2)">&larr; Back</button><span class="grow"></span><button class="ghost" onclick="synSaveCurrentJob()">Save Job</button><button onclick="synGenerateWithGame()">Generate</button></div>
    <div id="syn-result" class="empty" style="margin-top:14px">No output yet.</div>
  </div>`;
  if (r === 'DB') {
    synFillTargetDs();
    (synState.targetSystems || []).forEach((_, i) => synFillTargetSystemSchema(i));
  }
}

function synTargetSystemsHtml() {
  const systems = Array.isArray(synState.targetSystems) ? synState.targetSystems : [];
  systems.forEach(synEnsureTargetMappings);
  const dsOptionsFor = target => '<option value="">Select data source</option>' + (dataSources || [])
    .map(d => `<option value="${d.id}" ${String(target.targetDataSourceId || '') === String(d.id) ? 'selected' : ''}>${esc(d.name)}${d.kind ? ` (${esc(d.kind)})` : ''}</option>`).join('');
  return `<section class="syn-execution">
    <div class="syn-execution-head">
      <div><b>Target systems</b><span>Use this when one logical blueprint must load to multiple databases with different table or column names.</span></div>
      <span class="pill ${systems.length ? 'info' : 'dim'}">${systems.length ? `${systems.length} target(s)` : 'single target'}</span>
    </div>
    <div class="row push">
      <button type="button" class="ghost small" onclick="synAddTargetSystem()">+ Add target system</button>
      <button type="button" class="ghost small" onclick="synUseSingleTargetAsSystem()">Use selected target</button>
      ${systems.length ? `<button type="button" class="ghost small danger" onclick="synClearTargetSystems()">Clear multi-target map</button>` : ''}
    </div>
    ${systems.length ? `<p class="form-hint">When target systems are configured, they override the single target above. Large jobs use deterministic streaming replay per target so the heap stays bounded.</p>` : ''}
    <div class="syn-target-map-list">
      ${systems.map((target, i) => {
        const targetName = target.name || dataSourceName(target.targetDataSourceId) || `Target ${i + 1}`;
        const loadAction = target.loadAction || synState.loadAction || 'INSERT';
        const targetPrep = target.targetPrep || synState.targetPrep || 'NONE';
        return `<div class="syn-target-map">
          <div class="syn-target-map-head">
            <div><b>${esc(targetName)}</b><span>${esc(dataSourceName(target.targetDataSourceId))}${target.targetSchema ? ' / ' + esc(target.targetSchema) : ''}</span></div>
            <div class="row compact">
              <button type="button" class="ghost small" onclick="synAutoMapTargetTables(${i})">Auto-map tables</button>
              <button type="button" class="ghost small danger" onclick="synRemoveTargetSystem(${i})">Delete</button>
            </div>
          </div>
          <div class="row push">
            <div><label>Name</label><input value="${esc(target.name || '')}" placeholder="Oracle UAT, Postgres QA..." oninput="synSetTargetSystem(${i},'name',this.value)"></div>
            <div><label>Data source</label><select onchange="synSetTargetSystem(${i},'targetDataSourceId',this.value)">${dsOptionsFor(target)}</select></div>
            <div><label>Schema</label><select id="syn-mt-schema-${i}" onchange="synSetTargetSystem(${i},'targetSchema',this.value)"><option value="">Default</option></select></div>
            <div><label>Load action</label><select onchange="synSetTargetSystem(${i},'loadAction',this.value)">
              ${['REPLACE','INSERT','UPDATE','INSERT_UPDATE','TRUNCATE_ONLY'].map(v => `<option value="${v}" ${loadAction === v ? 'selected' : ''}>${esc(v.replace('_','-'))}</option>`).join('')}
            </select></div>
            <div><label>Prep</label><select onchange="synSetTargetSystem(${i},'targetPrep',this.value)">
              ${['NONE','DELETE','TRUNCATE'].map(v => `<option value="${v}" ${targetPrep === v ? 'selected' : ''}>${esc(v)}</option>`).join('')}
            </select></div>
          </div>
          <div class="row push">
            <label class="check"><input type="checkbox" ${target.createTable == null ? (synState.createTable ? 'checked' : '') : (target.createTable ? 'checked' : '')} onchange="synSetTargetSystem(${i},'createTable',this.checked)"> Create missing tables</label>
            <label class="check"><input type="checkbox" ${target.dropTable == null ? (synState.dropTable ? 'checked' : '') : (target.dropTable ? 'checked' : '')} onchange="synSetTargetSystem(${i},'dropTable',this.checked)"> Drop &amp; recreate first</label>
            <label class="check"><input type="checkbox" ${target.fastLoad ? 'checked' : ''} onchange="synSetTargetSystem(${i},'fastLoad',this.checked)"> Fast load</label>
            <label class="check"><input type="checkbox" ${target.continueOnError ? 'checked' : ''} onchange="synSetTargetSystem(${i},'continueOnError',this.checked)"> Skip bad rows</label>
          </div>
          <div class="syn-table-map-grid">
            ${(target.tables || []).map((mt, ti) => synTargetTableMapHtml(i, ti, mt)).join('')}
          </div>
        </div>`;
      }).join('')}
    </div>
  </section>`;
}

function synTargetTableMapHtml(i, ti, mt) {
  const cols = Array.isArray(mt.columns) ? mt.columns : [];
  return `<details class="syn-map-table" open>
    <summary><b>${esc(mt.logicalTable || '')}</b><span>&rarr;</span><input value="${esc(mt.physicalTable || '')}" onclick="event.stopPropagation()" oninput="synSetTargetTable(${i},${ti},'physicalTable',this.value)" placeholder="physical table"><button type="button" class="ghost small" onclick="event.preventDefault();event.stopPropagation();synLoadTargetColumns(${i},${ti})">Load columns</button></summary>
    <div class="syn-column-map-grid">
      <div class="syn-column-map-head">Logical column</div><div class="syn-column-map-head">Physical column</div><div class="syn-column-map-head">SQL type</div>
      ${cols.map((c, ci) => `<div><code>${esc(c.logicalColumn || '')}</code></div>
        <div><input value="${esc(c.physicalColumn || '')}" oninput="synSetTargetColumn(${i},${ti},${ci},'physicalColumn',this.value)" placeholder="physical column"></div>
        <div><input value="${esc(c.sqlType || '')}" oninput="synSetTargetColumn(${i},${ti},${ci},'sqlType',this.value)" placeholder="VARCHAR"></div>`).join('')}
    </div>
  </details>`;
}
function synFillTargetDs() {
  const sel = $('syn-ds2'); if (!sel) return;
  sel.innerHTML = '<option value="">Select data source</option>' + (dataSources || []).map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
  sel.value = synState.targetDsId || '';
  if (synState.targetDsId) synLoadTargetSchemas();
}
async function synLoadTargetSchemas() {
  const sel = $('syn-schema2'); if (!sel) return;
  if (!synState.targetDsId) { sel.innerHTML = '<option value="">Default</option>'; return; }
  const rows = await loadSchemas(synState.targetDsId);
  sel.innerHTML = '<option value="">Default</option>' + rows.map(r => `<option value="${esc(r.schema)}">${esc(r.schema)}</option>`).join('');
  sel.value = synState.targetSchema || '';
}
function synDropToggle(v) {
  synState.dropTable = !!v;
  if (v) {
    synState.createTable = true;
    synState.loadAction = 'REPLACE';
    synState.targetPrep = 'NONE';
  }
  synState.prepMode = synPrepModeFromLoad();
  synRender();
}
function synSummary() {
  const totalRows = synState.tables.reduce((a, t) => a + (parseInt(t.rowCount) || 0), 0);
  const fkTotal = synState.tables.reduce((a, t) => a + t.columns.filter(c => c.fkTable).length, 0);
  const load = synState.receiver === 'DB'
    ? ` &middot; <b>${esc((synState.loadAction || 'INSERT').replace('_', '-'))}</b>${synState.targetPrep && synState.targetPrep !== 'NONE' ? ` / ${esc(synState.targetPrep)}` : ''}`
    : '';
  const execution = synState.receiver === 'DB' && synState.executionMode !== 'SINGLE'
    ? ` &middot; <b>${esc(synState.executionMode === 'DISTRIBUTED' ? 'Distributed' : 'Local parallel')}</b> / ${esc(synState.partitionCount || 'auto')} workers`
    : '';
  return `<b>${synState.tables.length}</b> table(s) &middot; ~<b>${totalRows}</b> rows &middot; <b>${fkTotal}</b> referential link(s) &rarr; <b>${synState.receiver}</b>${load}${execution}`;
}

/* ---------- plan + ER + generate ---------- */
function collectSynPlan() {
  synNormalizeLoadState();
  const batchSize = parseInt(synState.batchSize || '0');
  const keyColumns = (synState.keyColumns || '').split(',').map(s => s.trim()).filter(Boolean);
  const plan = {
    dataset: synState.dataset || 'synthetic',
    seed: parseInt(synState.seed || '42') || 42,
    receiver: synState.receiver,
    targetDataSourceId: synState.targetDsId ? parseInt(synState.targetDsId) : null,
    targetSchema: synState.targetSchema || null,
    prepMode: synPrepModeFromLoad(),
    loadAction: synState.loadAction || 'INSERT',
    targetPrep: synState.targetPrep || 'NONE',
    keyColumns,
    batchSize: batchSize > 0 ? batchSize : null,
    commitEveryRows: parseInt(synState.commitEveryRows || '0') || 0,
    continueOnError: !!synState.continueOnError,
    maxRejects: synState.maxRejects === '' || synState.maxRejects == null ? null : (parseInt(synState.maxRejects) || 0),
    fastLoad: !!synState.fastLoad,
    executionMode: synState.receiver === 'DB' ? (synState.executionMode || 'SINGLE') : 'SINGLE',
    partitionCount: synState.executionMode === 'SINGLE' ? null : (parseInt(synState.partitionCount || '0') || null),
    partitionSize: synState.executionMode === 'SINGLE' ? null : (parseInt(synState.partitionSize || '0') || null),
    createTable: !!synState.createTable || !!synState.dropTable,
    dropTable: !!synState.dropTable,
    tables: synState.tables.map(t => ({
      name: (t.name || '').trim(),
      rowCount: parseInt(t.rowCount || '0') || 0,
      columns: t.columns.filter(c => (c.name || '').trim()).map(c => ({
        name: c.name.trim(), generator: c.generator, param1: c.param1 || null, param2: c.param2 || null,
        primaryKey: !!c.primaryKey, fkTable: c.fkTable || null, fkColumn: c.fkColumn || null, sqlType: c.sqlType || 'VARCHAR',
        fkMin: c.fkMin ? (parseInt(c.fkMin) || null) : null, fkMax: c.fkMax ? (parseInt(c.fkMax) || null) : null
      }))
    })).filter(t => t.name)
  };
  const targetSystems = synTargetSystemPlan();
  if (targetSystems.length) {
    plan.targetSystems = targetSystems;
  }
  return plan;
}

function renderSynER() {
  const el = $('syn-er'); if (!el) return;
  const tables = collectSynPlan().tables;
  if (!tables.length) { el.className = 'syn-er empty'; el.textContent = 'Add related tables to see the ER graph.'; return; }
  el.className = 'syn-er';
  const byName = {}; tables.forEach(t => { byName[t.name.toLowerCase()] = t; });
  const edges = [];
  tables.forEach(t => (t.columns || []).forEach(c => {
    if (c.fkTable && byName[c.fkTable.toLowerCase()]) edges.push({ child: t.name.toLowerCase(), parent: c.fkTable.toLowerCase() });
  }));
  const level = {}; tables.forEach(t => { level[t.name.toLowerCase()] = 0; });
  for (let i = 0; i < tables.length; i++) edges.forEach(e => { if (level[e.child] <= level[e.parent]) level[e.child] = level[e.parent] + 1; });
  const byLevel = {}; tables.forEach(t => { const l = level[t.name.toLowerCase()]; (byLevel[l] = byLevel[l] || []).push(t); });
  const levels = Object.keys(byLevel).map(Number).sort((a, b) => a - b);
  const COLW = 230, ROWH = 22, HEAD = 30, GAPX = 80, GAPY = 36, PAD = 18, MAXC = 12;
  const pos = {}; let maxBottom = 0;
  levels.forEach((l, li) => {
    let y = PAD;
    byLevel[l].forEach(t => {
      const shown = Math.min((t.columns || []).length, MAXC);
      const h = HEAD + shown * ROWH + ((t.columns || []).length > MAXC ? ROWH : 0) + 8;
      pos[t.name.toLowerCase()] = { x: PAD + li * (COLW + GAPX), y, w: COLW, h, t };
      y += h + GAPY; maxBottom = Math.max(maxBottom, y);
    });
  });
  const width = PAD * 2 + Math.max(1, levels.length) * (COLW + GAPX) - GAPX, height = Math.max(maxBottom, 120);
  let s = `<svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}"><defs><marker id="syn-arr" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 z" fill="var(--accent)"/></marker></defs>`;
  edges.forEach(e => {
    const p = pos[e.parent], c = pos[e.child]; if (!p || !c) return;
    const x1 = p.x + p.w, y1 = p.y + HEAD / 2, x2 = c.x, y2 = c.y + HEAD / 2, dx = Math.max(30, Math.abs(x2 - x1) * 0.4);
    s += `<path d="M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}" fill="none" stroke="var(--accent)" stroke-width="1.6" marker-end="url(#syn-arr)" opacity="0.8"/>`;
  });
  Object.values(pos).forEach(n => {
    const t = n.t;
    s += `<rect x="${n.x}" y="${n.y}" width="${n.w}" height="${n.h}" rx="8" fill="var(--panel)" stroke="var(--border)"/>`;
    s += `<rect x="${n.x}" y="${n.y}" width="${n.w}" height="${HEAD}" rx="8" fill="var(--accent-soft)"/>`;
    s += `<text x="${n.x + 12}" y="${n.y + 20}" font-size="13" font-weight="700" fill="var(--text)">${esc(t.name)}</text>`;
    (t.columns || []).slice(0, MAXC).forEach((c, i) => {
      const cy = n.y + HEAD + i * ROWH + 15, mark = (c.primaryKey ? '* ' : '') + (c.fkTable ? '↳ ' : '');
      s += `<text x="${n.x + 12}" y="${cy}" font-size="11.5" fill="${c.fkTable ? 'var(--accent)' : 'var(--muted)'}">${esc(mark + c.name)}</text>`;
    });
    if ((t.columns || []).length > MAXC) s += `<text x="${n.x + 12}" y="${n.y + HEAD + MAXC * ROWH + 15}" font-size="11" fill="var(--muted)">… +${t.columns.length - MAXC} more</text>`;
  });
  s += `</svg>`;
  el.innerHTML = `<div class="syn-er-scroll">${s}</div>`;
}

async function synGenerate() {
  const plan = collectSynPlan();
  if (!plan.tables.length) return toast('Add at least one table with fields', 'err');
  if (plan.receiver === 'DB' && !synHasDbTarget(plan)) return toast('Pick a target data source', 'err');
  const out = $('syn-result');
  if (out) { out.classList.remove('empty'); out.innerHTML = '<div class="loading">Generating…</div>'; }
  try {
    const r = await api.post('/api/synthetic/generate', plan);
    const summary = synResultTableSummary(r);
    if (r.files && r.files.length) {
      synLastFiles = r.files;
      r.files.forEach(f => synDownload(f.name, f.content));
      if (out) out.innerHTML = `<p class="sub tight">Generated ${r.files.length} file(s) — downloads started.</p><p class="sub tight">${summary}</p>
        <ul>${r.files.map((f, i) => `<li><code>${esc(f.name)}</code> (${f.content.length} bytes) <button class="ghost small" onclick="synDownloadIdx(${i})">Download again</button></li>`).join('')}</ul>`;
    } else {
      if (out) out.innerHTML = `<p class="sub tight">Loaded into the target database.</p><p class="sub tight">${summary}</p>`;
      toast('Synthetic data loaded', 'ok');
    }
  } catch (e) { if (out) out.innerHTML = `<div class="error">${esc(e.message)}</div>`; toast(e.message, 'err'); }
}

function synInstallSavedJobHandlers() {
  if (window.__synSavedJobHandlersInstalled) return;
  window.__synSavedJobHandlersInstalled = true;
  document.addEventListener('click', event => {
    const target = event.target;
    const button = target instanceof Element ? target.closest('[data-syn-saved-action]') : null;
    if (!button) return;
    event.preventDefault();
    const action = button.getAttribute('data-syn-saved-action');
    const id = button.getAttribute('data-syn-saved-id');
    const name = button.getAttribute('data-syn-saved-name') || '';
    if (action === 'save') return void synSaveCurrentJob();
    if (action === 'refresh') return void synRefreshSavedJobs();
    if (action === 'run') return void synConfirmRunSavedJob(id);
    if (action === 'load') return void synLoadSavedJob(id);
    if (action === 'request-approval') return void synRequestSavedJobApproval(id);
    if (action === 'approve') return void synApproveSavedJob(id);
    if (action === 'reject') return void synRejectSavedJob(id);
    if (action === 'download-ps1') return void synDownloadSavedJobScript(id, 'ps1');
    if (action === 'download-sh') return void synDownloadSavedJobScript(id, 'sh');
    if (action === 'delete') return void synDeleteSavedJob(id, name);
    if (action === 'close-run-confirm') return void synCloseRunConfirm();
    if (action === 'confirm-run') return void synRunPendingSavedJob();
  });
}

function synInstallImportHandlers() {
  if (window.__synImportHandlersInstalled) return;
  window.__synImportHandlersInstalled = true;
  document.addEventListener('click', event => {
    const target = event.target;
    const button = target instanceof Element ? target.closest('[data-syn-import-action]') : null;
    if (!button) return;
    event.preventDefault();
    event.stopPropagation();
    const action = button.getAttribute('data-syn-import-action');
    if (action === 'selected') return void synImportChecked();
    if (action === 'all') return void synImportAll();
    if (action === 'profile') return void synProfileChecked();
  });
}

const synPublicActions = {
  applyBlueprint,
  initSynthetic,
  synAddBlankTable,
  synAddField,
  synApproveSavedJob,
  synCancelSyntheticJob,
  synCloseRunConfirm,
  synConfirmRunSavedJob,
  synDeleteSavedJob,
  synDownloadSavedJobScript,
  synDropToggle,
  synFkInput,
  synFld,
  synGenChange,
  synGenerate,
  synGenerateWithGame,
  synGoStep,
  synImportAll,
  synImportChecked,
  synLoadActionChanged,
  synLoadSavedJob,
  synLoadSrcTables,
  synLoadTargetSchemas,
  synRefreshSavedJobs,
  synRefreshSyntheticJobs,
  synRejectSavedJob,
  synRemoveField,
  synRemoveTable,
  synRender,
  synRunPendingSavedJob,
  synRequestSavedJobApproval,
  synSaveCurrentJob,
  synSet,
  synSrcDsChanged,
  synSrcSchemaChanged,
  synTableMeta,
  synTargetPrepChanged,
  synToggleAdv,
  synUnlinkFk
};
if (typeof window.synDownload === 'function') synPublicActions.synDownload = window.synDownload;
if (typeof window.synDownloadIdx === 'function') synPublicActions.synDownloadIdx = window.synDownloadIdx;
Object.assign(window, synPublicActions);
window.__syntheticWizardVersion = 'synth-wiz-36';
synInstallSavedJobHandlers();
synInstallImportHandlers();
