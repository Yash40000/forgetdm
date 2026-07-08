/* ForgeTDM Mapping Designer — visual canvas (Informatica-style).
   • Drag tables from the connection tree onto the canvas (or click to drop).
   • Each table node shows its name + columns with input/output ports; wire output→input to define joins.
   • Top bar: join type, add transformations, overview/zoom, auto-layout, clear.
   • A flow strip shows the live output pipeline: Sources ⨝ → transforms → Target.
   • Minimize a single table (header only) or the whole view (Overview).
   • "Apply → Build & Run" compiles the design into mapState so the next tab executes it.
   Shares globals from app.js (api, esc, $, toast, dataSources, loadSchemas, loadTables, refreshShared)
   and mapping-designer.js (mapState, MAP_TRANSFORMS, mapDsName, mapGenSql, mapRender). */

const MC_NODE_W = 236, MC_HEAD_H = 34, MC_ROW_H = 26, MC_PAD_T = 6;
const MC_TF_ICON = { FILTER: '⧩', EXPRESSION: 'ƒx', AGGREGATOR: 'Σ', SORTER: '⇅', DISTINCT: '≠', LIMIT: '⌗',
                     ROUTER: '⑂', UNION: '∪', LOOKUP: '⌕', RANK: '№', SEQUENCE: '#', PIVOT: '⊞' };
const MC_JOINS = [
  { v: 'INNER', label: 'Inner (Normal)' },
  { v: 'LEFT',  label: 'Left Outer (Detail)' },
  { v: 'RIGHT', label: 'Right Outer (Master)' },
  { v: 'FULL',  label: 'Full Outer' }
];
const MC_ZOOMS = [['1.25', '125%'], ['1', '100%'], ['0.8', '80%'], ['0.6', '60%'], ['0.45', '45%']];
let mcSelLink = null;

/* ---------------- init / shell ---------------- */
function mcInit() {
  const host = $('map-designer-panel');
  if (!host) return;
  const tfOpts = (typeof MAP_TRANSFORMS !== 'undefined' ? MAP_TRANSFORMS : [])
    .map(t => `<option value="${t.type}">${esc(t.label)}${t.sql ? '' : ' (design)'}</option>`).join('');
  host.innerHTML = `
    <div class="mc-wrap card">
      <div class="mc-palette">
        <div class="mc-palette-head"><span>Connections</span><button class="iconbtn" title="Refresh" onclick="mcRefreshPalette()">⟳</button></div>
        <div id="mc-tree" class="mc-tree"></div>
        <div class="mc-palette-foot sub">Drag a table onto the canvas, or click it to drop.</div>
      </div>
      <div class="mc-main">
        <div class="mc-toolbar">
          <span class="mc-tool-label">Join</span>
          <select id="mc-jointype" onchange="mcSetSelType(this.value)">${MC_JOINS.map(j => `<option value="${j.v}">${j.label}</option>`).join('')}</select>
          <button class="ghost small" onclick="mcDeleteSel()" title="Delete selected link (Del)">Delete link</button>
          <span class="mc-sep"></span>
          <select id="mc-add-tf" title="Add a transformation to the pipeline" onchange="if(this.value){mcAddTf(this.value);this.value='';}">
            <option value="">+ Transformation…</option>${tfOpts}
          </select>
          <button class="ghost small" onclick="mcAddOutput()" title="Add an empty staging table (both input & output ports) to shape data between sources and the target">+ Staging table</button>
          <span class="mc-sep"></span>
          <button class="ghost small" id="mc-ov-btn" onclick="mcToggleOverview()">Overview</button>
          <select id="mc-zoom" title="Zoom" onchange="mcSetZoom(this.value)">${MC_ZOOMS.map(z => `<option value="${z[0]}">${z[1]}</option>`).join('')}</select>
          <button class="ghost small" onclick="mcAutoLayout()">Auto-layout</button>
          <button class="ghost small danger" onclick="mcClear()">Clear</button>
          <span class="grow"></span>
          <span class="mc-legend"><span class="mc-lg-join"></span>in↔in = join<span class="mc-lg-map"></span>out→in = data</span>
          <button onclick="mcApply()">Apply → Build &amp; Run</button>
        </div>
        <div id="mc-flow" class="mc-flow"></div>
        <div id="mc-props"></div>
        <div id="mc-canvas" class="mc-canvas">
          <div id="mc-content" class="mc-content">
            <svg id="mc-links" class="mc-links" width="3200" height="2000"></svg>
            <div id="mc-nodes" class="mc-nodes"></div>
          </div>
          <div id="mc-empty" class="mc-empty">Drag tables from <b>Source</b> connections to add sources, and a table from a <b>Target</b> connection as the target (set the role on the Data Sources page). Join sources <b>input ↔ input</b>; pass data <b>output → input</b> through an optional <b>Staging</b> table into the target.</div>
        </div>
      </div>
    </div>`;

  mcRenderPalette();
  mcWireTree();
  mcWireCanvas();
  mcRenderNodes();
  mcRenderStrip();
  mcDrawLinks();
  mcApplyZoom();
  mcSyncOverviewBtn();

  if (!window.__mcKeys) {
    window.__mcKeys = true;
    document.addEventListener('keydown', e => {
      if (mapState.tab !== 'designer' || !mcSelLink) return;
      if ((e.key === 'Delete' || e.key === 'Backspace') && !/^(INPUT|TEXTAREA|SELECT)$/.test(e.target.tagName || '')) {
        e.preventDefault(); mcDeleteSel();
      }
    });
  }
}

/* ---------------- palette (connection tree) ---------------- */
function mcRenderPalette() {
  const tree = $('mc-tree'); if (!tree) return;
  const list = dataSources || [];
  tree.innerHTML = list.length ? list.map(d => {
    const tgt = d.role === 'TARGET';
    return `<div class="mc-ds">
      <div class="mc-ds-h" data-ds="${d.id}"><span class="mc-caret">▸</span><span class="mc-ico">🗄</span><span class="mc-lbl">${esc(d.name)}</span><span class="mc-ds-role ${tgt ? 't' : 's'}" title="${esc(d.role || 'SOURCE')} connection">${tgt ? 'TARGET' : 'SOURCE'}</span></div>
      <div class="mc-ds-body" style="display:none"></div>
    </div>`; }).join('') : '<div class="mc-none">No data sources yet — add one on the Data Sources page.</div>';
}
function mcRefreshPalette() { (refreshShared ? refreshShared() : Promise.resolve()).then(() => mcRenderPalette()); }

function mcWireTree() {
  const tree = $('mc-tree'); if (!tree || tree.dataset.wired) return;
  tree.dataset.wired = '1';
  tree.addEventListener('click', e => {
    const dsH = e.target.closest('.mc-ds-h'); if (dsH) return mcTogDs(dsH);
    const schH = e.target.closest('.mc-sch-h'); if (schH) return mcTogSchema(schH);
    const tbl = e.target.closest('.mc-tbl'); if (tbl) mcAddNode(+tbl.dataset.ds, tbl.dataset.schema, tbl.dataset.name, 48 + Math.random() * 90, 48 + Math.random() * 60);
  });
  tree.addEventListener('dragstart', e => {
    const tbl = e.target.closest('.mc-tbl'); if (!tbl) return;
    const payload = JSON.stringify({ dsId: +tbl.dataset.ds, schema: tbl.dataset.schema, name: tbl.dataset.name });
    e.dataTransfer.setData('application/json', payload);
    e.dataTransfer.setData('text/plain', payload);
    e.dataTransfer.effectAllowed = 'copy';
  });
}
function mcTogDs(hEl) {
  const body = hEl.nextElementSibling, caret = hEl.querySelector('.mc-caret');
  const open = body.style.display === 'none';
  body.style.display = open ? 'block' : 'none'; caret.textContent = open ? '▾' : '▸';
  if (open && !body.dataset.loaded) {
    const id = +hEl.dataset.ds; body.innerHTML = '<div class="mc-loading">loading…</div>';
    loadSchemas(id).then(schemas => {
      body.dataset.loaded = '1';
      body.innerHTML = (schemas || []).map(s => `
        <div class="mc-sch">
          <div class="mc-sch-h" data-ds="${id}" data-schema="${esc(s.schema)}"><span class="mc-caret">▸</span><span class="mc-ico">▤</span><span class="mc-lbl">${esc(s.schema)}</span></div>
          <div class="mc-sch-body" style="display:none"></div>
        </div>`).join('') || '<div class="mc-none">no schemas</div>';
    }).catch(e => body.innerHTML = `<div class="error">${esc(e.message)}</div>`);
  }
}
function mcTogSchema(hEl) {
  const body = hEl.nextElementSibling, caret = hEl.querySelector('.mc-caret');
  const open = body.style.display === 'none';
  body.style.display = open ? 'block' : 'none'; caret.textContent = open ? '▾' : '▸';
  if (open && !body.dataset.loaded) {
    const id = +hEl.dataset.ds, schema = hEl.dataset.schema; body.innerHTML = '<div class="mc-loading">loading…</div>';
    loadTables(id, schema).then(tables => {
      body.dataset.loaded = '1';
      body.innerHTML = (tables || []).map(t => `<div class="mc-tbl" draggable="true" data-ds="${id}" data-schema="${esc(schema)}" data-name="${esc(t)}" title="drag to canvas or click to add"><span class="mc-ico">▦</span><span class="mc-lbl">${esc(t)}</span></div>`).join('') || '<div class="mc-none">no tables</div>';
    }).catch(e => body.innerHTML = `<div class="error">${esc(e.message)}</div>`);
  }
}

