/* ForgeTDM console - vanilla JS, no build step */
const $ = id => document.getElementById(id);
let currentUser = null;
let securityState = { users: [], groups: [], roles: [] };
const api = {
  async req(method, url, body) {
    const r = await fetch(url, { method, credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body) });
    const text = await r.text();
    let data = null; try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (!r.ok) {
      if (r.status === 401 && url !== '/api/auth/login') showLogin((data && data.error) || 'Login required');
      throw new Error((data && data.error) || r.status + ' ' + r.statusText);
    }
    return data;
  },
  get: u => api.req('GET', u),
  post: (u, b) => api.req('POST', u, b),
  patch: (u, b) => api.req('PATCH', u, b),
  put: (u, b) => api.req('PUT', u, b),
  del: u => api.req('DELETE', u)
};

function toast(msg, kind) {
  const t = document.createElement('div');
  t.className = 'toast ' + (kind || '');
  t.textContent = msg;
  $('toast').appendChild(t);
  setTimeout(() => t.remove(), 5200);
}

/* In-app modal dialogs (replace native window.confirm / window.prompt). Return a Promise. */
function uiModal(opts) {
  const o = opts || {};
  return new Promise(resolve => {
    const ov = document.createElement('div');
    ov.className = 'ui-modal-overlay';
    const box = document.createElement('div');
    box.className = 'ui-modal';
    if (o.title) {
      const h = document.createElement('div');
      h.className = 'ui-modal-title' + (o.danger ? ' danger' : '');
      h.textContent = o.title;
      box.appendChild(h);
    }
    const m = document.createElement('div');
    m.className = 'ui-modal-msg';
    String(o.message || '').split('\n').forEach((line, i) => {
      if (i) m.appendChild(document.createElement('br'));
      m.appendChild(document.createTextNode(line));
    });
    box.appendChild(m);
    let field = null;
    if (o.input) {
      field = document.createElement('input');
      field.className = 'ui-modal-input';
      field.value = o.defaultValue || '';
      box.appendChild(field);
    }
    const btns = document.createElement('div');
    btns.className = 'ui-modal-btns';
    const cancel = document.createElement('button');
    cancel.className = 'ui-modal-btn cancel';
    cancel.textContent = o.cancelText || 'Cancel';
    const ok = document.createElement('button');
    ok.className = 'ui-modal-btn ok' + (o.danger ? ' danger' : '');
    ok.textContent = o.okText || 'OK';
    btns.appendChild(cancel);
    btns.appendChild(ok);
    box.appendChild(btns);
    ov.appendChild(box);
    document.body.appendChild(ov);
    const cancelVal = o.input ? null : false;
    const okVal = () => (o.input ? (field ? field.value : '') : true);
    const done = (val) => { ov.remove(); document.removeEventListener('keydown', onKey); resolve(val); };
    const onKey = (e) => {
      if (e.key === 'Escape') done(cancelVal);
      else if (e.key === 'Enter') { e.preventDefault(); done(okVal()); }
    };
    document.addEventListener('keydown', onKey);
    cancel.onclick = () => done(cancelVal);
    ok.onclick = () => done(okVal());
    ov.onclick = (e) => { if (e.target === ov) done(cancelVal); };
    if (field) { field.focus(); field.select(); } else ok.focus();
  });
}
function uiConfirm(message, opts) {
  const o = opts || {};
  return uiModal({ title: o.title || 'Please confirm', message, danger: o.danger,
    okText: o.okText || 'OK', cancelText: o.cancelText || 'Cancel' });
}
function uiPrompt(message, defaultValue, opts) {
  const o = opts || {};
  return uiModal({ title: o.title || message, message: o.title ? message : '', input: true,
    defaultValue: defaultValue || '', okText: o.okText || 'OK', cancelText: o.cancelText || 'Cancel' });
}

function setCurrentUser(user) {
  currentUser = user || null;
  const box = $('current-user-box');
  if (!box) return;
  if (!currentUser) {
    box.classList.add('hidden');
    box.innerHTML = '';
    return;
  }
  const roles = (currentUser.roles || []).join(', ') || 'no role';
  box.classList.remove('hidden');
  box.innerHTML = `<b>${esc(currentUser.displayName || currentUser.username)}</b><span>${esc(currentUser.username)}<br>${esc(roles)}</span><button class="ghost small" onclick="logout()">Sign out</button>`;
}

function showLogin(message) {
  const modal = $('login-modal');
  if (!modal) return;
  modal.style.display = 'flex';
  const err = $('login-error');
  if (err) {
    err.textContent = message || '';
    err.classList.toggle('hidden', !message);
  }
  setTimeout(() => $('login-password')?.focus(), 0);
}

function hideLogin() {
  const modal = $('login-modal');
  if (modal) modal.style.display = 'none';
  const err = $('login-error');
  if (err) err.classList.add('hidden');
}

function loginKey(e) {
  if (e.key === 'Enter') login();
}

async function login() {
  const err = $('login-error');
  if (err) err.classList.add('hidden');
  try {
    const res = await api.post('/api/auth/login', {
      username: $('login-username')?.value || '',
      password: $('login-password')?.value || ''
    });
    setCurrentUser(res.user);
    hideLogin();
    await bootApp();
    toast('Signed in as ' + (res.user?.username || 'user'), 'ok');
  } catch (e) {
    if (err) {
      err.textContent = e.message;
      err.classList.remove('hidden');
    }
  }
}

async function logout() {
  try { await api.post('/api/auth/logout', {}); } catch {}
  setCurrentUser(null);
  showLogin('Signed out');
}

async function initAuth() {
  try {
    const me = await api.get('/api/auth/me');
    if (me?.authenticated) {
      setCurrentUser(me.user);
      hideLogin();
      await bootApp();
    } else {
      setCurrentUser(null);
      showLogin();
    }
  } catch {
    setCurrentUser(null);
    showLogin();
  }
}

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const js = s => JSON.stringify(String(s ?? ''));
const pill = (txt, kind) => `<span class="pill ${kind}">${esc(txt)}</span>`;
const statusPill = s => pill(s, { COMPLETED:'good', PASS:'good', ACTIVE:'good', APPROVED:'good',
  FRESH:'good', RUNNING:'info', PENDING:'dim', CANCEL_REQUESTED:'warn', CANCELED:'dim', SUGGESTED:'dim', NOT_PII:'dim', WARN:'warn', EXPIRED:'warn',
  PARTIAL:'warn', STALE:'warn', UNKNOWN:'dim', NEVER_CHECKED:'dim', HEARTBEAT:'info',
  AWAITING_APPROVAL:'warn',
  FAILED:'bad', FAIL:'bad', REJECTED:'bad', RELEASED:'dim' }[s] || 'info');

function setTheme(theme) {
  const next = theme || 'dark';
  document.documentElement.dataset.theme = next;
  localStorage.setItem('forgetdm.theme', next);
  const select = $('theme-select');
  if (select) select.value = next;
}
setTheme(localStorage.getItem('forgetdm.theme') || 'dark');

/* ---------- page-level work tabs ---------- */
const PAGE_TABS = new Set(['datasources','discovery','policies','subset','virtualize','synthetic','jobs','reservations','validation','datasets','security']);

function slug(s) {
  return String(s ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'tab';
}

function tabbedPageId(page) {
  return page?.id?.replace(/^page-/, '') || '';
}

function directCards(page) {
  return [...page.children].filter(el => el.classList?.contains('card'));
}

function visibleTabCards(page) {
  return directCards(page).filter(card => card.style.display !== 'none' && !card.classList.contains('hidden'));
}

function cardTabKey(card, index) {
  return card.id || slug(card.querySelector(':scope > .card-head h3, :scope > h3')?.textContent || `tab-${index + 1}`);
}

function cardTabLabel(card, index) {
  /* tab names come from data-tab-label in the markup so they always describe the step, not the widget */
  return card.dataset.tabLabel || card.querySelector(':scope > .card-head h3, :scope > h3')?.textContent?.trim() || `Tab ${index + 1}`;
}

function ensurePageTabs(page) {
  const pageId = tabbedPageId(page);
  if (!PAGE_TABS.has(pageId)) return;
  const cards = visibleTabCards(page);
  if (cards.length < 2) {
    page.dataset.usePageTabs = 'false';
    page.classList.remove('with-page-tabs');
    page.querySelector(':scope > .page-tab-strip')?.remove();
    directCards(page).forEach(card => card.classList.remove('page-tab-panel', 'page-tab-hidden'));
    return;
  }
  page.dataset.usePageTabs = 'true';
  page.classList.add('with-page-tabs');
  cards.forEach((card, i) => {
    card.classList.add('page-tab-panel');
    card.dataset.tabKey = cardTabKey(card, i);
  });

  let strip = page.querySelector(':scope > .page-tab-strip');
  if (!strip) {
    strip = document.createElement('div');
    strip.className = 'page-tab-strip';
    const firstCard = directCards(page)[0];
    page.insertBefore(strip, firstCard);
  }

  const available = cards.map(card => card.dataset.tabKey);
  const stored = localStorage.getItem('forgetdm.pageTab.' + pageId);
  const current = available.includes(stored) ? stored : available[0];
  strip.innerHTML = cards.map((card, i) => `<button type="button" class="page-tab-btn" data-page-tab="${esc(card.dataset.tabKey)}">${esc(cardTabLabel(card, i))}</button>`).join('');
  strip.querySelectorAll('.page-tab-btn').forEach(btn => {
    btn.onclick = () => activatePageTab(pageId, btn.dataset.pageTab, true);
  });
  activatePageTab(pageId, current, false);
}

function activatePageTab(pageId, key, persist) {
  const page = $('page-' + pageId);
  if (!page) return;
  const cards = visibleTabCards(page);
  if (!cards.some(card => card.dataset.tabKey === key)) key = cards[0]?.dataset.tabKey;
  if (!key) return;
  page.querySelectorAll(':scope > .page-tab-strip .page-tab-btn').forEach(btn =>
    btn.classList.toggle('active', btn.dataset.pageTab === key));
  cards.forEach(card => card.classList.toggle('page-tab-hidden', card.dataset.tabKey !== key));
  if (persist) localStorage.setItem('forgetdm.pageTab.' + pageId, key);
}

function refreshPageTabs(pageOrId) {
  const page = typeof pageOrId === 'string' ? $('page-' + pageOrId) : pageOrId;
  if (!page) return;
  ensurePageTabs(page);
}

/* ---------- panel minimizers ---------- */
function enhanceMinimizers(root = document) {
  const scope = root && root.querySelectorAll ? root : document;
  scope.querySelectorAll('.card').forEach((card, index) => {
    const page = card.closest('.page');
    if (PAGE_TABS.has(tabbedPageId(page))) return;
    if (card.dataset.minimizerReady === 'true') return;
    let head = card.querySelector(':scope > .card-head');
    if (!head) {
      const title = [...card.children].find(el => el.tagName === 'H3');
      if (!title) return;
      head = document.createElement('div');
      head.className = 'card-head';
      card.insertBefore(head, title);
      head.appendChild(title);
    }
    let actions = head.querySelector(':scope > .card-actions');
    if (!actions) {
      const title = head.querySelector('h3');
      actions = document.createElement('div');
      actions.className = 'card-actions';
      [...head.children].filter(el => el !== title).forEach(el => actions.appendChild(el));
      head.appendChild(actions);
    }
    const titleText = head.querySelector('h3')?.textContent?.trim() || 'panel';
    const pageId = card.closest('.page')?.id || 'global';
    const key = card.id || `${pageId}:${titleText.toLowerCase().replace(/[^a-z0-9]+/g, '-')}:${index}`;
    card.dataset.cardKey = key;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'card-minimize';
    button.onclick = () => setCardCollapsed(card, !card.classList.contains('is-collapsed'), true);
    actions.appendChild(button);
    card.dataset.minimizerReady = 'true';
    setCardCollapsed(card, localStorage.getItem('forgetdm.card.' + key) === 'collapsed', false);
  });
}

function setCardCollapsed(card, collapsed, persist) {
  card.classList.toggle('is-collapsed', collapsed);
  const button = card.querySelector(':scope > .card-head .card-minimize');
  if (button) {
    button.textContent = collapsed ? '+' : '-';
    button.title = collapsed ? 'Expand panel' : 'Minimize panel';
    button.setAttribute('aria-label', button.title);
    button.setAttribute('aria-expanded', String(!collapsed));
  }
  if (persist && card.dataset.cardKey) {
    const key = 'forgetdm.card.' + card.dataset.cardKey;
    if (collapsed) localStorage.setItem(key, 'collapsed');
    else localStorage.removeItem(key);
  }
}

/* ---------- navigation ---------- */
document.querySelectorAll('.nav-item').forEach(n => n.onclick = () => showPage(n.dataset.page));
/* Collapse / expand a sidebar group. */
function toggleNavGroup(titleEl) {
  titleEl.closest('.nav-group')?.classList.toggle('collapsed');
}
function showPage(p) {
  document.querySelectorAll('.nav-item').forEach(n => n.classList.toggle('active', n.dataset.page === p));
  // make sure the active page's group is expanded so the highlight is visible
  document.querySelector('.nav-item[data-page="' + p + '"]')?.closest('.nav-group')?.classList.remove('collapsed');
  document.querySelectorAll('.page').forEach(x => x.classList.toggle('active', x.id === 'page-' + p));
  const loader = ({ dashboard: loadStats, challenges: renderChallenges, datasources: loadDs, query: loadQuery, discovery: loadDiscovery, ri: loadRi, policies: loadPolicies,
     studio: loadFunctions, maskscripts: loadMaskScriptsPage, copybook: cbInit, mainframe: mfInit, mfgen: mfgInit, subset: async () => { fillDsSelects(); subsetTraversalChanged(); if ($('sub-ds')?.value) await loadSubsetSchemas(); },
     agent: loadAgent,
     mapping: loadMapping,
     datasets: loadAccessDefs,
     'business-entities': loadBusinessEntities,
     virtualize: loadVirtualization,
     synthetic: initSynthetic, jobs: async () => { await loadJobs(); await loadJobSchemas(); jobTypeChanged(); },
     reservations: loadReservations, validation: loadValidation, audit: loadAudit, security: loadSecurity }[p] || (()=>{}));
  return Promise.resolve(loader())
    .then(() => {
      refreshPageTabs(p);
      enhanceMinimizers($('page-' + p));
    })
    .catch(e => toast(e.message, 'err'));
}

/* navigate to a page and land on a specific tab (used so every action ends on its result) */
async function goTo(page, tabKey) {
  await showPage(page);
  if (tabKey) activatePageTab(page, tabKey, true);
}

/* ---------- shared state ---------- */
let dataSources = [], policiesList = [], functionsList = [], datasetsList = [], selectedPolicy = null;
let nativeLoaderStatus = [];
// Names of saved masking scripts, cached so the SCRIPT function's param can render a picker
// (not a free-text box) wherever mask params are shown — rule editor, discovery, Masking Studio.
let maskScriptNames = [];
async function loadScriptNames() {
  try { maskScriptNames = (await api.get('/api/policies/scripts')).map(s => s.name).filter(Boolean); }
  catch { /* keep whatever we had; picker falls back to a hint */ }
}
let selectedAdId = null, adProfiles = [], adOverrides = [], adTables = [], adColumns = [];
let tableMapState = { targetTables: [], addSourceTables: [] };
let columnMapState = { profileIdx: null, sourceColumns: [], targetColumns: [], rows: [], policyRules: [] };
let adCustomPks = [], adUserRels = [], adRelationships = [], adTraversalRules = [];
let businessEntities = [], selectedBusinessEntityId = null, businessEntityDetail = null;
let businessEntitySnapshots = [], businessEntityReservations = [], businessEntityIdentities = [], businessEntityIdentityResolve = null;
let businessEntitySyncPolicies = [], businessEntitySyncRun = null, selectedBusinessEntitySyncPolicyId = null;
let businessEntityEnterprise = { issuePackages: [], lookalikeProfiles: [], catalogAssets: [], governanceRequests: [], executionPlans: [], operationalPackages: [], packageVersions: [], packagePromotions: [], executionRuns: [], loaderStrategies: [] };
let businessEntityFlows = [], businessEntityFlowDraft = null, selectedBusinessEntityFlowId = null, selectedBusinessEntityFlowNodeKey = null, businessEntityFlowDebugRun = null, businessEntityFlowValidation = null;
let businessEntityActiveTab = localStorage.getItem('forgetdm.be.tab') || 'model';
let adPiiCoverage = null, adDrift = null;   // pre-provision guardrails: PII masking coverage + schema drift
let adDirty = false;   // staged-but-unsaved DataScope edits (profiles grid, custom PKs) — guards blueprint switches
let relCanvasColumns = {};        // tableName → [colName, ...]
let relDragState = null;
let _relPortPositions = {};       // key → {x, cy, table, col, side} for drag hit detection
let relFormPairs = [{ parentCol: '', childCol: '' }];  // multi-column form pairs
let relPendingRel = { parentTable: null, childTable: null, colPairs: [] }; // canvas pending    // active drag state for visual canvas
let generatorCatalog = [], selectedGenerator = null;
let virtualSnapshots = [], virtualDbs = [];
let schemaCache = {}, tableCache = {};
let GENERATORS = ["FIRST_NAME","LAST_NAME","FULL_NAME","MALE_FIRST_NAME","FEMALE_FIRST_NAME","FULL_NAME_BY_LOCALE","EMAIL","PHONE_US","SSN","CREDIT_CARD_VISA","CREDIT_CARD_MC",
  "DOB_ADULT","DATE_RECENT","STREET_ADDRESS","CITY","STATE","ZIP","COMPANY","UUID","SEQUENCE","INT_RANGE",
  "DECIMAL_RANGE","BOOLEAN","STATUS","CURRENCY_USD","IBAN_LIKE"];

const MASK_PARAM_META = {
  BY_INDICATOR: ['Indicator column', 'Map: P=PHONE|E=EMAIL|*=FORMAT_PRESERVE'],
  PARTIAL_MASK: ['Mask pattern (regex, default [A-Za-z]+)', 'Function per match (default FIRST_NAME)'],
  PHONE_SPLIT: ['THIS column name', 'Sibling columns in order: area_code,exchange,line_no'],
  SSN_SPLIT: ['THIS column name', 'Sibling columns in order: ssn_area,ssn_group,ssn_serial'],
  DATE_SPLIT: ['THIS column name', 'Role map: dd=dob_day,mm=dob_month,yyyy=dob_year'],
  AGE: ['Shift: +1y -2m +3w +10d', 'Date format'],
  SCRIPT: ['Script name (Policies → Scripts)', 'Extra arg (script sees it as "param")'],
  FIRST_NAME: [null, 'Output case'],
  LAST_NAME: [null, 'Output case'],
  FULL_NAME: ['Name format', 'Output case'],
  EMAIL: ['Email mode', 'Domain handling'],
  PHONE: ['Phone mode', 'Phone handling'],
  SSN: ['SSN mode', 'SSN format'],
  CREDIT_CARD: ['Card mode', 'Card format'],
  COMPANY: [null, 'Output case'],
  ADDRESS_STREET: [null, 'Output case'],
  ADDRESS_US: ['Address part', 'State handling'],
  FORMAT_PRESERVE: [null, 'Output case'],
  DATE_SHIFT: ['Max days', 'Date format'],
  DOB_AGE_BAND: ['Band years', 'Date format'],
  CITY_STATE_ZIP: ['Part: CITY/STATE/ZIP/FULL', 'State handling'],
  HASH_LOV: ['Seedlist file', 'Output case'],
  FIXED: ['Fixed value', 'Output case'],
  SEQUENCE: ['Prefix', null]
};

const NAME_FORMAT_OPTIONS = ['FIRST LAST', 'FIRST MIDDLE LAST', 'FIRST MID LAST', 'LAST FIRST',
  'LAST MIDDLE FIRST', 'LAST MID FIRST', 'LAST, FIRST', 'LAST, FIRST MIDDLE', 'FIRST, LAST'];
const CASE_OPTIONS = ['PROPER', 'UPPER', 'LOWER', 'AS_IS'];
/* Date-format picker for AGE / DATE_SHIFT / DOB_AGE_BAND. Values are Java DateTimeFormatter patterns;
   blank = auto-detect (ISO yyyy-MM-dd, dd/MM/yyyy, dd-MM-yyyy). */
const DATE_FORMAT_OPTIONS = [
  ['', 'Auto-detect (ISO / dd/MM/yyyy / dd-MM-yyyy)'],
  ['yyyy-MM-dd', 'yyyy-MM-dd  (2026-11-05)'],
  ['dd/MM/yyyy', 'dd/MM/yyyy  (05/11/2026)'],
  ['MM/dd/yyyy', 'MM/dd/yyyy  (11/05/2026, US)'],
  ['dd-MM-yyyy', 'dd-MM-yyyy  (05-11-2026)'],
  ['MM-dd-yyyy', 'MM-dd-yyyy  (11-05-2026, US)'],
  ['yyyy/MM/dd', 'yyyy/MM/dd  (2026/11/05)'],
  ['dd.MM.yyyy', 'dd.MM.yyyy  (05.11.2026)'],
  ['yyyyMMdd',   'yyyyMMdd  (20261105, compact)'],
  ['dd-MMM-yyyy', 'dd-MMM-yyyy  (05-Nov-2026, Oracle-style)'],
  ['MMM dd, yyyy', 'MMM dd, yyyy  (Nov 05, 2026)'],
  ['yyyyDDD', 'yyyyDDD Julian  (2026309 = day 309 of 2026)'],
  ['yyDDD', 'yyDDD Julian  (26309, mainframe packed)'],
  ['CYYDDD', 'CYYDDD Julian  (126309, JD Edwards)']
];
const GEO_PART_OPTIONS = ['FULL', 'CITY', 'STATE', 'ZIP'];
const ADDRESS_PART_OPTIONS = ['FULL', 'LINE1', 'LINE2', 'CITY', 'STATE', 'ZIP', 'COUNTRY'];
const STATE_MODE_OPTIONS = [['PRESERVE_STATE', 'Preserve source state'], ['', 'Obfuscate state']];
const EMAIL_MODE_OPTIONS = [
  ['NAME_SAFE', 'Name-based safe email'],
  ['USER_SAFE', 'Username safe email'],
  ['HASH_LOCAL', 'Hashed local-part'],
  ['REDACT_LOCAL', 'Redact local-part'],
  ['PRESERVE_DOMAIN', 'Name-based, keep domain']
];
const EMAIL_DOMAIN_OPTIONS = [
  ['SAFE_DOMAIN', 'Use safe .test domain'],
  ['PRESERVE_DOMAIN', 'Preserve original domain']
];
const PHONE_MODE_OPTIONS = [
  ['FORMAT_PRESERVE', 'Format-preserving'],
  ['PRESERVE_AREA', 'Preserve country/area'],
  ['KEEP_LAST4', 'Mask except last 4'],
  ['REDACT', 'Redact all digits'],
  ['DIGITS_ONLY', 'Generate digits only']
];
const PHONE_HANDLING_OPTIONS = [
  ['PRESERVE_COUNTRY', 'Preserve country code'],
  ['OBFUSCATE_ALL', 'Obfuscate all digits']
];
const SSN_MODE_OPTIONS = [
  ['VALID_PRESERVE_AREA', 'Valid SSN, keep area'],
  ['VALID_RANDOM_AREA', 'Valid SSN, new area'],
  ['KEEP_LAST4', 'Mask except last 4'],
  ['REDACT', 'Redact all digits'],
  ['FORMAT_PRESERVE', 'Format-preserving digits']
];
const SSN_FORMAT_OPTIONS = [
  ['PRESERVE_FORMAT', 'Preserve source format'],
  ['DASHED', 'Dashed 123-45-6789'],
  ['DIGITS_ONLY', 'Digits only']
];
const CARD_MODE_OPTIONS = [
  ['VALID_PRESERVE_BIN', 'Valid card, keep BIN'],
  ['VALID_RANDOM_BIN', 'Valid card, new test BIN'],
  ['VALID_KEEP_LAST4', 'Valid card, keep last 4'],
  ['FORMAT_PRESERVE', 'Valid card, preserve format']
];
const CARD_FORMAT_OPTIONS = [
  ['PRESERVE_FORMAT', 'Preserve source separators'],
  ['SPACES', 'Groups with spaces'],
  ['DASHES', 'Groups with dashes'],
  ['DIGITS_ONLY', 'Digits only']
];

const PII_TYPE_OPTIONS = ['FIRST_NAME','LAST_NAME','FULL_NAME','EMAIL','PHONE','SSN','CREDIT_CARD','DOB',
  'FULL_ADDRESS','ADDRESS','CITY','STATE','ZIP','COMPANY','BANK_ACCOUNT','IP_ADDRESS','TAX_ID','MANUAL_PII'];
const PII_DEFAULT_FUNCTIONS = {
  EMAIL:'EMAIL', SSN:'SSN', CREDIT_CARD:'CREDIT_CARD', FIRST_NAME:'FIRST_NAME', LAST_NAME:'LAST_NAME',
  FULL_NAME:'FULL_NAME', DOB:'DOB_AGE_BAND', PHONE:'PHONE', FULL_ADDRESS:'ADDRESS_US',
  ADDRESS:'ADDRESS_STREET', CITY:'CITY_STATE_ZIP', STATE:'CITY_STATE_ZIP', ZIP:'CITY_STATE_ZIP',
  COMPANY:'COMPANY'
};

async function refreshShared() {
  [dataSources, policiesList, datasetsList, nativeLoaderStatus] = await Promise.all([
    api.get('/api/datasources'), api.get('/api/policies'), api.get('/api/datasets'),
    api.get('/api/datasources/native-loaders').catch(() => [])
  ]);
  fillDsSelects();
}

function fillDsSelects() {
  const opts = dataSources.map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.role)})</option>`).join('');
  setOptionsPreserve('disc-ds', '<option value="">Select data source</option>' + opts);
  setOptionsPreserve('sub-ds', '<option value="">Select data source</option>' + opts);
  setOptionsPreserve('ad-ds', '<option value="">Select data source</option>' + opts);
  setOptionsPreserve('virt-source', '<option value="">Select data source</option>' + opts);
  setOptionsPreserve('ri-ds', '<option value="">Select data source</option>' + opts);
  ['syn-ds','job-src','job-tgt','res-ds','val-src','val-tgt']
    .forEach(id => setOptionsPreserve(id, opts));
  const polOpts = '<option value="">(none)</option>' +
    policiesList.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join('');
  ['job-policy','val-policy'].forEach(id => setOptionsPreserve(id, polOpts));
  // Access Definition picker for SUBSET_MASK jobs
  const dsOpts = '<option value="">None (manual settings)</option>' +
    datasetsList.map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
  setOptionsPreserve('job-dataset', dsOpts);
  setOptionsPreserve('be-from-dataset', '<option value="">Choose blueprint</option>' +
    datasetsList.map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join(''));
}

function setOptionsPreserve(id, html) {
  const el = $(id);
  if (!el) return;
  const current = el.value;
  el.innerHTML = html;
  if ([...el.options].some(o => o.value === current)) el.value = current;
}

async function loadSchemas(dsId) {
  if (!dsId) return [];
  if (schemaCache[dsId]) return schemaCache[dsId];
  const rows = await api.get(`/api/datasources/${dsId}/schemas`);
  schemaCache[dsId] = rows;
  return rows;
}

async function fillSchemaSelect(dsId, selectId, placeholder = 'Select schema') {
  try {
    const rows = await loadSchemas(dsId);
    const opts = `<option value="">${esc(placeholder)}</option>` +
      rows.map(r => `<option value="${esc(r.schema)}">${esc(r.schema)}${r.current ? ' (current)' : ''}</option>`).join('');
    setOptionsPreserve(selectId, opts);
    const el = $(selectId);
    if (el && !el.value && rows.length === 1) el.value = rows[0].schema;
    return rows;
  } catch (e) {
    setOptionsPreserve(selectId, `<option value="">Could not load schemas</option>`);
    toast(`Could not load schemas: ${e.message}`, 'err');
    return [];
  }
}

async function loadTables(dsId, schema) {
  if (!dsId || !schema) return [];
  const key = `${dsId}|${schema}`;
  if (tableCache[key]) return tableCache[key];
  const rows = await api.get(`/api/datasources/${dsId}/tables?schema=${encodeURIComponent(schema)}`);
  tableCache[key] = rows.map(r => r.table);
  return tableCache[key];
}

async function fillTableSelect(dsId, schema, selectId, placeholder = 'Select table') {
  try {
    const tables = await loadTables(dsId, schema);
    setOptionsPreserve(selectId, `<option value="">${esc(placeholder)}</option>` + tables.map(t => `<option>${esc(t)}</option>`).join(''));
    return tables;
  } catch (e) {
    setOptionsPreserve(selectId, `<option value="">Could not load tables</option>`);
    toast(`Could not load tables: ${e.message}`, 'err');
    return [];
  }
}

/* ---------- dashboard ---------- */
async function loadStats() {
  const s = await api.get('/api/dashboard/stats');
  const labels = { dataSources:'Data Sources', classifications:'PII Findings', policies:'Policies',
                   jobs:'Jobs', virtualSnapshots:'Snapshots', vdbs:'VDBs',
                   reservations:'Reservations', validationReports:'Validation Reports' };
  $('stats').innerHTML = Object.entries(s)
    .map(([k,v]) => `<div class="stat"><div class="n">${v}</div><div class="l">${labels[k]||k}</div></div>`).join('');
}

/* ---------- data sources ---------- */
/* ---------- data source catalog (K2View-style) ---------- */
const dsState = { status: {}, editing: null };
const ENGINE_META = {
  POSTGRES: { label: 'PG', cls: 'e-pg' }, MYSQL: { label: 'MY', cls: 'e-my' }, ORACLE: { label: 'OR', cls: 'e-or' },
  SQLSERVER: { label: 'MS', cls: 'e-ms' }, DB2: { label: 'DB2', cls: 'e-db2' }, DB2UDB: { label: 'DB2', cls: 'e-db2' },
  DB2ZOS: { label: 'zOS', cls: 'e-db2' }, H2: { label: 'H2', cls: 'e-h2' }, GENERIC: { label: 'DB', cls: 'e-gen' }
};
const ENGINE_TEMPLATES = {
  POSTGRES: 'jdbc:postgresql://localhost:5432/dbname',
  MYSQL: 'jdbc:mysql://localhost:3306/dbname',
  ORACLE: 'jdbc:oracle:thin:@localhost:1521/orclpdb1',
  SQLSERVER: 'jdbc:sqlserver://localhost:1433;databaseName=dbname;encrypt=false',
  DB2: 'jdbc:db2://localhost:50000/dbname', DB2UDB: 'jdbc:db2://localhost:50000/dbname',
  DB2ZOS: 'jdbc:db2://host:446/LOCATION', H2: 'jdbc:h2:mem:dbname;MODE=PostgreSQL',
  GENERIC: 'jdbc:vendor://host:port/dbname'
};
function dsEngine(kind) { return ENGINE_META[(kind || '').toUpperCase()] || { label: 'DB', cls: 'e-gen' }; }
function dsEnvCls(env) { return 'env-' + (env || '').toLowerCase(); }

async function loadDs() {
  await refreshShared();
  renderDsCatalog();
  renderNativeLoaders();
}

function renderDsCatalog() {
  const list = dataSources || [];
  // KPI tiles
  const sources = list.filter(d => d.role === 'SOURCE' || d.role === 'BOTH').length;
  const targets = list.filter(d => d.role === 'TARGET' || d.role === 'BOTH').length;
  const engines = new Set(list.map(d => (d.kind || '').toUpperCase())).size;
  const online = list.filter(d => dsState.status[d.id]?.state === 'online').length;
  const kpis = [['Connections', list.length], ['Sources', sources], ['Targets', targets], ['Engines', engines], ['Online', online]];
  const kEl = $('ds-kpis');
  if (kEl) kEl.innerHTML = kpis.map(([l, v]) => `<div class="ds-kpi"><div class="v">${v}</div><div class="l">${l}</div></div>`).join('');

  // filter option lists (preserve selection)
  dsFillSelect('ds-filter-engine', [...new Set(list.map(d => (d.kind || '').toUpperCase()).filter(Boolean))].sort(), 'All engines');
  dsFillSelect('ds-filter-env', [...new Set(list.map(d => d.environment).filter(Boolean))].sort(), 'All environments');

  const q = ($('ds-search')?.value || '').toLowerCase();
  const fr = $('ds-filter-role')?.value || '', fe = $('ds-filter-engine')?.value || '', fv = $('ds-filter-env')?.value || '';
  const shown = list.filter(d => {
    if (fr && d.role !== fr) return false;
    if (fe && (d.kind || '').toUpperCase() !== fe) return false;
    if (fv && d.environment !== fv) return false;
    if (q && !`${d.name} ${d.jdbcUrl} ${d.tags || ''} ${d.kind}`.toLowerCase().includes(q)) return false;
    return true;
  });

  const cards = $('ds-cards');
  if (!cards) return;
  if (!list.length) { cards.innerHTML = '<div class="empty">No connections yet. Click “+ Add connection” to register your first source or target.</div>'; return; }
  cards.innerHTML = shown.length ? shown.map(dsCardHtml).join('') : '<div class="empty">No connections match your filters.</div>';
  renderNativeLoaders();
}

function renderNativeLoaders() {
  const box = $('native-loader-panel');
  if (!box) return;
  const rows = nativeLoaderStatus || [];
  if (!rows.length) {
    box.innerHTML = '<h3>Native loader health</h3><div class="empty small-empty">Native loader status is not available.</div>';
    return;
  }
  const ready = rows.filter(r => r.nativeAvailable).length;
  box.innerHTML = `<div class="row push"><div><h3>Native loader health</h3><p class="sub tight">Fast vendor loaders are optional. If one is not ready, ForgeTDM uses the JDBC fallback.</p></div><span class="pill info">${ready}/${rows.length} ready</span></div>
    <div class="ds-cards native-loader-cards">${rows.map(nativeLoaderCard).join('')}</div>`;
}

function nativeLoaderCard(r) {
  const status = r.nativeAvailable ? 'READY' : (r.enabled ? 'MISSING BINARY' : 'FALLBACK');
  const pill = r.nativeAvailable ? 'ok' : (r.enabled ? 'warn' : 'dim');
  return `<div class="ds-card native-loader-card">
    <div class="ds-card-top">
      <div class="ds-engine ${dsEngine(r.engine).cls}">${esc(dsEngine(r.engine).label)}</div>
      <div class="ds-card-id">
        <div class="ds-card-name">${esc(r.strategy)}</div>
        <div class="ds-card-sub">${esc(r.label || r.engine)}</div>
      </div>
    </div>
    <div class="ds-card-pills"><span class="pill ${pill}">${esc(status)}</span><span class="pill dim">fallback ${esc(r.fallback || '-')}</span></div>
    <div class="ds-card-url mono" title="${esc(r.binaryPath || '')}">${r.builtIn ? 'Built in - no setup needed' : esc(r.binaryPath || r.hint || '')}</div>
    <div class="be-card-meta">${r.enabledEnv ? esc(r.enabledEnv) + ' / ' + esc(r.binaryEnv || '') : 'Postgres driver path'}</div>
  </div>`;
}

function dsFillSelect(id, values, allLabel) {
  const el = $(id); if (!el) return;
  const cur = el.value;
  el.innerHTML = `<option value="">${allLabel}</option>` + values.map(v => `<option${v === cur ? ' selected' : ''}>${esc(v)}</option>`).join('');
  el.value = cur;
}

function dsParseHost(url) {
  if (!url) return '';
  const u = url.replace(/^jdbc:/, '');
  let m;
  if ((m = u.match(/\/\/([^:\/;]+)(?::\d+)?;.*?databaseName=([^;?\s]+)/i))) return `${m[1]} / ${m[2]}`;
  if ((m = u.match(/\/\/([^:\/;]+)(?::\d+)?\/([^;?\s]+)/))) return `${m[1]} / ${m[2]}`;
  if ((m = u.match(/@\/?\/?([^:\/]+)(?::\d+)?[:/]([^;?\s]+)/))) return `${m[1]} / ${m[2]}`;
  if ((m = u.match(/h2:\w*:?([^;]+)/))) return m[1];
  if ((m = u.match(/\/\/([^:\/;]+)/))) return m[1];
  return '';
}

function dsHealthChip(st) {
  switch (st && st.state) {
    case 'testing': return '<span class="ds-health testing">◌ Testing…</span>';
    case 'online': return `<span class="ds-health online" title="${esc(st.info || '')}">● Online${st.info ? ' · ' + esc(st.info) : ''}</span>`;
    case 'offline': return `<span class="ds-health offline" title="${esc(st.info || '')}">○ Offline</span>`;
    default: return '<span class="ds-health untested">○ Not tested</span>';
  }
}

function dsCardHtml(d) {
  const eng = dsEngine(d.kind);
  const host = dsParseHost(d.jdbcUrl);
  const tags = (d.tags || '').split(',').map(t => t.trim()).filter(Boolean);
  return `<div class="ds-card">
    <div class="ds-card-top">
      <div class="ds-engine ${eng.cls}">${eng.label}</div>
      <div class="ds-card-id">
        <div class="ds-card-name">${esc(d.name)}</div>
        <div class="ds-card-sub">${esc(d.kind)}${host ? ' · ' + esc(host) : ''}</div>
      </div>
    </div>
    <div class="ds-card-pills">${statusPill(d.role)}${d.environment ? `<span class="env-pill ${dsEnvCls(d.environment)}">${esc(d.environment)}</span>` : ''}</div>
    <div class="ds-card-url mono" title="${esc(d.jdbcUrl)}">${esc(d.jdbcUrl)}</div>
    ${tags.length ? `<div class="ds-tags">${tags.map(t => `<span class="ds-tag">${esc(t)}</span>`).join('')}</div>` : ''}
    <div class="ds-health-row">${dsHealthChip(dsState.status[d.id])}</div>
    <div class="ds-card-foot">
      <button class="small ghost" onclick="testDs(${d.id})">Test</button>
      <button class="small ghost" onclick="dsBrowse(${d.id})">Browse</button>
      <button class="small ghost" onclick="dsEdit(${d.id})">Edit</button>
      <button class="small danger" onclick="delDs(${d.id})">Delete</button>
    </div>
    <div class="ds-browse" id="ds-browse-${d.id}"></div>
  </div>`;
}

function dsOpenAdd() {
  dsState.editing = null;
  ['ds-name', 'ds-url', 'ds-user', 'ds-pass', 'ds-tags'].forEach(i => { const e = $(i); if (e) e.value = ''; });
  $('ds-kind').value = 'POSTGRES'; $('ds-role').value = 'SOURCE'; $('ds-env').value = '';
  $('ds-form-title').textContent = 'Add connection';
  $('ds-save-btn').textContent = 'Add connection';
  $('ds-form-status').textContent = '';
  $('ds-form').classList.remove('hidden');
  refreshPageTabs('datasources');
  activatePageTab('datasources', 'ds-form', true);
  $('ds-name').focus();
}

function dsEdit(id) {
  const d = dataSources.find(x => x.id === id); if (!d) return;
  dsState.editing = id;
  $('ds-name').value = d.name || ''; $('ds-kind').value = (d.kind || 'POSTGRES'); $('ds-role').value = d.role || 'SOURCE';
  $('ds-env').value = d.environment || ''; $('ds-url').value = d.jdbcUrl || ''; $('ds-user').value = d.username || '';
  $('ds-pass').value = ''; $('ds-tags').value = d.tags || '';
  $('ds-form-title').textContent = 'Edit connection — ' + d.name;
  $('ds-save-btn').textContent = 'Save changes';
  $('ds-form-status').textContent = '';
  $('ds-form').classList.remove('hidden');
  refreshPageTabs('datasources');
  activatePageTab('datasources', 'ds-form', true);
  $('ds-form').scrollIntoView({ behavior: 'smooth', block: 'nearest' });
}

function dsCloseForm() {
  $('ds-form').classList.add('hidden');
  refreshPageTabs('datasources');
  activatePageTab('datasources', 'native-loader-panel', true);
}

function dsApplyTemplate(onlyIfEmpty) {
  const url = $('ds-url');
  if (onlyIfEmpty && url.value.trim()) return;
  url.value = ENGINE_TEMPLATES[$('ds-kind').value] || '';
}

function dsFormBody() {
  return {
    name: $('ds-name').value.trim(), kind: $('ds-kind').value, role: $('ds-role').value,
    environment: $('ds-env').value || null, tags: $('ds-tags').value.trim() || null,
    jdbcUrl: $('ds-url').value.trim(), username: $('ds-user').value, password: $('ds-pass').value
  };
}

async function dsTestForm() {
  const s = $('ds-form-status'); s.className = 'ds-form-status testing'; s.textContent = 'Testing…';
  try {
    const r = await api.post('/api/datasources/test-connection', dsFormBody());
    s.className = 'ds-form-status ok'; s.textContent = `Connected: ${r.product || ''} ${r.version || ''}`.trim();
  } catch (e) { s.className = 'ds-form-status err'; s.textContent = e.message; }
}

async function saveDs() {
  const body = dsFormBody();
  if (!body.name || !body.jdbcUrl) { toast('Name and JDBC URL are required', 'err'); return; }
  try {
    if (dsState.editing) await api.put('/api/datasources/' + dsState.editing, body);
    else await api.post('/api/datasources', body);
    toast(dsState.editing ? 'Connection updated' : 'Connection registered', 'ok');
    dsCloseForm();
    await loadDs();
  } catch (e) { toast(e.message, 'err'); }
}

async function testDs(id) {
  dsState.status[id] = { state: 'testing' }; renderDsCatalog();
  try {
    const r = await api.post(`/api/datasources/${id}/test`);
    const ver = (r.version || '').split(/\s+/)[0];
    dsState.status[id] = { state: 'online', info: `${r.product || ''} ${ver}`.trim() };
  } catch (e) { dsState.status[id] = { state: 'offline', info: e.message }; }
  renderDsCatalog();
}

async function testAllDs() {
  for (const d of (dataSources || [])) { await testDs(d.id); }
}

async function dsBrowse(id) {
  const el = $('ds-browse-' + id); if (!el) return;
  if (el.dataset.open === '1') { el.dataset.open = '0'; el.innerHTML = ''; return; }
  el.dataset.open = '1'; el.innerHTML = '<div class="loading">Loading schemas…</div>';
  try {
    const s = await api.get(`/api/datasources/${id}/schemas`);
    el.innerHTML = s.length
      ? `<div class="ds-schemas">${s.map(x => `<span class="ds-schema">${esc(x.schema || x.name || '')}${x.tableCount != null ? ` <b>${x.tableCount}</b>` : ''}</span>`).join('')}</div>`
      : '<div class="empty">No user schemas.</div>';
  } catch (e) { el.innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

async function delDs(id) {
  const d = (dataSources || []).find(x => x.id === id);
  if (!await uiConfirm(`Delete connection “${d ? d.name : id}”? This cannot be undone.`, {danger: true, okText: 'Delete'})) return;
  try { delete dsState.status[id]; await api.del('/api/datasources/' + id); loadDs(); }
  catch (e) { toast(e.message, 'err'); }
}

/* ---------- data explorer (read-only query runner) ---------- */
let qLastResult = null;

async function loadQuery() {
  await refreshShared();
  const sel = $('q-ds'); if (!sel) return;
  const cur = sel.value;
  sel.innerHTML = '<option value="">Select data source</option>' +
    (dataSources || []).map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join('');
  sel.value = cur;
}

async function qDsChanged() {
  $('q-schema').innerHTML = '<option value="">—</option>';
  $('q-table').innerHTML = '<option value="">—</option>';
  const ds = $('q-ds').value;
  if (!ds) return;
  await fillSchemaSelect(ds, 'q-schema');
  // fillSchemaSelect auto-selects when there's a single schema, but that doesn't fire onchange,
  // so load the tables explicitly.
  await qSchemaChanged();
}

async function qSchemaChanged() {
  const ds = $('q-ds').value, schema = $('q-schema').value;
  $('q-table').innerHTML = '<option value="">—</option>';
  if (!ds || !schema) return;
  await fillTableSelect(ds, schema, 'q-table', 'Select table');
}

function qTableChanged() {
  const schema = $('q-schema').value, table = $('q-table').value;
  if (table) $('q-sql').value = `SELECT * FROM ${schema ? schema + '.' : ''}${table}`;
}

function qSqlKey(e) { if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); runQuery(); } }

async function runQuery() {
  const ds = $('q-ds').value, sql = $('q-sql').value.trim();
  if (!ds) { toast('Pick a data source', 'err'); return; }
  if (!sql) { toast('Enter a SELECT query', 'err'); return; }
  $('q-status').textContent = 'Running…';
  $('q-results').innerHTML = '<div class="loading">Running…</div>';
  $('q-csv-btn').disabled = true;
  try {
    const r = await api.post('/api/query/run', { dataSourceId: parseInt(ds), sql });
    qLastResult = r;
    renderQueryResults(r);
  } catch (e) {
    qLastResult = null;
    $('q-results').innerHTML = `<div class="error">${esc(e.message)}</div>`;
    $('q-status').textContent = '';
    toast(e.message, 'err');
  }
}

function renderQueryResults(r) {
  const cols = r.columns || [], rows = r.rows || [];
  $('q-status').textContent = `${r.rowCount} row(s)${r.truncated ? ' · truncated at 1000' : ''} · ${r.elapsedMs} ms`;
  $('q-csv-btn').disabled = !rows.length;
  if (!cols.length) { $('q-results').innerHTML = '<div class="empty">Query returned no columns.</div>'; return; }
  const head = `<tr><th class="q-rownum">#</th>${cols.map(c => `<th>${esc(c)}</th>`).join('')}</tr>`;
  const body = rows.map((row, i) => `<tr><td class="q-rownum">${i + 1}</td>${
    row.map(v => `<td>${v === null ? '<span class="muted">NULL</span>' : esc(String(v))}</td>`).join('')}</tr>`).join('');
  const banner = r.truncated ? '<div class="q-trunc">Showing the first 1000 rows. Add a WHERE clause to narrow the result.</div>' : '';
  $('q-results').innerHTML = banner + `<div class="q-scroll"><table class="q-table"><thead>${head}</thead><tbody>${body}</tbody></table></div>`;
}

function qDownloadCsv() {
  if (!qLastResult || !qLastResult.columns) return;
  const cell = s => `"${String(s === null || s === undefined ? '' : s).replace(/"/g, '""')}"`;
  const lines = [qLastResult.columns.map(cell).join(',')]
    .concat((qLastResult.rows || []).map(r => r.map(cell).join(',')));
  const blob = new Blob([lines.join('\r\n')], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'query-results.csv';
  a.click();
  URL.revokeObjectURL(a.href);
}

/* ---------- discovery ---------- */
let discProgressTimer = null, discActiveJobId = null, discLatestJob = null, discResultTypeScope = new Set();

async function loadDiscovery() {
  await refreshShared();
  await loadPiiTypeScope();
  if ($('disc-ds')?.value) await loadDiscoverySchemas();
  else renderDiscoveryProgress(null);
}

async function loadDiscoverySchemas() {
  const ds = $('disc-ds').value;
  setOptionsPreserve('disc-schema', '<option value="">Select schema</option>');
  $('disc-table').innerHTML = '<div class="empty">Select a schema, then scan.</div>';
  setOptionsPreserve('disc-table-filter', '<option value="">All tables</option>');
  $('disc-column-review').innerHTML = '<div class="empty">Pick a single table above to review every column, including ones not flagged as PII.</div>';
  $('disc-policy-list').innerHTML = '<div class="empty">Select a data source and schema.</div>';
  if (!ds) { renderDiscoveryProgress(null); return; }
  await fillSchemaSelect(ds, 'disc-schema');
  await loadDiscoveryContext();
}

async function loadDiscoveryContext() {
  discResultTypeScope = new Set();
  erGraph = null; erSelected = null;
  await loadDiscoveryTables();
  await refreshDiscoveryProgress();
  await Promise.all([loadFindings(), loadContextPolicies()]);
  if ($('disc-tab-er')?.classList.contains('active')) await renderERTraversal();
}

async function loadDiscoveryTables() {
  const ds = $('disc-ds').value, schema = $('disc-schema').value;
  if (!ds || !schema) {
    setOptionsPreserve('disc-table-filter', '<option value="">All tables</option>');
    $('disc-column-review').innerHTML = '<div class="empty">Select a data source and schema first.</div>';
    return;
  }
  await fillTableSelect(ds, schema, 'disc-table-filter', 'All tables');
  if (!$('disc-table-filter').value) {
    $('disc-column-review').innerHTML = '<div class="empty">Pick a single table above to review every column, including ones not flagged as PII.</div>';
  }
}

/* one table dropdown drives both the findings filter and the full column review */
async function discTableChanged() {
  renderFindings();              // client-side filter (no refetch)
  await loadColumnReview();
}

function showDiscTab(id) {
  document.querySelectorAll('#page-discovery .tab-btn').forEach(b => b.classList.toggle('active', b.dataset.tab === id));
  document.querySelectorAll('#page-discovery .tab-pane').forEach(p => p.classList.toggle('active', p.id === id));
  if (id === 'disc-tab-er') renderERTraversal().catch(e => toast(e.message, 'err'));
}

async function runScan() {
  const ds = $('disc-ds').value; if (!ds) return toast('Register a data source first', 'err');
  const schema = $('disc-schema').value; if (!schema) return toast('Select a schema first', 'err');
  const piiTypes = [...discSelectedTypes];
  discResultTypeScope = new Set(piiTypes);
  erGraph = null;
  toast(piiTypes.length ? `Scanning for ${piiTypes.length} selected PII type(s)…` : 'Scanning. This samples metadata and values.');
  try {
    const job = await api.post(`/api/discovery/scan-jobs/${ds}?schema=${encodeURIComponent(schema)}`, { piiTypes });
    discActiveJobId = job.jobId;
    discLatestJob = job;
    renderDiscoveryProgress(job);
    activatePageTab('discovery', 'disc-live-card', true);
    startDiscoveryProgressPolling(job.jobId);
    toast('Discovery scan started. Live progress is open.', 'ok');
  }
  catch (e) { toast(e.message, 'err'); }
}

function discJobLive(job) {
  return job && (job.status === 'PENDING' || job.status === 'RUNNING');
}

function discStatusClass(status) {
  return ({ COMPLETED: 'good', RUNNING: 'info', PENDING: 'dim', FAILED: 'bad' }[status] || 'info');
}

function discJobTime(ts) {
  if (!ts) return '';
  try { return new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' }); }
  catch { return ''; }
}

function startDiscoveryProgressPolling(jobId) {
  if (!jobId) return;
  if (discProgressTimer) clearInterval(discProgressTimer);
  discProgressTimer = setInterval(() => pollDiscoveryProgress(jobId), 1400);
  pollDiscoveryProgress(jobId).catch(e => toast(e.message, 'err'));
}

function stopDiscoveryProgressPolling() {
  if (discProgressTimer) clearInterval(discProgressTimer);
  discProgressTimer = null;
}

async function pollDiscoveryProgress(jobId) {
  if (!jobId) return;
  try {
    const job = await api.get(`/api/discovery/scan-jobs/${encodeURIComponent(jobId)}`);
    discLatestJob = job;
    renderDiscoveryProgress(job);
    if (!discJobLive(job)) {
      stopDiscoveryProgressPolling();
      if (discActiveJobId === job.jobId) discActiveJobId = null;
      if (job.status === 'COMPLETED') {
        await loadDiscoveryTables();
        await Promise.all([loadFindings(), loadContextPolicies()]);
        if ($('disc-tab-er')?.classList.contains('active')) await renderERTraversal();
        toast('Discovery scan complete - findings are refreshed.', 'ok');
      }
    }
  } catch (e) {
    stopDiscoveryProgressPolling();
    throw e;
  }
}

async function refreshDiscoveryProgress() {
  const ds = $('disc-ds')?.value;
  const schema = $('disc-schema')?.value;
  if (!ds) { renderDiscoveryProgress(null); return; }
  try {
    const jobs = await api.get(`/api/discovery/scan-jobs?dataSourceId=${encodeURIComponent(ds)}${schema ? '&schema=' + encodeURIComponent(schema) : ''}`);
    let job = discActiveJobId ? jobs.find(j => j.jobId === discActiveJobId) : null;
    if (!job) job = jobs[0] || null;
    if (job) { discResultTypeScope = new Set(job.selectedTypes || []); erGraph = null; }
    discLatestJob = job;
    renderDiscoveryProgress(job);
    if (discJobLive(job)) {
      discActiveJobId = job.jobId;
      if (!discProgressTimer) startDiscoveryProgressPolling(job.jobId);
    }
  } catch (e) {
    const panel = $('disc-live-panel');
    if (panel) panel.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
  }
}

function renderDiscoveryProgress(job) {
  const panel = $('disc-live-panel');
  const badge = $('disc-live-status');
  if (!panel) return;
  if (!job) {
    if (badge) { badge.className = 'pill dim'; badge.textContent = 'no active scan'; }
    panel.innerHTML = '<div class="empty">Start a PII scan to watch table-by-table progress here.</div>';
    return;
  }
  const status = job.status || 'PENDING';
  if (badge) { badge.className = 'pill ' + discStatusClass(status); badge.textContent = status; }
  const tables = job.tables || [];
  const percent = Math.max(0, Math.min(100, Math.round(job.percent || 0)));
  const completed = job.completedTables || 0;
  const total = job.totalTables || tables.length || 0;
  const current = job.currentTable
    ? `<span>Now scanning <b>${esc(job.currentTable)}</b>${job.currentColumn ? '.' + esc(job.currentColumn) : ''}</span>`
    : '<span>No table currently active</span>';
  const typeText = (job.selectedTypes || []).length ? `${job.selectedTypes.length} selected PII type(s)` : 'all PII types';
  const tableHtml = tables.length ? `<div class="disc-live-grid">` + tables.map(t => {
    const tp = Math.max(0, Math.min(100, Math.round(t.percent || 0)));
    const cols = t.totalColumns ? `${t.scannedColumns || 0}/${t.totalColumns} columns` : 'reading columns';
    return `<div class="disc-live-table ${esc((t.status || '').toLowerCase())}">
      <div class="disc-live-table-top"><b title="${esc(t.tableName)}">${esc(t.tableName)}</b>${statusPill(t.status || 'PENDING')}</div>
      <div class="disc-live-bar"><i style="width:${tp}%"></i></div>
      <div class="disc-live-table-meta"><span>${tp}%</span><span>${esc(cols)}</span><span>${t.findings || 0} finding(s)</span></div>
      ${t.currentColumn ? `<div class="disc-live-current">Column: ${esc(t.currentColumn)}</div>` : ''}
    </div>`;
  }).join('') + `</div>` : '<div class="empty">Preparing table list...</div>';
  const doneAction = status === 'COMPLETED'
    ? `<button class="small" onclick="activatePageTab('discovery','disc-results-card',true); loadFindings(); loadColumnReview();">Review findings</button>`
    : '';
  panel.innerHTML = `<div class="disc-live-summary">
      <div class="disc-live-score"><b>${percent}%</b><span>complete</span></div>
      <div class="disc-live-detail">
        <div class="disc-live-msg">${esc(job.message || status)}</div>
        <div class="disc-live-meta">
          <span>${completed}/${total} table(s)</span>
          <span>${job.findings || 0} finding(s)</span>
          <span>${esc(typeText)}</span>
          <span>Started ${esc(discJobTime(job.startedAt))}</span>
          ${job.finishedAt ? `<span>Finished ${esc(discJobTime(job.finishedAt))}</span>` : ''}
        </div>
        <div class="disc-live-currentline">${current}</div>
        ${job.error ? `<div class="disc-live-error">${esc(job.error)}</div>` : ''}
      </div>
      <div class="disc-live-actions">${doneAction}</div>
    </div>
    <div class="disc-live-overall"><i style="width:${percent}%"></i></div>
    ${tableHtml}`;
}

function discTypeParams(types) {
  const list = [...(types || [])].filter(Boolean).sort();
  return list.map(t => '&piiTypes=' + encodeURIComponent(t)).join('');
}

function discResultTypeParams() {
  return discTypeParams(discResultTypeScope);
}

function discActionTypeParams() {
  const selectedFilter = $('disc-type-filter')?.value;
  return selectedFilter ? discTypeParams(new Set([selectedFilter])) : discTypeParams(discResultTypeScope);
}

/* ---------- PII type scope + custom regex patterns ---------- */
let discPiiTypes = [], discSelectedTypes = new Set(), cpGroupsCache = null;

async function loadPiiTypeScope() {
  try { discPiiTypes = await api.get('/api/discovery/pii-types'); } catch (e) { discPiiTypes = []; }
  // drop selections that no longer exist
  discSelectedTypes = new Set([...discSelectedTypes].filter(t => discPiiTypes.includes(t)));
  renderTypeScope();
}
function renderTypeScope() {
  const el = $('disc-type-scope'); if (!el) return;
  if (!discPiiTypes.length) { el.innerHTML = '<span class="sub">No PII types available.</span>'; return; }
  el.innerHTML = discPiiTypes.map(t =>
    `<label class="chip"><input type="checkbox" value="${esc(t)}" ${discSelectedTypes.has(t) ? 'checked' : ''} onchange="discToggleType(${js(t)}, this.checked)"><span>${esc(t)}</span></label>`).join('');
}
function discToggleType(t, on) { if (on) discSelectedTypes.add(t); else discSelectedTypes.delete(t); }
function discScopeAll(on) { discSelectedTypes = new Set(on ? discPiiTypes : []); renderTypeScope(); }

function toggleCustomPatterns() {
  const c = $('disc-custom-card'); if (!c) return;
  const show = c.style.display === 'none';
  c.style.display = show ? '' : 'none';
  if (show) loadCustomPatterns();
}
async function cpVisField() {
  if (cpGroupsCache === null) { try { cpGroupsCache = await api.get('/api/discovery/patterns/my-groups'); } catch (e) { cpGroupsCache = []; } }
  const opts = (cpGroupsCache || []).map(g => `<option value="${g.id}">${esc(g.name)}</option>`).join('');
  const w = $('cp-vis-wrap'); if (!w) return;
  w.innerHTML = `<label>Scope</label><select id="cp-vis" onchange="cpToggleGroup()">` +
    `<option value="PRIVATE">Private (only me)</option><option value="GROUP">Group</option><option value="GLOBAL">Global (admin)</option></select>` +
    `<span id="cp-group-wrap" style="display:none"><label>Group</label><select id="cp-group">${opts || '<option value="">(no groups)</option>'}</select></span>`;
}
function cpToggleGroup() { const v = $('cp-vis')?.value; const w = $('cp-group-wrap'); if (w) w.style.display = v === 'GROUP' ? '' : 'none'; }
async function loadCustomPatterns() {
  await cpVisField();
  try { renderCustomPatterns(await api.get('/api/discovery/patterns')); } catch (e) { toast(e.message, 'err'); }
}
function renderCustomPatterns(list) {
  const el = $('cp-list'); if (!el) return;
  if (!list || !list.length) { el.innerHTML = '<div class="empty">No custom patterns yet.</div>'; return; }
  el.innerHTML = '<table><tr><th>Type</th><th>Match</th><th>Regex</th><th>Mask fn</th><th>Scope</th><th>Owner</th><th></th></tr>' +
    list.map(p => `<tr><td><b>${esc(p.piiType)}</b></td><td>${esc(p.kind)}</td><td class="mono">${esc(p.regex)}</td>` +
      `<td>${esc(p.suggestedFunction || '')}</td><td>${esc(p.visibility)}</td><td>${esc(p.ownerUsername || '')}</td>` +
      `<td><button class="small ghost" onclick="deleteCustomPattern(${p.id})">Delete</button></td></tr>`).join('') + '</table>';
}
async function saveCustomPattern() {
  const vis = $('cp-vis')?.value || 'PRIVATE';
  const body = {
    piiType: ($('cp-type').value || '').trim(),
    kind: $('cp-kind').value,
    regex: ($('cp-regex').value || '').trim(),
    suggestedFunction: ($('cp-fn').value || '').trim() || null,
    visibility: vis,
    ownerGroupId: vis === 'GROUP' ? (parseInt($('cp-group').value) || null) : null
  };
  if (!body.piiType || !body.regex) return toast('PII type and regex are required', 'err');
  try {
    await api.post('/api/discovery/patterns', body);
    toast('Custom pattern added', 'ok');
    $('cp-type').value = ''; $('cp-regex').value = ''; $('cp-fn').value = '';
    await loadCustomPatterns(); await loadPiiTypeScope();
  } catch (e) { toast(e.message, 'err'); }
}
async function deleteCustomPattern(id) {
  if (!await uiConfirm('Delete this custom pattern?', {danger: true, okText: 'Delete'})) return;
  try { await api.del('/api/discovery/patterns/' + id); await loadCustomPatterns(); await loadPiiTypeScope(); toast('Deleted', 'ok'); }
  catch (e) { toast(e.message, 'err'); }
}

function fnOptions(selected) {
  const list = functionsList.length ? functionsList : GENERATORS;
  const withSel = selected && !list.includes(selected) ? [selected, ...list] : list;
  return withSel.map(f => `<option ${f === selected ? 'selected' : ''}>${esc(f)}</option>`).join('');
}

/* Mask-function / column-type compatibility — mirrors DiscoveryService so the UI never offers a
   name/text masker for a BIGINT, DATE or BOOLEAN column (e.g. no FULL_NAME on a bigint id). */
const MASK_TYPE_AGNOSTIC = ['NULLIFY', 'FIXED', 'PASSTHROUGH'];
function jsTypeCategory(typeName) {
  const t = (typeName || '').toLowerCase();
  if (t.includes('bool') || t === 'bit') return 'BOOLEAN';
  if (t.includes('date') || t.includes('time')) return 'DATE';
  if (t.includes('char') || t.includes('text') || t.includes('clob') || t.includes('json') || t.includes('uuid') || t.includes('enum')) return 'TEXT';
  if (t.includes('int') || t.includes('serial') || t.includes('numeric') || t.includes('decimal') || t.includes('real') || t.includes('double') || t.includes('float') || t.includes('money') || t.includes('number')) return 'NUMERIC';
  if (t.includes('binary') || t.includes('blob') || t.includes('bytea') || t.includes('raw')) return 'BINARY';
  return 'TEXT';
}
function jsMaskCompatible(fn, cat) {
  if (!fn) return false;
  if (MASK_TYPE_AGNOSTIC.includes(fn)) return true;
  switch (cat) {
    case 'TEXT': return true;
    case 'NUMERIC': return fn === 'FORMAT_PRESERVE' || fn === 'SEQUENCE';
    case 'DATE': return fn === 'DATE_SHIFT' || fn === 'DOB_AGE_BAND' || fn === 'AGE';
    default: return false;   // BOOLEAN / BINARY → only the type-agnostic ones
  }
}
function compatibleFnOptions(selected, typeName) {
  const cat = jsTypeCategory(typeName);
  const base = functionsList.length ? functionsList : GENERATORS;
  let list = base.filter(f => jsMaskCompatible(f, cat));
  if (!list.length) list = MASK_TYPE_AGNOSTIC.slice();
  if (selected && !list.includes(selected)) list = [selected, ...list];
  return list.map(f => `<option ${f === selected ? 'selected' : ''}>${esc(f)}</option>`).join('');
}

function maskParamLabel(fn, n) {
  return (MASK_PARAM_META[fn] || [])[n - 1] || null;
}

function shortParamLabel(label) {
  if (!label) return '';
  if (label === 'Part: CITY/STATE/ZIP/FULL') return 'Part';
  return label.replace('Output ', '').replace(' handling', '');
}

function optionTags(options, selected) {
  return options.map(o => {
    const value = Array.isArray(o) ? o[0] : o;
    const label = Array.isArray(o) ? o[1] : o;
    return `<option value="${esc(value)}" ${String(selected ?? '') === String(value) ? 'selected' : ''}>${esc(label)}</option>`;
  }).join('');
}

function paramControlHtml(id, label, value, onchange) {
  if (!label) return '<span class="param-na">-</span>';
  const attr = onchange ? ` onchange="${onchange}"` : '';
  const current = value ?? '';
  if (label === 'Name format') return `<select id="${id}" class="param-select"${attr}>${optionTags(NAME_FORMAT_OPTIONS, current || 'FIRST LAST')}</select>`;
  if (label === 'Output case') return `<select id="${id}" class="param-select"${attr}>${optionTags(CASE_OPTIONS, current || 'PROPER')}</select>`;
  if (label === 'Email mode') return `<select id="${id}" class="param-select"${attr}>${optionTags(EMAIL_MODE_OPTIONS, current || 'NAME_SAFE')}</select>`;
  if (label === 'Domain handling') return `<select id="${id}" class="param-select"${attr}>${optionTags(EMAIL_DOMAIN_OPTIONS, current || 'SAFE_DOMAIN')}</select>`;
  if (label === 'Phone mode') return `<select id="${id}" class="param-select"${attr}>${optionTags(PHONE_MODE_OPTIONS, current || 'FORMAT_PRESERVE')}</select>`;
  if (label === 'Phone handling') return `<select id="${id}" class="param-select"${attr}>${optionTags(PHONE_HANDLING_OPTIONS, current || 'PRESERVE_COUNTRY')}</select>`;
  if (label === 'SSN mode') return `<select id="${id}" class="param-select"${attr}>${optionTags(SSN_MODE_OPTIONS, current || 'VALID_PRESERVE_AREA')}</select>`;
  if (label === 'SSN format') return `<select id="${id}" class="param-select"${attr}>${optionTags(SSN_FORMAT_OPTIONS, current || 'PRESERVE_FORMAT')}</select>`;
  if (label === 'Card mode') return `<select id="${id}" class="param-select"${attr}>${optionTags(CARD_MODE_OPTIONS, current || 'VALID_PRESERVE_BIN')}</select>`;
  if (label === 'Card format') return `<select id="${id}" class="param-select"${attr}>${optionTags(CARD_FORMAT_OPTIONS, current || 'PRESERVE_FORMAT')}</select>`;
  if (label === 'Address part') return `<select id="${id}" class="param-select"${attr}>${optionTags(ADDRESS_PART_OPTIONS, current || 'FULL')}</select>`;
  if (label === 'State handling') return `<select id="${id}" class="param-select"${attr}>${optionTags(STATE_MODE_OPTIONS, current || 'PRESERVE_STATE')}</select>`;
  if (label && label.includes('CITY/STATE/ZIP')) return `<select id="${id}" class="param-select"${attr}>${optionTags(GEO_PART_OPTIONS, current || 'FULL')}</select>`;
  if (label && label.startsWith('Date format')) return `<select id="${id}" class="param-select"${attr}>${optionTags(DATE_FORMAT_OPTIONS, current)}</select>`;
  if (label && label.startsWith('Script name')) {
    const names = maskScriptNames || [];
    let opts = '<option value="">— select a saved script —</option>' +
      names.map(n => `<option value="${esc(n)}" ${n === current ? 'selected' : ''}>${esc(n)}</option>`).join('');
    if (current && !names.includes(current)) opts += `<option value="${esc(current)}" selected>${esc(current)} (not found)</option>`;
    if (!names.length) opts += '<option value="" disabled>No saved scripts — create one in Mask &rarr; Masking Scripts</option>';
    return `<select id="${id}" class="param-select"${attr}>${opts}</select>`;
  }
  return `<input id="${id}" class="param-input" value="${esc(current)}" placeholder="optional"${attr}>`;
}

function discoveryParamControl(r, n) {
  const label = maskParamLabel(r.suggestedFunction, n);
  if (!label) return '<span class="param-na">-</span>';
  const value = n === 1 ? r.suggestedParam1 : r.suggestedParam2;
  return `<label class="mini-param"><span>${esc(shortParamLabel(label))}</span>${paramControlHtml(`disc-p${n}-${r.id}`, label, value, `setFindingParam(${r.id}, ${n}, this.value)`)}</label>`;
}

// Both param editors for a finding row (e.g. SCRIPT's script-name picker, AGE's date format),
// shown inline in the findings table so params can be set without opening per-table Column Review.
function discoveryParamCell(r) {
  const cells = [1, 2].filter(n => maskParamLabel(r.suggestedFunction, n)).map(n => discoveryParamControl(r, n));
  return cells.length ? cells.join('') : '<span class="param-na">-</span>';
}

function defaultFunctionForPii(piiType) {
  return PII_DEFAULT_FUNCTIONS[piiType] || 'FORMAT_PRESERVE';
}

function piiTypeOptions(selected) {
  const current = selected || 'MANUAL_PII';
  const list = PII_TYPE_OPTIONS.includes(current) ? PII_TYPE_OPTIONS : [current, ...PII_TYPE_OPTIONS];
  return optionTags(list, current);
}

function reviewKey(row, i) {
  return `${i}-${row.tableName || ''}-${row.columnName || ''}`.replace(/[^A-Za-z0-9_]+/g, '_');
}

function reviewParamControlsHtml(key, fn, p1, p2, classificationId) {
  const cells = [];
  for (const n of [1, 2]) {
    const label = maskParamLabel(fn, n);
    if (!label) continue;
    const value = n === 1 ? p1 : p2;
    const onchange = classificationId ? `setFindingParam(${classificationId}, ${n}, this.value)` : '';
    cells.push(`<label class="mini-param"><span>${esc(shortParamLabel(label))}</span>${paramControlHtml(`review-p${n}-${key}`, label, value, onchange)}</label>`);
  }
  return cells.length ? cells.join('') : '<span class="param-na">No params</span>';
}

function updateReviewFunctionParams(key) {
  const fn = $('review-fn-' + key)?.value || 'FORMAT_PRESERVE';
  const target = $('review-params-' + key);
  if (target) target.innerHTML = reviewParamControlsHtml(key, fn, null, null, null);
}

function reviewPiiTypeChanged(key) {
  const piiType = $('review-pii-' + key)?.value || 'MANUAL_PII';
  const fn = defaultFunctionForPii(piiType);
  const select = $('review-fn-' + key);
  if (select && [...select.options].some(o => o.value === fn || o.text === fn)) select.value = fn;
  updateReviewFunctionParams(key);
}

async function reviewFunctionChanged(key, classificationId) {
  updateReviewFunctionParams(key);
  if (classificationId) await setFindingFunction(classificationId, $('review-fn-' + key).value);
}

let discFindings = [];

async function loadFindings() {
  const ds = $('disc-ds').value; if (!ds) return;
  const schema = $('disc-schema').value; if (!schema) return;
  if (!functionsList.length) await loadFunctions();
  await loadScriptNames();   // keep the SCRIPT-param picker current for the findings table
  discFindings = await api.get(`/api/discovery/results/${ds}?schema=${encodeURIComponent(schema)}${discResultTypeParams()}`);
  renderFindings();
}

function renderFindings() {
  const all = discFindings || [];
  // KPI summary (for the whole schema)
  const kEl = $('disc-kpis');
  if (kEl) {
    const types = new Set(all.map(f => f.piiType).filter(Boolean)).size;
    const high = all.filter(f => (f.confidence || 0) >= 0.8).length;
    const approved = all.filter(f => f.status === 'APPROVED').length;
    const pending = all.filter(f => f.status !== 'APPROVED' && f.status !== 'REJECTED').length;
    kEl.innerHTML = [['PII columns', all.length], ['PII types', types], ['High confidence', high], ['Approved', approved], ['To review', pending]]
      .map(([l, v]) => `<div class="ds-kpi"><div class="v">${v}</div><div class="l">${l}</div></div>`).join('');
  }
  // dynamic filter options
  dsFillSelect('disc-type-filter', [...new Set(all.map(f => f.piiType).filter(Boolean))].sort(), 'All PII types');
  dsFillSelect('disc-status-filter', [...new Set(all.map(f => f.status).filter(Boolean))].sort(), 'All statuses');

  const tf = $('disc-table-filter')?.value || '', tp = $('disc-type-filter')?.value || '',
        stf = $('disc-status-filter')?.value || '', q = ($('disc-search')?.value || '').toLowerCase();
  const rows = all.filter(f => {
    if (tf && (f.tableName || '').toLowerCase() !== tf.toLowerCase()) return false;
    if (tp && f.piiType !== tp) return false;
    if (stf && f.status !== stf) return false;
    if (q && !`${f.tableName} ${f.columnName} ${f.piiType} ${f.sampleValue || ''}`.toLowerCase().includes(q)) return false;
    return true;
  });
  // Stable order (table, then column) so approving/rejecting or changing a mask never reshuffles the
  // list — the row you just edited stays exactly where it was.
  rows.sort((a, b) => (a.tableName || '').localeCompare(b.tableName || '')
    || (a.columnName || '').localeCompare(b.columnName || '') || (a.id - b.id));

  const el = $('disc-table');
  if (!all.length) { el.innerHTML = '<div class="empty">No findings yet — run a scan from the Scan Source tab.</div>'; return; }
  el.innerHTML = rows.length ? `<div class="disc-find-wrap"><table class="disc-find-table"><thead><tr>
      <th>Column</th><th>Type</th><th>PII type</th><th>Confidence</th><th>Suggested mask</th><th>Params</th><th>Sample</th><th>Status</th><th></th></tr></thead><tbody>` +
    rows.map(r => `<tr>
      <td class="disc-col"><code>${esc(r.tableName)}</code>.<b>${esc(r.columnName)}</b></td>
      <td class="mono dim">${esc(r.dataType ?? '')}</td>
      <td>${pill(r.piiType, 'info')}</td>
      <td class="disc-conf"><span class="conf-bar"><i style="width:${Math.round((r.confidence || 0) * 100)}%"></i></span><span class="dim">${Math.round((r.confidence || 0) * 100)}%</span></td>
      <td><select class="inline-select" onchange="setFindingFunction(${r.id}, this.value)" title="Masking functions compatible with the ${esc(jsTypeCategory(r.dataType))} column type">${compatibleFnOptions(r.suggestedFunction, r.dataType)}</select></td>
      <td class="disc-params">${discoveryParamCell(r)}</td>
      <td class="mono dim">${esc(r.sampleValue ?? '')}</td>
      <td>${statusPill(r.status)}</td>
      <td class="nowrap"><button class="small" onclick="setClass(${r.id},'APPROVED')">Approve</button>
          <button class="small ghost" onclick="setClass(${r.id},'REJECTED')">Not PII</button></td>
    </tr>`).join('') + '</tbody></table></div>'
    : '<div class="empty">No findings match the current filters.</div>';
}

async function approveVisibleFindings() {
  const ds = $('disc-ds').value, schema = $('disc-schema').value;
  if (!ds || !schema) return toast('Select a data source and schema first', 'err');
  const tableFilter = $('disc-table-filter')?.value?.trim() || '';
  try {
    const r = await api.post(`/api/discovery/approve-all/${ds}?schema=${encodeURIComponent(schema)}${tableFilter ? '&tableFilter=' + encodeURIComponent(tableFilter) : ''}${discActionTypeParams()}`);
    toast(`${r.count || 0} visible finding(s) approved`, 'ok');
    await loadFindings();
    await loadColumnReview();
  } catch (e) { toast(e.message, 'err'); }
}

async function rejectVisibleFindings() {
  const ds = $('disc-ds').value, schema = $('disc-schema').value;
  if (!ds || !schema) return toast('Select a data source and schema first', 'err');
  const tableFilter = $('disc-table-filter')?.value?.trim() || '';
  try {
    const r = await api.post(`/api/discovery/reject-all/${ds}?schema=${encodeURIComponent(schema)}${tableFilter ? '&tableFilter=' + encodeURIComponent(tableFilter) : ''}${discActionTypeParams()}`);
    toast(`${r.count || 0} visible finding(s) rejected`, 'ok');
    await loadFindings();
    await loadColumnReview();
  } catch (e) { toast(e.message, 'err'); }
}

async function loadColumnReview() {
  const ds = $('disc-ds').value, schema = $('disc-schema').value, table = $('disc-table-filter')?.value;
  const panel = $('disc-column-review');
  if (!panel) return;
  if (!ds || !schema) { panel.innerHTML = '<div class="empty">Select a data source and schema first.</div>'; return; }
  if (!table) { panel.innerHTML = '<div class="empty">Pick a single table above to review every column, including ones not flagged as PII.</div>'; return; }
  if (!functionsList.length) await loadFunctions();
  try {
    const rows = await api.get(`/api/discovery/table-columns/${ds}?schema=${encodeURIComponent(schema)}&table=${encodeURIComponent(table)}${discResultTypeParams()}`);
    const detected = rows.filter(r => r.classificationId).length;
    panel.classList.remove('empty');
    panel.innerHTML = `<div class="review-summary">${esc(table)}: ${detected} discovered/manual PII column(s), ${rows.length - detected} not flagged.</div>
      <table class="pii-review-table"><tr><th>Column</th><th>Type</th><th>PII Type</th><th>Masking Function</th><th>Params</th><th>Sample</th><th>Status</th><th></th></tr>` +
      rows.map((r, i) => {
        const key = reviewKey(r, i);
        const hasFinding = !!r.classificationId;
        const piiType = r.piiType || 'MANUAL_PII';
        const fn = r.suggestedFunction || defaultFunctionForPii(piiType);
        const piiCell = hasFinding
          ? pill(piiType, 'info')
          : `<select id="review-pii-${key}" class="inline-select" onchange='reviewPiiTypeChanged(${js(key)})'>${piiTypeOptions(piiType)}</select>`;
        const fnCell = `<select id="review-fn-${key}" class="inline-select" onchange='reviewFunctionChanged(${js(key)}, ${hasFinding ? r.classificationId : 'null'})'>${compatibleFnOptions(fn, r.dataType)}</select>`;
        const params = reviewParamControlsHtml(key, fn, r.suggestedParam1, r.suggestedParam2, hasFinding ? r.classificationId : null);
        const action = hasFinding
          ? `<button class="small" onclick="setClass(${r.classificationId},'APPROVED')">Approve</button>
             <button class="small ghost" onclick="setClass(${r.classificationId},'REJECTED')">Not PII</button>`
          : `<button class="small" onclick='markManualPii(${js(r.tableName)}, ${js(r.columnName)}, ${js(key)})'>Mark PII</button>`;
        return `<tr><td><b>${esc(r.columnName)}</b>${r.nullable ? ' <span class="tiny-note">nullable</span>' : ''}</td>
          <td class="mono">${esc(r.dataType ?? '')}</td><td>${piiCell}</td><td>${fnCell}</td>
          <td><div class="review-params" id="review-params-${key}">${params}</div></td>
          <td class="mono">${esc(r.sampleValue ?? '')}</td><td>${statusPill(r.status)}</td><td class="review-actions">${action}</td></tr>`;
      }).join('') + '</table>';
  } catch (e) { toast(e.message, 'err'); panel.innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

async function markManualPii(table, column, key) {
  const ds = $('disc-ds').value, schema = $('disc-schema').value;
  const piiType = $('review-pii-' + key)?.value || 'MANUAL_PII';
  const fn = $('review-fn-' + key)?.value || defaultFunctionForPii(piiType);
  try {
    await api.post(`/api/discovery/manual/${ds}`, {
      schemaName: schema,
      tableName: table,
      columnName: column,
      piiType,
      suggestedFunction: fn,
      suggestedParam1: $('review-p1-' + key)?.value || '',
      suggestedParam2: $('review-p2-' + key)?.value || '',
      status: 'APPROVED'
    });
    toast(`${table}.${column} marked as PII`, 'ok');
    await loadFindings();
    await loadColumnReview();
  } catch (e) { toast(e.message, 'err'); }
}

async function setClass(id, status) {
  try { await api.patch(`/api/discovery/classifications/${id}`, { status }); await loadFindings(); await loadColumnReview(); }
  catch (e) { toast(e.message, 'err'); }
}

async function setFindingFunction(id, fn) {
  try {
    await api.patch(`/api/discovery/classifications/${id}`, { suggestedFunction: fn });
    toast('Masking function changed to ' + fn, 'ok');
    await loadFindings();
    await loadColumnReview();
  } catch (e) { toast(e.message, 'err'); loadFindings(); }
}

async function setFindingParam(id, n, value) {
  try {
    await api.patch(`/api/discovery/classifications/${id}`, n === 1 ? { suggestedParam1: value } : { suggestedParam2: value });
    toast('Discovery rule parameter updated', 'ok');
  } catch (e) { toast(e.message, 'err'); loadFindings(); }
}

async function genPolicy() {
  const ds = $('disc-ds').value, name = $('disc-policy-name').value || ('policy-ds-' + ds);
  const schema = $('disc-schema').value; if (!schema) return toast('Select a schema first', 'err');
  try {
    const p = await api.post(`/api/discovery/generate-policy/${ds}?schema=${encodeURIComponent(schema)}`, { name });
    toast(`Policy "${p.name}" created from approved findings`, 'ok');
    const res = $('disc-policy-result');
    if (res) res.innerHTML = `Created <b>${esc(p.name)}</b>. <a href="#" onclick='editGeneratedPolicy(${p.id}, ${js(p.name)});return false;'>Edit policy &rarr;</a> (edit rules and save under the same name)`;
    await refreshShared(); await loadContextPolicies();
  } catch (e) { toast(e.message, 'err'); }
}

async function editGeneratedPolicy(id, name) {
  await showPage('policies');
  await openRules(id, name);
}

/* ---------- DataScope guardrails: PII coverage + schema drift ---------- */
async function loadAdInsights() {
  adPiiCoverage = null; adDrift = null;
  renderAdInsights();
  const id = selectedAdId;
  if (!id) return;
  const [cov, drift] = await Promise.allSettled([
    api.get(`/api/datasets/${id}/pii-coverage`),
    api.get(`/api/datasets/${id}/drift`)
  ]);
  if (id !== selectedAdId) return;   // user switched blueprints while we were loading
  adPiiCoverage = cov.status === 'fulfilled' ? cov.value : null;
  adDrift = drift.status === 'fulfilled' ? drift.value : null;
  renderAdInsights();
  renderDataScopeProvision();
}

function renderAdInsights() {
  const el = $('ad-insights');
  if (!el) return;
  const parts = [];
  const un = adPiiCoverage?.unmaskedApproved || [];
  if (un.length) {
    parts.push(`<div class="ds-provision-warning">⚠ <b>${un.length} approved PII column${un.length === 1 ? '' : 's'} in scope with no masking:</b> ` +
      un.slice(0, 6).map(u => `<code>${esc(u.table)}.${esc(u.column)}</code> (${esc(u.piiType)})`).join(', ') +
      (un.length > 6 ? ` … and ${un.length - 6} more` : '') +
      '. Assign a policy or column override before provisioning, or the values are copied in clear.</div>');
  }
  const issues = adDrift?.issues || [];
  if (issues.length) {
    parts.push(`<div class="ds-provision-warning">⚠ <b>Schema drift (${issues.length}):</b> ` +
      issues.slice(0, 6).map(i =>
        `${esc(String(i.type || '').replaceAll('_', ' ').toLowerCase())} <code>${esc(i.table || '')}${i.column ? '.' + esc(i.column) : ''}</code> (${esc(i.artifact || '')})`
      ).join(', ') +
      (issues.length > 6 ? ` … and ${issues.length - 6} more` : '') +
      '. The blueprint references objects that no longer match the live source schema.</div>');
  }
  el.innerHTML = parts.join('');
}

/* ---------- DataScope blueprint version history ---------- */
async function loadAdVersions() {
  const el = $('ad-ver-list');
  if (!el || !selectedAdId) return;
  try {
    const list = await api.get(`/api/datasets/${selectedAdId}/versions`);
    if (!list.length) { el.innerHTML = '<div class="empty">No versions saved yet.</div>'; return; }
    el.innerHTML = '<table><tr><th>Version</th><th>Note</th><th>By</th><th>When</th><th></th></tr>' +
      list.map(v => `<tr><td><b>v${v.versionNo}</b></td><td>${esc(v.note || '')}</td><td>${esc(v.createdBy || '')}</td>` +
        `<td class="mono">${v.createdAt ? new Date(v.createdAt).toLocaleString() : ''}</td>` +
        `<td class="row-actions"><button class="small ghost" onclick="viewAdVersion(${v.id}, ${v.versionNo})">View</button> ` +
        `<button class="small ghost" onclick="diffAdVersion(${v.id}, ${v.versionNo})">Diff vs current</button> ` +
        `<button class="small ghost danger" onclick="restoreAdVersion(${v.id}, ${v.versionNo})">Restore</button></td></tr>`).join('') + '</table>';
  } catch (e) { /* versions are optional; ignore */ }
}

async function diffAdVersion(versionId, versionNo) {
  const el = $('ad-ver-diff');
  if (el) el.innerHTML = '<div class="loading">Comparing…</div>';
  try {
    const d = await api.get(`/api/datasets/versions/${versionId}/diff`);
    renderAdVersionDiff(d);
  } catch (e) {
    if (el) el.innerHTML = '';
    toast(e.message, 'err');
  }
}

function renderAdVersionDiff(d) {
  const el = $('ad-ver-diff');
  if (!el) return;
  const fieldRows = fields => (fields || []).map(f =>
    `<div class="diff-row">— <code>${esc(f.field)}</code>: <s>${esc(f.from ?? '∅')}</s> → <b>${esc(f.to ?? '∅')}</b></div>`).join('');
  const sections = [
    ['profiles', 'Table profiles'], ['overrides', 'Column overrides'], ['customPks', 'Custom PKs'],
    ['userRels', 'Relationships'], ['traversalRules', 'Traversal rules'], ['policyRules', 'Masking rules']
  ];
  const parts = [];
  if ((d.definition || []).length) {
    parts.push(`<div class="diff-section"><b>Definition</b>${fieldRows(d.definition)}</div>`);
  }
  for (const [key, label] of sections) {
    const s = d[key] || {};
    const added = s.added || [], removed = s.removed || [], changed = s.changed || [];
    if (!added.length && !removed.length && !changed.length) continue;
    parts.push(`<div class="diff-section"><b>${esc(label)}</b>` +
      added.map(k => `<div class="diff-row">+ <code>${esc(k)}</code> <span class="pill ok">only in ${esc(d.to)}</span></div>`).join('') +
      removed.map(k => `<div class="diff-row">− <code>${esc(k)}</code> <span class="pill warn">only in ${esc(d.from)}</span></div>`).join('') +
      changed.map(c => `<div class="diff-row">~ <code>${esc(c.key)}</code>${fieldRows(c.fields)}</div>`).join('') +
      '</div>');
  }
  el.innerHTML = `<div class="diff-panel">
      <div class="diff-head"><b>Diff: ${esc(d.from)} → ${esc(d.to)}</b>
        <button class="small ghost" onclick="$('ad-ver-diff').innerHTML=''">Close</button></div>
      ${parts.length ? parts.join('') : `<div class="empty">No differences — ${esc(d.from)} matches ${esc(d.to)}.</div>`}
    </div>`;
}

async function restoreAdVersion(versionId, versionNo) {
  const ok = await uiConfirm(
    `Restore this blueprint to v${versionNo}?\n\nThe current state is auto-saved as a new version first, so this is reversible. ` +
    'Masking policies themselves are not modified (they are shared) — only the blueprint configuration is restored.',
    { title: 'Restore version', danger: true, okText: `Restore v${versionNo}` });
  if (!ok) return;
  try {
    const r = await api.post(`/api/datasets/versions/${versionId}/restore`, {});
    toast(`Restored v${versionNo} (previous state saved as v${r.autoSavedVersion})`, 'ok');
    if ($('ad-ver-diff')) $('ad-ver-diff').innerHTML = '';
    await refreshShared();
    await selectAd(selectedAdId);   // reload profiles/overrides/rels/rules + guardrail insights
  } catch (e) { toast(e.message, 'err'); }
}

async function saveAdVersion() {
  if (!selectedAdId) return toast('Select a blueprint first', 'err');
  try {
    const note = $('ad-ver-note') ? $('ad-ver-note').value : '';
    const v = await api.post(`/api/datasets/${selectedAdId}/versions`, { note });
    if ($('ad-ver-note')) $('ad-ver-note').value = '';
    toast(`Saved version v${v.versionNo}`, 'ok');
    await loadAdVersions();
  } catch (e) { toast(e.message, 'err'); }
}

async function viewAdVersion(versionId, versionNo) {
  try {
    const v = await api.get(`/api/datasets/versions/${versionId}`);
    const pretty = JSON.stringify(v.snapshot, null, 2);
    const w = window.open('', '_blank');
    if (w) {
      w.document.title = 'DataScope version v' + versionNo;
      w.document.body.style.cssText = 'font:12px/1.5 monospace;white-space:pre-wrap;padding:16px;background:#0b1020;color:#cbd5e1';
      w.document.body.textContent = `Version v${versionNo} — ${v.note || '(no note)'} — by ${v.createdBy || ''}\n\n` + pretty;
    } else {
      toast('Allow pop-ups to view the snapshot', 'err');
    }
  } catch (e) { toast(e.message, 'err'); }
}

async function loadContextPolicies() {
  const ds = $('disc-ds').value, schema = $('disc-schema').value;
  if (!ds || !schema) return;
  const rows = await api.get(`/api/policies?dataSourceId=${encodeURIComponent(ds)}&schema=${encodeURIComponent(schema)}`);
  $('disc-policy-count').textContent = rows.length + ' policy(s)';
  $('disc-policy-list').innerHTML = rows.length ? `<table><tr><th>Name</th><th>Description</th><th>Created</th><th></th></tr>` +
    rows.map(p => `<tr><td><b>${esc(p.name)}</b></td><td>${esc(p.description ?? '')}</td><td class="mono">${p.createdAt ? new Date(p.createdAt).toLocaleString() : ''}</td><td><button class="small" onclick='editGeneratedPolicy(${p.id}, ${js(p.name)})'>Edit policy</button></td></tr>`).join('') + '</table>'
    : '<div class="empty">No policy yet for this database/schema. Approve findings and generate one.</div>';
}

/* ---------- ER traversal model ---------- */
let erGraph = null, erSelected = null;

async function renderERTraversal() {
  const ds = $('disc-ds').value, schema = $('disc-schema').value;
  const canvas = $('er-canvas');
  if (!ds || !schema) { canvas.innerHTML = '<div class="empty">Select a data source and schema first.</div>'; return; }
  if (!erGraph) erGraph = await api.get(`/api/discovery/graph/${ds}?schema=${encodeURIComponent(schema)}${discResultTypeParams()}`);
  drawERDiagram();
  renderTraversalOrder();
  renderTraversalCycles();
  renderRuleImpact(erSelected);
}

/* longest-path layering: parents (no incoming FK) at level 0, children to the right */
function erLevels() {
  const nodes = (erGraph.nodes || []).map(n => n.id);
  const edges = (erGraph.edges || []).filter(e => e.from !== e.to);
  const level = Object.fromEntries(nodes.map(n => [n, 0]));
  for (let pass = 0; pass < nodes.length; pass++) {
    let changed = false;
    for (const e of edges) {
      if (level[e.from] === undefined || level[e.to] === undefined) continue;
      if (level[e.to] < level[e.from] + 1 && level[e.from] + 1 < nodes.length) {
        level[e.to] = level[e.from] + 1; changed = true;
      }
    }
    if (!changed) break;
  }
  return level;
}

function erChildrenOf(table) { return (erGraph.edges || []).filter(e => e.from === table && e.to !== table); }
function erParentsOf(table)  { return (erGraph.edges || []).filter(e => e.to === table && e.from !== table); }
function erCycleEdgeIds() { return new Set(erGraph?.cycleEdgeIds || []); }
function erCycleTables() {
  const tables = new Set();
  (erGraph?.cycles || []).forEach(c => (c.tables || []).forEach(t => tables.add(t)));
  return tables;
}

function erDescendants(table) {
  const seen = new Set(); const queue = [table];
  while (queue.length) {
    const t = queue.shift();
    for (const e of erChildrenOf(t)) if (!seen.has(e.to)) { seen.add(e.to); queue.push(e.to); }
  }
  seen.delete(table); return seen;
}

function erAncestors(table) {
  const seen = new Set(); const queue = [table];
  while (queue.length) {
    const t = queue.shift();
    for (const e of erParentsOf(t)) if (!seen.has(e.from)) { seen.add(e.from); queue.push(e.from); }
  }
  seen.delete(table); return seen;
}

function drawERDiagram() {
  const canvas = $('er-canvas');
  const nodes = erGraph.nodes || [], edges = erGraph.edges || [];
  if (!nodes.length) { canvas.innerHTML = '<div class="empty">No tables found. Run a scan first.</div>'; return; }

  const NODE_W = 200, COL_GAP = 110, ROW_GAP = 26, PAD = 26, HDR = 30, LINE = 15, MAX_COLS = 6;
  const levels = erLevels();
  const byLevel = {};
  nodes.forEach(n => (byLevel[levels[n.id] ?? 0] ||= []).push(n));
  const levelKeys = Object.keys(byLevel).map(Number).sort((a, b) => a - b);

  const nodeH = n => {
    const cols = n.piiColumns || [];
    const shown = Math.min(cols.length, MAX_COLS) + (cols.length > MAX_COLS ? 1 : 0);
    return HDR + (shown === 0 ? 24 : shown * (LINE * 2 + 4) + 10);
  };

  const pos = {};
  let maxX = 0, maxY = 0;
  levelKeys.forEach((lv, ci) => {
    let y = PAD;
    const x = PAD + ci * (NODE_W + COL_GAP);
    for (const n of byLevel[lv]) {
      const h = nodeH(n);
      pos[n.id] = { x, y, h };
      y += h + ROW_GAP;
    }
    maxX = Math.max(maxX, x + NODE_W);
    maxY = Math.max(maxY, y);
  });

  const selected = erSelected;
  const desc = selected ? erDescendants(selected) : new Set();
  const anc = selected ? erAncestors(selected) : new Set();
  const cycleEdges = erCycleEdgeIds();
  const cycleTables = erCycleTables();
  const hotPair = (a, b) => selected &&
    ((a === selected || desc.has(a) || anc.has(a)) && (b === selected || desc.has(b) || anc.has(b))) &&
    !(anc.has(a) && desc.has(b)) ? ' hot' : '';

  const edgeSvg = edges.filter(e => pos[e.from] && pos[e.to]).map(e => {
    const a = pos[e.from], b = pos[e.to];
    const x1 = a.x + NODE_W, y1 = a.y + Math.min(a.h / 2, 40);
    const x2 = b.x, y2 = b.y + Math.min(b.h / 2, 40);
    const mx = (x1 + x2) / 2;
    const hot = hotPair(e.from, e.to);
    const cycle = cycleEdges.has(e.id) ? ' cycle' : '';
    return `<path class="er-edge${hot}${cycle}" d="M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2 - 6} ${y2}"></path>
      <text class="er-edge-label${hot}${cycle}" x="${mx}" y="${(y1 + y2) / 2 - 6}" text-anchor="middle">${esc(e.fkColumn || e.label || '')}</text>`;
  }).join('');

  const nodeSvg = nodes.map(n => {
    const p = pos[n.id];
    const cols = n.piiColumns || [];
    const shown = cols.slice(0, MAX_COLS);
    const cls = (n.id === selected ? ' selected' : desc.has(n.id) ? ' descendant' : anc.has(n.id) ? ' ancestor' : '') +
      (cycleTables.has(n.id) ? ' cycle' : '');
    let body;
    if (!cols.length) {
      body = `<text class="nopii" x="${p.x + 12}" y="${p.y + HDR + 16}">no PII detected</text>`;
    } else {
      body = shown.map((c, i) => {
        const cy = p.y + HDR + 8 + i * (LINE * 2 + 4);
        return `<text class="colname" x="${p.x + 12}" y="${cy + LINE - 3}">${esc(c.column)} <tspan class="colfn">[${esc(c.piiType)}]</tspan></text>
          <text class="colfn" x="${p.x + 20}" y="${cy + LINE * 2 - 3}">${esc(c.function || '')}${c.status === 'APPROVED' ? ' &#10003;' : c.status === 'REJECTED' ? ' &#10007;' : ''}</text>`;
      }).join('');
      if (cols.length > MAX_COLS) {
        const cy = p.y + HDR + 8 + MAX_COLS * (LINE * 2 + 4);
        body += `<text class="colfn" x="${p.x + 12}" y="${cy + LINE - 3}">+ ${cols.length - MAX_COLS} more PII column(s)</text>`;
      }
    }
    return `<g class="er-svg-node${cls}" onclick='selectERTable(${js(n.id)})'>
      <rect class="box" x="${p.x}" y="${p.y}" width="${NODE_W}" height="${p.h}" rx="8"></rect>
      <rect class="hdr" x="${p.x + 1}" y="${p.y + 1}" width="${NODE_W - 2}" height="${HDR - 4}" rx="7"></rect>
      <text class="title" x="${p.x + 12}" y="${p.y + 20}">${esc(n.label)}</text>
      <text class="colfn" x="${p.x + NODE_W - 12}" y="${p.y + 20}" text-anchor="end">${cols.length} PII</text>
      ${body}</g>`;
  }).join('');

  canvas.classList.remove('empty');
  canvas.innerHTML = `<svg viewBox="0 0 ${maxX + PAD} ${maxY + PAD}" width="${maxX + PAD}" height="${maxY + PAD}" xmlns="http://www.w3.org/2000/svg">
    <defs><marker id="er-arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto">
      <path d="M0,0 L9,4.5 L0,9 z" fill="var(--border2)"></path></marker></defs>
    ${edgeSvg}${nodeSvg}</svg>`;
}

function selectERTable(table) {
  erSelected = erSelected === table ? null : table;
  drawERDiagram();
  renderTraversalCycles();
  renderRuleImpact(erSelected);
}

function renderTraversalOrder() {
  const el = $('er-traversal');
  const levels = erLevels();
  const byLevel = {};
  (erGraph.nodes || []).forEach(n => (byLevel[levels[n.id] ?? 0] ||= []).push(n.id));
  const keys = Object.keys(byLevel).map(Number).sort((a, b) => a - b);
  if (!(erGraph.edges || []).length) {
    el.innerHTML = '<h4>Traversal order</h4><div class="empty">No FK relationships detected — tables are independent.</div>';
    return;
  }
  const mode = erGraph.traversalMode === 'CYCLE_GUARDED'
    ? `<p class="sub tight">${pill('CYCLE_GUARDED','warn')} Cycle-aware traversal is required to avoid revisiting tables indefinitely.</p>`
    : `<p class="sub tight">${pill('ACYCLIC','good')} No relationship cycles detected.</p>`;
  el.innerHTML = '<h4>Traversal order (parents first)</h4>' + mode +
    keys.map(k => `<div class="lvl"><b>Level ${k}</b><span class="mono">${byLevel[k].map(esc).join(', ')}</span></div>`).join('') +
    `<p class="sub tight">Masking and subset loads traverse parents before children so FKs stay intact.</p>`;
}

function renderTraversalCycles() {
  const el = $('er-cycles');
  if (!el) return;
  const cycles = erGraph?.cycles || [];
  if (!cycles.length) {
    el.innerHTML = '<h4>Traversal cycles</h4><div class="empty">No traversal cycles detected.</div>';
    return;
  }
  const selected = erSelected;
  const rows = cycles.map(c => {
    const active = selected && (c.tables || []).includes(selected) ? ' active' : '';
    const path = (c.tables || []).map(t => `<span class="${t === selected ? 'sel' : ''}">${esc(t)}</span>`).join(' <b>-></b> ');
    return `<div class="cycle-row${active}"><div>${path}</div><p class="sub tight">Length ${esc(c.length)}. Traversal guard processes each table once per path and flags this loop for review.</p></div>`;
  }).join('');
  el.innerHTML = `<h4>Traversal cycles ${pill(cycles.length,'warn')}</h4>${rows}`;
}

function renderRuleImpact(table) {
  const el = $('er-impact');
  if (!table) { el.innerHTML = '<h4>Rule impact</h4><div class="empty">Click a table in the diagram.</div>'; return; }
  const piiOf = t => ((erGraph.nodes || []).find(n => n.id === t) || {}).piiColumns || [];
  const childEdges = erChildrenOf(table);
  const parentEdges = erParentsOf(table);
  const ownPii = piiOf(table);

  let html = `<h4>Rule impact — ${esc(table)}</h4>`;
  html += ownPii.length
    ? `<div class="er-impact-table"><b>${esc(table)} (this table)</b>` +
      ownPii.map(c => `<div class="er-impact-col">${esc(c.column)} ${pill(c.piiType, 'info')} <code>${esc(c.function || '')}</code> ${statusPill(c.status)}</div>`).join('') + '</div>'
    : `<p class="sub tight">No PII detected on ${esc(table)} itself.</p>`;

  if (childEdges.length) {
    html += `<p class="sub tight">Children reached via FK — if you mask ${esc(table)}, review these fields too so related data stays consistent:</p>`;
    for (const e of childEdges) {
      const cp = piiOf(e.to);
      html += `<div class="er-impact-table"><b>${esc(e.to)}</b><div class="via">joined via <code>${esc(e.to)}.${esc(e.fkColumn || '')} &rarr; ${esc(table)}.${esc(e.pkColumn || '')}</code></div>` +
        (cp.length
          ? cp.map(c => `<div class="er-impact-col">${esc(c.column)} ${pill(c.piiType, 'info')} <code>${esc(c.function || '')}</code> ${statusPill(c.status)}</div>`).join('')
          : '<div class="empty">No PII findings on this child.</div>') + '</div>';
    }
  } else {
    html += '<p class="sub tight">No child tables reference this table.</p>';
  }
  if (parentEdges.length) {
    html += `<p class="sub tight">Parents: ${parentEdges.map(e => `<b>${esc(e.from)}</b> (via ${esc(e.fkColumn || '')})`).join(', ')}</p>`;
  }
  el.innerHTML = html;
}

/* ---------- policies ---------- */
async function loadPolicies() {
  await refreshShared(); await loadFunctions();
  setOptionsPreserve('pol-ds', '<option value="">No datasource context</option>' + dataSources.map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.role)})</option>`).join(''));
  setOptionsPreserve('map-ds', '<option value="">Select data source</option>' + dataSources.map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.role)})</option>`).join(''));
  $('pol-table').innerHTML = policiesList.length ? `<table><tr><th>ID</th><th>Name</th><th>Description</th><th></th></tr>` +
    policiesList.map(p => `<tr><td>${p.id}</td><td><b>${esc(p.name)}</b></td><td>${esc(p.description ?? '')}</td>
      <td><button class="small" onclick='openRules(${p.id}, ${js(p.name)})'>Edit policy</button>
          <button class="small danger" onclick="delPolicy(${p.id})">Delete</button></td></tr>`).join('') + '</table>'
    : '<div class="empty">No policies yet. Create one or generate from discovery.</div>';
}

/** Dedicated sidebar page: Masking Scripts (Lua). */
async function loadMaskScriptsPage() {
  initMsEditor();
  fillMaskSamples();
  await loadMaskingScripts();
}

/* ---------- Masking scripts (user-defined Lua, Optim-style exits) ---------- */
let msScripts = [];

/* Ready-to-use example scripts across common flavours. Loading one fills the editor;
   edit the name and Save to make it your own. Each is valid against the sandboxed forge.* API. */
const MASK_SAMPLES = [
  { name: 'keep-last4', desc: 'Mask all but the last 4 characters (account / card numbers)', flavour: 'Partial',
    code: '-- Keep the last 4 characters; format-preserve the rest so length and shape hold.\nif value == nil then return nil end\nif #value <= 4 then return value end\nreturn forge.fpe(string.sub(value, 1, #value - 4)) .. string.sub(value, -4)' },
  { name: 'mask-letters-keep-digits', desc: 'Substitute the name letters, keep digits (yash1234 → kim1234)', flavour: 'Partial',
    code: '-- Replace the alphabetic run with a deterministic masked name; keep digits and punctuation.\nif value == nil then return nil end\nif not string.match(value, "%a") then return value end\nlocal name = string.lower(forge.pick("first_names.txt", value))\nreturn (value:gsub("%a+", name, 1))' },
  { name: 'by-indicator', desc: 'Mask differently based on a type column (P=phone, E=email)', flavour: 'Conditional',
    code: '-- A polymorphic column: type_ind decides how ref_value is masked.\nlocal ind = row["type_ind"]\nif ind == "P" then return forge.mask("PHONE", value) end\nif ind == "E" then return forge.mask("EMAIL", value) end\nreturn forge.fpe(value)' },
  { name: 'consent-nullout', desc: 'NULL the value only when a consent flag is not Y', flavour: 'Conditional',
    code: "-- Suppress PII for customers who didn't consent; otherwise pass through unchanged.\nif row[\"consent_flag\"] ~= \"Y\" then return nil end\nreturn value" },
  { name: 'deterministic-token', desc: 'Stable 10-digit token per value (same input → same token)', flavour: 'Tokenize',
    code: '-- TKN + a deterministic, zero-padded number. Same value maps to the same token everywhere.\nif value == nil then return nil end\nreturn string.format("TKN%010d", forge.hash(value, 1000000000))' },
  { name: 'email-from-name', desc: "Rebuild an email from the row's masked first/last name", flavour: 'Composite',
    code: '-- Compose a safe, undeliverable email from already-masked name columns in this row.\nlocal f = forge.masked("first_name") or "user"\nlocal l = forge.masked("last_name") or tostring(forge.hash(value, 9999))\nreturn string.lower(f .. "." .. l) .. "@example.test"' },
  { name: 'iban-keep-country', desc: 'Format-preserve an IBAN but keep the 2-letter country code', flavour: 'Format-preserving',
    code: '-- Keep the leading 2-letter country code; format-preserve (digit→digit) the rest.\nif value == nil or #value < 4 then return forge.fpe(value) end\nreturn string.sub(value, 1, 2) .. forge.fpe(string.sub(value, 3))' },
  { name: 'redact-keep-length', desc: 'Replace every character with * (keeps length)', flavour: 'Redaction',
    code: '-- Full redaction that preserves the original length.\nif value == nil then return nil end\nreturn string.rep("*", #value)' },
  { name: 'company-from-list', desc: 'Deterministically map to a company from the built-in seedlist', flavour: 'Substitution',
    code: '-- Same input always maps to the same company name (seedlist substitution).\nif value == nil then return nil end\nreturn forge.pick("companies.txt", value)' },
  { name: 'card-with-tag', desc: 'Luhn-valid masked card + a stable 3-digit cohort tag', flavour: 'Built-in + custom',
    code: '-- Deterministic masked card number plus a stable tag you can group by.\nif value == nil then return nil end\nreturn forge.mask("CREDIT_CARD", value) .. " #" .. string.format("%03d", forge.hash(value, 1000))' }
];

function fillMaskSamples() {
  const sel = $('ms-sample');
  if (!sel || sel.options.length > 1) return;
  sel.innerHTML = '<option value="">— pick an example —</option>' +
    MASK_SAMPLES.map((s, i) => `<option value="${i}">${esc(s.flavour)}: ${esc(s.name)}</option>`).join('');
}

async function loadMaskSample(idx) {
  const s = MASK_SAMPLES[parseInt(idx)];
  const sel = $('ms-sample');
  if (sel) sel.value = '';
  if (!s) return;
  const src = $('ms-source');
  if (src && src.value.trim() && !await uiConfirm('Replace the current editor contents with this sample?', { okText: 'Load sample' })) return;
  if ($('ms-name')) $('ms-name').value = s.name;
  if ($('ms-desc')) $('ms-desc').value = s.desc;
  if (src) src.value = s.code;
  toast(`Loaded sample "${s.name}" — edit the name and Save to make it yours`, 'ok');
}

/* Complete-line API suggestions shown while typing in the script editor. */
const MS_SUGGESTIONS = [
  ['return forge.mask("FIRST_NAME", value)', 'apply a built-in function (join-consistent salts)'],
  ['return forge.mask("EMAIL", value)', 'mask as a safe, undeliverable email'],
  ['return forge.mask("PHONE", value)', 'format-preserving phone mask'],
  ['forge.hash(value, 100000)', 'deterministic number 0..N-1 — same input, same number, everywhere'],
  ['forge.pick("first_names.txt", value)', 'deterministic seedlist substitution'],
  ['forge.pick("last_names.txt", value)', 'deterministic seedlist substitution'],
  ['forge.fpe(value)', 'format-preserving scramble (digit→digit, letter→letter)'],
  ['forge.masked("first_name")', "a sibling column's ALREADY-masked value in this row"],
  ['row["column_name"]', 'original sibling column value (lower-cased name)'],
  ['value', 'the original value being masked'],
  ['param', "the rule's param2, passed through to the script"],
  ['rowIndex', 'current row number'],
  ['string.sub(value, 1, 4)', 'substring (1-based, inclusive)'],
  ['string.upper(value)', 'upper-case'],
  ['string.lower(value)', 'lower-case'],
  ['string.rep("*", 4)', 'repeat a character'],
  ['string.format("%05d", forge.hash(value, 99999))', 'zero-padded deterministic number'],
  ['string.match(value, "%d+")', 'first digit run (Lua pattern)'],
  ['tostring(x)', 'convert to string'],
  ['tonumber(value)', 'convert to number (nil when not numeric)'],
  ['if value == nil then return nil end', 'null guard'],
  ['if row["type_ind"] == "P" then return forge.mask("PHONE", value) end', 'branch on a sibling column'],
  ['return value', 'passthrough (no masking)']
];

async function loadMaskingScripts() {
  const el = $('ms-list');
  if (!el) return;
  try {
    msScripts = await api.get('/api/policies/scripts');
    maskScriptNames = msScripts.map(s => s.name).filter(Boolean);   // keep SCRIPT-param pickers current
    if ($('ms-count')) $('ms-count').textContent = msScripts.length + ' script(s)';
    el.innerHTML = msScripts.length
      ? '<table><tr><th>Rule param1</th><th>Description</th><th>Visibility</th><th>Owner</th><th>Updated</th><th></th></tr>' +
        msScripts.map(s => `<tr>
          <td><code>${esc(s.name)}</code></td>
          <td>${esc(s.description || '')}</td>
          <td>${pill(s.visibility, s.visibility === 'PRIVATE' ? 'warn' : 'dim')}</td>
          <td>${esc(s.ownerUsername || '')}</td>
          <td class="mono">${s.updatedAt ? new Date(s.updatedAt).toLocaleDateString() : ''}</td>
          <td class="row-actions"><button class="small ghost" onclick="editMaskingScript(${s.id})">Edit</button>
            <button class="small ghost danger" onclick="deleteMaskingScript(${s.id}, ${js(s.name)})">Delete</button></td>
        </tr>`).join('') + '</table>'
      : '<div class="empty">No scripts yet — write one above. It must <code>return</code> the masked value.</div>';
  } catch (e) { el.innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

function editMaskingScript(id) {
  const s = msScripts.find(x => x.id === id);
  if (!s) return;
  $('ms-name').value = s.name;
  $('ms-desc').value = s.description || '';
  $('ms-visibility').value = s.visibility || 'GLOBAL';
  $('ms-source').value = s.luaSource || '';
  $('ms-name').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

async function saveMaskingScript() {
  const out = $('ms-test-result');
  try {
    const saved = await api.post('/api/policies/scripts', {
      name: $('ms-name').value.trim(),
      description: $('ms-desc').value.trim() || null,
      visibility: $('ms-visibility').value,
      luaSource: $('ms-source').value
    });
    if (out) { out.textContent = ''; out.className = 'mono'; }
    toast(`Saved script "${saved.name}" — use function SCRIPT with param1 = ${saved.name}`, 'ok');
    await loadMaskingScripts();
  } catch (e) {
    // syntax errors include the line number (e.g. "script:3: 'then' expected") — keep them visible
    if (out) { out.textContent = e.message; out.className = 'mono warn-row'; }
    toast(e.message, 'err');
  }
}

async function deleteMaskingScript(id, name) {
  if (!await uiConfirm(`Delete script "${name}"? Rules referencing it will FAIL at run time until it is recreated.`, { danger: true, okText: 'Delete' })) return;
  try { await api.del('/api/policies/scripts/' + id); toast('Deleted', 'ok'); await loadMaskingScripts(); }
  catch (e) { toast(e.message, 'err'); }
}

async function testMaskingScript() {
  const name = $('ms-name').value.trim();
  const out = $('ms-test-result');
  if (!name) return toast('Enter the script name first (and Save — the test runs the saved version)', 'err');
  try {
    const r = await api.post('/api/policies/preview', {
      function: 'SCRIPT', param1: name, value: $('ms-test-value').value
    });
    out.textContent = `${r.original} → ${r.masked}`;
    out.className = 'mono';
  } catch (e) {
    out.textContent = e.message;
    out.className = 'mono warn-row';
  }
}

/* ---------- editor autocomplete: complete code lines while typing ---------- */
let msSuggestIdx = 0, msSuggestItems = [];

function initMsEditor() {
  const ta = $('ms-source');
  if (!ta || ta.dataset.suggestReady) return;
  ta.dataset.suggestReady = 'true';
  ta.addEventListener('input', msShowSuggestions);
  ta.addEventListener('click', msHideSuggestions);
  ta.addEventListener('blur', () => setTimeout(msHideSuggestions, 200));   // allow click on a suggestion
  ta.addEventListener('keydown', e => {
    const box = $('ms-suggest');
    if (!box || box.style.display === 'none') return;
    if (e.key === 'ArrowDown') { e.preventDefault(); msMoveSuggestion(1); }
    else if (e.key === 'ArrowUp') { e.preventDefault(); msMoveSuggestion(-1); }
    else if (e.key === 'Tab' || e.key === 'Enter') { e.preventDefault(); msAcceptSuggestion(msSuggestIdx); }
    else if (e.key === 'Escape') { msHideSuggestions(); }
  });
}

function msCurrentToken() {
  const ta = $('ms-source');
  const caret = ta.selectionStart;
  const text = ta.value;
  let s = caret;
  while (s > 0 && /[\w."'\[\]%(]/.test(text[s - 1])) s--;
  return { start: s, caret, word: text.slice(s, caret) };
}

function msShowSuggestions() {
  const box = $('ms-suggest');
  if (!box) return;
  const { word } = msCurrentToken();
  if (!word || word.length < 2) return msHideSuggestions();
  const q = word.toLowerCase();
  msSuggestItems = MS_SUGGESTIONS
    .filter(([code]) => code.toLowerCase().includes(q))
    .sort((a, b) => (b[0].toLowerCase().startsWith(q) ? 1 : 0) - (a[0].toLowerCase().startsWith(q) ? 1 : 0))
    .slice(0, 8);
  if (!msSuggestItems.length) return msHideSuggestions();
  msSuggestIdx = 0;
  box.innerHTML = msSuggestItems.map(([code, hint], i) =>
    `<div class="ms-suggest-item${i === 0 ? ' active' : ''}" onmousedown="event.preventDefault();msAcceptSuggestion(${i})">
       <code>${esc(code)}</code><small>${esc(hint)}</small></div>`).join('');
  box.style.display = '';
}

function msMoveSuggestion(delta) {
  msSuggestIdx = (msSuggestIdx + delta + msSuggestItems.length) % msSuggestItems.length;
  const box = $('ms-suggest');
  [...box.children].forEach((el, i) => el.classList.toggle('active', i === msSuggestIdx));
  box.children[msSuggestIdx]?.scrollIntoView({ block: 'nearest' });
}

function msAcceptSuggestion(i) {
  const picked = msSuggestItems[i];
  if (!picked) return;
  const ta = $('ms-source');
  const { start, caret } = msCurrentToken();
  ta.value = ta.value.slice(0, start) + picked[0] + ta.value.slice(caret);
  const pos = start + picked[0].length;
  ta.setSelectionRange(pos, pos);
  ta.focus();
  msHideSuggestions();
}

function msHideSuggestions() {
  const box = $('ms-suggest');
  if (box) box.style.display = 'none';
}

async function createPolicy() {
  try {
    await api.post('/api/policies', { name: $('pol-name').value, description: $('pol-desc').value,
      dataSourceId: $('pol-ds').value ? parseInt($('pol-ds').value) : null,
      schemaName: $('pol-schema').value || null });
    toast('Policy created', 'ok');
    await loadPolicies();
    activatePageTab('policies', 'pol-list-card', true);
  } catch (e) { toast(e.message, 'err'); }
}

async function loadPolicySchemas() {
  const ds = $('pol-ds').value;
  setOptionsPreserve('pol-schema', '<option value="">Optional schema</option>');
  if (ds) await fillSchemaSelect(ds, 'pol-schema', 'Optional schema');
}

async function delPolicy(id) {
  try {
    await api.del('/api/policies/' + id);
    $('rules-card').style.display='none';
    $('table-map-card').style.display='none';
    await loadPolicies();
    refreshPageTabs('policies');
  }
  catch (e) { toast(e.message, 'err'); }
}

async function openRules(id, name) {
  selectedPolicy = id;
  $('rules-card').style.display = 'block';
  $('table-map-card').style.display = 'block';
  $('rules-title').textContent = 'Rules - ' + name;
  $('rules-title').dataset.policyName = name;
  if (!functionsList.length) await loadFunctions();
  const rules = await api.get(`/api/policies/${id}/rules`);
  $('rules-table').innerHTML = rules.length ? `<table><tr><th>Schema</th><th>Table</th><th>Column</th><th>Function</th><th>Param1</th><th>Param2</th><th></th></tr>` +
    rules.map(r => `<tr><td>${esc(r.schemaName ?? '')}</td><td>${esc(r.tableName)}</td><td><b>${esc(r.columnName)}</b></td>
      <td><select class="inline-select" onchange="updateRule(${r.id}, { function: this.value })">${fnOptions(r.function)}</select></td>
      <td>${ruleParamControl(r, 1)}</td>
      <td>${ruleParamControl(r, 2)}</td>
      <td><button class="small danger" onclick="delRule(${r.id})">x</button></td></tr>`).join('') + '</table>'
    : '<div class="empty">No rules yet.</div>';
  updateMaskParamVisibility('rule');
  refreshPageTabs('policies');
  activatePageTab('policies', 'rules-card', true);
}

function ruleParamControl(r, n) {
  const label = maskParamLabel(r.function, n);
  if (!label) return '<span class="param-na">-</span>';
  const value = n === 1 ? r.param1 : r.param2;
  return paramControlHtml(`rule-inline-p${n}-${r.id}`, label, value, `updateRule(${r.id}, { param${n}: this.value })`);
}

async function updateRule(id, patch) {
  try {
    await api.patch(`/api/policies/rules/${id}`, patch);
    toast('Rule updated', 'ok');
    openRules(selectedPolicy, $('rules-title').dataset.policyName || '');
  } catch (e) {
    toast(e.message, 'err');
    openRules(selectedPolicy, $('rules-title').dataset.policyName || '');
  }
}

async function addRule() {
  if (!selectedPolicy) return toast('Open a policy first', 'err');
  try {
    await api.post(`/api/policies/${selectedPolicy}/rules`, { tableName: $('rule-table').value,
      columnName: $('rule-col').value, function: $('rule-fn').value, param1: visibleParam('rule', 1), param2: visibleParam('rule', 2),
      schemaName: $('map-schema')?.value || $('pol-schema')?.value || null });
    openRules(selectedPolicy, $('rules-title').dataset.policyName || '');
  } catch (e) { toast(e.message, 'err'); }
}

async function loadMapSchemas() {
  const ds = $('map-ds').value;
  setOptionsPreserve('map-schema', '<option value="">Select schema</option>');
  $('table-map').innerHTML = '<div class="empty">Select a schema.</div>';
  if (ds) await fillSchemaSelect(ds, 'map-schema');
}

async function loadTableMap() {
  if (!selectedPolicy) return toast('Open a policy first', 'err');
  const ds = $('map-ds').value, schema = $('map-schema').value;
  if (!ds || !schema) return toast('Select discovery data source and schema', 'err');
  const findings = await api.get(`/api/discovery/results/${ds}?schema=${encodeURIComponent(schema)}`);
  if (!findings.length) {
    $('table-map').innerHTML = '<div class="empty">No discovery findings for this context. Run PII Discovery first.</div>';
    return;
  }
  const byTable = findings.reduce((m, f) => ((m[f.tableName] ||= []).push(f), m), {});
  $('table-map').classList.remove('empty');
  $('table-map').innerHTML = Object.entries(byTable).map(([table, rows]) => `<div class="map-table">
    <label class="check"><input type="checkbox" class="map-table-check" onchange="toggleMapTable(this)"> <b>${esc(table)}</b> <span>${rows.length} finding(s)</span></label>
    <div class="map-cols">${rows.map(f => `<label class="check map-col"><input type="checkbox" class="map-finding" data-schema="${esc(f.schemaName ?? '')}" data-table="${esc(f.tableName)}" data-column="${esc(f.columnName)}" data-function="${esc(f.suggestedFunction)}" data-pii="${esc(f.piiType)}" data-param1="${esc(f.suggestedParam1 ?? '')}" data-param2="${esc(f.suggestedParam2 ?? '')}"> ${esc(f.columnName)} ${pill(f.piiType,'info')} <code>${esc(f.suggestedFunction)}</code>${maskDefaultHint(f.suggestedFunction, f.piiType, f.suggestedParam1, f.suggestedParam2)}</label>`).join('')}</div>
  </div>`).join('');
}

function toggleMapTable(box) {
  box.closest('.map-table').querySelectorAll('.map-finding').forEach(c => c.checked = box.checked);
}

async function addMappedRules() {
  if (!selectedPolicy) return toast('Open a policy first', 'err');
  const selected = [...document.querySelectorAll('.map-finding:checked')];
  if (!selected.length) return toast('Select at least one mapped column', 'err');
  let added = 0;
  for (const c of selected) {
    const fn = c.dataset.function;
    const params = defaultMaskParamsForMap(fn, c.dataset.pii);
    await api.post(`/api/policies/${selectedPolicy}/rules`, {
      schemaName: c.dataset.schema || null,
      tableName: c.dataset.table,
      columnName: c.dataset.column,
      function: fn,
      param1: c.dataset.param1 || params.param1,
      param2: c.dataset.param2 || params.param2
    });
    added++;
  }
  toast(`Added ${added} mapped rule(s)`, 'ok');
  openRules(selectedPolicy, $('rules-title').dataset.policyName || '');
}

function defaultMaskParamsForMap(fn, pii) {
  if (fn === 'CITY_STATE_ZIP') {
    return { param1: pii === 'CITY' ? 'CITY' : pii === 'STATE' ? 'STATE' : pii === 'ZIP' ? 'ZIP' : 'FULL',
      param2: 'PRESERVE_STATE' };
  }
  if (fn === 'ADDRESS_US') return { param1: 'FULL', param2: 'PRESERVE_STATE' };
  if (fn === 'FULL_NAME') return { param1: 'FIRST LAST', param2: 'PROPER' };
  if (fn === 'EMAIL') return { param1: 'NAME_SAFE', param2: 'SAFE_DOMAIN' };
  if (fn === 'PHONE') return { param1: 'FORMAT_PRESERVE', param2: 'PRESERVE_COUNTRY' };
  if (fn === 'SSN') return { param1: 'VALID_PRESERVE_AREA', param2: 'PRESERVE_FORMAT' };
  if (fn === 'CREDIT_CARD') return { param1: 'VALID_PRESERVE_BIN', param2: 'PRESERVE_FORMAT' };
  if (['FIRST_NAME', 'LAST_NAME', 'COMPANY', 'ADDRESS_STREET'].includes(fn)) return { param1: null, param2: 'PROPER' };
  return { param1: null, param2: null };
}

function maskDefaultHint(fn, pii, param1, param2) {
  const params = { ...defaultMaskParamsForMap(fn, pii), param1: param1 || defaultMaskParamsForMap(fn, pii).param1,
    param2: param2 || defaultMaskParamsForMap(fn, pii).param2 };
  const hint = [params.param1 && ('p1: ' + params.param1), params.param2 && ('p2: ' + params.param2)].filter(Boolean).join(' | ');
  return hint ? ` <span class="param-hint">${esc(hint)}</span>` : '';
}

async function delRule(id) {
  try { await api.del('/api/policies/rules/' + id); openRules(selectedPolicy, $('rules-title').dataset.policyName || ''); }
  catch (e) { toast(e.message, 'err'); }
}

/* ---------- studio ---------- */
async function loadFunctions() {
  if (!functionsList.length) functionsList = await api.get('/api/policies/functions');
  await loadScriptNames();   // so the SCRIPT param renders a picker of saved scripts
  const opts = functionsList.map(f => `<option>${f}</option>`).join('');
  ['st-fn','rule-fn'].forEach(id => { const el = $(id); if (el && !el.options.length) el.innerHTML = opts; });
  updateMaskParamVisibility('st');
  updateMaskParamVisibility('rule');
}

function updateMaskParamVisibility(prefix) {
  const fn = $(`${prefix}-fn`)?.value;
  const meta = MASK_PARAM_META[fn] || [];
  [1, 2].forEach(i => {
    const wrap = $(`${prefix}-p${i}-wrap`);
    const label = $(`${prefix}-p${i}-label`);
    const control = $(`${prefix}-p${i}-control`);
    const text = meta[i - 1];
    if (!wrap || !control) return;
    const current = $(`${prefix}-p${i}`)?.value || '';
    wrap.style.display = text ? 'block' : 'none';
    if (label && text) label.textContent = text;
    control.innerHTML = text ? paramControlHtml(`${prefix}-p${i}`, text, current) : `<input id="${prefix}-p${i}" type="hidden" value="">`;
  });
}

function visibleParam(prefix, n) {
  const wrap = $(`${prefix}-p${n}-wrap`);
  const input = $(`${prefix}-p${n}`);
  return wrap && wrap.style.display !== 'none' && input.value ? input.value : null;
}

async function preview() {
  try {
    const r = await api.post('/api/policies/preview', { function: $('st-fn').value, value: $('st-value').value,
      param1: visibleParam('st', 1), param2: visibleParam('st', 2), seed: $('st-seed').value || null });
    $('st-out').style.display = 'block';
    $('st-out').innerHTML = `<span>${esc(r.original)}</span><span class="arrow">-&gt;</span><span class="masked">${esc(r.masked)}</span>`;
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- subsetting ---------- */
async function loadSubsetSchemas() {
  const ds = $('sub-ds').value;
  setOptionsPreserve('sub-schema', '<option value="">Select schema</option>');
  setOptionsPreserve('sub-driver', '<option value="">Select table</option>');
  $('sub-criteria').innerHTML = '';
  if (!ds) return;
  await fillSchemaSelect(ds, 'sub-schema');
  await loadSubsetTables();
}

async function loadSubsetTables() {
  const ds = $('sub-ds').value, schema = $('sub-schema').value;
  if (!ds || !schema) return;
  const tables = await fillTableSelect(ds, schema, 'sub-driver');
  document.querySelectorAll('.crit-table').forEach(sel => {
    const current = sel.value;
    sel.innerHTML = '<option value="">Select table</option>' + tables.map(t => `<option>${esc(t)}</option>`).join('');
    if (tables.includes(current)) sel.value = current;
  });
}

function addSubsetCriterion(table = '', filter = '', rowLimit = '') {
  const row = document.createElement('div');
  row.className = 'syn-row subset-criterion';
  const tables = tableCache[`${$('sub-ds').value}|${$('sub-schema').value}`] || [];
  row.innerHTML = `<div><label>Table</label><select class="crit-table"><option value="">Select table</option>${tables.map(t => `<option ${t===table?'selected':''}>${esc(t)}</option>`).join('')}</select></div>
    <div><label>Filter</label><input class="crit-filter" value="${esc(filter)}" placeholder="status = 'ACTIVE'"></div>
    <div><label>Row limit</label><input class="crit-limit" type="number" min="0" value="${esc(rowLimit)}"></div>
    <div></div>
    <button class="small danger" onclick="this.parentElement.remove()">x</button>`;
  $('sub-criteria').appendChild(row);
}

function subsetCriteria() {
  return [...$('sub-criteria').children].map(r => ({
    table: r.querySelector('.crit-table').value,
    filter: r.querySelector('.crit-filter').value || null,
    rowLimit: r.querySelector('.crit-limit').value ? parseInt(r.querySelector('.crit-limit').value) : null
  })).filter(r => r.table);
}

function subsetTraversalChanged() {
  const enabled = $('sub-related')?.checked ?? true;
  ['sub-q1','sub-q2'].forEach(id => { if ($(id)) $(id).disabled = !enabled; });
}

async function planSubset() {
  try {
    if (!$('sub-ds').value) return toast('Select a data source', 'err');
    if (!$('sub-schema').value) return toast('Select a schema', 'err');
    if (!$('sub-driver').value) return toast('Select a driver table', 'err');
    toast('Planning subset and reading FK metadata.');
    const plan = await api.post('/api/subset/plan', { dataSourceId: $('sub-ds').value,
      schemaName: $('sub-schema').value,
      driverTable: $('sub-driver').value, filter: $('sub-filter').value || null,
      maxDriverRows: parseInt($('sub-max').value || '0'), includeRelated: $('sub-related').checked,
      includeParents: $('sub-q1').checked, includeChildren: $('sub-q2').checked,
      tableCriteria: subsetCriteria() });
    const warnings = plan.warnings && plan.warnings.length
      ? `<div class="warnings">${plan.warnings.map(esc).join('<br>')}</div>` : '';
    const traversal = plan.includeRelated
      ? `Q1 parents ${pill(plan.includeParents ? 'ON' : 'OFF', plan.includeParents ? 'good' : 'dim')} ` +
        `Q2 children ${pill(plan.includeChildren ? 'ON' : 'OFF', plan.includeChildren ? 'good' : 'dim')}`
      : 'Driver table only';
    $('sub-out').innerHTML =
      `<p class="sub">Mode ${pill(plan.mode || 'REFERENTIAL_CLOSURE','info')} Driver <b>${esc(plan.driverTable)}</b> ` +
      `selected <b>${plan.rowCounts?.[plan.driverTable] ?? 0}</b> driver row(s), total <b>${plan.totalRows}</b> row(s).</p>` +
      warnings +
      `<p class="sub tight">${traversal}</p>` +
      `<p class="sub tight">Load order: <span class="mono">${(plan.loadOrder || []).map(esc).join(' -> ')}</span></p>` +
      `<table><tr><th>Table</th><th>Rows selected</th></tr>` +
      Object.entries(plan.rowCounts || {}).map(([t,n]) => `<tr><td>${esc(t)}</td><td>${n}</td></tr>`).join('') + '</table>' +
      `<p class="sub tight">Execute it from Provisioning Jobs with SUBSET_MASK and the same driver, filter, row limit, and Q1/Q2 traversal settings.</p>` +
      `<div class="row push"><button onclick="goTo('jobs','job-launch-card')">Open Provisioning Jobs</button></div>`;
    activatePageTab('subset', 'sub-plan-card', true);
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- virtualization ---------- */
async function loadVirtualization() {
  await refreshShared();
  if ($('virt-source')?.value) await loadVirtSchemas();
  let poolStats = null, dockerStatus = null, zfsStatus = null;
  [virtualSnapshots, virtualDbs, poolStats, dockerStatus, zfsStatus] = await Promise.all([
    api.get('/api/virtualization/snapshots'),
    api.get('/api/virtualization/vdbs'),
    api.get('/api/virtualization/pool').catch(() => null),
    api.get('/api/virtualization/docker').catch(() => null),
    api.get('/api/virtualization/zfs').catch(() => null)
  ]);
  renderVirtualization();
  renderVirtPool(poolStats);
  await loadEnvironments();
  const dockerEl = $('virt-docker-status');
  if (dockerEl) {
    const dockerMsg = dockerStatus?.available
      ? `<b>Docker: available (engine ${esc(dockerStatus.serverVersion || '?')})</b>.`
      : '<b>Docker: not detected — container provider disabled.</b>';
    const zfsMsg = zfsStatus?.available
      ? ` <b>ZFS engine: ${esc(zfsStatus.engineHost)} pool ${esc(zfsStatus.pool)} (${esc(zfsStatus.health || '?')}, ${fmtBytes(zfsStatus.allocatedBytes)} / ${fmtBytes(zfsStatus.sizeBytes)}, ${esc(zfsStatus.compressRatio || '?')} compress)</b>.`
      : ' <b>ZFS engine: not configured.</b>';
    dockerEl.innerHTML = dockerMsg + zfsMsg;
  }
}

function renderVirtPool(p) {
  const el = $('virt-pool-stats');
  if (!el) return;
  if (!p || !p.chunkCount) { el.textContent = 'pool: empty'; return; }
  el.textContent = `pool: ${p.chunkCount} chunks · ${fmtBytes(p.storedBytes)} physical / ${fmtBytes(p.logicalBytes)} logical · ${p.dedupRatio}x dedup`;
}

function fmtBytes(n) {
  if (n == null) return '-';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1048576).toFixed(1)} MB`;
  return `${(n / 1073741824).toFixed(2)} GB`;
}

async function loadVirtSchemas() {
  const ds = $('virt-source').value;
  setOptionsPreserve('virt-schema', '<option value="">Select schema</option>');
  if (!ds) return;
  await fillSchemaSelect(ds, 'virt-schema');
}

async function createVirtSnapshot() {
  try {
    const provider = $('virt-provider')?.value || 'POOL';
    if (!$('virt-source').value) return toast('Select a source data source', 'err');
    if (provider === 'POOL' && !$('virt-schema').value) return toast('Select a schema', 'err');
    const r = await api.post('/api/virtualization/snapshots', {
      dataSourceId: parseInt($('virt-source').value),
      schemaName: $('virt-schema').value || null,
      name: $('virt-snap-name').value || null,
      note: $('virt-note').value || null,
      provider
    });
    $('virt-snap-name').value = '';
    $('virt-note').value = '';
    virtWatchOp(r.opId);   // live progress panel — no more disappearing toast
  } catch (e) { toast(e.message, 'err'); }
}

// Pre-flight the ZFS engine over SSH (zfs/docker/nfs) so setup issues surface before a snapshot.
async function testVirtEngine() {
  const box = $('virt-engine-test');
  if (box) box.innerHTML = '<div class="engine-test running">Testing ZFS engine over SSH…</div>';
  try {
    const r = await api.get('/api/virtualization/engine-test');
    const rows = (r.checks || []).map(c => `<tr class="${c.ok ? 'et-ok' : (c.required ? 'et-fail' : 'et-warn')}">
      <td>${c.ok ? '✅' : (c.required ? '❌' : '⚠️')}</td>
      <td>${esc(c.name)}${c.required ? '' : ' <span class="dim">(optional)</span>'}</td>
      <td class="mono">${esc(c.detail || '')}</td></tr>`).join('');
    box.innerHTML = `<div class="engine-test ${r.ready ? 'ready' : 'notready'}">
      <div class="et-head">${r.ready ? '✅' : '⛔'} ${esc(r.message || '')}</div>
      <div class="et-meta dim">mode: <b>${esc(r.mode)}</b> · host: <code>${esc(String(r.host))}</code> · user: <code>${esc(String(r.sshUser))}</code>:${esc(String(r.sshPort))}${r.useSudo ? ' <span class="pill dim">sudo</span>' : ''} · pool: <code>${esc(String(r.pool))}</code>${r.localhostAlias ? ' · localhost-alias: <code>' + esc(String(r.localhostAlias)) + '</code>' : ''}</div>
      <table class="engine-test-table">${rows}</table>
      ${r.ready ? '' : '<div class="et-hint dim">Fix the required (❌) checks — see the Linux ZFS setup steps. Optional (⚠️) checks only matter for specific source types: Docker for Postgres/SQL Server capture, NFS for serving VDBs.</div>'}
    </div>`;
  } catch (e) {
    box.innerHTML = `<div class="engine-test notready"><div class="et-head">⛔ ${esc(e.message)}</div>
      <div class="et-hint dim">Set FORGETDM_ZFS_HOST + SSH key auth first, then restart. This runs over the same SSH channel snapshots use.</div></div>`;
  }
}

function renderVirtualization() {
  const dsById = Object.fromEntries(dataSources.map(d => [String(d.id), d]));
  $('virt-snap-count').textContent = `${virtualSnapshots.length} snapshot${virtualSnapshots.length === 1 ? '' : 's'}`;
  $('virt-vdb-count').textContent = `${virtualDbs.length} VDB${virtualDbs.length === 1 ? '' : 's'}`;
  $('virt-snapshots').innerHTML = virtualSnapshots.length ? `<table><tr><th>ID</th><th>Type</th><th>Name</th><th>Origin</th><th>Rows</th><th>Created</th><th></th></tr>` +
    virtualSnapshots.map(s => {
      const origin = s.vdbId ? `VDB #${s.vdbId}` : (dsById[String(s.sourceId)]?.name || `source #${s.sourceId}`);
      const prov = (s.provider === 'CONTAINER' || s.provider === 'ZFS') ? ` · <span class="mono">${esc(s.imageRef || s.provider)}</span>` : '';
      const blocks = s.chunkCount ? `<div class="sub tight">${s.newChunkCount}/${s.chunkCount} new blocks · ${fmtBytes(s.storedBytes)} stored${prov}</div>`
        : (s.provider === 'CONTAINER' ? `<div class="sub tight">${fmtBytes(s.storedBytes)} image${prov}</div>`
        : s.provider === 'ZFS' ? `<div class="sub tight">${fmtBytes(s.storedBytes)} changed blocks${prov}</div>` : '');
      return `<tr><td>${s.id}</td><td>${snapshotPill(s.snapshotType)}</td><td><b>${esc(s.name)}</b><div class="sub tight">${esc(s.note || '')}</div></td>
        <td>${esc(origin)}</td><td>${s.tableCount} table(s), ${s.rowCount} row(s)${blocks}</td>
        <td class="mono">${new Date(s.createdAt).toLocaleString()}</td>
        <td>
          <button class="small ghost" onclick="provisionVdb(${s.id})">Provision VDB</button>
          <button class="small danger" onclick="deleteSnapshot(${s.id}, ${esc(JSON.stringify(s.name))})">Delete</button>
        </td></tr>`;
    }).join('') + '</table>' : '<div class="empty">No snapshots yet.</div>';

  $('virt-vdbs').innerHTML = virtualDbs.length ? `<table><tr><th>ID</th><th>Name</th><th>Provider</th><th>Status</th><th>Datasource</th><th>Snapshot</th><th>JDBC</th><th></th></tr>` +
    virtualDbs.map(v => {
      const provDetail = v.provider === 'CONTAINER' ? `<div class="sub tight">container: ${esc(v.containerId || '')}</div>`
        : v.provider === 'ZFS' ? `<div class="sub tight">storage: ${esc(v.storagePath || '')}</div>` : '';
      const envBadge = v.environmentId ? ` · env #${v.environmentId}` : '';
      return `<tr><td>${v.id}</td><td><b>${esc(v.name)}</b>${provDetail}</td>
        <td>${v.provider || 'POOL'}${envBadge}</td>
        <td>${statusPill(v.status)}</td>
        <td>${v.dataSourceId ? `#${v.dataSourceId}` : '-'}</td>
        <td>${v.currentSnapshotId ? `#${v.currentSnapshotId}` : '-'}</td>
        <td class="mono" style="max-width:240px;overflow:hidden;text-overflow:ellipsis">${esc(v.jdbcUrl)}</td>
        <td><button class="small danger" onclick="deleteVdb(${v.id}, ${esc(JSON.stringify(v.name))})">Delete</button></td></tr>`;
    }).join('') + '</table>' : '<div class="empty">No VDBs yet. Provision one from a snapshot.</div>';

  setOptionsPreserve('virt-op-vdb', '<option value="">Select VDB</option>' +
    virtualDbs.map(v => `<option value="${v.id}">${esc(v.name)} (#${v.id})</option>`).join(''));
  setOptionsPreserve('virt-provision-target', '<option value="">Embedded H2 (thin clone)</option>' +
    dataSources.filter(d => d.role !== 'SOURCE').map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.kind)})</option>`).join(''));
  // LogSync data source selector
  setOptionsPreserve('virt-ls-ds', dataSources.map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join(''));
  const selectedVdb = $('virt-op-vdb').value;
  const dsSnaps = virtualSnapshots.filter(s => s.snapshotType === 'DSOURCE');
  const rewindSnaps = selectedVdb ? virtualSnapshots.filter(s => String(s.vdbId || '') === selectedVdb) : [];
  const snapOpts = [
    ...dsSnaps.map(s => `<option value="${s.id}">Refresh: ${esc(s.name)} (#${s.id})</option>`),
    ...rewindSnaps.map(s => `<option value="${s.id}">Rewind: ${esc(s.name)} (#${s.id})</option>`)
  ].join('');
  setOptionsPreserve('virt-op-snapshot', '<option value="">Select snapshot</option>' + snapOpts);
  enhanceMinimizers($('page-virtualize'));
}

function snapshotPill(type) {
  const kind = type === 'DSOURCE' ? 'info' : type === 'BOOKMARK' ? 'warn' : 'good';
  return pill(type, kind);
}

async function provisionVdb(snapshotId) {
  const name = await uiPrompt('VDB name?', `qa-vdb-${snapshotId}`);
  if (!name) return;
  try {
    const target = $('virt-provision-target')?.value;
    const pit = $('virt-pit')?.value?.trim() || null;
    const envId = $('virt-provision-env')?.value || null;
    const r = await api.post('/api/virtualization/vdbs', {
      snapshotId, name,
      targetDataSourceId: target ? parseInt(target) : null,
      pointInTime: pit || null,
      environmentId: envId ? parseInt(envId) : null
    });
    activatePageTab('virtualize', 'virt-capture-card', true);   // the live panel lives on the capture card
    virtWatchOp(r.opId);
  } catch (e) { toast(e.message, 'err'); }
}

// Poll a virtualization operation and render live stage progress (and a PERSISTENT final status/error).
let virtOpTimer = null;
async function virtWatchOp(opId) {
  if (!opId) return;
  clearTimeout(virtOpTimer);
  const poll = async () => {
    try {
      const op = await api.get('/api/virtualization/operations/' + encodeURIComponent(opId));
      renderVirtOp(op);
      if (op.status === 'RUNNING') { virtOpTimer = setTimeout(poll, 1500); return; }
      if (op.status === 'DONE') toast(op.kind === 'PROVISION' ? 'VDB provisioned' : 'Snapshot created', 'ok');
      else if (op.status === 'CANCELLED') toast('Operation cancelled', 'ok');
      else toast('Operation failed — details in the progress panel', 'err');
      schemaCache = {}; tableCache = {};
      await loadVirtualization();
    } catch (e) {
      const panel = $('virt-op-panel');
      if (panel) panel.innerHTML = `<div class="virt-op failed"><b>Lost track of the operation:</b> ${esc(e.message)}</div>`;
    }
  };
  poll();
}

function renderVirtOp(op) {
  const panel = $('virt-op-panel');
  if (!panel) return;
  const secs = Math.round((op.elapsedMs || 0) / 1000);
  const cls = op.status === 'DONE' ? 'done' : (op.status === 'FAILED' || op.status === 'CANCELLED') ? 'failed' : 'running';
  const icon = op.status === 'DONE' ? '✅' : op.status === 'FAILED' ? '⛔' : op.status === 'CANCELLED' ? '🚫' : '⏳';
  const cancelBtn = op.status === 'RUNNING'
    ? ` <button class="small danger ghost" onclick="cancelVirtOp('${esc(op.id)}')">Cancel</button>` : '';
  const stages = (op.stages || []).map(s => {
    const si = s.status === 'DONE' ? '✓' : s.status === 'FAILED' ? '✗' : '⟳';
    const scls = s.status === 'DONE' ? 'st-done' : s.status === 'FAILED' ? 'st-fail' : 'st-run';
    return `<li class="${scls}">${si} ${esc(s.name)} <span class="dim">${Math.round((s.elapsedMs || 0) / 1000)}s</span></li>`;
  }).join('');
  const result = op.status === 'DONE' && op.result
    ? `<div class="dim">${op.result.vdbId ? 'VDB #' + esc(op.result.vdbId) : op.result.snapshotId ? 'Snapshot #' + esc(op.result.snapshotId) : ''} created</div>`
    : '';
  panel.innerHTML = `<div class="virt-op ${cls}">
    <div class="virt-op-head">${icon} <b>${esc(op.label || op.kind)}</b> <span class="dim">· ${esc(op.status.toLowerCase())} · ${secs}s</span>${cancelBtn}</div>
    <ul class="virt-op-stages">${stages || '<li class="dim">starting…</li>'}</ul>
    ${op.error ? `<div class="virt-op-err">⛔ ${esc(op.error)}</div>` : ''}
    ${result}
  </div>`;
}

async function cancelVirtOp(id) {
  try {
    await api.post('/api/virtualization/operations/' + encodeURIComponent(id) + '/cancel');
    toast('Cancelling — stopping the engine container…', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function snapshotSelectedVdb(bookmark) {
  try {
    const vdbId = $('virt-op-vdb').value;
    if (!vdbId) return toast('Select a VDB', 'err');
    const fallback = `${bookmark ? 'bookmark' : 'snapshot'}-${new Date().toISOString().slice(0,16).replace(/[:T]/g,'-')}`;
    const name = $('virt-op-name').value || fallback;
    await api.post(`/api/virtualization/vdbs/${vdbId}/snapshots`, { name, bookmark });
    $('virt-op-name').value = '';
    toast(bookmark ? 'Bookmark created' : 'VDB snapshot created', 'ok');
    await loadVirtualization();
    activatePageTab('virtualize', 'virt-snapshots-card', true);
  } catch (e) { toast(e.message, 'err'); }
}

async function refreshSelectedVdb() {
  try {
    const vdbId = $('virt-op-vdb').value, snapshotId = $('virt-op-snapshot').value;
    if (!vdbId || !snapshotId) return toast('Select VDB and dSource snapshot', 'err');
    await api.post(`/api/virtualization/vdbs/${vdbId}/refresh`, { snapshotId: parseInt(snapshotId) });
    toast('VDB refreshed from dSource snapshot', 'ok');
    await loadVirtualization();
  } catch (e) { toast(e.message, 'err'); }
}

async function rewindSelectedVdb() {
  try {
    const vdbId = $('virt-op-vdb').value, snapshotId = $('virt-op-snapshot').value;
    if (!vdbId || !snapshotId) return toast('Select VDB and VDB snapshot/bookmark', 'err');
    await api.post(`/api/virtualization/vdbs/${vdbId}/rewind`, { snapshotId: parseInt(snapshotId) });
    toast('VDB rewound to selected point', 'ok');
    await loadVirtualization();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteVdb(id, name) {
  if (!await uiConfirm(`Delete VDB "${name}" (#${id})? This destroys its storage and cannot be undone.`, {danger: true, okText: 'Delete'})) return;
  try {
    await api.del(`/api/virtualization/vdbs/${id}`);
    toast('VDB deleted', 'ok');
    schemaCache = {}; tableCache = {};
    await loadVirtualization();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteSnapshot(id, name) {
  if (!await uiConfirm(`Delete snapshot "${name}" (#${id})?`, {danger: true, okText: 'Delete'})) return;
  try {
    await api.del(`/api/virtualization/snapshots/${id}`);
    toast('Snapshot deleted', 'ok');
    await loadVirtualization();
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- LogSync ---------- */
async function enableLogSync() {
  const dsId = $('virt-ls-ds')?.value;
  if (!dsId) return toast('Select a data source', 'err');
  try {
    const r = await api.post(`/api/virtualization/datasources/${dsId}/logsync/enable`);
    $('virt-ls-status').textContent = JSON.stringify(r, null, 2);
    toast('LogSync enabled', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function disableLogSync() {
  const dsId = $('virt-ls-ds')?.value;
  if (!dsId) return toast('Select a data source', 'err');
  if (!await uiConfirm('Stop continuous WAL streaming for this source?', {okText: 'Stop'})) return;
  try {
    const r = await api.post(`/api/virtualization/datasources/${dsId}/logsync/disable`);
    $('virt-ls-status').textContent = JSON.stringify(r, null, 2);
    toast('LogSync disabled', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function getLogSyncStatus() {
  const dsId = $('virt-ls-ds')?.value;
  if (!dsId) return toast('Select a data source', 'err');
  try {
    const r = await api.get(`/api/virtualization/datasources/${dsId}/logsync`);
    const el = $('virt-ls-status');
    el.className = '';
    el.textContent = JSON.stringify(r, null, 2);
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- Target environments ---------- */
let virtEnvironments = [];

async function loadEnvironments() {
  try {
    virtEnvironments = await api.get('/api/virtualization/environments');
    renderEnvironments();
    // populate provision env selector
    setOptionsPreserve('virt-provision-env',
      '<option value="">Engine host (local)</option>' +
      virtEnvironments.map(e => `<option value="${e.id}">${esc(e.name)} — ${esc(e.host)}</option>`).join(''));
  } catch (_) {}
}

function renderEnvironments() {
  const el = $('virt-env-list');
  if (!el) return;
  if (!virtEnvironments.length) { el.innerHTML = '<div class="empty">No environments registered.</div>'; return; }
  el.innerHTML = `<table><tr><th>ID</th><th>Name</th><th>Host</th><th>SSH user</th><th>Port</th><th>Mount base</th><th></th></tr>` +
    virtEnvironments.map(e => `<tr><td>${e.id}</td><td><b>${esc(e.name)}</b></td><td>${esc(e.host)}</td>
      <td>${esc(e.sshUser)}</td><td>${e.sshPort}</td><td class="mono">${esc(e.mountBase)}</td>
      <td><button class="small danger" onclick="deleteEnvironment(${e.id}, '${esc(e.name)}')">Delete</button></td></tr>`
    ).join('') + '</table>';
}

async function createEnvironment() {
  const name = $('env-name')?.value?.trim();
  const host = $('env-host')?.value?.trim();
  const sshUser = $('env-ssh-user')?.value?.trim() || 'root';
  const sshPort = parseInt($('env-ssh-port')?.value || '22');
  const mountBase = $('env-mount-base')?.value?.trim() || '/mnt/forgetdm';
  if (!name || !host) return toast('Name and host are required', 'err');
  try {
    await api.post('/api/virtualization/environments', { name, host, sshUser, sshPort, mountBase });
    $('env-name').value = ''; $('env-host').value = '';
    toast('Environment registered', 'ok');
    await loadEnvironments();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteEnvironment(id, name) {
  if (!await uiConfirm(`Remove environment "${name}" (#${id})?`, {danger: true, okText: 'Remove'})) return;
  try {
    await api.del(`/api/virtualization/environments/${id}`);
    toast('Environment removed', 'ok');
    await loadEnvironments();
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- synthetic ---------- */
const BLUEPRINTS = {
  customer360: {
    table: 'customers',
    columns: [
      ['customer_id','SEQUENCE','CUST-',''],
      ['first_name','FIRST_NAME','',''],
      ['last_name','LAST_NAME','',''],
      ['email','EMAIL','',''],
      ['phone','PHONE_US','',''],
      ['dob','DOB_ADULT','',''],
      ['street','STREET_ADDRESS','',''],
      ['city','CITY','',''],
      ['state','STATE','',''],
      ['zip','ZIP','',''],
      ['status','STATUS','ACTIVE|INACTIVE|PENDING','']
    ]
  },
  banking: {
    table: 'accounts',
    columns: [
      ['account_id','SEQUENCE','ACCT-',''],
      ['customer_id','SEQUENCE','CUST-',''],
      ['account_number','ACCOUNT_NUMBER','12',''],
      ['routing_number','ROUTING_NUMBER_US','',''],
      ['account_type','WEIGHTED','CHECKING:55|SAVINGS:35|MONEY_MARKET:7|CD:3',''],
      ['available_balance','DECIMAL_RANGE','0','250000',''],
      ['current_balance','NORMAL_DECIMAL','35000','22000',''],
      ['risk_score','RISK_SCORE','',''],
      ['status','WEIGHTED','ACTIVE:82|DORMANT:10|CLOSED:5|HOLD:3','']
    ]
  },
  orders: {
    table: 'orders',
    columns: [
      ['order_id','SEQUENCE','ORD-',''],
      ['customer_id','SEQUENCE','CUST-',''],
      ['sku','PRODUCT_SKU','SKU',''],
      ['order_status','ORDER_STATUS','',''],
      ['payment_status','PAYMENT_STATUS','',''],
      ['amount','CURRENCY_USD','5000',''],
      ['created_date','DATE_RECENT','90','']
    ]
  },
  api: {
    table: 'api_events',
    columns: [
      ['event_id','UUID','',''],
      ['request_url','URL','v1/customers',''],
      ['source_ip','IPV4','',''],
      ['http_status','HTTP_STATUS','',''],
      ['payload','JSON_OBJECT','',''],
      ['created_at','TIMESTAMP_RECENT','1440','']
    ]
  }
};

async function initSynthetic() {
  fillDsSelects();
  if ($('syn-src-ds')) setOptionsPreserve('syn-src-ds', '<option value="">Data source</option>' +
    dataSources.map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.role)})</option>`).join(''));
  if ($('vl-ds')) setOptionsPreserve('vl-ds', '<option value="">Data source</option>' +
    dataSources.map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.role)})</option>`).join(''));
  await loadGeneratorCatalog();
  synReceiverChanged();
  loadValueLists();   // reference-data registry — async, non-blocking
  if ($('syn-ds')?.value) await loadSyntheticSchema();
  if ($('syn-tables') && !$('syn-tables').children.length) applyBlueprint('customer360', false);
}

/* ---------- Value lists (reference data registry) ---------- */
async function loadValueLists() {
  const el = $('vl-list');
  if (!el) return;
  try {
    const lists = await api.get('/api/synthetic/value-lists');
    if ($('vl-count')) $('vl-count').textContent = lists.length + ' list(s)';
    if (!lists.length) { el.innerHTML = '<div class="empty">No value lists yet — create one above, or import from a live column.</div>'; return; }
    el.innerHTML = '<table><tr><th>Reference</th><th>System</th><th>Values</th><th>Visibility</th><th>Updated</th><th></th></tr>' +
      lists.map(v => {
        const preview = String(v.listValues || '');
        return `<tr>
          <td><code>@${esc(v.name)}</code>${v.description ? `<div class="muted" style="font-size:11.5px">${esc(v.description)}</div>` : ''}</td>
          <td>${v.systemTag ? `<span class="pill dim">${esc(v.systemTag)}</span>` : ''}</td>
          <td class="mono" title="${esc(preview)}">${esc(preview.length > 60 ? preview.slice(0, 60) + '…' : preview)}</td>
          <td>${pill(v.visibility, v.visibility === 'PRIVATE' ? 'warn' : 'dim')}</td>
          <td class="mono">${v.updatedAt ? new Date(v.updatedAt).toLocaleDateString() : ''}</td>
          <td class="row-actions"><button class="small ghost" onclick="editValueList(${v.id})">Edit</button>
            <button class="small ghost danger" onclick="deleteValueList(${v.id}, ${js(v.name)})">Delete</button></td>
        </tr>`;
      }).join('') + '</table>';
    valueListsCache = lists;
  } catch (e) { el.innerHTML = `<div class="empty">${esc(e.message)}</div>`; }
}

let valueListsCache = [];

function editValueList(id) {
  const v = valueListsCache.find(x => x.id === id);
  if (!v) return;
  $('vl-name').value = v.name;
  $('vl-system').value = v.systemTag || '';
  $('vl-values').value = v.listValues || '';
  $('vl-visibility').value = v.visibility || 'GLOBAL';
  $('vl-name').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

async function saveValueList() {
  try {
    const saved = await api.post('/api/synthetic/value-lists', {
      name: $('vl-name').value.trim(),
      systemTag: $('vl-system').value.trim() || null,
      listValues: $('vl-values').value.trim(),
      visibility: $('vl-visibility').value
    });
    toast(`Saved @${saved.name} — reference it from any ENUM / STATUS / WEIGHTED param`, 'ok');
    await loadValueLists();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteValueList(id, name) {
  if (!await uiConfirm(`Delete value list @${name}? Plans referencing it will fail to resolve until it is recreated.`, { danger: true, okText: 'Delete' })) return;
  try { await api.del('/api/synthetic/value-lists/' + id); toast('Deleted', 'ok'); await loadValueLists(); }
  catch (e) { toast(e.message, 'err'); }
}

async function importValueList() {
  const dsId = $('vl-ds')?.value;
  if (!dsId) return toast('Pick the data source to import from', 'err');
  try {
    const saved = await api.post('/api/synthetic/value-lists/import', {
      dataSourceId: parseInt(dsId),
      schema: $('vl-schema').value.trim() || null,
      table: $('vl-table').value.trim(),
      column: $('vl-column').value.trim(),
      name: $('vl-name').value.trim() || null,
      systemTag: $('vl-system').value.trim() || null,
      weighted: $('vl-weighted').checked,
      visibility: $('vl-visibility').value
    });
    toast(`Imported @${saved.name} (${String(saved.listValues || '').split('|').length} values)`, 'ok');
    await loadValueLists();
  } catch (e) { toast(e.message, 'err'); }
}

async function loadSyntheticSchema() {
  const ds = $('syn-ds').value;
  setOptionsPreserve('syn-schema', '<option value="">Default schema</option>');
  if (!ds) return;
  await fillSchemaSelect(ds, 'syn-schema', 'Default schema');
}

async function loadSyntheticTables() {
  const ds = $('syn-ds').value, schema = $('syn-schema').value;
  if (!ds || !schema) return;
  await fillTableSelect(ds, schema, 'syn-existing-table');
}

async function loadSyntheticColumns() {
  const ds = $('syn-ds').value, schema = $('syn-schema').value, table = $('syn-existing-table').value;
  if (!ds || !schema || !table) return toast('Select target datasource, schema, and table', 'err');
  const cols = await api.get(`/api/datasources/${ds}/tables/${encodeURIComponent(table)}/columns?schema=${encodeURIComponent(schema)}`);
  $('syn-table').value = table;
  $('syn-cols').innerHTML = '';
  cols.forEach(c => {
    const gen = suggestGeneratorForColumn(c.column, c.type);
    addSynCol(c.column, gen, defaultParamForGenerator(gen, c.column), defaultParam2ForGenerator(gen));
  });
  toast(`Loaded ${cols.length} column(s) from ${table}`, 'ok');
}

async function loadGeneratorCatalog() {
  if (generatorCatalog.length) return;
  try {
    generatorCatalog = await api.get('/api/synthetic/generators');
    GENERATORS = generatorCatalog.map(g => g.name);
    renderGeneratorFilters();
    renderGeneratorCatalog();
    refreshGeneratorSelects();
  } catch (e) {
    if ($('gen-count')) $('gen-count').textContent = 'offline catalog';
  }
}

function renderGeneratorFilters() {
  const el = $('gen-category');
  if (!el) return;
  const cats = [...new Set(generatorCatalog.map(g => g.category))].sort();
  el.innerHTML = '<option value="">All categories</option>' + cats.map(c => `<option>${esc(c)}</option>`).join('');
}

function renderGeneratorCatalog() {
  const target = $('gen-catalog');
  if (!target) return;
  const q = ($('gen-search')?.value || '').trim().toLowerCase();
  const cat = $('gen-category')?.value || '';
  const rows = generatorCatalog.filter(g =>
    (!cat || g.category === cat) &&
    (!q || [g.name, g.category, g.description, g.example].join(' ').toLowerCase().includes(q)));
  if ($('gen-count')) $('gen-count').textContent = rows.length + ' of ' + generatorCatalog.length;
  target.innerHTML = rows.length ? rows.map(g => `<div class="generator-tile ${selectedGenerator === g.name ? 'active' : ''}" onclick="selectGenerator(${js(g.name)})">
      <b>${esc(g.name)}</b>
      <p>${esc(g.description)}</p>
      <div class="generator-meta">${pill(g.category,'dim')}<button class="small ghost" onclick="event.stopPropagation(); addSynCol(${js(suggestColumnName(g.name))},${js(g.name)})">Add</button></div>
      <div class="param-hint">${esc([g.param1 && ('p1: ' + g.param1), g.param2 && ('p2: ' + g.param2)].filter(Boolean).join(' | ') || 'No params')}</div>
    </div>`).join('')
    : '<div class="empty">No generators match that search.</div>';
}

async function selectGenerator(name) {
  selectedGenerator = name;
  renderGeneratorCatalog();
  await previewGenerator(name);
}

async function previewGenerator(name) {
  const panel = $('gen-preview');
  if (!panel) return;
  try {
    const r = await api.post('/api/synthetic/preview', { generator: name, seed: parseInt($('syn-seed')?.value || '42'), rows: 6 });
    panel.classList.remove('empty');
    panel.innerHTML = `<b>${esc(name)}</b><br>${r.values.map(v => `<span>${esc(v)}</span>`).join('<br>')}`;
  } catch (e) {
    panel.classList.add('empty');
    panel.textContent = e.message;
  }
}

const SYN_DATASETS = {
  customer360: { dataset: 'customer360', tables: [
    { name: 'customers', rows: 500, cols: [
      ['customer_id','SEQUENCE','C-','',true,''], ['first_name','FIRST_NAME','','',false,''],
      ['last_name','LAST_NAME','','',false,''], ['email','EMAIL','','',false,''],
      ['phone','PHONE_US','','',false,''], ['gender','GENDER_WEIGHTED','F:50|M:50|X:0','',false,''],
      ['city','CITY','','',false,''], ['state','STATE','','',false,''] ] },
    { name: 'addresses', rows: 600, cols: [
      ['address_id','SEQUENCE','A-','',true,''], ['customer_id','SEQUENCE','','',false,'customers.customer_id'],
      ['street','STREET_ADDRESS','','',false,''], ['city','CITY','','',false,''],
      ['state','STATE','','',false,''], ['zip','ZIP','','',false,''] ] } ] },
  banking: { dataset: 'banking-core-scenarios', tables: [
    { name: 'branches', rows: 25, cols: [
      ['branch_id','SEQUENCE','BR-','',true,''], ['branch_code','PADDED_SEQUENCE','5','BR',false,''],
      ['branch_name','TEMPLATE','${city} ${pick:Retail|Commercial|Private Banking|Operations}','',false,''],
      ['city','CITY','','',false,''], ['state','STATE','','',false,''], ['routing_number','ROUTING_NUMBER_US','','',false,''] ] },
    { name: 'customers', rows: 1000, cols: [
      ['customer_id','SEQUENCE','C-','',true,''], ['first_name','FIRST_NAME_BY_LOCALE','US','ANY',false,''],
      ['last_name','LAST_NAME_BY_LOCALE','US','',false,''], ['full_name','TEMPLATE','${first_name} ${last_name}','',false,''],
      ['email','EMAIL','US','ANY',false,''], ['phone','PHONE_US','','',false,''], ['dob','DOB_ADULT','','',false,''],
      ['risk_segment','WEIGHTED','LOW:72|MEDIUM:22|HIGH:6','',false,''],
      ['kyc_status','WEIGHTED','VERIFIED:88|REVIEW:9|REJECTED:3','',false,''],
      ['onboarded_date','DATE_BETWEEN','2020-01-01','2026-06-21',false,''],
      ['street','STREET_ADDRESS','','',false,''], ['city','CITY','','',false,''], ['state','STATE','','',false,''], ['zip','ZIP','','',false,''] ] },
    { name: 'accounts', rows: 1800, cols: [
      ['account_id','SEQUENCE','AC-','',true,''], ['customer_id','SEQUENCE','','',false,'customers.customer_id'],
      ['branch_id','SEQUENCE','','',false,'branches.branch_id'], ['account_number','ACCOUNT_NUMBER','12','',false,''],
      ['routing_number','LOOKUP','routing_number','branch_id',false,''], ['account_type','WEIGHTED','CHECKING:55|SAVINGS:35|MONEY_MARKET:7|CD:3','',false,''],
      ['open_date','DATE_BETWEEN','2020-01-01','2026-06-21',false,''], ['status','WEIGHTED','ACTIVE:82|DORMANT:10|CLOSED:5|HOLD:3','',false,''],
      ['available_balance','DECIMAL_RANGE','0','250000',false,''], ['current_balance','NORMAL_DECIMAL','35000','22000',false,''],
      ['overdraft_allowed','BOOLEAN_WEIGHTED','18','',false,''] ] },
    { name: 'cards', rows: 1200, cols: [
      ['card_id','SEQUENCE','CARD-','',true,''], ['account_id','SEQUENCE','','',false,'accounts.account_id'],
      ['customer_id','LOOKUP','customer_id','account_id',false,''], ['card_number','CREDIT_CARD_VISA','','',false,''],
      ['card_status','WEIGHTED','ACTIVE:78|LOCKED:8|EXPIRED:7|CLOSED:7','',false,''],
      ['issue_date','DATE_RECENT','1460','',false,''], ['expiry_date','DATE_FUTURE','1460','',false,''],
      ['credit_limit','DECIMAL_RANGE','500','50000',false,''] ] },
    { name: 'loans', rows: 420, cols: [
      ['loan_id','SEQUENCE','LN-','',true,''], ['customer_id','SEQUENCE','','',false,'customers.customer_id'],
      ['loan_type','WEIGHTED','AUTO:35|MORTGAGE:28|PERSONAL:25|STUDENT:12','',false,''],
      ['origination_date','DATE_RECENT','1460','',false,''],
      ['principal_amount','DECIMAL_RANGE','1000','750000',false,''], ['outstanding_balance','DECIMAL_RANGE','0','750000',false,''],
      ['delinquency_bucket','WEIGHTED','CURRENT:86|DAYS_30:7|DAYS_60:4|DAYS_90:2|CHARGEOFF:1','',false,''],
      ['loan_status','CASE','delinquency_bucket','CHARGEOFF=CHARGED_OFF|CURRENT=ACTIVE|*=DELINQUENT',false,''] ] },
    { name: 'merchants', rows: 300, cols: [
      ['merchant_id','SEQUENCE','M-','',true,''], ['merchant_name','COMPANY','','',false,''],
      ['merchant_category','WEIGHTED','GROCERY:18|FUEL:14|RETAIL:24|TRAVEL:8|DINING:20|HEALTHCARE:6|OTHER:10','',false,''],
      ['city','CITY','','',false,''], ['state','STATE','','',false,''] ] },
    { name: 'payments', rows: 5000, cols: [
      ['payment_id','SEQUENCE','P-','',true,''], ['account_id','SEQUENCE','','',false,'accounts.account_id'],
      ['merchant_id','SEQUENCE','','',false,'merchants.merchant_id'], ['payment_date','DATE_RECENT','365','',false,''],
      ['amount','DECIMAL_RANGE','1','5000',false,''], ['payment_status','PAYMENT_STATUS','','',false,''],
      ['channel','WEIGHTED','ACH:28|CARD:42|WIRE:5|ATM:7|BRANCH:6|MOBILE:12','',false,''],
      ['fraud_flag','BOOLEAN_WEIGHTED','2','',false,''] ] },
    { name: 'statements', rows: 1800, cols: [
      ['statement_id','SEQUENCE','ST-','',true,''], ['account_id','SEQUENCE','','',false,'accounts.account_id'],
      ['statement_start','DATE_RECENT','730','',false,''], ['statement_end','DATE_AFTER','statement_start','31',false,''],
      ['opening_balance','DECIMAL_RANGE','0','250000',false,''], ['closing_balance','DECIMAL_RANGE','0','250000',false,''],
      ['minimum_due','DECIMAL_RANGE','0','5000',false,''] ] } ] },
  orders: { dataset: 'orders', tables: [
    { name: 'customers', rows: 200, cols: [
      ['customer_id','SEQUENCE','C-','',true,''], ['full_name','FULL_NAME','','',false,''], ['email','EMAIL','','',false,''] ] },
    { name: 'orders', rows: 800, cols: [
      ['order_id','SEQUENCE','O-','',true,''], ['customer_id','SEQUENCE','','',false,'customers.customer_id'],
      ['sku','PRODUCT_SKU','SKU','',false,''], ['amount','CURRENCY_USD','2000','',false,''],
      ['order_status','ORDER_STATUS','','',false,''], ['created_at','TIMESTAMP_RECENT','43200','',false,''] ] } ] },
  api: { dataset: 'api-contract', tables: [
    { name: 'events', rows: 500, cols: [
      ['id','UUID','','',true,''], ['user','USERNAME','','',false,''], ['ip','IPV4','','',false,''],
      ['url','URL','app','',false,''], ['http_status','HTTP_STATUS','','',false,''],
      ['payload','JSON_OBJECT','','',false,''], ['ts','TIMESTAMP_RECENT','1440','',false,''] ] } ] }
};

function applyBlueprint(key, announce = true) {
  const bp = SYN_DATASETS[key] || SYN_DATASETS.customer360;
  if ($('syn-dataset')) $('syn-dataset').value = bp.dataset;
  $('syn-tables').innerHTML = '';
  bp.tables.forEach(t => synAddTable({
    name: t.name, rows: t.rows,
    columns: t.cols.map(([name, gen, p1, p2, pk, fk]) => ({ name, gen, p1, p2, pk, fk }))
  }));
  if (announce) toast('Blueprint applied: ' + key, 'ok');
}

function genOptions(selected) {
  const list = selected && !GENERATORS.includes(selected) ? [selected, ...GENERATORS] : GENERATORS;
  return list.map(g => `<option ${g === selected ? 'selected' : ''}>${g}</option>`).join('');
}

function refreshGeneratorSelects() {
  document.querySelectorAll('.syn-gen').forEach(sel => {
    const value = sel.value;
    sel.innerHTML = genOptions(value);
    sel.value = GENERATORS.includes(value) ? value : GENERATORS[0];
  });
}

function addSynCol(name, gen, p1, p2) {
  let panels = document.querySelectorAll('#syn-tables [data-syn-table]');
  if (!panels.length) { synAddTable({ name: 'table1', rows: 100, columns: [] }); panels = document.querySelectorAll('#syn-tables [data-syn-table]'); }
  const cols = panels[panels.length - 1].querySelector('.syn-cols');
  synAddColRow(cols, { name, gen, p1, p2 });
}

function addCountryAddressCols() {
  const country = $('syn-country').value || 'US';
  const states = $('syn-states').value || null;
  addSynCol('address_line', 'ADDRESS_BY_COUNTRY', country, states);
  addSynCol('city', 'CITY_BY_COUNTRY', country, states);
  addSynCol('state', 'STATE_BY_COUNTRY', country, states);
  addSynCol(country === 'IN' ? 'pin_code' : 'zip', 'POSTAL_BY_COUNTRY', country, states);
}

function suggestColumnName(generator) {
  const map = {
    FIRST_NAME:'first_name', LAST_NAME:'last_name', FULL_NAME:'full_name', MALE_FIRST_NAME:'first_name',
    FEMALE_FIRST_NAME:'first_name', FIRST_NAME_BY_LOCALE:'first_name', LAST_NAME_BY_LOCALE:'last_name',
    FULL_NAME_BY_LOCALE:'full_name', USERNAME:'username',
    EMAIL:'email', PHONE_US:'phone', SSN:'ssn', DOB_ADULT:'dob', CREDIT_CARD_VISA:'card_number',
    CREDIT_CARD_MC:'card_number', CREDIT_CARD_AMEX:'card_number', ACCOUNT_NUMBER:'account_number',
    ROUTING_NUMBER_US:'routing_number', CURRENCY_USD:'amount', IBAN_LIKE:'iban', BIC:'bic',
    DATE_RECENT:'created_date', DATE_BETWEEN:'effective_date', TIMESTAMP_RECENT:'created_at',
    STREET_ADDRESS:'street', CITY:'city', STATE:'state', ZIP:'zip', GEO_TRIPLET:'city_state_zip',
    LATITUDE:'latitude', LONGITUDE:'longitude', COMPANY:'company_name', PRODUCT_SKU:'sku',
    ORDER_STATUS:'order_status', PAYMENT_STATUS:'payment_status', UUID:'id', SEQUENCE:'id',
    GENDER_WEIGHTED:'gender', ADDRESS_BY_COUNTRY:'address_line', CITY_BY_COUNTRY:'city',
    STATE_BY_COUNTRY:'state', POSTAL_BY_COUNTRY:'postal_code',
    DOMAIN:'domain', URL:'url', IPV4:'ip_address', IPV6:'ip_address', MAC_ADDRESS:'mac_address'
  };
  return map[generator] || generator.toLowerCase();
}

function suggestGeneratorForColumn(column, type) {
  const c = column.toLowerCase();
  const t = String(type || '').toLowerCase();
  const words = c.replace(/[^a-z0-9]+/g, ' ');
  const femaleHint = /\b(female|mother|wife|daughter|girl)\b/.test(words);
  const maleHint = /\b(male|father|husband|son|boy)\b/.test(words);
  if (c === 'id' || c.endsWith('_id')) return 'SEQUENCE';
  if (femaleHint && (c.includes('first') || c.includes('name'))) return 'FEMALE_FIRST_NAME';
  if (maleHint && (c.includes('first') || c.includes('name'))) return 'MALE_FIRST_NAME';
  if (c.includes('first')) return 'FIRST_NAME';
  if (c.includes('last')) return 'LAST_NAME';
  if (c.includes('full') || c.includes('cardholder')) return 'FULL_NAME';
  if (c.includes('gender')) return 'GENDER_WEIGHTED';
  if (c.includes('email')) return 'EMAIL';
  if (c.includes('phone')) return 'PHONE_US';
  if (c.includes('ssn')) return 'SSN';
  if (c.includes('card')) return 'CREDIT_CARD_VISA';
  if (c.includes('account_no') || c.includes('account_number')) return 'ACCOUNT_NUMBER';
  if (c.includes('routing')) return 'ROUTING_NUMBER_US';
  if (c.includes('street') || c.includes('address')) return 'ADDRESS_BY_COUNTRY';
  if (c === 'city') return 'CITY_BY_COUNTRY';
  if (c === 'state' || c.includes('state_code')) return 'STATE_BY_COUNTRY';
  if (c.includes('zip') || c.includes('postal') || c.includes('pin')) return 'POSTAL_BY_COUNTRY';
  if (c.includes('date') || c.endsWith('_on')) return 'DATE_RECENT';
  if (c.includes('amount') || c.includes('balance') || t.includes('numeric') || t.includes('decimal')) return 'DECIMAL_RANGE';
  if (c.includes('status')) return 'STATUS';
  if (t.includes('bool')) return 'BOOLEAN';
  return 'ALPHANUMERIC';
}

function defaultParamForGenerator(gen, column) {
  if (gen === 'GENDER_WEIGHTED') return 'F:40|M:60|X:0';
  if (gen.endsWith('_BY_COUNTRY') || gen === 'ADDRESS_BY_COUNTRY') return $('syn-country')?.value || 'US';
  if (gen.endsWith('_BY_LOCALE') || gen === 'MALE_FIRST_NAME' || gen === 'FEMALE_FIRST_NAME') return $('syn-country')?.value || 'US';
  if (gen === 'SEQUENCE') return column.toUpperCase() + '-';
  return '';
}

function defaultParam2ForGenerator(gen) {
  if (gen === 'FIRST_NAME_BY_LOCALE' || gen === 'FULL_NAME_BY_LOCALE') return 'ANY';
  return (gen.endsWith('_BY_COUNTRY') || gen === 'ADDRESS_BY_COUNTRY') ? ($('syn-states')?.value || '') : '';
}

async function submitSynthetic() {
  const columns = [...$('syn-cols').children].map(r => ({
    name: r.querySelector('.syn-name').value,
    generator: r.querySelector('.syn-gen').value,
    param1: r.querySelector('.syn-p1').value || null,
    param2: r.querySelector('.syn-p2').value || null
  })).filter(c => c.name);
  try {
    await api.post('/api/jobs', { name: 'synthetic-' + $('syn-table').value, jobType: 'SYNTHETIC_LOAD',
      targetId: parseInt($('syn-ds').value),
      specJson: JSON.stringify({ table: $('syn-table').value, rowCount: parseInt($('syn-rows').value||'100'),
        seed: parseInt($('syn-seed').value||'42'), targetSchema: $('syn-schema').value || null, columns }) });
    toast('Synthetic load launched - monitoring progress', 'ok');
    await goTo('jobs', 'job-monitor-card');
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- synthetic v2: multi-table dataset + receivers ---------- */
let synLastFiles = [];

function synReceiverChanged() {
  const r = ($('syn-receiver')?.value) || 'DB';
  if ($('syn-target-row')) $('syn-target-row').style.display = r === 'DB' ? 'flex' : 'none';
  if ($('syn-ddl-row')) $('syn-ddl-row').style.display = (r === 'DB' || r === 'SQL') ? 'flex' : 'none';
}

function synSqlTypeForGenerator(gen) {
  const g = (gen || '').toUpperCase();
  if (g === 'INT_RANGE' || g === 'AGE') return 'INTEGER';
  if (g === 'DECIMAL_RANGE' || g === 'CURRENCY_USD' || g === 'PERCENT' || g === 'RISK_SCORE' || g === 'LATITUDE' || g === 'LONGITUDE') return 'DECIMAL';
  if (g.startsWith('BOOLEAN')) return 'BOOLEAN';
  if (g.startsWith('DATE')) return 'DATE';
  if (g.startsWith('TIMESTAMP')) return 'TIMESTAMP';
  return 'VARCHAR';
}

function synAddTable(prefill) {
  prefill = prefill || { name: 'table' + (document.querySelectorAll('#syn-tables [data-syn-table]').length + 1), rows: 100, columns: [] };
  const panel = document.createElement('div');
  panel.setAttribute('data-syn-table', '');
  panel.style.cssText = 'border:1px solid var(--border);border-radius:8px;padding:10px;margin-bottom:12px';
  panel.innerHTML = `
    <div class="row push">
      <div><label>Table name</label><input class="syn-t-name" value="${esc(prefill.name || '')}"></div>
      <div><label>Rows</label><input class="syn-t-rows" type="number" min="0" value="${esc(prefill.rows ?? 100)}"></div>
      <button class="ghost small" onclick="synAddColRow(this.closest('[data-syn-table]').querySelector('.syn-cols'))">+ Column</button>
      <button class="small danger" onclick="this.closest('[data-syn-table]').remove()">Remove table</button>
    </div>
    <div class="syn-cols"></div>`;
  $('syn-tables').appendChild(panel);
  const cols = panel.querySelector('.syn-cols');
  (prefill.columns || []).forEach(c => synAddColRow(cols, c));
  return panel;
}

function synAddColRow(cols, prefill) {
  prefill = prefill || {};
  const gen = prefill.gen || prefill.generator || 'SEQUENCE';
  let fkTable = prefill.fkTable || '', fkColumn = prefill.fkColumn || '';
  if (!fkTable && prefill.fk && prefill.fk.includes('.')) {
    const i = prefill.fk.indexOf('.'); fkTable = prefill.fk.slice(0, i); fkColumn = prefill.fk.slice(i + 1);
  }
  const fkVal = fkTable && fkColumn ? fkTable + '.' + fkColumn : '';
  const isFk = !!fkVal;
  const row = document.createElement('div');
  row.className = 'syn-row syn-col' + (isFk ? ' fk-locked' : '');
  row.dataset.sqltype = prefill.sqlType || synSqlTypeForGenerator(gen);
  const dis = isFk ? ' disabled' : '';
  row.innerHTML = `
    <div><label>Column</label><input class="syn-c-name" value="${esc(prefill.name || '')}"></div>
    <div><label>Generator</label><select class="syn-c-gen" onchange="synGenChanged(this)"${dis}>${genOptions(isFk ? 'SEQUENCE' : gen)}</select></div>
    <div><label>Param1</label><input class="syn-c-p1" value="${esc(prefill.p1 ?? prefill.param1 ?? '')}"${dis}></div>
    <div><label>Param2</label><input class="syn-c-p2" value="${esc(prefill.p2 ?? prefill.param2 ?? '')}"${dis}></div>
    <label class="check" title="Primary key"><input type="checkbox" class="syn-c-pk" ${prefill.pk || prefill.primaryKey ? 'checked' : ''}> PK</label>
    <div><label>FK &rarr; table.column</label><input class="syn-c-fk" value="${esc(fkVal)}" placeholder="customers.customer_id"${isFk ? ' readonly' : ''}></div>
    ${isFk
      ? `<span class="fk-badge" title="This is a child column. Values are drawn from the parent key so they stay referentially intact and can't be set manually.">&#8627; child of ${esc(fkTable)}.${esc(fkColumn)}</span>`
      : ''}
    <button class="small danger" onclick="this.closest('.syn-col').remove()">x</button>`;
  cols.appendChild(row);
}

/* Build an ER graph (tables + FK edges) from the current plan — like Discovery's traversal view. */
function renderSynER() {
  const el = $('syn-er'); if (!el) return;
  const tables = collectSynPlan().tables;
  if (!tables.length) { el.className = 'syn-er empty'; el.textContent = 'Add related tables to see the ER graph.'; return; }
  el.className = 'syn-er';

  const byName = {}; tables.forEach(t => { byName[t.name.toLowerCase()] = t; });
  const edges = [];
  tables.forEach(t => (t.columns || []).forEach(c => {
    if (c.fkTable && byName[c.fkTable.toLowerCase()])
      edges.push({ child: t.name.toLowerCase(), parent: c.fkTable.toLowerCase() });
  }));

  // level = depth from roots (parents first), relaxed for FK chains
  const level = {}; tables.forEach(t => { level[t.name.toLowerCase()] = 0; });
  for (let i = 0; i < tables.length; i++) edges.forEach(e => {
    if (level[e.child] <= level[e.parent]) level[e.child] = level[e.parent] + 1;
  });
  const colsByLevel = {};
  tables.forEach(t => { const l = level[t.name.toLowerCase()]; (colsByLevel[l] = colsByLevel[l] || []).push(t); });
  const levels = Object.keys(colsByLevel).map(Number).sort((a, b) => a - b);

  const COLW = 230, ROWH = 22, HEAD = 30, GAPX = 80, GAPY = 36, PAD = 18, MAXC = 12;
  const pos = {};
  let maxBottom = 0;
  levels.forEach((l, li) => {
    let y = PAD;
    colsByLevel[l].forEach(t => {
      const shown = Math.min((t.columns || []).length, MAXC);
      const h = HEAD + shown * ROWH + ((t.columns || []).length > MAXC ? ROWH : 0) + 8;
      pos[t.name.toLowerCase()] = { x: PAD + li * (COLW + GAPX), y, w: COLW, h, t };
      y += h + GAPY;
      maxBottom = Math.max(maxBottom, y);
    });
  });
  const width = PAD * 2 + Math.max(1, levels.length) * (COLW + GAPX) - GAPX;
  const height = Math.max(maxBottom, 120);

  let s = `<svg viewBox="0 0 ${width} ${height}" width="${width}" height="${height}">`;
  s += `<defs><marker id="syn-arr" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto"><path d="M0,0 L9,4.5 L0,9 z" fill="var(--accent)"/></marker></defs>`;
  edges.forEach(e => {
    const p = pos[e.parent], c = pos[e.child]; if (!p || !c) return;
    const x1 = p.x + p.w, y1 = p.y + HEAD / 2, x2 = c.x, y2 = c.y + HEAD / 2;
    const dx = Math.max(30, Math.abs(x2 - x1) * 0.4);
    s += `<path d="M${x1},${y1} C${x1 + dx},${y1} ${x2 - dx},${y2} ${x2},${y2}" fill="none" stroke="var(--accent)" stroke-width="1.6" marker-end="url(#syn-arr)" opacity="0.8"/>`;
  });
  Object.values(pos).forEach(n => {
    const t = n.t;
    s += `<rect x="${n.x}" y="${n.y}" width="${n.w}" height="${n.h}" rx="8" fill="var(--panel)" stroke="var(--border)"/>`;
    s += `<rect x="${n.x}" y="${n.y}" width="${n.w}" height="${HEAD}" rx="8" fill="var(--accent-soft)"/>`;
    s += `<text x="${n.x + 12}" y="${n.y + 20}" font-size="13" font-weight="700" fill="var(--text)">${esc(t.name)}</text>`;
    (t.columns || []).slice(0, MAXC).forEach((c, i) => {
      const cy = n.y + HEAD + i * ROWH + 15;
      const mark = (c.primaryKey ? '* ' : '') + (c.fkTable ? '↳ ' : '');
      s += `<text x="${n.x + 12}" y="${cy}" font-size="11.5" fill="${c.fkTable ? 'var(--accent)' : 'var(--muted)'}">${esc(mark + c.name)}</text>`;
    });
    if ((t.columns || []).length > MAXC)
      s += `<text x="${n.x + 12}" y="${n.y + HEAD + MAXC * ROWH + 15}" font-size="11" fill="var(--muted)">… +${t.columns.length - MAXC} more</text>`;
  });
  s += `</svg>`;
  el.innerHTML = `<div class="syn-er-scroll">${s}</div>`;
}

function synGenChanged(sel) {
  const row = sel.closest('.syn-col');
  if (row) row.dataset.sqltype = synSqlTypeForGenerator(sel.value);
  previewGenerator(sel.value);
}

async function loadSynSrcSchema() {
  const ds = $('syn-src-ds').value;
  setOptionsPreserve('syn-src-schema', '<option value="">Schema</option>');
  setOptionsPreserve('syn-src-table', '<option value="">Table</option>');
  if (!ds) return;
  await fillSchemaSelect(ds, 'syn-src-schema', 'Schema');
  await loadSynSrcTables();
}

async function loadSynSrcTables() {
  const ds = $('syn-src-ds').value, schema = $('syn-src-schema').value;
  if (!ds || !schema) return;
  await fillTableSelect(ds, schema, 'syn-src-table');
  const sel = $('syn-src-table');                 // it's a multi-select — drop the empty placeholder option
  if (sel && sel.options.length && sel.options[0].value === '') sel.remove(0);
}

async function addTablesFromExisting(all) {
  const ds = $('syn-src-ds').value, schema = $('syn-src-schema').value;
  if (!ds || !schema) return toast('Pick a data source and schema', 'err');
  const sel = $('syn-src-table');
  let names = (all ? [...sel.options] : [...sel.selectedOptions]).map(o => o.value).filter(Boolean);
  names = [...new Set(names)];
  if (!names.length) return toast(all ? 'No tables in this schema' : 'Select one or more tables', 'err');

  const existing = new Set([...document.querySelectorAll('#syn-tables .syn-t-name')].map(i => i.value.trim().toLowerCase()));
  let added = 0;
  for (const table of names) {
    if (existing.has(table.toLowerCase())) continue;
    try {
      const [colsMeta, fks] = await Promise.all([
        api.get(`/api/datasources/${ds}/tables/${encodeURIComponent(table)}/columns?schema=${encodeURIComponent(schema)}`),
        api.get(`/api/datasources/${ds}/tables/${encodeURIComponent(table)}/fks?schema=${encodeURIComponent(schema)}`).catch(() => [])
      ]);
      const fkByCol = {};
      (fks || []).forEach(f => { fkByCol[(f.column || '').toLowerCase()] = f; });
      const columns = colsMeta.map(c => {
        const f = fkByCol[(c.column || '').toLowerCase()];
        const gen = suggestGeneratorForColumn(c.column, c.type);
        return {
          name: c.column, gen, p1: defaultParamForGenerator(gen, c.column), p2: defaultParam2ForGenerator(gen),
          sqlType: sqlTypeFromDbType(c.type),
          fkTable: f ? f.refTable : null, fkColumn: f ? f.refColumn : null
        };
      });
      synAddTable({ name: table, rows: 100, columns });
      existing.add(table.toLowerCase());
      added++;
    } catch (e) { toast(`${table}: ${e.message}`, 'err'); }
  }
  if (added) { toast(`Added ${added} table(s) with referential links`, 'ok'); renderSynER(); }
  else toast('Those tables are already added', 'info');
}

function sqlTypeFromDbType(t) {
  const s = String(t || '').toLowerCase();
  if (s.includes('int')) return 'INTEGER';
  if (s.includes('num') || s.includes('dec') || s.includes('double') || s.includes('real') || s.includes('float') || s.includes('money')) return 'DECIMAL';
  if (s.includes('bool') || s === 'bit') return 'BOOLEAN';
  if (s.includes('timestamp') || s.includes('datetime')) return 'TIMESTAMP';
  if (s.includes('date')) return 'DATE';
  return 'VARCHAR';
}

function collectSynPlan() {
  const tables = [...document.querySelectorAll('#syn-tables [data-syn-table]')].map(p => ({
    name: p.querySelector('.syn-t-name').value.trim(),
    rowCount: parseInt(p.querySelector('.syn-t-rows').value || '0'),
    columns: [...p.querySelectorAll('.syn-col')].map(r => {
      const fk = r.querySelector('.syn-c-fk').value.trim();
      const dot = fk.indexOf('.');
      return {
        name: r.querySelector('.syn-c-name').value.trim(),
        generator: r.querySelector('.syn-c-gen').value,
        param1: r.querySelector('.syn-c-p1').value || null,
        param2: r.querySelector('.syn-c-p2').value || null,
        primaryKey: r.querySelector('.syn-c-pk').checked,
        fkTable: dot > 0 ? fk.slice(0, dot) : null,
        fkColumn: dot > 0 ? fk.slice(dot + 1) : null,
        sqlType: r.dataset.sqltype || 'VARCHAR'
      };
    }).filter(c => c.name)
  })).filter(t => t.name);
  return {
    dataset: $('syn-dataset').value || 'synthetic',
    seed: parseInt($('syn-seed').value || '42'),
    receiver: $('syn-receiver').value,
    targetDataSourceId: $('syn-ds').value ? parseInt($('syn-ds').value) : null,
    targetSchema: $('syn-schema').value || null,
    createTable: $('syn-create').checked,
    dropTable: $('syn-drop').checked,
    tables
  };
}

async function synGenerate() {
  const plan = collectSynPlan();
  if (!plan.tables.length) return toast('Add at least one table with columns', 'err');
  if (plan.receiver === 'DB' && !plan.targetDataSourceId) return toast('Pick a target data source for DB load', 'err');
  const out = $('syn-result');
  out.classList.remove('empty');
  out.innerHTML = '<div class="loading">Generating…</div>';
  try {
    const r = await api.post('/api/synthetic/generate', plan);
    const summary = (r.tables || []).map(t => `${esc(t.name)}: ${t.rows} rows`).join(' · ');
    if (r.files && r.files.length) {
      synLastFiles = r.files;
      r.files.forEach(f => synDownload(f.name, f.content));
      out.innerHTML = `<p class="sub tight">Generated ${r.files.length} file(s) — downloads started.</p>
        <p class="sub tight">${summary}</p>
        <ul>${r.files.map((f, i) => `<li><code>${esc(f.name)}</code> (${f.content.length} bytes) <button class="ghost small" onclick="synDownloadIdx(${i})">Download again</button></li>`).join('')}</ul>`;
    } else {
      out.innerHTML = `<p class="sub tight">Loaded into <b>${esc($('syn-ds').selectedOptions[0]?.textContent || 'target')}</b>.</p><p class="sub tight">${summary}</p>`;
      toast('Synthetic data loaded', 'ok');
    }
  } catch (e) { out.innerHTML = `<div class="error">${esc(e.message)}</div>`; toast(e.message, 'err'); }
}

function synDownloadIdx(i) { const f = synLastFiles[i]; if (f) synDownload(f.name, f.content); }
function synDownload(name, content) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  document.body.appendChild(a); a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
}

/* ---------- jobs ---------- */
async function loadJobSchemas() {
  if ($('job-src')?.value) await fillSchemaSelect($('job-src').value, 'job-src-schema', 'Default');
  if ($('job-tgt')?.value) await fillSchemaSelect($('job-tgt').value, 'job-tgt-schema', 'Default');
  jobLoadChanged(); // re-evaluate which load options are valid for the current src/tgt pair
}

function jobTypeChanged() {
  const subset = $('job-type').value === 'SUBSET_MASK';
  $('job-subset-row').style.display = subset ? 'flex' : 'none';
  $('job-tables').disabled = subset;
  jobTraversalChanged();
  jobLoadChanged();
}

function jobTraversalChanged() {
  const enabled = $('job-related')?.checked ?? true;
  ['job-q1','job-q2'].forEach(id => { if ($(id)) $(id).disabled = !enabled; });
}

function jobLoadChanged() {
  const sel = $('job-load-action');
  if (!sel) return;

  // IN_PLACE requires source == target (same datasource ID). Disable it otherwise.
  const srcId = $('job-src')?.value;
  const tgtId = $('job-tgt')?.value;
  const canInPlace = !!(srcId && tgtId && srcId === tgtId);
  const inPlaceOpt = [...sel.options].find(o => o.value === 'IN_PLACE');
  if (inPlaceOpt) {
    inPlaceOpt.disabled = !canInPlace;
    if (!canInPlace && sel.value === 'IN_PLACE') sel.value = 'REPLACE';
  }

  const action = sel.value || 'REPLACE';
  const prep = $('job-target-prep');
  const keys = $('job-key-cols');
  const hint = $('job-load-hint');
  if (prep) {
    if (action === 'REPLACE' && prep.value === 'NONE') prep.value = 'DELETE';
    if (action === 'INSERT' && prep.value === 'DELETE') prep.value = 'NONE';
    if (action === 'TRUNCATE_ONLY') prep.value = 'TRUNCATE';
    prep.disabled = action === 'IN_PLACE';   // nothing is cleared for in-place
  }
  if (keys) keys.disabled = !['UPDATE','INSERT_UPDATE','IN_PLACE'].includes(action);
  if (hint) {
    hint.textContent = ({
      REPLACE: 'Load replace clears each selected target table, then inserts masked rows.',
      INSERT: 'Insert only appends rows and lets the database reject duplicate keys.',
      UPDATE: 'Update only changes rows that match key columns; missing rows are skipped.',
      INSERT_UPDATE: 'Insert-update updates matching keys and inserts missing rows.',
      IN_PLACE: 'In-place masks the same table (source = target) in committed chunks via a staging join — no full reload. Key columns optional (defaults to the primary key, used for chunking/matching).',
      TRUNCATE_ONLY: 'Truncate only clears selected target tables without loading rows.'
    })[action] || '';
  }
}

function jobKeyColumns() {
  return ($('job-key-cols')?.value || '').split(',').map(s => s.trim()).filter(Boolean);
}

function jobBatchSize() {
  const raw = parseInt($('job-batch')?.value || '0');
  return raw > 0 ? raw : null;
}

async function submitJob() {
  const type = $('job-type').value;
  const maskingSeed = $('job-seed').value.trim() || null;
  const loadAction = $('job-load-action')?.value || 'REPLACE';
  const targetPrep = $('job-target-prep')?.value || (loadAction === 'REPLACE' ? 'DELETE' : 'NONE');
  const keyColumns = jobKeyColumns();
  const batchSize = jobBatchSize();
  const loadSpec = { loadAction, targetPrep, keyColumns, batchSize };
  const spec = type === 'SUBSET_MASK'
    ? { driverTable: $('job-driver').value, filter: $('job-filter').value || null,
        maxDriverRows: parseInt($('job-max').value || '0'), includeRelated: $('job-related').checked,
        includeParents: $('job-q1').checked, includeChildren: $('job-q2').checked,
        sourceSchema: $('job-src-schema').value || null, targetSchema: $('job-tgt-schema').value || null,
        maskingSeed, ...loadSpec }
    : { tables: $('job-tables').value ? $('job-tables').value.split(',').map(s=>s.trim()).filter(Boolean) : [],
        sourceSchema: $('job-src-schema').value || null, targetSchema: $('job-tgt-schema').value || null,
        maskingSeed, ...loadSpec };
  if (!keyColumns.length) delete spec.keyColumns;
  if (!batchSize) delete spec.batchSize;
  const datasetId = type === 'SUBSET_MASK' && $('job-dataset')?.value
      ? parseInt($('job-dataset').value) : null;
  try {
    const job = await api.post('/api/jobs', { name: $('job-name').value || (type.toLowerCase()+'-job'), jobType: type,
      sourceId: parseInt($('job-src').value), targetId: parseInt($('job-tgt').value),
      policyId: $('job-policy').value ? parseInt($('job-policy').value) : null,
      datasetId, specJson: JSON.stringify(spec) });
    toast(job.status === 'AWAITING_APPROVAL'
      ? 'Submitted for approval — an approver must sign off before it runs'
      : 'Job launched - monitoring progress', 'ok');
    await loadJobs();
    activatePageTab('jobs', 'job-monitor-card', true);
  } catch (e) { toast(e.message, 'err'); }
}

function hasPerm(p) {
  const perms = currentUser?.permissions || [];
  return perms.includes('admin.all') || perms.includes(p);
}

async function approveJob(id) {
  const note = await uiPrompt('Approval note / e-signature reason (required)', '', { title: 'Approve provisioning job', okText: 'Approve & run' });
  if (!note || !note.trim()) return;
  try {
    await api.post(`/api/jobs/${id}/approval/approve`, { note: note.trim() });
    toast('Job approved — running', 'ok');
    await loadJobs();
  } catch (e) { toast(e.message, 'err'); }
}

async function rejectJob(id) {
  const note = await uiPrompt('Rejection reason (required; rejection is final — resubmit a new job to retry)', '', { title: 'Reject provisioning job', okText: 'Reject' });
  if (!note || !note.trim()) return;
  try {
    await api.post(`/api/jobs/${id}/approval/reject`, { note: note.trim() });
    toast('Job rejected', 'ok');
    await loadJobs();
  } catch (e) { toast(e.message, 'err'); }
}

let jobsTimer = null;
function jobSpec(j) {
  try { return JSON.parse(j.specJson || '{}'); } catch { return {}; }
}

async function loadJobs() {
  await refreshShared();
  const [jobs, ret] = await Promise.all([api.get('/api/jobs'), api.get('/api/jobs/retention').catch(() => ({retentionDays: 90}))]);
  const retDays = ret.retentionDays || 0;
  const retNote = retDays > 0
    ? `<span class="job-retention-note">Jobs are automatically removed ${retDays} days after completion.</span>`
    : '';
  $('jobs-table').innerHTML = jobs.length ? retNote + `<table><tr><th>ID</th><th>Name</th><th>Type</th><th>Load</th><th>Status</th><th>Rows</th><th>Message</th><th>Finished</th><th></th></tr>` +
    jobs.map(j => {
      const spec = jobSpec(j);
      const load = spec.loadAction || (spec.truncateTarget ? 'REPLACE' : 'INSERT');
      const prep = spec.targetPrep ? ` / ${spec.targetPrep}` : '';
      const awaiting = j.status === 'AWAITING_APPROVAL';
      const canCancel = j.status === 'RUNNING' || j.status === 'PENDING';
      const canDelete = !canCancel && !awaiting;
      const actions = [
        awaiting && hasPerm('provision.approve') ? `<button class="small" onclick="approveJob(${j.id})">Approve</button>` : '',
        awaiting && hasPerm('provision.approve') ? `<button class="small danger ghost" onclick="rejectJob(${j.id})">Reject</button>` : '',
        awaiting ? `<button class="small ghost" onclick="cancelJob(${j.id})">Withdraw</button>` : '',
        j.status === 'COMPLETED' && j.jobType !== 'SYNTHETIC_LOAD' ? `<button class="small ghost" onclick="showJobSample(${j.id})">Sample</button>` : '',
        canCancel ? `<button class="small danger" onclick="cancelJob(${j.id})">Cancel</button>` : '',
        canDelete ? `<button class="small danger ghost" onclick="deleteJob(${j.id}, ${esc(JSON.stringify(j.name))})">Delete</button>` : ''
      ].filter(Boolean).join(' ');
      return `<tr><td>${j.id}</td><td><b>${esc(j.name)}</b></td><td>${pill(j.jobType,'info')}</td><td>${pill(load + prep,'dim')}</td>
      <td>${statusPill(j.status)}</td><td>${j.rowsProcessed}</td><td class="mono">${esc(j.message ?? '')}</td>
      <td class="mono">${j.finishedAt ? new Date(j.finishedAt).toLocaleTimeString() : '-'}</td>
      <td>${actions}</td></tr>` + renderJobProgress(j) + renderJobConflict(j);
    }).join('') + '</table>' +
      '<div id="job-sample" class="preview-box empty">Select a completed masking job to compare source vs target sample rows.</div>'
    : (retNote || '') + '<div class="empty">No jobs yet.</div>';
  clearTimeout(jobsTimer);
  if (jobs.some(j => j.status === 'RUNNING' || j.status === 'PENDING'))
    jobsTimer = setTimeout(loadJobs, 1500);
}

/* Constraint-failure diagnostic: shows the failed record (original vs masked) and the conflicting record so a
   masked-value collision can be root-caused without re-running or querying the DB by hand. */
function renderJobConflict(j) {
  if (!j.conflictJson) return '';
  let d; try { d = JSON.parse(j.conflictJson); } catch { return ''; }
  const colsOf = rec => (rec && rec.columns) || [];
  const keyset = new Set((d.keyColumns || []).map(c => String(c).toLowerCase()));
  const failed = d.failedRecord, conflict = d.conflictingRecord;
  const baseCols = colsOf(failed).length ? colsOf(failed) : colsOf(conflict);
  const byName = rec => { const m = {}; colsOf(rec).forEach(c => { m[String(c.column).toLowerCase()] = c; }); return m; };
  const fMap = byName(failed), cMap = byName(conflict);
  const keyLabel = (d.keyColumns || []).join(', ');
  const keyVal = (d.keyValues || []).join(', ');
  const sameBatch = !!(conflict && typeof conflict.source === 'string' && conflict.source.startsWith('same'));

  const rows = baseCols.map(c => {
    const lc = String(c.column).toLowerCase();
    const f = fMap[lc] || {}, cf = cMap[lc] || {};
    const isKey = keyset.has(lc);
    const cfVal = cf.masked != null ? cf.masked : (cf.original != null ? cf.original : '');
    const changed = f.original != null && f.masked != null && f.original !== f.masked;
    return `<tr class="${isKey ? 'ds-conflict-key' : ''}">
      <td class="mono">${esc(c.column)}${isKey ? ' 🔑' : ''}</td>
      <td class="mono">${esc(f.original ?? '')}</td>
      <td class="mono ${changed ? 'ds-conflict-changed' : ''}">${esc(f.masked ?? '')}</td>
      <td class="mono">${esc(cfVal)}</td></tr>`;
  }).join('');

  const noParse = !baseCols.length;
  const conflictHeader = conflict && conflict.source ? esc(conflict.source) : 'existing row';
  return `<tr class="ds-conflict-row"><td colspan="9">
    <details class="ds-conflict-panel" open>
      <summary>⚠ Why it failed — constraint conflict on <code>${esc(d.table || '')}</code>${d.constraint ? ' · <code>' + esc(d.constraint) + '</code>' : ''}</summary>
      <div class="ds-conflict-head">${keyLabel ? `Duplicate <b>${esc(keyLabel)}</b> = <b>${esc(keyVal)}</b>` : 'Unique/constraint violation'}</div>
      <div class="ds-conflict-msg mono">${esc(d.message || '')}</div>
      ${noParse ? '<div class="ds-conflict-note">Could not extract the exact records from this database’s error (the constraint name is shown above).</div>'
        : `<table class="ds-conflict-table">
            <tr><th>Column</th><th>Failed row — original</th><th>Failed row — masked</th><th>Conflicting row (${conflictHeader})</th></tr>
            ${rows}
          </table>
          <div class="ds-conflict-note">The failed row’s masked <b>${esc(keyLabel)}</b> collided with the conflicting row. ${sameBatch
            ? 'Both rows are in <b>this</b> load — two different source values masked to the same output (a masking collision on a unique column).'
            : 'The conflicting row was already in the target — a previous load, or an existing row that wasn’t cleared before an INSERT.'}</div>`}
    </details></td></tr>`;
}

/* Basketball court progress panel — replaces old chip row for provisioning/DataScope jobs. */
function renderJobProgress(j) {
  // Only show the live panel while the job is actually running or pending
  if (j.status !== 'RUNNING' && j.status !== 'PENDING') return '';
  if (!j.tableStatesJson) return '';
  let entries;
  try { entries = JSON.parse(j.tableStatesJson); } catch (e) { return ''; }
  if (!entries || !entries.length) return '';

  // Pull __meta__ sentinel (first entry) for job-level phase
  const meta = entries.find(e => e.table === '__meta__') || {};
  const jobPhase = (meta.jobPhase || 'EXTRACT').toUpperCase();
  const tables = entries.filter(e => e.table !== '__meta__');
  // Render even with no table entries yet — happens during EXTRACT/planning before initTableStates fires

  // Phase helpers
  const phases = ['EXTRACT', 'MASK', 'PROVISION'];
  const phaseLabel = { EXTRACT: 'Extract', MASK: 'Mask', PROVISION: 'Load' };
  const phaseIcon  = { EXTRACT: '📥', MASK: '🎭', PROVISION: '📤' };
  const activeIdx  = phases.indexOf(jobPhase);

  // Returns speed + optional ETA. Shows "1,234 rows/s" always when data exists;
  // prepends "~3min left · " when rowsTotal is known.
  function speedAndEta(rowsDone, rowsTotal, startedAtMs) {
    if (!startedAtMs || !(startedAtMs > 0) || rowsDone <= 0) return '';
    const elapsed = (Date.now() - startedAtMs) / 1000;
    if (elapsed < 2) return '';
    const rate = rowsDone / elapsed;                        // rows per second
    const rateStr = rate >= 1
      ? `${Math.round(rate).toLocaleString()} rows/s`
      : `${(rate * 60).toFixed(1)} rows/min`;
    if (rowsTotal > rowsDone) {
      const remaining = (rowsTotal - rowsDone) / rate;
      if (remaining > 1) {
        const etaStr = remaining < 90
          ? `~${Math.ceil(remaining)}s left`
          : `~${Math.ceil(remaining / 60)}min left`;
        return `${etaStr} · ${rateStr}`;
      }
    }
    return rateStr;
  }

  // Build lane HTML
  const lanes = phases.map((ph, idx) => {
    const isActive = ph === jobPhase;
    const isDone   = idx < activeIdx;
    const cls = isDone ? 'bb-lane done' : isActive ? 'bb-lane active' : 'bb-lane';
    const ball = isActive ? '<div class="bb-ball">🏀</div>' : '';
    const statusLabel = isDone ? '✓ Done' : isActive ? 'In progress…' : 'Waiting';
    return `<div class="${cls}">
      <div class="bb-lane-header">${phaseIcon[ph]} ${phaseLabel[ph]}</div>
      ${ball}
      <div class="bb-lane-status">${statusLabel}</div>
    </div>`;
  }).join('<div class="bb-lane-sep">→</div>');

  // Build per-table cards
  const done  = tables.filter(t => (t.state || '').toUpperCase() === 'DONE').length;
  const total = tables.length;

  // Overall row-wise progress + ETA across all tables (parallel-aware: rate from earliest start).
  const sumDone  = tables.reduce((a, t) => a + (t.rowsDone  || 0), 0);
  const sumTotal = tables.reduce((a, t) => a + (t.rowsTotal || 0), 0);
  const startTimes = tables.map(t => t.startedAtMs).filter(s => s > 0);
  const firstStart = startTimes.length ? Math.min(...startTimes) : 0;
  const overallPct = sumTotal > 0 ? Math.min(100, Math.round(sumDone / sumTotal * 100))
                   : (total > 0 ? Math.round(done / total * 100) : 0);
  const overallEta = speedAndEta(sumDone, sumTotal, firstStart);
  const overallRows = sumTotal > 0 ? `${sumDone.toLocaleString()} / ${sumTotal.toLocaleString()} rows`
                    : (sumDone > 0 ? `${sumDone.toLocaleString()} rows` : '');
  const overall = `<div class="bb-overall">
      <div class="bb-overall-top"><span>Overall &mdash; ${overallPct}%</span>
        <span class="bb-overall-meta">${overallRows}${overallEta ? ' · ' + overallEta : ''}</span></div>
      <div class="bb-bar"><div class="bb-bar-fill" style="width:${overallPct}%"></div></div>
    </div>`;

  const cards = tables.map(t => {
    const st  = (t.state || 'PENDING').toUpperCase();
    const cls = st === 'DONE' ? 'bb-table-card done'
              : st === 'RUNNING' ? 'bb-table-card running'
              : st === 'FAILED'  ? 'bb-table-card failed'
              : 'bb-table-card';
    const icon = st === 'DONE' ? '✓' : st === 'RUNNING' ? '⟳' : st === 'FAILED' ? '✕' : '·';
    const rd  = t.rowsDone  || 0;
    const rt  = t.rowsTotal || 0;
    const pct = rt > 0 ? Math.min(100, Math.round(rd / rt * 100)) : (st === 'DONE' ? 100 : 0);
    const etaTxt = st === 'RUNNING' ? speedAndEta(rd, rt, t.startedAtMs) : '';

    // Progress bar (always shown for RUNNING/DONE; shows pct even when no rowsTotal)
    const showBar = st === 'RUNNING' || st === 'DONE';
    const bar = showBar
      ? `<div class="bb-bar"><div class="bb-bar-fill" style="width:${pct}%"></div></div>
         <div class="bb-card-pct">${pct}%</div>`
      : '';

    // Row count line — show whatever we have
    let rowLine = '';
    if (rt > 0) {
      rowLine = `${rd.toLocaleString()} / ${rt.toLocaleString()} rows`;
    } else if (rd > 0) {
      rowLine = `${rd.toLocaleString()} rows processed`;
    }
    const etaLine = etaTxt ? `<div class="bb-card-eta">${etaTxt}</div>` : '';

    return `<div class="${cls}">
      <div class="bb-card-name">${icon} ${esc(t.table)}</div>
      ${bar}
      ${rowLine ? `<div class="bb-card-rows">${rowLine}</div>` : ''}
      ${etaLine}
    </div>`;
  }).join('');

  const tableSection = tables.length
    ? `<div class="bb-table-grid-header">Tables &mdash; ${done}/${total} done</div>
       <div class="bb-table-grid">${cards}</div>`
    : `<div class="bb-table-grid-header bb-planning">Planning tables&hellip;</div>`;

  return `<tr class="job-tstates-row"><td colspan="9">
    <div class="bb-court">${lanes}</div>
    ${overall}
    ${tableSection}
  </td></tr>`;
}

async function cancelJob(jobId) {
  try {
    await api.post(`/api/jobs/${jobId}/cancel`, {});
    toast(`Cancel requested for job ${jobId}`, 'ok');
    await loadJobs();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteJob(jobId, jobName) {
  if (!await uiConfirm(`Delete job "${jobName}" (id ${jobId})? This cannot be undone.`, {danger: true, okText: 'Delete'})) return;
  try {
    await api.del(`/api/jobs/${jobId}`);
    toast(`Job "${jobName}" deleted`, 'ok');
    await loadJobs();
  } catch (e) { toast(e.message, 'err'); }
}

async function showJobSample(jobId) {
  const table = await uiPrompt('Table to sample?', 'customers');
  if (!table) return;
  try {
    const s = await api.get(`/api/jobs/${jobId}/sample?table=${encodeURIComponent(table)}&limit=5`);
    $('job-sample').classList.remove('empty');
    if (s.inPlace) {
      // In-place: source and target are the same table, so a side-by-side would just show identical rows.
      $('job-sample').innerHTML = `<b>${esc(s.message)}</b>`
        + `<div><h3>Masked result</h3>${rowsTable(s.columns, s.targetRows && s.targetRows.length ? s.targetRows : s.sourceRows)}</div>`;
    } else {
      $('job-sample').innerHTML = `<b>${esc(s.message)}</b><div class="compare-grid"><div><h3>Source (original)</h3>${rowsTable(s.columns, s.sourceRows)}</div><div><h3>Target (masked)</h3>${rowsTable(s.columns, s.targetRows)}</div></div>`;
    }
  } catch (e) { toast(e.message, 'err'); }
}

function rowsTable(columns, rows) {
  if (!rows || !rows.length) return '<div class="empty">No rows.</div>';
  return `<table><tr>${columns.map(c => `<th>${esc(c)}</th>`).join('')}</tr>` +
    rows.map(r => `<tr>${columns.map(c => `<td class="mono">${esc(r[c] ?? '')}</td>`).join('')}</tr>`).join('') + '</table>';
}

/* ---------- reservations ---------- */
async function reserve() {
  try {
    await api.post('/api/reservations/find-and-reserve', { dataSourceId: parseInt($('res-ds').value),
      table: $('res-table').value, criteria: $('res-criteria').value || null,
      count: parseInt($('res-count').value||'1'), reservedBy: $('res-by').value || 'anonymous',
      ttlHours: parseInt($('res-ttl').value||'24') });
    toast('Rows reserved', 'ok');
    await loadReservations();
    activatePageTab('reservations', 'res-list-card', true);
  } catch (e) { toast(e.message, 'err'); }
}

async function loadReservations() {
  await refreshShared();
  const rs = await api.get('/api/reservations');
  $('res-table-out').innerHTML = rs.length ? `<table><tr><th>ID</th><th>Table</th><th>Criteria</th><th>Keys</th><th>By</th><th>Status</th><th>Expires</th><th></th></tr>` +
    rs.map(r => { let keys = []; try { keys = JSON.parse(r.rowKeysJson); } catch {}
      return `<tr><td>${r.id}</td><td><b>${esc(r.tableName)}</b></td><td class="mono">${esc(r.criteria ?? '')}</td>
      <td class="mono" title="${esc(keys.join(', '))}">${keys.length} key(s): ${esc(keys.slice(0,5).join(', '))}${keys.length>5?'...':''}</td>
      <td>${esc(r.reservedBy)}</td><td>${statusPill(r.status)}</td>
      <td class="mono">${new Date(r.expiresAt).toLocaleString()}</td>
      <td>${r.status==='ACTIVE' ? `<button class="small ghost" onclick="releaseRes(${r.id})">Release</button>` : ''}</td></tr>`; }).join('') + '</table>'
    : '<div class="empty">No reservations.</div>';
}

async function releaseRes(id) {
  try { await api.post(`/api/reservations/${id}/release`); loadReservations(); }
  catch (e) { toast(e.message, 'err'); }
}

/* ---------- validation ---------- */
async function runValidation() {
  try {
    toast('Validating masked target.');
    const r = await api.post('/api/validation/run', { sourceId: $('val-src').value || null,
      targetId: parseInt($('val-tgt').value), policyId: $('val-policy').value ? parseInt($('val-policy').value) : null });
    toast('Validation ' + r.result, r.result === 'PASS' ? 'ok' : 'err');
    await loadValidation();
    activatePageTab('validation', 'val-reports-card', true);
  } catch (e) { toast(e.message, 'err'); }
}

let valRemedies = {};

async function loadValidation() {
  await refreshShared();
  const reps = await api.get('/api/validation/reports');
  $('val-table').innerHTML = reps.length ? reps.map(r => {
    let f = []; try { f = JSON.parse(r.findingsJson); } catch {}
    const fixable = r.result === 'FAIL' || r.result === 'WARN';
    return `<div class="report"><p class="sub">#${r.id} ${statusPill(r.result)} <span class="mono">${new Date(r.createdAt).toLocaleString()}</span>
      ${fixable ? `<button class="small" onclick="valExplain(${r.id})">✦ Explain &amp; fix</button>` : ''}</p>
      <table><tr><th>Severity</th><th>Check</th><th>Table</th><th>Column</th><th>Detail</th></tr>` +
      f.map(x => `<tr><td>${statusPill(x.severity)}</td><td>${esc(x.check)}</td><td>${esc(x.table ?? '')}</td><td>${esc(x.column ?? '')}</td><td>${esc(x.detail)}</td></tr>`).join('') +
      `</table><div class="val-remedies" id="val-remedies-${r.id}"></div></div>`; }).join('')
    : '<div class="empty">No reports yet.</div>';
}

async function valExplain(reportId) {
  const el = $('val-remedies-' + reportId); if (!el) return;
  el.innerHTML = '<div class="loading">Analyzing failures &amp; proposing fixes…</div>';
  try {
    const r = await api.post(`/api/validation/reports/${reportId}/diagnose`);
    valRenderRemedies(el, r);
  } catch (e) { el.innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

function valRenderRemedies(el, r) {
  const items = (r.remedies && r.remedies.length) ? r.remedies : [];
  valRemedies[r.reportId] = { policyId: r.policyId, items };
  if (!items.length) { el.innerHTML = '<div class="empty">No remediation suggestions — the findings may be informational.</div>'; return; }
  el.innerHTML = '<div class="val-rem-list">' + items.map((m, i) => `
    <div class="val-rem">
      <div class="val-rem-head">${statusPill(m.severity || 'FAIL')} <b>${esc(m.check || '')}</b> <code>${esc((m.table || '') + (m.column ? '.' + m.column : ''))}</code></div>
      <p class="tight"><b>Cause:</b> ${esc(m.cause || '')}</p>
      <p class="tight"><b>Fix:</b> ${esc(m.fix || '')}</p>
      ${m.suggestedFunction ? `<div class="val-apply">
        <span class="pill info">${esc(m.suggestedFunction)}${m.suggestedParam1 ? ' · ' + esc(m.suggestedParam1) : ''}${m.suggestedParam2 ? ' · ' + esc(m.suggestedParam2) : ''}</span>
        ${r.policyId ? `<button class="small" onclick="valApplyFix(${r.reportId}, ${i})">Apply fix</button>` : '<span class="sub">No policy linked — apply this in the policy manually.</span>'}
      </div>` : ''}
    </div>`).join('') + '</div>';
}

async function valApplyFix(reportId, i) {
  const ctx = valRemedies[reportId]; if (!ctx) return;
  const m = ctx.items[i]; if (!m) return;
  try {
    await api.post('/api/validation/apply-fix', {
      policyId: ctx.policyId, table: m.table, column: m.column,
      function: m.suggestedFunction, param1: m.suggestedParam1 || null, param2: m.suggestedParam2 || null
    });
    toast(`Applied ${m.suggestedFunction} to ${m.table}.${m.column} — re-run validation to confirm`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- TDM challenge center ---------- */
/* Top corporate test-data challenges (industry reports, 2025-26), each mapped to the ForgeTDM workflow that addresses it. */
const CHALLENGES = [
  { n: 1, title: 'Slow test data provisioning blocks releases',
    pain: 'Industry surveys attribute ~44% of testing delays to inadequate test data; teams in large banks wait days for a refreshed environment.',
    fix: 'Launch repeatable MASK_COPY / SUBSET_MASK jobs with async progress, or clone a VDB from a snapshot in seconds instead of copying full databases.',
    actions: [['Launch a provisioning job', 'jobs', 'job-launch-card'], ['Provision a VDB', 'virtualize', 'virt-snapshots-card']] },
  { n: 2, title: 'Sensitive data exposure in non-production',
    pain: 'Only ~4% of organizations report fully compliant dev/test environments, and 76% have had a sensitive-data incident in lower environments.',
    fix: 'Dual-signal PII discovery scans column names and sampled values, then routes every finding through review, masking-rule assignment, and approval.',
    actions: [['Scan for PII', 'discovery', 'disc-scan-card']] },
  { n: 3, title: 'Inconsistent masking across teams',
    pain: 'Without central governance each team masks differently, so the same customer becomes a different person in every environment and cross-system tests break.',
    fix: 'Central masking policies with deterministic, seed-driven functions: the same input always masks to the same output across jobs, teams, and runs.',
    actions: [['Manage policies', 'policies', 'pol-list-card'], ['Try functions live', 'studio', null]] },
  { n: 4, title: 'Proving GDPR / CCPA / HIPAA compliance',
    pain: 'Regulators and auditors expect evidence that production PII never reaches test environments - a screenshot of a masked row is not evidence.',
    fix: 'Validation runs leak, format, referential-integrity, and email-domain checks against the masked target, and every consequential action lands in an immutable audit trail.',
    actions: [['Run validation', 'validation', 'val-run-card'], ['Open audit trail', 'audit', null]] },
  { n: 5, title: 'Oversized environments and storage cost',
    pain: 'Full production copies are slow, expensive, and mostly unused - large databases now take hours to copy or anonymize for a test that touches 1% of rows.',
    fix: 'FK-aware subsetting plans a right-sized slice from a driver table with filters, row limits, and Q1/Q2 referential closure, dry-run before you execute.',
    actions: [['Plan a subset', 'subset', 'sub-define-card']] },
  { n: 6, title: 'Broken referential integrity after masking or subsetting',
    pain: 'Naive masking and slicing breaks foreign keys, which research flags as the single largest source of flaky test behavior.',
    fix: 'The ER traversal view shows parent/child paths and cycles, loads traverse parents first, and deterministic masking keeps joined values consistent across tables.',
    actions: [['Inspect ER traversal', 'discovery', 'disc-results-card']] },
  { n: 7, title: 'Missing edge-case and negative test data',
    pain: 'Production never contains tomorrow\'s scenarios: new products, boundary values, bad input, or rare combinations needed for negative testing.',
    fix: 'Blueprint-driven synthetic generation with a searchable generator catalog (finance, geo, identity, API shapes) and seeded reproducibility.',
    actions: [['Design synthetic data', 'synthetic', 'syn-design-card']] },
  { n: 8, title: 'Stale test data and environment drift',
    pain: 'Static datasets go obsolete quickly, failing to reflect recent production changes - tests pass against data that no longer exists.',
    fix: 'Refresh a VDB from a new dSource snapshot, or rewind it to a bookmark to restore a known test state in seconds.',
    actions: [['Refresh / rewind a VDB', 'virtualize', 'virt-vdb-card']] },
  { n: 9, title: 'Test data collisions between teams',
    pain: 'Shared mutable test data is a top cause of flaky suites: two teams mutate the same rows and both runs become unreliable.',
    fix: 'Find & Reserve locks matching rows for exclusive use with an owner and TTL, so each test cycle works on data nobody else can touch.',
    actions: [['Reserve test data', 'reservations', 'res-search-card']] },
  { n: 10, title: 'TDM not wired into CI/CD pipelines',
    pain: 'Legacy TDM assumes manual, waterfall-style requests; agile pipelines need data provisioned on demand, repeatably, per build.',
    fix: 'Every ForgeTDM action is a REST API call, and seeded determinism makes provisioned data identical run-to-run - call the same job spec from your pipeline.',
    actions: [['See job API in action', 'jobs', 'job-monitor-card'], ['Audit the evidence', 'audit', null]] }
];

function renderChallenges() {
  const grid = $('challenge-grid');
  if (!grid || grid.children.length) return;
  grid.innerHTML = CHALLENGES.map(c => `<div class="challenge-card">
    <div class="challenge-head"><span class="challenge-num">${c.n}</span><b>${esc(c.title)}</b></div>
    <p class="challenge-pain">${esc(c.pain)}</p>
    <p class="challenge-fix"><span class="pill good">ForgeTDM</span> ${esc(c.fix)}</p>
    <div class="challenge-actions">${c.actions.map(([label, page, tab]) =>
      `<button class="small" onclick='goTo(${js(page)}, ${tab ? js(tab) : 'null'})'>${esc(label)}</button>`).join('')}</div>
  </div>`).join('');
}

/* ---------- audit ---------- */
async function loadAudit() {
  const rows = await api.get('/api/audit?limit=200');
  $('audit-table').innerHTML = rows.length ? `<table><tr><th>When</th><th>Actor</th><th>Action</th><th>Detail</th></tr>` +
    rows.map(a => `<tr><td class="mono">${new Date(a.createdAt).toLocaleString()}</td><td>${esc(a.actor)}</td>
      <td>${pill(a.action,'info')}</td><td class="mono">${esc(a.detail ?? '')}</td></tr>`).join('') + '</table>'
    : '<div class="empty">Quiet so far.</div>';
}

/* ──────────────────────────────────────────────────────────────────────────
   DATASCOPE  (Extraction blueprints — per-table traversal, masking overrides)
   ────────────────────────────────────────────────────────────────────────── */

/* ---------- access control ---------- */
async function loadSecurity() {
  securityState = await api.get('/api/security/summary');
  securityState.users ||= [];
  securityState.groups ||= [];
  securityState.roles ||= [];
  renderSecurity();
}

function renderSecurity() {
  const roleOpts = roleOptions();
  if ($('sec-user-roles')) $('sec-user-roles').innerHTML = roleOpts;
  if ($('sec-group-roles')) $('sec-group-roles').innerHTML = roleOpts;
  if ($('sec-user-groups')) $('sec-user-groups').innerHTML = groupOptions();

  const adminUsers = securityState.users.filter(u => (u.roles || []).includes('ADMIN')).length;
  $('sec-overview').innerHTML = `
    <div class="security-tile"><span>Users</span><b>${securityState.users.length}</b></div>
    <div class="security-tile"><span>Groups</span><b>${securityState.groups.length}</b></div>
    <div class="security-tile"><span>Roles</span><b>${securityState.roles.length}</b></div>
    <div class="security-tile"><span>Admin users</span><b>${adminUsers}</b></div>`;

  renderSecurityUsers();
  renderSecurityGroups();
  renderSecurityRoles();
}

function renderSecurityUsers() {
  const el = $('sec-users');
  if (!el) return;
  el.innerHTML = securityState.users.length ? `<table><tr><th>User</th><th>Status</th><th>Roles</th><th>Groups</th><th>Reset / Update</th><th></th></tr>` +
    securityState.users.map(u => `<tr>
      <td><b>${esc(u.displayName || u.username)}</b><div class="mono">${esc(u.username)}</div></td>
      <td><label class="check"><input id="sec-u-active-${u.id}" type="checkbox" ${u.active ? 'checked' : ''}> Active</label></td>
      <td><select id="sec-u-roles-${u.id}" multiple size="4">${roleOptions(u.roles || [])}</select></td>
      <td><select id="sec-u-groups-${u.id}" multiple size="4">${groupOptions((u.groups || []).map(g => g.id))}</select></td>
      <td><input id="sec-u-display-${u.id}" value="${esc(u.displayName || '')}" placeholder="display name">
          <input id="sec-u-pass-${u.id}" type="password" placeholder="new password" style="margin-top:6px"></td>
      <td class="row-actions"><button class="small" onclick="updateSecurityUser(${u.id})">Save</button>
          <button class="small ghost danger" onclick="deleteSecurityUser(${u.id}, ${js(u.username)})">Delete</button></td>
    </tr>`).join('') + '</table>' : '<div class="empty">No users yet.</div>';
}

function renderSecurityGroups() {
  const el = $('sec-groups');
  if (!el) return;
  el.innerHTML = securityState.groups.length ? `<table><tr><th>Group</th><th>Description</th><th>Roles</th><th></th></tr>` +
    securityState.groups.map(g => `<tr>
      <td><input id="sec-g-name-${g.id}" value="${esc(g.name)}"></td>
      <td><input id="sec-g-desc-${g.id}" value="${esc(g.description || '')}"></td>
      <td><select id="sec-g-roles-${g.id}" multiple size="4">${roleOptions(g.roles || [])}</select></td>
      <td class="row-actions"><button class="small" onclick="updateSecurityGroup(${g.id})">Save</button>
          <button class="small ghost danger" onclick="deleteSecurityGroup(${g.id}, ${js(g.name)})">Delete</button></td>
    </tr>`).join('') + '</table>' : '<div class="empty">No groups yet.</div>';
}

function renderSecurityRoles() {
  const el = $('sec-roles');
  if (!el) return;
  el.innerHTML = securityState.roles.length ? `<table><tr><th>Role</th><th>Purpose</th><th>Permissions</th></tr>` +
    securityState.roles.map(r => `<tr>
      <td><b>${esc(r.label)}</b><div class="mono">${esc(r.name)}</div></td>
      <td>${esc(r.description)}</td>
      <td><div class="permission-list">${(r.permissions || []).map(p => `<code>${esc(p)}</code>`).join('')}</div></td>
    </tr>`).join('') + '</table>' : '<div class="empty">No roles returned.</div>';
}

function roleOptions(selected = []) {
  const sel = new Set(selected);
  return (securityState.roles || []).map(r => `<option value="${esc(r.name)}" ${sel.has(r.name) ? 'selected' : ''}>${esc(r.label)}</option>`).join('');
}

function groupOptions(selected = []) {
  const sel = new Set((selected || []).map(String));
  return (securityState.groups || []).map(g => `<option value="${g.id}" ${sel.has(String(g.id)) ? 'selected' : ''}>${esc(g.name)}</option>`).join('');
}

function selectedValues(id) {
  return [...($(id)?.selectedOptions || [])].map(o => o.value);
}

async function createSecurityUser() {
  try {
    await api.post('/api/security/users', {
      username: $('sec-user-name').value,
      displayName: $('sec-user-display').value,
      password: $('sec-user-password').value,
      active: true,
      roles: selectedValues('sec-user-roles'),
      groupIds: selectedValues('sec-user-groups').map(Number)
    });
    ['sec-user-name','sec-user-display','sec-user-password'].forEach(id => $(id).value = '');
    await loadSecurity();
    toast('User created', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function updateSecurityUser(id) {
  try {
    await api.put('/api/security/users/' + id, {
      displayName: $('sec-u-display-' + id).value,
      password: $('sec-u-pass-' + id).value || null,
      active: $('sec-u-active-' + id).checked,
      roles: selectedValues('sec-u-roles-' + id),
      groupIds: selectedValues('sec-u-groups-' + id).map(Number)
    });
    await loadSecurity();
    toast('User updated', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteSecurityUser(id, username) {
  if (!await uiConfirm('Delete user ' + username + '?', {danger: true, okText: 'Delete'})) return;
  try {
    await api.del('/api/security/users/' + id);
    await loadSecurity();
    toast('User deleted', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function createSecurityGroup() {
  try {
    await api.post('/api/security/groups', {
      name: $('sec-group-name').value,
      description: $('sec-group-desc').value,
      roles: selectedValues('sec-group-roles')
    });
    ['sec-group-name','sec-group-desc'].forEach(id => $(id).value = '');
    await loadSecurity();
    toast('Group created', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function updateSecurityGroup(id) {
  try {
    await api.put('/api/security/groups/' + id, {
      name: $('sec-g-name-' + id).value,
      description: $('sec-g-desc-' + id).value,
      roles: selectedValues('sec-g-roles-' + id)
    });
    await loadSecurity();
    toast('Group updated', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteSecurityGroup(id, name) {
  if (!await uiConfirm('Delete group ' + name + '?', {danger: true, okText: 'Delete'})) return;
  try {
    await api.del('/api/security/groups/' + id);
    await loadSecurity();
    toast('Group deleted', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function loadAccessDefs() {
  await refreshShared();
  fillDsSelects();
  renderAdList();
}

let adListPage = 1;
const AD_LIST_PAGE_SIZE = 25;

function adListGo(p) { adListPage = Math.max(1, p || 1); renderAdList(); }

function renderAdList() {
  const el = $('ad-list');
  if (!el) return;
  // keep the data-source filter in sync with registered sources (selection preserved)
  if ($('ad-filter-ds')) setOptionsPreserve('ad-filter-ds',
    '<option value="">All data sources</option>' + dataSources.map(d => `<option value="${d.id}">${esc(d.name)}</option>`).join(''));
  const countEl = $('ad-list-count');

  if (!datasetsList.length) {
    el.innerHTML = `<div class="empty ds-empty">
      <b>No DataScope blueprints yet.</b>
      <p>A blueprint is a reusable recipe for carving a referentially-intact, masked slice of a database:</p>
      <p>1 · Pick a <b>source</b> and a <b>driver table</b> (its rows define the subset) &nbsp;→&nbsp;
         2 · <b>Map tables</b> to the target &nbsp;→&nbsp;
         3 · Assign <b>masking</b> policies &nbsp;→&nbsp;
         4 · <b>Provision</b> — with PII-coverage and schema checks built in.</p>
      <p>Create your first blueprint using the form above.</p>
    </div>`;
    if (countEl) countEl.textContent = '';
    return;
  }

  const q = ($('ad-search')?.value || '').toLowerCase().trim();
  const fds = $('ad-filter-ds')?.value || '';
  const fst = $('ad-filter-status')?.value || '';
  const sort = $('ad-sort')?.value || 'name';

  let rows = datasetsList.filter(d => {
    if (fds && String(d.dataSourceId) !== fds) return false;
    if (fst === 'ready' && !d.driverTable) return false;
    if (fst === 'needs' && d.driverTable) return false;
    if (q) {
      const hay = `${d.name} ${d.description || ''} ${dataSourceName(d.dataSourceId)} ${d.schemaName || ''} ${d.driverTable || ''}`.toLowerCase();
      if (!hay.includes(q)) return false;
    }
    return true;
  });
  rows.sort((a, b) => sort === 'recent' ? (b.id - a.id) : String(a.name || '').localeCompare(String(b.name || '')));

  const total = rows.length;
  const pages = Math.max(1, Math.ceil(total / AD_LIST_PAGE_SIZE));
  if (adListPage > pages) adListPage = pages;
  const start = (adListPage - 1) * AD_LIST_PAGE_SIZE;
  const pageRows = rows.slice(start, start + AD_LIST_PAGE_SIZE);

  if (countEl) {
    countEl.textContent = total
      ? `${start + 1}–${Math.min(start + AD_LIST_PAGE_SIZE, total)} of ${total}` + (total !== datasetsList.length ? ` (filtered from ${datasetsList.length})` : '')
      : `0 of ${datasetsList.length}`;
  }
  if (!total) { el.innerHTML = '<div class="empty">No blueprints match your search.</div>'; return; }

  el.innerHTML = `<div class="ds-list-wrap"><table class="ds-list-table"><thead><tr>
      <th>Name</th><th>Source / schema</th><th>Driver</th><th>Closure</th><th>Status</th><th></th></tr></thead><tbody>` +
    pageRows.map(d => {
      const active = selectedAdId === d.id;
      const ready = d.driverTable ? '<span class="pill good small">Ready</span>' : '<span class="pill warn small">Needs driver</span>';
      return `<tr class="${active ? 'active' : ''}" onclick="selectAd(${d.id}, true)">
        <td><b>${esc(d.name)}</b>${d.description ? `<div class="ds-row-desc">${esc(d.description)}</div>` : ''}</td>
        <td class="mono dim">${esc(dataSourceName(d.dataSourceId))}${d.schemaName ? ' / ' + esc(d.schemaName) : ''}</td>
        <td class="mono">${d.driverTable ? esc(d.driverTable) : '<span class="dim">—</span>'}</td>
        <td class="dim">${d.globalQ1 ? 'Q1' : '·'}&nbsp;${d.globalQ2 ? 'Q2' : '·'}</td>
        <td>${ready}</td>
        <td class="nowrap"><button class="small" onclick="event.stopPropagation(); selectAd(${d.id}, true)">${active ? 'Editing' : 'Edit'}</button>
          <button class="small ghost danger" data-name="${esc(d.name)}" onclick="event.stopPropagation(); deleteAd(${d.id}, this.dataset.name)">Del</button></td>
      </tr>`;
    }).join('') + '</tbody></table></div>' + adPagerHtml(adListPage, pages);
}

function adPagerHtml(page, pages) {
  if (pages <= 1) return '';
  return `<div class="ds-pager">
    <button class="ghost small" ${page <= 1 ? 'disabled' : ''} onclick="adListGo(${page - 1})">&lsaquo; Prev</button>
    <span class="muted">Page ${page} of ${pages}</span>
    <button class="ghost small" ${page >= pages ? 'disabled' : ''} onclick="adListGo(${page + 1})">Next &rsaquo;</button>
  </div>`;
}

function renderDataScopeOverview() {
  const el = $('ad-workspace-summary');
  const def = currentAdDef();
  if (!el || !def) return;
  const included = adProfiles.filter(p => p.included);
  const mapped = included.filter(p => p.targetTableName).length;
  const sourceStatus = dataScopeSourceSummary(def, included);
  const policyStatus = dataScopePolicySummary(included);
  const target = def.targetDataSourceId
    ? `${dataSourceName(def.targetDataSourceId)}${def.targetSchemaName ? ' / ' + def.targetSchemaName : ''}`
    : 'target not selected';
  el.innerHTML = `
    <div class="ds-overview-tile">
      <span>Source</span><b>${esc(sourceStatus)}</b>
    </div>
    <div class="ds-overview-tile">
      <span>Driver</span><b>${def.driverTable ? `<code>${esc(def.driverTable)}</code>` : 'Not set'}</b>
    </div>
    <div class="ds-overview-tile">
      <span>Table Map</span><b>${included.length} included, ${mapped} custom target${mapped === 1 ? '' : 's'}</b>
    </div>
    <div class="ds-overview-tile">
      <span>Masking</span><b>${esc(policyStatus)}</b>
    </div>
    <div class="ds-overview-tile">
      <span>Target</span><b>${esc(target)}</b>
    </div>`;
}

async function createAccessDef() {
  const name = $('ad-name').value.trim();
  const dsId = parseInt($('ad-ds').value);
  if (!name) { toast('Name is required', 'err'); return; }
  if (!dsId) { toast('Data source is required', 'err'); return; }
  try {
    const def = await api.post('/api/datasets', {
      name, dataSourceId: dsId,
      schemaName: $('ad-schema').value || null,
      description: $('ad-desc').value.trim() || null,
      globalQ1: true, globalQ2: true
    });
    $('ad-name').value = ''; $('ad-desc').value = '';
    await refreshShared();
    renderAdList();
    toast(`Created "${name}"`, 'ok');
    await selectAd(def.id, true);   // open the new blueprint immediately, scrolled into view
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteAd(id, name) {
  if (!await uiConfirm(`Delete DataScope blueprint "${name}"?`, {danger: true, okText: 'Delete'})) return;
  try {
    await api.del(`/api/datasets/${id}`);
    if (selectedAdId === id) {
      selectedAdId = null;
      $('ad-detail').style.display = 'none';
      for (const key of ['profiles', 'custom', 'refint', 'travmap', 'overrides', 'provision']) {
        const gate = $(`ds-${key}-gate`), content = $(`ds-${key}-content`);
        if (gate) gate.style.display = '';
        if (content) content.style.display = 'none';
      }
    }
    await refreshShared(); renderAdList();
    toast(`Deleted "${name}"`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function selectAd(id, focusDetail = false) {
  if (adDirty && selectedAdId && selectedAdId !== id) {
    const ok = await uiConfirm(
      'You have unsaved DataScope edits (table profiles or custom keys) that will be discarded if you switch blueprints. Continue?',
      { title: 'Unsaved changes', danger: true, okText: 'Discard changes' });
    if (!ok) return;
  }
  adDirty = false;
  selectedAdId = id;
  const def = datasetsList.find(d => d.id === id);
  if (!def) return;

  // Reveal the detail panel inside the Blueprint tab
  $('ad-detail').style.display = '';
  $('ad-detail-title').textContent = `Editing: ${def.name}`;
  $('ad-detail-badge').textContent = dataSources.find(s => s.id === def.dataSourceId)?.name || '';
  if ($('ad-detail-meta')) {
    $('ad-detail-meta').textContent = `${dataSourceName(def.dataSourceId)}${def.schemaName ? ' / ' + def.schemaName : ''}`;
  }
  $('ad-filter').value = def.driverFilter ?? '';
  $('ad-maxrows').value = '';
  $('ad-q1').checked = def.globalQ1;
  $('ad-q2').checked = def.globalQ2;
  renderAdList();

  // Unlock gated tabs (Table Profiles, Custom Data, Ref Integrity, Traversal Map, Masking Overrides)
  for (const key of ['profiles', 'custom', 'refint', 'travmap', 'overrides', 'provision']) {
    const gate = $(`ds-${key}-gate`), content = $(`ds-${key}-content`);
    if (gate) gate.style.display = 'none';
    if (content) content.style.display = '';
  }

  // Load tables for this source+schema (populates driver, ov-table, prof-table selects).
  // Runs concurrently with the config-DB fetches below: it hits the live source database,
  // so a slow or unreachable source must not block the blueprint/provision tabs from rendering.
  const tablesReady = loadAdTables();   // handles its own errors (toast)

  // Load all tab data in parallel
  [adProfiles, adOverrides, adCustomPks, adUserRels, adTraversalRules] = await Promise.all([
    api.get(`/api/datasets/${id}/profiles`),
    api.get(`/api/datasets/${id}/overrides`),
    api.get(`/api/datasets/${id}/custom-pks`),
    api.get(`/api/datasets/${id}/user-rels`),
    api.get(`/api/datasets/${id}/traversal-rules`)
  ]);
  renderAdProfiles();          // also calls updateOverrideTableSelect + updateProfiledTableSelects
  renderAdOverrides();
  renderCustomPks();
  renderUserRels();
  renderDataScopeOverview();
  renderDataScopeProvision();
  loadAdVersions();
  loadAdInsights();   // PII coverage + schema drift — async, never blocks the page
  activatePageTab('datasets', 'ds-tab-blueprint', true);
  await tablesReady;
  if (focusDetail) {
    requestAnimationFrame(() => $('ad-detail')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }
}

async function loadAdSchemas() {
  const dsId = $('ad-ds')?.value;
  if (!dsId) return;
  await fillSchemaSelect(dsId, 'ad-schema', 'Optional schema');
}

async function loadAdTables() {
  const def = selectedAdId ? datasetsList.find(d => d.id === selectedAdId) : null;
  if (!def) return;
  try {
    const schema = def.schemaName ?? '';
    // Listing tables opens a fresh connection to the live source DB — cache per source+schema
    // (same tableCache the Subset page uses) so re-selecting a blueprint doesn't pay that cost again.
    const key = `${def.dataSourceId}|${schema}`;
    if (!tableCache[key]) {
      const rows = await api.get(`/api/datasources/${def.dataSourceId}/tables${schema ? '?schema=' + encodeURIComponent(schema) : ''}`);
      tableCache[key] = rows.map(r => r.table);
    }
    adTables = tableCache[key];
    const tableOpts = '<option value="">Select table</option>' +
      adTables.map(t => `<option value="${esc(t)}">${esc(t)}</option>`).join('');
    setOptionsPreserve('ad-driver', '<option value="">Select driver table</option>' +
      adTables.map(t => `<option value="${esc(t)}"${def.driverTable === t ? ' selected' : ''}>${esc(t)}</option>`).join(''));
    setOptionsPreserve('ad-prof-table', tableOpts);
    // ad-pk-table / ad-rel-parent / ad-rel-child are driven from adProfiles, not all schema tables
    updateOverrideTableSelect();
  } catch (e) { toast('Could not load tables: ' + e.message, 'err'); }
}

async function saveAdDriver() {
  if (!selectedAdId) return;
  const def = datasetsList.find(d => d.id === selectedAdId);
  if (!def) return;
  try {
    const updated = await api.put(`/api/datasets/${selectedAdId}`, {
      ...def,
      driverTable: $('ad-driver').value || null,
      driverFilter: $('ad-filter').value.trim() || null,
      globalQ1: $('ad-q1').checked,
      globalQ2: $('ad-q2').checked
    });
    await refreshShared();
    renderAdList();
    // Update local copy
    const idx = datasetsList.findIndex(d => d.id === selectedAdId);
    if (idx >= 0) datasetsList[idx] = updated;
    if ($('ad-detail-meta')) {
      $('ad-detail-meta').textContent = `${dataSourceName(updated.dataSourceId)}${updated.schemaName ? ' / ' + updated.schemaName : ''}`;
    }
    renderDataScopeOverview();
    renderDataScopeProvision();
    toast('Saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

// ── Table Profiles ──────────────────────────────────────────────────────────

function renderAdProfiles() {
  const el = $('ad-profiles-grid');
  if (!el) return;
  if (!adProfiles.length) {
    el.innerHTML = '<div class="empty">No table profiles. Add tables using the selector above.</div>';
    return;
  }
  el.innerHTML = `<div class="ad-profiles-table">
    <div class="ad-prof-header">
      <span title="Table name from the schema">Table</span>
      <span title="Target table loaded by provisioning. Defaults to the source table name.">Target table</span>
      <span title="Uncheck to completely exclude this table from the extraction">Included</span>
      <span title="Additional SQL WHERE clause applied after FK closure — e.g. status='A'">Filter (WHERE)</span>
      <span title="Inherit: use global Q1/Q2. Follow Parent: rows must join to a parent PK already selected. Independent: seed from own Filter, ignore FK path.">Strategy</span>
      <span title="Pull parent rows referenced by this table's FK? Global = use the Pull-parents checkbox above. Yes/No = force for this table. (Q1)">Pull parents</span>
      <span title="Pull child rows that reference this table via FK? Global = use the Pull-children checkbox above. Yes/No = force for this table. (Q2)">Pull children</span>
      <span title="Optional free-text note for documentation purposes">Note</span>
      <span></span>
    </div>` +
    adProfiles.map((p, idx) => `
    <div class="ad-prof-row" data-idx="${idx}">
      <span class="prof-table-name">${esc(p.tableName)}</span>
      <span class="prof-table-name">${esc(p.targetTableName || p.tableName)}</span>
      <span><label class="check tight"><input type="checkbox" ${p.included ? 'checked' : ''}
        onchange="adProfileChanged(${idx}, 'included', this.checked)"> Include</label></span>
      <span><input class="prof-filter" value="${esc(p.filterExpr ?? '')}"
        placeholder="col='val'"
        onchange="adProfileChanged(${idx}, 'filterExpr', this.value)"></span>
      <span><select onchange="adProfileChanged(${idx}, 'referentialStrategy', this.value)">
        <option value="INHERIT"${p.referentialStrategy === 'INHERIT' ? ' selected' : ''}>Inherit global</option>
        <option value="FOLLOW_PARENT"${p.referentialStrategy === 'FOLLOW_PARENT' ? ' selected' : ''}>Follow parent FK</option>
        <option value="INDEPENDENT"${p.referentialStrategy === 'INDEPENDENT' ? ' selected' : ''}>Independent filter</option>
      </select></span>
      <span><select onchange="adProfileChanged(${idx}, 'q1Override', this.value)">
        <option value=""${p.q1Override === null || p.q1Override === undefined ? ' selected' : ''}>Global</option>
        <option value="true"${p.q1Override === true ? ' selected' : ''}>Yes</option>
        <option value="false"${p.q1Override === false ? ' selected' : ''}>No</option>
      </select></span>
      <span><select onchange="adProfileChanged(${idx}, 'q2Override', this.value)">
        <option value=""${p.q2Override === null || p.q2Override === undefined ? ' selected' : ''}>Global</option>
        <option value="true"${p.q2Override === true ? ' selected' : ''}>Yes</option>
        <option value="false"${p.q2Override === false ? ' selected' : ''}>No</option>
      </select></span>
      <span><input value="${esc(p.note ?? '')}" placeholder="optional note"
        onchange="adProfileChanged(${idx}, 'note', this.value)"></span>
      <span class="profile-actions">
        <button class="small ghost" onclick="openTableMap()">Map</button>
        <button class="small danger" onclick="removeAdProfile(${idx})">X</button>
      </span>
    </div>`).join('') + '</div>';
  updateOverrideTableSelect();
  updateProfiledTableSelects();
  renderDataScopeOverview();
}

function adProfileChanged(idx, field, value) {
  adDirty = true;
  if (field === 'included') { adProfiles[idx][field] = value; renderDataScopeProvision(); return; }
  if (field === 'q1Override' || field === 'q2Override') {
    adProfiles[idx][field] = value === '' ? null : value === 'true';
    renderDataScopeProvision();
    return;
  }
  adProfiles[idx][field] = value || null;
  renderDataScopeProvision();
}

function addAdProfile() {
  const table = $('ad-prof-table').value;
  if (!table) { toast('Select a table first', 'err'); return; }
  if (adProfiles.some(p => p.tableName === table)) { toast(`${table} already has a profile`, 'err'); return; }
  adProfiles.push(newAdProfile(table));
  adDirty = true;
  renderAdProfiles();
  openTableMap();
}

async function addAllAdProfiles() {
  await loadAdTables();
  if (!adTables.length) { toast('Load tables first (click Refresh)', 'err'); return; }
  let added = 0;
  const existing = new Set(adProfiles.map(p => String(p.tableName || '').toLowerCase()));
  for (const t of adTables) {
    const key = String(t || '').toLowerCase();
    if (key && !existing.has(key)) {
      adProfiles.push(newAdProfile(t));
      existing.add(key);
      added++;
    }
  }
  if (!added) { toast('All tables are already in the profile list', 'err'); return; }
  renderAdProfiles();
  toast(`Added ${added} table${added !== 1 ? 's' : ''} — Save All Profiles to persist`, 'ok');
  await openTableMap();
}

// Keep override table dropdown in sync with currently-included profiles
function updateOverrideTableSelect() {
  const def = selectedAdId ? datasetsList.find(d => d.id === selectedAdId) : null;
  const driver = def?.driverTable;
  const included = adProfiles.filter(p => p.included).map(p => p.tableName);
  const tables = driver && !included.includes(driver) ? [driver, ...included] : included;
  setOptionsPreserve('ad-ov-table', '<option value="">Select table</option>' +
    tables.map(t => `<option value="${esc(t)}">${esc(t)}</option>`).join(''));
}

// Keep custom-PK and custom-relationship dropdowns scoped to profiled tables + driver table
function updateProfiledTableSelects() {
  const def = selectedAdId ? datasetsList.find(d => d.id === selectedAdId) : null;
  const driver = def?.driverTable;
  const profiled = adProfiles.map(p => p.tableName);
  // Include driver even if not yet in adProfiles
  const tables = driver && !profiled.includes(driver) ? [driver, ...profiled] : profiled;
  const opts = '<option value="">Select table</option>' +
    tables.map(t => `<option value="${esc(t)}">${esc(t)}</option>`).join('');
  setOptionsPreserve('ad-pk-table', opts);
  setOptionsPreserve('ad-rel-parent', opts);
  setOptionsPreserve('ad-rel-child', opts);
}

function removeAdProfile(idx) {
  adProfiles.splice(idx, 1);
  renderAdProfiles();
  renderDataScopeProvision();
}

async function saveAdProfiles() {
  if (!selectedAdId) return;
  try {
    adProfiles = await api.put(`/api/datasets/${selectedAdId}/profiles`, adProfiles);
    adDirty = false;
    renderAdProfiles();          // also calls updateOverrideTableSelect + updateProfiledTableSelects
    renderDataScopeProvision();
    loadAdInsights();
    toast('Table profiles saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

// ── Custom PKs ───────────────────────────────────────────────────────────────

// -- Optim-style table/column maps -----------------------------------------

function currentAdDef() {
  return selectedAdId ? datasetsList.find(d => d.id === selectedAdId) : null;
}

function newAdProfile(tableName, sourceDsId = null, sourceSchema = null, policyId = null) {
  return {
    sourceDataSourceId: sourceDsId ? parseInt(sourceDsId) : null,
    sourceSchemaName: sourceSchema || null,
    tableName,
    targetTableName: null,
    policyId: policyId ? parseInt(policyId) : null,
    included: true,
    filterExpr: null,
    rowLimit: null,
    referentialStrategy: 'INHERIT',
    q1Override: null,
    q2Override: null,
    note: null
  };
}

function sourceDataSourcesForTableMap() {
  const allowed = dataSources.filter(d => ['SOURCE', 'BOTH'].includes(String(d.role || '').toUpperCase()));
  return allowed.length ? allowed : dataSources;
}

function profileSourceDataSourceId(profile, def = currentAdDef()) {
  return profile?.sourceDataSourceId || def?.dataSourceId || null;
}

function profileSourceSchemaName(profile, def = currentAdDef()) {
  if (profile?.sourceSchemaName) return profile.sourceSchemaName;
  const sourceDs = profileSourceDataSourceId(profile, def);
  return String(sourceDs || '') === String(def?.dataSourceId || '') ? (def?.schemaName || '') : '';
}

function profilePolicyId(profile) {
  return profile?.policyId ? parseInt(profile.policyId) : null;
}

function tableMapSourceContextLabel(profile, def = currentAdDef()) {
  const dsId = profileSourceDataSourceId(profile, def);
  const schema = profileSourceSchemaName(profile, def);
  return `${dataSourceName(dsId)}${schema ? ' / ' + schema : ''}`;
}

function closeTableMap() {
  $('table-map-modal').style.display = 'none';
  tableMapState = { targetTables: [], addSourceTables: [] };
}

function closeColumnMap() {
  $('column-map-modal').style.display = 'none';
  columnMapState = { profileIdx: null, sourceColumns: [], targetColumns: [], rows: [], policyRules: [] };
}

async function openTableMap() {
  const def = currentAdDef();
  if (!def) return;
  tableMapState = { targetTables: [], addSourceTables: [] };
  $('tm-subtitle').textContent = `Map ${adProfiles.length} profiled table${adProfiles.length !== 1 ? 's' : ''} to target tables.`;
  const sourceOptions = '<option value="">Select source data source</option>' +
    sourceDataSourcesForTableMap().map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.role)})</option>`).join('');
  setOptionsPreserve('tm-add-source-ds', sourceOptions);
  if (def.dataSourceId && $('tm-add-source-ds')) $('tm-add-source-ds').value = String(def.dataSourceId);
  setOptionsPreserve('tm-target-ds', '<option value="">Select target data source</option>' +
    dataSources.map(d => `<option value="${d.id}">${esc(d.name)} (${esc(d.role)})</option>`).join(''));
  const targetDs = def.targetDataSourceId || def.dataSourceId;
  if (targetDs) $('tm-target-ds').value = String(targetDs);
  $('table-map-modal').style.display = 'flex';
  await tableMapAddSourceChanged();
  await tableMapTargetChanged();
}

async function tableMapAddSourceChanged() {
  const dsId = $('tm-add-source-ds')?.value;
  if (!dsId) {
    setOptionsPreserve('tm-add-source-schema', '<option value="">Default</option>');
    setOptionsPreserve('tm-add-source-table', '<option value="">Select table</option>');
    tableMapState.addSourceTables = [];
    return;
  }
  await fillSchemaSelect(dsId, 'tm-add-source-schema', 'Default');
  const def = currentAdDef();
  if (String(dsId) === String(def?.dataSourceId || '') && def?.schemaName &&
      [...$('tm-add-source-schema').options].some(o => o.value === def.schemaName)) {
    $('tm-add-source-schema').value = def.schemaName;
  }
  await loadTableMapAddTables();
}

async function loadTableMapAddTables() {
  const dsId = $('tm-add-source-ds')?.value;
  if (!dsId) return;
  const schema = $('tm-add-source-schema')?.value || '';
  setOptionsPreserve('tm-add-source-table', '<option value="">Loading tables...</option>');
  try {
    const rows = await api.get(`/api/datasources/${dsId}/tables${schema ? '?schema=' + encodeURIComponent(schema) : ''}`);
    tableMapState.addSourceTables = rows.map(r => r.table);
    setOptionsPreserve('tm-add-source-table', '<option value="">Select table</option>' +
      tableMapState.addSourceTables.map(t => `<option value="${esc(t)}">${esc(t)}</option>`).join(''));
  } catch (e) {
    tableMapState.addSourceTables = [];
    setOptionsPreserve('tm-add-source-table', '<option value="">Could not load tables</option>');
    toast(e.message, 'err');
  }
}

function addTableMapSourceRow() {
  const def = currentAdDef();
  const dsId = parseInt($('tm-add-source-ds')?.value || '0');
  const schema = $('tm-add-source-schema')?.value || '';
  const table = $('tm-add-source-table')?.value || '';
  if (!def || !dsId || !table) { toast('Choose a source DB and source table first', 'err'); return; }
  if (adProfiles.some(p => String(p.tableName || '').toLowerCase() === table.toLowerCase())) {
    toast(`${table} already has a profile. A table name can only appear once in this DataScope map.`, 'err');
    return;
  }
  const sourceOverride = String(dsId) === String(def.dataSourceId || '') ? null : dsId;
  const schemaOverride = schema && !(String(dsId) === String(def.dataSourceId || '') && sameSchemaName(schema, def.schemaName))
    ? schema : null;
  adProfiles.push(newAdProfile(table, sourceOverride, schemaOverride, null));
  $('tm-add-source-table').value = '';
  renderAdProfiles();
  renderTableMapGrid();
  renderDataScopeProvision();
  toast(`Added ${table} from ${dataSourceName(dsId)} - save the table map to persist`, 'ok');
}

async function tableMapTargetChanged() {
  const dsId = $('tm-target-ds')?.value;
  if (!dsId) {
    setOptionsPreserve('tm-target-schema', '<option value="">Default</option>');
    tableMapState.targetTables = [];
    renderTableMapGrid();
    return;
  }
  await fillSchemaSelect(dsId, 'tm-target-schema', 'Default');
  const def = currentAdDef();
  if (def?.targetSchemaName && [...$('tm-target-schema').options].some(o => o.value === def.targetSchemaName)) {
    $('tm-target-schema').value = def.targetSchemaName;
  }
  await loadTableMapTargetTables();
}

async function loadTableMapTargetTables() {
  const dsId = $('tm-target-ds')?.value;
  if (!dsId) {
    tableMapState.targetTables = [];
    renderTableMapGrid();
    return;
  }
  const schema = $('tm-target-schema')?.value || '';
  $('tm-grid').innerHTML = '<div class="loading">Loading target tables...</div>';
  try {
    const rows = await api.get(`/api/datasources/${dsId}/tables${schema ? '?schema=' + encodeURIComponent(schema) : ''}`);
    tableMapState.targetTables = rows.map(r => r.table);
    renderTableMapGrid();
  } catch (e) {
    tableMapState.targetTables = [];
    $('tm-grid').innerHTML = `<div class="error">${esc(e.message)}</div>`;
    toast(e.message, 'err');
  }
}

function renderTableMapGrid() {
  const el = $('tm-grid');
  if (!el) return;
  if (!adProfiles.length) {
    el.innerHTML = '<div class="empty">No table profiles. Add a source table above.</div>';
    return;
  }
  const def = currentAdDef();
  const targetDsSelected = !!$('tm-target-ds')?.value;
  el.innerHTML = `<div class="table-map-table">
    <div class="table-map-head">
      <span>Source DB</span><span>Source schema</span><span>Source table</span><span>Target table</span>
      <span>Row limit</span><span>Policy</span><span>Status</span><span>Column map</span>
    </div>` + adProfiles.map((profile, idx) => {
      const targetTable = profile.targetTableName || profile.tableName;
      const duplicate = tableMapTargetUsedBy(targetTable, idx);
      const sourceDs = profileSourceDataSourceId(profile, def);
      const sourceSchema = profileSourceSchemaName(profile, def);
      const customSource = String(sourceDs || '') !== String(def?.dataSourceId || '') ||
        !sameSchemaName(sourceSchema, def?.schemaName || '');
      const status = [
        duplicate ? pill('Duplicate target', 'warn') : null,
        customSource ? pill('Source override', 'info') : pill('Default source', 'dim'),
        profile.targetTableName ? pill('Custom target', 'info') : pill('Same target name', 'dim')
      ].filter(Boolean).join(' ');
      return `<div class="table-map-row">
        <span><select class="tm-source-ds"
          onchange="tableMapRowSourceChanged(${idx}, this.value)">${tableMapSourceOptions(profile, def)}</select></span>
        <span><input class="tm-source-schema" value="${esc(sourceSchema)}"
          placeholder="default" onchange="tableMapRowSourceSchemaChanged(${idx}, this.value)"></span>
        <span><input class="tm-source-table" value="${esc(profile.tableName || '')}"
          placeholder="source table" onchange="tableMapRowSourceTableChanged(${idx}, this.value)"></span>
        <span><select class="tm-target-select" ${targetDsSelected ? '' : 'disabled'}
          onchange="tableMapRowTargetChanged(${idx}, this.value)">${tableMapTargetOptions(profile, idx)}</select></span>
        <span><input class="tm-row-limit" type="number" min="0" value="${esc(profile.rowLimit ?? '')}"
          placeholder="all rows" onchange="tableMapRowLimitChanged(${idx}, this.value)"></span>
        <span><select class="tm-policy-select"
          onchange="tableMapRowPolicyChanged(${idx}, this.value)">${tableMapPolicyOptions(profile)}</select></span>
        <span>${status}</span>
        <span><button class="small ghost" ${targetDsSelected ? '' : 'disabled'}
          onclick="openColumnMapFromTableMap(${idx})">Column Map</button></span>
      </div>`;
    }).join('') + '</div>';
}

function tableMapSourceOptions(profile, def = currentAdDef()) {
  const selected = profileSourceDataSourceId(profile, def);
  return sourceDataSourcesForTableMap().map(d =>
    `<option value="${d.id}"${String(d.id) === String(selected) ? ' selected' : ''}>${esc(d.name)} (${esc(d.role)})</option>`
  ).join('');
}

function tableMapPolicyOptions(profile) {
  return policyOptionsForProfile(profile);
}

function policyMatchesSource(policy, dsId, schema) {
  if (!policy?.dataSourceId) return true;
  if (String(policy.dataSourceId) !== String(dsId || '')) return false;
  return !policy.schemaName || !schema || sameSchemaName(policy.schemaName, schema);
}

function policyLabelForProfile(policy, profile, def = currentAdDef()) {
  const dsId = profileSourceDataSourceId(profile, def);
  const schema = profileSourceSchemaName(profile, def);
  const matches = policyMatchesSource(policy, dsId, schema);
  const scope = policy.dataSourceId
    ? ` - ${dataSourceName(policy.dataSourceId)}${policy.schemaName ? ' / ' + policy.schemaName : ''}`
    : '';
  return `${policy.name}${scope}${matches ? '' : ' (other source)'}`;
}

function policyOptionsForProfile(profile) {
  const def = currentAdDef();
  const selected = profilePolicyId(profile);
  const dsId = profileSourceDataSourceId(profile, def);
  const schema = profileSourceSchemaName(profile, def);
  const sorted = [...policiesList].sort((a, b) => {
    const ma = policyMatchesSource(a, dsId, schema) ? 1 : 0;
    const mb = policyMatchesSource(b, dsId, schema) ? 1 : 0;
    if (ma !== mb) return mb - ma;
    return String(a.name || '').localeCompare(String(b.name || ''));
  });
  return '<option value="">No masking - copy as-is</option>' +
    sorted.map(p => `<option value="${p.id}"${String(p.id) === String(selected || '') ? ' selected' : ''}>${esc(policyLabelForProfile(p, profile, def))}</option>`).join('');
}

function tableMapTargetValue(profile) {
  return profile?.targetTableName || profile?.tableName || '';
}

function tableMapTargetKey(name) {
  return String(name || '').toLowerCase();
}

function tableMapTargetUsedBy(targetTable, skipIdx) {
  const key = tableMapTargetKey(targetTable);
  if (!key) return null;
  return adProfiles.find((profile, idx) =>
    idx !== skipIdx && tableMapTargetKey(tableMapTargetValue(profile)) === key) || null;
}

function tableMapTargetOptions(profile, idx) {
  const selected = profile.targetTableName || profile.tableName;
  const usedByOtherRows = new Set(adProfiles
    .filter((_, profileIdx) => profileIdx !== idx)
    .map(tableMapTargetValue)
    .filter(Boolean)
    .map(tableMapTargetKey));
  const tables = [...tableMapState.targetTables];
  if (selected && !tables.some(t => t === selected)) tables.unshift(selected);
  return '<option value="">Select table</option>' + tables.map(t => {
    const selectedAttr = t === selected ? ' selected' : '';
    const disabledAttr = t !== selected && usedByOtherRows.has(tableMapTargetKey(t)) ? ' disabled' : '';
    const label = disabledAttr ? `${t} (used)` : t;
    return `<option value="${esc(t)}"${selectedAttr}${disabledAttr}>${esc(label)}</option>`;
  }).join('');
}

function tableMapRowSourceChanged(idx, value) {
  const def = currentAdDef();
  const profile = adProfiles[idx];
  if (!def || !profile) return;
  const dsId = parseInt(value || '0');
  profile.sourceDataSourceId = dsId && String(dsId) !== String(def.dataSourceId || '') ? dsId : null;
  if (!profile.sourceDataSourceId && sameSchemaName(profile.sourceSchemaName, def.schemaName)) {
    profile.sourceSchemaName = null;
  }
  const selectedPolicy = policiesList.find(p => String(p.id) === String(profile.policyId || ''));
  if (selectedPolicy && selectedPolicy.dataSourceId &&
      !policyMatchesSource(selectedPolicy, profileSourceDataSourceId(profile, def), profileSourceSchemaName(profile, def))) {
    profile.policyId = null;
  }
  renderAdProfiles();
  renderTableMapGrid();
  renderDataScopeProvision();
}

function tableMapRowSourceSchemaChanged(idx, value) {
  const def = currentAdDef();
  const profile = adProfiles[idx];
  if (!def || !profile) return;
  const schema = value && value.trim() ? value.trim() : '';
  const sourceDs = profileSourceDataSourceId(profile, def);
  profile.sourceSchemaName = schema && !(String(sourceDs) === String(def.dataSourceId || '') && sameSchemaName(schema, def.schemaName))
    ? schema : null;
  const selectedPolicy = policiesList.find(p => String(p.id) === String(profile.policyId || ''));
  if (selectedPolicy && selectedPolicy.dataSourceId &&
      !policyMatchesSource(selectedPolicy, profileSourceDataSourceId(profile, def), profileSourceSchemaName(profile, def))) {
    profile.policyId = null;
  }
  renderTableMapGrid();
  renderDataScopeProvision();
}

function tableMapRowSourceTableChanged(idx, value) {
  const profile = adProfiles[idx];
  if (!profile) return;
  const table = value && value.trim() ? value.trim() : '';
  if (!table) { renderTableMapGrid(); return; }
  const duplicate = adProfiles.find((p, pIdx) =>
    pIdx !== idx && String(p.tableName || '').toLowerCase() === table.toLowerCase());
  if (duplicate) {
    toast(`${table} already has a profile. A table name can only appear once in this DataScope map.`, 'err');
    renderTableMapGrid();
    return;
  }
  const oldTable = profile.tableName;
  profile.tableName = table;
  if (!profile.targetTableName || sameSchemaName(profile.targetTableName, oldTable)) profile.targetTableName = null;
  renderAdProfiles();
  renderTableMapGrid();
  renderDataScopeProvision();
}

function tableMapRowTargetChanged(idx, value) {
  const profile = adProfiles[idx];
  if (!profile) return;
  const usedBy = tableMapTargetUsedBy(value, idx);
  if (usedBy) {
    toast(`Target table ${value} is already mapped to ${usedBy.tableName}`, 'err');
    renderTableMapGrid();
    return;
  }
  profile.targetTableName = value && value !== profile.tableName ? value : null;
  renderAdProfiles();
  renderTableMapGrid();
}

function tableMapRowPolicyChanged(idx, value) {
  const profile = adProfiles[idx];
  if (!profile) return;
  const policyId = value ? parseInt(value) : null;
  profile.policyId = policyId || null;
  renderTableMapGrid();
  renderDataScopeProvision();
}

function tableMapRowLimitChanged(idx, value) {
  const profile = adProfiles[idx];
  if (!profile) return;
  const limit = parseInt(value || '0');
  profile.rowLimit = limit > 0 ? limit : null;
  renderDataScopeProvision();
}

async function saveTableMapContext() {
  const def = currentAdDef();
  const dsId = parseInt($('tm-target-ds')?.value || '0');
  if (!def || !dsId) return;
  const updated = await api.put(`/api/datasets/${selectedAdId}`, {
    ...def,
    targetDataSourceId: dsId,
    targetSchemaName: $('tm-target-schema')?.value || ''
  });
  const idx = datasetsList.findIndex(d => d.id === selectedAdId);
  if (idx >= 0) datasetsList[idx] = updated;
}

async function persistTableMap(showToast = false) {
  if (!selectedAdId) return false;
  if (!adProfiles.length) { toast('Add table profiles first', 'err'); return false; }
  if (!parseInt($('tm-target-ds')?.value || '0')) { toast('Select a target data source', 'err'); return false; }
  let missing = null;
  const rows = [...document.querySelectorAll('#tm-grid .table-map-row')];
  const staged = rows.map((row, idx) => ({
    idx,
    profile: adProfiles[idx],
    sourceDs: parseInt(row.querySelector('.tm-source-ds')?.value || '0'),
    sourceSchema: row.querySelector('.tm-source-schema')?.value?.trim() || '',
    sourceTable: row.querySelector('.tm-source-table')?.value?.trim() || '',
    targetTable: row.querySelector('.tm-target-select')?.value || '',
    rowLimit: parseInt(row.querySelector('.tm-row-limit')?.value || '0'),
    policyId: row.querySelector('.tm-policy-select')?.value || ''
  })).filter(item => item.profile);

  staged.forEach(item => {
    if (!item.sourceDs && !missing) missing = item.profile.tableName || 'a source row';
    if (!item.sourceTable && !missing) missing = item.profile.tableName || 'a source row';
    if (!item.targetTable && !missing) missing = item.sourceTable || item.profile.tableName;
  });
  if (missing) { toast(`Complete source and target mapping for ${missing}`, 'err'); return false; }

  const tableNames = new Map();
  for (const item of staged) {
    const key = item.sourceTable.toLowerCase();
    const prior = tableNames.get(key);
    if (prior) {
      toast(`${item.sourceTable} is already in this DataScope map. A table name can only appear once.`, 'err');
      renderTableMapGrid();
      return false;
    }
    tableNames.set(key, item);
  }

  const seen = new Map();
  for (const item of staged) {
    const key = tableMapTargetKey(item.targetTable);
    const prior = seen.get(key);
    if (prior) {
      toast(`Target table ${item.targetTable} is already mapped to ${prior.profile.tableName}`, 'err');
      renderTableMapGrid();
      return false;
    }
    seen.set(key, item);
  }

  const def = currentAdDef();
  let tableNameChanged = false;
  staged.forEach(({ profile, sourceDs, sourceSchema, sourceTable, targetTable, rowLimit, policyId }) => {
    const oldTable = profile.tableName;
    profile.sourceDataSourceId = sourceDs && String(sourceDs) !== String(def?.dataSourceId || '') ? sourceDs : null;
    profile.sourceSchemaName = sourceSchema &&
      !(String(sourceDs) === String(def?.dataSourceId || '') && sameSchemaName(sourceSchema, def?.schemaName))
      ? sourceSchema : null;
    profile.tableName = sourceTable;
    profile.policyId = policyId ? parseInt(policyId) : null;
    profile.targetTableName = targetTable && targetTable !== profile.tableName ? targetTable : null;
    profile.rowLimit = rowLimit > 0 ? rowLimit : null;
    if (oldTable && oldTable !== sourceTable) {
      tableNameChanged = true;
      adOverrides = adOverrides.filter(o => o.tableName !== oldTable);
    }
  });
  await saveTableMapContext();
  adProfiles = await api.put(`/api/datasets/${selectedAdId}/profiles`, adProfiles);
  if (tableNameChanged) {
    adOverrides = await api.put(`/api/datasets/${selectedAdId}/overrides`, adOverrides);
  }
  adDirty = false;
  renderAdProfiles();
  renderTableMapGrid();
  renderDataScopeProvision();
  loadAdInsights();
  if (showToast) toast('Table map saved', 'ok');
  return true;
}

async function saveTableMap() {
  try {
    if (await persistTableMap(true)) closeTableMap();
  } catch (e) { toast(e.message, 'err'); }
}

async function openColumnMapFromTableMap(idx) {
  try {
    if (await persistTableMap(false)) {
      await openColumnMap(idx);
    }
  } catch (e) { toast(e.message, 'err'); }
}

async function openColumnMap(idx) {
  const def = currentAdDef();
  const profile = adProfiles[idx];
  if (!def || !profile) return;
  const tableMapOpen = $('table-map-modal').style.display !== 'none';
  const sourceDs = profileSourceDataSourceId(profile, def);
  const sourceSchema = profileSourceSchemaName(profile, def);
  const targetDs = parseInt((tableMapOpen ? $('tm-target-ds')?.value : def.targetDataSourceId) || def.dataSourceId || '0');
  const targetSchema = (tableMapOpen ? $('tm-target-schema')?.value : def.targetSchemaName) || '';
  const targetTable = profile.targetTableName || profile.tableName;
  if (!sourceDs || !profile.tableName || !targetDs || !targetTable) {
    toast('Choose source and target tables first', 'err');
    return;
  }

  $('column-map-modal').style.display = 'flex';
  $('cm-subtitle').textContent = `${tableMapSourceContextLabel(profile, def)} / ${profile.tableName} -> ${targetTable}`;
  $('cm-grid').innerHTML = '<div class="loading">Loading source and target columns...</div>';
  fillColumnMapPolicySelect(profile);
  if ($('cm-bulk-action')) $('cm-bulk-action').value = 'USE_POLICY';
  if ($('cm-bulk-literal')) $('cm-bulk-literal').value = '';
  columnMapBulkActionChanged();

  try {
    const [sourceColumns, targetColumns] = await Promise.all([
      fetchColumnMeta(sourceDs, sourceSchema || '', profile.tableName),
      fetchColumnMeta(targetDs, targetSchema, targetTable)
    ]);
    columnMapState = {
      profileIdx: idx,
      sourceTable: profile.tableName,
      targetTable,
      sourceColumns,
      targetColumns,
      rows: buildColumnMapRows(profile.tableName, sourceColumns, targetColumns),
      policyRules: []
    };
    renderColumnMap();
    await loadColumnMapPolicyRules();
  } catch (e) {
    $('cm-grid').innerHTML = `<div class="error">${esc(e.message)}</div>`;
  }
}

function fillColumnMapPolicySelect(profile) {
  setOptionsPreserve('cm-policy', policyOptionsForProfile(profile));
  const selected = profilePolicyId(profile);
  if (selected && [...$('cm-policy').options].some(o => o.value === String(selected))) {
    $('cm-policy').value = String(selected);
  } else {
    $('cm-policy').value = '';
  }
}

async function columnMapPolicyChanged() {
  await saveColumnMapPolicy($('cm-policy')?.value || '', false);
  await loadColumnMapPolicyRules();
}

async function fetchColumnMeta(dsId, schema, table) {
  const rows = await api.get(`/api/datasources/${dsId}/tables/${encodeURIComponent(table)}/columns${schema ? '?schema=' + encodeURIComponent(schema) : ''}`);
  return rows.map(r => ({
    name: r.column || r.name || r.columnName || String(r),
    type: r.type || r.typeName || '',
    size: r.size ?? r.length ?? '',
    nullable: r.nullable
  }));
}

function buildColumnMapRows(sourceTable, sourceColumns, targetColumns) {
  const existing = {};
  adOverrides.filter(o => o.tableName === sourceTable).forEach(o => {
    existing[String(o.columnName).toLowerCase()] = o;
  });
  const used = new Set();
  return targetColumns.map(target => {
    const saved = existing[target.name.toLowerCase()];
    let action = saved?.overrideType || 'USE_POLICY';
    let source = saved?.sourceColumnName || '';
    if (!source && action === 'USE_POLICY') source = autoSourceForTarget(target.name, sourceColumns, used);
    if (!source && !saved) action = 'SUPPRESS';
    if (source) used.add(source.toLowerCase());
    return {
      targetColumn: target.name,
      sourceColumn: source,
      action,
      literalValue: saved?.literalValue || '',
      note: saved?.note || '',
      ...cmParseCond(saved)
    };
  });
}


function cmSqlTerm(col, op, val) {
  const v = val == null ? '' : String(val);
  const q = s => "'" + String(s).replace(/'/g, "''") + "'";
  switch ((op || 'EQ').toUpperCase()) {
    case 'NE': return `${col} <> ${q(v)}`;
    case 'GT': return `${col} > ${q(v)}`;
    case 'LT': return `${col} < ${q(v)}`;
    case 'GTE': return `${col} >= ${q(v)}`;
    case 'LTE': return `${col} <= ${q(v)}`;
    case 'CONTAINS': return `${col} LIKE ${q('%' + v + '%')}`;
    case 'STARTS_WITH': return `${col} LIKE ${q(v + '%')}`;
    case 'IN': return `${col} IN (${v.split(',').map(x => q(x.trim())).join(',')})`;
    case 'NOT_IN': return `${col} NOT IN (${v.split(',').map(x => q(x.trim())).join(',')})`;
    case 'IS_NULL': return `${col} IS NULL`;
    case 'IS_NOT_NULL': return `${col} IS NOT NULL`;
    default: return `${col} = ${q(v)}`;
  }
}

function cmLegacyToExpr(logic, clauses) {
  const joins = [];
  const terms = clauses.map((c, i) => {
    let colRef;
    if (c.joinTable) {
      const al = 'j' + i;
      joins.push(`LEFT JOIN ${c.joinTable} ${al} ON ${al}.${c.joinTargetCol} = t.${c.joinSourceCol}`);
      colRef = `${al}.${c.column}`;
    } else colRef = `t.${c.column}`;
    return cmSqlTerm(colRef, c.operator, c.value);
  });
  return { expr: terms.join(logic === 'ANY' ? ' OR ' : ' AND '), join: joins.join(' ') };
}

function cmParseCond(saved) {
  if (saved && saved.condExpr) return { condEnabled: true, condExpr: saved.condExpr, condJoin: saved.condJoin || '' };
  if (saved && saved.condJson) {
    try {
      const cj = JSON.parse(saved.condJson);
      const cl = cj.clauses || [];
      if (cl.length) { const r = cmLegacyToExpr(cj.logic, cl); return { condEnabled: true, condExpr: r.expr, condJoin: r.join }; }
    } catch (e) { /* fall through */ }
  }
  if (saved && saved.condColumn) {
    const r = cmLegacyToExpr('ALL', [{ column: saved.condColumn, operator: saved.condOperator, value: saved.condValue,
      joinTable: saved.condJoinTable, joinSourceCol: saved.condJoinSourceCol, joinTargetCol: saved.condJoinTargetCol }]);
    return { condEnabled: true, condExpr: r.expr, condJoin: r.join };
  }
  return { condEnabled: false, condExpr: '', condJoin: '' };
}

/* ---------- Referential Integrity & Keys registry ---------- */
let riGroupsCache = null;

async function loadRi() {
  if (!dataSources.length) { try { await loadDs(); } catch (e) { /* ignore */ } }
  fillDsSelects();
  const ds = $('ri-ds') ? $('ri-ds').value : '';
  if (!ds) {
    if ($('ri-keys')) $('ri-keys').innerHTML = '<div class="empty">Select a data source.</div>';
    if ($('ri-rels')) $('ri-rels').innerHTML = '<div class="empty">Select a data source.</div>';
    return;
  }
  const schema = ($('ri-schema').value || '').trim();
  const qs = '?dataSourceId=' + encodeURIComponent(ds) + (schema ? '&schema=' + encodeURIComponent(schema) : '');
  try {
    const [keys, rels] = await Promise.all([api.get('/api/ri/keys' + qs), api.get('/api/ri/relationships' + qs)]);
    riRenderKeys(keys);
    riRenderRels(rels);
  } catch (e) { toast(e.message, 'err'); }
}

function riVisBadge(v) {
  return '<span class="pill ' + (v === 'PRIVATE' ? 'dim' : 'info') + ' small">' + esc(v) + '</span>';
}

function riRenderKeys(list) {
  if (!list || !list.length) { $('ri-keys').innerHTML = '<div class="empty">No primary keys defined for this target yet.</div>'; return; }
  $('ri-keys').innerHTML = '<table><tr><th>Table</th><th>Key columns</th><th>Scope</th><th>Owner</th><th></th></tr>' +
    list.map(k => `<tr><td><b>${esc(k.tableName)}</b>${k.schemaName ? ' <span class="sub">' + esc(k.schemaName) + '</span>' : ''}</td>` +
      `<td class="mono">${esc(k.keyColumns)}</td><td>${riVisBadge(k.visibility)}</td><td>${esc(k.ownerUsername || '')}</td>` +
      `<td><button class="small ghost" onclick="riDeleteKey(${k.id})">Delete</button></td></tr>`).join('') + '</table>';
}

function riRenderRels(list) {
  if (!list || !list.length) { $('ri-rels').innerHTML = '<div class="empty">No relationships defined for this target yet.</div>'; return; }
  $('ri-rels').innerHTML = '<table><tr><th>Child</th><th>Parent</th><th>Children / parent</th><th>Type</th><th>Scope</th><th>Owner</th><th></th></tr>' +
    list.map(r => `<tr><td class="mono">${esc(r.childTable)}(${esc(r.childColumns)})</td>` +
      `<td class="mono">${esc(r.parentTable)}(${esc(r.parentColumns)})</td>` +
      `<td>${(r.cardinalityMin != null || r.cardinalityMax != null) ? esc((r.cardinalityMin == null ? '' : r.cardinalityMin) + '..' + (r.cardinalityMax == null ? '' : r.cardinalityMax)) : '<span class="sub">&mdash;</span>'}</td>` +
      `<td>${esc(r.relationshipType || '')}</td><td>${riVisBadge(r.visibility)}</td><td>${esc(r.ownerUsername || '')}</td>` +
      `<td><button class="small ghost" onclick="riDeleteRel(${r.id})">Delete</button></td></tr>`).join('') + '</table>';
}

async function riVisibilityField(prefix) {
  if (riGroupsCache === null) { try { riGroupsCache = await api.get('/api/ri/my-groups'); } catch (e) { riGroupsCache = []; } }
  const groupOpts = (riGroupsCache || []).map(g => `<option value="${g.id}">${esc(g.name)}</option>`).join('');
  return `<div><label>Scope</label><select id="${prefix}-vis" onchange="riToggleGroup('${prefix}')">` +
    `<option value="PRIVATE">Private (only me)</option>` +
    `<option value="GROUP">Group</option>` +
    `<option value="GLOBAL">Global (everyone &mdash; admin only)</option></select></div>` +
    `<div id="${prefix}-group-wrap" style="display:none"><label>Group</label><select id="${prefix}-group">${groupOpts || '<option value="">(no groups)</option>'}</select></div>`;
}

function riToggleGroup(prefix) {
  const v = $(prefix + '-vis').value;
  const w = $(prefix + '-group-wrap');
  if (w) w.style.display = (v === 'GROUP') ? '' : 'none';
}

async function riNewKey() {
  if (!$('ri-ds').value) return toast('Select a data source first', 'err');
  const visHtml = await riVisibilityField('rik');
  $('ri-keys').insertAdjacentHTML('afterbegin',
    `<div class="card" id="rik-form"><div class="row">` +
    `<div><label>Table</label><input id="rik-table" placeholder="customers"></div>` +
    `<div><label>Schema <span class="pill dim small">optional</span></label><input id="rik-schema" placeholder="${esc(($('ri-schema').value || '').trim())}"></div>` +
    `<div><label>Key columns <span class="pill dim small">comma-separated</span></label><input id="rik-cols" placeholder="customer_id"></div>` +
    visHtml + `</div>` +
    `<div class="row push"><button class="small" onclick="riSaveKey()">Save</button><button class="small ghost" onclick="loadRi()">Cancel</button></div></div>`);
}

async function riSaveKey() {
  const vis = $('rik-vis').value;
  const body = {
    dataSourceId: parseInt($('ri-ds').value),
    schemaName: ($('rik-schema').value || '').trim() || null,
    tableName: ($('rik-table').value || '').trim(),
    keyColumns: ($('rik-cols').value || '').trim(),
    visibility: vis,
    ownerGroupId: vis === 'GROUP' ? (parseInt($('rik-group').value) || null) : null
  };
  if (!body.tableName || !body.keyColumns) return toast('Table and key columns are required', 'err');
  try { await api.post('/api/ri/keys', body); toast('Primary key saved', 'ok'); await loadRi(); }
  catch (e) { toast(e.message, 'err'); }
}

async function riDeleteKey(id) {
  if (!await uiConfirm('Delete this primary key definition?', {danger: true, okText: 'Delete'})) return;
  try { await api.del('/api/ri/keys/' + id); await loadRi(); toast('Deleted', 'ok'); } catch (e) { toast(e.message, 'err'); }
}

async function riNewRel() {
  if (!$('ri-ds').value) return toast('Select a data source first', 'err');
  const visHtml = await riVisibilityField('rir');
  $('ri-rels').insertAdjacentHTML('afterbegin',
    `<div class="card" id="rir-form"><div class="row">` +
    `<div><label>Child table</label><input id="rir-ctable" placeholder="accounts"></div>` +
    `<div><label>Child columns</label><input id="rir-ccols" placeholder="customer_id"></div>` +
    `<div><label>Parent table</label><input id="rir-ptable" placeholder="customers"></div>` +
    `<div><label>Parent columns</label><input id="rir-pcols" placeholder="customer_id"></div></div>` +
    `<div class="row">` +
    `<div><label>Type</label><select id="rir-type"><option>NON_IDENTIFYING</option><option>IDENTIFYING</option><option>OPTIONAL</option></select></div>` +
    `<div><label>Children/parent min <span class="pill dim small">optional</span></label><input id="rir-cmin" type="number" min="0"></div>` +
    `<div><label>max</label><input id="rir-cmax" type="number" min="0"></div>` +
    visHtml + `</div>` +
    `<div class="row push"><button class="small" onclick="riSaveRel()">Save</button><button class="small ghost" onclick="loadRi()">Cancel</button></div></div>`);
}

async function riSaveRel() {
  const vis = $('rir-vis').value;
  const schema = ($('ri-schema').value || '').trim() || null;
  const body = {
    dataSourceId: parseInt($('ri-ds').value),
    childSchema: schema, childTable: ($('rir-ctable').value || '').trim(), childColumns: ($('rir-ccols').value || '').trim(),
    parentSchema: schema, parentTable: ($('rir-ptable').value || '').trim(), parentColumns: ($('rir-pcols').value || '').trim(),
    relationshipType: $('rir-type').value,
    cardinalityMin: $('rir-cmin').value !== '' ? parseInt($('rir-cmin').value) : null,
    cardinalityMax: $('rir-cmax').value !== '' ? parseInt($('rir-cmax').value) : null,
    visibility: vis,
    ownerGroupId: vis === 'GROUP' ? (parseInt($('rir-group').value) || null) : null
  };
  if (!body.childTable || !body.childColumns || !body.parentTable || !body.parentColumns)
    return toast('Child and parent table + columns are required', 'err');
  try { await api.post('/api/ri/relationships', body); toast('Relationship saved', 'ok'); await loadRi(); }
  catch (e) { toast(e.message, 'err'); }
}

async function riDeleteRel(id) {
  if (!await uiConfirm('Delete this relationship definition?', {danger: true, okText: 'Delete'})) return;
  try { await api.del('/api/ri/relationships/' + id); await loadRi(); toast('Deleted', 'ok'); } catch (e) { toast(e.message, 'err'); }
}

function cmCondEditor(row, idx) {
  if (!row.condEnabled) {
    return `<div class="cm-cond" style="grid-column:1/-1;padding:5px 8px;border-bottom:1px dashed var(--border)">
      <label class="check" title="Mask this column only when the condition is true; otherwise keep the original value">
        <input type="checkbox" onchange="columnMapCondToggle(${idx},this.checked)"> Conditional</label></div>`;
  }
  return `<div class="cm-cond" style="grid-column:1/-1;padding:6px 8px;border-bottom:1px dashed var(--border)">
    <label class="check"><input type="checkbox" checked onchange="columnMapCondToggle(${idx},this.checked)"> Conditional — mask only when this is true</label>
    <div style="margin-top:5px">
      <label style="display:block">WHERE condition (SQL — source table is <code>t</code>)</label>
      <input style="width:100%;font-family:monospace" value="${esc(row.condExpr || '')}" oninput="cmCondField(${idx},'condExpr',this.value)"
        placeholder="(t.STATUS = 1 AND t.TYPE = 2) OR t.REGION = 'US'">
    </div>
    <div style="margin-top:5px">
      <label style="display:block">JOIN (optional — to test a column on another table)</label>
      <input style="width:100%;font-family:monospace" value="${esc(row.condJoin || '')}" oninput="cmCondField(${idx},'condJoin',this.value)"
        placeholder="LEFT JOIN CUST_EXT x ON x.CUST_ID = t.CUST_ID AND x.BRANCH = t.BRANCH">
    </div>
    <p class="sub" style="margin:5px 0 0">Reference the source table as <code>t</code> (e.g. <code>t.STATUS</code>). To test another table, add a JOIN above and reference it by your alias (e.g. <code>x.REGION = 'US'</code>). Evaluated per row; when false the original value is kept. Multi-column joins: chain with <code>AND</code> in the ON clause.</p>
  </div>`;
}

function cmCondField(idx, key, value) {
  const r = columnMapState.rows[idx];
  if (r) r[key] = value;
}

function columnMapCondToggle(idx, checked) {
  const row = columnMapState.rows[idx];
  if (!row) return;
  row.condEnabled = checked;
  if (!checked) { row.condExpr = ''; row.condJoin = ''; }
  renderColumnMap();
}

function autoSourceForTarget(targetName, sourceColumns, used) {
  const exact = sourceColumns.find(c => c.name.toLowerCase() === targetName.toLowerCase() && !used.has(c.name.toLowerCase()));
  if (exact) return exact.name;
  const key = mapNameKey(targetName);
  const normalized = sourceColumns.find(c => mapNameKey(c.name) === key && !used.has(c.name.toLowerCase()));
  return normalized ? normalized.name : '';
}

function mapNameKey(name) {
  return String(name || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

function columnMeta(columns, name) {
  return columns.find(c => c.name.toLowerCase() === String(name || '').toLowerCase()) || null;
}

function dtype(meta) {
  if (!meta) return '-';
  const size = meta.size ? `(${meta.size})` : '';
  const nullable = meta.nullable === true ? ' null' : meta.nullable === false ? ' not null' : '';
  return `${meta.type || 'type'}${size}${nullable}`;
}

function renderColumnMap() {
  const state = columnMapState;
  const el = $('cm-grid');
  if (!el) return;
  if (!state.rows.length) {
    el.innerHTML = '<div class="empty">No target columns found.</div>';
    return;
  }
  const used = new Set(state.rows.map(r => r.sourceColumn).filter(Boolean).map(c => c.toLowerCase()));
  el.innerHTML = `<div class="column-map-table">
    <div class="column-map-head">
      <span>Target column</span><span>Target dtype</span><span>Source column</span>
      <span>Source dtype</span><span>Action</span><span>Literal</span><span>Policy rule</span>
    </div>` + state.rows.map((row, idx) => {
      const srcMeta = columnMeta(state.sourceColumns, row.sourceColumn);
      const tgtMeta = columnMeta(state.targetColumns, row.targetColumn);
      const sourceOptions = '<option value="">No source</option>' + state.sourceColumns.map(c => {
        const disabled = used.has(c.name.toLowerCase()) && c.name !== row.sourceColumn ? ' disabled' : '';
        return `<option value="${esc(c.name)}"${c.name === row.sourceColumn ? ' selected' : ''}${disabled}>${esc(c.name)}</option>`;
      }).join('');
      const action = row.action || 'USE_POLICY';
      return `<div class="column-map-row ${action === 'SUPPRESS' ? 'unused' : ''}">
        <span><code>${esc(row.targetColumn)}</code></span>
        <span class="dtype">${esc(dtype(tgtMeta))}</span>
        <span><select onchange="columnMapSourceChanged(${idx}, this.value)">${sourceOptions}</select></span>
        <span class="dtype">${esc(dtype(srcMeta))}</span>
        <span><select onchange="columnMapActionChanged(${idx}, this.value)">
          <option value="USE_POLICY"${action === 'USE_POLICY' ? ' selected' : ''}>Map / mask if rule exists</option>
          <option value="LITERAL"${action === 'LITERAL' ? ' selected' : ''}>Literal</option>
          <option value="NULL_OUT"${action === 'NULL_OUT' ? ' selected' : ''}>Null</option>
          <option value="SUPPRESS"${action === 'SUPPRESS' ? ' selected' : ''}>Unused</option>
        </select></span>
        <span><input value="${esc(row.literalValue || '')}" ${action === 'LITERAL' ? '' : 'disabled'}
          oninput="columnMapLiteralChanged(${idx}, this.value)" placeholder="literal value"></span>
        <span class="rule-hint">${esc(policyRuleHint(row))}</span>
      </div>` + cmCondEditor(row, idx);
    }).join('') + '</div>';
}

function columnMapSourceChanged(idx, value) {
  const row = columnMapState.rows[idx];
  if (!row) return;
  row.sourceColumn = value || '';
  if (row.sourceColumn && row.action === 'SUPPRESS') row.action = 'USE_POLICY';
  if (!row.sourceColumn && row.action === 'USE_POLICY') row.action = 'SUPPRESS';
  renderColumnMap();
}

function columnMapActionChanged(idx, value) {
  const row = columnMapState.rows[idx];
  if (!row) return;
  row.action = value;
  if (value === 'SUPPRESS') row.sourceColumn = '';
  if (value === 'USE_POLICY' && !row.sourceColumn) {
    const used = new Set(columnMapState.rows
      .filter((_, rowIdx) => rowIdx !== idx)
      .map(r => r.sourceColumn)
      .filter(Boolean)
      .map(c => c.toLowerCase()));
    row.sourceColumn = autoSourceForTarget(row.targetColumn, columnMapState.sourceColumns, used);
    if (!row.sourceColumn) row.action = 'SUPPRESS';
  }
  renderColumnMap();
}

function columnMapLiteralChanged(idx, value) {
  const row = columnMapState.rows[idx];
  if (row) row.literalValue = value;
}

function columnMapBulkActionChanged() {
  const literal = $('cm-bulk-literal');
  const action = $('cm-bulk-action')?.value || 'USE_POLICY';
  if (literal) literal.disabled = action !== 'LITERAL';
  if (columnMapState.rows.length) applyColumnMapActionToAll(false, false);
}

function applyColumnMapActionToAll(showToast = true, requireLiteral = true) {
  if (!columnMapState.rows.length) {
    if (showToast) toast('Open a column map first', 'err');
    return;
  }
  const action = $('cm-bulk-action')?.value || 'USE_POLICY';
  const literal = $('cm-bulk-literal')?.value || '';
  if (action === 'LITERAL' && requireLiteral && !literal.trim()) {
    toast('Literal value is required before applying to all columns', 'err');
    return;
  }

  if (action === 'USE_POLICY') {
    const used = new Set();
    columnMapState.rows.forEach(row => {
      if (row.sourceColumn && !used.has(row.sourceColumn.toLowerCase())) {
        used.add(row.sourceColumn.toLowerCase());
      } else {
        row.sourceColumn = '';
      }
    });
    let unmapped = 0;
    columnMapState.rows.forEach(row => {
      if (!row.sourceColumn) row.sourceColumn = autoSourceForTarget(row.targetColumn, columnMapState.sourceColumns, used);
      if (row.sourceColumn) {
        row.action = 'USE_POLICY';
        used.add(row.sourceColumn.toLowerCase());
      } else {
        row.action = 'SUPPRESS';
        unmapped++;
      }
    });
    renderColumnMap();
    if (showToast) {
      toast(unmapped ? `Applied column mapping; ${unmapped} target column${unmapped !== 1 ? 's' : ''} left unused` : 'Applied column mapping to all columns', 'ok');
    }
    return;
  }

  columnMapState.rows.forEach(row => {
    row.action = action;
    if (action === 'LITERAL' && literal.trim()) row.literalValue = literal;
    if (action === 'SUPPRESS') row.sourceColumn = '';
  });
  renderColumnMap();
  if (showToast) toast('Applied action to all columns', 'ok');
}

function autoMapColumnMap() {
  const used = new Set();
  columnMapState.rows.forEach(row => {
    row.sourceColumn = autoSourceForTarget(row.targetColumn, columnMapState.sourceColumns, used);
    row.action = row.sourceColumn ? 'USE_POLICY' : 'SUPPRESS';
    if (row.sourceColumn) used.add(row.sourceColumn.toLowerCase());
  });
  renderColumnMap();
}

/* Masked-value preview: sends the CURRENT (unsaved) column selections to the server, which masks
   a few live source rows with exact job-time semantics (same engine, salts, and row context). */
async function previewColumnMapMasking() {
  const state = columnMapState;
  if (!state.rows?.length || state.profileIdx == null) return toast('Open a column map first', 'err');
  const columns = state.rows
    .filter(r => r.action !== 'SUPPRESS')
    .map(r => ({
      targetColumn: r.targetColumn,
      sourceColumn: r.sourceColumn || null,
      action: r.action || 'USE_POLICY',
      literalValue: r.literalValue || null
    }));
  if (!columns.length) return toast('All columns are marked Unused — nothing to preview', 'err');
  const el = $('cm-preview');
  if (el) el.innerHTML = '<div class="loading">Masking sample rows…</div>';
  try {
    const res = await api.post(`/api/datasets/${selectedAdId}/preview-mask`, {
      table: state.sourceTable,
      policyId: parseInt($('cm-policy')?.value) || null,
      rows: 5,
      columns
    });
    renderColumnMapPreview(res);
  } catch (e) {
    if (el) el.innerHTML = '';
    toast(e.message, 'err');
  }
}

function renderColumnMapPreview(res) {
  const el = $('cm-preview');
  if (!el) return;
  if (!res.rows?.length) {
    el.innerHTML = '<div class="empty">Source table has no rows to preview.</div>';
    return;
  }
  el.innerHTML = `<div class="cm-preview-panel">
      <div class="diff-head"><b>Masked preview — ${res.rows.length} live row${res.rows.length === 1 ? '' : 's'} from <code>${esc(res.table)}</code></b>
        <button class="small ghost" onclick="$('cm-preview').innerHTML=''">Close</button></div>
      <div class="cm-preview-scroll"><table>
        <tr>${res.columns.map(c => `<th>${esc(c.targetColumn)}<div class="cm-preview-state">${esc(c.state)}</div></th>`).join('')}</tr>` +
        res.rows.map(cells => `<tr>${cells.map(cell => {
          const orig = cell.original ?? '∅', masked = cell.masked ?? '∅';
          return orig === masked
            ? `<td><span class="dim">${esc(orig)}</span></td>`
            : `<td><s>${esc(orig)}</s><br><b>${esc(masked)}</b></td>`;
        }).join('')}</tr>`).join('') +
      '</table></div></div>';
}

async function loadColumnMapPolicyRules() {
  const policyId = $('cm-policy')?.value;
  columnMapState.policyRules = [];
  if (policyId) {
    try { columnMapState.policyRules = await api.get(`/api/policies/${policyId}/rules`); }
    catch (e) { toast(e.message, 'err'); }
  }
  renderColumnMap();
}

function policyRuleHint(row) {
  const policyId = $('cm-policy')?.value || '';
  if (!row.sourceColumn) return row.action === 'SUPPRESS' ? 'Unused' : 'No source';
  if (!policyId) return 'Copy as-is (no masking)';
  if (!columnMapState.policyRules.length) return 'No policy rule';
  const sourceTable = String(columnMapState.sourceTable || '').toLowerCase();
  const targetTable = String(columnMapState.targetTable || '').toLowerCase();
  const sourceCol = row.sourceColumn.toLowerCase();
  const targetCol = row.targetColumn.toLowerCase();
  const rule = columnMapState.policyRules.find(r =>
    String(r.tableName || '').toLowerCase() === sourceTable &&
    String(r.columnName || '').toLowerCase() === sourceCol)
    || columnMapState.policyRules.find(r =>
      String(r.tableName || '').toLowerCase() === targetTable &&
      String(r.columnName || '').toLowerCase() === targetCol);
  if (!rule) return 'No policy rule';
  const params = [rule.param1, rule.param2].filter(Boolean).join(', ');
  return params ? `${rule.function}(${params})` : rule.function;
}

async function saveColumnMap() {
  if (!selectedAdId || columnMapState.profileIdx === null) return;
  const profile = adProfiles[columnMapState.profileIdx];
  if (!profile) return;
  const next = adOverrides.filter(o => o.tableName !== profile.tableName);
  for (const row of columnMapState.rows) {
    let overrideType = row.action || 'USE_POLICY';
    if (overrideType === 'USE_POLICY' && !row.sourceColumn) overrideType = 'SUPPRESS';
    const literalValue = overrideType === 'LITERAL' ? String(row.literalValue || '').trim() : null;
    if (overrideType === 'LITERAL' && !literalValue) {
      toast(`Literal value is required for ${row.targetColumn}`, 'err');
      return;
    }
    const condOn = row.condEnabled && row.condExpr && row.condExpr.trim();
    next.push({
      tableName: profile.tableName,
      columnName: row.targetColumn,
      sourceColumnName: overrideType === 'USE_POLICY' ? row.sourceColumn : null,
      overrideType,
      literalValue,
      note: row.note || null,
      condExpr: condOn ? row.condExpr.trim() : null,
      condJoin: condOn && row.condJoin && row.condJoin.trim() ? row.condJoin.trim() : null,
      condJson: null,
      condColumn: null, condOperator: null, condValue: null,
      condJoinTable: null, condJoinSourceCol: null, condJoinTargetCol: null
    });
  }
  try {
    adOverrides = await api.put(`/api/datasets/${selectedAdId}/overrides`, next);
    renderAdOverrides();
    renderDataScopeProvision();
    loadAdInsights();
    toast('Column map saved', 'ok');
    closeColumnMap();
  } catch (e) { toast(e.message, 'err'); }
}

function dataSourceName(id) {
  return dataSources.find(d => String(d.id) === String(id))?.name || (id ? `#${id}` : '-');
}

function datasetName(id) {
  return datasetsList.find(d => String(d.id) === String(id))?.name || (id ? `#${id}` : '-');
}

async function loadBusinessEntities() {
  await refreshShared();
  businessEntities = await api.get('/api/business-entities');
  renderBusinessEntities();
  if (selectedBusinessEntityId && businessEntities.some(e => e.id === selectedBusinessEntityId)) {
    await selectBusinessEntity(selectedBusinessEntityId, false);
  } else if (businessEntities.length && !businessEntityDetail) {
    await selectBusinessEntity(businessEntities[0].id, false);
  } else if (!businessEntities.length) {
    resetBusinessEntityForm(false);
  } else {
    renderBusinessEntityDetail();
  }
}

function renderBusinessEntities() {
  const el = $('be-list');
  if (!el) return;
  const q = ($('be-search')?.value || '').toLowerCase().trim();
  const rows = (businessEntities || []).filter(e => {
    const hay = `${e.name || ''} ${e.description || ''} ${e.domain || ''} ${e.ownerUsername || ''} ${e.primaryDatasetName || ''} ${e.rootTable || ''}`.toLowerCase();
    return !q || hay.includes(q);
  });
  if ($('be-count')) $('be-count').textContent = `${rows.length} of ${(businessEntities || []).length}`;
  if (!rows.length) {
    el.innerHTML = '<div class="empty">No business entities match your search.</div>';
    return;
  }
  el.innerHTML = rows.map(e => `
    <div class="be-card ${selectedBusinessEntityId === e.id ? 'active' : ''}" onclick="selectBusinessEntity(${e.id})">
      <div class="be-card-title">
        <b>${esc(e.name)}</b>
        ${statusPill(e.status || 'ACTIVE')}
      </div>
      <div class="be-card-meta">${esc(e.domain || 'No domain')} · ${e.memberCount || 0} tables · ${e.dataSourceCount || 0} systems</div>
      <div class="be-card-meta">${esc(e.primaryDatasetName || 'No DataScope link')}${e.rootTable ? ' / ' + esc(e.rootTable) : ''}</div>
    </div>`).join('');
}

async function selectBusinessEntity(id, showToast = false) {
  selectedBusinessEntityId = id;
  try {
    [businessEntityDetail, businessEntitySnapshots, businessEntityReservations, businessEntityEnterprise, businessEntityFlows, businessEntityIdentities, businessEntitySyncPolicies] = await Promise.all([
      api.get('/api/business-entities/' + id),
      api.get('/api/business-entities/' + id + '/snapshots'),
      api.get('/api/business-entities/' + id + '/reservations'),
      api.get('/api/business-entities/' + id + '/enterprise'),
      api.get('/api/business-entities/' + id + '/flows'),
      api.get('/api/business-entities/' + id + '/identities'),
      api.get('/api/business-entities/' + id + '/sync-policies')
    ]);
    selectedBusinessEntitySyncPolicyId = businessEntitySyncPolicies.some(p => String(p.id) === String(selectedBusinessEntitySyncPolicyId))
      ? selectedBusinessEntitySyncPolicyId
      : (businessEntitySyncPolicies[0]?.id || null);
    businessEntitySyncRun = null;
    await hydrateBusinessEntityFlowDraft();
    try { virtualDbs = await api.get('/api/virtualization/vdbs'); } catch {}
    renderBusinessEntities();
    renderBusinessEntityDetail();
    if (showToast) toast('Business Entity loaded', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function hydrateBusinessEntityFlowDraft() {
  businessEntityFlowDebugRun = null;
  businessEntityFlowValidation = null;
  if (!selectedBusinessEntityId) {
    businessEntityFlowDraft = null;
    selectedBusinessEntityFlowId = null;
    return;
  }
  if (businessEntityFlows.length) {
    const existing = businessEntityFlows.find(f => String(f.id) === String(selectedBusinessEntityFlowId)) || businessEntityFlows[0];
    selectedBusinessEntityFlowId = existing.id;
    businessEntityFlowDraft = beFlowToDraft(existing);
    selectedBusinessEntityFlowNodeKey = businessEntityFlowDraft.nodes?.[0]?.key || null;
    try {
      const runs = await api.get('/api/business-entities/flows/' + existing.id + '/debug-runs');
      businessEntityFlowDebugRun = runs?.[0] || null;
    } catch {}
    return;
  }
  try {
    const starter = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/flows/starter');
    businessEntityFlowDraft = beFlowToDraft(starter);
    selectedBusinessEntityFlowId = null;
    selectedBusinessEntityFlowNodeKey = businessEntityFlowDraft.nodes?.[0]?.key || null;
  } catch {
    businessEntityFlowDraft = null;
    selectedBusinessEntityFlowId = null;
  }
}

function resetBusinessEntityForm(updateList = true) {
  selectedBusinessEntityId = null;
  businessEntityActiveTab = 'model';
  localStorage.setItem('forgetdm.be.tab', businessEntityActiveTab);
  businessEntityDetail = {
    entity: { status: 'ACTIVE' },
    members: [],
    primaryDatasetName: null,
    dataSourceNames: {}
  };
  businessEntitySnapshots = [];
  businessEntityReservations = [];
  businessEntityIdentities = [];
  businessEntityIdentityResolve = null;
  businessEntitySyncPolicies = [];
  businessEntitySyncRun = null;
  selectedBusinessEntitySyncPolicyId = null;
  businessEntityEnterprise = { issuePackages: [], lookalikeProfiles: [], catalogAssets: [], governanceRequests: [], executionPlans: [], operationalPackages: [], packageVersions: [], packagePromotions: [], executionRuns: [], loaderStrategies: [] };
  businessEntityFlows = [];
  businessEntityFlowDraft = null;
  selectedBusinessEntityFlowId = null;
  selectedBusinessEntityFlowNodeKey = null;
  businessEntityFlowDebugRun = null;
  businessEntityFlowValidation = null;
  if (updateList) renderBusinessEntities();
  renderBusinessEntityDetail();
}

function rememberBusinessEntityDraft() {
  if (!businessEntityDetail || !$('be-name')) return;
  businessEntityDetail.entity = { ...(businessEntityDetail.entity || {}), ...collectBusinessEntityDefinition() };
  businessEntityDetail.members = collectBusinessEntityMembers();
}

function setBusinessEntityTab(tab) {
  rememberBusinessEntityDraft();
  businessEntityActiveTab = tab || 'model';
  localStorage.setItem('forgetdm.be.tab', businessEntityActiveTab);
  renderBusinessEntityDetail();
}

function renderBusinessEntityDetail() {
  const el = $('be-detail');
  if (!el) return;
  const detail = businessEntityDetail || { entity: { status: 'ACTIVE' }, members: [] };
  const e = detail.entity || { status: 'ACTIVE' };
  const members = detail.members || [];
  if (!e.id && businessEntityActiveTab !== 'model') businessEntityActiveTab = 'model';
  el.innerHTML = `
    <div class="be-editor-head">
      <div>
        <h3>${esc(e.name || (e.id ? 'Business Entity' : 'New Business Entity'))}</h3>
        <p class="sub tight">${businessEntitySubtitle(e, members)}</p>
      </div>
      <div class="be-actions">
        ${e.id ? `<button class="ghost danger" onclick="deleteBusinessEntity(${e.id})">Delete</button>` : ''}
        ${businessEntityActiveTab === 'model' ? '<button onclick="saveBusinessEntity()">Save Entity</button>' : ''}
      </div>
    </div>
    ${renderBusinessEntityTabs(e, members)}
    <div class="be-tab-pane">${renderBusinessEntityActivePane(e, members)}</div>`;
}

function businessEntitySubtitle(e, members) {
  const parts = [];
  if (e.domain) parts.push(e.domain);
  parts.push(`${members.length} member table${members.length === 1 ? '' : 's'}`);
  if (e.primaryDatasetId) parts.push(`DataScope: ${datasetName(e.primaryDatasetId)}`);
  return parts.join(' / ') || 'Model a reusable business object, then drive DataScope or Synthetic from it.';
}

function renderBusinessEntityTabs(e, members) {
  const ent = businessEntityEnterprise || {};
  const tabs = [
    ['model', 'Model', `${members.length} table${members.length === 1 ? '' : 's'}`],
    ['identity', 'Identity', `${businessEntityIdentities.length} key${businessEntityIdentities.length === 1 ? '' : 's'}`],
    ['freshness', 'Freshness', `${businessEntitySyncPolicies.length} polic${businessEntitySyncPolicies.length === 1 ? 'y' : 'ies'}`],
    ['time', 'Time & Reserve', `${businessEntitySnapshots.length} snap / ${businessEntityReservations.filter(r => r.status === 'ACTIVE').length} active`],
    ['data', 'Build Data', `${(ent.issuePackages || []).length + (ent.lookalikeProfiles || []).length} item${((ent.issuePackages || []).length + (ent.lookalikeProfiles || []).length) === 1 ? '' : 's'}`],
    ['governance', 'Governance', `${(ent.governanceRequests || []).filter(r => r.status === 'PENDING').length} pending`],
    ['flow', 'Flow Studio', `${businessEntityFlows.length || (businessEntityFlowDraft ? 1 : 0)} flow${(businessEntityFlows.length || (businessEntityFlowDraft ? 1 : 0)) === 1 ? '' : 's'}`],
    ['run', 'Run & Packages', `${(ent.executionPlans || []).length} plan${(ent.executionPlans || []).length === 1 ? '' : 's'}`],
    ['evidence', 'Evidence', `${(ent.executionRuns || []).length} run${(ent.executionRuns || []).length === 1 ? '' : 's'}`]
  ];
  const locked = !e.id;
  return `<div class="be-tabs">${tabs.map(([id, label, meta]) => {
    const disabled = locked && id !== 'model';
    return `<button type="button" class="be-tab ${businessEntityActiveTab === id ? 'active' : ''}" ${disabled ? 'disabled' : `onclick="setBusinessEntityTab('${id}')"`}>
      <b>${esc(label)}</b><span>${esc(disabled ? 'save entity first' : meta)}</span>
    </button>`;
  }).join('')}</div>`;
}

function renderBusinessEntityActivePane(e, members) {
  if (!e.id && businessEntityActiveTab !== 'model') return renderBusinessEntityModelPane(e, members);
  switch (businessEntityActiveTab) {
    case 'identity': return renderBusinessEntityIdentityPane();
    case 'freshness': return renderBusinessEntityFreshnessPane();
    case 'time': return renderBusinessEntityTimePane(e);
    case 'data': return renderBusinessEntityDataPane();
    case 'governance': return renderBusinessEntityGovernancePane();
    case 'flow': return renderBusinessEntityFlowPane();
    case 'run': return renderBusinessEntityRunPane();
    case 'evidence': return renderBusinessEntityEvidencePane();
    case 'model':
    default: return renderBusinessEntityModelPane(e, members);
  }
}

function renderBusinessEntityModelPane(e, members) {
  return `
    <div class="be-test-card">
      <b>What to test here</b>
      <span>Start from a DataScope blueprint when you already know the tables. Use New blank when you want to manually define a business object across systems.</span>
    </div>
    <div class="be-form-grid">
      <div><label>Name</label><input id="be-name" value="${esc(e.name || '')}" placeholder="Customer 360"></div>
      <div><label>Domain</label><input id="be-domain" value="${esc(e.domain || '')}" placeholder="Retail banking"></div>
      <div><label>Status</label><select id="be-status">
        ${['ACTIVE','DRAFT','RETIRED'].map(s => `<option value="${s}"${(e.status || 'ACTIVE') === s ? ' selected' : ''}>${s}</option>`).join('')}
      </select></div>
      <div><label>Owner</label><input id="be-owner" value="${esc(e.ownerUsername || '')}" placeholder="current user"></div>
      <div><label>Primary DataScope</label><select id="be-primary-dataset">${beDatasetOptions(e.primaryDatasetId, 'No primary blueprint')}</select></div>
      <div><label>Root table</label><input id="be-root" value="${esc(e.rootTable || '')}" placeholder="customers"></div>
      <div class="grow"><label>Business key columns</label><input id="be-key-cols" value="${esc(e.businessKeyColumns || '')}" placeholder="customer_id or region,customer_no"></div>
      <div class="grow"><label>Description</label><input id="be-desc" value="${esc(e.description || '')}" placeholder="What this business entity represents"></div>
    </div>
    <div class="be-members-head">
      <div><h3>Member tables</h3><p class="sub tight">These are the physical tables/systems that make up the entity.</p></div>
      <div class="be-actions">
        <button class="ghost small" onclick="addBusinessEntityMember()">Add member</button>
        <button class="small" onclick="saveBusinessEntityMembers()">Save members</button>
      </div>
    </div>
    <div id="be-members">${renderBusinessEntityMembers(members)}</div>`;
}

function renderBusinessEntityIdentityPane() {
  const members = businessEntityDetail?.members || [];
  const linkRows = members.length ? members.slice(0, 4) : [{ logicalRole: 'customer', tableName: businessEntityDetail?.entity?.rootTable || 'customers', keyColumns: businessEntityDetail?.entity?.businessKeyColumns || 'customer_id' }];
  return `<div class="be-ops">
    <div class="be-test-card">
      <b>Cross-application identity crosswalk</b>
      <span>Store the real mapping from one canonical business key to DB2, Oracle, CRM, and other application identifiers. Resolve from any system key back to the full Business Entity identity.</span>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Create / update identity</h3><p class="sub tight">Example: CUST-10025 -> DB2 customer_id -> Oracle card ref -> CRM party id.</p></div>
        <button onclick="saveBusinessEntityIdentity()">Save crosswalk</button>
      </div>
      <div class="be-form-grid">
        <div><label>Canonical key</label><input id="be-id-canonical" placeholder="CUST-10025"></div>
        <div><label>Identity type</label><select id="be-id-type"><option>CUSTOMER</option><option>ACCOUNT</option><option>PARTY</option><option>HOUSEHOLD</option><option>BUSINESS_ENTITY</option></select></div>
        <div><label>Status</label><select id="be-id-status"><option>ACTIVE</option><option>REVIEW</option><option>RETIRED</option></select></div>
        <div><label>Confidence</label><input id="be-id-confidence" type="number" min="0" max="1" step=".01" value="1"></div>
      </div>
      <div class="be-identity-link-grid">
        ${linkRows.map((m, idx) => renderBusinessEntityIdentityLinkInput(m, idx)).join('')}
      </div>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Resolve any system key</h3><p class="sub tight">Look up by DB2 customer id, Oracle card ref, CRM party id, or another app key.</p></div>
        <button class="ghost" onclick="resolveBusinessEntityIdentity()">Resolve</button>
      </div>
      <div class="be-op-form compact">
        <div><label>Member / system</label><select id="be-id-res-member">${businessEntityMemberOptions()}</select></div>
        <div><label>External id</label><input id="be-id-res-external" placeholder="customer_id=10025 or 10025"></div>
        <button class="ghost" onclick="loadBusinessEntityIdentities()">Refresh</button>
      </div>
      ${renderBusinessEntityIdentityResolve()}
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Stored crosswalks</h3><p class="sub tight">${businessEntityIdentities.length} canonical identity record${businessEntityIdentities.length === 1 ? '' : 's'}.</p></div>
        <input id="be-id-search" placeholder="Search canonical, DB2, Oracle, CRM..." onkeydown="if(event.key==='Enter') loadBusinessEntityIdentities()">
      </div>
      <div id="be-identity-list">${renderBusinessEntityIdentityList()}</div>
    </div>
  </div>`;
}

function renderBusinessEntityIdentityLinkInput(m, idx) {
  const role = m.logicalRole || '';
  const system = m.systemName || dataSourceName(m.dataSourceId) || '';
  const cols = m.keyColumns || '';
  return `<div class="be-identity-link-row" data-id-link-row="${idx}">
    <div>
      <b>${esc(system || role || 'System ' + (idx + 1))}</b>
      <span>${esc(role || '-')} / ${esc(m.tableName || '-')}</span>
    </div>
    <input class="be-id-link-member" type="hidden" value="${esc(m.id || '')}">
    <input class="be-id-link-system" value="${esc(system)}" placeholder="DB2 / Oracle / CRM">
    <input class="be-id-link-table" value="${esc(m.tableName || '')}" placeholder="table">
    <input class="be-id-link-role" value="${esc(role)}" placeholder="role">
    <input class="be-id-link-cols" value="${esc(cols)}" placeholder="key columns">
    <input class="be-id-link-external" placeholder="${esc(cols ? cols.split(',')[0].trim() + '=10025' : 'external id')}">
    <input class="be-id-link-rule" placeholder="match rule / source">
  </div>`;
}

function renderBusinessEntityIdentityResolve() {
  const r = businessEntityIdentityResolve;
  if (!r) return '<div class="empty small-empty">No resolve result yet.</div>';
  if (!r.matched) return `<div class="warnings">${esc(r.message || 'No match found')}</div>`;
  const c = r.crosswalk || {};
  return `<div class="be-rb-panel">
    <b>Matched ${esc(c.canonicalKey || '-')}</b>
    <div class="be-card-meta">${(c.links || []).length} linked system identifier${(c.links || []).length === 1 ? '' : 's'}</div>
    ${renderBusinessEntityIdentityLinks(c.links || [])}
  </div>`;
}

function renderBusinessEntityIdentityList() {
  if (!businessEntityIdentities.length) return '<div class="empty">No identity crosswalks yet.</div>';
  return `<div class="be-op-list">` + businessEntityIdentities.map(c => `
    <div class="be-op-row">
      <div>
        <b>${esc(c.canonicalKey)}</b> ${statusPill(c.status || 'ACTIVE')}
        <div class="be-card-meta">${esc(c.identityType || 'IDENTITY')} / confidence ${esc(c.confidence ?? '-')} / ${(c.links || []).length} system link${(c.links || []).length === 1 ? '' : 's'}</div>
        ${renderBusinessEntityIdentityLinks(c.links || [])}
      </div>
      <div class="be-actions">
        <button class="ghost small danger" onclick="deleteBusinessEntityIdentity(${c.id})">Delete</button>
      </div>
    </div>`).join('') + '</div>';
}

function renderBusinessEntityIdentityLinks(links) {
  if (!links.length) return '<div class="empty small-empty">No linked system identifiers.</div>';
  return `<div class="be-identity-chipset">` + links.map(l => `
    <span class="be-identity-chip">
      <b>${esc(l.systemName || dataSourceName(l.dataSourceId) || l.logicalRole || 'System')}</b>
      ${esc(l.tableName || '-')}: <code>${esc(l.externalId || '')}</code>
      <button title="Delete link" onclick="event.stopPropagation(); deleteBusinessEntityIdentityLink(${l.id})">x</button>
    </span>`).join('') + '</div>';
}

function renderBusinessEntityFreshnessPane() {
  const policy = currentBusinessEntitySyncPolicy();
  return `<div class="be-ops">
    <div class="be-test-card">
      <b>Freshness and source sync policy</b>
      <span>Define how current each application slice must be. ForgeTDM can read source watermarks directly through JDBC, or accept heartbeat watermarks from CDC, file, mainframe, or scheduler jobs.</span>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Entity freshness policy</h3><p class="sub tight">Use polling for JDBC sources, scheduled for overnight refresh, and heartbeat/real-time when an external feed reports the latest watermark.</p></div>
        <div class="be-actions">
          ${businessEntitySyncPolicies.length ? `<select id="be-sync-policy-select" onchange="selectBusinessEntitySyncPolicy(this.value)">${businessEntitySyncPolicies.map(p => `<option value="${p.id}"${String(p.id) === String(policy.id || '') ? ' selected' : ''}>${esc(p.name)} (#${p.id})</option>`).join('')}</select>` : ''}
          <button class="ghost small" onclick="newBusinessEntitySyncPolicy()">New</button>
          <button onclick="saveBusinessEntitySyncPolicy()">Save policy</button>
          <button class="ghost" onclick="checkBusinessEntitySyncPolicy(${policy.id ? js(policy.id) : ''})">Check now</button>
        </div>
      </div>
      <div class="be-form-grid">
        <input id="be-sync-id" type="hidden" value="${esc(policy.id || '')}">
        <div><label>Name</label><input id="be-sync-name" value="${esc(policy.name || '')}" placeholder="${esc((businessEntityDetail?.entity?.name || 'Entity') + ' freshness')}"></div>
        <div><label>Mode</label><select id="be-sync-mode">${['POLLING','REALTIME','SCHEDULED','ON_DEMAND'].map(s => `<option value="${s}"${(policy.syncMode || 'POLLING') === s ? ' selected' : ''}>${s}</option>`).join('')}</select></div>
        <div><label>Status</label><select id="be-sync-status">${['ACTIVE','DRAFT','PAUSED','RETIRED'].map(s => `<option value="${s}"${(policy.status || 'ACTIVE') === s ? ' selected' : ''}>${s}</option>`).join('')}</select></div>
        <div><label>Max lag seconds</label><input id="be-sync-lag" type="number" min="1" value="${esc(policy.maxLagSeconds || 900)}"></div>
        <div><label>Schedule cron</label><input id="be-sync-cron" value="${esc(policy.scheduleCron || '')}" placeholder="optional, e.g. 0 */5 * * * *"></div>
        <div><label>Strategy</label><select id="be-sync-strategy">${['FRESHNESS_CHECK','REFRESH_BEFORE_RUN','CDC_HEARTBEAT','HYBRID'].map(s => `<option value="${s}"${(policy.syncStrategy || 'FRESHNESS_CHECK') === s ? ' selected' : ''}>${s}</option>`).join('')}</select></div>
        <div><label>Auto check</label><label class="check tight"><input id="be-sync-auto" type="checkbox" ${policy.autoRefreshEnabled ? 'checked' : ''}> run when due</label></div>
        <div class="grow"><label>Notes</label><input id="be-sync-notes" value="${esc(policy.notes || '')}" placeholder="SLA, source owner, CDC topic, runbook"></div>
      </div>
      <div class="be-members-head">
        <div><h3>Source/application watermarks</h3><p class="sub tight">Each row is one physical application participant in the Business Entity freshness decision.</p></div>
        <button class="ghost small" onclick="addBusinessEntitySyncMember()">Add source/table</button>
      </div>
      <div class="be-sync-member-list" id="be-sync-members">
        ${(policy.members || []).map((m, idx) => renderBusinessEntitySyncMemberInput(m, idx)).join('') || '<div class="empty">No source members yet. Add Business Entity members first, or add a manual source/table.</div>'}
      </div>
    </div>
    <div class="be-mini-grid">
      <div><b>Saved policies</b>${renderBusinessEntitySyncPolicyList()}</div>
      <div><b>Latest check result</b>${renderBusinessEntitySyncRun()}</div>
    </div>
  </div>`;
}

function currentBusinessEntitySyncPolicy() {
  const existing = (businessEntitySyncPolicies || []).find(p => String(p.id) === String(selectedBusinessEntitySyncPolicyId));
  if (existing) return existing;
  const members = (businessEntityDetail?.members || []).filter(m => m.includeInSubset !== false).map(m => ({
    memberId: m.id,
    systemName: m.systemName || dataSourceName(m.dataSourceId) || m.logicalRole || '',
    dataSourceId: m.dataSourceId || null,
    schemaName: m.schemaName || '',
    tableName: m.tableName || '',
    logicalRole: m.logicalRole || '',
    keyColumns: m.keyColumns || '',
    watermarkColumn: '',
    maxLagSeconds: 900,
    syncMode: 'POLLING',
    queryFilter: ''
  }));
  return {
    id: null,
    name: (businessEntityDetail?.entity?.name || 'Business Entity') + ' freshness',
    syncMode: 'POLLING',
    status: 'ACTIVE',
    maxLagSeconds: 900,
    scheduleCron: '',
    syncStrategy: 'FRESHNESS_CHECK',
    autoRefreshEnabled: true,
    notes: '',
    members
  };
}

function renderBusinessEntitySyncMemberInput(m, idx) {
  const status = m.lastStatus || 'NEVER_CHECKED';
  const meta = [
    m.lastSourceWatermark ? 'watermark ' + m.lastSourceWatermark : '',
    m.lastCheckedAt ? 'checked ' + new Date(m.lastCheckedAt).toLocaleString() : '',
    m.lastMessage || ''
  ].filter(Boolean).join(' / ');
  return `<div class="be-sync-member-row" data-be-sync-member="${idx}">
    <div>
      <label>Member</label>
      <select class="be-sync-member" onchange="applyBusinessEntitySyncMember(${idx}, this.value)">${businessEntityMemberOptions(m.memberId)}</select>
      <div class="be-card-meta">${statusPill(status)}${meta ? ' ' + esc(meta) : ''}</div>
    </div>
    <div><label>System</label><input class="be-sync-system" value="${esc(m.systemName || '')}" placeholder="Core DB2 / Cards Oracle"></div>
    <div><label>Source DB</label><select class="be-sync-source">${beDataSourceOptions(m.dataSourceId)}</select></div>
    <div><label>Schema</label><input class="be-sync-schema" value="${esc(m.schemaName || '')}" placeholder="optional"></div>
    <div><label>Table</label><input class="be-sync-table" value="${esc(m.tableName || '')}" placeholder="customers"></div>
    <div><label>Keys</label><input class="be-sync-keys" value="${esc(m.keyColumns || '')}" placeholder="customer_id"></div>
    <div><label>Watermark</label><input class="be-sync-watermark" value="${esc(m.watermarkColumn || '')}" placeholder="updated_at"></div>
    <div><label>SLA seconds</label><input class="be-sync-member-lag" type="number" min="1" value="${esc(m.maxLagSeconds || '')}" placeholder="policy default"></div>
    <div><label>Mode</label><select class="be-sync-member-mode">${['POLLING','REALTIME','SCHEDULED','ON_DEMAND','HEARTBEAT'].map(s => `<option value="${s}"${(m.syncMode || 'POLLING') === s ? ' selected' : ''}>${s}</option>`).join('')}</select></div>
    <div class="grow"><label>Filter</label><input class="be-sync-filter" value="${esc(m.queryFilter || '')}" placeholder="region = 'US'"></div>
    <button class="ghost small danger" onclick="removeBusinessEntitySyncMember(this)">Remove</button>
  </div>`;
}

function renderBusinessEntitySyncPolicyList() {
  if (!businessEntitySyncPolicies.length) return '<div class="empty small-empty">No freshness policies saved yet.</div>';
  return `<div class="be-op-list compact-list">` + businessEntitySyncPolicies.map(p => {
    const latest = (p.runs || [])[0];
    const memberStatuses = (p.members || []).map(m => m.lastStatus || 'NEVER_CHECKED');
    const stale = memberStatuses.filter(s => ['STALE','FAILED','UNKNOWN'].includes(String(s).toUpperCase())).length;
    return `<div class="be-op-row compact-row">
      <div>
        <b>${esc(p.name)}</b> ${statusPill(latest?.status || p.status || 'ACTIVE')}
        <div class="be-card-meta">${esc(p.syncMode || 'POLLING')} / max lag ${esc(p.maxLagSeconds || '-')}s / ${p.autoRefreshEnabled ? 'auto check on' : 'manual'} / ${esc((p.members || []).length)} member(s)</div>
        <div class="be-card-meta">${stale ? stale + ' member(s) need attention' : 'No stale evidence recorded'}</div>
        <div class="be-actions">
          <button class="ghost small" onclick="selectBusinessEntitySyncPolicy(${p.id})">Edit</button>
          <button class="ghost small" onclick="checkBusinessEntitySyncPolicy(${p.id})">Check now</button>
          <button class="ghost small danger" onclick="deleteBusinessEntitySyncPolicy(${p.id})">Delete</button>
        </div>
      </div>
    </div>`;
  }).join('') + '</div>';
}

function renderBusinessEntitySyncRun() {
  const run = businessEntitySyncRun || (currentBusinessEntitySyncPolicy().runs || [])[0];
  if (!run) return '<div class="empty small-empty">No freshness check run yet.</div>';
  const result = run.result || beJson(run.resultJson) || {};
  const members = result.members || [];
  const rows = members.length ? members.map(m => `<div class="be-sync-run-row">
    <div><b>${esc(m.systemName || m.tableName || 'member')}</b><span>${esc(m.tableName || '')}${m.watermarkColumn ? ' / ' + esc(m.watermarkColumn) : ''}</span></div>
    ${statusPill(m.status || 'UNKNOWN')}
    <span>${esc(m.lagSeconds == null ? '-' : m.lagSeconds + 's lag')}</span>
    <span>${esc(m.message || '')}</span>
  </div>`).join('') : '<div class="empty small-empty">No member evidence captured in this run.</div>';
  return `<div class="be-rb-panel">
    <b>${esc(result.policyName || currentBusinessEntitySyncPolicy().name || 'Freshness check')}</b> ${statusPill(run.status || result.status || 'UNKNOWN')}
    <div class="be-card-meta">run #${esc(run.id || '-')} / ${esc(run.runType || 'FRESHNESS_CHECK')} / ${esc(run.completedAt ? new Date(run.completedAt).toLocaleString() : '')}</div>
    <div class="be-sync-run-list">${rows}</div>
  </div>`;
}

function renderBusinessEntityMembers(members) {
  if (!members.length) return '<div class="empty">No member tables yet. Add one, or create from a DataScope blueprint.</div>';
  return `<div class="be-member-list">
    <div class="be-member-head-row">
      <span>Role</span><span>Data source</span><span>Schema</span><span>Table</span>
      <span>Key columns</span><span>Join to</span><span>Use</span><span></span>
    </div>
    ${members.map((m, idx) => `
      <div class="be-member-row" data-be-member="${idx}">
        <input class="be-m-role" value="${esc(m.logicalRole || '')}" placeholder="customer">
        <select class="be-m-source">${beDataSourceOptions(m.dataSourceId)}</select>
        <input class="be-m-schema" value="${esc(m.schemaName || '')}" placeholder="schema">
        <input class="be-m-table" value="${esc(m.tableName || '')}" placeholder="table">
        <input class="be-m-keys" value="${esc(m.keyColumns || '')}" placeholder="id or key1,key2">
        <input class="be-m-join" value="${esc(m.joinToRole || '')}" placeholder="parent role">
        <span class="be-member-use">
          <label class="check tight"><input class="be-m-subset" type="checkbox" ${m.includeInSubset === false ? '' : 'checked'}>Subset</label>
          <label class="check tight"><input class="be-m-synth" type="checkbox" ${m.includeInSynthetic === false ? '' : 'checked'}>Synthetic</label>
        </span>
        <button class="ghost small danger" onclick="removeBusinessEntityMember(${idx})">Remove</button>
        <div class="be-member-extra">
          <input class="be-m-alias" value="${esc(m.tableAlias || '')}" placeholder="Target/alias table">
          <select class="be-m-dataset">${beDatasetOptions(m.datasetId, 'No blueprint')}</select>
          <textarea class="be-m-rel" placeholder="Relationship evidence JSON">${esc(m.relationshipJson || '')}</textarea>
        </div>
      </div>`).join('')}
    </div>`;
}

function renderBusinessEntityTimePane(entity) {
  return `<div class="be-ops">
    <div class="be-test-card">
      <b>How to test this tab</b>
      <span>Create an Evidence-only snapshot first. Then create a short reservation with count 1 and release it. Physical snapshot rollback needs Virtualization snapshots to exist.</span>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Snapshots & rollback</h3><p class="sub tight">Capture a point-in-time entity bookmark. Physical mode links to Virtualization snapshots for VDB rollback.</p></div>
        <span class="pill info">${businessEntitySnapshots.length} snapshot${businessEntitySnapshots.length === 1 ? '' : 's'}</span>
      </div>
      <div class="be-op-form">
        <div><label>Name</label><input id="be-snap-name" placeholder="${esc(entity.name || 'Entity')} baseline"></div>
        <div><label>Mode</label><select id="be-snap-mode"><option value="EVIDENCE_ONLY">Evidence only</option><option value="PHYSICAL_SNAPSHOT">Physical snapshot</option></select></div>
        <div><label>Retention days</label><input id="be-snap-retention" type="number" min="0" placeholder="optional"></div>
        <div class="grow"><label>Filter / evidence criteria</label><input id="be-snap-criteria" placeholder="status = 'ACTIVE'"></div>
        <div class="grow"><label>Note</label><input id="be-snap-note" placeholder="Why this point matters"></div>
        <button onclick="createBusinessEntitySnapshot()">Create snapshot</button>
      </div>
      <div id="be-snapshot-list">${renderBusinessEntitySnapshots()}</div>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Entity reservations</h3><p class="sub tight">Reserve complete business objects with TTL, conflict checks, and member-table key evidence.</p></div>
        <span class="pill warn">${businessEntityReservations.filter(r => r.status === 'ACTIVE').length} active</span>
      </div>
      <div class="be-op-form">
        <div><label>Name</label><input id="be-res-name" placeholder="QA cycle reservation"></div>
        <div><label>Count</label><input id="be-res-count" type="number" min="1" value="1"></div>
        <div><label>TTL hours</label><input id="be-res-ttl" type="number" min="1" value="24"></div>
        <div><label>Environment</label><input id="be-res-env" placeholder="UAT"></div>
        <div class="grow"><label>Criteria</label><input id="be-res-criteria" placeholder="region = 'US'"></div>
        <div class="grow"><label>Purpose</label><input id="be-res-purpose" placeholder="Test cycle / defect / team"></div>
        <button onclick="createBusinessEntityReservation()">Reserve entity</button>
      </div>
      <div id="be-reservation-list">${renderBusinessEntityReservations()}</div>
    </div>
  </div>`;
}

function renderBusinessEntityDataPane() {
  const e = businessEntityEnterprise || {};
  return `<div class="be-ops">
    <div class="be-test-card">
      <b>How this uses DataScope and Synthetic</b>
      <span>Business Entity does not replace those pages. It stores the business object and creates packages/plans that later call DataScope subset-mask or Synthetic look-alike generation.</span>
      <div class="be-actions"><button class="ghost small" onclick="goTo('datasets')">Open DataScope</button><button class="ghost small" onclick="goTo('synthetic')">Open Synthetic</button></div>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Production issue recreation</h3><p class="sub tight">Capture the defect context and produce a replayable, privacy-safe data package.</p></div>
        <span class="pill info">${(e.issuePackages || []).length} package${(e.issuePackages || []).length === 1 ? '' : 's'}</span>
      </div>
      <div class="be-op-form">
        <div><label>Issue key</label><input id="be-issue-key" placeholder="INC-12345"></div>
        <div><label>Severity</label><select id="be-issue-sev"><option>MEDIUM</option><option>HIGH</option><option>CRITICAL</option><option>LOW</option></select></div>
        <div><label>Mode</label><select id="be-issue-mode"><option value="MASKED_SUBSET">Masked subset</option><option value="SYNTHETIC_REPLAY">Synthetic replay</option><option value="HYBRID">Hybrid</option></select></div>
        <div class="grow"><label>Title</label><input id="be-issue-title" placeholder="Payment fails for active customer"></div>
        <div><label>Target env</label><input id="be-issue-target" placeholder="UAT"></div>
        <button onclick="createBusinessEntityIssuePackage()">Create package</button>
      </div>
      ${enterpriseRows(e.issuePackages, r => `<b>${esc(r.issueKey)}</b> ${esc(r.title || '')}<div class="be-card-meta">${esc(r.recreationMode)} / ${esc(r.privacyAction)} / ${esc(r.status)} / ${esc(r.approvalStatus)}</div>`)}
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>AI-assisted look-alike planning</h3><p class="sub tight">Generate a metadata-only synthetic plan. No raw source values are stored in the profile.</p></div>
        <span class="pill info">${(e.lookalikeProfiles || []).length} profile${(e.lookalikeProfiles || []).length === 1 ? '' : 's'}</span>
      </div>
      <div class="be-op-form">
        <div><label>Name</label><input id="be-look-name" placeholder="Customer UAT look-alike"></div>
        <div><label>Rows</label><input id="be-look-rows" type="number" min="1" value="1000"></div>
        <div><label>Privacy</label><select id="be-look-privacy"><option value="NO_RAW_VALUES">No raw values</option><option value="BANKING_SAFE_PROFILE">Banking safe profile</option></select></div>
        <div class="grow"><label>Objective</label><input id="be-look-objective" placeholder="Maintain account/customer shape for UAT"></div>
        <button onclick="createBusinessEntityLookalike()">Create plan</button>
      </div>
      ${enterpriseRows(e.lookalikeProfiles, r => `<b>${esc(r.name)}</b><div class="be-card-meta">${esc(r.privacyMode)} / ${r.rowGoal || 0} rows / ${esc(r.status)}</div>`)}
    </div>
  </div>`;
}

function renderBusinessEntityGovernancePane() {
  const e = businessEntityEnterprise || {};
  return `<div class="be-ops">
    <div class="be-test-card">
      <b>How to test this tab</b>
      <span>Click Sync catalog, then create a governance request. You can approve or reject the pending request from the list below to verify maker-checker evidence.</span>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Catalog & governance</h3><p class="sub tight">Publish lineage/certification metadata and enforce maker-checker sign-off for releases.</p></div>
        <button class="ghost small" onclick="syncBusinessEntityCatalog()">Sync catalog</button>
      </div>
      <div class="be-op-form">
        <div><label>Object type</label><select id="be-gov-type"><option>BUSINESS_ENTITY</option><option>ISSUE_PACKAGE</option><option>EXECUTION_PLAN</option><option>OPERATIONAL_PACKAGE</option></select></div>
        <div><label>Action</label><select id="be-gov-action"><option>RELEASE</option><option>RUN</option><option>EXPORT</option><option>PROMOTE</option></select></div>
        <div><label>Risk</label><select id="be-gov-risk"><option>MEDIUM</option><option>HIGH</option><option>CRITICAL</option><option>LOW</option></select></div>
        <div><label>Reviewer</label><input id="be-gov-reviewer" placeholder="checker username"></div>
        <div class="grow"><label>Comments</label><input id="be-gov-comments" placeholder="Release reason and evidence"></div>
        <button onclick="createBusinessEntityGovernance()">Request approval</button>
      </div>
      <div class="be-mini-grid">
        <div><b>Catalog</b>${enterpriseRows(e.catalogAssets, r => `${esc(r.assetType)} / <b>${esc(r.displayName)}</b><div class="be-card-meta">${esc(r.certificationStatus)} / score ${esc(r.qualityScore ?? '')}</div>`)}</div>
        <div><b>Approvals</b>${enterpriseRows(e.governanceRequests, r => `<b>${esc(r.action)}</b> ${statusPill(r.status)}<div class="be-card-meta">${esc(r.objectType)} / risk ${esc(r.riskLevel)} / reviewer ${esc(r.reviewer || '-')}</div><div class="be-actions">${r.status === 'PENDING' ? `<button class="ghost small" onclick="decideBusinessEntityGovernance(${r.id}, 'approve')">Approve</button><button class="ghost small danger" onclick="decideBusinessEntityGovernance(${r.id}, 'reject')">Reject</button>` : ''}</div>`)}</div>
      </div>
    </div>
  </div>`;
}

function renderBusinessEntityFlowPane() {
  if (!businessEntityFlowDraft) {
    return `<div class="be-ops"><div class="empty">No flow loaded for this Business Entity yet.</div></div>`;
  }
  const f = businessEntityFlowDraft;
  return `<div class="be-ops">
    <div class="be-test-card">
      <b>How to use Flow Studio</b>
      <span>Build the enterprise run as a visual flow, save it, then use Debug dry-run to step through approvals, loops, fan-out, exception routes, and two-phase commit without changing target data.</span>
    </div>
    <div class="be-flow-studio">
      <div class="be-flow-side">
        <div class="be-flow-panel">
          <div class="be-members-head">
            <div><h3>Saved flows</h3><p class="sub tight">Reusable orchestration per Business Entity.</p></div>
            <button class="ghost small" onclick="newBusinessEntityFlow()">New</button>
          </div>
          <select id="be-flow-select" onchange="selectBusinessEntityFlow(this.value)">
            ${businessEntityFlows.length ? businessEntityFlows.map(flow => `<option value="${flow.id}"${String(flow.id) === String(selectedBusinessEntityFlowId) ? ' selected' : ''}>${esc(flow.name)} v${esc(flow.versionNo || 1)}</option>`).join('') : '<option value="">Unsaved starter flow</option>'}
          </select>
          <div class="be-flow-form">
            <label>Name</label><input id="be-flow-name" value="${esc(f.name || '')}" placeholder="Customer enterprise flow">
            <label>Description</label><textarea id="be-flow-desc" placeholder="What this orchestration controls">${esc(f.description || '')}</textarea>
            <label>Status</label><select id="be-flow-status">${['DRAFT','ACTIVE','RETIRED'].map(s => `<option value="${s}"${(f.status || 'DRAFT') === s ? ' selected' : ''}>${s}</option>`).join('')}</select>
          </div>
          <div class="be-actions">
            <button onclick="saveBusinessEntityFlow()">Save flow</button>
            ${f.id ? `<button class="ghost" onclick="validateBusinessEntityFlow()">Validate</button><button class="ghost" onclick="publishBusinessEntityFlow()">Publish</button>` : ''}
            ${f.id ? `<button class="ghost danger" onclick="deleteBusinessEntityFlow()">Delete</button>` : ''}
          </div>
          ${renderBusinessEntityFlowValidation()}
        </div>
        ${renderBusinessEntityFlowPalette()}
      </div>
      <div class="be-flow-main">
        ${renderBusinessEntityFlowCanvas()}
      </div>
      <div class="be-flow-side">
        ${renderBusinessEntityFlowInspector()}
        ${renderBusinessEntityFlowDebugger()}
      </div>
    </div>
  </div>`;
}

function renderBusinessEntityFlowPalette() {
  const items = [
    ['TRANSFORM', 'Reusable transform', 'Policy/function step from the shared library'],
    ['LOOP', 'Loop', 'Repeat over slices, members, or reserved keys'],
    ['EXCEPTION_HANDLER', 'Exception handler', 'Route failures to rollback/evidence'],
    ['TWO_PHASE_COMMIT', 'Two-phase commit', 'Prepare/commit/rollback coordination'],
    ['SYNTHETIC_LOOKALIKE', 'Synthetic step', 'Generate look-alike data when needed']
  ];
  return `<div class="be-flow-panel">
    <h3>Step palette</h3>
    <p class="sub tight">Add no-code building blocks to the graph.</p>
    <div class="be-flow-palette">
      ${items.map(([type, label, desc]) => `<button type="button" onclick="addBusinessEntityFlowStep('${type}')"><b>${esc(label)}</b><span>${esc(desc)}</span></button>`).join('')}
    </div>
    <div class="be-flow-library">
      <b>Reusable transformations</b>
      <button class="ghost small" onclick="addBusinessEntityFlowStep('TRANSFORM','Format-preserving masking',{transformationName:'FORMAT_PRESERVE', reusable:true})">Format preserve</button>
      <button class="ghost small" onclick="addBusinessEntityFlowStep('TRANSFORM','Eligibility filter',{transformationName:'ELIGIBILITY_FILTER', reusable:true})">Eligibility filter</button>
      <button class="ghost small" onclick="addBusinessEntityFlowStep('TRANSFORM','Cross-system key hash',{transformationName:'KEY_HASH', reusable:true})">Key hash</button>
    </div>
  </div>`;
}

function renderBusinessEntityFlowCanvas() {
  const flow = businessEntityFlowDraft || {};
  const nodes = flow.nodes || [];
  const edges = flow.edges || [];
  const events = new Map((businessEntityFlowDebugRun?.events || []).map(e => [String(e.stepKey), e]));
  const byKey = new Map(nodes.map(n => [String(n.key), n]));
  const width = Math.max(980, ...nodes.map(n => Number(n.x || 0) + 230));
  const height = Math.max(560, ...nodes.map(n => Number(n.y || 0) + 130));
  const lines = edges.map((e, idx) => {
    const a = byKey.get(String(e.from)), b = byKey.get(String(e.to));
    if (!a || !b) return '';
    const x1 = Number(a.x || 0) + 92, y1 = Number(a.y || 0) + 38;
    const x2 = Number(b.x || 0) + 92, y2 = Number(b.y || 0) + 38;
    const mid = `M ${x1} ${y1} C ${(x1 + x2) / 2} ${y1}, ${(x1 + x2) / 2} ${y2}, ${x2} ${y2}`;
    const err = String(e.condition || '').toUpperCase() === 'ERROR';
    return `<path d="${mid}" class="be-flow-edge ${err ? 'error' : ''}" marker-end="url(#be-flow-arrow)"></path>
      <text x="${(x1 + x2) / 2}" y="${(y1 + y2) / 2 - 4}" class="be-flow-edge-label">${esc(e.condition || 'SUCCESS')}</text>`;
  }).join('');
  const cards = nodes.map(n => {
    const ev = events.get(String(n.key));
    const type = String(n.type || 'STEP').toLowerCase().replace(/[^a-z0-9_-]+/g, '-');
    const selected = String(selectedBusinessEntityFlowNodeKey || '') === String(n.key);
    return `<div class="be-flow-node ${type} ${selected ? 'selected' : ''} ${ev ? String(ev.status || '').toLowerCase() : ''}" style="left:${Number(n.x || 0)}px;top:${Number(n.y || 0)}px" onclick="selectBusinessEntityFlowNode(${js(n.key)})">
      <div class="be-flow-node-head"><b>${esc(n.label || n.type || n.key)}</b>${ev ? statusPill(ev.status || 'PASSED') : ''}</div>
      <span>${esc(n.type || 'STEP')}</span>
      ${n.breakpoint ? '<em>breakpoint</em>' : ''}
    </div>`;
  }).join('');
  return `<div class="be-flow-canvas-wrap">
    <div class="be-flow-canvas" style="width:${width}px;height:${height}px">
      <svg width="${width}" height="${height}" class="be-flow-svg">
        <defs><marker id="be-flow-arrow" markerWidth="9" markerHeight="9" refX="7" refY="3" orient="auto"><path d="M0,0 L0,6 L8,3 z"></path></marker></defs>
        ${lines}
      </svg>
      ${cards}
    </div>
  </div>`;
}

function renderBusinessEntityFlowInspector() {
  const node = (businessEntityFlowDraft?.nodes || []).find(n => String(n.key) === String(selectedBusinessEntityFlowNodeKey));
  if (!node) return `<div class="be-flow-panel"><h3>Step inspector</h3><div class="empty small-empty">Select a step on the canvas.</div></div>`;
  return `<div class="be-flow-panel">
    <h3>Step inspector</h3>
    <div class="be-flow-form">
      <label>Step label</label><input id="be-flow-node-label" value="${esc(node.label || '')}">
      <label>Step type</label><input value="${esc(node.type || '')}" disabled>
      <label>Config JSON</label><textarea id="be-flow-node-config">${esc(JSON.stringify(node.config || {}, null, 2))}</textarea>
      <label class="inline-check"><input id="be-flow-node-breakpoint" type="checkbox" ${node.breakpoint ? 'checked' : ''}> Pause debugger here</label>
    </div>
    <div class="be-actions">
      <button class="ghost small" onclick="applyBusinessEntityFlowNode()">Apply</button>
      ${!['START','END'].includes(String(node.type || '').toUpperCase()) ? `<button class="ghost small danger" onclick="removeBusinessEntityFlowNode(${js(node.key)})">Remove</button>` : ''}
    </div>
  </div>`;
}

function renderBusinessEntityFlowDebugger() {
  const nodes = businessEntityFlowDraft?.nodes || [];
  const failOpts = '<option value="">No injected failure</option>' + nodes.map(n => `<option value="${esc(n.key)}">${esc(n.label || n.key)}</option>`).join('');
  return `<div class="be-flow-panel">
    <div class="be-members-head">
      <div><h3>Run control</h3><p class="sub tight">Debug is dry-run. Run approved calls the same governed BE execution launcher.</p></div>
    </div>
    <div class="be-flow-form">
      <label>Execution plan</label><select id="be-flow-run-plan">${enterprisePlanOptions()}</select>
      <label>Target DB override</label><select id="be-flow-run-target">${beTargetDataSourceOptions(null, 'Use blueprint target(s)')}</select>
      <label>Target schema</label><input id="be-flow-run-schema" placeholder="optional">
      <label>Seed</label><input id="be-flow-run-seed" placeholder="optional">
      <label>Rows / cap</label><input id="be-flow-run-rows" type="number" min="1" placeholder="optional">
      <label>Load</label><select id="be-flow-run-load"><option>REPLACE</option><option>INSERT</option><option>INSERT_UPDATE</option><option>UPDATE</option><option>TRUNCATE_ONLY</option></select>
      <label>Prep</label><select id="be-flow-run-prep"><option>DELETE</option><option>TRUNCATE</option><option>TRUNCATE_CASCADE</option><option>NONE</option></select>
      <label>Inject failure at step</label><select id="be-flow-fail-step">${failOpts}</select>
    </div>
    <div class="be-actions">
      <button class="ghost" onclick="debugBusinessEntityFlow()">Debug dry-run</button>
      <button onclick="runBusinessEntityFlow()">Run approved</button>
    </div>
    ${renderBusinessEntityFlowEvents()}
  </div>`;
}

function renderBusinessEntityFlowValidation() {
  const v = businessEntityFlowValidation;
  if (!v) return '';
  const rows = (v.findings || []).slice(0, 8).map(f => `<div class="be-flow-finding ${String(f.severity || '').toLowerCase()}">
    <b>${esc(f.severity || '')} / ${esc(f.code || '')}</b>
    <span>${esc(f.stepKey ? f.stepKey + ': ' : '')}${esc(f.message || '')}</span>
  </div>`).join('');
  return `<div class="be-flow-validation ${String(v.status || '').toLowerCase()}">
    <div>${statusPill(v.status || 'CHECK')}<b>Score ${esc(v.score ?? '-')}</b><span>${esc(v.summary || '')}</span></div>
    ${rows || '<div class="okbox">No validation findings.</div>'}
  </div>`;
}

function renderBusinessEntityFlowEvents() {
  const run = businessEntityFlowDebugRun;
  if (!run) return '<div class="empty small-empty">No debug run yet.</div>';
  const rows = (run.events || []).map(e => `<div class="be-flow-event ${String(e.status || '').toLowerCase()}">
    <div><b>${esc(e.sequence)}. ${esc(e.label || e.stepKey)}</b>${statusPill(e.status || 'PASSED')}</div>
    <p>${esc(e.message || '')}</p>
    <pre>${esc(JSON.stringify(e.details || {}, null, 2))}</pre>
  </div>`).join('');
  return `<div class="be-flow-run-head">${statusPill(run.status || 'COMPLETED')}<span>run #${esc(run.id || '-')} / ${esc(run.mode || 'DEBUG_DRY_RUN')}</span></div>
    <div class="be-flow-events">${rows}</div>`;
}

function renderBusinessEntityRunPane() {
  const e = businessEntityEnterprise || {};
  return `<div class="be-ops">
    <div class="be-test-card">
      <b>How to test this tab</b>
      <span>Create an execution plan in PLAN_ONLY mode first. SUBSET_MASK/ISSUE_RECREATE launch one DataScope run per application slice. Leave Target DB blank to use each blueprint target, or select a target to override all slices.</span>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Cross-system execution & operations</h3><p class="sub tight">Plan entity-level subset/mask/synthetic execution, choose loader strategy, and export scheduler packages.</p></div>
        <span class="pill dim">${(e.loaderStrategies || []).length} loader checks</span>
      </div>
      <div class="be-op-form">
        <div><label>Plan name</label><input id="be-plan-name" placeholder="Customer UAT release"></div>
        <div><label>Operation</label><select id="be-plan-op"><option>SUBSET_MASK</option><option>SYNTHETIC_LOOKALIKE</option><option>ISSUE_RECREATE</option></select></div>
        <div><label>Mode</label><select id="be-plan-mode"><option>PLAN_ONLY</option><option>APPROVED_RUN_READY</option></select></div>
        <div><label>Target env</label><input id="be-plan-target" placeholder="UAT"></div>
        <div class="grow"><label>Source env</label><input id="be-plan-source" placeholder="PROD / masked lower env"></div>
        <button onclick="createBusinessEntityExecutionPlan()">Create plan</button>
      </div>
      <div class="be-op-form compact">
        <div><label>Execution plan</label><select id="be-package-plan">${enterprisePlanOptions()}</select></div>
        <div class="grow"><label>Package name</label><input id="be-package-name" placeholder="Nightly scheduler package"></div>
        <button onclick="createBusinessEntityOperationalPackage()">Create package</button>
      </div>
      <div class="be-op-form compact">
        <div><label>Package lifecycle</label><select id="be-lifecycle-package">${enterprisePackageOptions()}</select></div>
        <div><label>Promote to</label><input id="be-promote-target" placeholder="QA / UAT / STAGE"></div>
        <button class="ghost" onclick="createBusinessEntityPackageVersion()">Create version</button>
        <button class="ghost" onclick="promoteBusinessEntityPackage()">Promote</button>
      </div>
      <div class="be-op-form compact">
        <div><label>Plan to launch</label><select id="be-launch-plan">${enterprisePlanOptions()}</select></div>
        <div><label>Target DB override</label><select id="be-launch-target">${beTargetDataSourceOptions(null, 'Use blueprint target(s)')}</select></div>
        <div><label>Target schema</label><input id="be-launch-schema" placeholder="public / UAT schema"></div>
        <div><label>Seed</label><input id="be-launch-seed" placeholder="optional"></div>
        <div><label>Rows / cap</label><input id="be-launch-rows" type="number" min="1" placeholder="optional"></div>
        <div><label>Load</label><select id="be-launch-load"><option>REPLACE</option><option>INSERT</option><option>INSERT_UPDATE</option><option>UPDATE</option><option>TRUNCATE_ONLY</option></select></div>
        <div><label>Prep</label><select id="be-launch-prep"><option>DELETE</option><option>TRUNCATE</option><option>TRUNCATE_CASCADE</option><option>NONE</option></select></div>
        <button onclick="launchBusinessEntityExecutionPlan()">Launch</button>
      </div>
      <div class="be-mini-grid">
        <div><b>Execution plans</b>${enterpriseRows(e.executionPlans, renderBusinessEntityExecutionPlanRow)}</div>
        <div><b>Operational packages</b>${enterpriseRows(e.operationalPackages, r => `<b>${esc(r.name)}</b> ${statusPill(r.status)}<div class="be-card-meta">${esc(r.packageType)} / plan #${esc(r.executionPlanId || '-')}</div><div class="be-actions"><button class="ghost small" onclick="viewBusinessEntityPackageScript(${r.id})">View runner</button></div>`)}</div>
      </div>
      <div id="be-package-script"></div>
    </div>
  </div>`;
}

function renderBusinessEntityEvidencePane() {
  const e = businessEntityEnterprise || {};
  return `<div class="be-ops">
    <div class="be-test-card">
      <b>What this tab proves</b>
      <span>This is the audit/evidence view: loader decisions, execution run links, immutable package versions, and promotion records.</span>
    </div>
    <div class="be-mini-grid">
      <div><b>Loader strategy evidence</b>${enterpriseRows(e.loaderStrategies, r => `${esc(r.role)} / ${esc(r.table)}<div class="be-card-meta">${esc(r.engine)} / ${esc(r.strategy)} / fallback ${esc(r.fallback)}</div>`)}</div>
      <div><b>Execution run history</b>${enterpriseRows(e.executionRuns, renderBusinessEntityRunEvidence)}</div>
      <div><b>Package versions</b>${enterpriseRows(e.packageVersions, r => `<b>v${esc(r.versionNumber)}</b> ${statusPill(r.status)}<div class="be-card-meta">package #${esc(r.packageId || '-')} - hash ${esc(String(r.artifactHash || '').slice(0, 12))} - retention ${esc(r.retentionUntil || '-')}</div>`)}</div>
      <div><b>Package promotions</b>${enterpriseRows(e.packagePromotions, r => `<b>${esc(r.fromEnvironment || '-')} -> ${esc(r.toEnvironment || '-')}</b> ${statusPill(r.status)}<div class="be-card-meta">package #${esc(r.packageId || '-')} - version #${esc(r.versionId || '-')} - approval ${esc(r.approvedRequestId || '-')}</div>`)}</div>
    </div>
  </div>`;
}

function beJson(value) {
  if (!value) return null;
  if (typeof value === 'object') return value;
  try { return JSON.parse(value); } catch { return null; }
}

function businessEntityDataScopeSlices(plan) {
  const planJson = beJson(plan?.planJson);
  if (Array.isArray(planJson?.applicationSlices) && planJson.applicationSlices.length) return planJson.applicationSlices;
  const detail = businessEntityDetail || {};
  const entity = detail.entity || {};
  const primaryDatasetId = entity.primaryDatasetId;
  const byDataset = new Map();
  (detail.members || []).forEach(m => {
    if (m.includeInSubset === false) return;
    const datasetId = m.datasetId || primaryDatasetId;
    if (!datasetId) return;
    const key = String(datasetId);
    if (!byDataset.has(key)) {
      byDataset.set(key, {
        datasetId,
        label: m.systemName || m.logicalRole || m.tableName || ('DataScope ' + datasetId),
        members: []
      });
    }
    byDataset.get(key).members.push(m);
  });
  if (!byDataset.size && primaryDatasetId) {
    byDataset.set(String(primaryDatasetId), { datasetId: primaryDatasetId, label: 'Primary DataScope blueprint', members: [] });
  }
  return [...byDataset.values()];
}

function renderBusinessEntityRunEvidence(r) {
  const result = beJson(r.launchResultJson) || {};
  const loader = beJson(r.loaderStrategyJson) || {};
  const status = r.status || r.engineStatus || result.status;
  const base = `<b>${esc(r.engine === 'DATASCOPE_FANOUT' ? 'DataScope fan-out' : r.engine)}</b> ${statusPill(status)}`;
  const meta = `<div class="be-card-meta">plan #${esc(r.executionPlanId || '-')} - run ${esc(r.engineRunId || result.runId || '-')} - ${esc(r.engineStatus || result.status || '-')}</div>`;
  if (r.engine === 'DATASCOPE_FANOUT' || result.fanOut || loader.fanOut) {
    const slices = result.slices || loader.slices || [];
    const childRuns = result.runs || loader.childRuns || [];
    const sliceText = slices.slice(0, 4).map(s => esc(s.label || s.datasetName || s.sliceKey || 'slice')).join(', ');
    const childText = childRuns.slice(0, 4).map(c => {
      const label = c.slice?.label || c.slice?.datasetName || c.sliceNo || 'slice';
      return `${esc(label)} -> ${esc(c.runId || '-')}`;
    }).join(' / ');
    return `${base}${meta}<div class="be-card-meta">${esc(result.sliceCount || loader.sliceCount || slices.length || childRuns.length || '-')} application slice(s)${sliceText ? ': ' + sliceText : ''}</div>${childText ? `<div class="be-card-meta">Child runs: ${childText}</div>` : ''}`;
  }
  if (r.engine === 'DATASCOPE' && result.slice) {
    const slice = result.slice;
    return `${base}${meta}<div class="be-card-meta">${esc(slice.label || slice.datasetName || 'application slice')} - source #${esc(result.sourceDataSourceId || slice.sourceDataSourceId || '-')} -> target ${esc(result.targetDataSourceName || slice.targetDataSourceName || result.targetDataSourceId || '-')}</div>`;
  }
  return `${base}${meta}`;
}

function renderBusinessEntityExecutionPlanRow(r) {
  const op = String(r.operationType || '').toUpperCase();
  const slices = ['SUBSET_MASK', 'ISSUE_RECREATE'].includes(op) ? businessEntityDataScopeSlices(r) : [];
  const sliceMeta = slices.length
    ? ` / ${slices.length} app slice${slices.length === 1 ? '' : 's'}`
    : '';
  return `<b>${esc(r.name)}</b> ${statusPill(r.status)}<div class="be-card-meta">${esc(r.operationType)} / ${esc(r.mode)} / target ${esc(r.targetEnvironment || '-')}${sliceMeta}</div>`;
}

function renderBusinessEntityOps(entity) {
  if (!entity?.id) {
    return `<div class="be-ops">
      <div class="empty">Save the Business Entity before creating snapshots or reservations.</div>
    </div>`;
  }
  return `<div class="be-ops">
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Snapshots & rollback</h3><p class="sub tight">Capture a point-in-time entity bookmark. Physical mode links to Virtualization snapshots for VDB rollback.</p></div>
        <span class="pill info">${businessEntitySnapshots.length} snapshot${businessEntitySnapshots.length === 1 ? '' : 's'}</span>
      </div>
      <div class="be-op-form">
        <div><label>Name</label><input id="be-snap-name" placeholder="${esc(entity.name || 'Entity')} baseline"></div>
        <div><label>Mode</label><select id="be-snap-mode"><option value="EVIDENCE_ONLY">Evidence only</option><option value="PHYSICAL_SNAPSHOT">Physical snapshot</option></select></div>
        <div><label>Retention days</label><input id="be-snap-retention" type="number" min="0" placeholder="optional"></div>
        <div class="grow"><label>Filter / evidence criteria</label><input id="be-snap-criteria" placeholder="status = 'ACTIVE'"></div>
        <div class="grow"><label>Note</label><input id="be-snap-note" placeholder="Why this point matters"></div>
        <button onclick="createBusinessEntitySnapshot()">Create snapshot</button>
      </div>
      <div id="be-snapshot-list">${renderBusinessEntitySnapshots()}</div>
    </div>
    <div class="be-op-card">
      <div class="be-members-head">
        <div><h3>Entity reservations</h3><p class="sub tight">Reserve complete business objects with TTL, conflict checks, and member-table key evidence.</p></div>
        <span class="pill warn">${businessEntityReservations.filter(r => r.status === 'ACTIVE').length} active</span>
      </div>
      <div class="be-op-form">
        <div><label>Name</label><input id="be-res-name" placeholder="QA cycle reservation"></div>
        <div><label>Count</label><input id="be-res-count" type="number" min="1" value="1"></div>
        <div><label>TTL hours</label><input id="be-res-ttl" type="number" min="1" value="24"></div>
        <div><label>Environment</label><input id="be-res-env" placeholder="UAT"></div>
        <div class="grow"><label>Criteria</label><input id="be-res-criteria" placeholder="region = 'US'"></div>
        <div class="grow"><label>Purpose</label><input id="be-res-purpose" placeholder="Test cycle / defect / team"></div>
        <button onclick="createBusinessEntityReservation()">Reserve entity</button>
      </div>
      <div id="be-reservation-list">${renderBusinessEntityReservations()}</div>
    </div>
    ${renderBusinessEntityEnterpriseOps()}
  </div>`;
}

function renderBusinessEntityEnterpriseOps() {
  const e = businessEntityEnterprise || {};
  return `<div class="be-op-card">
    <div class="be-members-head">
      <div><h3>Production issue recreation</h3><p class="sub tight">Capture the defect context and produce a replayable, privacy-safe data package.</p></div>
      <span class="pill info">${(e.issuePackages || []).length} package${(e.issuePackages || []).length === 1 ? '' : 's'}</span>
    </div>
    <div class="be-op-form">
      <div><label>Issue key</label><input id="be-issue-key" placeholder="INC-12345"></div>
      <div><label>Severity</label><select id="be-issue-sev"><option>MEDIUM</option><option>HIGH</option><option>CRITICAL</option><option>LOW</option></select></div>
      <div><label>Mode</label><select id="be-issue-mode"><option value="MASKED_SUBSET">Masked subset</option><option value="SYNTHETIC_REPLAY">Synthetic replay</option><option value="HYBRID">Hybrid</option></select></div>
      <div class="grow"><label>Title</label><input id="be-issue-title" placeholder="Payment fails for active customer"></div>
      <div><label>Target env</label><input id="be-issue-target" placeholder="UAT"></div>
      <button onclick="createBusinessEntityIssuePackage()">Create package</button>
    </div>
    ${enterpriseRows(e.issuePackages, r => `<b>${esc(r.issueKey)}</b> ${esc(r.title || '')}<div class="be-card-meta">${esc(r.recreationMode)} · ${esc(r.privacyAction)} · ${esc(r.status)} · ${esc(r.approvalStatus)}</div>`)}
  </div>
  <div class="be-op-card">
    <div class="be-members-head">
      <div><h3>AI-assisted look-alike planning</h3><p class="sub tight">Generate a metadata-only synthetic plan. No raw source values are stored in the profile.</p></div>
      <span class="pill info">${(e.lookalikeProfiles || []).length} profile${(e.lookalikeProfiles || []).length === 1 ? '' : 's'}</span>
    </div>
    <div class="be-op-form">
      <div><label>Name</label><input id="be-look-name" placeholder="Customer UAT look-alike"></div>
      <div><label>Rows</label><input id="be-look-rows" type="number" min="1" value="1000"></div>
      <div><label>Privacy</label><select id="be-look-privacy"><option value="NO_RAW_VALUES">No raw values</option><option value="BANKING_SAFE_PROFILE">Banking safe profile</option></select></div>
      <div class="grow"><label>Objective</label><input id="be-look-objective" placeholder="Maintain account/customer shape for UAT"></div>
      <button onclick="createBusinessEntityLookalike()">Create plan</button>
    </div>
    ${enterpriseRows(e.lookalikeProfiles, r => `<b>${esc(r.name)}</b><div class="be-card-meta">${esc(r.privacyMode)} · ${r.rowGoal || 0} rows · ${esc(r.status)}</div>`)}
  </div>
  <div class="be-op-card">
    <div class="be-members-head">
      <div><h3>Catalog & governance</h3><p class="sub tight">Publish lineage/certification metadata and enforce maker-checker sign-off for releases.</p></div>
      <button class="ghost small" onclick="syncBusinessEntityCatalog()">Sync catalog</button>
    </div>
    <div class="be-op-form">
      <div><label>Object type</label><select id="be-gov-type"><option>BUSINESS_ENTITY</option><option>ISSUE_PACKAGE</option><option>EXECUTION_PLAN</option><option>OPERATIONAL_PACKAGE</option></select></div>
      <div><label>Action</label><select id="be-gov-action"><option>RELEASE</option><option>RUN</option><option>EXPORT</option><option>PROMOTE</option></select></div>
      <div><label>Risk</label><select id="be-gov-risk"><option>MEDIUM</option><option>HIGH</option><option>CRITICAL</option><option>LOW</option></select></div>
      <div><label>Reviewer</label><input id="be-gov-reviewer" placeholder="checker username"></div>
      <div class="grow"><label>Comments</label><input id="be-gov-comments" placeholder="Release reason and evidence"></div>
      <button onclick="createBusinessEntityGovernance()">Request approval</button>
    </div>
    <div class="be-mini-grid">
      <div><b>Catalog</b>${enterpriseRows(e.catalogAssets, r => `${esc(r.assetType)} · <b>${esc(r.displayName)}</b><div class="be-card-meta">${esc(r.certificationStatus)} · score ${esc(r.qualityScore ?? '')}</div>`)}</div>
      <div><b>Approvals</b>${enterpriseRows(e.governanceRequests, r => `<b>${esc(r.action)}</b> ${statusPill(r.status)}<div class="be-card-meta">${esc(r.objectType)} · risk ${esc(r.riskLevel)} · reviewer ${esc(r.reviewer || '-')}</div><div class="be-actions">${r.status === 'PENDING' ? `<button class="ghost small" onclick="decideBusinessEntityGovernance(${r.id}, 'approve')">Approve</button><button class="ghost small danger" onclick="decideBusinessEntityGovernance(${r.id}, 'reject')">Reject</button>` : ''}</div>`)}</div>
    </div>
  </div>
  <div class="be-op-card">
    <div class="be-members-head">
      <div><h3>Cross-system execution & operations</h3><p class="sub tight">Plan entity-level subset/mask/synthetic execution, choose loader strategy, and export scheduler packages.</p></div>
      <span class="pill dim">${(e.loaderStrategies || []).length} loader checks</span>
    </div>
    <div class="be-op-form">
      <div><label>Plan name</label><input id="be-plan-name" placeholder="Customer UAT release"></div>
      <div><label>Operation</label><select id="be-plan-op"><option>SUBSET_MASK</option><option>SYNTHETIC_LOOKALIKE</option><option>ISSUE_RECREATE</option></select></div>
      <div><label>Mode</label><select id="be-plan-mode"><option>PLAN_ONLY</option><option>APPROVED_RUN_READY</option></select></div>
      <div><label>Target env</label><input id="be-plan-target" placeholder="UAT"></div>
      <div class="grow"><label>Source env</label><input id="be-plan-source" placeholder="PROD / masked lower env"></div>
      <button onclick="createBusinessEntityExecutionPlan()">Create plan</button>
    </div>
    <div class="be-op-form compact">
      <div><label>Execution plan</label><select id="be-package-plan">${enterprisePlanOptions()}</select></div>
      <div class="grow"><label>Package name</label><input id="be-package-name" placeholder="Nightly scheduler package"></div>
      <button onclick="createBusinessEntityOperationalPackage()">Create package</button>
    </div>
    <div class="be-op-form compact">
      <div><label>Package lifecycle</label><select id="be-lifecycle-package">${enterprisePackageOptions()}</select></div>
      <div><label>Promote to</label><input id="be-promote-target" placeholder="QA / UAT / STAGE"></div>
      <button class="ghost" onclick="createBusinessEntityPackageVersion()">Create version</button>
      <button class="ghost" onclick="promoteBusinessEntityPackage()">Promote</button>
    </div>
    <div class="be-op-form compact">
      <div><label>Plan to launch</label><select id="be-launch-plan">${enterprisePlanOptions()}</select></div>
      <div><label>Target DB override</label><select id="be-launch-target">${beTargetDataSourceOptions(null, 'Use blueprint target(s)')}</select></div>
      <div><label>Target schema</label><input id="be-launch-schema" placeholder="public / UAT schema"></div>
      <div><label>Seed</label><input id="be-launch-seed" placeholder="optional"></div>
      <div><label>Rows / cap</label><input id="be-launch-rows" type="number" min="1" placeholder="optional"></div>
      <div><label>Load</label><select id="be-launch-load"><option>REPLACE</option><option>INSERT</option><option>INSERT_UPDATE</option><option>UPDATE</option><option>TRUNCATE_ONLY</option></select></div>
      <div><label>Prep</label><select id="be-launch-prep"><option>DELETE</option><option>TRUNCATE</option><option>TRUNCATE_CASCADE</option><option>NONE</option></select></div>
      <button onclick="launchBusinessEntityExecutionPlan()">Launch</button>
    </div>
    <div class="be-mini-grid">
      <div><b>Execution plans</b>${enterpriseRows(e.executionPlans, renderBusinessEntityExecutionPlanRow)}</div>
      <div><b>Operational packages</b>${enterpriseRows(e.operationalPackages, r => `<b>${esc(r.name)}</b> ${statusPill(r.status)}<div class="be-card-meta">${esc(r.packageType)} · plan #${esc(r.executionPlanId || '-')}</div><div class="be-actions"><button class="ghost small" onclick="viewBusinessEntityPackageScript(${r.id})">View runner</button></div>`)}</div>
    </div>
    <div><b>Loader strategy evidence</b>${enterpriseRows(e.loaderStrategies, r => `${esc(r.role)} / ${esc(r.table)}<div class="be-card-meta">${esc(r.engine)} · ${esc(r.strategy)} · fallback ${esc(r.fallback)}</div>`)}</div>
    <div><b>Execution run history</b>${enterpriseRows(e.executionRuns, renderBusinessEntityRunEvidence)}</div>
    <div><b>Package versions</b>${enterpriseRows(e.packageVersions, r => `<b>v${esc(r.versionNumber)}</b> ${statusPill(r.status)}<div class="be-card-meta">package #${esc(r.packageId || '-')} - hash ${esc(String(r.artifactHash || '').slice(0, 12))} - retention ${esc(r.retentionUntil || '-')}</div>`)}</div>
    <div><b>Package promotions</b>${enterpriseRows(e.packagePromotions, r => `<b>${esc(r.fromEnvironment || '-')} -> ${esc(r.toEnvironment || '-')}</b> ${statusPill(r.status)}<div class="be-card-meta">package #${esc(r.packageId || '-')} - version #${esc(r.versionId || '-')} - approval ${esc(r.approvedRequestId || '-')}</div>`)}</div>
    <div id="be-package-script"></div>
  </div>`;
}

function enterpriseRows(rows, render) {
  rows = rows || [];
  if (!rows.length) return '<div class="empty small-empty">None yet.</div>';
  return `<div class="be-op-list compact-list">` + rows.slice(0, 8).map(r => `<div class="be-op-row compact-row"><div>${render(r)}</div></div>`).join('') + '</div>';
}

function enterprisePlanOptions() {
  const rows = businessEntityEnterprise?.executionPlans || [];
  return '<option value="">Select plan</option>' + rows.map(p => `<option value="${p.id}">${esc(p.name)} (#${p.id})</option>`).join('');
}

function enterprisePackageOptions() {
  const rows = businessEntityEnterprise?.operationalPackages || [];
  return '<option value="">Select package</option>' + rows.map(p => `<option value="${p.id}">${esc(p.name)} (#${p.id})</option>`).join('');
}

function renderBusinessEntitySnapshots() {
  if (!businessEntitySnapshots.length) return '<div class="empty">No snapshots captured yet.</div>';
  return `<div class="be-op-list">` + businessEntitySnapshots.map(s => `
    <div class="be-op-row">
      <div>
        <b>${esc(s.name)}</b>
        <div class="be-card-meta">#${s.id} · ${esc(s.captureMode)} · ${esc(s.status)} · ${s.totalMembers || 0} members · ${s.linkedVirtualSnapshots || 0} physical links · ${s.totalRows || 0} rows</div>
        <div class="be-card-meta">${s.retentionUntil ? 'retention until ' + esc(new Date(s.retentionUntil).toLocaleString()) : 'no retention date'}${s.note ? ' · ' + esc(s.note) : ''}</div>
      </div>
      <div class="be-actions">
        <button class="ghost small" onclick="previewBusinessEntityRollback(${s.id})">Rollback preview</button>
      </div>
      <div class="be-rollback" id="be-rb-${s.id}"></div>
    </div>`).join('') + `</div>`;
}

function renderBusinessEntityReservations() {
  if (!businessEntityReservations.length) return '<div class="empty">No entity reservations yet.</div>';
  return `<div class="be-op-list">` + businessEntityReservations.map(r => `
    <div class="be-op-row">
      <div>
        <b>${esc(r.name || ('Reservation #' + r.id))}</b> ${statusPill(r.status)}
        <div class="be-card-meta">#${r.id} · by ${esc(r.reservedBy)} · ${r.requestedCount || 0} key(s) · expires ${esc(new Date(r.expiresAt).toLocaleString())}</div>
        <div class="be-card-meta">${esc(r.environment || 'no environment')}${r.purpose ? ' · ' + esc(r.purpose) : ''}</div>
      </div>
      <div class="be-actions">
        ${r.status === 'ACTIVE' ? `<button class="ghost small danger" onclick="releaseBusinessEntityReservation(${r.id})">Release</button>` : ''}
      </div>
    </div>`).join('') + `</div>`;
}

function beDataSourceOptions(selected) {
  return '<option value="">No source</option>' + dataSources.map(d =>
    `<option value="${d.id}"${String(selected ?? '') === String(d.id) ? ' selected' : ''}>${esc(d.name)} (${esc(d.kind || 'DB')})</option>`).join('');
}

function businessEntityMemberOptions(selected) {
  const rows = businessEntityDetail?.members || [];
  return '<option value="">Manual / no member</option>' + rows.map(m =>
    `<option value="${m.id}"${String(selected ?? '') === String(m.id) ? ' selected' : ''}>${esc(m.systemName || dataSourceName(m.dataSourceId) || m.logicalRole || 'member')} / ${esc(m.tableName || '')}</option>`).join('');
}

function beTargetDataSourceOptions(selected, emptyLabel) {
  const rows = dataSources.filter(d => ['TARGET', 'BOTH'].includes(String(d.role || '').toUpperCase()));
  return `<option value="">${esc(emptyLabel || 'Select target')}</option>` + rows.map(d =>
    `<option value="${d.id}"${String(selected ?? '') === String(d.id) ? ' selected' : ''}>${esc(d.name)} (${esc(d.kind || 'DB')})</option>`).join('');
}

function beFlowToDraft(flow) {
  const canvas = beJson(flow?.canvasJson) || {};
  return {
    id: flow?.id || null,
    name: flow?.name || canvas.name || '',
    description: flow?.description || canvas.description || '',
    status: flow?.status || 'DRAFT',
    versionNo: flow?.versionNo || 1,
    nodes: JSON.parse(JSON.stringify(flow?.nodes || canvas.nodes || [])),
    edges: JSON.parse(JSON.stringify(flow?.edges || canvas.edges || [])),
    settings: JSON.parse(JSON.stringify(flow?.settings || canvas.settings || {}))
  };
}

function beFlowStepLabel(type) {
  return ({
    TRANSFORM: 'Reusable transformation',
    LOOP: 'Loop over participants',
    EXCEPTION_HANDLER: 'Exception handler',
    TWO_PHASE_COMMIT: 'Two-phase commit',
    SYNTHETIC_LOOKALIKE: 'Synthetic look-alike',
    DATASCOPE_FANOUT: 'DataScope fan-out',
    EXECUTION_PLAN: 'Execution plan launch'
  })[type] || type;
}

async function selectBusinessEntityFlow(id) {
  const flow = (businessEntityFlows || []).find(f => String(f.id) === String(id));
  if (!flow) return;
  selectedBusinessEntityFlowId = flow.id;
  businessEntityFlowDraft = beFlowToDraft(flow);
  selectedBusinessEntityFlowNodeKey = businessEntityFlowDraft.nodes?.[0]?.key || null;
  businessEntityFlowDebugRun = null;
  try {
    const runs = await api.get('/api/business-entities/flows/' + flow.id + '/debug-runs');
    businessEntityFlowDebugRun = runs?.[0] || null;
  } catch {}
  renderBusinessEntityDetail();
}

async function newBusinessEntityFlow() {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  try {
    const starter = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/flows/starter');
    businessEntityFlowDraft = beFlowToDraft(starter);
    businessEntityFlowDraft.id = null;
    selectedBusinessEntityFlowId = null;
    selectedBusinessEntityFlowNodeKey = businessEntityFlowDraft.nodes?.[0]?.key || null;
    businessEntityFlowDebugRun = null;
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

function collectBusinessEntityFlowDraftFromForm() {
  if (!businessEntityFlowDraft) return null;
  businessEntityFlowDraft.name = $('be-flow-name')?.value.trim() || businessEntityFlowDraft.name || 'Enterprise flow';
  businessEntityFlowDraft.description = $('be-flow-desc')?.value.trim() || null;
  businessEntityFlowDraft.status = $('be-flow-status')?.value || 'DRAFT';
  return businessEntityFlowDraft;
}

async function saveBusinessEntityFlow(showToast = true) {
  if (!selectedBusinessEntityId) return null;
  const draft = collectBusinessEntityFlowDraftFromForm();
  if (!draft) return null;
  try {
    const saved = await api.post('/api/business-entities/' + selectedBusinessEntityId + '/flows', {
      id: draft.id || null,
      name: draft.name,
      description: draft.description,
      status: draft.status,
      nodes: draft.nodes || [],
      edges: draft.edges || [],
      settings: draft.settings || {}
    });
    businessEntityFlows = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/flows');
    selectedBusinessEntityFlowId = saved.id;
    businessEntityFlowDraft = beFlowToDraft(saved);
    selectedBusinessEntityFlowNodeKey = selectedBusinessEntityFlowNodeKey || businessEntityFlowDraft.nodes?.[0]?.key || null;
    businessEntityFlowValidation = null;
    if (showToast) toast('Flow saved', 'ok');
    renderBusinessEntityDetail();
    return saved;
  } catch (e) {
    toast(e.message, 'err');
    return null;
  }
}

async function validateBusinessEntityFlow() {
  const saved = await saveBusinessEntityFlow(false);
  if (!saved?.id) return;
  try {
    businessEntityFlowValidation = await api.post('/api/business-entities/flows/' + saved.id + '/validate', {});
    toast(`Flow validation ${businessEntityFlowValidation.status}: score ${businessEntityFlowValidation.score}`, businessEntityFlowValidation.status === 'PASS' ? 'ok' : 'err');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

async function publishBusinessEntityFlow() {
  const saved = await saveBusinessEntityFlow(false);
  if (!saved?.id) return;
  try {
    businessEntityFlowValidation = await api.post('/api/business-entities/flows/' + saved.id + '/validate', {});
    if (businessEntityFlowValidation.status !== 'PASS') {
      renderBusinessEntityDetail();
      return toast('Fix critical validation findings before publish', 'err');
    }
    const published = await api.post('/api/business-entities/flows/' + saved.id + '/publish', {});
    businessEntityFlows = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/flows');
    selectedBusinessEntityFlowId = published.id;
    businessEntityFlowDraft = beFlowToDraft(published);
    toast('Flow published and ready for governed execution', 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteBusinessEntityFlow() {
  const id = businessEntityFlowDraft?.id;
  if (!id) return;
  if (!await uiConfirm('Delete this orchestration flow?', { danger: true, okText: 'Delete' })) return;
  try {
    await api.del('/api/business-entities/flows/' + id);
    businessEntityFlows = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/flows');
    await hydrateBusinessEntityFlowDraft();
    toast('Flow deleted', 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

function selectBusinessEntityFlowNode(key) {
  selectedBusinessEntityFlowNodeKey = key;
  renderBusinessEntityDetail();
}

function applyBusinessEntityFlowNode() {
  const node = (businessEntityFlowDraft?.nodes || []).find(n => String(n.key) === String(selectedBusinessEntityFlowNodeKey));
  if (!node) return;
  node.label = $('be-flow-node-label')?.value.trim() || node.label;
  try {
    node.config = JSON.parse($('be-flow-node-config')?.value || '{}');
  } catch (e) {
    return toast('Config JSON is not valid: ' + e.message, 'err');
  }
  node.breakpoint = !!$('be-flow-node-breakpoint')?.checked;
  renderBusinessEntityDetail();
}

function addBusinessEntityFlowStep(type, label, config) {
  if (!businessEntityFlowDraft) return;
  collectBusinessEntityFlowDraftFromForm();
  type = String(type || 'TRANSFORM').toUpperCase();
  const key = type.toLowerCase().replace(/[^a-z0-9]+/g, '-') + '-' + Date.now();
  const nodes = businessEntityFlowDraft.nodes || (businessEntityFlowDraft.nodes = []);
  const edges = businessEntityFlowDraft.edges || (businessEntityFlowDraft.edges = []);
  const end = nodes.find(n => String(n.type || '').toUpperCase() === 'END');
  const prior = [...nodes].reverse().find(n => !['END','EXCEPTION_HANDLER'].includes(String(n.type || '').toUpperCase())) || nodes[nodes.length - 1];
  const x = 28 + ((nodes.length % 4) * 235);
  const y = 560 + (Math.floor(nodes.length / 4) * 125);
  nodes.push({ key, type, label: label || beFlowStepLabel(type), x, y, config: config || defaultBusinessEntityFlowStepConfig(type) });
  if (prior && end) {
    const old = edges.findIndex(e => String(e.from) === String(prior.key) && String(e.to) === String(end.key) && String(e.condition || 'SUCCESS').toUpperCase() === 'SUCCESS');
    if (old >= 0) edges.splice(old, 1);
    edges.push({ from: prior.key, to: key, condition: 'SUCCESS' });
    edges.push({ from: key, to: end.key, condition: 'SUCCESS' });
  }
  selectedBusinessEntityFlowNodeKey = key;
  renderBusinessEntityDetail();
}

function defaultBusinessEntityFlowStepConfig(type) {
  if (type === 'LOOP') return { loopMode: 'FOR_EACH_APPLICATION_SLICE', iterations: businessEntityDataScopeSlices().length || 1 };
  if (type === 'TWO_PHASE_COMMIT') return { prepare: true, rollbackOnFailure: true };
  if (type === 'EXCEPTION_HANDLER') return { onFailure: 'capture evidence and rollback prepared participants' };
  if (type === 'SYNTHETIC_LOOKALIKE') return { profile: 'selected look-alike profile', dryRunOnlyInDebugger: true };
  return { transformationName: 'CUSTOM_TRANSFORM', reusable: true };
}

function removeBusinessEntityFlowNode(key) {
  const node = (businessEntityFlowDraft?.nodes || []).find(n => String(n.key) === String(key));
  if (!node || ['START','END'].includes(String(node.type || '').toUpperCase())) return;
  businessEntityFlowDraft.nodes = businessEntityFlowDraft.nodes.filter(n => String(n.key) !== String(key));
  businessEntityFlowDraft.edges = (businessEntityFlowDraft.edges || []).filter(e => String(e.from) !== String(key) && String(e.to) !== String(key));
  selectedBusinessEntityFlowNodeKey = businessEntityFlowDraft.nodes?.[0]?.key || null;
  renderBusinessEntityDetail();
}

async function debugBusinessEntityFlow() {
  const failStepKey = $('be-flow-fail-step')?.value || null;
  const saved = await saveBusinessEntityFlow(false);
  if (!saved?.id) return;
  const draft = businessEntityFlowDraft || beFlowToDraft(saved);
  const breakpoints = (draft.nodes || []).filter(n => n.breakpoint).map(n => n.key);
  try {
    businessEntityFlowDebugRun = await api.post('/api/business-entities/flows/' + saved.id + '/debug', {
      mode: 'DEBUG_DRY_RUN',
      failStepKey,
      breakpoints,
      inputs: { entityId: selectedBusinessEntityId, flowVersion: draft.versionNo || saved.versionNo || 1 }
    });
    toast('Debug run completed: ' + (businessEntityFlowDebugRun.status || 'COMPLETED'), 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

async function runBusinessEntityFlow() {
  const planId = beInt($('be-flow-run-plan')?.value);
  const targetDataSourceId = beInt($('be-flow-run-target')?.value);
  const rows = beInt($('be-flow-run-rows')?.value);
  const seed = beInt($('be-flow-run-seed')?.value);
  const targetSchema = $('be-flow-run-schema')?.value.trim() || null;
  const seedText = $('be-flow-run-seed')?.value.trim() || null;
  const loadAction = $('be-flow-run-load')?.value || 'REPLACE';
  const targetPrep = $('be-flow-run-prep')?.value || 'DELETE';
  const saved = await saveBusinessEntityFlow(false);
  if (!saved?.id) return;
  if (!planId) return toast('Choose an approved execution plan for the flow run', 'err');
  const plan = (businessEntityEnterprise?.executionPlans || []).find(p => String(p.id) === String(planId));
  if (plan && !['APPROVED', 'SUBMITTED'].includes(String(plan.status || '').toUpperCase())) {
    return toast('Approve this execution plan before running the flow', 'err');
  }
  if (String(saved.status || businessEntityFlowDraft?.status || '').toUpperCase() !== 'ACTIVE') {
    return toast('Publish the flow before approved physical execution', 'err');
  }
  if (!await uiConfirm(`Run this published orchestration flow using execution plan #${planId}?\n\nThis can submit real DataScope/Synthetic jobs through the governed backend launcher.`, { okText: 'Run' })) return;
  try {
    businessEntityFlowDebugRun = await api.post('/api/business-entities/flows/' + saved.id + '/run', {
      mode: 'EXECUTE_APPROVED',
      executionPlanId: planId,
      targetDataSourceId: targetDataSourceId || null,
      targetSchema,
      seed: seed || null,
      maskingSeed: seedText,
      rowCount: rows || null,
      maxRows: rows || null,
      loadAction,
      targetPrep,
      inputs: { entityId: selectedBusinessEntityId, planId }
    });
    await refreshBusinessEntityEnterprise();
    toast(`Flow run ${businessEntityFlowDebugRun.status || 'submitted'}`, 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

function beDatasetOptions(selected, emptyLabel) {
  return `<option value="">${esc(emptyLabel || 'None')}</option>` + datasetsList.map(d =>
    `<option value="${d.id}"${String(selected ?? '') === String(d.id) ? ' selected' : ''}>${esc(d.name)}</option>`).join('');
}

function addBusinessEntityMember() {
  if (!businessEntityDetail) resetBusinessEntityForm(false);
  businessEntityDetail.members = collectBusinessEntityMembers();
  businessEntityDetail.members.push({ includeInSubset: true, includeInSynthetic: true });
  renderBusinessEntityDetail();
}

function removeBusinessEntityMember(idx) {
  if (!businessEntityDetail) return;
  businessEntityDetail.members = collectBusinessEntityMembers().filter((_, i) => i !== idx);
  renderBusinessEntityDetail();
}

async function createBusinessEntityFromDataset() {
  const datasetId = $('be-from-dataset')?.value;
  if (!datasetId) { toast('Choose a DataScope blueprint first', 'err'); return; }
  try {
    businessEntityDetail = await api.post('/api/business-entities/from-dataset/' + datasetId, {
      name: $('be-from-name')?.value.trim() || null,
      domain: $('be-from-domain')?.value.trim() || null,
      description: $('be-from-desc')?.value.trim() || null
    });
    selectedBusinessEntityId = businessEntityDetail.entity.id;
    businessEntityActiveTab = 'model';
    localStorage.setItem('forgetdm.be.tab', businessEntityActiveTab);
    ['be-from-name','be-from-domain','be-from-desc'].forEach(id => { if ($(id)) $(id).value = ''; });
    businessEntities = await api.get('/api/business-entities');
    renderBusinessEntities();
    renderBusinessEntityDetail();
    toast('Business Entity created from DataScope', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function loadBusinessEntityIdentities() {
  if (!selectedBusinessEntityId) return;
  const q = $('be-id-search')?.value.trim();
  try {
    businessEntityIdentities = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/identities' + (q ? '?q=' + encodeURIComponent(q) : ''));
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

function collectBusinessEntityIdentityLinks() {
  const rows = [...document.querySelectorAll('[data-id-link-row]')];
  return rows.map(row => {
    const memberId = beInt(row.querySelector('.be-id-link-member')?.value);
    const member = (businessEntityDetail?.members || []).find(m => String(m.id) === String(memberId));
    const externalId = row.querySelector('.be-id-link-external')?.value.trim();
    if (!externalId) return null;
    return {
      memberId: memberId || null,
      systemName: row.querySelector('.be-id-link-system')?.value.trim() || member?.systemName || null,
      dataSourceId: member?.dataSourceId || null,
      schemaName: member?.schemaName || null,
      tableName: row.querySelector('.be-id-link-table')?.value.trim() || member?.tableName || null,
      logicalRole: row.querySelector('.be-id-link-role')?.value.trim() || member?.logicalRole || null,
      keyColumns: row.querySelector('.be-id-link-cols')?.value.trim() || member?.keyColumns || null,
      externalId,
      matchRule: row.querySelector('.be-id-link-rule')?.value.trim() || 'MANUAL',
      confidence: 1,
      status: 'ACTIVE',
      source: 'BUSINESS_ENTITY_UI'
    };
  }).filter(Boolean);
}

async function saveBusinessEntityIdentity() {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  const canonicalKey = $('be-id-canonical')?.value.trim();
  if (!canonicalKey) return toast('Canonical key is required', 'err');
  const links = collectBusinessEntityIdentityLinks();
  if (!links.length) return toast('Enter at least one system identifier link', 'err');
  try {
    const saved = await api.post('/api/business-entities/' + selectedBusinessEntityId + '/identities', {
      canonicalKey,
      identityType: $('be-id-type')?.value || 'CUSTOMER',
      status: $('be-id-status')?.value || 'ACTIVE',
      confidence: parseFloat($('be-id-confidence')?.value || '1'),
      attributes: { createdFrom: 'Business Entity Identity tab' },
      links
    });
    businessEntityIdentities = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/identities');
    businessEntityIdentityResolve = { matched: true, crosswalk: saved };
    toast('Identity crosswalk saved', 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

async function resolveBusinessEntityIdentity() {
  if (!selectedBusinessEntityId) return;
  const memberId = beInt($('be-id-res-member')?.value);
  const member = (businessEntityDetail?.members || []).find(m => String(m.id) === String(memberId));
  const externalId = $('be-id-res-external')?.value.trim();
  if (!externalId) return toast('Enter a system external id to resolve', 'err');
  try {
    businessEntityIdentityResolve = await api.post('/api/business-entities/' + selectedBusinessEntityId + '/identities/resolve', {
      memberId: memberId || null,
      systemName: member?.systemName || null,
      dataSourceId: member?.dataSourceId || null,
      schemaName: member?.schemaName || null,
      tableName: member?.tableName || null,
      keyColumns: member?.keyColumns || null,
      externalId
    });
    toast(businessEntityIdentityResolve.matched ? 'Identity resolved' : 'No identity match found', businessEntityIdentityResolve.matched ? 'ok' : 'err');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteBusinessEntityIdentity(id) {
  if (!await uiConfirm('Delete this canonical identity crosswalk?', { danger: true, okText: 'Delete' })) return;
  try {
    await api.del('/api/business-entities/identities/' + id);
    businessEntityIdentities = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/identities');
    businessEntityIdentityResolve = null;
    toast('Identity deleted', 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteBusinessEntityIdentityLink(id) {
  if (!await uiConfirm('Delete this system identity link?', { danger: true, okText: 'Delete' })) return;
  try {
    await api.del('/api/business-entities/identity-links/' + id);
    businessEntityIdentities = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/identities');
    businessEntityIdentityResolve = null;
    toast('Identity link deleted', 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

function newBusinessEntitySyncPolicy() {
  selectedBusinessEntitySyncPolicyId = null;
  businessEntitySyncRun = null;
  renderBusinessEntityDetail();
}

function selectBusinessEntitySyncPolicy(id) {
  selectedBusinessEntitySyncPolicyId = id || null;
  businessEntitySyncRun = null;
  renderBusinessEntityDetail();
}

function applyBusinessEntitySyncMember(idx, memberId) {
  const row = document.querySelector(`[data-be-sync-member="${idx}"]`);
  const member = (businessEntityDetail?.members || []).find(m => String(m.id) === String(memberId));
  if (!row || !member) return;
  const set = (sel, value) => { const el = row.querySelector(sel); if (el) el.value = value ?? ''; };
  set('.be-sync-system', member.systemName || dataSourceName(member.dataSourceId) || member.logicalRole || '');
  set('.be-sync-source', member.dataSourceId || '');
  set('.be-sync-schema', member.schemaName || '');
  set('.be-sync-table', member.tableName || '');
  set('.be-sync-keys', member.keyColumns || '');
}

function addBusinessEntitySyncMember() {
  const box = $('be-sync-members');
  if (!box) return;
  if (box.querySelector('.empty')) box.innerHTML = '';
  const idx = Date.now();
  box.insertAdjacentHTML('beforeend', renderBusinessEntitySyncMemberInput({
    memberId: null,
    systemName: '',
    dataSourceId: null,
    schemaName: '',
    tableName: '',
    keyColumns: '',
    watermarkColumn: '',
    maxLagSeconds: '',
    syncMode: 'POLLING',
    queryFilter: ''
  }, idx));
}

function removeBusinessEntitySyncMember(btn) {
  btn?.closest('[data-be-sync-member]')?.remove();
}

function collectBusinessEntitySyncMembers() {
  return [...document.querySelectorAll('[data-be-sync-member]')].map(row => ({
    memberId: beInt(row.querySelector('.be-sync-member')?.value),
    systemName: row.querySelector('.be-sync-system')?.value.trim() || null,
    dataSourceId: beInt(row.querySelector('.be-sync-source')?.value),
    schemaName: row.querySelector('.be-sync-schema')?.value.trim() || null,
    tableName: row.querySelector('.be-sync-table')?.value.trim() || null,
    logicalRole: null,
    keyColumns: row.querySelector('.be-sync-keys')?.value.trim() || null,
    watermarkColumn: row.querySelector('.be-sync-watermark')?.value.trim() || null,
    maxLagSeconds: beInt(row.querySelector('.be-sync-member-lag')?.value),
    syncMode: row.querySelector('.be-sync-member-mode')?.value || 'POLLING',
    queryFilter: row.querySelector('.be-sync-filter')?.value.trim() || null
  })).filter(m => m.tableName);
}

function collectBusinessEntitySyncPolicy() {
  return {
    id: beInt($('be-sync-id')?.value),
    name: $('be-sync-name')?.value.trim() || ((businessEntityDetail?.entity?.name || 'Business Entity') + ' freshness'),
    syncMode: $('be-sync-mode')?.value || 'POLLING',
    status: $('be-sync-status')?.value || 'ACTIVE',
    maxLagSeconds: beInt($('be-sync-lag')?.value) || 900,
    scheduleCron: $('be-sync-cron')?.value.trim() || null,
    syncStrategy: $('be-sync-strategy')?.value || 'FRESHNESS_CHECK',
    autoRefreshEnabled: !!$('be-sync-auto')?.checked,
    notes: $('be-sync-notes')?.value.trim() || null,
    members: collectBusinessEntitySyncMembers()
  };
}

async function loadBusinessEntitySyncPolicies() {
  if (!selectedBusinessEntityId) return;
  businessEntitySyncPolicies = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/sync-policies');
  selectedBusinessEntitySyncPolicyId = businessEntitySyncPolicies.some(p => String(p.id) === String(selectedBusinessEntitySyncPolicyId))
    ? selectedBusinessEntitySyncPolicyId
    : (businessEntitySyncPolicies[0]?.id || null);
}

async function saveBusinessEntitySyncPolicy(showToast = true) {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  const body = collectBusinessEntitySyncPolicy();
  if (!body.members.length) return toast('Add at least one source/table freshness member', 'err');
  try {
    const saved = await api.post('/api/business-entities/' + selectedBusinessEntityId + '/sync-policies', body);
    selectedBusinessEntitySyncPolicyId = saved.id;
    await loadBusinessEntitySyncPolicies();
    if (showToast) toast('Freshness policy saved', 'ok');
    renderBusinessEntityDetail();
    return saved;
  } catch (e) {
    toast(e.message, 'err');
    return null;
  }
}

async function checkBusinessEntitySyncPolicy(policyId) {
  let id = policyId || beInt($('be-sync-id')?.value);
  const formId = beInt($('be-sync-id')?.value);
  if (!id || (formId && String(formId) === String(id))) {
    const saved = await saveBusinessEntitySyncPolicy(false);
    if (!saved?.id) return;
    id = saved.id;
  }
  try {
    businessEntitySyncRun = await api.post('/api/business-entities/sync-policies/' + id + '/check', {});
    selectedBusinessEntitySyncPolicyId = id;
    await loadBusinessEntitySyncPolicies();
    toast('Freshness check completed: ' + (businessEntitySyncRun.status || businessEntitySyncRun.result?.status || 'done'), 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteBusinessEntitySyncPolicy(id) {
  if (!await uiConfirm('Delete this freshness policy?', { danger: true, okText: 'Delete' })) return;
  try {
    await api.del('/api/business-entities/sync-policies/' + id);
    if (String(selectedBusinessEntitySyncPolicyId) === String(id)) selectedBusinessEntitySyncPolicyId = null;
    businessEntitySyncRun = null;
    await loadBusinessEntitySyncPolicies();
    toast('Freshness policy deleted', 'ok');
    renderBusinessEntityDetail();
  } catch (e) { toast(e.message, 'err'); }
}

async function createBusinessEntitySnapshot() {
  if (!selectedBusinessEntityId) return toast('Save or select a Business Entity first', 'err');
  try {
    const detail = await api.post('/api/business-entities/' + selectedBusinessEntityId + '/snapshots', {
      name: $('be-snap-name')?.value.trim() || null,
      captureMode: $('be-snap-mode')?.value || 'EVIDENCE_ONLY',
      retentionDays: beInt($('be-snap-retention')?.value),
      criteria: $('be-snap-criteria')?.value.trim() || null,
      note: $('be-snap-note')?.value.trim() || null
    });
    ['be-snap-name','be-snap-retention','be-snap-criteria','be-snap-note'].forEach(id => { if ($(id)) $(id).value = ''; });
    businessEntitySnapshots = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/snapshots');
    renderBusinessEntityDetail();
    toast('Business Entity snapshot created #' + detail.snapshot.id, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function createBusinessEntityReservation() {
  if (!selectedBusinessEntityId) return toast('Save or select a Business Entity first', 'err');
  try {
    const detail = await api.post('/api/business-entities/' + selectedBusinessEntityId + '/reservations', {
      name: $('be-res-name')?.value.trim() || null,
      count: beInt($('be-res-count')?.value) || 1,
      ttlHours: beInt($('be-res-ttl')?.value) || 24,
      environment: $('be-res-env')?.value.trim() || null,
      criteria: $('be-res-criteria')?.value.trim() || null,
      purpose: $('be-res-purpose')?.value.trim() || null
    });
    ['be-res-name','be-res-env','be-res-criteria','be-res-purpose'].forEach(id => { if ($(id)) $(id).value = ''; });
    businessEntityReservations = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/reservations');
    renderBusinessEntityDetail();
    toast('Entity reservation created #' + detail.reservation.id, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function refreshBusinessEntityEnterprise() {
  if (!selectedBusinessEntityId) return;
  businessEntityEnterprise = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/enterprise');
  renderBusinessEntityDetail();
}

async function createBusinessEntityIssuePackage() {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  try {
    await api.post('/api/business-entities/' + selectedBusinessEntityId + '/issue-packages', {
      issueKey: $('be-issue-key')?.value.trim() || null,
      title: $('be-issue-title')?.value.trim() || null,
      severity: $('be-issue-sev')?.value || 'MEDIUM',
      targetEnvironment: $('be-issue-target')?.value.trim() || null,
      recreationMode: $('be-issue-mode')?.value || 'MASKED_SUBSET',
      privacyAction: 'MASK_OR_SYNTHETIC'
    });
    ['be-issue-key','be-issue-title','be-issue-target'].forEach(id => { if ($(id)) $(id).value = ''; });
    await refreshBusinessEntityEnterprise();
    toast('Issue recreation package created', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function createBusinessEntityLookalike() {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  try {
    await api.post('/api/business-entities/' + selectedBusinessEntityId + '/lookalike-profiles', {
      name: $('be-look-name')?.value.trim() || null,
      objective: $('be-look-objective')?.value.trim() || null,
      privacyMode: $('be-look-privacy')?.value || 'NO_RAW_VALUES',
      rowGoal: beInt($('be-look-rows')?.value) || 1000
    });
    ['be-look-name','be-look-objective'].forEach(id => { if ($(id)) $(id).value = ''; });
    await refreshBusinessEntityEnterprise();
    toast('Look-alike profile created', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function syncBusinessEntityCatalog() {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  try {
    businessEntityEnterprise = await api.post('/api/business-entities/' + selectedBusinessEntityId + '/catalog/sync', {});
    renderBusinessEntityDetail();
    toast('Catalog synced', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function createBusinessEntityGovernance() {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  try {
    await api.post('/api/business-entities/' + selectedBusinessEntityId + '/governance-requests', {
      objectType: $('be-gov-type')?.value || 'BUSINESS_ENTITY',
      action: $('be-gov-action')?.value || 'RELEASE',
      reviewer: $('be-gov-reviewer')?.value.trim() || null,
      riskLevel: $('be-gov-risk')?.value || 'MEDIUM',
      comments: $('be-gov-comments')?.value.trim() || null
    });
    ['be-gov-reviewer','be-gov-comments'].forEach(id => { if ($(id)) $(id).value = ''; });
    await refreshBusinessEntityEnterprise();
    toast('Governance request created', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function decideBusinessEntityGovernance(id, action) {
  const approve = action === 'approve';
  const reviewer = await uiPrompt((approve ? 'Approve' : 'Reject') + ' request #' + id + ' as reviewer', currentUser?.username || '', { okText: approve ? 'Approve' : 'Reject' });
  if (reviewer === null) return;
  try {
    await api.post('/api/business-entities/governance-requests/' + id + '/' + (approve ? 'approve' : 'reject'), {
      reviewer,
      comments: approve ? 'Approved in Business Entity workspace' : 'Rejected in Business Entity workspace',
      eSignature: reviewer + ':' + Date.now()
    });
    await refreshBusinessEntityEnterprise();
    toast(approve ? 'Request approved' : 'Request rejected', approve ? 'ok' : 'warn');
  } catch (e) { toast(e.message, 'err'); }
}

async function createBusinessEntityExecutionPlan() {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  try {
    await api.post('/api/business-entities/' + selectedBusinessEntityId + '/execution-plans', {
      name: $('be-plan-name')?.value.trim() || null,
      operationType: $('be-plan-op')?.value || 'SUBSET_MASK',
      mode: $('be-plan-mode')?.value || 'PLAN_ONLY',
      sourceEnvironment: $('be-plan-source')?.value.trim() || null,
      targetEnvironment: $('be-plan-target')?.value.trim() || null
    });
    ['be-plan-name','be-plan-source','be-plan-target'].forEach(id => { if ($(id)) $(id).value = ''; });
    await refreshBusinessEntityEnterprise();
    toast('Entity execution plan created', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function createBusinessEntityOperationalPackage() {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  try {
    const planId = beInt($('be-package-plan')?.value);
    if (!planId) return toast('Choose an execution plan first', 'err');
    await api.post('/api/business-entities/' + selectedBusinessEntityId + '/operational-packages', {
      executionPlanId: planId,
      name: $('be-package-name')?.value.trim() || null,
      packageType: 'SCHEDULER_RUNNER',
      targetEnvironment: $('be-plan-target')?.value.trim() || null
    });
    if ($('be-package-name')) $('be-package-name').value = '';
    await refreshBusinessEntityEnterprise();
    toast('Operational package created', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function createBusinessEntityPackageVersion(packageId) {
  const id = packageId || beInt($('be-lifecycle-package')?.value);
  if (!id) return toast('Choose an operational package first', 'err');
  const note = await uiPrompt('Version note for package #' + id, 'Release artifact update', { okText: 'Create version' });
  if (note === null) return;
  try {
    await api.post('/api/business-entities/operational-packages/' + id + '/versions', {
      retentionPolicy: 'STANDARD_7_YEAR',
      retentionDays: 2555,
      changeNote: note
    });
    await refreshBusinessEntityEnterprise();
    toast('Immutable package version created', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function promoteBusinessEntityPackage(packageId) {
  const id = packageId || beInt($('be-lifecycle-package')?.value);
  if (!id) return toast('Choose an operational package first', 'err');
  const target = $('be-promote-target')?.value.trim() || await uiPrompt('Promote package #' + id + ' to environment', 'UAT', { okText: 'Promote' });
  if (!target) return;
  try {
    await api.post('/api/business-entities/operational-packages/' + id + '/promotions', {
      toEnvironment: target,
      fromEnvironment: 'DEV',
      comments: 'Promotion requested from Business Entity workspace',
      approver: currentUser?.username || null
    });
    await refreshBusinessEntityEnterprise();
    toast('Package promotion recorded', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function launchBusinessEntityExecutionPlan(planId) {
  if (!selectedBusinessEntityId) return toast('Select a Business Entity first', 'err');
  const id = planId || beInt($('be-launch-plan')?.value);
  if (!id) return toast('Choose an execution plan first', 'err');
  const plan = (businessEntityEnterprise?.executionPlans || []).find(p => String(p.id) === String(id));
  if (plan && !['APPROVED', 'SUBMITTED'].includes(String(plan.status || '').toUpperCase())) {
    return toast('Approve this execution plan before launch', 'err');
  }
  const targetDataSourceId = beInt($('be-launch-target')?.value);
  const seed = beInt($('be-launch-seed')?.value);
  const rows = beInt($('be-launch-rows')?.value);
  const slices = businessEntityDataScopeSlices(plan);
  const flow = plan?.operationType === 'SYNTHETIC_LOOKALIKE'
    ? 'Synthetic look-alike data will be generated and loaded into the selected target.'
    : (slices.length > 1
      ? `DataScope fan-out will submit ${slices.length} application slice runs: ${slices.slice(0, 5).map(s => s.label || s.datasetName || ('dataset #' + s.datasetId)).join(', ')}.${targetDataSourceId ? '\nThe selected Target DB will override every slice target.' : '\nEach slice will use its configured DataScope blueprint target.'}`
      : `${plan?.operationType || 'DataScope'} will submit one DataScope application slice run.${targetDataSourceId ? '\nThe selected Target DB will override the blueprint target.' : '\nThe blueprint target DB will be used.'}`);
  if (!await uiConfirm(`${flow}\n\nLaunch plan #${id}?`, { okText: 'Launch' })) return;
  try {
    const result = await api.post('/api/business-entities/execution-plans/' + id + '/launch', {
      targetDataSourceId: targetDataSourceId || null,
      targetSchema: $('be-launch-schema')?.value.trim() || null,
      seed: seed || null,
      maskingSeed: $('be-launch-seed')?.value.trim() || null,
      rowCount: rows || null,
      maxRows: rows || null,
      loadAction: $('be-launch-load')?.value || 'REPLACE',
      targetPrep: $('be-launch-prep')?.value || 'DELETE'
    });
    await refreshBusinessEntityEnterprise();
    const childCount = Array.isArray(result.runs) ? result.runs.length : 0;
    toast(result.engine === 'DATASCOPE_FANOUT'
      ? `Fan-out submitted: ${childCount} slice run(s)`
      : `${result.engine || 'Plan'} launch submitted: ${result.runId || result.id || result.status}`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function viewBusinessEntityPackageScript(id) {
  try {
    const pkg = await api.get('/api/business-entities/operational-packages/' + id);
    const box = $('be-package-script');
    if (box) box.innerHTML = `<div class="be-rb-panel"><b>Runner script for package #${id}</b><pre class="script-box">${esc(pkg.shellScript || '')}</pre></div>`;
  } catch (e) { toast(e.message, 'err'); }
}

async function releaseBusinessEntityReservation(id) {
  if (!await uiConfirm('Release entity reservation #' + id + '?', { danger: true, okText: 'Release' })) return;
  try {
    await api.post('/api/business-entities/reservations/' + id + '/release', {});
    businessEntityReservations = await api.get('/api/business-entities/' + selectedBusinessEntityId + '/reservations');
    renderBusinessEntityDetail();
    toast('Reservation released', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function previewBusinessEntityRollback(snapshotId) {
  const box = $('be-rb-' + snapshotId);
  if (!box) return;
  try {
    if (!virtualDbs.length) {
      try { virtualDbs = await api.get('/api/virtualization/vdbs'); } catch {}
    }
    const plan = await api.post('/api/business-entities/snapshots/' + snapshotId + '/rollback', { dryRun: true });
    box.innerHTML = renderBusinessEntityRollbackPlan(snapshotId, plan);
  } catch (e) {
    box.innerHTML = `<div class="warnings">${esc(e.message)}</div>`;
  }
}

function renderBusinessEntityRollbackPlan(snapshotId, plan) {
  const actions = plan.actions || [];
  if (!actions.length) return '<div class="empty">No rollback actions available for this snapshot.</div>';
  const rows = actions.map((a, idx) => {
    const ready = a.status === 'READY';
    const vdbOpts = '<option value="">Select target VDB</option>' + virtualDbs.map(v =>
      `<option value="${v.id}">${esc(v.name)} (#${v.id}) · ${esc(v.provider || '')}</option>`).join('');
    return `<div class="be-rb-row">
      <div>
        <b>${a.virtualSnapshotId ? 'Virtual snapshot #' + a.virtualSnapshotId : 'Evidence-only member'}</b>
        <div class="be-card-meta">Data source ${esc(a.dataSourceId || '-')} · schema ${esc(a.schemaName || '-')} · ${esc((a.tables || []).join(', '))}</div>
        <div class="be-card-meta">${esc(a.status || '')}</div>
      </div>
      <select data-rb-ds="${esc(a.dataSourceId || '')}" data-rb-snap="${esc(a.virtualSnapshotId || '')}" ${a.virtualSnapshotId ? '' : 'disabled'}>
        ${ready && a.targetVdbId ? vdbOpts.replace(`value="${a.targetVdbId}"`, `value="${a.targetVdbId}" selected`) : vdbOpts}
      </select>
    </div>`;
  }).join('');
  return `<div class="be-rb-panel">
    <div class="warnings">Rollback changes target VDBs. Preview first, then type the confirmation exactly.</div>
    ${rows}
    <div class="be-op-form compact">
      <div class="grow"><label>Reason</label><input id="be-rb-reason-${snapshotId}" placeholder="Why rollback is required"></div>
      <div><label>Confirm text</label><input id="be-rb-confirm-${snapshotId}" placeholder="${esc(plan.requiredConfirmText || ('ROLLBACK ' + snapshotId))}"></div>
      <button class="danger" onclick="runBusinessEntityRollback(${snapshotId})">Execute rollback</button>
    </div>
  </div>`;
}

async function runBusinessEntityRollback(snapshotId) {
  const box = $('be-rb-' + snapshotId);
  if (!box) return;
  const byDs = {}, bySnap = {};
  box.querySelectorAll('select[data-rb-ds]').forEach(sel => {
    if (!sel.value) return;
    const ds = sel.dataset.rbDs, snap = sel.dataset.rbSnap;
    if (ds) byDs[ds] = parseInt(sel.value, 10);
    if (snap) bySnap[snap] = parseInt(sel.value, 10);
  });
  if (!await uiConfirm('Execute rollback for Business Entity snapshot #' + snapshotId + '?', { danger: true, okText: 'Rollback' })) return;
  try {
    const result = await api.post('/api/business-entities/snapshots/' + snapshotId + '/rollback', {
      dryRun: false,
      targetVdbByDataSource: byDs,
      targetVdbBySnapshot: bySnap,
      reason: $('be-rb-reason-' + snapshotId)?.value.trim() || null,
      confirmText: $('be-rb-confirm-' + snapshotId)?.value.trim() || null
    });
    box.innerHTML = `<div class="okbox">Rollback executed: ${esc(result.executed || 0)} VDB refresh action(s).</div>`;
    toast('Rollback executed', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function saveBusinessEntity() {
  const body = collectBusinessEntityDefinition();
  if (!body.name) { toast('Business Entity name is required', 'err'); return; }
  const memberRows = collectBusinessEntityMembers();
  try {
    const saved = selectedBusinessEntityId
      ? await api.put('/api/business-entities/' + selectedBusinessEntityId, body)
      : await api.post('/api/business-entities', body);
    selectedBusinessEntityId = saved.id;
    await api.put('/api/business-entities/' + saved.id + '/members', memberRows);
    businessEntities = await api.get('/api/business-entities');
    await selectBusinessEntity(saved.id, false);
    toast('Business Entity saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function saveBusinessEntityMembers() {
  if (!selectedBusinessEntityId) {
    await saveBusinessEntity();
    return;
  }
  try {
    await api.put('/api/business-entities/' + selectedBusinessEntityId + '/members', collectBusinessEntityMembers());
    businessEntities = await api.get('/api/business-entities');
    await selectBusinessEntity(selectedBusinessEntityId, false);
    toast('Member tables saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteBusinessEntity(id) {
  const name = businessEntityDetail?.entity?.name || ('#' + id);
  if (!await uiConfirm('Delete Business Entity "' + name + '"?', { danger: true, okText: 'Delete' })) return;
  try {
    await api.del('/api/business-entities/' + id);
    selectedBusinessEntityId = null;
    businessEntityDetail = null;
    businessEntities = await api.get('/api/business-entities');
    renderBusinessEntities();
    resetBusinessEntityForm(false);
    toast('Business Entity deleted', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

function collectBusinessEntityDefinition() {
  return {
    name: $('be-name')?.value.trim() || null,
    description: $('be-desc')?.value.trim() || null,
    domain: $('be-domain')?.value.trim() || null,
    ownerUsername: $('be-owner')?.value.trim() || null,
    primaryDatasetId: beInt($('be-primary-dataset')?.value),
    rootTable: $('be-root')?.value.trim() || null,
    businessKeyColumns: $('be-key-cols')?.value.trim() || null,
    status: $('be-status')?.value || 'ACTIVE'
  };
}

function collectBusinessEntityMembers() {
  return [...document.querySelectorAll('[data-be-member]')].map((row, idx) => ({
    logicalRole: row.querySelector('.be-m-role')?.value.trim() || null,
    dataSourceId: beInt(row.querySelector('.be-m-source')?.value),
    schemaName: row.querySelector('.be-m-schema')?.value.trim() || null,
    datasetId: beInt(row.querySelector('.be-m-dataset')?.value),
    tableName: row.querySelector('.be-m-table')?.value.trim() || null,
    tableAlias: row.querySelector('.be-m-alias')?.value.trim() || null,
    keyColumns: row.querySelector('.be-m-keys')?.value.trim() || null,
    joinToRole: row.querySelector('.be-m-join')?.value.trim() || null,
    relationshipJson: row.querySelector('.be-m-rel')?.value.trim() || null,
    includeInSubset: !!row.querySelector('.be-m-subset')?.checked,
    includeInSynthetic: !!row.querySelector('.be-m-synth')?.checked,
    ordinalNo: idx
  }));
}

function beInt(v) {
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? n : null;
}

function preferredPolicyForDataScope(def) {
  return policiesList.find(p => p.id === def?.policyId)
    || policiesList.find(p => p.dataSourceId === def?.dataSourceId &&
      (!p.schemaName || !def?.schemaName || p.schemaName === def.schemaName))
    || policiesList[0] || null;
}

async function saveDataScopePolicy(policyId, silent = true) {
  const def = currentAdDef();
  const parsed = policyId ? parseInt(policyId) : null;
  const current = def?.policyId == null ? null : parseInt(def.policyId);
  if (!def || current === parsed) return def;
  const updated = await api.put(`/api/datasets/${selectedAdId}/policy`, { policyId: parsed });
  const idx = datasetsList.findIndex(d => d.id === selectedAdId);
  if (idx >= 0) datasetsList[idx] = updated;
  if (!silent) toast(parsed ? 'DataScope policy saved' : 'DataScope set to no masking', 'ok');
  renderDataScopeProvision();
  loadAdInsights();
  return updated;
}

async function saveColumnMapPolicy(policyId, silent = true) {
  if (!selectedAdId || columnMapState.profileIdx === null) return;
  const profile = adProfiles[columnMapState.profileIdx];
  if (!profile) return;
  const parsed = policyId ? parseInt(policyId) : null;
  const current = profile.policyId == null ? null : parseInt(profile.policyId);
  if (current === parsed) return;
  profile.policyId = parsed;
  adProfiles = await api.put(`/api/datasets/${selectedAdId}/profiles`, adProfiles);
  adDirty = false;
  renderAdProfiles();
  renderTableMapGrid();
  renderDataScopeProvision();
  loadAdInsights();
  if (!silent) toast(parsed ? 'Table policy saved' : 'Table set to no masking', 'ok');
}

function dataScopeSourceSummary(def, included) {
  const contexts = [];
  const seen = new Set();
  included.forEach(profile => {
    const dsId = profileSourceDataSourceId(profile, def);
    const schema = profileSourceSchemaName(profile, def);
    const key = `${dsId || ''}|${String(schema || '').toLowerCase()}`;
    if (seen.has(key)) return;
    seen.add(key);
    contexts.push(`${dataSourceName(dsId)}${schema ? ' / ' + schema : ''}`);
  });
  if (!contexts.length) return `${dataSourceName(def?.dataSourceId)}${def?.schemaName ? ' / ' + def.schemaName : ''}`;
  if (contexts.length === 1) return contexts[0];
  const preview = contexts.slice(0, 2).join(', ');
  return `${contexts.length} source contexts (${preview}${contexts.length > 2 ? ', ...' : ''})`;
}

function dataScopePolicySummary(included) {
  if (!included.length) return 'No included tables';
  const ids = [...new Set(included.map(profilePolicyId).filter(Boolean))];
  const noMaskCount = included.filter(p => !profilePolicyId(p)).length;
  const names = ids.map(id => policiesList.find(p => String(p.id) === String(id))?.name || `#${id}`);
  if (!ids.length) return 'No masking - copy mapped columns as-is';
  if (ids.length === 1 && !noMaskCount) return names[0];
  return `${ids.length} table polic${ids.length === 1 ? 'y' : 'ies'}${noMaskCount ? `, ${noMaskCount} unmasked table${noMaskCount !== 1 ? 's' : ''}` : ''}`;
}

function renderDataScopeProvision() {
  renderDataScopeOverview();
  const def = currentAdDef();
  const summary = $('ad-prov-summary');
  if (!summary) return;
  if (!def) {
    summary.innerHTML = '<div class="empty">Select a DataScope blueprint first.</div>';
    return;
  }
  const jobName = $('ad-prov-name');
  if (jobName && !jobName.value) jobName.value = `${def.name || 'datascope'}-provision`;
  if (!dsSavedJobsLoaded) dsRefreshSavedJobs(true);   // one-time load of this user's saved DataScope jobs
  if ($('ad-prov-policy')) {
    setOptionsPreserve('ad-prov-policy',
      '<option value="">No default — only per-table policies</option>' +
      policiesList.map(p => `<option value="${p.id}">${esc(p.name)}</option>`).join(''));
    // Default the provision policy to the blueprint's saved policy so it's actually applied without
    // re-selecting it on this tab. Only fills an empty selection — an explicit user choice is preserved.
    const provSel = $('ad-prov-policy');
    if (!provSel.value && def.policyId != null
        && policiesList.some(p => String(p.id) === String(def.policyId))) {
      provSel.value = String(def.policyId);
    }
  }

  const included = adProfiles.filter(p => p.included);
  const sourceStatus = dataScopeSourceSummary(def, included);
  const policyStatus = dataScopePolicySummary(included);
  const mapped = included.filter(p => p.targetTableName).length;
  const actions = adOverrides.reduce((acc, o) => {
    const key = o.overrideType || 'USE_POLICY';
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});
  const targetStatus = def.targetDataSourceId
    ? `${esc(dataSourceName(def.targetDataSourceId))}${def.targetSchemaName ? ` / ${esc(def.targetSchemaName)}` : ''}`
    : '<span class="warn-row">Open Table Map and select target data source</span>';
  const selfConflict = adProvisionSelfLoadConflict(def);
  const actionSummary = Object.entries(actions).length
    ? Object.entries(actions).map(([k,v]) => `${esc(k)}: ${v}`).join(', ')
    : 'Same-name mapped columns';
  const unmaskedPii = adPiiCoverage?.unmaskedApproved || [];
  const piiStatus = !adPiiCoverage ? 'Checking…'
    : (adPiiCoverage.approvedTotal || 0) === 0 ? 'No approved PII in scope'
    : unmaskedPii.length
      ? `<span class="warn-row">${adPiiCoverage.approvedMasked}/${adPiiCoverage.approvedTotal} approved PII masked</span>`
      : `${adPiiCoverage.approvedMasked}/${adPiiCoverage.approvedTotal} approved PII masked`;
  const driftIssues = adDrift?.issues || [];
  const driftStatus = !adDrift ? 'Checking…'
    : driftIssues.length ? `<span class="warn-row">${driftIssues.length} drift issue${driftIssues.length === 1 ? '' : 's'}</span>`
    : 'In sync with source';
  summary.innerHTML = `<div class="ds-provision-grid">
      <div class="ds-overview-tile"><span>Job type</span><b>${pill('SUBSET_MASK', 'info')}</b></div>
      <div class="ds-overview-tile"><span>Source</span><b>${esc(sourceStatus)}</b></div>
      <div class="ds-overview-tile"><span>Target</span><b>${targetStatus}</b></div>
      <div class="ds-overview-tile"><span>Masking</span><b>${esc(policyStatus)}</b></div>
      <div class="ds-overview-tile"><span>Driver</span><b><code>${esc(def.driverTable || '-')}</code>${def.driverFilter ? `<small>${esc(def.driverFilter)}</small>` : ''}</b></div>
      <div class="ds-overview-tile"><span>Included tables</span><b>${included.length} of ${adProfiles.length}</b></div>
      <div class="ds-overview-tile"><span>Targets</span><b>${mapped} custom, ${Math.max(0, included.length - mapped)} same-name</b></div>
      <div class="ds-overview-tile"><span>Column map actions</span><b>${actionSummary}</b></div>
      <div class="ds-overview-tile"><span>PII coverage</span><b>${piiStatus}</b></div>
      <div class="ds-overview-tile"><span>Schema check</span><b>${driftStatus}</b></div>
      ${selfConflict ? `<div class="ds-provision-warning">Self-target conflict: <code>${esc(selfConflict.tableName)}</code></div>` : ''}
    </div>`;
  adProvisionLoadChanged();
}

function adProvisionLoadChanged() {
  const sel = $('ad-prov-load-action');
  if (!sel) return;

  // IN_PLACE requires all tables to share the same source DB (no per-table source overrides).
  // Disable it if the current DataScope definition has mixed sources, or if no definition is selected.
  const def = currentAdDef();
  const canInPlace = !!(def && !adProvisionHasSourceOverrides(def));
  const inPlaceOpt = [...sel.options].find(o => o.value === 'IN_PLACE');
  if (inPlaceOpt) {
    inPlaceOpt.disabled = !canInPlace;
    if (!canInPlace && sel.value === 'IN_PLACE') sel.value = 'REPLACE';
  }

  const action = sel.value || 'REPLACE';
  const prep = $('ad-prov-target-prep');
  const keys = $('ad-prov-key-cols');
  const hint = $('ad-prov-load-hint');
  const inPlace = action === 'IN_PLACE';
  if (prep) {
    if (action === 'REPLACE' && prep.value === 'NONE') prep.value = 'DELETE';
    if (action === 'INSERT' && prep.value === 'DELETE') prep.value = 'NONE';
    if (action === 'TRUNCATE_ONLY') prep.value = 'TRUNCATE';
    prep.disabled = inPlace;   // target prep is irrelevant for in-place (nothing is cleared)
  }
  if (keys) keys.disabled = !['UPDATE','INSERT_UPDATE','IN_PLACE'].includes(action);
  const ckw = $('ad-prov-chunk-key-wrap');
  if (ckw) ckw.style.display = inPlace ? '' : 'none';
  if (hint) {
    hint.textContent = ({
      REPLACE: 'Load replace clears each mapped target table, then inserts masked rows.',
      INSERT: 'Insert only appends rows and lets the database reject duplicate keys.',
      UPDATE: 'Update only changes rows that match key columns; missing rows are skipped.',
      INSERT_UPDATE: 'Insert-update updates matching keys and inserts missing rows.',
      IN_PLACE: 'In-place masks the SOURCE tables themselves (no separate target) in committed chunks. Covers every included table. Key columns optional (default = PK). For a composite-PK table, set a single unique “in-place chunk key”.',
      TRUNCATE_ONLY: 'Truncate only clears mapped target tables without loading rows.'
    })[action] || '';
  }
}

function adProvisionList(id) {
  return ($(id)?.value || '').split(',').map(s => s.trim()).filter(Boolean);
}

function adProvisionDuplicateTarget() {
  const seen = new Map();
  for (const profile of adProfiles.filter(p => p.included)) {
    const target = tableMapTargetValue(profile);
    const key = tableMapTargetKey(target);
    if (seen.has(key)) return { target, first: seen.get(key), second: profile.tableName };
    seen.set(key, profile.tableName);
  }
  return null;
}

function adProvisionHasSourceOverrides(def) {
  return adProfiles.filter(p => p.included).some(p =>
    String(profileSourceDataSourceId(p, def) || '') !== String(def?.dataSourceId || '') ||
    !sameSchemaName(profileSourceSchemaName(p, def), def?.schemaName || ''));
}

function sameDataSourceConfig(aId, bId) {
  if (!aId || !bId) return false;
  if (String(aId) === String(bId)) return true;
  const a = dataSources.find(d => String(d.id) === String(aId));
  const b = dataSources.find(d => String(d.id) === String(bId));
  if (!a || !b) return false;
  return String(a.jdbcUrl || '').toLowerCase() === String(b.jdbcUrl || '').toLowerCase() &&
    String(a.username || '').toLowerCase() === String(b.username || '').toLowerCase();
}

function sameSchemaName(a, b) {
  return String(a || '').toLowerCase() === String(b || '').toLowerCase();
}

function adProvisionSelfLoadConflict(def) {
  if (!def?.targetDataSourceId) return null;
  return adProfiles.filter(p => p.included).find(p => {
    const sourceDs = profileSourceDataSourceId(p, def);
    const sourceSchema = profileSourceSchemaName(p, def);
    if (!sameDataSourceConfig(sourceDs, def.targetDataSourceId)) return false;
    if (!sameSchemaName(sourceSchema, def.targetSchemaName)) return false;
    return String(p.tableName || '').toLowerCase() === String(tableMapTargetValue(p) || '').toLowerCase();
  }) || null;
}

function toggleExchangeFields() {
  const on = $('ad-prov-exchange')?.checked;
  const wrap = $('ad-prov-exchange-wrap');
  if (wrap) wrap.style.display = on ? '' : 'none';
}

// Build (and fully validate/confirm) the /api/jobs provisioning payload from the DataScope provision tab.
// Returns { payload, def, inPlace } when good, or a falsy value when the user aborted or validation failed
// (the inline `return toast(...)` / `if (!ok) return;` guards below all yield undefined = "aborted").
// Shared by Launch (submitDataScopeProvision) and Save-as-job (saveDataScopeProvisionJob).
async function adBuildProvisionPayload() {
  const def = currentAdDef();
  if (!def) return toast('Select a DataScope blueprint first', 'err');
  const sourceOverrides = adProvisionHasSourceOverrides(def);
  const loadAction = $('ad-prov-load-action')?.value || 'REPLACE';
  const inPlace = loadAction === 'IN_PLACE';
  // In-place masks every included table on the source itself — it has no driver/subset closure, so don't require one.
  if (!inPlace && !def.driverTable && !sourceOverrides) return toast('Select and save a driver table first', 'err');
  if (inPlace && sourceOverrides) return toast('In-place masking requires all mapped tables to use the default source DB and schema', 'err');
  // In-place masks the source tables themselves, so a separate target / self-map check don't apply.
  if (!inPlace && !def.targetDataSourceId) return toast('Open Table Map and select a target data source first', 'err');
  const included = adProfiles.filter(p => p.included);
  if (!included.length) return toast('Include at least one table profile first', 'err');
  if (!inPlace) {
    const duplicate = adProvisionDuplicateTarget();
    if (duplicate) return toast(`Target table ${duplicate.target} is mapped by both ${duplicate.first} and ${duplicate.second}`, 'err');
    const selfLoad = adProvisionSelfLoadConflict(def);
    if (selfLoad) {
      return toast(`Source and target are the same table (${selfLoad.tableName}). Choose a different target data source, schema, or target table.`, 'err');
    }
  }
  const badPolicy = included.find(p => p.policyId && !policiesList.some(policy => String(policy.id) === String(p.policyId)));
  if (badPolicy) {
    return toast(`Saved policy for ${badPolicy.tableName} no longer exists. Open Column Map and choose another policy or No masking.`, 'err');
  }

  // Guardrail 1: approved PII columns in scope with no masking. Recomputed fresh (with the
  // ad-hoc default policy selected on this tab) so it reflects the run about to launch.
  try {
    const provPolicy = parseInt($('ad-prov-policy')?.value) || null;
    const cov = await api.get(`/api/datasets/${def.id}/pii-coverage${provPolicy ? '?policyId=' + provPolicy : ''}`);
    const un = cov.unmaskedApproved || [];
    if (un.length) {
      const shown = un.slice(0, 10).map(u => `• ${u.table}.${u.column}  (${u.piiType})`).join('\n');
      const ok = await uiConfirm(
        `${un.length} approved PII column${un.length === 1 ? '' : 's'} in scope ${un.length === 1 ? 'has' : 'have'} NO masking rule or override:\n\n${shown}` +
        (un.length > 10 ? `\n…and ${un.length - 10} more` : '') +
        '\n\nThese values will be copied IN CLEAR to the target. Provision anyway?',
        { title: '⚠ Unmasked PII in scope', danger: true, okText: 'Provision anyway' });
      if (!ok) return;
    }
  } catch (e) { /* the coverage check is advisory — never block provisioning when it can't run */ }

  // Guardrail 2: blueprint out of sync with the live source schema (checked when the blueprint loaded).
  const driftIssues = adDrift?.issues || [];
  if (driftIssues.length) {
    const shown = driftIssues.slice(0, 8)
      .map(i => `• ${String(i.type || '').replaceAll('_', ' ').toLowerCase()}: ${i.table || ''}${i.column ? '.' + i.column : ''} (${i.artifact || ''})`)
      .join('\n');
    const ok = await uiConfirm(
      `This blueprint no longer matches the live source schema:\n\n${shown}` +
      (driftIssues.length > 8 ? `\n…and ${driftIssues.length - 8} more` : '') +
      '\n\nThe job may fail or silently skip data. Provision anyway?',
      { title: '⚠ Schema drift detected', danger: true, okText: 'Provision anyway' });
    if (!ok) return;
  }

  const targetPrep = $('ad-prov-target-prep')?.value || (loadAction === 'REPLACE' ? 'DELETE' : 'NONE');
  const keyColumns = adProvisionList('ad-prov-key-cols');
  const batchSize = parseInt($('ad-prov-batch')?.value || '0');
  const maskingSeed = $('ad-prov-seed')?.value?.trim() || null;
  const maxRows = parseInt($('ad-prov-max')?.value || '0');
  const spec = {
    driverTable: def.driverTable,
    filter: def.driverFilter || null,
    maxDriverRows: maxRows > 0 ? maxRows : 0,
    sourceSchema: def.schemaName || null,
    // in-place masks the source tables; force target schema to the source schema
    targetSchema: inPlace ? (def.schemaName || null) : (def.targetSchemaName || null),
    maskingSeed,
    loadAction,
    targetPrep
  };
  if (keyColumns.length) spec.keyColumns = keyColumns;
  if (batchSize > 0) spec.batchSize = batchSize;
  if (inPlace) {
    const chunkKey = $('ad-prov-chunk-key')?.value?.trim();
    if (chunkKey) spec.inPlaceChunkKey = chunkKey;
  }

  // Partition exchange (Oracle): load+mask into a staging table, then swap it into the target partition.
  if ($('ad-prov-exchange')?.checked) {
    const part = $('ad-prov-exchange-part')?.value?.trim();
    if (!part) return toast('Enter the partition name for partition exchange', 'err');
    spec.exchangePartition = part;
    const xtbl = $('ad-prov-exchange-tbl')?.value?.trim();
    if (xtbl) spec.exchangeTable = xtbl;
    if ($('ad-prov-exchange-validate')?.checked) spec.exchangeValidate = true;
  }

  // In-place + subset is destructive: it DELETES the non-subset rows from the source database in place.
  // Warn and require explicit confirmation — only for this combination.
  if (inPlace) {
    const subset = (maxRows > 0)
      || !!(def.driverFilter && def.driverFilter.trim())
      || included.some(p => (p.rowLimit && p.rowLimit > 0) || (p.filterExpr && p.filterExpr.trim()));
    if (subset) {
      const ok = await uiConfirm(
        `This permanently DELETES the non-matching rows from the selected table(s) in "${dataSourceName(def.dataSourceId)}" ` +
        '(the database is modified in place — this cannot be undone), then masks what remains.\n\n' +
        'Run this only against a non-production copy. Continue?',
        { title: '⚠ In-place + subset is destructive', danger: true, okText: 'Delete & mask' });
      if (!ok) return;
      spec.confirmInPlaceSubsetDelete = true;
    }
  }

  return {
    payload: {
      name: $('ad-prov-name')?.value || `${def.name || 'datascope'}-provision`,
      jobType: 'SUBSET_MASK',
      sourceId: def.dataSourceId,
      // in-place runs against the source itself
      targetId: inPlace ? def.dataSourceId : def.targetDataSourceId,
      // top-level default masking policy; per-table policies in Table Map override it
      policyId: (parseInt($('ad-prov-policy')?.value) || null),
      datasetId: def.id,
      specJson: JSON.stringify(spec)
    },
    def, inPlace
  };
}

async function submitDataScopeProvision() {
  const built = await adBuildProvisionPayload();
  if (!built) return;
  try {
    const job = await api.post('/api/jobs', built.payload);
    toast(job.status === 'AWAITING_APPROVAL'
      ? 'Submitted for approval — an approver must sign off before it runs'
      : 'DataScope provision launched - monitoring progress', 'ok');
    await goTo('jobs', 'job-monitor-card');
  } catch (e) { toast(e.message, 'err'); }
}

// Save the current provision design as a reusable, owner-private DataScope job (parity with synthetic).
async function saveDataScopeProvisionJob() {
  const built = await adBuildProvisionPayload();
  if (!built) return;
  const suggested = built.payload.name || `${built.def.name || 'datascope'}-provision`;
  const name = await uiPrompt('Saved job name', suggested, { title: 'Save DataScope job', okText: 'Save' });
  if (name === null) return;
  if (!name.trim()) return toast('A saved job name is required', 'err');
  const description = await uiPrompt('Description (optional)', '', { title: 'Save DataScope job', okText: 'Save' });
  if (description === null) return;
  try {
    await api.post('/api/datascope/saved-jobs', {
      name: name.trim(), description: (description || '').trim(), spec: built.payload });
    toast('DataScope job saved — see Saved Jobs below', 'ok');
    await dsRefreshSavedJobs();
  } catch (e) { toast(e.message, 'err'); }
}

/* ───────────── DataScope saved jobs: reusable, schedulable, batch-runner exportable ───────────── */
let dsSavedJobs = [], dsSavedJobsLoaded = false;

async function dsRefreshSavedJobs(silent = false) {
  const box = $('ds-saved-jobs');
  if (!box) return;
  try {
    dsSavedJobs = await api.get('/api/datascope/saved-jobs') || [];
    dsSavedJobsLoaded = true;
    dsRenderSavedJobs();
  } catch (e) {
    if (!silent) toast(e.message, 'err');
    box.innerHTML = `<div class="empty">${esc(e.message)}</div>`;
  }
}

function dsSavedJobDate(v) {
  if (!v) return '';
  try { return new Date(v).toLocaleString(); } catch { return String(v); }
}

function dsRenderSavedJobs() {
  const box = $('ds-saved-jobs');
  const count = $('ds-saved-count');
  if (!box) return;
  if (count) count.textContent = `${dsSavedJobs.length} saved`;
  if (!dsSavedJobs.length) {
    box.innerHTML = '<div class="empty">No saved DataScope jobs yet. Build a provision design above, then click “Save as Job”.</div>';
    return;
  }
  box.innerHTML = dsSavedJobs.map(job => {
    const spec = job.spec || {};
    const sched = job.scheduleEnabled
      ? `<span class="pill ok" title="cron ${esc(job.scheduleCron || '')} (${esc(job.scheduleZone || '')})">⏱ ${esc(job.scheduleCron || 'scheduled')}</span>`
      : '<span class="pill dim">manual</span>';
    const next = job.scheduleEnabled && job.nextRunAt ? ` <span class="dim">next ${esc(dsSavedJobDate(job.nextRunAt))}</span>` : '';
    // Badge when the last run is parked in the maker-checker gate and needs a sign-off.
    const parked = job.lastRunStatus === 'AWAITING_APPROVAL'
      ? ` <span class="pill warn" style="cursor:pointer" title="The last run is waiting for maker-checker approval — open the Job Monitor to approve" onclick="goTo('jobs','job-monitor-card')">⏳ awaiting approval</span>` : '';
    const last = job.lastRunJobId
      ? ` · <a href="#" onclick="goTo('jobs','job-monitor-card');return false;">last run #${esc(job.lastRunJobId)}${job.lastRunStatus ? ' (' + esc(job.lastRunStatus.toLowerCase()) + ')' : ''}</a>` : '';
    return `<div class="ds-saved-job">
      <div class="ds-saved-job-main">
        <div class="ds-saved-job-title"><b>${esc(job.name)}</b> ${sched}${next}${parked}</div>
        <div class="ds-saved-job-meta dim">${esc(spec.jobType || 'SUBSET_MASK')} · ${esc(dataSourceName(spec.sourceId) || spec.sourceId || '?')} → ${esc(dataSourceName(spec.targetId) || spec.targetId || '?')}${job.description ? ' · ' + esc(job.description) : ''}</div>
        <div class="ds-saved-job-meta dim">updated ${esc(dsSavedJobDate(job.updatedAt))}${last}</div>
      </div>
      <div class="ds-saved-job-actions">
        <button class="small" onclick='dsRunSavedJob(${js(job.id)})'>Run</button>
        <button class="ghost small" onclick='dsLoadSavedJob(${js(job.id)})'>Load</button>
        <button class="ghost small" onclick='dsRenameSavedJob(${js(job.id)}, ${js(job.name)}, ${js(job.description || "")})'>Rename</button>
        <button class="ghost small" onclick='dsScheduleSavedJob(${js(job.id)})'>Schedule</button>
        <button class="ghost small" title="Download PowerShell runner for Windows Task Scheduler" onclick='dsExportRunner(${js(job.id)}, "ps1")'>PS1</button>
        <button class="ghost small" title="Download Bash runner for cron" onclick='dsExportRunner(${js(job.id)}, "sh")'>SH</button>
        <button class="ghost small danger" onclick='dsDeleteSavedJob(${js(job.id)}, ${js(job.name)})'>Delete</button>
      </div>
    </div>`;
  }).join('');
}

async function dsRunSavedJob(id) {
  try {
    const r = await api.post(`/api/datascope/saved-jobs/${encodeURIComponent(id)}/run`);
    toast(r.status === 'AWAITING_APPROVAL'
      ? 'Run submitted for approval — an approver must sign off before it runs'
      : `Run #${r.runId} launched (${r.status})`, 'ok');
    await dsRefreshSavedJobs(true);
    await goTo('jobs', 'job-monitor-card');
  } catch (e) { toast(e.message, 'err'); }
}

async function dsLoadSavedJob(id) {
  try {
    const job = dsSavedJobs.find(j => j.id === id) || await api.get(`/api/datascope/saved-jobs/${encodeURIComponent(id)}`);
    const spec = job?.spec;
    if (!spec) return toast('Saved job has no spec to load', 'err');
    const def = currentAdDef();
    if (!def || String(def.id) !== String(spec.datasetId)) {
      return toast(`This job was saved from a different DataScope blueprint (id ${spec.datasetId}). Open that blueprint, then Load again.`, 'err');
    }
    let s = {}; try { s = JSON.parse(spec.specJson || '{}'); } catch { /* ignore */ }
    const set = (elId, val) => { const el = $(elId); if (el) el.value = val ?? ''; };
    set('ad-prov-name', spec.name);
    set('ad-prov-policy', spec.policyId != null ? String(spec.policyId) : '');
    set('ad-prov-load-action', s.loadAction || 'REPLACE');
    set('ad-prov-target-prep', s.targetPrep || 'NONE');
    set('ad-prov-batch', s.batchSize || '');
    set('ad-prov-seed', s.maskingSeed || '');
    set('ad-prov-max', s.maxDriverRows || '');
    set('ad-prov-key-cols', (s.keyColumns || []).join(', '));
    set('ad-prov-chunk-key', s.inPlaceChunkKey || '');
    adProvisionLoadChanged();
    toast(`Loaded “${job.name}” into the provision form`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function dsDeleteSavedJob(id, name) {
  const ok = await uiConfirm(`Delete saved DataScope job “${name}”? Jobs already run are not affected.`,
    { title: 'Delete saved job', danger: true, okText: 'Delete' });
  if (!ok) return;
  try {
    await api.del(`/api/datascope/saved-jobs/${encodeURIComponent(id)}`);
    toast('Deleted', 'ok');
    await dsRefreshSavedJobs(true);
  } catch (e) { toast(e.message, 'err'); }
}

// Edit a saved job's name and description in place (the provision spec and any schedule are preserved).
async function dsRenameSavedJob(id, currentName, currentDesc) {
  const name = await uiPrompt('Job name', currentName || '', { title: 'Edit DataScope job', okText: 'Save' });
  if (name === null) return;
  if (!name.trim()) return toast('A job name is required', 'err');
  const description = await uiPrompt('Description (optional)', currentDesc || '', { title: 'Edit DataScope job', okText: 'Save' });
  if (description === null) return;
  try {
    // Omitting `spec` tells the backend to keep the existing provisioning spec unchanged.
    await api.put(`/api/datascope/saved-jobs/${encodeURIComponent(id)}`, { name: name.trim(), description: (description || '').trim() });
    toast('Saved job updated', 'ok');
    await dsRefreshSavedJobs(true);
  } catch (e) { toast(e.message, 'err'); }
}

async function dsScheduleSavedJob(id) {
  const job = dsSavedJobs.find(j => j.id === id);
  const currentCron = job?.scheduleCron || '0 0 2 * * *';
  const cron = await uiPrompt(
    'Cron schedule — Spring 6-field: second minute hour day-of-month month day-of-week.\n' +
    'Examples:  “0 0 2 * * *” daily 02:00   ·   “0 0 3 * * MON” Mondays 03:00   ·   “0 */30 * * * *” every 30 min.\n' +
    'Leave blank to turn the schedule OFF.',
    job?.scheduleEnabled ? currentCron : '',
    { title: `Schedule “${job?.name || ''}”`, okText: 'Save schedule' });
  if (cron === null) return;
  try {
    if (!cron.trim()) {
      await api.put(`/api/datascope/saved-jobs/${encodeURIComponent(id)}/schedule`, { cron: null, enabled: false });
      toast('Schedule turned off', 'ok');
    } else {
      const zone = (Intl.DateTimeFormat().resolvedOptions().timeZone) || null;
      const preview = await api.post('/api/datascope/saved-jobs/schedule/preview', { cron: cron.trim(), zone });
      const ok = await uiConfirm(
        `Next run: ${new Date(preview.nextRunAt).toLocaleString()} (${preview.zone}).\n\n` +
        'Scheduled runs still pass through the approval gate when the source is governed. Enable this schedule?',
        { title: 'Confirm schedule', okText: 'Enable' });
      if (!ok) return;
      await api.put(`/api/datascope/saved-jobs/${encodeURIComponent(id)}/schedule`, { cron: cron.trim(), zone, enabled: true });
      toast('Schedule enabled', 'ok');
    }
    await dsRefreshSavedJobs(true);
  } catch (e) { toast(e.message, 'err'); }
}

/* ---- Batch runners (PS1 / SH): log in, run the saved job, poll /api/jobs/{runId} to completion ---- */
function dsRunnerBaseUrl() { try { return window.location.origin || 'http://localhost:8088'; } catch { return 'http://localhost:8088'; } }
function dsPsQuote(v) { return "'" + String(v ?? '').replace(/'/g, "''") + "'"; }
function dsShQuote(v) { return "'" + String(v ?? '').replace(/'/g, "'\"'\"'") + "'"; }
function dsFileSlug(v) { return String(v || 'datascope-job').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80) || 'datascope-job'; }
function dsOneLine(v) { return String(v ?? '').replace(/\s+/g, ' ').trim(); }

async function dsExportRunner(id, kind) {
  try {
    const job = dsSavedJobs.find(j => j.id === id) || await api.get(`/api/datascope/saved-jobs/${encodeURIComponent(id)}`);
    if (!job?.id) throw new Error('Saved job not found');
    const ext = kind === 'sh' ? 'sh' : 'ps1';
    const content = ext === 'sh' ? dsSavedJobBashScript(job) : dsSavedJobPowerShellScript(job);
    synDownload(`forgetdm-${dsFileSlug(job.name || job.id)}-datascope-runner.${ext}`, content);
    toast(`Downloaded ${ext.toUpperCase()} runner`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

function dsSavedJobPowerShellScript(job) {
  const baseUrl = dsRunnerBaseUrl();
  const jobId = String(job.id || ''), jobName = dsOneLine(job.name || 'DataScope job');
  const lines = [
    '# ForgeTDM saved DataScope job runner',
    '# Saved job: ' + jobName,
    '# Scheduler usage: set FORGETDM_URL plus FORGETDM_TOKEN, or FORGETDM_USER/FORGETDM_PASS as a fallback.',
    '# Task Scheduler command: powershell -ExecutionPolicy Bypass -File .\\' + `forgetdm-${dsFileSlug(job.name || job.id)}-datascope-runner.ps1`,
    '',
    'param(',
    '  [string]$BaseUrl = $env:FORGETDM_URL,',
    '  [string]$Token = $env:FORGETDM_TOKEN,',
    '  [string]$Username = $env:FORGETDM_USER,',
    '  [string]$Password = $env:FORGETDM_PASS,',
    '  [int]$PollSeconds = 3',
    ')',
    '$ErrorActionPreference = "Stop"',
    'if ($env:FORGETDM_POLL_SECONDS) { $PollSeconds = [int]$env:FORGETDM_POLL_SECONDS }',
    '$JobId = ' + dsPsQuote(jobId),
    '$JobName = ' + dsPsQuote(jobName),
    'if ([string]::IsNullOrWhiteSpace($BaseUrl)) { $BaseUrl = ' + dsPsQuote(baseUrl) + ' }',
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
    'Write-Host "Launching ForgeTDM DataScope job $JobName ($JobId)"',
    '$Run = Invoke-RestMethod -Uri "$BaseUrl/api/datascope/saved-jobs/$EncodedJobId/run" -Method Post -ContentType "application/json" -Body "{}" -WebSession $Session -Headers $Headers',
    '$RunId = [string]$Run.runId',
    'if ([string]::IsNullOrWhiteSpace($RunId)) { throw "ForgeTDM did not return a provisioning run id." }',
    'if ([string]$Run.status -eq "AWAITING_APPROVAL") {',
    '  Write-Warning "Run $RunId is awaiting maker-checker approval; an approver must sign off before it executes."',
    '  exit 3',
    '}',
    '$EncodedRunId = [System.Uri]::EscapeDataString($RunId)',
    'Write-Host "Provision run id: $RunId"',
    '',
    'do {',
    '  Start-Sleep -Seconds $PollSeconds',
    '  $Status = Invoke-RestMethod -Uri "$BaseUrl/api/jobs/$EncodedRunId" -Method Get -WebSession $Session -Headers $Headers',
    '  Write-Host ("{0} - {1} ({2} rows)" -f $Status.status, $Status.message, $Status.rowsProcessed)',
    '} while (@("COMPLETED","FAILED","CANCELLED","CANCELED","AWAITING_APPROVAL") -notcontains [string]$Status.status)',
    '',
    'if ([string]$Status.status -eq "AWAITING_APPROVAL") { Write-Warning "Job is awaiting approval."; exit 3 }',
    'if ([string]$Status.status -eq "FAILED") { Write-Error ("ForgeTDM job failed: " + $Status.message); exit 1 }',
    'if (@("CANCELLED","CANCELED") -contains [string]$Status.status) { Write-Error "ForgeTDM job was cancelled."; exit 2 }',
    'Write-Host "ForgeTDM DataScope job completed successfully."',
    'exit 0'
  ];
  return lines.join('\r\n') + '\r\n';
}

function dsSavedJobBashScript(job) {
  const baseUrl = dsRunnerBaseUrl();
  const jobId = String(job.id || ''), jobName = dsOneLine(job.name || 'DataScope job');
  const lines = [
    '#!/usr/bin/env bash',
    '# ForgeTDM saved DataScope job runner',
    '# Saved job: ' + jobName,
    '# Scheduler usage: set FORGETDM_URL plus FORGETDM_TOKEN, or FORGETDM_USER/FORGETDM_PASS as a fallback.',
    '# Cron example: FORGETDM_URL=http://server:8088 FORGETDM_TOKEN=... /path/' + `forgetdm-${dsFileSlug(job.name || job.id)}-datascope-runner.sh`,
    '',
    'set -euo pipefail',
    'JOB_ID=' + dsShQuote(jobId),
    'JOB_NAME=' + dsShQuote(jobName),
    'BASE_URL="${FORGETDM_URL:-' + baseUrl.replace(/"/g, '\\"') + '}"',
    'POLL_SECONDS="${FORGETDM_POLL_SECONDS:-3}"',
    'TOKEN="${FORGETDM_TOKEN:-}"',
    'USER_NAME="${FORGETDM_USER:-}"',
    'PASSWORD="${FORGETDM_PASS:-}"',
    'command -v curl >/dev/null 2>&1 || { echo "curl is required" >&2; exit 1; }',
    'command -v python3 >/dev/null 2>&1 || { echo "python3 is required for JSON parsing" >&2; exit 1; }',
    'BASE_URL="${BASE_URL%/}"',
    'COOKIE_JAR="$(mktemp)"; STATUS_FILE="$(mktemp)"',
    'cleanup() { rm -f "$COOKIE_JAR" "$STATUS_FILE"; }',
    'trap cleanup EXIT',
    'AUTH_ARGS=()',
    'if [[ -n "$TOKEN" ]]; then',
    '  AUTH_ARGS=(-H "Authorization: Bearer $TOKEN")',
    'else',
    '  if [[ -z "$USER_NAME" ]]; then read -r -p "ForgeTDM username: " USER_NAME; fi',
    '  if [[ -z "$PASSWORD" ]]; then read -r -s -p "ForgeTDM password: " PASSWORD; echo; fi',
    '  LOGIN_BODY="$(USER_NAME="$USER_NAME" PASSWORD="$PASSWORD" python3 -c \'import json,os; print(json.dumps({"username":os.environ["USER_NAME"],"password":os.environ["PASSWORD"]}))\')"',
    '  curl -fsS -c "$COOKIE_JAR" -H "Content-Type: application/json" -d "$LOGIN_BODY" "$BASE_URL/api/auth/login" >/dev/null',
    'fi',
    'ENC() { python3 -c \'import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=""))\' "$1"; }',
    'JSON() { python3 -c \'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2],""))\' "$1" "$2"; }',
    'JOB_ID_ENC="$(ENC "$JOB_ID")"',
    'echo "Launching ForgeTDM DataScope job $JOB_NAME ($JOB_ID)"',
    'curl -fsS -b "$COOKIE_JAR" "${AUTH_ARGS[@]}" -H "Content-Type: application/json" -X POST -d \'{}\' "$BASE_URL/api/datascope/saved-jobs/$JOB_ID_ENC/run" > "$STATUS_FILE"',
    'RUN_ID="$(JSON "$STATUS_FILE" runId)"',
    'RUN_STATUS="$(JSON "$STATUS_FILE" status)"',
    'if [[ -z "$RUN_ID" ]]; then echo "ForgeTDM did not return a run id" >&2; exit 1; fi',
    'if [[ "$RUN_STATUS" == "AWAITING_APPROVAL" ]]; then echo "Run $RUN_ID is awaiting approval; an approver must sign off." >&2; exit 3; fi',
    'RUN_ID_ENC="$(ENC "$RUN_ID")"',
    'echo "Provision run id: $RUN_ID"',
    'while true; do',
    '  sleep "$POLL_SECONDS"',
    '  curl -fsS -b "$COOKIE_JAR" "${AUTH_ARGS[@]}" "$BASE_URL/api/jobs/$RUN_ID_ENC" > "$STATUS_FILE"',
    '  STATUS="$(JSON "$STATUS_FILE" status)"',
    '  MESSAGE="$(JSON "$STATUS_FILE" message)"',
    '  echo "$STATUS - $MESSAGE"',
    '  case "$STATUS" in COMPLETED|FAILED|CANCELLED|CANCELED|AWAITING_APPROVAL) break;; esac',
    'done',
    'case "$STATUS" in',
    '  COMPLETED) echo "ForgeTDM DataScope job completed successfully."; exit 0;;',
    '  AWAITING_APPROVAL) echo "Job is awaiting approval." >&2; exit 3;;',
    '  FAILED) echo "ForgeTDM job failed: $MESSAGE" >&2; exit 1;;',
    '  *) echo "ForgeTDM job ended: $STATUS" >&2; exit 2;;',
    'esac'
  ];
  return lines.join('\n') + '\n';
}

function renderCustomPks() {
  const el = $('ad-pks-grid'); if (!el) return;
  if (!adCustomPks.length) { el.innerHTML = '<div class="empty">No custom PKs defined.</div>'; return; }
  el.innerHTML = `<table>
    <tr><th>Table</th><th>PK Column(s)</th><th>Note</th><th></th></tr>` +
    adCustomPks.map((p, i) => `<tr>
      <td><code>${esc(p.tableName)}</code></td>
      <td><code>${esc(p.columnNames)}</code></td>
      <td>${esc(p.note ?? '')}</td>
      <td><button class="small danger" onclick="removeCustomPk(${i})">✕</button></td></tr>`
    ).join('') + '</table>';
}

function addCustomPk() {
  const table = $('ad-pk-table')?.value;
  const cols  = $('ad-pk-cols')?.value.trim();
  const note  = $('ad-pk-note')?.value.trim() || null;
  if (!table || !cols) { toast('Table and column(s) are required', 'err'); return; }
  const existing = adCustomPks.findIndex(p => p.tableName === table);
  const entry = { tableName: table, columnNames: cols, note };
  if (existing >= 0) adCustomPks[existing] = entry;
  else adCustomPks.push(entry);
  adDirty = true;
  renderCustomPks();
  $('ad-pk-cols').value = ''; $('ad-pk-note').value = '';
  toast(`Custom PK for ${table} staged — click Save to persist`, 'ok');
}

function removeCustomPk(idx) { adCustomPks.splice(idx, 1); adDirty = true; renderCustomPks(); }

async function saveCustomPks() {
  if (!selectedAdId) return;
  try {
    adCustomPks = await api.put(`/api/datasets/${selectedAdId}/custom-pks`, adCustomPks);
    adDirty = false;
    renderCustomPks();
    toast('Custom PKs saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

// ── Custom Relationships ──────────────────────────────────────────────────────

let _relView = 'form';

function switchRelView(view) {
  _relView = view;
  $('ad-rels-form-view').style.display   = view === 'form'   ? '' : 'none';
  $('ad-rels-canvas-view').style.display = view === 'canvas' ? '' : 'none';
  if (view === 'canvas') loadRelCanvas();
}

function renderUserRels() {
  const el = $('ad-rels-grid'); if (!el) return;
  if (!adUserRels.length) { el.innerHTML = '<div class="empty">No custom relationships defined.</div>'; return; }
  el.innerHTML = `<table>
    <tr><th>Name</th><th>Parent table</th><th>Join columns</th><th>Child table</th><th></th></tr>` +
    adUserRels.map(r => {
      const pCols = r.parentColumns.split(','), cCols = r.childColumns.split(',');
      const joinStr = pCols.map((pc, i) => `${pc.trim()} → ${(cCols[i]||'').trim()}`).join(', ');
      return `<tr>
        <td>${esc(r.relName ?? '')}</td>
        <td><code>${esc(r.parentTable)}</code></td>
        <td><code>${esc(joinStr)}</code></td>
        <td><code>${esc(r.childTable)}</code></td>
        <td><button class="small danger" onclick="deleteUserRel(${r.id})">✕</button></td></tr>`;
    }).join('') + '</table>';
}

// ── Multi-column form for creating relationships ───────────────────────────────

async function onRelTableChange() {
  relFormPairs = [{ parentCol: '', childCol: '' }];
  const def = selectedAdId ? datasetsList.find(d => d.id === selectedAdId) : null;
  if (!def) { renderRelFormPairs(); return; }
  const pt = $('ad-rel-parent')?.value, ct = $('ad-rel-child')?.value;
  if (!pt || !ct) { renderRelFormPairs(); return; }
  const schema = def.schemaName ?? '';
  // Fetch columns for newly selected tables if not cached
  await Promise.all([pt, ct].filter(t => t && !relCanvasColumns[t]).map(async t => {
    try {
      const cols = await api.get(
        `/api/datasources/${def.dataSourceId}/tables/${encodeURIComponent(t)}/columns${schema ? '?schema=' + encodeURIComponent(schema) : ''}`
      );
      relCanvasColumns[t] = cols.map(c => c.column || c.name || c.columnName || String(c));
    } catch (_) { relCanvasColumns[t] = []; }
  }));
  renderRelFormPairs();
}

function _colOpts(cols, selected) {
  return `<option value="">— select —</option>` +
    cols.map(c => `<option value="${esc(c)}"${c === selected ? ' selected' : ''}>${esc(c)}</option>`).join('');
}

function renderRelFormPairs() {
  const el = $('rel-form-pairs'); if (!el) return;
  const pt = $('ad-rel-parent')?.value, ct = $('ad-rel-child')?.value;
  if (!pt || !ct) {
    el.innerHTML = '<p class="sub tight" style="margin:6px 0">Select parent and child tables above to configure join columns.</p>';
    return;
  }
  const pCols = relCanvasColumns[pt] || [], cCols = relCanvasColumns[ct] || [];
  el.innerHTML =
    `<div class="rel-pairs-head">
       <span>Parent column <span class="muted">(${esc(pt)})</span></span>
       <span></span>
       <span>Child column <span class="muted">(${esc(ct)})</span></span>
       <span></span>
     </div>` +
    relFormPairs.map((pair, i) =>
      `<div class="rel-pair-row">
         <select onchange="relFormPairs[${i}].parentCol=this.value">${_colOpts(pCols, pair.parentCol)}</select>
         <span class="rel-pair-arrow">→</span>
         <select onchange="relFormPairs[${i}].childCol=this.value">${_colOpts(cCols, pair.childCol)}</select>
         <button class="small danger" onclick="removeRelFormPair(${i})"
           ${relFormPairs.length === 1 ? 'disabled title="Need at least one pair"' : ''}>✕</button>
       </div>`
    ).join('') +
    `<button class="ghost small" onclick="addRelFormPair()" style="margin-top:6px">+ Add column pair</button>`;
}

function addRelFormPair() {
  relFormPairs.push({ parentCol: '', childCol: '' });
  renderRelFormPairs();
}

function removeRelFormPair(idx) {
  if (relFormPairs.length <= 1) return;
  relFormPairs.splice(idx, 1);
  renderRelFormPairs();
}

async function createUserRel() {
  if (!selectedAdId) return;
  const parentTable = $('ad-rel-parent')?.value;
  const childTable  = $('ad-rel-child')?.value;
  const relName     = $('ad-rel-name')?.value.trim() || null;
  if (!parentTable || !childTable) { toast('Select parent and child tables', 'err'); return; }
  const validPairs = relFormPairs.filter(p => p.parentCol && p.childCol);
  if (!validPairs.length) { toast('At least one column pair is required', 'err'); return; }
  const parentColumns = validPairs.map(p => p.parentCol).join(',');
  const childColumns  = validPairs.map(p => p.childCol).join(',');
  try {
    await api.post(`/api/datasets/${selectedAdId}/user-rels`,
      { parentTable, parentColumns, childTable, childColumns, relName });
    adUserRels = await api.get(`/api/datasets/${selectedAdId}/user-rels`);
    renderUserRels();
    relFormPairs = [{ parentCol: '', childCol: '' }];
    if ($('ad-rel-name')) $('ad-rel-name').value = '';
    renderRelFormPairs();
    if (_relView === 'canvas') renderRelCanvas();
    toast(`Relationship ${parentTable} → ${childTable} saved`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteUserRel(id) {
  try {
    await api.del(`/api/datasets/user-rels/${id}`);
    adUserRels = adUserRels.filter(r => r.id !== id);
    renderUserRels();
    if (_relView === 'canvas') renderRelCanvas();
    toast('Relationship removed', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

// ── Visual Relationship Canvas ────────────────────────────────────────────────
// Shows ALL schema tables. Profiled tables show columns + port dots for drag-to-connect.
// Non-profiled tables show as grey dashed boxes — click their header to add to profile.
// Existing user-defined relationships are drawn as bezier arcs with a ✕ remove button.

async function loadRelCanvas() {
  const def = selectedAdId ? datasetsList.find(d => d.id === selectedAdId) : null;
  if (!def) return;

  // Ensure schema table list is populated (needed by renderRelCanvas for all-tables view)
  if (!adTables.length) await loadAdTables();

  // Fetch column metadata only for profiled tables (avoids loading every table in the schema)
  const profiledNames = adProfiles.map(p => p.tableName);
  const schema = def.schemaName ?? '';
  relCanvasColumns = {};
  await Promise.all(profiledNames.map(async t => {
    try {
      const cols = await api.get(
        `/api/datasources/${def.dataSourceId}/tables/${encodeURIComponent(t)}/columns${schema ? '?schema=' + encodeURIComponent(schema) : ''}`
      );
      relCanvasColumns[t] = cols.map(c => c.column || c.name || c.columnName || String(c));
    } catch (_) { relCanvasColumns[t] = []; }
  }));
  renderRelCanvas();
}

function renderRelCanvas() {
  const svg = $('ad-rel-svg'); if (!svg) return;

  // All schema tables are shown; profiled ones get columns + port dots
  // Driver/root table is always present regardless of schema fetch state
  const def = selectedAdId ? datasetsList.find(d => d.id === selectedAdId) : null;
  const driverTable = def?.driverTable ?? null;
  let allTables = adTables.length ? [...adTables] : Object.keys(relCanvasColumns);
  if (driverTable && !allTables.includes(driverTable)) allTables = [driverTable, ...allTables];
  if (!allTables.length) {
    svg.innerHTML = '<text x="20" y="36" fill="var(--muted)" font-family="sans-serif" font-size="13">No tables found. Refresh Tables in Blueprint tab first.</text>';
    return;
  }
  const profiledSet = new Set(adProfiles.map(p => p.tableName));

  const COLS = 3, BOX_W = 215, COL_H = 22, HEAD_H = 36, BOX_PAD = 10,
        GAP_X = 72, GAP_Y = 52, PORT_R = 6;

  // Compute each table's height
  const heights = allTables.map(t => {
    const cols = profiledSet.has(t) ? (relCanvasColumns[t] || []) : [];
    return HEAD_H + cols.length * COL_H + BOX_PAD;
  });

  // Compute per-row max height
  const rowCount = Math.ceil(allTables.length / COLS);
  const rowMaxH = Array.from({ length: rowCount }, (_, r) => {
    let mh = HEAD_H + BOX_PAD;
    for (let c = 0; c < COLS; c++) {
      const idx = r * COLS + c;
      if (idx < allTables.length) mh = Math.max(mh, heights[idx]);
    }
    return mh;
  });

  // Assign positions
  const pos = {};
  allTables.forEach((t, i) => {
    const ri = Math.floor(i / COLS), ci = i % COLS;
    let y = GAP_Y / 2;
    for (let r = 0; r < ri; r++) y += rowMaxH[r] + GAP_Y;
    pos[t] = { x: ci * (BOX_W + GAP_X) + GAP_X / 2, y, w: BOX_W, h: heights[i] };
  });

  const totalW = COLS * (BOX_W + GAP_X) + GAP_X / 2;
  const totalH = rowMaxH.reduce((s, h) => s + h + GAP_Y, GAP_Y / 2);
  svg.setAttribute('viewBox', `0 0 ${totalW} ${totalH}`);
  svg.setAttribute('width', String(totalW));
  svg.setAttribute('height', String(totalH));
  svg.style.minWidth = totalW + 'px';

  // ── Pre-compute port positions BEFORE drawing any arcs ────────────────────
  // (arcs are drawn after boxes in the HTML so they appear on top, but we need
  //  port coords up-front so arc coordinates are correct regardless of draw order)
  _relPortPositions = {};
  allTables.forEach(t => {
    const { x, y, w } = pos[t];
    if (!profiledSet.has(t)) return;
    (relCanvasColumns[t] || []).forEach((col, ci) => {
      const cy = y + HEAD_H + ci * COL_H + COL_H / 2 + 5;
      _relPortPositions[`${t}||${col}||child`]  = { x,     cy, table: t, col, side: 'child' };
      _relPortPositions[`${t}||${col}||parent`] = { x: x+w, cy, table: t, col, side: 'parent' };
    });
  });

  let html = `<defs>
    <marker id="arr" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <polygon points="0 0, 8 3, 0 6" fill="var(--accent)"/>
    </marker>
    <marker id="arr-warn" markerWidth="8" markerHeight="6" refX="7" refY="3" orient="auto">
      <polygon points="0 0, 8 3, 0 6" fill="var(--warn,#e3a008)"/>
    </marker>
    <filter id="fshadow"><feDropShadow dx="0" dy="2" stdDeviation="2.5" flood-opacity="0.10"/></filter>
  </defs>`;

  // ── DB FK arcs (read-only hint lines, drawn first so boxes sit on top) ────
  adRelationships.filter(r => r.source === 'DB').forEach(rel => {
    const pp = pos[rel.parentTable], cp = pos[rel.childTable];
    if (!pp || !cp) return;
    const x1 = pp.x + pp.w, y1 = pp.y + HEAD_H * 0.7;
    const x2 = cp.x,        y2 = cp.y + HEAD_H * 0.7;
    const dx = Math.abs(x2 - x1) * 0.4;
    html += `<path d="M${x1},${y1} C${x1+dx},${y1} ${x2-dx},${y2} ${x2},${y2}"
      fill="none" stroke="var(--border)" stroke-width="1" stroke-dasharray="5 4"
      opacity="0.5" style="pointer-events:none"/>`;
  });

  // ── Table boxes (drawn after background arcs, before overlay arcs) ─────────
  allTables.forEach(t => {
    const { x, y, w, h } = pos[t];
    const inProfile = profiledSet.has(t);
    const cols = inProfile ? (relCanvasColumns[t] || []) : [];
    const isDriver   = driverTable && t === driverTable;
    const hdrFill    = isDriver ? 'var(--good,#3fb950)' : inProfile ? 'var(--accent)' : '#888';
    const hdrOpacity = (isDriver || inProfile) ? '0.92' : '0.45';
    const bdColor    = isDriver ? 'var(--good,#3fb950)' : inProfile ? 'var(--accent)' : 'var(--border)';
    const bdWidth    = (isDriver || inProfile) ? '2' : '1';
    const bdDash     = (isDriver || inProfile) ? '' : 'stroke-dasharray="6 3"';
    const tooltip    = isDriver
      ? 'Root/driver table — always included'
      : inProfile ? 'Click to remove from Table Profile' : 'Click to add to Table Profile';
    const clickHandler = isDriver
      ? ''   // driver table header is not toggleable
      : `onclick="canvasToggleProfile('${esc(t)}')"`;

    html += `<g>
      <rect x="${x}" y="${y}" width="${w}" height="${h}" rx="7"
        fill="var(--panel)" stroke="${bdColor}" stroke-width="${bdWidth}" ${bdDash}
        filter="url(#fshadow)"/>
      <rect x="${x}" y="${y}" width="${w}" height="${HEAD_H}" rx="7"
        fill="${hdrFill}" opacity="${hdrOpacity}" style="cursor:${isDriver ? 'default' : 'pointer'}"
        ${clickHandler}>
        <title>${esc(tooltip)}</title>
      </rect>
      <rect x="${x}" y="${y+HEAD_H-6}" width="${w}" height="6"
        fill="${hdrFill}" opacity="${hdrOpacity}" style="pointer-events:none"/>
      ${isDriver ? `<text x="${x+8}" y="${y+HEAD_H/2+1}" font-size="9" fill="white" font-family="sans-serif"
        opacity="0.85" style="pointer-events:none">ROOT</text>` : ''}
      <text x="${x+w/2}" y="${y+HEAD_H/2+5}" text-anchor="middle"
        font-size="12" font-weight="600" fill="white" font-family="sans-serif"
        style="pointer-events:none">${esc(t)}</text>`;

    if (!inProfile && !isDriver) {
      html += `<text x="${x+w/2}" y="${y+HEAD_H+20}" text-anchor="middle"
        font-size="11" fill="var(--muted)" font-family="sans-serif"
        style="pointer-events:none">click header to add to profile</text>`;
    } else {
      cols.forEach((col, ci) => {
        const cy = y + HEAD_H + ci * COL_H + COL_H / 2 + 5;
        // Highlight pending ports
        const isPendingParent = relPendingRel.parentTable === t && relPendingRel.colPairs.some(p => p.parentCol === col);
        const isPendingChild  = relPendingRel.childTable  === t && relPendingRel.colPairs.some(p => p.childCol  === col);
        const portStroke = (isPendingParent || isPendingChild) ? 'var(--warn,#e3a008)' : bdColor;
        html += `
        <text x="${x+14}" y="${cy+3}" font-size="11" fill="var(--text)" font-family="sans-serif"
          style="pointer-events:none">${esc(col)}</text>
        <circle cx="${x}" cy="${cy}" r="${PORT_R}" fill="var(--field)" stroke="${portStroke}"
          stroke-width="1.5" style="cursor:crosshair"
          onmousedown="canvasPortDragStart(event,'${esc(t)}','${esc(col)}','child')"/>
        <circle cx="${x+w}" cy="${cy}" r="${PORT_R}" fill="var(--field)" stroke="${portStroke}"
          stroke-width="1.5" style="cursor:crosshair"
          onmousedown="canvasPortDragStart(event,'${esc(t)}','${esc(col)}','parent')"/>`;
      });
    }
    html += `</g>`;
  });

  // ── User-defined relationship arcs (drawn ON TOP of boxes) ────────────────
  adUserRels.forEach(rel => {
    const pp = pos[rel.parentTable], cp = pos[rel.childTable];
    if (!pp || !cp) return;
    const pCols = rel.parentColumns.split(',').map(s => s.trim());
    const cCols = rel.childColumns.split(',').map(s => s.trim());
    pCols.forEach((pc, i) => {
      const cc = cCols[i] ?? cCols[0];
      const parentPort = _relPortPositions[`${rel.parentTable}||${pc}||parent`];
      const childPort  = _relPortPositions[`${rel.childTable}||${cc}||child`];
      const x1 = parentPort ? parentPort.x : pp.x + pp.w;
      const y1 = parentPort ? parentPort.cy : pp.y + HEAD_H / 2;
      const x2 = childPort  ? childPort.x  : cp.x;
      const y2 = childPort  ? childPort.cy : cp.y + HEAD_H / 2;
      const dx = Math.abs(x2 - x1) * 0.45;
      const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
      html += `<path d="M${x1},${y1} C${x1+dx},${y1} ${x2-dx},${y2} ${x2},${y2}"
        fill="none" stroke="var(--accent)" stroke-width="2" marker-end="url(#arr)" opacity="0.85"/>`;
      if (i === 0) {
        html += `
        <text x="${mx}" y="${my - 12}" text-anchor="middle" font-size="10"
          fill="var(--accent)" font-family="sans-serif" style="pointer-events:none"
          >${esc(rel.relName || rel.parentTable + ' → ' + rel.childTable)}</text>
        <circle cx="${mx}" cy="${my}" r="10" fill="var(--bad)" opacity="0.9" style="cursor:pointer"
          onclick="deleteUserRel(${rel.id})"/>
        <text x="${mx}" y="${my + 4}" text-anchor="middle" font-size="12"
          fill="white" font-family="sans-serif" font-weight="bold" style="pointer-events:none">✕</text>`;
      }
    });
  });

  // ── Pending relationship arcs (warn / orange, drawn ON TOP) ───────────────
  if (relPendingRel.parentTable && relPendingRel.colPairs.length) {
    relPendingRel.colPairs.forEach((cp, i) => {
      const parentPort = _relPortPositions[`${relPendingRel.parentTable}||${cp.parentCol}||parent`];
      const childPort  = _relPortPositions[`${relPendingRel.childTable}||${cp.childCol}||child`];
      // Fallback to box edge if port not found (table not profiled yet)
      const pBox = pos[relPendingRel.parentTable], cBox = pos[relPendingRel.childTable];
      if (!pBox || !cBox) return;
      const x1 = parentPort ? parentPort.x : pBox.x + pBox.w;
      const y1 = parentPort ? parentPort.cy : pBox.y + HEAD_H / 2;
      const x2 = childPort  ? childPort.x  : cBox.x;
      const y2 = childPort  ? childPort.cy : cBox.y + HEAD_H / 2;
      const dx = Math.abs(x2 - x1) * 0.45;
      const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
      html += `<path d="M${x1},${y1} C${x1+dx},${y1} ${x2-dx},${y2} ${x2},${y2}"
        fill="none" stroke="var(--warn,#e3a008)" stroke-width="2.5"
        stroke-dasharray="6 3" marker-end="url(#arr-warn)" opacity="0.95"/>`;
      if (i === 0) {
        html += `<text x="${mx}" y="${my - 10}" text-anchor="middle" font-size="10"
          fill="var(--warn,#e3a008)" font-family="sans-serif" font-weight="600"
          style="pointer-events:none">pending…</text>`;
      }
    });
  }

  // Drag preview path (hidden until drag starts)
  html += `<path id="rel-drag-path" d="M0,0" fill="none"
    stroke="var(--accent)" stroke-width="1.5" stroke-dasharray="7 4"
    visibility="hidden" style="pointer-events:none"/>`;

  svg.innerHTML = html;
  svg.onmousemove = canvasPortDragMove;
  svg.onmouseup   = canvasPortDragEnd;
  svg.onmouseleave = () => {
    relDragState = null;
    const p = $('rel-drag-path'); if (p) p.setAttribute('visibility', 'hidden');
  };
}

function canvasToggleProfile(tableName) {
  const idx = adProfiles.findIndex(p => p.tableName === tableName);
  if (idx >= 0) {
    adProfiles.splice(idx, 1);
    toast(`Removed ${tableName} from profile — Save All Profiles to persist`, 'ok');
    updateOverrideTableSelect(); updateProfiledTableSelects(); renderAdProfiles();
    renderRelCanvas(); // re-render in place (columns already cached, no new fetch needed)
  } else {
    adProfiles.push(newAdProfile(tableName));
    toast(`Added ${tableName} to profile — Save All Profiles to persist`, 'ok');
    updateOverrideTableSelect(); updateProfiledTableSelects(); renderAdProfiles();
    loadRelCanvas(); // reload to fetch columns for the newly added table
  }
}

function _svgCoords(e) {
  const svg = $('ad-rel-svg'); if (!svg) return { x: 0, y: 0 };
  const rect = svg.getBoundingClientRect();
  const vb = (svg.getAttribute('viewBox') || '0 0 800 600').split(' ').map(Number);
  return {
    x: (e.clientX - rect.left) * (vb[2] / rect.width),
    y: (e.clientY - rect.top)  * (vb[3] / rect.height)
  };
}

function canvasPortDragStart(e, table, col, side) {
  e.preventDefault();
  const { x, y } = _svgCoords(e);
  relDragState = { fromTable: table, fromCol: col, fromSide: side, startX: x, startY: y };
  const p = $('rel-drag-path');
  if (p) { p.setAttribute('d', `M${x},${y} L${x},${y}`); p.setAttribute('visibility', 'visible'); }
}

function canvasPortDragMove(e) {
  if (!relDragState) return;
  const { x, y } = _svgCoords(e);
  const sx = relDragState.startX, sy = relDragState.startY;
  const dx = Math.abs(x - sx) * 0.45;
  const p = $('rel-drag-path');
  if (p) p.setAttribute('d', `M${sx},${sy} C${sx+dx},${sy} ${x-dx},${y} ${x},${y}`);
}

function canvasPortDragEnd(e) {
  if (!relDragState) return;
  const state = relDragState; relDragState = null;
  const p = $('rel-drag-path'); if (p) p.setAttribute('visibility', 'hidden');

  // Find the closest port by coordinate proximity (avoids SVG closest() issues)
  const { x, y } = _svgCoords(e);
  let bestKey = null, bestDist = 20;
  for (const [key, pp] of Object.entries(_relPortPositions)) {
    const d = Math.hypot(x - pp.x, y - pp.cy);
    if (d < bestDist) { bestDist = d; bestKey = key; }
  }
  if (!bestKey) return;
  const pp = _relPortPositions[bestKey];
  const toTable = pp.table, toCol = pp.col, toSide = pp.side;
  if (toTable === state.fromTable) return;

  let parentTable, parentCol, childTable, childCol;
  if (state.fromSide === 'parent' && toSide === 'child') {
    parentTable = state.fromTable; parentCol = state.fromCol; childTable = toTable; childCol = toCol;
  } else if (state.fromSide === 'child' && toSide === 'parent') {
    parentTable = toTable; parentCol = toCol; childTable = state.fromTable; childCol = state.fromCol;
  } else {
    toast('Drag from a right ● (parent side) to a left ● (child side), or vice versa', 'err'); return;
  }

  // If there's an existing pending rel for a different pair of tables, reject
  if (relPendingRel.parentTable && (relPendingRel.parentTable !== parentTable || relPendingRel.childTable !== childTable)) {
    toast(`Save or clear the pending ${relPendingRel.parentTable} → ${relPendingRel.childTable} relationship first`, 'err');
    return;
  }

  // Check for duplicate column pair
  const alreadyExists = relPendingRel.colPairs.some(cp => cp.parentCol === parentCol && cp.childCol === childCol);
  if (alreadyExists) { toast('That column pair is already in the pending relationship', 'err'); return; }

  relPendingRel.parentTable = parentTable;
  relPendingRel.childTable = childTable;
  relPendingRel.colPairs.push({ parentCol, childCol });

  renderPendingRelPanel();
  renderRelCanvas();   // redraws including pending lines in warn colour
}

function renderPendingRelPanel() {
  const panel = $('rel-pending-panel');
  const summary = $('rel-pending-summary');
  if (!panel || !summary) return;
  if (!relPendingRel.parentTable || !relPendingRel.colPairs.length) {
    panel.style.display = 'none'; return;
  }
  panel.style.display = '';
  summary.innerHTML = `<div style="font-size:12px;margin-bottom:4px">
    <strong>${esc(relPendingRel.parentTable)}</strong> → <strong>${esc(relPendingRel.childTable)}</strong>
  </div>` +
  relPendingRel.colPairs.map((cp, i) =>
    `<div class="row" style="gap:6px;align-items:center;font-size:12px;margin-bottom:2px">
       <code>${esc(cp.parentCol)}</code>
       <span style="color:var(--accent)">→</span>
       <code>${esc(cp.childCol)}</code>
       <button class="small danger" style="padding:1px 6px;font-size:11px" onclick="removePendingColPair(${i})">✕</button>
     </div>`
  ).join('');
}

function removePendingColPair(idx) {
  relPendingRel.colPairs.splice(idx, 1);
  if (!relPendingRel.colPairs.length) { relPendingRel.parentTable = null; relPendingRel.childTable = null; }
  renderPendingRelPanel();
  renderRelCanvas();
}

async function savePendingRelCanvas() {
  if (!selectedAdId || !relPendingRel.parentTable || !relPendingRel.colPairs.length) return;
  const parentColumns = relPendingRel.colPairs.map(cp => cp.parentCol).join(',');
  const childColumns  = relPendingRel.colPairs.map(cp => cp.childCol).join(',');
  const relName       = ($('rel-pending-name')?.value.trim()) ||
                        `${relPendingRel.parentTable} → ${relPendingRel.childTable}`;
  try {
    await api.post(`/api/datasets/${selectedAdId}/user-rels`, {
      parentTable: relPendingRel.parentTable,
      parentColumns,
      childTable: relPendingRel.childTable,
      childColumns,
      relName
    });
    adUserRels = await api.get(`/api/datasets/${selectedAdId}/user-rels`);
    renderUserRels();
    clearPendingRelCanvas();
    toast(`Relationship saved: ${relPendingRel.parentTable ?? ''} → ${relPendingRel.childTable ?? ''}`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

function clearPendingRelCanvas() {
  relPendingRel = { parentTable: null, childTable: null, colPairs: [] };
  const nameEl = $('rel-pending-name'); if (nameEl) nameEl.value = '';
  renderPendingRelPanel();
  renderRelCanvas();
}

// ── Referential Integrity ─────────────────────────────────────────────────────

async function loadRefInt() {
  if (!selectedAdId) return;
  $('ad-refint-grid').innerHTML = '<div class="loading">Loading relationships…</div>';
  try {
    adRelationships = await api.get(`/api/datasets/${selectedAdId}/relationships`);
    adTraversalRules = await api.get(`/api/datasets/${selectedAdId}/traversal-rules`);
    renderRefInt();
  } catch (e) { $('ad-refint-grid').innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

function renderRefInt() {
  const el = $('ad-refint-grid'); if (!el) return;
  if (!adRelationships.length) {
    el.innerHTML = '<div class="empty">No FK relationships found for the included tables. Make sure the datasource is reachable and tables are in Table Profiles.</div>';
    return;
  }
  // Build a quick lookup of existing rules: "parent->child:source[:refId]" → traverseDirection
  const ruleMap = {};
  adTraversalRules.forEach(r => {
    const k = `${r.parentTable.toLowerCase()}->${r.childTable.toLowerCase()}:${r.relSource}${r.relRefId ? ':' + r.relRefId : ''}`;
    ruleMap[k] = r.traverseDirection;
  });

  el.innerHTML = `<div class="refint-table">
    <div class="refint-header">
      <span>Source</span><span>Parent table</span><span>Columns</span>
      <span>Child table</span><span>Traversal direction</span><span>Note</span>
    </div>` +
    adRelationships.map((r, idx) => {
      const ruleKey = `${r.parentTable.toLowerCase()}->${r.childTable.toLowerCase()}:${r.source}${r.relRefId ? ':' + r.relRefId : ''}`;
      const currentDir = ruleMap[ruleKey] || (r.traverseDirection || 'BOTH');
      const colPairs = r.parentColumns.map((pc, i) => `${pc}→${r.childColumns[i] ?? '?'}`).join(', ');
      return `<div class="refint-row" data-idx="${idx}">
        <span>${pill(r.source, r.source === 'USER' ? 'info' : 'dim')}</span>
        <span class="prof-table-name">${esc(r.parentTable)}</span>
        <span class="muted" style="font-size:11px"><code>${esc(colPairs)}</code>${r.relName ? `<br><em>${esc(r.relName)}</em>` : ''}</span>
        <span class="prof-table-name">${esc(r.childTable)}</span>
        <span><select class="refint-dir-sel" onchange="refintDirChanged(${idx}, this.value)">
          <option value="BOTH"${currentDir === 'BOTH' ? ' selected' : ''}>Both (Q1 + Q2)</option>
          <option value="Q1_ONLY"${currentDir === 'Q1_ONLY' ? ' selected' : ''}>Q1 only — parent pull</option>
          <option value="Q2_ONLY"${currentDir === 'Q2_ONLY' ? ' selected' : ''}>Q2 only — child pull</option>
          <option value="NONE"${currentDir === 'NONE' ? ' selected' : ''}>None — skip this relationship</option>
        </select></span>
        <span><input value="${esc(r.traversalNote ?? '')}" placeholder="optional note"
          style="width:100%" onchange="refintNoteChanged(${idx}, this.value)"></span>
      </div>`;
    }).join('') + '</div>';
}

function refintDirChanged(idx, dir) {
  if (!adRelationships[idx]) return;
  adRelationships[idx]._pendingDir = dir;
}

function refintNoteChanged(idx, note) {
  if (!adRelationships[idx]) return;
  adRelationships[idx]._pendingNote = note;
}

async function saveTraversalRules() {
  if (!selectedAdId) return;
  // Build the list of traversal rules from the current UI state
  const rules = adRelationships.map(r => ({
    parentTable: r.parentTable, childTable: r.childTable,
    relSource: r.source, relRefId: r.relRefId ?? null,
    traverseDirection: r._pendingDir || r.traverseDirection || 'BOTH',
    note: r._pendingNote ?? r.traversalNote ?? null, priority: 0
  }));
  try {
    adTraversalRules = await api.put(`/api/datasets/${selectedAdId}/traversal-rules`, rules);
    toast('Traversal rules saved', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

// ── Traversal Map ─────────────────────────────────────────────────────────────

async function loadTraversalMap() {
  const el = $('ad-travmap-out');
  if (!selectedAdId) {
    el.innerHTML = '<div class="empty">Select a DataScope blueprint first.</div>';
    return;
  }
  el.innerHTML = '<div class="loading">Fetching relationships and rules…</div>';
  try {
    // Always fetch fresh data so the map reflects the latest Ref Integrity settings
    [adRelationships, adTraversalRules, adProfiles] = await Promise.all([
      api.get(`/api/datasets/${selectedAdId}/relationships`),
      api.get(`/api/datasets/${selectedAdId}/traversal-rules`),
      api.get(`/api/datasets/${selectedAdId}/profiles`)
    ]);
    const def = datasetsList.find(d => d.id === selectedAdId);
    const includedTables = adProfiles.filter(p => p.included).map(p => p.tableName);
    if (!includedTables.length) {
      el.innerHTML = '<div class="empty">No included tables — add and include tables in Table Profiles first.</div>';
      return;
    }
    renderTraversalMap(def, includedTables);
  } catch (e) { el.innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

function renderTraversalMap(def, includedTables) {
  const el = $('ad-travmap-out'); if (!el) return;
  if (!adRelationships.length && !includedTables.length) {
    el.innerHTML = '<div class="empty">No relationships or included tables to map.</div>'; return;
  }

  // Build a per-table map of relationships grouped by direction
  const tableMap = {}; // tableName → { q1Parents: [], q2Children: [], skipped: [] }
  const allTables = new Set([...includedTables, ...adRelationships.map(r => r.parentTable), ...adRelationships.map(r => r.childTable)]);
  allTables.forEach(t => { tableMap[t] = { q1Parents: [], q2Children: [], skipped: [] }; });

  adRelationships.forEach(r => {
    const dir = r._pendingDir || r.traverseDirection || 'BOTH';
    const colPairs = r.parentColumns.map((pc, i) => `${pc}=${r.childColumns[i] ?? '?'}`).join(', ');
    const relLabel = `${r.parentTable}(${colPairs}) [${r.source}${r.relName ? ' · ' + r.relName : ''}]`;
    if (dir === 'NONE') {
      if (tableMap[r.childTable])  tableMap[r.childTable].skipped.push(`↑ skip parent ${r.parentTable}`);
      if (tableMap[r.parentTable]) tableMap[r.parentTable].skipped.push(`↓ skip child ${r.childTable}`);
    } else {
      if ((dir === 'BOTH' || dir === 'Q1_ONLY') && tableMap[r.childTable])
        tableMap[r.childTable].q1Parents.push({ table: r.parentTable, cols: colPairs, label: relLabel });
      if ((dir === 'BOTH' || dir === 'Q2_ONLY') && tableMap[r.parentTable])
        tableMap[r.parentTable].q2Children.push({ table: r.childTable, cols: colPairs, label: relLabel });
    }
  });

  const driverProfile = adProfiles.find(p => p.tableName === def?.driverTable);
  let html = `<div class="travmap-container">`;

  // Driver table entry
  if (def?.driverTable) {
    const strategy = driverProfile?.filterExpr ? ` — filter: <code>${esc(driverProfile.filterExpr)}</code>` : '';
    html += `<div class="travmap-section">
      <div class="travmap-driver">
        <span class="pill ok">DRIVER</span>
        <strong>${esc(def.driverTable)}</strong>
        ${def.driverFilter ? ` — seed WHERE <code>${esc(def.driverFilter)}</code>` : ' — seed: all rows (up to max)'}
        ${strategy}
      </div>`;
    html += renderTravMapRelations(tableMap[def.driverTable]);
    html += `</div>`;
  }

  // Remaining included tables
  includedTables.filter(t => t !== def?.driverTable).forEach(t => {
    const profile = adProfiles.find(p => p.tableName === t);
    const stratPill = profile?.referentialStrategy === 'INDEPENDENT' ? `<span class="pill info small">INDEPENDENT</span>` :
                      profile?.referentialStrategy === 'FOLLOW_PARENT' ? `<span class="pill dim small">FOLLOW_PARENT</span>` : '';
    const filterNote = profile?.filterExpr ? ` — filter: <code>${esc(profile.filterExpr)}</code>` : '';
    html += `<div class="travmap-section">
      <div class="travmap-table-title">${stratPill} <strong>${esc(t)}</strong>${filterNote}</div>`;
    html += renderTravMapRelations(tableMap[t]);
    html += `</div>`;
  });

  html += `</div>`;
  el.innerHTML = html;
}

function renderTravMapRelations(info) {
  if (!info) return '';
  let h = '';
  if (info.q1Parents.length) {
    h += `<div class="travmap-block q1-block"><span class="travmap-dir-label">Q1 — pulls these parents:</span><ul>`;
    info.q1Parents.forEach(p => {
      h += `<li><span class="travmap-arrow">↑</span> <code>${esc(p.table)}</code> <span class="muted">via ${esc(p.cols)}</span></li>`;
    });
    h += `</ul></div>`;
  }
  if (info.q2Children.length) {
    h += `<div class="travmap-block q2-block"><span class="travmap-dir-label">Q2 — pulls these children:</span><ul>`;
    info.q2Children.forEach(c => {
      h += `<li><span class="travmap-arrow">↓</span> <code>${esc(c.table)}</code> <span class="muted">via ${esc(c.cols)}</span></li>`;
    });
    h += `</ul></div>`;
  }
  if (info.skipped.length) {
    h += `<div class="travmap-block skip-block"><span class="travmap-dir-label muted">Skipped (direction=NONE):</span><ul>`;
    info.skipped.forEach(s => { h += `<li class="muted">${esc(s)}</li>`; });
    h += `</ul></div>`;
  }
  if (!info.q1Parents.length && !info.q2Children.length && !info.skipped.length) {
    h += `<div class="muted" style="font-size:12px;padding:4px 0 4px 12px">No FK relationships configured for this table.</div>`;
  }
  return h;
}

// ── Column Overrides ────────────────────────────────────────────────────────

function adOverrideTypeChanged() {
  const type = $('ad-ov-type')?.value;
  const wrap = $('ad-ov-literal-wrap');
  if (wrap) wrap.style.display = type === 'LITERAL' ? '' : 'none';
}

async function loadAdOverrideColumns() {
  const table = $('ad-ov-table')?.value;
  const def = selectedAdId ? datasetsList.find(d => d.id === selectedAdId) : null;
  if (!table || !def) return;
  adColumns = [];
  try {
    const schema = def.schemaName ?? '';
    const rows = await api.get(`/api/datasources/${def.dataSourceId}/tables/${encodeURIComponent(table)}/columns${schema ? '?schema=' + encodeURIComponent(schema) : ''}`);
    adColumns = rows.map(r => r.column || r.name || r.columnName || String(r));
  } catch (_) { adColumns = []; }
  setOptionsPreserve('ad-ov-col', '<option value="">Select column</option>' +
    adColumns.map(c => `<option value="${esc(c)}">${esc(c)}</option>`).join(''));
}

function renderAdOverrides() {
  const el = $('ad-overrides-grid');
  if (!el) return;
  if (!adOverrides.length) {
    el.innerHTML = '<div class="empty">No column overrides. Use the form above to add.</div>';
    return;
  }
  el.innerHTML = `<table>
    <tr><th>Source Table</th><th>Source Column</th><th>Target Column</th><th>Action</th><th>Literal Value</th><th>Note</th><th></th></tr>` +
    adOverrides.map(o => {
      const typePill = { USE_POLICY: 'dim', LITERAL: 'info', NULL_OUT: 'warn', SUPPRESS: 'bad' }[o.overrideType] || 'dim';
      return `<tr>
        <td>${esc(o.tableName)}</td>
        <td><code>${esc(o.sourceColumnName || (o.overrideType === 'USE_POLICY' ? o.columnName : ''))}</code></td>
        <td><code>${esc(o.columnName)}</code></td>
        <td>${pill(o.overrideType, typePill)}</td>
        <td>${o.overrideType === 'LITERAL' ? `<code>${esc(o.literalValue ?? '')}</code>` : '-'}</td>
        <td>${esc(o.note ?? '')}</td>
        <td><button class="small danger" onclick="deleteAdOverride(${o.id})">✕</button></td></tr>`;
    }).join('') + '</table>';
}

async function addAdOverride() {
  if (!selectedAdId) return;
  const tableName = $('ad-ov-table')?.value;
  const columnName = $('ad-ov-col')?.value;
  const overrideType = $('ad-ov-type')?.value;
  const literalValue = $('ad-ov-literal')?.value.trim() || null;
  const note = $('ad-ov-note')?.value.trim() || null;
  if (!tableName || !columnName || !overrideType) { toast('Table, column, and override type are required', 'err'); return; }
  if (overrideType === 'LITERAL' && !literalValue) { toast('Literal value is required for Literal override', 'err'); return; }
  try {
    const saved = await api.post(`/api/datasets/${selectedAdId}/overrides`,
      { tableName, columnName, sourceColumnName: overrideType === 'USE_POLICY' ? columnName : null,
        overrideType, literalValue, note });
    adOverrides = await api.get(`/api/datasets/${selectedAdId}/overrides`);
    renderAdOverrides();
    $('ad-ov-note').value = ''; $('ad-ov-literal').value = '';
    toast(`Override added for ${tableName}.${columnName}`, 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function deleteAdOverride(id) {
  try {
    await api.del(`/api/datasets/overrides/${id}`);
    adOverrides = adOverrides.filter(o => o.id !== id);
    renderAdOverrides();
    toast('Override removed', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

// ── Plan Preview ────────────────────────────────────────────────────────────

async function previewAdPlan() {
  if (!selectedAdId) return;
  const maxRows = parseInt($('ad-maxrows')?.value || '0');
  $('ad-plan-out').innerHTML = '<div class="loading">Running plan dry-run…</div>';
  activatePageTab('datasets', 'ds-tab-overrides', true);
  try {
    const plan = await api.post(`/api/datasets/${selectedAdId}/preview`, { maxDriverRows: maxRows });
    renderAdPlan(plan);
  } catch (e) {
    $('ad-plan-out').innerHTML = `<div class="error">${esc(e.message)}</div>`;
  }
}

function renderAdPlan(plan) {
  const el = $('ad-plan-out');
  if (!el) return;
  const modeClass = { Q1_Q2_CYCLE_CLOSURE: 'ok', Q1_PARENT_CLOSURE: 'info', Q2_CHILD_CLOSURE: 'info',
    DRIVER_ROW_LIMIT: 'dim', REFERENTIAL_CLOSURE: 'ok' }[plan.mode] || 'dim';
  let html = `<div class="plan-summary">
    <span>${pill(plan.mode, modeClass)}</span>
    <span>${plan.totalRows.toLocaleString()} total rows</span>
    <span class="muted">Driver: ${esc(plan.driverTable)}</span>
    ${plan.filter ? `<span class="muted">Filter: <code>${esc(plan.filter)}</code></span>` : ''}
  </div>`;
  if (plan.warnings?.length) {
    html += `<div class="plan-warnings">${plan.warnings.map(w => `<div class="warn-row">⚠ ${esc(w)}</div>`).join('')}</div>`;
  }
  html += `<table><tr><th>Table</th><th>Load order</th><th>Strategy</th><th>Rows</th><th>Status</th></tr>` +
    plan.loadOrder.map((table, i) => {
      const slice = plan.slices[table];
      const rows = plan.rowCounts?.[table] ?? (slice?.pkValues?.length ?? 0);
      const strategy = slice?.keyless ? 'row-limit' : 'PK-based';
      return `<tr>
        <td><b>${esc(table)}</b></td>
        <td>${i + 1}</td>
        <td>${pill(strategy, 'dim')}</td>
        <td>${rows.toLocaleString()}</td>
        <td>${pill(rows > 0 ? 'included' : 'empty', rows > 0 ? 'ok' : 'warn')}</td></tr>`;
    }).join('') + '</table>';
  el.innerHTML = html;
}

// ── Job form: dataset picker ────────────────────────────────────────────────

async function jobDatasetChanged() {
  const dsId = $('job-dataset')?.value;
  if (!dsId) return;
  const def = datasetsList.find(d => d.id == dsId);
  if (!def) return;
  if (def.dataSourceId && [...$('job-src').options].some(o => o.value === String(def.dataSourceId))) {
    $('job-src').value = String(def.dataSourceId);
  }
  if (def.targetDataSourceId && [...$('job-tgt').options].some(o => o.value === String(def.targetDataSourceId))) {
    $('job-tgt').value = String(def.targetDataSourceId);
  }
  if (def.policyId && [...$('job-policy').options].some(o => o.value === String(def.policyId))) {
    $('job-policy').value = String(def.policyId);
  } else if ($('job-policy')) {
    $('job-policy').value = '';
  }
  await loadJobSchemas();
  if (def.schemaName && [...$('job-src-schema').options].some(o => o.value === def.schemaName)) $('job-src-schema').value = def.schemaName;
  if (def.targetSchemaName && [...$('job-tgt-schema').options].some(o => o.value === def.targetSchemaName)) $('job-tgt-schema').value = def.targetSchemaName;
  // Auto-fill driver table and filter from definition if fields are empty
  if (!$('job-driver').value && def.driverTable) $('job-driver').value = def.driverTable;
  if (!$('job-filter').value && def.driverFilter) $('job-filter').value = def.driverFilter;
  $('job-q1').checked = def.globalQ1;
  $('job-q2').checked = def.globalQ2;
}

/* ---------- Copybook Studio (mainframe) ---------- */
let cbFileRecords = [];
let cbCurrentHex = '';
const CB_FUNCTIONS = ['FORMAT_PRESERVE','FIRST_NAME','LAST_NAME','FULL_NAME','EMAIL','PHONE','SSN','CREDIT_CARD',
  'DATE_SHIFT','DOB_AGE_BAND','ADDRESS_STREET','ADDRESS_US','CITY_STATE_ZIP','COMPANY','REDACT_KEEP_LAST4',
  'HASH_LOV','FIXED','NULLIFY','SEQUENCE','PASSTHROUGH'];

let cbMaskFields = [];   // decoded fields currently shown in the mask grid

function cbInit() { /* panel is input-driven; nothing to preload */ }

function cbSample() {
  $('cb-src').value = [
    '01 CUSTOMER-RECORD.',
    '   05 CUST-ID      PIC 9(5).',
    '   05 CUST-NAME    PIC X(10).',
    '   05 BALANCE      PIC S9(5)V99 COMP-3.',
    '   05 ACCT-COUNT   PIC 9(2) COMP.',
    '   05 STATUS-FLAG  PIC X.'
  ].join('\n');
  // CUST-ID 12345 · CUST-NAME ALICE · BALANCE -123.45 · ACCT-COUNT 7 · STATUS A  (EBCDIC Cp037)
  $('cb-hex').value = 'F1F2F3F4F5 C1D3C9C3C5 4040404040 0012345D 0007 C1';
  toast('Sample copybook + record loaded', 'ok');
}

function cbMode(m) {
  const hex = m === 'hex';
  $('cb-hex-pane').style.display = hex ? '' : 'none';
  $('cb-file-pane').style.display = hex ? 'none' : '';
  $('cb-seg-hex').classList.toggle('active', hex);
  $('cb-seg-file').classList.toggle('active', !hex);
}

async function cbParse() {
  try {
    const r = await api.post('/api/copybook/parse', { copybook: $('cb-src').value, codePage: $('cb-codepage').value });
    const rows = r.fields.map(f =>
      `<tr><td>${esc(f.path)}</td><td>${f.offset}</td><td>${f.length}</td><td><code>${esc(f.type)}</code></td></tr>`).join('');
    $('cb-layout').classList.remove('empty');
    $('cb-layout').innerHTML =
      `<p class="sub tight">Record <b>${esc(r.record)}</b> — fixed length <b>${r.recordLength}</b> bytes.</p>
       <table><thead><tr><th>Field</th><th>Offset</th><th>Len</th><th>Type</th></tr></thead><tbody>${rows}</tbody></table>`;
  } catch (e) { toast(e.message, 'err'); $('cb-layout').innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

function cbDecodedTable(r) {
  const rows = r.fields.map(f =>
    `<tr><td>${esc(f.path)}</td><td>${f.offset}</td><td>${f.length}</td><td><code>${esc(f.type)}</code></td>
     <td>${f.numeric ? pill('num', 'dim') : ''}</td><td><code>${esc(f.value)}</code></td></tr>`).join('');
  return `<p class="sub tight">Decoded ${r.fields.length} fields from ${r.byteLength} bytes (record length ${r.recordLength}).</p>
    <table><thead><tr><th>Field</th><th>Off</th><th>Len</th><th>Type</th><th></th><th>Value</th></tr></thead><tbody>${rows}</tbody></table>`;
}

function cbMaskFnOptions(selected) {
  return CB_FUNCTIONS.map(f => `<option ${f === selected ? 'selected' : ''}>${f}</option>`).join('');
}

/* sensible default masking function from the field name */
function cbGuessFn(path) {
  const p = (path || '').toUpperCase();
  if (p.includes('CARD')) return 'CREDIT_CARD';
  if (p.includes('PHONE') || p.includes('MOBILE') || p.includes('TEL')) return 'PHONE';
  if (p.includes('SSN')) return 'SSN';
  if (p.includes('EMAIL')) return 'EMAIL';
  if (p.includes('DOB') || p.includes('BIRTH') || p.includes('DATE')) return 'DOB_AGE_BAND';
  if (p.includes('FIRST')) return 'FIRST_NAME';
  if (p.includes('LAST') || p.includes('SURNAME')) return 'LAST_NAME';
  if (p.includes('NAME')) return 'FULL_NAME';
  if (p.includes('STREET') || p.includes('ADDR')) return 'ADDRESS_STREET';
  if (p.includes('CITY') || p.includes('STATE') || p.includes('ZIP') || p.includes('POSTAL')) return 'CITY_STATE_ZIP';
  if (p.includes('COMPANY') || p.includes('EMPLOYER')) return 'COMPANY';
  return 'FORMAT_PRESERVE';
}

/* one param cell — reuses the Masking Studio's option dropdowns (card mode, output case, etc.) */
function cbRowParamCell(i, fn, n) {
  const label = (MASK_PARAM_META[fn] || [])[n - 1];
  if (!label) return '<span class="param-na">-</span>';
  return `<label class="mini-param"><span>${esc(label)}</span>${paramControlHtml('cb-mk-p' + n + '-' + i, label, '')}</label>`;
}

function cbBuildMaskGrid(fields) {
  cbMaskFields = fields;
  const grid = $('cb-mask-grid');
  if (!grid) return;
  if (!fields.length) { grid.className = 'empty'; grid.textContent = 'No fields to mask.'; return; }
  grid.className = '';
  const rows = fields.map((f, i) => {
    const fn = cbGuessFn(f.path);
    return `<tr>
      <td><input type="checkbox" class="cb-mk-chk" data-idx="${i}"></td>
      <td>${esc(f.path)}</td>
      <td><code>${esc(f.type)}</code></td>
      <td><code>${esc(f.value)}</code></td>
      <td><select class="cb-mk-fn" data-idx="${i}" onchange="cbRowParams(${i})">${cbMaskFnOptions(fn)}</select></td>
      <td id="cb-mk-p1-cell-${i}">${cbRowParamCell(i, fn, 1)}</td>
      <td id="cb-mk-p2-cell-${i}">${cbRowParamCell(i, fn, 2)}</td>
    </tr>`;
  }).join('');
  grid.innerHTML = `<table><thead><tr><th></th><th>Field</th><th>Type</th><th>Value</th><th>Function</th><th>Param 1</th><th>Param 2</th></tr></thead><tbody>${rows}</tbody></table>`;
}

function cbRowParams(i) {
  const sel = document.querySelector(`.cb-mk-fn[data-idx="${i}"]`);
  if (!sel) return;
  $(`cb-mk-p1-cell-${i}`).innerHTML = cbRowParamCell(i, sel.value, 1);
  $(`cb-mk-p2-cell-${i}`).innerHTML = cbRowParamCell(i, sel.value, 2);
}

function cbCheckAll(v) { document.querySelectorAll('.cb-mk-chk').forEach(c => c.checked = v); }

function cbRowParamVal(i, n) { const el = $('cb-mk-p' + n + '-' + i); return el && el.value ? el.value : null; }

async function cbDecodeHex() {
  try {
    const r = await api.post('/api/copybook/decode',
      { copybook: $('cb-src').value, codePage: $('cb-codepage').value, hex: $('cb-hex').value });
    cbCurrentHex = $('cb-hex').value;
    $('cb-decoded').classList.remove('empty');
    $('cb-decoded').innerHTML = cbDecodedTable(r);
    cbBuildMaskGrid(r.fields);
  } catch (e) { toast(e.message, 'err'); $('cb-decoded').innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

async function cbDecodeFile() {
  const file = $('cb-file').files[0];
  if (!file) { toast('Choose a binary file first', 'err'); return; }
  const fd = new FormData();
  fd.append('file', file);
  fd.append('copybook', $('cb-src').value);
  fd.append('codePage', $('cb-codepage').value);
  fd.append('maxRecords', $('cb-maxrec').value || '50');
  try {
    const res = await fetch('/api/copybook/decode-file', { method: 'POST', body: fd });
    const text = await res.text();
    const data = text ? JSON.parse(text) : {};
    if (!res.ok) throw new Error(data.error || res.statusText);
    cbFileRecords = data.records || [];
    $('cb-rec-nav').style.display = '';
    $('cb-rec-select').innerHTML = cbFileRecords.map(rec => `<option value="${rec.index}">Record ${rec.index + 1}</option>`).join('');
    $('cb-rec-summary').textContent =
      `${data.recordCount} record(s) in file (${data.fileBytes} bytes, record length ${data.recordLength}); showing ${data.shown}` +
      (data.remainderBytes ? ` · ${data.remainderBytes} trailing bytes are not a full record` : '');
    cbShowRecord();
  } catch (e) { toast(e.message, 'err'); $('cb-decoded').innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

function cbShowRecord() {
  const idx = parseInt($('cb-rec-select').value, 10) || 0;
  const rec = cbFileRecords.find(r => r.index === idx) || cbFileRecords[0];
  if (!rec) return;
  cbCurrentHex = rec.hex;
  $('cb-decoded').classList.remove('empty');
  $('cb-decoded').innerHTML = cbDecodedTable({ fields: rec.fields, byteLength: rec.hex.length / 2, recordLength: rec.hex.length / 2 });
  cbBuildMaskGrid(rec.fields);
}

async function cbMaskBatch() {
  if (!cbCurrentHex) { toast('Decode a record first', 'err'); return; }
  const masks = [];
  document.querySelectorAll('.cb-mk-chk').forEach(c => {
    if (!c.checked) return;
    const i = c.dataset.idx;
    const fn = document.querySelector(`.cb-mk-fn[data-idx="${i}"]`).value;
    masks.push({ path: cbMaskFields[i].path, 'function': fn, param1: cbRowParamVal(i, 1), param2: cbRowParamVal(i, 2) });
  });
  if (!masks.length) { toast('Check at least one field to mask', 'err'); return; }
  try {
    const r = await api.post('/api/copybook/mask-preview',
      { copybook: $('cb-src').value, codePage: $('cb-codepage').value, hex: cbCurrentHex, masks });
    const rows = (r.fields || []).map(f =>
      `<tr><td>${esc(f.path)}</td><td><code>${esc(f.before ?? '')}</code></td>
       <td>${f.error ? `<span class="pill bad">${esc(f.error)}</span>` : `<code>${esc(f.after ?? '')}</code>`}</td></tr>`).join('');
    const out = $('cb-mask-out');
    out.classList.remove('empty');
    out.innerHTML =
      `<table><thead><tr><th>Field</th><th>Before</th><th>After</th></tr></thead><tbody>${rows}</tbody></table>
       <p class="sub tight">${r.bytesChanged} byte(s) changed across ${(r.fields || []).length} field(s).</p>
       <div style="display:grid;gap:8px;margin-top:8px">
         <div><label>Before bytes</label><code style="display:block;word-break:break-all">${esc(r.beforeHex)}</code></div>
         <div><label>After bytes</label><code style="display:block;word-break:break-all">${esc(r.afterHex)}</code></div>
       </div>`;
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- Mainframe pipeline ---------- */
let mfConns = [], mfCopybooks = [], mfEditCpyId = null, mfMapId = null, mfBrowseFiles = [], mfConnTests = {};

async function mfInit() {
  try {
    [mfConns, mfCopybooks] = await Promise.all([
      api.get('/api/mainframe/connections'), api.get('/api/mainframe/copybooks')]);
    mfConnTypeChanged();
    mfRenderConns();
    mfRenderCopybooks();
    mfFillConnSelects();
    mfRefreshCopybookSelects();
    if (!document.querySelector('.mf-file-row')) mfAddFileRow();
    await mfRenderJobs();
  } catch (e) { toast(e.message, 'err'); }
}

// ---- connections
function mfConnTypeChanged() {
  const t = $('mf-conn-type').value;
  $('mf-conn-local').style.display = t === 'LOCAL' ? '' : 'none';
  $('mf-conn-zowe').style.display = t === 'ZOWE' ? '' : 'none';
}

async function mfAddConn() {
  const t = $('mf-conn-type').value;
  const body = { name: $('mf-conn-name').value, type: t, codePage: $('mf-conn-cp').value };
  if (t === 'LOCAL') body.baseDir = $('mf-conn-dir').value;
  else {
    body.host = $('mf-conn-host').value;
    body.port = $('mf-conn-port').value ? parseInt($('mf-conn-port').value) : null;
    body.basePath = $('mf-conn-path').value;
    body.username = $('mf-conn-user').value;
    body.password = $('mf-conn-pass').value;
    body.trustAllCerts = $('mf-conn-trust').checked;
  }
  try {
    await api.post('/api/mainframe/connections', body);
    toast('Connection added', 'ok');
    $('mf-conn-name').value = '';
    mfConns = await api.get('/api/mainframe/connections');
    mfRenderConns(); mfFillConnSelects();
  } catch (e) { toast(e.message, 'err'); }
}

function mfConnTestStatus(c) {
  const st = mfConnTests[c.id];
  if (!st) return '<span class="ds-health untested">○ Not tested</span>';
  if (st.state === 'testing') {
    const elapsed = Math.max(0, Math.floor((Date.now() - st.startedAt) / 1000));
    const note = elapsed > 25 ? 'Still waiting on host or credentials'
      : elapsed > 10 ? 'Waiting for z/OSMF response' : (st.note || '');
    return `<span class="ds-health testing">◌ Testing... ${elapsed}s</span><div class="mf-test-note">${esc(note)}</div>`;
  }
  if (st.state === 'online') return `<span class="ds-health online">● OK</span><div class="mf-test-note">${esc(st.info || '')}</div>`;
  if (st.state === 'offline') return `<span class="ds-health offline">○ Failed</span><div class="mf-test-note" title="${esc(st.info || '')}">${esc(st.info || '')}</div>`;
  return '<span class="ds-health untested">○ Not tested</span>';
}

function mfRenderConns() {
  const el = $('mf-conn-list');
  if (!mfConns.length) { el.innerHTML = '<div class="empty">No connections yet.</div>'; return; }
  const rows = mfConns.map(c => {
    const testing = mfConnTests[c.id]?.state === 'testing';
    return `<tr><td>${esc(c.name)}</td><td>${pill(c.type, c.type === 'ZOWE' ? 'info' : 'dim')}</td>
    <td><code>${esc(c.type === 'LOCAL' ? (c.baseDir || '') : (c.host + ':' + (c.port || '') + (c.basePath || '')))}</code></td>
    <td>${esc(c.codePage)}</td><td>${mfConnTestStatus(c)}</td>
    <td><button class="ghost small" onclick="mfTestConn(${c.id})" ${testing ? 'disabled' : ''}>${testing ? 'Testing...' : 'Test'}</button>
        <button class="ghost small danger" onclick="mfDeleteConn(${c.id})" ${testing ? 'disabled' : ''}>Delete</button></td></tr>`;
  }).join('');
  el.innerHTML = `<table><thead><tr><th>Name</th><th>Type</th><th>Endpoint</th><th>CP</th><th>Test status</th><th></th></tr></thead><tbody>${rows}</tbody></table>`;
}

async function mfTestConn(id) {
  const c = mfConns.find(x => x.id === id) || {};
  mfConnTests[id] = {
    state: 'testing',
    startedAt: Date.now(),
    note: (c.type || '').toUpperCase() === 'ZOWE' ? 'Contacting z/OSMF and listing datasets' : 'Checking landing folder'
  };
  mfRenderConns();
  const ticker = setInterval(mfRenderConns, 1000);
  const done = () => { clearInterval(ticker); mfRenderConns(); };
  try {
    const r = await api.post(`/api/mainframe/connections/${id}/test`);
    mfConnTests[id] = r.ok
      ? { state: 'online', info: `${r.count || 0} file/dataset(s) visible` }
      : { state: 'offline', info: r.error || 'Connection test failed' };
    done();
    toast(r.ok ? `OK — ${r.count} file(s) visible` : `Failed: ${r.error}`, r.ok ? 'ok' : 'err');
  } catch (e) {
    mfConnTests[id] = { state: 'offline', info: e.message };
    done();
    toast(e.message, 'err');
  }
}

async function mfDeleteConn(id) {
  try { await api.del(`/api/mainframe/connections/${id}`); mfConns = await api.get('/api/mainframe/connections'); mfRenderConns(); mfFillConnSelects(); }
  catch (e) { toast(e.message, 'err'); }
}

// ---- copybooks
function mfCpySample() {
  $('mf-cpy-name').value = 'customer-record';
  $('mf-cpy-src').value = [
    '01 CUSTOMER-RECORD.',
    '   05 CUST-ID        PIC 9(6).',
    '   05 CUST-NAME      PIC X(12).',
    '   05 CARD-NUMBER    PIC 9(16) COMP-3.',
    '   05 DATE-OF-BIRTH  PIC 9(8)  COMP-3.',
    '   05 PHONE-NUMBER   PIC 9(10) COMP-3.',
    '   05 STATUS-FLAG    PIC X.'
  ].join('\n');
}

async function mfSaveCpy() {
  const body = { name: $('mf-cpy-name').value, source: $('mf-cpy-src').value, codePage: $('mf-cpy-cp').value };
  try {
    if (mfEditCpyId) await api.put(`/api/mainframe/copybooks/${mfEditCpyId}`, body);
    else await api.post('/api/mainframe/copybooks', body);
    toast(mfEditCpyId ? 'Copybook updated' : 'Copybook saved', 'ok');
    mfEditCpyId = null;
    mfCopybooks = await api.get('/api/mainframe/copybooks');
    mfRenderCopybooks();
    mfRefreshCopybookSelects();
  } catch (e) { toast(e.message, 'err'); }
}

function mfRenderCopybooks() {
  const el = $('mf-cpy-list');
  if (!mfCopybooks.length) { el.innerHTML = '<div class="empty">No copybooks yet.</div>'; return; }
  const rows = mfCopybooks.map(c => `<tr><td>${esc(c.name)}</td><td>${esc(c.recordName || '')}</td><td>${c.recordLength ?? ''}</td><td>${esc(c.codePage)}</td>
    <td><button class="ghost small" onclick="mfEditCpy(${c.id})">Edit</button>
        <button class="ghost small" onclick="mfOpenMap(${c.id})">Field map</button>
        <button class="ghost small danger" onclick="mfDeleteCpy(${c.id})">Delete</button></td></tr>`).join('');
  el.innerHTML = `<table><thead><tr><th>Name</th><th>Record</th><th>Len</th><th>CP</th><th></th></tr></thead><tbody>${rows}</tbody></table>`;
}

async function mfEditCpy(id) {
  try {
    const c = await api.get(`/api/mainframe/copybooks/${id}`);
    $('mf-cpy-name').value = c.name; $('mf-cpy-cp').value = c.codePage; $('mf-cpy-src').value = c.source;
    mfEditCpyId = id;
    toast('Loaded for edit — Save to update', 'ok');
  } catch (e) { toast(e.message, 'err'); }
}

async function mfDeleteCpy(id) {
  try { await api.del(`/api/mainframe/copybooks/${id}`); mfCopybooks = await api.get('/api/mainframe/copybooks'); mfRenderCopybooks(); $('mf-cpy-map').className = 'empty'; $('mf-cpy-map').textContent = 'Open a copybook’s field map to configure masking.'; }
  catch (e) { toast(e.message, 'err'); }
}

async function mfOpenMap(id) {
  try {
    const [fields, masks] = await Promise.all([
      api.get(`/api/mainframe/copybooks/${id}/fields`), api.get(`/api/mainframe/copybooks/${id}/masks`)]);
    mfRenderMap(id, fields, masks);
  } catch (e) { toast(e.message, 'err'); }
}

function mfGuessFn(path) {
  const p = (path || '').toUpperCase();
  if (p.includes('CARD')) return 'CREDIT_CARD';
  if (p.includes('PHONE') || p.includes('MOBILE')) return 'PHONE';
  if (p.includes('SSN')) return 'SSN';
  if (p.includes('EMAIL')) return 'EMAIL';
  if (p.includes('DOB') || p.includes('BIRTH')) return 'DOB_AGE_BAND';
  if (p.includes('FIRST')) return 'FIRST_NAME';
  if (p.includes('LAST') || p.includes('SURNAME')) return 'LAST_NAME';
  if (p.includes('NAME')) return 'FULL_NAME';
  if (p.includes('STREET') || p.includes('ADDR')) return 'ADDRESS_STREET';
  if (p.includes('CITY') || p.includes('STATE') || p.includes('ZIP')) return 'CITY_STATE_ZIP';
  return 'NONE';
}

function mfMapFnOptions(sel) {
  return ['NONE', ...CB_FUNCTIONS].map(f => `<option ${f === sel ? 'selected' : ''}>${f}</option>`).join('');
}

function mfMapParamCell(i, fn, n, val) {
  if (fn === 'NONE') return '<span class="param-na">-</span>';
  const label = (MASK_PARAM_META[fn] || [])[n - 1];
  if (!label) return '<span class="param-na">-</span>';
  return `<label class="mini-param"><span>${esc(label)}</span>${paramControlHtml('mf-map-p' + n + '-' + i, label, val || '')}</label>`;
}

function mfRenderMap(id, fields, masks) {
  mfMapId = id;
  const byPath = {}; masks.forEach(m => byPath[m.fieldPath.toUpperCase()] = m);
  const name = (mfCopybooks.find(c => c.id === id) || {}).name || '';
  const rows = fields.map((f, i) => {
    const ex = byPath[f.path.toUpperCase()];
    const fn = ex ? ex.function : mfGuessFn(f.path);
    return `<tr><td>${esc(f.path)}</td><td><code>${esc(f.type)}</code></td><td>${f.offset}</td><td>${f.length}</td>
      <td><select class="mf-map-fn" data-idx="${i}" data-path="${esc(f.path)}" onchange="mfMapFnChanged(${i})">${mfMapFnOptions(fn)}</select></td>
      <td id="mf-map-p1-cell-${i}">${mfMapParamCell(i, fn, 1, ex ? ex.param1 : '')}</td>
      <td id="mf-map-p2-cell-${i}">${mfMapParamCell(i, fn, 2, ex ? ex.param2 : '')}</td></tr>`;
  }).join('');
  const el = $('mf-cpy-map'); el.className = '';
  el.innerHTML = `<h3 class="section-title">Field map — ${esc(name)}</h3>
    <p class="sub tight">Set NONE to leave a field unmasked. Saved per copybook and applied to every job that uses it.</p>
    <table><thead><tr><th>Field</th><th>Type</th><th>Off</th><th>Len</th><th>Function</th><th>Param 1</th><th>Param 2</th></tr></thead><tbody>${rows}</tbody></table>
    <div class="row push" style="margin-top:10px"><button onclick="mfSaveMap()">Save field map</button></div>`;
}

function mfMapFnChanged(i) {
  const sel = document.querySelector(`.mf-map-fn[data-idx="${i}"]`);
  $(`mf-map-p1-cell-${i}`).innerHTML = mfMapParamCell(i, sel.value, 1, '');
  $(`mf-map-p2-cell-${i}`).innerHTML = mfMapParamCell(i, sel.value, 2, '');
}

function mfMapVal(i, n) { const el = $('mf-map-p' + n + '-' + i); return el && el.value ? el.value : null; }

async function mfSaveMap() {
  if (!mfMapId) return;
  const masks = [];
  document.querySelectorAll('.mf-map-fn').forEach(sel => {
    if (sel.value === 'NONE') return;
    const i = sel.dataset.idx;
    masks.push({ fieldPath: sel.dataset.path, 'function': sel.value, param1: mfMapVal(i, 1), param2: mfMapVal(i, 2) });
  });
  try { await api.put(`/api/mainframe/copybooks/${mfMapId}/masks`, masks); toast(`Saved ${masks.length} field rule(s)`, 'ok'); }
  catch (e) { toast(e.message, 'err'); }
}

// ---- jobs
function mfFillConnSelects() {
  const opts = mfConns.map(c => `<option value="${c.id}">${esc(c.name)} (${esc(c.type)})</option>`).join('');
  setOptionsPreserve('mf-job-src', opts);
  setOptionsPreserve('mf-job-tgt', opts);
}

function mfConnOptions(sel) {
  return mfConns.map(c => `<option value="${c.id}" ${c.id == sel ? 'selected' : ''}>${esc(c.name)}</option>`).join('');
}
function mfCpyOptions(sel) {
  return '<option value="">Select copybook</option>' +
    mfCopybooks.map(c => `<option value="${c.id}" ${c.id == sel ? 'selected' : ''}>${esc(c.name)}</option>`).join('');
}

function mfRefreshCopybookSelects() {
  document.querySelectorAll('.mf-f-cpy').forEach(sel => {
    const cur = sel.value;
    sel.innerHTML = mfCpyOptions(cur);
    if ([...sel.options].some(o => o.value === cur)) sel.value = cur;
  });
}

function mfAddFileRow(prefill) {
  prefill = prefill || {};
  const row = document.createElement('div');
  row.className = 'syn-row mf-file-row';
  row.innerHTML = `
    <div><label>Source dataset/file</label><input class="mf-f-src" value="${esc(prefill.sourceName || '')}"></div>
    <div><label>Copybook</label><select class="mf-f-cpy">${mfCpyOptions(prefill.copybookId)}</select></div>
    <div><label>RECFM</label><select class="mf-f-recfm"><option value="FB" ${prefill.recfm === 'VB' ? '' : 'selected'}>FB</option><option value="VB" ${prefill.recfm === 'VB' ? 'selected' : ''}>VB</option></select></div>
    <div><label>LRECL</label><input class="mf-f-lrecl" type="number" value="${esc(prefill.lrecl ?? '')}" placeholder="auto"></div>
    <div><label>Code page</label><input class="mf-f-cp" placeholder="inherit"></div>
    <div><label>Target LPAR</label><select class="mf-f-tgt"><option value="">(job target)</option>${mfConnOptions()}</select></div>
    <div><label>Target name</label><input class="mf-f-tname" placeholder="same as source"></div>
    <button class="small danger" onclick="this.closest('.mf-file-row').remove()">x</button>`;
  $('mf-files').appendChild(row);
}

async function mfBrowseSource() {
  const src = $('mf-job-src').value;
  if (!src) { toast('Pick a source LPAR first', 'err'); return; }
  const el = $('mf-browse'); el.style.display = ''; el.className = ''; el.innerHTML = '<div class="loading">Listing files…</div>';
  try {
    mfBrowseFiles = await api.get(`/api/mainframe/connections/${src}/files?pattern=*`);
    if (!mfBrowseFiles.length) { el.innerHTML = '<div class="empty">No files on this LPAR.</div>'; return; }
    el.innerHTML = `<table><thead><tr><th>Name</th><th>RECFM</th><th>LRECL</th><th>Size</th><th></th></tr></thead><tbody>` +
      mfBrowseFiles.map((f, i) => `<tr><td>${esc(f.name)}</td><td>${esc(f.recfm || '')}</td><td>${f.lrecl ?? ''}</td><td>${f.sizeBytes ?? ''}</td>
        <td><button class="ghost small" onclick="mfAddFromBrowse(${i})">Add</button></td></tr>`).join('') + `</tbody></table>`;
  } catch (e) { el.innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

function mfAddFromBrowse(i) {
  const f = mfBrowseFiles[i];
  mfAddFileRow({ sourceName: f.name, recfm: f.recfm, lrecl: f.lrecl });
  toast('Added ' + f.name, 'ok');
}

function mfFileRows() {
  return [...document.querySelectorAll('.mf-file-row')].map(r => ({
    sourceName: r.querySelector('.mf-f-src').value.trim(),
    copybookId: r.querySelector('.mf-f-cpy').value ? parseInt(r.querySelector('.mf-f-cpy').value) : null,
    recfm: r.querySelector('.mf-f-recfm').value,
    lrecl: r.querySelector('.mf-f-lrecl').value ? parseInt(r.querySelector('.mf-f-lrecl').value) : null,
    codePage: r.querySelector('.mf-f-cp').value || null,
    targetConnectionId: r.querySelector('.mf-f-tgt').value ? parseInt(r.querySelector('.mf-f-tgt').value) : null,
    targetName: r.querySelector('.mf-f-tname').value || null
  })).filter(f => f.sourceName);
}

async function mfLaunchJob() {
  const files = mfFileRows();
  if (!files.length) { toast('Add at least one file', 'err'); return; }
  const body = {
    name: $('mf-job-name').value,
    sourceConnectionId: $('mf-job-src').value ? parseInt($('mf-job-src').value) : null,
    targetConnectionId: $('mf-job-tgt').value ? parseInt($('mf-job-tgt').value) : null,
    maskingSeed: $('mf-job-seed').value || null,
    files
  };
  try { await api.post('/api/mainframe/jobs', body); toast('Job launched', 'ok'); await mfRenderJobs(); }
  catch (e) { toast(e.message, 'err'); }
}

async function mfRenderJobs() {
  try {
    const list = await api.get('/api/mainframe/jobs');
    const el = $('mf-jobs');
    if (!list.length) { el.innerHTML = '<div class="empty">No jobs yet.</div>'; return; }
    el.innerHTML = `<table><thead><tr><th>Job</th><th>Status</th><th>Files</th><th>Records</th><th></th></tr></thead><tbody>` +
      list.map(j => `<tr><td>${esc(j.name)}</td><td>${statusPill(j.status)}</td><td>${j.filesDone}/${j.filesTotal}</td><td>${j.recordsProcessed}</td>
        <td><button class="ghost small" onclick="mfViewJob(${j.id})">Details</button></td></tr>`).join('') + `</tbody></table>`;
  } catch (e) { toast(e.message, 'err'); }
}

async function mfViewJob(id) {
  try {
    const r = await api.get(`/api/mainframe/jobs/${id}`);
    const files = r.files || [];
    const rows = files.map(f => `<tr><td>${esc(f.sourceName)}</td><td>${esc(f.recfm)}</td><td>${f.recordCount}</td>
      <td>${statusPill(f.status)}</td><td>${esc(f.targetName || f.sourceName)}</td><td class="muted">${esc(f.message || '')}</td></tr>`).join('');
    $('mf-job-detail').innerHTML = `<h3 class="section-title">${esc(r.job.name)} — ${statusPill(r.job.status)}</h3>
      <p class="sub tight">${esc(r.job.message || '')}</p>
      <table><thead><tr><th>Source</th><th>RECFM</th><th>Records</th><th>Status</th><th>Target name</th><th>Message</th></tr></thead><tbody>${rows}</tbody></table>`;
  } catch (e) { toast(e.message, 'err'); }
}

/* ---------- MF File Generator ---------- */
let mfgCopybooks = [], mfgFields = [], mfgLastResult = null;

async function mfgInit() {
  try {
    await loadGeneratorCatalog();
    mfgCopybooks = await api.get('/api/mainframe/copybooks');
    setOptionsPreserve('mfg-cpy', '<option value="">Select copybook</option>' +
      mfgCopybooks.map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join(''));
    const conns = await api.get('/api/mainframe/connections');
    setOptionsPreserve('mfg-target', '<option value="">Select LPAR</option>' +
      conns.map(c => `<option value="${c.id}">${esc(c.name)} (${esc(c.type)})</option>`).join(''));
    mfgOutputChanged();
    if ($('mfg-cpy').value) await mfgSelectCopybook();
  } catch (e) { toast(e.message, 'err'); }
}

function mfgCpySample() {
  $('mfg-cpy-name').value = 'customer-record';
  $('mfg-cpy-src').value = [
    '01 CUSTOMER-RECORD.',
    '   05 CUST-ID         PIC 9(6).',
    '   05 CUST-NAME       PIC X(20).',
    '   05 ACCOUNT-NUMBER  PIC 9(16) COMP-3.',
    '   05 BALANCE         PIC S9(7)V99 COMP-3.',
    '   05 OPEN-DATE       PIC 9(8).',
    '   05 STATUS-FLAG     PIC X.'
  ].join('\n');
}

async function mfgSaveCopybook() {
  const body = { name: $('mfg-cpy-name').value, source: $('mfg-cpy-src').value, codePage: $('mfg-cpy-cp').value };
  try {
    const saved = await api.post('/api/mainframe/copybooks', body);
    toast('Copybook saved', 'ok');
    mfgCopybooks = await api.get('/api/mainframe/copybooks');
    setOptionsPreserve('mfg-cpy', '<option value="">Select copybook</option>' +
      mfgCopybooks.map(c => `<option value="${c.id}">${esc(c.name)}</option>`).join(''));
    $('mfg-cpy').value = String(saved.id);
    await mfgSelectCopybook();
  } catch (e) { toast(e.message, 'err'); }
}

async function mfgSelectCopybook() {
  const id = $('mfg-cpy').value;
  if (!id) { $('mfg-fields').innerHTML = '<div class="empty">Select or save a copybook on tab 1 first.</div>'; $('mfg-cpy-info').textContent = ''; return; }
  const cpy = mfgCopybooks.find(c => String(c.id) === id);
  if (cpy) { $('mfg-cpy-info').textContent = `${cpy.recordName || ''} · ${cpy.recordLength ?? '?'} bytes · ${cpy.codePage}`; $('mfg-codepage').value = cpy.codePage || 'Cp037'; }
  await mfgLoadFields();
}

async function mfgLoadFields() {
  const id = $('mfg-cpy').value;
  if (!id) return;
  try {
    mfgFields = await api.get(`/api/mainframe/copybooks/${id}/fields`);
    const rows = mfgFields.map(f => {
      const s = mfgSuggest(f);
      return `<div class="syn-row mfg-field" data-field="${esc(f.path)}">
        <div><label>Field</label><input value="${esc(f.path)}" disabled></div>
        <div><label>Type</label><input value="${esc(f.type)}" disabled></div>
        <div><label>Generator</label><select class="mfg-gen" onchange="previewGenerator(this.value)">${genOptions(s.gen)}</select></div>
        <div><label>Param1</label><input class="mfg-p1" value="${esc(s.p1)}"></div>
        <div><label>Param2</label><input class="mfg-p2" value="${esc(s.p2)}"></div>
      </div>`;
    }).join('');
    $('mfg-fields').innerHTML = rows || '<div class="empty">No fields in this copybook.</div>';
  } catch (e) { toast(e.message, 'err'); $('mfg-fields').innerHTML = `<div class="error">${esc(e.message)}</div>`; }
}

function mfgParsePic(pic) {
  const s = (pic || '').toUpperCase();
  let total = 0, scale = 0, afterV = false, i = 0;
  while (i < s.length) {
    const ch = s[i];
    if (ch === '9') {
      let rep = 1;
      if (s[i + 1] === '(') { const close = s.indexOf(')', i + 2); rep = parseInt(s.slice(i + 2, close)) || 1; i = close; }
      total += rep; if (afterV) scale += rep;
    } else if (ch === 'V') { afterV = true; }
    i++;
  }
  return { total, scale, intDigits: Math.max(1, total - scale) };
}

/* suggest a generator (and fitting params) for a copybook field */
function mfgSuggest(f) {
  const n = (f.path || '').toUpperCase();
  if (!f.numeric) {
    if (n.includes('CARD')) return { gen: 'CREDIT_CARD_VISA', p1: '', p2: '' };
    if (n.includes('PHONE')) return { gen: 'PHONE_US', p1: '', p2: '' };
    if (n.includes('SSN')) return { gen: 'SSN', p1: '', p2: '' };
    if (n.includes('EMAIL')) return { gen: 'EMAIL', p1: '', p2: '' };
    if (n.includes('FIRST')) return { gen: 'FIRST_NAME', p1: '', p2: '' };
    if (n.includes('LAST')) return { gen: 'LAST_NAME', p1: '', p2: '' };
    if (n.includes('NAME')) return { gen: 'FULL_NAME', p1: '', p2: '' };
    if (n.includes('CITY')) return { gen: 'CITY', p1: '', p2: '' };
    if (n.includes('STATE')) return { gen: 'STATE', p1: '', p2: '' };
    if (n.includes('STATUS') || n.includes('FLAG')) return { gen: 'ALPHA', p1: String(f.length), p2: '' };
    return { gen: 'ALPHANUMERIC', p1: String(f.length), p2: '' };  // codec truncates to field width
  }
  // numeric field — fit value to the picture's digits/scale so it never overflows
  const pic = (f.type || '').split(' ').slice(1).join(' ');
  const m = mfgParsePic(pic);
  if (n.includes('ACCOUNT')) return { gen: 'NUMERIC_STRING', p1: String(m.total), p2: '' };
  if (n.includes('DATE')) return { gen: 'NUMERIC_STRING', p1: String(m.total), p2: '' };
  if (m.scale > 0) { const max = Math.max(1, Math.pow(10, m.intDigits) - 1); return { gen: 'DECIMAL_RANGE', p1: '0', p2: String(max) }; }
  return { gen: 'NUMERIC_STRING', p1: String(m.total), p2: '' };
}

function mfgOutputChanged() {
  $('mfg-target-row').style.display = $('mfg-output').value === 'TARGET' ? 'flex' : 'none';
}

async function mfgGenerate() {
  const id = $('mfg-cpy').value;
  if (!id) return toast('Select a copybook on tab 1', 'err');
  const columns = [...document.querySelectorAll('.mfg-field')].map(r => ({
    field: r.dataset.field,
    generator: r.querySelector('.mfg-gen').value,
    param1: r.querySelector('.mfg-p1').value || null,
    param2: r.querySelector('.mfg-p2').value || null
  }));
  if (!columns.length) return toast('Load a copybook with fields first', 'err');
  const output = $('mfg-output').value;
  const body = {
    copybookId: parseInt(id), codePage: $('mfg-codepage').value || null, recfm: $('mfg-recfm').value,
    seed: parseInt($('mfg-seed').value || '42'), rowCount: parseInt($('mfg-rows').value || '100'),
    columns, output,
    targetConnectionId: output === 'TARGET' && $('mfg-target').value ? parseInt($('mfg-target').value) : null,
    targetName: $('mfg-target-name').value || null
  };
  const out = $('mfg-result'); out.classList.remove('empty'); out.innerHTML = '<div class="loading">Generating…</div>';
  try {
    const r = await api.post('/api/mainframe/generate-file', body);
    mfgLastResult = r;
    let html = `<p class="sub tight">Generated ${r.rowCount} records · RECFM ${esc(r.recfm)} · LRECL ${r.recordLength} · ${esc(r.codePage)}.</p>`;
    if (r.delivered) html += `<p class="sub tight">Delivered to <b>${esc(r.delivered.connection)}</b> as <code>${esc(r.delivered.name)}</code> (${r.delivered.bytes} bytes).</p>`;
    html += `<div class="row push">
      <button class="ghost small" onclick="mfgDownloadText('copybook')">Download copybook</button>
      <button class="ghost small" onclick="mfgDownloadText('pre')">Download pre-EBCDIC (CSV)</button>
      <button onclick="mfgDownloadBinary()">Download EBCDIC (.dat)</button></div>`;
    out.innerHTML = html;
  } catch (e) { out.innerHTML = `<div class="error">${esc(e.message)}</div>`; toast(e.message, 'err'); }
}

function mfgDownloadText(which) {
  const r = mfgLastResult; if (!r) return;
  if (which === 'copybook') synDownload(r.copybookName, r.copybook);
  else synDownload(r.preName, r.preContent);
}

function mfgDownloadBinary() {
  const r = mfgLastResult; if (!r) return;
  const bin = atob(r.postBase64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  const blob = new Blob([bytes], { type: 'application/octet-stream' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob); a.download = r.postName;
  document.body.appendChild(a); a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 0);
}

/* ---------- boot ---------- */
async function bootApp() {
  await refreshShared();
  await loadStats();
  await loadFunctions();
  enhanceMinimizers();
}

enhanceMinimizers();
initAuth();