/* ---------------- canvas wiring ---------------- */
function mcZoom() { return mapState.canvas.zoom || 1; }
function mcPoint(ev) {
  const c = $('mc-canvas'), r = c.getBoundingClientRect(), z = mcZoom();
  return { x: (ev.clientX - r.left + c.scrollLeft) / z, y: (ev.clientY - r.top + c.scrollTop) / z };
}
function mcWireCanvas() {
  const canvas = $('mc-canvas'), nodesEl = $('mc-nodes');
  if (!canvas || !nodesEl) return;
  canvas.addEventListener('dragover', e => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; });
  canvas.addEventListener('drop', e => {
    e.preventDefault();
    let raw = e.dataTransfer.getData('application/json') || e.dataTransfer.getData('text/plain') || '';
    let data = {}; try { data = JSON.parse(raw); } catch (_) {}
    if (!data.name) return;
    const p = mcPoint(e);
    mcAddNode(data.dsId, data.schema, data.name, Math.max(4, p.x - 60), Math.max(4, p.y - 12));
  });
  canvas.addEventListener('pointerdown', e => { if (e.target === canvas || e.target.id === 'mc-content' || e.target.id === 'mc-links' || e.target.id === 'mc-nodes' || e.target.id === 'mc-empty') mcSelectLink(null); });
  nodesEl.addEventListener('pointerdown', e => {
    const port = e.target.closest('.mc-port');
    if (port) { e.preventDefault(); e.stopPropagation(); return mcStartLink(port, e); }
    const head = e.target.closest('.mc-node-head');
    if (head && !e.target.closest('button')) mcStartNodeDrag(head.closest('.mc-node'), e);
  });
}

/* ---------------- table nodes ---------------- */
async function mcAddNode(dsId, schema, name, x, y) {
  if (mcDsRole(dsId) === 'TARGET') {
    const existing = Object.keys(mapState.canvas.nodes).find(k => mcRoleOf(k) === 'TARGET');
    if (existing) return toast(`Only one target table allowed (currently ${mapState.canvas.nodes[existing].name}). Remove it first.`, 'err');
  }
  // unique key + SQL alias so the SAME table can be placed multiple times (e.g. self-joins)
  const base = String(name).toLowerCase().replace(/[^a-z0-9_]/g, '_') || 'tbl';
  let key = base, i = 1; while (mapState.canvas.nodes[key]) key = base + '__' + (++i);
  const aliases = new Set(Object.values(mapState.canvas.nodes).map(n => n.alias || n.name));
  let alias = name, j = 1; while (aliases.has(alias)) alias = name + '_' + (++j);
  let cols = [];
  try {
    const meta = await api.get(`/api/datasources/${dsId}/tables/${encodeURIComponent(name)}/columns?schema=${encodeURIComponent(schema || '')}`);
    cols = (meta || []).map(c => ({
      name: c.column != null ? c.column : (c.name != null ? c.name : (c.columnName != null ? c.columnName : (c.COLUMN_NAME != null ? c.COLUMN_NAME : ''))),
      type: c.type != null ? c.type : (c.dataType != null ? c.dataType : (c.typeName != null ? c.typeName : ''))
    })).filter(c => c.name);
  } catch (e) { toast(`${name}: ${e.message}`, 'err'); }
  const ports = {}; cols.forEach(c => ports[c.name] = { in: true, out: true });
  mapState.canvas.nodes[key] = { key, dsId, schema: schema || '', name, alias, x, y, cols, ports, collapsed: false };
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();
}
function mcRemoveNode(key) {
  delete mapState.canvas.nodes[key];
  mapState.canvas.links = mapState.canvas.links.filter(l => l.from.table !== key && l.to.table !== key);
  if (mcSelLink && !mapState.canvas.links.some(l => l.id === mcSelLink)) mcSelLink = null;
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();
}
function mcToggleNode(key) {
  const n = mapState.canvas.nodes[key]; if (!n) return;
  n.collapsed = !n.collapsed; mcRenderNodes(); mcDrawLinks();
}
function mcRenderNodes() {
  const host = $('mc-nodes'), empty = $('mc-empty'); if (!host) return;
  const keys = Object.keys(mapState.canvas.nodes);
  if (empty) empty.style.display = keys.length ? 'none' : 'block';
  host.innerHTML = keys.map(k => {
    const n = mapState.canvas.nodes[k];
    if (n.kind === 'TRANSFORM') return mcTransformNodeHtml(n, k);   // Informatica-style transformation node
    const role = mcRoleOf(k);          // SOURCE | TARGET | STAGING
    const isStaging = n.kind === 'STAGING';
    const rows = n.cols.length ? n.cols.map((c, i) => mcColRow(n, c, i)).join('')
                              : `<div class="mc-norow">${isStaging ? 'no columns yet — add some, or drag a source output here' : 'columns unavailable'}</div>`;
    const addCol = isStaging ? `<div class="mc-addcol"><button onclick="mcAddCol('${mcJs(k)}')">+ column</button></div>` : '';
    const body = n.collapsed ? '' : `<div class="mc-node-body">${rows}${addCol}</div>`;
    const nameCell = isStaging
      ? `<input class="mc-name-edit" value="${esc(n.name)}" title="staging name" onchange="mcRenameNode('${mcJs(k)}',this.value)">`
      : `<b class="mc-node-name" title="${esc(n.name)}">${esc(n.name || n.key || 'table')}</b>${(n.alias && n.alias !== n.name) ? `<span class="mc-alias" title="SQL alias">${esc(n.alias)}</span>` : ''}`;
    const ico = role === 'TARGET' ? '◎' : role === 'STAGING' ? '▥' : '▦';
    const roleCls = role === 'TARGET' ? 't' : role === 'STAGING' ? 'g' : 's';
    return `<div class="mc-node${n.collapsed ? ' collapsed' : ''}${role === 'TARGET' ? ' target' : ''}${isStaging ? ' staging' : ''}" data-key="${esc(k)}" style="left:${n.x}px;top:${n.y}px;width:${MC_NODE_W}px">
      <button class="mc-x" title="Delete this table from the canvas" onclick="mcRemoveNode('${mcJs(k)}')">×</button>
      <div class="mc-node-head">
        <button class="mc-min" title="${n.collapsed ? 'Expand' : 'Minimize'}" onclick="mcToggleNode('${mcJs(k)}')">${n.collapsed ? '▸' : '▾'}</button>
        <span class="mc-ico">${ico}</span>${nameCell}
        <span class="mc-role ${roleCls}" title="Source/Target come from the data source role; staging is the middle container">${role}</span>
        <span class="grow"></span>
      </div>${body}
    </div>`;
  }).join('');
}

/** An Informatica-style transformation node: input ports (left) + output ports (right), gear to edit.
    Layout matches table/staging nodes (head + rows) so mcPortPos math is shared unchanged. */
function mcTransformNodeHtml(n, k) {
  const lib = (typeof MAP_TRANSFORMS !== 'undefined' ? MAP_TRANSFORMS : []);
  const meta = lib.find(x => x.type === n.tfType) || {};
  const sel = (typeof mcEditTfKey !== 'undefined' && mcEditTfKey === k) ? ' sel' : '';
  const sum = mcTfSummary(n.tf);
  const configured = !/not set|no columns yet|no groups yet|not configured|no sort keys|no group by/.test(sum);
  const rows = n.cols.length ? n.cols.map((c, i) => mcColRow(n, c, i)).join('')
    : `<div class="mc-norow">wire source columns into this transformation ▸</div>`;
  const body = n.collapsed ? '' : `<div class="mc-node-body">${rows}</div>`;
  return `<div class="mc-node tf${sel}${configured ? '' : ' unconfigured'}" data-key="${esc(k)}"
      style="left:${n.x}px;top:${n.y}px;width:${MC_NODE_W}px" ondblclick="mcEditTf('${mcJs(k)}')">
    <button class="mc-x" title="Remove transformation" onclick="mcDelTf('${mcJs(k)}')">×</button>
    <div class="mc-node-head" title="${esc(meta.desc || '')} — ${esc(sum)}">
      <button class="mc-min" title="${n.collapsed ? 'Expand' : 'Minimize'}" onclick="mcToggleNode('${mcJs(k)}')">${n.collapsed ? '▸' : '▾'}</button>
      <span class="mc-ico">${MC_TF_ICON[n.tfType] || '⚙'}</span>
      <b class="mc-node-name" title="${esc(sum)}">${esc(meta.label || n.tfType)}</b>
      <span class="mc-role x" title="transformation">TF</span>
      <span class="grow"></span>
      <button class="mc-min" title="Edit properties" onclick="mcEditTf('${mcJs(k)}')">⚙</button>
    </div>${body}
  </div>`;
}

/* ---------------- transformation nodes (Informatica-style: input/output ports, wired into the graph) ---------------- */
function mcCleanName(s) {
  let n = String(s || '').trim();
  if (n.includes('.')) n = n.split('.').pop();
  n = n.replace(/[^A-Za-z0-9_]/g, '_').replace(/^_+|_+$/g, '');
  return n;
}
function mcPivotAlias(v) { let a = String(v).replace(/[^A-Za-z0-9_]/g, '_').toLowerCase(); return /^\d/.test(a) ? 'p_' + a : a; }

/** Inside a transformation CTE the upstream is a SINGLE relation, so its columns are available
    unqualified. References like "accounts.customer_id" (pasted from the ports list) won't resolve —
    strip the qualifier when it matches a known SOURCE table/alias. Skips string literals; leaves
    lookup-alias qualifiers (not source names) intact. */
function mcDequalify(text, quals) {
  if (!text || !quals || !quals.size) return text;
  let out = '', i = 0, inStr = false, q = '';
  while (i < text.length) {
    const ch = text[i];
    if (inStr) { out += ch; if (ch === q) inStr = false; i++; continue; }
    if (ch === "'" || ch === '"') { inStr = true; q = ch; out += ch; i++; continue; }
    const m = /^([A-Za-z_][A-Za-z0-9_]*)\.([A-Za-z_][A-Za-z0-9_]*)/.exec(text.slice(i));
    if (m && quals.has(m[1].toLowerCase())) { out += m[2]; i += m[0].length; continue; }
    out += ch; i++;
  }
  return out;
}

function mcTfSummary(t) {
  switch (t.type) {
    case 'FILTER': return t.condition || 'condition not set';
    case 'EXPRESSION': { const n = (t.columns || []).filter(c => c.name && c.expr).length; return n ? n + ' output column(s)' : 'no columns yet'; }
    case 'AGGREGATOR': return ((t.groupBy || []).filter(Boolean).join(', ') || 'no group by') + ' · ' + (t.aggregates || []).filter(a => a.name).length + ' agg(s)';
    case 'SORTER': return (t.sort || []).map(s => s.col + (s.dir === 'DESC' ? ' ↓' : '')).join(', ') || 'no sort keys';
    case 'LIMIT': return t.rows ? t.rows + ' rows' : 'row cap not set';
    case 'DISTINCT': return 'remove duplicates';
    case 'ROUTER': { const g = (t.groups || []).length; return g ? g + ' group(s)' + ((t.groups || []).some(x => (x.target || '').trim()) ? ' → targets' : '') : 'no groups yet'; }
    case 'UNION': return t.table ? '∪ ' + t.table : 'table not set';
    case 'LOOKUP': return t.table ? '⌕ ' + t.table : 'lookup table not set';
    case 'RANK': return (t.orderBy ? 'by ' + t.orderBy : 'order not set') + (t.topN ? ' · top ' + t.topN : '');
    case 'SEQUENCE': return (t.name || 'seq_no');
    case 'PIVOT': return t.category ? t.category + ' → columns' : 'not configured';
    default: return '';
  }
}

/** The full column list (with per-column in/out flags) a transformation exposes, given the names of the
    columns wired into its inputs. Pass-through inputs keep both ports; derived/aggregate/rank/seq/pivot
    outputs are output-only; aggregator inputs not in the GROUP BY are input-only (consumed). */
function mcTfCols(n, inputNames) {
  const t = n.tf, type = n.tfType, cols = [];
  const push = (name, io) => {
    if (!name) return;
    const ex = cols.find(c => c.name.toLowerCase() === String(name).toLowerCase());
    if (ex) { if (io.indexOf('in') >= 0) ex.in = true; if (io.indexOf('out') >= 0) ex.out = true; }
    else cols.push({ name: String(name), type: '', in: io.indexOf('in') >= 0, out: io.indexOf('out') >= 0 });
  };
  const passthrough = () => inputNames.forEach(nm => push(nm, 'inout'));
  switch (type) {
    case 'FILTER': case 'SORTER': case 'DISTINCT': case 'LIMIT': case 'ROUTER': case 'UNION':
      passthrough(); break;
    case 'EXPRESSION':
      passthrough();
      (t.columns || []).forEach(c => { if ((c.expr || '').trim()) push(mcCleanName(c.name) || null, 'out'); }); break;
    case 'AGGREGATOR': {
      const grp = new Set((t.groupBy || []).map(s => String(s).toLowerCase()));
      inputNames.forEach(nm => push(nm, grp.has(nm.toLowerCase()) ? 'inout' : 'in'));
      (t.aggregates || []).forEach(a => { if (a.name && (a.expr || '').trim()) push(a.name, 'out'); }); break;
    }
    case 'LOOKUP':
      passthrough();
      (t.returns || '').split(',').map(s => s.trim()).filter(Boolean).forEach(r => { const m = r.match(/\bAS\s+(\w+)/i); push(m ? m[1] : mcCleanName(r.split('.').pop()), 'out'); }); break;
    case 'RANK': passthrough(); push(t.name || 'rank_in_group', 'out'); break;
    case 'SEQUENCE': passthrough(); push(t.name || 'seq_no', 'out'); break;
    case 'PIVOT':
      (t.groupBy || '').split(',').map(s => s.trim()).filter(Boolean).forEach(g => push(g, 'inout'));
      (t.values || '').split(',').map(s => s.trim()).filter(Boolean).forEach(v => push(mcPivotAlias(v), 'out')); break;
    default: passthrough();
  }
  return cols;
}

/** Recompute a transform node's columns/ports from its wired inputs + config. Idempotent. */
function mcTfRecompute(key) {
  const n = mapState.canvas.nodes[key];
  if (!n || n.kind !== 'TRANSFORM') return;
  const inputNames = [];
  mapState.canvas.links.forEach(l => { if (l.kind === 'MAP' && l.to.table === key && !inputNames.includes(l.to.col)) inputNames.push(l.to.col); });
  const cols = mcTfCols(n, inputNames);
  n.cols = cols.map(c => ({ name: c.name, type: c.type || '' }));
  n.ports = {}; cols.forEach(c => n.ports[c.name] = { in: c.in, out: c.out });
}

// Role comes from the data source's classification (Source/Target on the Data Sources page).
// The staging container sits in the middle and is neither.
function mcDsRole(dsId) {
  const d = (dataSources || []).find(x => String(x.id) === String(dsId));
  return (d && d.role === 'TARGET') ? 'TARGET' : 'SOURCE';   // BOTH/SOURCE act as a source in the designer
}
function mcRoleOf(key) {
  const n = mapState.canvas.nodes[key];
  if (!n) return 'SOURCE';
  if (n.kind === 'STAGING') return 'STAGING';
  if (n.kind === 'TRANSFORM') return 'TRANSFORM';
  return mcDsRole(n.dsId);
}
function mcTargetKey() { return Object.keys(mapState.canvas.nodes).find(k => mcRoleOf(k) === 'TARGET') || null; }
function mcColRow(n, c, idx) {
  const isT = mcRoleOf(n.key) === 'TARGET';
  const isStaging = n.kind === 'STAGING';
  const isTf = n.kind === 'TRANSFORM';
  // Transforms carry per-column port flags (n.ports[col] = {in,out}); tables/staging use the default
  // (input on every column; output on all but a target).
  const pf = isTf ? (n.ports[c.name] || {}) : null;
  const showIn = isTf ? !!(pf && pf.in) : true;
  const showOut = isTf ? !!(pf && pf.out) : !isT;
  const inPort = showIn ? `<span class="mc-port mc-in" data-node="${esc(n.key)}" data-col="${esc(c.name)}" data-kind="in" title="input port"></span>` : '';
  const outPort = showOut ? `<span class="mc-port mc-out" data-node="${esc(n.key)}" data-col="${esc(c.name)}" data-kind="out" title="output port"></span>` : '';
  const nameCell = isStaging
    ? `<input class="mc-col-edit" value="${esc(c.name)}" placeholder="column" onchange="mcRenameCol('${mcJs(n.key)}',${idx},this.value)"><input class="mc-type-edit${c.auto ? ' auto' : ''}" value="${esc(c.type || '')}" placeholder="type" title="datatype/length — blank or inherited from the mapped input; type to override (adds a CAST)" onchange="mcRetypeCol('${mcJs(n.key)}',${idx},this.value)"><button class="mc-colx" title="remove column" onclick="mcDelCol('${mcJs(n.key)}',${idx})">×</button>`
    : `<span class="mc-col-name" title="${esc(c.name)}">${esc(c.name || '(unnamed)')}</span><span class="mc-col-type">${esc(mcShortType(c.type))}</span>`;
  return `<div class="mc-row${isStaging ? ' edit' : ''}" style="height:${MC_ROW_H}px">${inPort}${nameCell}${outPort}</div>`;
}

/* ---------------- staging containers (empty editable, both ports) — many allowed for multi-stage pipelines ---------------- */
function mcAddOutput() {
  let i = 1; while (mapState.canvas.nodes['__staging' + i + '__']) i++;
  const key = '__staging' + i + '__';
  const n = Object.keys(mapState.canvas.nodes).length;
  mapState.canvas.nodes[key] = { key, dsId: null, schema: '', name: 'S' + i, x: 60 + (n % 4) * 290, y: 60 + Math.floor(n / 4) * 240, cols: [], ports: {}, collapsed: false, kind: 'STAGING' };
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();
  toast(`Staging table S${i} added — wire source/staging outputs into it (it can be joined further downstream)`, 'info');
}
function mcAddCol(key) {
  const n = mapState.canvas.nodes[key]; if (!n) return;
  let i = 1, name; do { name = 'col' + i++; } while (n.cols.some(c => c.name === name));
  n.cols.push({ name, type: '', auto: true }); n.ports[name] = { in: true, out: true };
  mcRenderNodes(); mcDrawLinks();
}
function mcRetypeCol(key, idx, val) {
  const n = mapState.canvas.nodes[key]; if (!n || !n.cols[idx]) return;
  n.cols[idx].type = (val || '').trim();
  n.cols[idx].auto = false;   // user-defined type → honoured with a CAST in the generated SQL
}
// a staging column with no explicit type adopts the datatype/length of the input port mapped into it
function mcInheritType(to, from) {
  const tn = mapState.canvas.nodes[to.node];
  if (!tn || tn.kind !== 'STAGING') return;
  const tcol = tn.cols.find(c => c.name === to.col);
  if (!tcol || tcol.type) return;
  const sn = mapState.canvas.nodes[from.table];
  const sc = sn && sn.cols.find(c => c.name === from.col);
  if (sc && sc.type) { tcol.type = mcShortType(sc.type); tcol.auto = true; }
}
function mcRenameCol(key, idx, val) {
  const n = mapState.canvas.nodes[key]; if (!n || !n.cols[idx]) return;
  const old = n.cols[idx].name; val = (val || '').trim();
  if (!val) return mcRenderNodes();
  if (n.cols.some((c, i) => i !== idx && c.name === val)) { toast('Duplicate column name', 'err'); return mcRenderNodes(); }
  n.cols[idx].name = val;
  if (old !== val) { n.ports[val] = n.ports[old] || { in: true, out: true }; delete n.ports[old]; }
  mapState.canvas.links.forEach(l => {
    if (l.to.table === key && l.to.col === old) l.to.col = val;
    if (l.from.table === key && l.from.col === old) l.from.col = val;
  });
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();
}
function mcDelCol(key, idx) {
  const n = mapState.canvas.nodes[key]; if (!n || !n.cols[idx]) return;
  const name = n.cols[idx].name;
  n.cols.splice(idx, 1); delete n.ports[name];
  mapState.canvas.links = mapState.canvas.links.filter(l => !((l.to.table === key && l.to.col === name) || (l.from.table === key && l.from.col === name)));
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();
}
function mcRenameNode(key, val) {
  const n = mapState.canvas.nodes[key]; if (!n) return;
  n.name = (val || '').trim() || n.name;
  mcRenderStrip();
}
function mcTogPort(key, col, kind, val) {
  const n = mapState.canvas.nodes[key]; if (!n) return;
  (n.ports[col] || (n.ports[col] = { in: true, out: true }))[kind] = val;
  mcRenderStrip();
}
function mcFlashNode(key) {
  const el = document.querySelector(`.mc-node[data-key="${mcAttr(key)}"]`);
  if (el) { el.classList.add('flash'); setTimeout(() => el.classList.remove('flash'), 600); }
  toast('That table is already on the canvas', 'info');
}

/* ---------------- output pipeline strip ---------------- */
function mcRenderStrip() {
  const el = $('mc-flow'); if (!el) return;
  const all = Object.values(mapState.canvas.nodes);
  const sources = all.filter(n => mcRoleOf(n.key) === 'SOURCE');
  const targetNode = all.find(n => mcRoleOf(n.key) === 'TARGET');
  const stagingNode = all.find(n => n.kind === 'STAGING');
  const src = sources.length ? sources.map(n => esc(n.name)).join(' ⨝ ') : 'Sources';
  const stage = stagingNode ? `<span class="mc-arrow">→</span><span class="mc-fchip stg">▥ <b>${esc(stagingNode.name)}</b></span>` : '';
  const lib = (typeof MAP_TRANSFORMS !== 'undefined' ? MAP_TRANSFORMS : []);
  // Transformations are graph NODES on the canvas now; the strip shows them as clickable chips in wired order.
  const tfKeys = mcTransformOrder();
  const tfs = tfKeys.map(k => {
    const n = mapState.canvas.nodes[k];
    const meta = lib.find(x => x.type === n.tfType) || {};
    const active = (typeof mcEditTfKey !== 'undefined' && mcEditTfKey === k) ? ' active' : '';
    return `<span class="mc-arrow">→</span><span class="mc-fchip tf${active}" title="${esc(meta.desc || '')} — click to edit"><b style="cursor:pointer" onclick="mcEditTf('${mcJs(k)}')">${MC_TF_ICON[n.tfType] || '⚙'} ${esc(meta.label || n.tfType)}</b><button class="mc-fx" title="properties" onclick="mcEditTf('${mcJs(k)}')">⚙</button><button class="mc-fx" title="remove" onclick="mcDelTf('${mcJs(k)}')">×</button></span>`;
  }).join('');
  let tgt;
  if (targetNode) {
    const mapped = mapState.canvas.links.filter(l => l.kind === 'MAP' && (l.to.table === targetNode.key || l.from.table === targetNode.key)).length;
    tgt = `<span class="mc-fchip tgt">◎ Target: <b>${esc(targetNode.name)}</b>${mapped ? `<span class="mc-tagi">${mapped} mapped</span>` : '<span class="mc-tagi warn">wire columns</span>'}</span>`;
  } else {
    const outCount = (typeof mcProjection === 'function' && mcProjection()) ? mcProjection().length : 0;
    const modeSel = `<select class="mc-chip-sel" onchange="mapState.target.mode=this.value;mcRenderStrip()">${['PREVIEW', 'TABLE', 'CSV', 'JSON'].map(m => `<option value="${m}"${mapState.target.mode === m ? ' selected' : ''}>${({ PREVIEW: 'Preview', TABLE: 'Load table', CSV: 'CSV file', JSON: 'JSON file' })[m]}</option>`).join('')}</select>`;
    tgt = `<span class="mc-fchip tgt">▸ ${modeSel}${outCount ? `<span class="mc-tagi">${outCount} cols</span>` : ''}</span>`;
  }
  el.innerHTML = `<span class="mc-flbl">Pipeline</span><span class="mc-fchip src"><b>${src}</b></span>${tfs}${stage}<span class="mc-arrow">→</span>${tgt}`;
  mcRenderProps();   // keep the on-canvas properties panel in sync (survives full re-renders)
}

/* ---- on-canvas transformation properties (Informatica-style: configure where you design) ---- */
let mcEditTfKey = null;

function mcRenderProps() {
  const el = $('mc-props');
  if (!el) return;
  const node = mcEditTfKey == null ? null : mapState.canvas.nodes[mcEditTfKey];
  const t = node ? node.tf : null;
  if (!t) { el.innerHTML = ''; return; }
  const lib = (typeof MAP_TRANSFORMS !== 'undefined' ? MAP_TRANSFORMS : []);
  const meta = lib.find(x => x.type === t.type) || {};
  // Ports = this transform's own INPUT columns ONLY (unqualified) — the exact names its expressions and
  // conditions can reference. (Referencing a source table by name won't resolve here; the compiler also
  // strips stray qualifiers as a safety net.) Wire more columns in from the canvas to grow this list.
  const ports = [];
  (node.cols || []).forEach(c => { if (!node.ports[c.name] || node.ports[c.name].in) ports.push(c.name); });
  const portChips = ports.length
    ? ports.slice(0, 80).map(p => `<button class="map-fn" title="Insert into the focused field" onmousedown="event.preventDefault()" onclick="mapUseFn('${mcJs(p)}')">${esc(p)}</button>`).join('')
    : '<span class="muted">no input columns yet — drag source output ports onto this transformation\'s body</span>';
  const validatable = ['FILTER', 'EXPRESSION', 'AGGREGATOR', 'PIVOT', 'RANK'].includes(t.type);
  el.innerHTML = `<div class="map-tf mc-propbox">
    <div class="map-tf-head"><span class="mc-ico">${MC_TF_ICON[t.type] || '⚙'}</span><b>${esc(meta.label || t.type)} — properties</b>
      <span class="sub" style="margin-left:8px">${esc(meta.desc || '')}</span><span class="grow"></span>
      ${validatable ? `<button class="small ghost" onclick="mcValidateTf('${mcJs(mcEditTfKey)}')" title="Runs the expressions against ONE live source row — the database itself is the validator">Validate</button>` : ''}
      <span id="mc-validate-out" class="mono" style="font-size:11.5px"></span>
      <button class="small ghost" onclick="mcCloseProps()">Done</button></div>
    <div class="map-tf-body">${typeof mapTfBody === 'function' ? mapTfBody(t, mcEditTfKey) : ''}</div>
    ${ports.length ? `<div class="mc-ports"><span class="mc-ports-h">Ports — click to insert:</span>${portChips}${ports.length > 80 ? `<span class="muted">+${ports.length - 80} more</span>` : ''}</div>` : ''}
  </div>`;
}

/** Informatica's Validate button, but stronger: runs the expressions on ONE live row of the first
    source, so the database itself confirms syntax AND semantics. */
async function mcValidateTf(key) {
  const node = mapState.canvas.nodes[key];
  const t = node ? node.tf : null;
  const out = $('mc-validate-out');
  if (!t) return;
  const firstNode = Object.values(mapState.canvas.nodes).find(n => mcRoleOf(n.key) === 'SOURCE');
  const src = firstNode ? { dsId: firstNode.dsId, qual: (firstNode.schema ? firstNode.schema + '.' : '') + firstNode.name }
    : (mapState.tables[0] ? { dsId: mapState.tables[0].dsId, qual: mapQual(mapState.tables[0]) } : null);
  if (!src) return toast('Add a source table first', 'err');
  let exprs = [];
  if (t.type === 'FILTER') exprs = (t.condition || '').trim() ? [`CASE WHEN (${t.condition.trim()}) THEN 1 ELSE 0 END`] : [];
  else if (t.type === 'EXPRESSION') exprs = (t.columns || []).filter(c => (c.expr || '').trim()).map(c => c.expr.trim());
  else if (t.type === 'AGGREGATOR') exprs = (t.aggregates || []).filter(a => (a.expr || '').trim()).map(a => a.expr.trim());
  else if (t.type === 'PIVOT') exprs = (t.category && t.value) ? [`${(t.agg || 'SUM')}(CASE WHEN ${t.category} IS NOT NULL THEN ${t.value} END)`] : [];
  else if (t.type === 'RANK') exprs = (t.orderBy || '').trim() ? [`ROW_NUMBER() OVER (${(t.partitionBy || '').trim() ? 'PARTITION BY ' + t.partitionBy.trim() + ' ' : ''}ORDER BY ${t.orderBy.trim()})`] : [];
  if (!exprs.length) { if (out) { out.textContent = 'nothing to validate yet'; out.className = 'mono'; } return; }
  const d = (typeof mapDialect === 'function') ? mapDialect() : 'postgres';
  const list = exprs.map((e2, k) => `${e2} AS v${k}`).join(', ');
  const sql = d === 'sqlserver' ? `SELECT TOP 1 ${list} FROM ${src.qual}`
    : `SELECT ${list} FROM ${src.qual} ` + ((d === 'oracle' || d === 'db2') ? 'FETCH FIRST 1 ROWS ONLY' : 'LIMIT 1');
  if (out) { out.textContent = 'validating…'; out.className = 'mono'; }
  try {
    await api.post('/api/mappings/preview', { dataSourceId: parseInt(src.dsId), sql });
    if (out) { out.textContent = '✓ valid on ' + src.qual; out.className = 'mono'; }
  } catch (e) {
    if (out) { out.textContent = '✗ ' + e.message; out.className = 'mono warn-row'; }
  }
}

function mcEditTf(key) { mcEditTfKey = key; mcRenderNodes(); mcRenderStrip(); mcDrawLinks(); }
function mcCloseProps() { mcEditTfKey = null; mcRenderNodes(); mcRenderStrip(); mcDrawLinks(); }

function mcTfInit(type) {
  const t = { type };
  if (type === 'EXPRESSION') t.columns = [{ name: '', expr: '' }];
  if (type === 'AGGREGATOR') { t.groupBy = []; t.aggregates = [{ name: '', expr: '' }]; }
  if (type === 'SORTER') t.sort = [];
  return t;
}
function mcAddTf(type) {
  const meta = (typeof MAP_TRANSFORMS !== 'undefined' ? MAP_TRANSFORMS : []).find(x => x.type === type) || {};
  const base = 'tf_' + type.toLowerCase();
  let key = base, i = 1; while (mapState.canvas.nodes[key]) key = base + '_' + (++i);
  const all = Object.values(mapState.canvas.nodes);
  const x = (all.length ? Math.max(...all.map(n => n.x + MC_NODE_W)) : 40) + 70;
  const ys = all.filter(n => mcRoleOf(n.key) === 'SOURCE').map(n => n.y);
  const y = ys.length ? Math.min(...ys) : 60;
  mapState.canvas.nodes[key] = { key, kind: 'TRANSFORM', tfType: type, tf: mcTfInit(type),
    name: meta.label || type, x, y, cols: [], ports: {}, collapsed: false };
  mcEditTfKey = key;
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();
  toast(`${meta.label || type} added — wire source output ports into its input side, then wire its outputs on`, 'info');
}
function mcDelTf(key) {
  delete mapState.canvas.nodes[key];
  mapState.canvas.links = mapState.canvas.links.filter(l => l.from.table !== key && l.to.table !== key);
  if (mcEditTfKey === key) mcEditTfKey = null;
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();
}

/** Transform nodes in wired (upstream-first) execution order — for the strip + linearization. */
function mcTransformOrder() {
  const tf = Object.keys(mapState.canvas.nodes).filter(k => mapState.canvas.nodes[k].kind === 'TRANSFORM');
  const upstream = k => { const s = new Set(); mapState.canvas.links.forEach(l => { if (l.kind === 'MAP' && l.to.table === k) s.add(l.from.table); }); return [...s]; };
  const order = [], pending = new Set(tf); let guard = 0;
  while (pending.size && guard++ < 200) {
    let moved = false;
    for (const k of [...pending]) {
      const deps = upstream(k).filter(u => pending.has(u));   // transform deps still pending
      if (deps.length === 0) { order.push(k); pending.delete(k); moved = true; }
    }
    if (!moved) { order.push(...pending); pending.clear(); }
  }
  return order;
}

/* ---------------- node drag ---------------- */
function mcStartNodeDrag(nodeEl, e) {
  const key = nodeEl.dataset.key, node = mapState.canvas.nodes[key]; if (!node) return;
  const sx = e.clientX, sy = e.clientY, ox = node.x, oy = node.y, z = mcZoom();
  nodeEl.classList.add('dragging');
  function mv(ev) {
    node.x = Math.max(0, ox + (ev.clientX - sx) / z);
    node.y = Math.max(0, oy + (ev.clientY - sy) / z);
    nodeEl.style.left = node.x + 'px'; nodeEl.style.top = node.y + 'px';
    mcDrawLinks();
  }
  function up() { document.removeEventListener('pointermove', mv); document.removeEventListener('pointerup', up); nodeEl.classList.remove('dragging'); }
  document.addEventListener('pointermove', mv); document.addEventListener('pointerup', up);
}

/* ---------------- link drag ---------------- */
function mcStartLink(portEl, e) {
  const from = { node: portEl.dataset.node, col: portEl.dataset.col, kind: portEl.dataset.kind };
  function mv(ev) { mcDrawTemp(from, mcPoint(ev)); }
  function up(ev) {
    document.removeEventListener('pointermove', mv); document.removeEventListener('pointerup', up);
    mcClearTemp();
    const tgt = document.elementFromPoint(ev.clientX, ev.clientY);
    const tp = tgt && tgt.closest ? tgt.closest('.mc-port') : null;
    if (tp) { mcCommitLink(from, { node: tp.dataset.node, col: tp.dataset.col, kind: tp.dataset.kind }); return; }
    // dropped an output onto the BODY of a staging/transformation node → wire it into that node's input
    const nodeEl = tgt && tgt.closest ? tgt.closest('.mc-node') : null;
    if (nodeEl && from.kind === 'out') {
      const k = nodeEl.dataset.key, n = mapState.canvas.nodes[k];
      if (n && k !== from.node && n.kind === 'STAGING') {
        let name = from.col, i = 1; while (n.cols.some(c => c.name === name)) name = from.col + '_' + i++;
        n.cols.push({ name, type: '', auto: true }); n.ports[name] = { in: true, out: true };
        if (!mcCommitLink(from, { node: k, col: name, kind: 'in' })) {   // rejected (e.g. cross-join guard) → undo the column
          n.cols = n.cols.filter(c => c.name !== name); delete n.ports[name]; mcRenderNodes(); mcDrawLinks();
        }
      } else if (n && k !== from.node && n.kind === 'TRANSFORM') {
        // input port name = the incoming column name; mcTfRecompute (in mcCommitLink) builds the ports
        mcCommitLink(from, { node: k, col: from.col, kind: 'in' });
      }
    }
  }
  document.addEventListener('pointermove', mv); document.addEventListener('pointerup', up);
}
/** True when two nodes are in the same JOIN-connected component (so mapping both into one node is a
    real join, not a cross join). Same node = trivially connected. Transforms/CTEs have no JOIN links,
    so they're only connected to themselves — mixing them with anything else is a cross join. */
function mcJoinConnected(a, b) {
  if (a === b) return true;
  const adj = {};
  mapState.canvas.links.filter(l => l.kind === 'JOIN').forEach(l => {
    (adj[l.from.table] = adj[l.from.table] || []).push(l.to.table);
    (adj[l.to.table] = adj[l.to.table] || []).push(l.from.table);
  });
  const seen = new Set([a]), q = [a];
  while (q.length) { const x = q.shift(); if (x === b) return true; (adj[x] || []).forEach(y => { if (!seen.has(y)) { seen.add(y); q.push(y); } }); }
  return false;
}
function mcCommitLink(a, b) {
  if (a.node === b.node) return toast('Connect columns of two different tables', 'err');
  // The PORTS you connect decide the meaning:
  //   input ↔ input  → JOIN the two tables on these columns
  //   output → input → pass the output column's data into the destination table's column
  let from, to, kind;
  if (a.kind === 'out' && b.kind === 'out') {
    return toast('Connect an output port to an input port (out → in), or two input ports to join', 'err');
  } else if (a.kind === 'in' && b.kind === 'in') {
    // JOIN — between sources and/or staging tables, never the target or a transformation
    if (mcRoleOf(a.node) === 'TARGET' || mcRoleOf(b.node) === 'TARGET')
      return toast('The target only receives data — join sources/staging together instead', 'err');
    if (mcRoleOf(a.node) === 'TRANSFORM' || mcRoleOf(b.node) === 'TRANSFORM')
      return toast('Transformations take DATA links: wire an output port → the transformation\'s input side', 'err');
    kind = 'JOIN'; from = a; to = b;
  } else {
    // MAP (output → input): data flows into a staging or target table, never into a source
    kind = 'MAP';
    if (a.kind === 'out') { from = a; to = b; } else { from = b; to = a; }
    if (mcRoleOf(to.node) === 'SOURCE')
      return toast('A source does not receive data — wire into a staging or target table', 'err');
    // One-path rule: a node's data must come from ONE connected stream. Reject a second upstream that
    // isn't joined to the existing ones — that's exactly what silently produced a cross join.
    if (from.node !== to.node) {
      const existing = new Set(mapState.canvas.links.filter(l => l.kind === 'MAP' && l.to.table === to.node && l.from.table !== from.node).map(l => l.from.table));
      for (const ex of existing) {
        if (!mcJoinConnected(ex, from.node)) {
          const nm = k => (mapState.canvas.nodes[k] ? mapState.canvas.nodes[k].name : k);
          return toast(`Can't feed ${nm(to.node)} from both ${nm(ex)} and ${nm(from.node)} — they aren't joined, so this would cross-join (cartesian). Route every column through ONE path, or join the sources first (wire input ↔ input).`, 'err');
        }
      }
    }
  }
  const dup = mapState.canvas.links.some(l => l.from.table === from.node && l.from.col === from.col && l.to.table === to.node && l.to.col === to.col && l.kind === kind);
  if (dup) return toast('That link already exists', 'info');
  if (kind === 'MAP') {
    // a destination column receives a single value — replace any existing data flowing into it
    mapState.canvas.links = mapState.canvas.links.filter(l => !(l.kind === 'MAP' && l.to.table === to.node && l.to.col === to.col));
  }
  const link = { id: mcUid(), kind, from: { table: from.node, col: from.col, port: from.kind }, to: { table: to.node, col: to.col, port: to.kind }, type: kind === 'JOIN' ? ($('mc-jointype')?.value || 'INNER') : null };
  mapState.canvas.links.push(link);
  mcSelLink = link.id;
  if (kind === 'MAP') mcInheritType(to, from);   // staging column adopts the mapped input's datatype/length
  // A transformation that just received an input recomputes its output ports (pass-through + derived).
  if (kind === 'MAP' && mapState.canvas.nodes[to.node] && mapState.canvas.nodes[to.node].kind === 'TRANSFORM') mcTfRecompute(to.node);
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();   // a new out→in link may turn a table into a target
  toast(kind === 'JOIN' ? `Join: ${from.node}.${from.col} = ${to.node}.${to.col}` : `Data → ${to.node}.${to.col} (from ${from.node}.${from.col})`, 'ok');
  return true;   // committed (rejections above return a falsy toast) — callers can roll back on false
}

/* ---------------- links rendering ---------------- */
function mcPortPos(table, col, kind) {
  const n = mapState.canvas.nodes[table]; if (!n) return null;
  const x = n.x + (kind === 'out' ? MC_NODE_W : 0);
  if (n.collapsed) return { x, y: n.y + MC_HEAD_H / 2 };
  const idx = n.cols.findIndex(c => c.name === col); if (idx < 0) return { x, y: n.y + MC_HEAD_H / 2 };
  return { x, y: n.y + MC_HEAD_H + MC_PAD_T + (idx + 0.5) * MC_ROW_H };
}
function mcBezier(a, b) {
  const dx = Math.max(40, Math.abs(b.x - a.x) * 0.5);
  return `M ${a.x} ${a.y} C ${a.x + dx} ${a.y}, ${b.x - dx} ${b.y}, ${b.x} ${b.y}`;
}
// tangents respect each endpoint's port side: output ports push right (+), input ports push left (-)
function mcBezierSides(a, aPort, b, bPort) {
  const dx = Math.max(46, Math.abs(b.x - a.x) * 0.5);
  const c1 = a.x + (aPort === 'in' ? -dx : dx);
  const c2 = b.x + (bPort === 'in' ? -dx : dx);
  return `M ${a.x} ${a.y} C ${c1} ${a.y}, ${c2} ${b.y}, ${b.x} ${b.y}`;
}
function mcDrawLinks() {
  const svg = $('mc-links'); if (!svg) return;
  const body = mapState.canvas.links.map(l => {
    const a = mcPortPos(l.from.table, l.from.col, l.from.port || 'out'), b = mcPortPos(l.to.table, l.to.col, l.to.port || 'in');
    if (!a || !b) return '';
    const isMap = l.kind === 'MAP';
    const d = mcBezierSides(a, l.from.port || 'out', b, l.to.port || 'in'), sel = l.id === mcSelLink, mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2;
    const label = isMap ? '→' : esc(l.type || 'INNER');
    const w = isMap ? 20 : 48;
    return `<path class="mc-link${isMap ? ' map' : ''}${sel ? ' sel' : ''}" d="${d}" marker-end="url(#mc-arrow)"></path>
      <path class="mc-link-hit" d="${d}" onclick="mcSelectLink('${l.id}')"></path>
      <g class="mc-tag${isMap ? ' map' : ''}${sel ? ' sel' : ''}" onclick="mcSelectLink('${l.id}')"><rect x="${mx - w / 2}" y="${my - 9}" width="${w}" height="18" rx="4"></rect><text x="${mx}" y="${my + 4}" text-anchor="middle">${label}</text></g>`;
  }).join('');
  svg.innerHTML = `<defs><marker id="mc-arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto"><path d="M0 0 L10 5 L0 10 z"></path></marker></defs>${body}`;
}
function mcDrawTemp(from, p) {
  const svg = $('mc-links'); if (!svg) return;
  const a = mcPortPos(from.node, from.col, from.kind) || p;
  let t = svg.querySelector('#mc-temp');
  if (!t) { t = document.createElementNS('http://www.w3.org/2000/svg', 'path'); t.id = 'mc-temp'; t.setAttribute('class', 'mc-link temp'); svg.appendChild(t); }
  t.setAttribute('d', mcBezierSides(a, from.kind, p, from.kind === 'out' ? 'in' : 'out'));
}
function mcClearTemp() { const t = $('mc-links')?.querySelector('#mc-temp'); if (t) t.remove(); }

/* ---------------- selection / join type ---------------- */
function mcSelectLink(id) {
  mcSelLink = id;
  const link = mapState.canvas.links.find(l => l.id === id);
  const sel = $('mc-jointype'); if (sel && link) sel.value = link.type;
  mcDrawLinks();
}
function mcSetSelType(v) {
  const link = mapState.canvas.links.find(l => l.id === mcSelLink);
  if (link && link.kind !== 'MAP') { link.type = v; mcDrawLinks(); }
  else if (link) toast('That link is a field mapping, not a join', 'info');
}
function mcDeleteSel() {
  if (!mcSelLink) return toast('Click a link first', 'info');
  mapState.canvas.links = mapState.canvas.links.filter(l => l.id !== mcSelLink);
  mcSelLink = null; mcRenderNodes(); mcRenderStrip(); mcDrawLinks();   // removing a mapping may turn a target back into a source
}

/* ---------------- view: zoom / overview / layout ---------------- */
function mcApplyZoom() {
  const c = $('mc-content'); if (c) { c.style.transform = `scale(${mcZoom()})`; c.style.transformOrigin = '0 0'; }
  const sel = $('mc-zoom'); if (sel) sel.value = String(mcZoom());
}
function mcSetZoom(v) { mapState.canvas.zoom = parseFloat(v) || 1; mcApplyZoom(); }
function mcToggleOverview() {
  const ov = mapState.canvas.view === 'overview';
  mapState.canvas.view = ov ? 'normal' : 'overview';
  Object.values(mapState.canvas.nodes).forEach(n => n.collapsed = !ov);
  mapState.canvas.zoom = ov ? 1 : 0.6;
  mcRenderNodes(); mcDrawLinks(); mcApplyZoom(); mcSyncOverviewBtn();
}
function mcSyncOverviewBtn() {
  const b = $('mc-ov-btn'); if (b) { const on = mapState.canvas.view === 'overview'; b.classList.toggle('on', on); b.textContent = on ? 'Exit overview' : 'Overview'; }
}
function mcAutoLayout() {
  const nodes = mapState.canvas.nodes, keys = Object.keys(nodes);
  if (!keys.length) return;
  const level = {}; keys.forEach(k => level[k] = 0);
  for (let pass = 0; pass < keys.length; pass++)
    mapState.canvas.links.forEach(l => { if (nodes[l.from.table] && nodes[l.to.table]) level[l.to.table] = Math.max(level[l.to.table], level[l.from.table] + 1); });
  const rowAt = {};
  keys.forEach(k => {
    const lv = level[k], row = (rowAt[lv] = (rowAt[lv] || 0));
    nodes[k].x = 32 + lv * (MC_NODE_W + 90);
    nodes[k].y = 32 + row * 250;
    rowAt[lv] = row + 1;
  });
  mcRenderNodes(); mcDrawLinks();
}
function mcClear() {
  if (!Object.keys(mapState.canvas.nodes).length) return;
  if (!confirm('Clear the whole canvas?')) return;
  mapState.canvas.nodes = {}; mapState.canvas.links = []; mcSelLink = null;
  mcRenderNodes(); mcRenderStrip(); mcDrawLinks();
}
function mcApply() {
  mcSyncToBuild();
  mapState.tab = 'build';
  mapRender();
  const w = mapState._designWarnings || [];
  if (w.length) { w.slice(0, 2).forEach(m => toast('⚠ ' + m, 'warn', 9000)); }
  else toast('Design applied — preview or load below', 'ok');
}

/* ---------------- DAG → SQL compiler ----------------
   Each staging/target node is the output of ONE join query over its inputs (the tables/stagings wired
   into it). Staging nodes feed downstream joins, so the whole graph compiles to a chain of CTEs:
     WITH s1 AS (… t1 ⨝ t2 ⨝ t3 …), s2 AS (… t4 ⨝ t5 …), s3 AS (… t6 ⨝ s1 …)
     INSERT INTO target (…) SELECT … FROM s3 ⨝ s2 …                                                   */
const MC_SQL_RESERVED = new Set(['distinct', 'limit', 'union', 'filter', 'order', 'group', 'select', 'from',
  'where', 'all', 'and', 'or', 'join', 'table', 'user', 'case', 'when', 'then', 'else', 'end', 'as', 'on',
  'in', 'is', 'not', 'null', 'with', 'having', 'offset', 'fetch', 'over', 'desc', 'asc', 'left', 'right',
  'inner', 'outer', 'full', 'cross', 'using', 'values', 'into', 'set', 'by', 'intersect', 'except', 'window']);
function mcCteName(name, used) {
  let base = String(name || 's').toLowerCase().replace(/[^a-z0-9_]/g, '_').replace(/^_+|_+$/g, '') || 's';
  if (MC_SQL_RESERVED.has(base)) base = 't_' + base;   // never name a CTE after a reserved word
  let n = base, i = 1; while (used.has(n)) n = base + '_' + i++; used.add(n); return n;
}
function mcBuildSql() {
  const nodes = mapState.canvas.nodes, keys = Object.keys(nodes);
  const maps = mapState.canvas.links.filter(l => l.kind === 'MAP');
  const joins = mapState.canvas.links.filter(l => l.kind === 'JOIN');
  const isStaging = k => nodes[k] && nodes[k].kind === 'STAGING';
  const isTf = k => nodes[k] && nodes[k].kind === 'TRANSFORM';
  const isCte = k => isStaging(k) || isTf(k);   // both compile to a WITH … AS ( … ) block

  const inputsOf = key => { const s = new Set(); maps.forEach(l => { if (l.to.table === key) s.add(l.from.table); }); return [...s]; };
  const colMapOf = key => { const m = {}; maps.forEach(l => { if (l.to.table === key) m[l.to.col] = { table: l.from.table, col: l.from.col }; }); return m; };

  const targetKey = keys.find(k => mcRoleOf(k) === 'TARGET');
  const cteKeys = keys.filter(isCte);
  const sourceKeys = keys.filter(k => mcRoleOf(k) === 'SOURCE');
  // source table names/aliases → used to strip stray qualifiers from transformation expressions
  const srcQuals = new Set();
  sourceKeys.forEach(k => { const n = nodes[k]; if (n.name) srcQuals.add(n.name.toLowerCase()); if (n.alias) srcQuals.add(n.alias.toLowerCase()); });
  const warnings = [];   // design problems surfaced at Apply (e.g. accidental cross joins)

  // topological order of intermediate CTEs (staging + transforms) — each waits for its CTE inputs
  const cte = {}, used = new Set(), order = [], pending = new Set(cteKeys);
  let guard = 0;
  while (pending.size && guard++ < 200) {
    let moved = false;
    for (const k of [...pending]) {
      const deps = inputsOf(k).filter(isCte);
      if (deps.every(d => !pending.has(d))) { order.push(k); pending.delete(k); moved = true; }
    }
    if (!moved) { order.push(...pending); pending.clear(); }   // break cycles defensively
  }
  order.forEach(k => cte[k] = mcCteName(nodes[k].name, used));

  const colRef = k => isCte(k) ? cte[k] : (nodes[k].alias || nodes[k].name);              // qualifier for column refs
  const fromRef = k => isCte(k) ? cte[k] : (((nodes[k].schema ? nodes[k].schema + '.' : '') + nodes[k].name) + ((nodes[k].alias && nodes[k].alias !== nodes[k].name) ? ' ' + nodes[k].alias : ''));

  function buildFrom(inputs, ownerKey) {
    if (!inputs.length) return null;
    let from = 'FROM ' + fromRef(inputs[0]);
    const joined = new Set([inputs[0]]);
    const conds = joins.filter(j => inputs.includes(j.from.table) && inputs.includes(j.to.table))
      .map(j => ({ lt: j.from.table, lc: j.from.col, rt: j.to.table, rc: j.to.col, type: j.type || 'INNER' }));
    const rem = conds.slice();
    let added = true;
    while (added) {
      added = false;
      const groups = {};
      rem.forEach(c => { const lJ = joined.has(c.lt), rJ = joined.has(c.rt); const nt = (lJ && !rJ) ? c.rt : (rJ && !lJ) ? c.lt : null; if (nt) (groups[nt] = groups[nt] || { conds: [], type: c.type }).conds.push(c); });
      const nk = Object.keys(groups)[0];
      if (nk) {
        const g = groups[nk], on = g.conds.map(c => `${colRef(c.lt)}.${c.lc} = ${colRef(c.rt)}.${c.rc}`).join(' AND ');
        from += `\n${g.type} JOIN ${fromRef(nk)} ON ${on}`;
        joined.add(nk);
        for (let i = rem.length - 1; i >= 0; i--) if (joined.has(rem[i].lt) && joined.has(rem[i].rt)) rem.splice(i, 1);
        added = true;
      }
    }
    inputs.slice(1).forEach(k => {
      if (!joined.has(k)) {
        from += `\nCROSS JOIN ${fromRef(k)}`;
        const owner = ownerKey && nodes[ownerKey] ? nodes[ownerKey].name : 'a node';
        warnings.push(`${owner} receives data from unrelated inputs (${nodes[inputs[0]] ? nodes[inputs[0]].name : inputs[0]} and ${nodes[k] ? nodes[k].name : k}) with no join between them → this produces a CROSS JOIN (cartesian product). Route all columns through one path, or add a join.`);
      }
    });
    return from;
  }
  function buildSelect(key) {
    if (isTf(key)) return mcTfSelect(key);
    const inputs = inputsOf(key); if (!inputs.length) return null;
    const cmap = colMapOf(key);
    const cols = nodes[key].cols.filter(c => cmap[c.name]);
    if (!cols.length) return null;
    const staging = isStaging(key);
    const sel = cols.map(c => {
      const s = cmap[c.name];
      let expr = `${colRef(s.table)}.${s.col}`;
      if (staging && c.type && !c.auto) expr = `CAST(${expr} AS ${c.type})`;   // user-defined datatype/length → explicit CAST
      return `${expr} AS ${c.name}`;
    }).join(', ');
    const from = buildFrom(inputs, key); if (!from) return null;
    return { sql: `SELECT ${sel}\n${from}`, cols: cols.map(c => c.name) };
  }

  /** Compile a TRANSFORM node's CTE: its single upstream relation, projected through the transform's
      output ports, with the type-specific clause (WHERE / GROUP BY / window / …). Expressions reference
      the transform's input-port names, which equal the upstream columns wired in — so they resolve. */
  function mcTfSelect(key) {
    const n = nodes[key], t = n.tf, type = n.tfType;
    const inputs = inputsOf(key); if (!inputs.length) return null;
    const up = inputs[0], upRef = colRef(up);
    const cmap = colMapOf(key);   // {inputPortName: {table, col}}
    const passRef = name => { const s = cmap[name]; return s ? `${colRef(s.table)}.${s.col}` : name; };
    const outCols = (n.cols || []).filter(c => n.ports[c.name] && n.ports[c.name].out);
    if (!outCols.length) return null;

    const dq = s => mcDequalify(s, srcQuals);   // strip stray source-table qualifiers
    // derived expressions by output name (everything not a straight pass-through)
    const derived = {};
    if (type === 'EXPRESSION') {
      (t.columns || []).forEach(c => { if ((c.expr || '').trim()) derived[mcCleanName(c.name)] = dq(c.expr.trim()); });
      // An Expression is ROW-LEVEL. An aggregate here (max/sum/…) mixed with pass-through columns is
      // invalid SQL (needs GROUP BY). Catch it early with an actionable message.
      const agg = /\b(max|min|sum|avg|count|stddev|stdev|variance|var_pop|array_agg|string_agg|listagg|bool_and|bool_or|every)\s*\(/i;
      const aggExpr = Object.entries(derived).find(([, e]) => agg.test(e));
      const hasPassthrough = outCols.some(c => derived[c.name] == null);
      if (aggExpr && hasPassthrough)
        warnings.push(`${n.name} uses an aggregate (${aggExpr[1]}) in an Expression alongside pass-through columns — that's invalid without GROUP BY. Use an AGGREGATOR transform (Group By + this aggregate), or make the Expression row-level.`);
    }
    if (type === 'AGGREGATOR') (t.aggregates || []).forEach(a => { if (a.name && (a.expr || '').trim()) derived[a.name] = dq(a.expr.trim()); });
    if (type === 'RANK') derived[t.name || 'rank_in_group'] = `ROW_NUMBER() OVER (${(t.partitionBy || '').trim() ? 'PARTITION BY ' + dq(t.partitionBy.trim()) + ' ' : ''}ORDER BY ${dq((t.orderBy || '1').trim() || '1')})`;
    if (type === 'SEQUENCE') derived[t.name || 'seq_no'] = `ROW_NUMBER() OVER (ORDER BY ${dq((t.orderBy || '').trim() || '1')})`;
    if (type === 'PIVOT') (t.values || '').split(',').map(s => s.trim()).filter(Boolean).forEach(v =>
      derived[mcPivotAlias(v)] = `${(t.agg || 'SUM')}(CASE WHEN ${dq(t.category)} = '${v.replace(/'/g, "''")}' THEN ${dq(t.value)} END)`);
    if (type === 'LOOKUP') (t.returns || '').split(',').map(s => s.trim()).filter(Boolean).forEach(r => { const m = r.match(/\bAS\s+(\w+)/i); derived[m ? m[1] : mcCleanName(r.split('.').pop())] = dq(r.replace(/\s+AS\s+\w+$/i, '')); });

    const selParts = outCols.map(c => `${derived[c.name] != null ? derived[c.name] : passRef(c.name)} AS ${c.name}`);
    let from = buildFrom(inputs, key); if (!from) return null;
    if (type === 'LOOKUP' && (t.table || '').trim() && (t.on || '').trim()) {
      const alias = (t.alias || 'lk1').trim();
      from += `\nLEFT JOIN ${(t.schema ? t.schema.trim() + '.' : '')}${t.table.trim()} ${alias} ON ${dq(t.on.trim())}`;
    }
    let sql = `SELECT ${type === 'DISTINCT' ? 'DISTINCT ' : ''}${selParts.join(', ') || '*'}\n${from}`;
    if (type === 'FILTER' && (t.condition || '').trim()) sql += `\nWHERE ${dq(t.condition.trim())}`;
    if (type === 'ROUTER' && (t.groups || []).length) {
      const g = t.groups[Math.min(parseInt(t.active || 0) || 0, t.groups.length - 1)];
      if (g && (g.condition || '').trim()) sql += `\nWHERE ${dq(g.condition.trim())}`;
    }
    if (type === 'AGGREGATOR') { const g = (t.groupBy || []).filter(Boolean); if (g.length) sql += `\nGROUP BY ${g.map(x => passRef(dq(x))).join(', ')}`; }
    if (type === 'PIVOT') { const g = (t.groupBy || '').split(',').map(s => s.trim()).filter(Boolean); if (g.length) sql += `\nGROUP BY ${g.map(x => passRef(dq(x))).join(', ')}`; }
    if (type === 'SORTER') { const s = (t.sort || []).filter(x => x.col); if (s.length) sql += `\nORDER BY ${s.map(x => `${dq(x.col)} ${x.dir || 'ASC'}`).join(', ')}`; }
    if (type === 'LIMIT' && t.rows) sql += `\nLIMIT ${parseInt(t.rows)}`;
    if (type === 'UNION' && (t.table || '').trim()) sql += `\nUNION ${t.all === false ? '' : 'ALL '}SELECT ${(t.columns || '').trim() || '*'} FROM ${(t.schema ? t.schema.trim() + '.' : '')}${t.table.trim()}${(t.condition || '').trim() ? ` WHERE ${t.condition.trim()}` : ''}`;
    if (type === 'RANK' && t.topN) {
      const rn = t.name || 'rank_in_group';
      sql = `SELECT * FROM (\n${sql.split('\n').map(x => '  ' + x).join('\n')}\n) __rk WHERE ${rn} <= ${parseInt(t.topN)}`;
    }
    return { sql, cols: outCols.map(c => c.name) };
  }

  const ctes = [], builtKeys = new Set();
  order.forEach(k => { const b = buildSelect(k); if (b) { builtKeys.add(k); ctes.push(`${cte[k]} AS (\n${b.sql.split('\n').map(x => '  ' + x).join('\n')}\n)`); } });
  const withClause = ctes.length ? 'WITH ' + ctes.join(',\n') + '\n' : '';

  const warn = [...new Set(warnings)];
  if (targetKey) {
    const b = buildSelect(targetKey); if (!b) return null;
    const tn = nodes[targetKey];
    return { mode: 'TABLE', sql: withClause + b.sql, targetCols: b.cols, dsId: tn.dsId, schema: tn.schema || '', table: tn.name, warnings: warn };
  }
  // preview: a terminal CTE (built, and not used as input by anyone), else the sources joined directly
  const terminal = order.filter(k => builtKeys.has(k) && !keys.some(o => o !== k && inputsOf(o).includes(k)));
  if (terminal.length) return { mode: 'PREVIEW', sql: withClause + `SELECT * FROM ${cte[terminal[terminal.length - 1]]}`, warnings: warn };
  if (sourceKeys.length) { const f = buildFrom(sourceKeys); if (f) return { mode: 'PREVIEW', sql: `SELECT *\n${f}`, warnings: warn }; }
  return null;
}

/* ---------------- sync to build model ---------------- */
function mcSyncToBuild() {
  const nodes = mapState.canvas.nodes, keys = Object.keys(nodes);
  if (!keys.length) return;
  const nm = t => (nodes[t] ? nodes[t].name : t);
  const sourceKeys = keys.filter(k => mcRoleOf(k) === 'SOURCE');

  // expose sources/joins for the Build tab display & single-DB federation fallback
  mapState.tables = sourceKeys.map(k => { const n = nodes[k]; return { dsId: n.dsId, schema: n.schema, name: n.name, cols: n.cols.map(c => c.name) }; });
  mapState.joins = mapState.canvas.links.filter(l => l.kind === 'JOIN').map(l => {
    const lt = nm(l.from.table), rt = nm(l.to.table);
    return { type: l.type || 'INNER', left: `${lt}.${l.from.col}`, right: `${rt}.${l.to.col}`, leftTable: lt, leftCol: l.from.col, rightTable: rt, rightCol: l.to.col };
  });
  // linearise the transform graph (wired order) so lineage & the Build tab still see the transforms;
  // the CANVAS SQL (mcBuildSql) remains authoritative for execution. Only overwrite when the canvas
  // actually has transform nodes — otherwise a legacy (Build-tab-only) mapping keeps its array.
  const tfOrder = mcTransformOrder();
  if (tfOrder.length) mapState.transforms = tfOrder.map(k => nodes[k].tf);

  const built = mcBuildSql();
  mapState._designWarnings = built && built.warnings ? built.warnings : [];
  if (built) {
    mapState.sql = built.sql;
    if (built.mode === 'TABLE') {
      mapState.target = { mode: 'TABLE', dsId: built.dsId, schema: built.schema, table: built.table, create: false };
      mapState.colmap = built.targetCols.map(c => ({ target: c, source: c }));   // SELECT already aliases to target cols; this drives the INSERT (…) list
    } else {
      mapState.target = { mode: 'PREVIEW', dsId: '', schema: '', table: '', create: false };
      mapState.colmap = null;
    }
  } else {
    mapState.colmap = null;
    if (mapState.target && mapState.target.mode === 'TABLE') mapState.target = { mode: 'PREVIEW', dsId: '', schema: '', table: '', create: false };
    mapState.sql = (typeof mapGenSql === 'function') ? mapGenSql() : mapState.sql;
  }

  if (!mapState.srcDsId && sourceKeys[0]) { mapState.srcDsId = nodes[sourceKeys[0]].dsId; mapState.srcSchema = nodes[sourceKeys[0]].schema; }
}
/* output-port selection → SELECT projection for single-source SQL (null = SELECT *) */
function mcProjection() {
  const nodes = mapState.canvas.nodes, keys = Object.keys(nodes).filter(k => mcRoleOf(k) === 'SOURCE');
  if (!keys.length) return null;
  let all = true; const sel = [];
  keys.forEach(k => { const n = nodes[k]; n.cols.forEach(c => {
    const on = !(n.ports[c.name] && n.ports[c.name].out === false);
    if (on) sel.push(`${n.name}.${c.name}`); else all = false;
  }); });
  return all ? null : (sel.length ? sel : null);
}

/* ---------------- helpers ---------------- */
function mcShortType(t) {
  if (!t) return '';
  return String(t).toLowerCase()
    .replace('character varying', 'varchar').replace('timestamp without time zone', 'timestamp')
    .replace('timestamp with time zone', 'timestamptz').replace('double precision', 'double').replace('integer', 'int');
}
function mcUid() { return 'l' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6); }
function mcJs(s) { return String(s).replace(/\\/g, '\\\\').replace(/'/g, "\\'"); }
function mcAttr(s) { return String(s).replace(/(["\\])/g, '\\$1'); }
