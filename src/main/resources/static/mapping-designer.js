/* ForgeTDM Mapping Designer — Informatica-style: sources + ER joins → transformation pipeline → target,
   compiled to runnable SQL. Reuses globals from app.js: api, esc, $, toast, dataSources, loadSchemas,
   loadTables, refreshShared, dsFillSelect. */

const mapState = {
  id: null, name: '', description: '',
  srcDsId: '', srcSchema: '',
  tables: [],            // { name, cols:[...] }
  joins: [],             // { type, leftTable, leftCol, rightTable, rightCol }
  transforms: [],        // ordered pipeline
  target: { mode: 'PREVIEW', dsId: '', schema: '', table: '', create: false },
  colmap: null,          // [{target, source}] — source→target field mapping when the canvas has a TARGET node
  sql: '', saved: [], lastFn: null,
  tab: 'designer',       // 'designer' (visual canvas) | 'build' (form + run)
  canvas: { nodes: {}, links: [], zoom: 1, view: 'normal' }   // nodes[name]={dsId,schema,name,x,y,cols:[{name,type}],ports:{col:{in,out}},collapsed}; links=[{from,to,type}]
};

/* ---- transformation library ---- */
const MAP_TRANSFORMS = [
  { type: 'FILTER',     label: 'Filter',        sql: true,  desc: 'Keep rows matching a condition (WHERE)' },
  { type: 'EXPRESSION', label: 'Expression',    sql: true,  desc: 'Derive / project output columns with formulas' },
  { type: 'AGGREGATOR', label: 'Aggregator',    sql: true,  desc: 'Group by and aggregate (SUM, COUNT, AVG…)' },
  { type: 'SORTER',     label: 'Sorter',        sql: true,  desc: 'Order rows' },
  { type: 'DISTINCT',   label: 'Distinct',      sql: true,  desc: 'Remove duplicate rows' },
  { type: 'LIMIT',      label: 'Limit',         sql: true,  desc: 'Cap the number of rows' },
  { type: 'ROUTER',     label: 'Router',        sql: true,  desc: 'Route one named condition group into the target' },
  { type: 'UNION',      label: 'Union',         sql: true,  desc: 'Append rows from another table (UNION / UNION ALL)' },
  { type: 'LOOKUP',     label: 'Lookup',        sql: true,  desc: 'Enrich from a reference table (LEFT JOIN)' },
  { type: 'RANK',       label: 'Rank / Top-N',  sql: true,  desc: 'Top-N per group via ROW_NUMBER() window' },
  { type: 'SEQUENCE',   label: 'Sequence',      sql: true,  desc: 'Add a running number column' },
  { type: 'PIVOT',      label: 'Pivot',         sql: true,  desc: 'Rows → columns via conditional aggregation' }
];

/* ---- function library: DIALECT-AWARE — chips show the syntax that actually runs on the
        source database (expressions execute there, so Postgres syntax fails on Oracle etc.) ---- */
function mapDialect() {
  const t = mapState.tables[0];
  const ds = (dataSources || []).find(x => String(x.id) === String(t ? t.dsId : mapState.srcDsId));
  const u = (((ds && ds.jdbcUrl) || '') + ' ' + ((ds && ds.kind) || '')).toLowerCase();
  if (u.includes('oracle')) return 'oracle';
  if (u.includes('sqlserver') || u.includes('mssql')) return 'sqlserver';
  if (u.includes('db2')) return 'db2';
  if (u.includes('h2')) return 'h2';
  return 'postgres';
}
const MAP_DIALECT_LABEL = { postgres: 'PostgreSQL', h2: 'H2', oracle: 'Oracle', sqlserver: 'SQL Server', db2: 'DB2' };

function mapFuncsFor(d) {
  const win = ['ROW_NUMBER() OVER (PARTITION BY p ORDER BY o)', 'RANK() OVER (ORDER BY o)', 'DENSE_RANK() OVER (ORDER BY o)', 'LAG(col) OVER (ORDER BY o)', 'LEAD(col) OVER (ORDER BY o)', 'NTILE(4) OVER (ORDER BY o)'];
  const num = ['ABS(n)', 'CEIL(n)', 'FLOOR(n)', 'ROUND(n, d)', 'MOD(a, b)', 'POWER(a, b)', 'SQRT(n)', 'SIGN(n)'];
  const cond = ['CASE WHEN cond THEN a ELSE b END', 'COALESCE(a, b)', 'NULLIF(a, b)'];
  if (d === 'oracle') return {
    String: ["a || b", 'SUBSTR(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'INITCAP(s)', 'TRIM(s)', 'LTRIM(s)', 'RTRIM(s)', 'LENGTH(s)', 'REPLACE(s, f, t)', 'LPAD(s, n, c)', 'RPAD(s, n, c)', 'INSTR(s, sub)', 'REGEXP_REPLACE(s, pat, rep)', 'REGEXP_SUBSTR(s, pat)'],
    Numeric: num.concat(['TRUNC(n, d)', 'GREATEST(a, b)', 'LEAST(a, b)']),
    Date: ['SYSDATE', 'CURRENT_TIMESTAMP', "TRUNC(d, 'MM')", 'EXTRACT(YEAR FROM d)', 'ADD_MONTHS(d, n)', 'MONTHS_BETWEEN(a, b)', 'd + 30', "TO_DATE(s, 'YYYY-MM-DD')", "TO_CHAR(d, 'YYYY-MM-DD')", 'LAST_DAY(d)'],
    Conditional: cond.concat(['NVL(a, b)', 'NVL2(x, a, b)', 'DECODE(x, v1, r1, d)']),
    Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDDEV(col)', "LISTAGG(col, ',') WITHIN GROUP (ORDER BY col)"],
    Conversion: ['CAST(x AS INTEGER)', 'TO_NUMBER(s)', 'TO_CHAR(n, fmt)', "TO_DATE(s, 'YYYY-MM-DD')"],
    Window: win
  };
  if (d === 'sqlserver') return {
    String: ['CONCAT(a, b)', "a + b", 'SUBSTRING(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'LTRIM(s)', 'RTRIM(s)', 'LEN(s)', 'REPLACE(s, f, t)', 'LEFT(s, n)', 'RIGHT(s, n)', 'CHARINDEX(sub, s)', 'REPLICATE(c, n)', "FORMAT(x, 'format')"],
    Numeric: num.concat(['GREATEST(a, b) /* 2022+ */']),
    Date: ['GETDATE()', 'SYSDATETIME()', 'DATEADD(day, n, d)', 'DATEDIFF(day, a, b)', 'DATEPART(year, d)', 'EOMONTH(d)', 'CONVERT(varchar, d, 120)'],
    Conditional: cond.concat(['ISNULL(a, b)', 'IIF(cond, a, b)']),
    Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDEV(col)', "STRING_AGG(col, ',')"],
    Conversion: ['CAST(x AS INT)', 'CONVERT(NUMERIC(12,2), x)', 'TRY_CAST(x AS INT)'],
    Window: win
  };
  if (d === 'db2') return {
    String: ["a || b", 'SUBSTR(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'TRIM(s)', 'LENGTH(s)', 'REPLACE(s, f, t)', 'LPAD(s, n, c)', 'RPAD(s, n, c)', 'LOCATE(sub, s)', 'TRANSLATE(s, to, from)'],
    Numeric: num.concat(['TRUNCATE(n, d)']),
    Date: ['CURRENT DATE', 'CURRENT TIMESTAMP', 'd + 30 DAYS', 'MONTHS_BETWEEN(a, b)', 'YEAR(d)', 'MONTH(d)', 'DAY(d)', 'DATE(s)', "VARCHAR_FORMAT(d, 'YYYY-MM-DD')", 'LAST_DAY(d)'],
    Conditional: cond.concat(['NVL(a, b)', 'DECODE(x, v1, r1, d)']),
    Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDDEV(col)', "LISTAGG(col, ',')"],
    Conversion: ['CAST(x AS INTEGER)', 'INT(x)', 'CHAR(x)', 'DECIMAL(x, 12, 2)'],
    Window: win
  };
  // postgres + h2 (near-identical; H2 runs in PostgreSQL compatibility mode here)
  const pg = {
    String: ['CONCAT(a, b)', "a || b", 'SUBSTRING(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'INITCAP(s)', 'TRIM(s)', 'LTRIM(s)', 'RTRIM(s)', 'LENGTH(s)', 'REPLACE(s, f, t)', 'LPAD(s, n, c)', 'RPAD(s, n, c)', 'LEFT(s, n)', 'RIGHT(s, n)', 'POSITION(sub IN s)', 'REGEXP_REPLACE(s, pat, rep)'],
    Numeric: num.concat(['TRUNC(n, d)', 'GREATEST(a, b)', 'LEAST(a, b)']),
    Date: ['CURRENT_DATE', 'CURRENT_TIMESTAMP', 'NOW()', "DATE_TRUNC('day', d)", 'EXTRACT(YEAR FROM d)', "d + INTERVAL '30 days'", "TO_DATE(s, 'YYYY-MM-DD')", "TO_CHAR(d, 'YYYY-MM-DD')"],
    Conditional: cond.concat(["/* IIF */ CASE WHEN cond THEN a ELSE b END", "/* DECODE */ CASE x WHEN v1 THEN r1 ELSE d END"]),
    Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDDEV(col)', "STRING_AGG(col, ',')"],
    Conversion: ['CAST(x AS INTEGER)', 'CAST(x AS NUMERIC(12,2))', 'x::text', 'TO_CHAR(n, fmt)'],
    Window: win
  };
  if (d === 'postgres') {
    pg.String.push('SPLIT_PART(s, delim, n)');
    pg.Date.push('AGE(d)', "DATE_PART('month', d)");
    pg['Informatica → SQL'] = [
      'IIF(c, a, b)  →  CASE WHEN c THEN a ELSE b END',
      'DECODE(x, v1, r1, d)  →  CASE x WHEN v1 THEN r1 ELSE d END',
      'NVL(a, b) / ISNULL  →  COALESCE(a, b)',
      'INSTR(s, sub)  →  POSITION(sub IN s)',
      'SUBSTR(s, p, n)  →  SUBSTRING(s, p, n)',
      'SYSDATE  →  CURRENT_TIMESTAMP',
      "ADD_TO_DATE(d, 'DD', n)  →  d + (n || ' days')::interval",
      "DATE_DIFF(a, b, 'DD')  →  (a::date - b::date)",
      'REG_REPLACE(s, pat, rep)  →  REGEXP_REPLACE(s, pat, rep)',
      'MD5(s)  →  md5(s)'
    ];
  }
  return pg;
}

async function loadMapping() {
  await refreshShared();
  await mapLoadList();
  wfLoadList();       // workflows card — async, non-blocking
  wfRenderSteps();
  mapRender();
}

async function mapLoadList() {
  try { mapState.saved = await api.get('/api/mappings'); } catch (e) { mapState.saved = []; }
}

/* ---------------- render ---------------- */
function mapRender() {
  const app = $('map-app'); if (!app) return;
  const dsOpts = (cur) => '<option value="">Data source</option>' + (dataSources || []).map(d => `<option value="${d.id}"${String(cur) === String(d.id) ? ' selected' : ''}>${esc(d.name)}</option>`).join('');
  app.innerHTML = `
    <div class="card map-topbar">
      <div><label>Mapping name</label><input value="${esc(mapState.name)}" oninput="mapSet('name',this.value)" placeholder="cust_dw_load"></div>
      <div class="grow"><label>Description</label><input value="${esc(mapState.description)}" oninput="mapSet('description',this.value)" placeholder="optional"></div>
      <div><label>Saved</label><select onchange="if(this.value)mapOpen(this.value)"><option value="">— open —</option>${mapState.saved.map(m => `<option value="${m.id}">${esc(m.name)}</option>`).join('')}</select></div>
      <button class="ghost small" onclick="mapNew()">New</button>
      <button onclick="mapSave()">Save</button>
      ${mapState.id ? `<button class="ghost small danger" onclick="mapDelete()">Delete</button>` : ''}
    </div>

    <div class="map-tabbar">
      <button class="map-tab ${mapState.tab === 'designer' ? 'on' : ''}" onclick="mapTab('designer')">◳ Designer</button>
      <button class="map-tab ${mapState.tab === 'build' ? 'on' : ''}" onclick="mapTab('build')">⚙ Build &amp; Run</button>
      <span class="grow"></span>
      <span class="sub">${mapState.tab === 'designer' ? 'Drag tables from the left, wire columns to define joins.' : 'Tune transformations &amp; target, then preview or load.'}</span>
      <button class="ghost small" id="map-full-btn" onclick="mapToggleFull()" title="Maximize the designer (Esc to exit)">${mapFull ? '🗗 Exit full screen' : '⛶ Full screen'}</button>
    </div>

    <div id="map-designer-panel" style="${mapState.tab === 'designer' ? '' : 'display:none'}"></div>

    <div id="map-build-panel" style="${mapState.tab === 'build' ? '' : 'display:none'}">
    <div class="map-flow">${mapFlow()}</div>

    <div class="card">
      <div class="card-head"><h3>1 · Sources &amp; joins</h3><span class="pill dim">${mapState.tables.length} table(s)</span></div>
      <div class="row push">
        <div><label>Source data source</label><select id="map-src-ds" onchange="mapSrcDs(this.value)">${dsOpts(mapState.srcDsId)}</select></div>
        <div><label>Schema</label><select id="map-src-schema" onchange="mapSrcSchema(this.value)"><option value="">Schema</option></select></div>
        <div class="grow"><label>Add tables</label><select id="map-src-tables" multiple size="3"></select></div>
        <button class="ghost small" onclick="mapAddTables()">+ Add</button>
      </div>
      <div class="map-tables">${mapState.tables.map((t, i) => `<span class="map-chip"><b>${esc(t.name)}</b><span class="sub">${esc(mapDsName(t.dsId))}${t.schema ? ' · ' + esc(t.schema) : ''} · ${t.cols.length} cols</span><button class="iconbtn danger" onclick="mapRemoveTable(${i})">&times;</button></span>`).join('') || '<span class="sub">No source tables yet.</span>'}</div>
      ${mapMultiSource() ? '<p class="sub tight" style="color:var(--accent)">Cross-database mapping: tables span multiple data sources — runs via the in-memory federation engine (equi-joins + projection). The SQL editor is bypassed.</p>' : ''}
      ${mapState.tables.length > 1 ? mapJoinsEditor() : ''}
    </div>

    <div class="card">
      <div class="card-head"><h3>2 · Transformations</h3>
        <select id="map-add-tf" onchange="if(this.value){mapAddTransform(this.value);this.value='';}">
          <option value="">+ Add transformation…</option>
          ${MAP_TRANSFORMS.map(t => `<option value="${t.type}">${t.label}${t.sql ? '' : ' (design)'}</option>`).join('')}
        </select>
      </div>
      <div class="map-pipe">${mapState.transforms.length ? mapState.transforms.map((t, i) => mapTransformCard(t, i)).join('') : '<div class="empty">Add filters, expressions, aggregates, sorters… the pipeline runs top to bottom.</div>'}</div>
    </div>

    <div class="card">
      <div class="card-head"><h3>3 · Target</h3></div>
      <div class="row push">
        <div><label>Output</label><select onchange="mapTgt('mode',this.value)">
          ${['PREVIEW', 'TABLE', 'CSV', 'JSON'].map(m => `<option value="${m}"${mapState.target.mode === m ? ' selected' : ''}>${({ PREVIEW: 'Preview rows', TABLE: 'Load into table', CSV: 'CSV file', JSON: 'JSON file' })[m]}</option>`).join('')}
        </select></div>
        ${mapState.target.mode === 'TABLE' ? `
        <div><label>Target data source</label><select onchange="mapTgt('dsId',this.value)">${dsOpts(mapState.target.dsId)}</select></div>
        <div><label>Target schema</label><input value="${esc(mapState.target.schema)}" oninput="mapTgt('schema',this.value)" placeholder="optional"></div>
        <div><label>Target table</label><input value="${esc(mapState.target.table)}" oninput="mapTgt('table',this.value)" placeholder="dw_customers"></div>
        <label class="check"><input type="checkbox" ${mapState.target.create ? 'checked' : ''} onchange="mapTgt('create',this.checked)"> Create table (CTAS)</label>` : ''}
      </div>
    </div>

    <div class="card">
      <div class="card-head"><h3>Generated SQL</h3><button class="ghost small" onclick="mapRegen()">Regenerate from design</button></div>
      <textarea id="map-sql" class="mono" rows="8" oninput="mapState.sql=this.value" placeholder="Design your mapping above, then Regenerate.">${esc(mapState.sql)}</textarea>
      <div class="row push" style="margin-top:10px">
        <button onclick="mapRun()">${mapState.target.mode === 'TABLE' ? 'Run load' : 'Preview'}</button>
        <span class="grow"></span><span id="map-status" class="sub"></span>
      </div>
      <div id="map-results"></div>
    </div>

    <div class="card" id="map-fn-card"><div class="card-head"><h3>Function library</h3><span id="map-fn-dialect" class="pill dim"></span><input id="map-fn-search" class="ds-search" placeholder="search functions…" oninput="mapRenderFns()"></div>
      <p class="sub tight">Click a function to insert it into the expression field you were editing (falls back to copy). Syntax follows the SOURCE database's dialect. Verify anything against live data below.</p>
      <div class="row push"><div class="grow"><label>Test an expression on the source</label><input id="map-expr-test" placeholder="UPPER(first_name) || ' #' || id"></div>
        <button class="ghost small" onclick="mapTestExpr()">Run on 1 row</button><span id="map-expr-result" class="mono"></span></div>
      <div id="map-fns"></div>
    </div>
    </div>`;

  mapFillSrc();
  mapRenderFns();
  if (mapState.tab === 'designer' && typeof mcInit === 'function') mcInit();
}

/* ---- full-screen mode: maximize the whole designer over the app chrome (small-screen relief).
        Uses native fullscreen when available, with a fixed-overlay fallback; Esc exits both. ---- */
let mapFull = false;

function mapToggleFull() {
  mapFull = !mapFull;
  const el = $('map-app');
  if (!el) return;
  el.classList.toggle('map-fullscreen', mapFull);
  document.body.classList.toggle('map-noscroll', mapFull);
  if (mapFull) {
    const p = el.requestFullscreen && el.requestFullscreen();
    if (p && p.catch) p.catch(() => { /* overlay fallback is already active */ });
  } else if (document.fullscreenElement) {
    document.exitFullscreen && document.exitFullscreen();
  }
  const btn = $('map-full-btn');
  if (btn) btn.textContent = mapFull ? '🗗 Exit full screen' : '⛶ Full screen';
  if (typeof mcDrawLinks === 'function' && mapState.tab === 'designer') setTimeout(mcDrawLinks, 60);   // re-anchor wires after resize
}

document.addEventListener('keydown', e => {
  if (e.key === 'Escape' && mapFull && !document.fullscreenElement) mapToggleFull();
});
document.addEventListener('fullscreenchange', () => {
  // browser-side exit (Esc in native fullscreen) — sync the overlay state
  if (!document.fullscreenElement && mapFull) {
    mapFull = false;
    const el = $('map-app');
    if (el) el.classList.remove('map-fullscreen');
    document.body.classList.remove('map-noscroll');
    const btn = $('map-full-btn');
    if (btn) btn.textContent = '⛶ Full screen';
  }
});

function mapTab(t) {
  if (t === mapState.tab) return;
  // leaving the designer: push wired joins + tables into the build model so it can run
  if (mapState.tab === 'designer' && typeof mcSyncToBuild === 'function') mcSyncToBuild();
  mapState.tab = t;
  mapRender();
}

function mapFlow() {
  const src = mapState.tables.length ? mapState.tables.map(t => esc(t.name)).join(' ⨝ ') : 'Source';
  const chips = mapState.transforms.map(t => `<span class="map-flow-chip">${esc((MAP_TRANSFORMS.find(x => x.type === t.type) || {}).label || t.type)}</span>`).join('<span class="map-arrow">→</span>');
  const tgt = mapState.target.mode === 'TABLE' ? (mapState.target.table || 'table') : mapState.target.mode;
  return `<span class="map-flow-chip src">${src}</span>${chips ? '<span class="map-arrow">→</span>' + chips : ''}<span class="map-arrow">→</span><span class="map-flow-chip tgt">${esc(tgt)}</span>`;
}

function mapJoinsEditor() {
  const colOpts = () => mapState.tables.map(t => t.cols.map(c => `${t.name}.${c}`)).flat();
  const opts = (sel) => colOpts().map(c => `<option${c === sel ? ' selected' : ''}>${esc(c)}</option>`).join('');
  const tblOpts = (sel) => mapState.tables.map(t => `<option${t.name === sel ? ' selected' : ''}>${esc(t.name)}</option>`).join('');
  return `<div class="map-joins"><div class="sub" style="margin:6px 0">Joins</div>
    ${mapState.joins.map((j, i) => `<div class="map-join-row">
      <select onchange="mapJoin(${i},'type',this.value)">${['INNER', 'LEFT', 'RIGHT', 'FULL'].map(x => `<option${j.type === x ? ' selected' : ''}>${x}</option>`).join('')}</select>
      <span>join</span>
      <input value="${esc(j.left || '')}" oninput="mapJoin(${i},'left',this.value)" placeholder="table.col" list="map-cols">
      <span>=</span>
      <input value="${esc(j.right || '')}" oninput="mapJoin(${i},'right',this.value)" placeholder="table.col" list="map-cols">
      <button class="iconbtn danger" onclick="mapRemoveJoin(${i})">&times;</button>
    </div>`).join('')}
    <datalist id="map-cols">${colOpts().map(c => `<option value="${esc(c)}">`).join('')}</datalist>
    <button class="ghost small" onclick="mapAddJoin()">+ Join</button></div>`;
}

function mapTransformCard(t, i) {
  const meta = MAP_TRANSFORMS.find(x => x.type === t.type) || {};
  const body = mapTfBody(t, i);
  return `<div class="map-tf${meta.sql ? '' : ' design'}">
    <div class="map-tf-head"><span class="n">${i + 1}</span><b>${esc(meta.label || t.type)}</b>${meta.sql ? '' : '<span class="pill dim">design</span>'}
      <span class="grow"></span>
      <button class="iconbtn" title="Move up" onclick="mapMoveTf(${i},-1)">↑</button>
      <button class="iconbtn" title="Move down" onclick="mapMoveTf(${i},1)">↓</button>
      <button class="iconbtn danger" onclick="mapRemoveTf(${i})">&times;</button></div>
    <div class="map-tf-body">${body}</div></div>`;
}

/** The per-type property editor — shared by the Build tab card (h = numeric index) AND the Designer's
    on-canvas node panel (h = node key). The handle is always quoted so both forms reach mapTfObj. */
function mapTfBody(t, h) {
  const meta = MAP_TRANSFORMS.find(x => x.type === t.type) || {};
  const H = `'${h}'`;
  let body = '';
  if (t.type === 'FILTER') body = `<input value="${esc(t.condition || '')}" oninput="mapTf(${H},'condition',this.value)" placeholder="status = 1 AND region = 'US'">`;
  else if (t.type === 'EXPRESSION') body = `${(t.columns || []).map((c, ci) => `<div class="map-exprow"><input value="${esc(c.name || '')}" oninput="mapTfCol(${H},${ci},'name',this.value)" placeholder="out_col"><span>=</span><input value="${esc(c.expr || '')}" oninput="mapTfCol(${H},${ci},'expr',this.value)" placeholder="UPPER(first_name)"><button class="iconbtn danger" onclick="mapTfColDel(${H},${ci})">&times;</button></div>`).join('')}<button class="ghost small" onclick="mapTfColAdd(${H})">+ column</button>`;
  else if (t.type === 'AGGREGATOR') body = `<label class="map-lbl">Group by (comma list)</label><input value="${esc((t.groupBy || []).join(', '))}" oninput="mapTf(${H},'groupBy',this.value.split(',').map(s=>s.trim()).filter(Boolean))" placeholder="region, status">
      <label class="map-lbl">Aggregates</label>${(t.aggregates || []).map((a, ci) => `<div class="map-exprow"><input value="${esc(a.name || '')}" oninput="mapTfAgg(${H},${ci},'name',this.value)" placeholder="total"><span>=</span><input value="${esc(a.expr || '')}" oninput="mapTfAgg(${H},${ci},'expr',this.value)" placeholder="SUM(balance)"><button class="iconbtn danger" onclick="mapTfAggDel(${H},${ci})">&times;</button></div>`).join('')}<button class="ghost small" onclick="mapTfAggAdd(${H})">+ aggregate</button>`;
  else if (t.type === 'SORTER') body = `<input value="${esc((t.sort || []).map(s => s.col + (s.dir === 'DESC' ? ' DESC' : '')).join(', '))}" oninput="mapTfSort(${H},this.value)" placeholder="created_on DESC, id">`;
  else if (t.type === 'LIMIT') body = `<input type="number" min="1" value="${esc(t.rows || '')}" oninput="mapTf(${H},'rows',this.value)" placeholder="1000" style="width:140px">`;
  else if (t.type === 'DISTINCT') body = `<span class="sub">Removes duplicate rows from the result.</span>`;
  else if (t.type === 'ROUTER') {
    const groups = t.groups || [];
    body = `<label class="map-lbl">Groups — with per-group targets set and Output = Table, ALL groups load in ONE transaction (blank condition = DEFAULT group: rows matching no other group)</label>` +
      groups.map((g, gi) => `<div class="map-exprow"><input value="${esc(g.name || '')}" oninput="mapTfGroup(${H},${gi},'name',this.value)" placeholder="us-retail" style="max-width:140px"><span>WHEN</span><input value="${esc(g.condition || '')}" oninput="mapTfGroup(${H},${gi},'condition',this.value)" placeholder="region = 'US'  (blank = DEFAULT)"><span>→</span><input value="${esc(g.target || '')}" oninput="mapTfGroup(${H},${gi},'target',this.value)" placeholder="target table" style="max-width:170px"><input value="${esc(g.targetSchema || '')}" oninput="mapTfGroup(${H},${gi},'targetSchema',this.value)" placeholder="schema" style="max-width:110px"><button class="iconbtn danger" onclick="mapTfGroupDel(${H},${gi})">&times;</button></div>`).join('') +
      `<button class="ghost small" onclick="mapTfGroupAdd(${H})">+ group</button>
       <label class="map-lbl">Active group (Preview mode shows this group only)</label><select onchange="mapTf(${H},'active',this.value)">${groups.map((g, gi) => `<option value="${gi}" ${String(t.active ?? 0) === String(gi) ? 'selected' : ''}>${esc(g.name || ('group ' + (gi + 1)))}</option>`).join('') || '<option value="0">add a group first</option>'}</select>`;
  }
  else if (t.type === 'UNION') body = `<div class="map-exprow"><input value="${esc(t.table || '')}" oninput="mapTf(${H},'table',this.value)" placeholder="other_table"><input value="${esc(t.schema || '')}" oninput="mapTf(${H},'schema',this.value)" placeholder="schema (optional)" style="max-width:160px"></div>
      <label class="map-lbl">Columns (comma list matching the main SELECT; blank = *)</label><input value="${esc(t.columns || '')}" oninput="mapTf(${H},'columns',this.value)" placeholder="id, name, balance">
      <label class="map-lbl">WHERE (optional)</label><input value="${esc(t.condition || '')}" oninput="mapTf(${H},'condition',this.value)" placeholder="status = 'ACTIVE'">
      <label class="check"><input type="checkbox" ${t.all === false ? '' : 'checked'} onchange="mapTf(${H},'all',this.checked)"> UNION ALL (keep duplicates — faster)</label>`;
  else if (t.type === 'LOOKUP') body = `<div class="map-exprow"><input value="${esc(t.table || '')}" oninput="mapTf(${H},'table',this.value)" placeholder="ref_ratings"><input value="${esc(t.schema || '')}" oninput="mapTf(${H},'schema',this.value)" placeholder="schema (optional)" style="max-width:140px"><input value="${esc(t.alias || '')}" oninput="mapTf(${H},'alias',this.value)" placeholder="alias (lk1)" style="max-width:120px"></div>
      <label class="map-lbl">Match condition (use the alias)</label><input value="${esc(t.on || '')}" oninput="mapTf(${H},'on',this.value)" placeholder="customers.cust_id = lk1.cust_id">
      <label class="map-lbl">Return columns (comma list; blank = all lookup columns when SELECT *)</label><input value="${esc(t.returns || '')}" oninput="mapTf(${H},'returns',this.value)" placeholder="lk1.rating AS credit_rating">`;
  else if (t.type === 'RANK') body = `<div class="map-exprow"><input value="${esc(t.partitionBy || '')}" oninput="mapTf(${H},'partitionBy',this.value)" placeholder="partition by (optional): region"><input value="${esc(t.orderBy || '')}" oninput="mapTf(${H},'orderBy',this.value)" placeholder="order by: balance DESC"></div>
      <div class="map-exprow"><input type="number" min="1" value="${esc(t.topN || '')}" oninput="mapTf(${H},'topN',this.value)" placeholder="top N (blank = rank only)" style="max-width:200px"><input value="${esc(t.name || '')}" oninput="mapTf(${H},'name',this.value)" placeholder="rank column (rank_in_group)" style="max-width:220px"></div>`;
  else if (t.type === 'SEQUENCE') body = `<div class="map-exprow"><input value="${esc(t.name || '')}" oninput="mapTf(${H},'name',this.value)" placeholder="column name (seq_no)" style="max-width:220px"><input value="${esc(t.orderBy || '')}" oninput="mapTf(${H},'orderBy',this.value)" placeholder="order by (optional): id"></div>`;
  else if (t.type === 'PIVOT') body = `<label class="map-lbl">Group by (comma list)</label><input value="${esc(t.groupBy || '')}" oninput="mapTf(${H},'groupBy',this.value)" placeholder="region">
      <div class="map-exprow"><input value="${esc(t.category || '')}" oninput="mapTf(${H},'category',this.value)" placeholder="category column: product_type"><input value="${esc(t.value || '')}" oninput="mapTf(${H},'value',this.value)" placeholder="value column: amount"><select onchange="mapTf(${H},'agg',this.value)">${['SUM','COUNT','AVG','MIN','MAX'].map(a => `<option ${((t.agg || 'SUM') === a) ? 'selected' : ''}>${a}</option>`).join('')}</select></div>
      <label class="map-lbl">Pivot values (comma list — one output column each)</label><input value="${esc(t.values || '')}" oninput="mapTf(${H},'values',this.value)" placeholder="A1, A2, A3">`;
  else body = `<span class="sub">${esc(meta.desc || '')}</span>`;
  return body;
}

function mapRenderFns() {
  const el = $('map-fns'); if (!el) return;
  const d = mapDialect();
  if ($('map-fn-dialect')) $('map-fn-dialect').textContent = (MAP_DIALECT_LABEL[d] || d) + ' syntax';
  const q = ($('map-fn-search')?.value || '').toLowerCase();
  el.innerHTML = Object.entries(mapFuncsFor(d)).map(([cat, fns]) => {
    const list = fns.filter(f => !q || f.toLowerCase().includes(q));
    if (!list.length) return '';
    return `<div class="map-fn-cat"><div class="map-fn-cat-h">${esc(cat)}</div><div class="map-fn-chips">${list.map(f => `<button class="map-fn" title="Insert into the focused expression" onmousedown="event.preventDefault()" onclick="mapUseFn(${jsStr(f)})">${esc(f)}</button>`).join('')}</div></div>`;
  }).join('') || '<div class="empty">No functions match.</div>';
}
function jsStr(s) { return JSON.stringify(s); }

/* click-to-insert: remember the last expression-capable field the user focused inside the designer */
let mapLastInput = null;
document.addEventListener('focusin', e => {
  const t = e.target;
  if (!t || t.id === 'map-fn-search' || t.id === 'map-expr-test') return;
  if (((t.tagName === 'INPUT' && (!t.type || t.type === 'text')) || t.tagName === 'TEXTAREA')
      && t.closest && t.closest('#map-app')) mapLastInput = t;
});

function mapUseFn(f) {
  // the Informatica→SQL reference rows are documentation, not insertable code
  const insertable = f.includes('→') ? f.split('→').pop().trim() : f;
  const el = mapLastInput;
  if (el && document.contains(el)) {
    const s = el.selectionStart ?? el.value.length, e2 = el.selectionEnd ?? s;
    el.value = el.value.slice(0, s) + insertable + el.value.slice(e2);
    const pos = s + insertable.length;
    el.focus();
    el.setSelectionRange(pos, pos);
    el.dispatchEvent(new Event('input'));   // fire the inline oninput so mapState picks it up
  } else {
    navigator.clipboard?.writeText(insertable);
    toast('Copied (click an expression field first to insert directly): ' + insertable, 'ok');
  }
}

/** Prove a function/expression works on the ACTUAL source database — runs SELECT <expr> on one row. */
async function mapTestExpr() {
  const expr = ($('map-expr-test')?.value || '').trim();
  const out = $('map-expr-result');
  if (!expr) return toast("Type an expression to test, e.g. UPPER(first_name)", 'err');
  const t = mapState.tables[0];
  if (!t) return toast('Add a source table first', 'err');
  const d = mapDialect();
  const from = mapQual(t);
  const sql = d === 'sqlserver'
    ? `SELECT TOP 1 ${expr} AS result FROM ${from}`
    : `SELECT ${expr} AS result FROM ${from} ` + ((d === 'oracle' || d === 'db2') ? 'FETCH FIRST 1 ROWS ONLY' : 'LIMIT 1');
  if (out) { out.textContent = '…'; out.className = 'mono'; }
  try {
    const r = await api.post('/api/mappings/preview', { dataSourceId: parseInt(t.dsId), sql });
    const v = (r.rows && r.rows.length) ? r.rows[0][0] : '(table has no rows)';
    out.textContent = '= ' + (v === null ? 'NULL' : String(v));
    out.className = 'mono';
  } catch (e) {
    out.textContent = e.message;   // the database's own error — tells you exactly why it doesn't work
    out.className = 'mono warn-row';
  }
}

/* ---------------- source handlers ---------------- */
function mapSet(k, v) { mapState[k] = v; }
function mapTgt(k, v) { mapState.target[k] = v; if (k === 'mode') mapRender(); }
async function mapFillSrc() {
  const sel = $('map-src-schema'); if (!sel) return;
  if (!mapState.srcDsId) { sel.innerHTML = '<option value="">Schema</option>'; return; }
  const rows = await loadSchemas(mapState.srcDsId);
  sel.innerHTML = '<option value="">Schema</option>' + rows.map(r => `<option value="${esc(r.schema)}"${r.schema === mapState.srcSchema ? ' selected' : ''}>${esc(r.schema)}</option>`).join('');
  if (mapState.srcSchema) mapFillSrcTables();
}
async function mapFillSrcTables() {
  const box = $('map-src-tables'); if (!box) return;
  if (!mapState.srcDsId || !mapState.srcSchema) { box.innerHTML = ''; return; }
  const tables = await loadTables(mapState.srcDsId, mapState.srcSchema);
  box.innerHTML = tables.map(t => `<option value="${esc(t)}">${esc(t)}</option>`).join('');
}
function mapSrcDs(v) { mapState.srcDsId = v; mapState.srcSchema = ''; mapFillSrc(); }
function mapSrcSchema(v) { mapState.srcSchema = v; mapFillSrcTables(); }
async function mapAddTables() {
  const sel = $('map-src-tables'); if (!sel) return;
  const names = [...sel.selectedOptions].map(o => o.value).filter(Boolean);
  if (!names.length) return toast('Select one or more tables', 'err');
  const have = new Set(mapState.tables.map(t => t.name.toLowerCase()));
  for (const name of names) {
    if (have.has(name.toLowerCase())) continue;
    let cols = [];
    try {
      const meta = await api.get(`/api/datasources/${mapState.srcDsId}/tables/${encodeURIComponent(name)}/columns?schema=${encodeURIComponent(mapState.srcSchema)}`);
      cols = meta.map(c => c.column);
    } catch (e) { toast(`${name}: ${e.message}`, 'err'); }
    mapState.tables.push({ dsId: mapState.srcDsId, schema: mapState.srcSchema, name, cols });
    have.add(name.toLowerCase());
  }
  mapRender();
}
function mapDsName(id) { const d = (dataSources || []).find(x => String(x.id) === String(id)); return d ? d.name : ('ds#' + id); }
function mapMultiSource() { return new Set(mapState.tables.map(t => String(t.dsId))).size > 1; }
function mapRemoveTable(i) { mapState.tables.splice(i, 1); mapRender(); }

/* joins */
function mapAddJoin() {
  const t = mapState.tables;
  mapState.joins.push({ type: 'INNER', leftTable: t[0]?.name || '', leftCol: '', rightTable: t[1]?.name || '', rightCol: '', left: '', right: '' });
  mapRender();
}
function mapRemoveJoin(i) { mapState.joins.splice(i, 1); mapRender(); }
function mapJoin(i, k, v) { const j = mapState.joins[i]; if (j) j[k] = v; }

/* transforms */
function mapAddTransform(type) {
  const t = { type };
  if (type === 'EXPRESSION') t.columns = [{ name: '', expr: '' }];
  if (type === 'AGGREGATOR') { t.groupBy = []; t.aggregates = [{ name: '', expr: '' }]; }
  if (type === 'SORTER') t.sort = [];
  mapState.transforms.push(t); mapRender();
}
function mapRemoveTf(i) { mapState.transforms.splice(i, 1); mapRender(); }
function mapMoveTf(i, d) { const a = mapState.transforms, j = i + d; if (j < 0 || j >= a.length) return; [a[i], a[j]] = [a[j], a[i]]; mapRender(); }

/* Transform property callbacks are HANDLE-based so the same editor (mapTfBody) works for the Build-tab
   array (handle = numeric index) AND the canvas transform NODES (handle = node key string). */
function mapTfObj(h) {
  if (typeof h === 'number') return mapState.transforms[h];
  if (typeof h === 'string' && /^\d+$/.test(h)) return mapState.transforms[+h];
  const n = mapState.canvas && mapState.canvas.nodes[h];
  return n ? n.tf : null;
}
function mapTfIsNode(h) { return typeof h === 'string' && !/^\d+$/.test(h) && mapState.canvas && mapState.canvas.nodes[h]; }
// Light refresh for VALUE edits (oninput): update the canvas node's ports/wires but DON'T rebuild the
// property panel — that would steal focus from the field being typed in. (Build-tab edits: no-op, as before.)
function mapTfTouch(h) {
  if (mapTfIsNode(h)) { if (typeof mcTfRecompute === 'function') mcTfRecompute(h); if (typeof mcRenderNodes === 'function') { mcRenderNodes(); mcDrawLinks(); } }
}
// Full refresh for STRUCTURAL edits (add/remove row): rebuild the editor.
function mapTfStruct(h) {
  if (mapTfIsNode(h)) { if (typeof mcTfRecompute === 'function') mcTfRecompute(h); if (typeof mcRenderNodes === 'function') { mcRenderNodes(); mcRenderStrip(); mcDrawLinks(); } }
  else mapRender();
}
function mapTf(h, k, v) { const t = mapTfObj(h); if (t) { t[k] = v; mapTfTouch(h); } }
function mapTfCol(h, ci, k, v) { const t = mapTfObj(h); if (t && t.columns[ci]) { t.columns[ci][k] = v; mapTfTouch(h); } }
function mapTfColAdd(h) { const t = mapTfObj(h); if (t) { (t.columns = t.columns || []).push({ name: '', expr: '' }); mapTfStruct(h); } }
function mapTfColDel(h, ci) { const t = mapTfObj(h); if (t) { t.columns.splice(ci, 1); mapTfStruct(h); } }
function mapTfAgg(h, ci, k, v) { const t = mapTfObj(h); if (t && t.aggregates[ci]) { t.aggregates[ci][k] = v; mapTfTouch(h); } }
function mapTfAggAdd(h) { const t = mapTfObj(h); if (t) { (t.aggregates = t.aggregates || []).push({ name: '', expr: '' }); mapTfStruct(h); } }
function mapTfAggDel(h, ci) { const t = mapTfObj(h); if (t) { t.aggregates.splice(ci, 1); mapTfStruct(h); } }
function mapTfGroup(h, gi, k, v) { const t = mapTfObj(h); if (t && (t.groups || [])[gi]) { t.groups[gi][k] = v; mapTfTouch(h); } }
function mapTfGroupAdd(h) { const t = mapTfObj(h); if (t) { (t.groups = t.groups || []).push({ name: '', condition: '' }); mapTfStruct(h); } }
function mapTfGroupDel(h, gi) { const t = mapTfObj(h); if (t) { t.groups.splice(gi, 1); mapTfStruct(h); } }
function mapTfSort(h, v) {
  const t = mapTfObj(h); if (!t) return;
  t.sort = v.split(',').map(s => s.trim()).filter(Boolean).map(s => {
    const desc = /\s+desc$/i.test(s); return { col: s.replace(/\s+(asc|desc)$/i, ''), dir: desc ? 'DESC' : 'ASC' };
  });
  mapTfTouch(h);
}

/* ---------------- SQL generation ---------------- */
function mapQual(t) { return (t.schema ? t.schema + '.' : '') + t.name; }   // schema.table for the FROM/JOIN
function mapTableByName(n) { return mapState.tables.find(t => t.name.toLowerCase() === String(n).toLowerCase()); }

/** routerCondOverride: undefined = use the router's active group (preview); null = omit the router
 *  condition entirely; string = use exactly this condition (multi-target per-group generation). */
function mapGenSql(routerCondOverride) {
  const st = mapState;
  if (!st.tables.length) return '';
  let from = 'FROM ' + mapQual(st.tables[0]);
  const joined = new Set([st.tables[0].name.toLowerCase()]);
  // Normalise wired links into equality conditions. Multiple links between the SAME pair of tables
  // collapse into ONE join whose conditions are AND-ed (a composite key) — like an Informatica Joiner,
  // NOT a separate JOIN per link.
  const conds = (st.joins || []).map(j => {
    const left = (j.left || ((j.leftTable || '') + '.' + (j.leftCol || ''))).trim();
    const right = (j.right || ((j.rightTable || '') + '.' + (j.rightCol || ''))).trim();
    return { left, right, lt: left.split('.')[0].toLowerCase(), rt: right.split('.')[0].toLowerCase(), type: (j.type || 'INNER').toUpperCase() };
  }).filter(c => c.left.includes('.') && c.right.includes('.'));

  const remaining = conds.slice();
  let added = true;
  while (added) {
    added = false;
    const groups = {};   // newTable -> { conds:[], type }
    remaining.forEach(c => {
      const lJ = joined.has(c.lt), rJ = joined.has(c.rt);
      const nt = (lJ && !rJ) ? c.rt : (rJ && !lJ) ? c.lt : null;
      if (nt) (groups[nt] = groups[nt] || { conds: [], type: c.type }).conds.push(c);
    });
    const keys = Object.keys(groups);
    if (keys.length) {
      const nt = keys[0], g = groups[nt], t = mapTableByName(nt);
      const on = g.conds.map(c => `${c.left} = ${c.right}`).join(' AND ');
      from += `\n${g.type} JOIN ${t ? mapQual(t) : nt} ON ${on}`;
      joined.add(nt);
      for (let i = remaining.length - 1; i >= 0; i--) if (joined.has(remaining[i].lt) && joined.has(remaining[i].rt)) remaining.splice(i, 1);
      added = true;
    }
  }
  st.tables.slice(1).forEach(t => { if (!joined.has(t.name.toLowerCase())) from += `\nCROSS JOIN ${mapQual(t)}`; });

  // LOOKUP → LEFT JOIN reference tables (each gets a stable alias for its match condition / returns)
  const lookupReturns = [];
  st.transforms.forEach((t, ti) => {
    if (t.type !== 'LOOKUP' || !(t.table || '').trim() || !(t.on || '').trim()) return;
    const alias = (t.alias || '').trim() || ('lk' + (ti + 1));
    from += `\nLEFT JOIN ${(t.schema ? t.schema.trim() + '.' : '')}${t.table.trim()} ${alias} ON ${t.on.trim()}`;
    (t.returns || '').split(',').map(s => s.trim()).filter(Boolean).forEach(r => lookupReturns.push(r));
  });

  const filters = st.transforms.filter(t => t.type === 'FILTER' && t.condition && t.condition.trim()).map(t => '(' + t.condition.trim() + ')');
  // ROUTER: preview uses the ACTIVE group's condition; multi-target generation overrides per group
  if (routerCondOverride !== undefined) {
    if (routerCondOverride) filters.push('(' + routerCondOverride + ')');
  } else {
    st.transforms.forEach(t => {
      if (t.type !== 'ROUTER' || !(t.groups || []).length) return;
      const g = t.groups[Math.min(parseInt(t.active || 0) || 0, t.groups.length - 1)] || t.groups[0];
      if (g && (g.condition || '').trim()) filters.push('(' + g.condition.trim() + ')');
    });
  }
  const agg = st.transforms.find(t => t.type === 'AGGREGATOR');
  const pivot = st.transforms.find(t => t.type === 'PIVOT' && (t.category || '').trim()
      && (t.value || '').trim() && (t.values || '').trim());
  const exprs = st.transforms.filter(t => t.type === 'EXPRESSION');
  const distinct = st.transforms.some(t => t.type === 'DISTINCT');

  let selectList, groupByCols = [];
  if (pivot) {
    // rows → columns via conditional aggregation, the portable pivot
    groupByCols = (pivot.groupBy || '').split(',').map(s => s.trim()).filter(Boolean);
    const aggFn = (pivot.agg || 'SUM').toUpperCase();
    const cells = pivot.values.split(',').map(s => s.trim()).filter(Boolean).map(v => {
      let alias = v.replace(/[^A-Za-z0-9_]/g, '_').toLowerCase();
      if (/^\d/.test(alias)) alias = 'p_' + alias;
      return `${aggFn}(CASE WHEN ${pivot.category.trim()} = '${v.replace(/'/g, "''")}' THEN ${pivot.value.trim()} END) AS ${alias}`;
    });
    selectList = groupByCols.concat(cells).join(', ');
  } else if (agg) {
    groupByCols = (agg.groupBy || []).filter(Boolean);
    const aggs = (agg.aggregates || []).filter(a => a.expr && a.name).map(a => `${a.expr} AS ${a.name}`);
    selectList = groupByCols.concat(aggs).join(', ') || '*';
  } else if (exprs.length) {
    // Expression transform: each row is "out_col = expr". A missing out_col name gets an auto alias so
    // a lone expression like MAX(balance) still projects (instead of silently falling back to SELECT *).
    const cols = []; let auto = 0;
    exprs.forEach(t => (t.columns || []).forEach(c => {
      if (!(c.expr || '').trim()) return;
      // Output name must be a bare identifier. A qualified value pasted from a port ("accounts.account_id")
      // → take the last segment; strip anything non-identifier; blank → auto alias. Never emit "AS a.b".
      let name = (c.name || '').trim();
      if (name.includes('.')) name = name.split('.').pop();
      name = name.replace(/[^A-Za-z0-9_]/g, '_').replace(/^_+|_+$/g, '');
      if (!name) name = 'expr_' + (++auto);
      if (/^\d/.test(name)) name = 'c_' + name;
      cols.push(`${c.expr.trim()} AS ${name}`);
    }));
    selectList = cols.length ? cols.join(', ') : '*';
  } else if (st.colmap && st.colmap.length) {
    // source→target field mapping: each selected source expression feeds the matching target column
    selectList = st.colmap.map(m => `${m.source} AS ${m.target}`).join(', ');
  } else {
    const proj = (typeof mcProjection === 'function') ? mcProjection() : null;   // honour Designer output-port selection
    selectList = (proj && proj.length) ? proj.join(', ') : '*';
  }
  if (lookupReturns.length && selectList !== '*') selectList += ', ' + lookupReturns.join(', ');
  const seq = st.transforms.find(t => t.type === 'SEQUENCE');
  if (seq) selectList += `, ROW_NUMBER() OVER (ORDER BY ${(seq.orderBy || '').trim() || '1'}) AS ${(seq.name || 'seq_no').trim()}`;

  let sql = 'SELECT ' + (distinct ? 'DISTINCT ' : '') + selectList + '\n' + from;
  if (filters.length) sql += '\nWHERE ' + filters.join(' AND ');
  if (groupByCols.length) sql += '\nGROUP BY ' + groupByCols.join(', ');

  // UNION: append rows from other tables (columns must line up with the main SELECT)
  st.transforms.forEach(t => {
    if (t.type !== 'UNION' || !(t.table || '').trim()) return;
    sql += `\nUNION ${t.all === false ? '' : 'ALL '}SELECT ${(t.columns || '').trim() || '*'} FROM ${(t.schema ? t.schema.trim() + '.' : '')}${t.table.trim()}`;
    if ((t.condition || '').trim()) sql += ` WHERE ${t.condition.trim()}`;
  });

  // RANK / Top-N: wrap with a ROW_NUMBER() window, filter when Top-N is set
  const rank = st.transforms.find(t => t.type === 'RANK' && (t.orderBy || '').trim());
  if (rank) {
    const name = (rank.name || 'rank_in_group').trim();
    const part = (rank.partitionBy || '').trim();
    sql = `SELECT __r.* FROM (\nSELECT __s.*, ROW_NUMBER() OVER (${part ? 'PARTITION BY ' + part + ' ' : ''}ORDER BY ${rank.orderBy.trim()}) AS ${name}\nFROM (\n${sql}\n) __s\n) __r`;
    if (rank.topN) sql += `\nWHERE ${name} <= ${parseInt(rank.topN)}`;
  }

  const sorter = st.transforms.find(t => t.type === 'SORTER');
  if (sorter && (sorter.sort || []).filter(s => s.col).length) sql += '\nORDER BY ' + sorter.sort.filter(s => s.col).map(s => `${s.col} ${s.dir || 'ASC'}`).join(', ');
  const limit = st.transforms.find(t => t.type === 'LIMIT');
  if (limit && limit.rows) sql += '\nLIMIT ' + parseInt(limit.rows);
  return sql;
}
function mapRegen() {
  const sql = mapGenSql();
  if (!sql) return toast('Add a source table first', 'err');
  mapState.sql = sql;
  if ($('map-sql')) $('map-sql').value = sql;
}

/* ---------------- run / export ---------------- */
async function mapRun() {
  const status = $('map-status'), res = $('map-results');
  if (!mapState.tables.length) return toast('Add a source table first', 'err');
  const multi = mapMultiSource();
  status.textContent = 'Running…'; res.innerHTML = '<div class="loading">Running…</div>';
  try {
    if (mapState.target.mode === 'TABLE') {
      if (multi) { res.innerHTML = '<div class="empty">Cross-database load into a table isn\'t supported yet — use Preview / CSV / JSON, or keep all tables in one data source.</div>'; status.textContent = ''; return; }
      if (!mapState.target.dsId) return toast('Pick a target data source', 'err');

      // Multi-target routing: a Router whose groups carry target tables fans out in ONE transaction.
      const router = mapState.transforms.find(t => t.type === 'ROUTER' && (t.groups || []).some(g => (g.target || '').trim()));
      if (router) {
        const routed = router.groups.filter(g => (g.target || '').trim());
        const unrouted = router.groups.filter(g => !(g.target || '').trim());
        if (unrouted.length) return toast(`Router group "${unrouted[0].name || 'unnamed'}" has no target table — set one on every group (or remove the group)`, 'err');
        // DEFAULT group semantics: rows where every OTHER condition IS NOT TRUE (catches NULLs too, like Informatica)
        const conds = router.groups.map(g => (g.condition || '').trim()).filter(Boolean);
        const statements = routed.map(g => {
          let cond = (g.condition || '').trim();
          if (!cond) cond = conds.length ? conds.map(c => `(${c}) IS NOT TRUE`).join(' AND ') : '';
          const sql = mapGenSql(cond || null);
          const tgt = (g.targetSchema ? g.targetSchema.trim() + '.' : (mapState.target.schema ? mapState.target.schema + '.' : '')) + g.target.trim();
          return (mapState.target.create ? `CREATE TABLE ${tgt} AS\n` : `INSERT INTO ${tgt}\n`) + sql;
        });
        const r = await api.post('/api/mappings/load-multi', { dataSourceId: parseInt(mapState.target.dsId), statements });
        status.textContent = `${r.totalRows} row(s) across ${routed.length} target(s) · ${r.elapsedMs} ms`;
        res.innerHTML = `<p class="sub tight">Routed in one transaction:</p><ul class="map-route-list">` +
          r.results.map((x, ri) => `<li><b>${esc(routed[ri].target)}</b> ← ${x.rows} row(s)` +
            `${(routed[ri].condition || '').trim() ? ` <span class="muted">WHERE ${esc(routed[ri].condition.trim())}</span>` : ' <span class="pill dim">DEFAULT</span>'}</li>`).join('') + '</ul>';
        toast('Multi-target routing loaded', 'ok');
        return;
      }

      if (!mapState.target.table) return toast('Enter a target table', 'err');
      const sql = ($('map-sql')?.value || mapState.sql || '').trim();
      if (!sql) { mapRegen(); return toast('Generated the SQL — click Run again', 'info'); }
      const tgt = (mapState.target.schema ? mapState.target.schema + '.' : '') + mapState.target.table;
      const colList = (mapState.colmap && mapState.colmap.length && !mapState.target.create) ? ` (${mapState.colmap.map(m => m.target).join(', ')})` : '';
      const loadSql = (mapState.target.create ? `CREATE TABLE ${tgt} AS\n` : `INSERT INTO ${tgt}${colList}\n`) + sql;
      const r = await api.post('/api/mappings/load', { dataSourceId: parseInt(mapState.target.dsId), sql: loadSql });
      status.textContent = `${r.rows} row(s) loaded · ${r.elapsedMs} ms`;
      res.innerHTML = `<p class="sub tight">Loaded into <b>${esc(tgt)}</b>.</p>`;
      toast('Mapping loaded', 'ok');
      return;
    }
    let r;
    if (multi) {
      if (mapState.transforms.some(t => t.type !== 'LIMIT'))
        toast('Cross-database mode joins and previews only — transformations apply in single-source (SQL) mode', 'info');
      r = await api.post('/api/mappings/federated', {
        tables: mapState.tables.map(t => ({ dsId: parseInt(t.dsId), schema: t.schema || null, name: t.name })),
        joins: mapState.joins.map(j => ({ type: j.type, left: (j.left || (j.leftTable + '.' + j.leftCol)), right: (j.right || (j.rightTable + '.' + j.rightCol)) })),
        limit: (mapState.transforms.find(t => t.type === 'LIMIT') || {}).rows || null
      });
    } else {
      const sql = ($('map-sql')?.value || mapState.sql || '').trim();
      if (!sql) { mapRegen(); return toast('Generated the SQL — click Preview again', 'info'); }
      const dsId = mapState.srcDsId || (mapState.tables[0] && mapState.tables[0].dsId);
      r = await api.post('/api/mappings/preview', { dataSourceId: parseInt(dsId), sql });
    }
    mapLastResult = r;
    status.textContent = `${r.rowCount} row(s)${r.truncated ? ' · capped 1000' : ''}${r.elapsedMs != null ? ' · ' + r.elapsedMs + ' ms' : ''}`;
    if (mapState.target.mode === 'CSV') mapExport('csv');
    else if (mapState.target.mode === 'JSON') mapExport('json');
    res.innerHTML = mapResultTable(r);
  } catch (e) { res.innerHTML = `<div class="error">${esc(e.message)}</div>`; status.textContent = ''; toast(e.message, 'err'); }
}

let mapLastResult = null;
function mapResultTable(r) {
  const cols = r.columns || [], rows = r.rows || [];
  if (!cols.length) return '<div class="empty">No columns.</div>';
  return `<div class="q-scroll" style="margin-top:10px"><table class="q-table"><thead><tr><th class="q-rownum">#</th>${cols.map(c => `<th>${esc(c)}</th>`).join('')}</tr></thead><tbody>` +
    rows.map((row, i) => `<tr><td class="q-rownum">${i + 1}</td>${row.map(v => `<td>${v === null ? '<span class="muted">NULL</span>' : esc(String(v))}</td>`).join('')}</tr>`).join('') + '</tbody></table></div>';
}
function mapExport(kind) {
  if (!mapLastResult) return;
  let blob, name;
  if (kind === 'json') {
    const objs = (mapLastResult.rows || []).map(r => Object.fromEntries((mapLastResult.columns || []).map((c, i) => [c, r[i]])));
    blob = new Blob([JSON.stringify(objs, null, 2)], { type: 'application/json' }); name = (mapState.name || 'mapping') + '.json';
  } else {
    const cell = s => `"${String(s === null || s === undefined ? '' : s).replace(/"/g, '""')}"`;
    const lines = [(mapLastResult.columns || []).map(cell).join(',')].concat((mapLastResult.rows || []).map(r => r.map(cell).join(',')));
    blob = new Blob([lines.join('\r\n')], { type: 'text/csv' }); name = (mapState.name || 'mapping') + '.csv';
  }
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = name; a.click(); URL.revokeObjectURL(a.href);
}

/* ---------------- persistence ---------------- */

/** Executable load statement(s) for the current design — single target, or router multi-target.
 *  Persisted into the spec on Save so WORKFLOWS can run this mapping server-side. Null when the
 *  design isn't a complete table load. */
function mapBuildLoadStatements() {
  const st = mapState;
  if (st.target.mode !== 'TABLE' || !st.target.dsId || !st.tables.length) return null;
  const router = st.transforms.find(t => t.type === 'ROUTER' && (t.groups || []).some(g => (g.target || '').trim()));
  if (router) {
    if (router.groups.some(g => !(g.target || '').trim())) return null;   // half-configured router
    const conds = router.groups.map(g => (g.condition || '').trim()).filter(Boolean);
    const statements = [], targets = [];
    router.groups.forEach(g => {
      let cond = (g.condition || '').trim();
      if (!cond) cond = conds.length ? conds.map(c => `(${c}) IS NOT TRUE`).join(' AND ') : '';
      const sql = mapGenSql(cond || null);
      const tgt = (g.targetSchema ? g.targetSchema.trim() + '.' : (st.target.schema ? st.target.schema + '.' : '')) + g.target.trim();
      statements.push((st.target.create ? `CREATE TABLE ${tgt} AS\n` : `INSERT INTO ${tgt}\n`) + sql);
      targets.push({ table: g.target.trim(), condition: (g.condition || '').trim() || null });
    });
    return { statements, targets };
  }
  if (!st.target.table) return null;
  const sql = (st.sql || '').trim() || mapGenSql();
  if (!sql) return null;
  const tgt = (st.target.schema ? st.target.schema + '.' : '') + st.target.table;
  const colList = (st.colmap && st.colmap.length && !st.target.create) ? ` (${st.colmap.map(m => m.target).join(', ')})` : '';
  return {
    statements: [(st.target.create ? `CREATE TABLE ${tgt} AS\n` : `INSERT INTO ${tgt}${colList}\n`) + sql],
    targets: [{ table: st.target.table, condition: null }]
  };
}

function mapSpec() {
  if (mapState.tab === 'designer' && typeof mcSyncToBuild === 'function') mcSyncToBuild();
  const load = mapBuildLoadStatements();
  return { srcDsId: mapState.srcDsId, srcSchema: mapState.srcSchema, tables: mapState.tables, joins: mapState.joins,
           transforms: mapState.transforms, target: mapState.target, sql: mapState.sql, canvas: mapState.canvas,
           loadStatements: load ? load.statements : undefined, loadTargets: load ? load.targets : undefined };
}
async function mapSave() {
  if (!mapState.name.trim()) return toast('Give the mapping a name', 'err');
  mapState.sql = $('map-sql')?.value || mapState.sql;
  try {
    const saved = await api.post('/api/mappings', { id: mapState.id, name: mapState.name.trim(), description: mapState.description, specJson: JSON.stringify(mapSpec()) });
    mapState.id = saved.id;
    await mapLoadList();
    toast('Mapping saved', 'ok'); mapRender();
  } catch (e) { toast(e.message, 'err'); }
}
async function mapOpen(id) {
  try {
    const m = await api.get('/api/mappings/' + id);
    const s = JSON.parse(m.specJson || '{}');
    mapState.id = m.id; mapState.name = m.name; mapState.description = m.description || '';
    mapState.srcDsId = s.srcDsId || ''; mapState.srcSchema = s.srcSchema || '';
    mapState.tables = s.tables || []; mapState.joins = s.joins || []; mapState.transforms = s.transforms || [];
    mapState.target = s.target || { mode: 'PREVIEW', dsId: '', schema: '', table: '', create: false };
    mapState.sql = s.sql || '';
    mapState.canvas = s.canvas && s.canvas.nodes ? s.canvas : { nodes: {}, links: [], zoom: 1, view: 'normal' };
    if (!mapState.canvas.zoom) mapState.canvas.zoom = 1;
    mapRender();
  } catch (e) { toast(e.message, 'err'); }
}
function mapNew() {
  Object.assign(mapState, { id: null, name: '', description: '', srcDsId: '', srcSchema: '', tables: [], joins: [], transforms: [], target: { mode: 'PREVIEW', dsId: '', schema: '', table: '', create: false }, colmap: null, sql: '', canvas: { nodes: {}, links: [], zoom: 1, view: 'normal' } });
  mapRender();
}
async function mapDelete() {
  if (!mapState.id || !confirm('Delete this mapping?')) return;
  try { await api.del('/api/mappings/' + mapState.id); mapNew(); await mapLoadList(); mapRender(); toast('Deleted', 'ok'); }
  catch (e) { toast(e.message, 'err'); }
}

/* ================= column lineage ================= */

/** Output-column lineage from the current design. Source matching is heuristic (column-name based);
    qualified table.col references in expressions match exactly. */
function mapComputeLineage() {
  const st = mapState;
  const sourceCols = [];
  st.tables.forEach(t => (t.cols || []).forEach(c => sourceCols.push({ table: t.name, col: c })));
  const findRefs = expr => {
    const e = String(expr || '');
    const refs = [];
    sourceCols.forEach(s => {
      const qualified = new RegExp('\\b' + s.table + '\\.' + s.col + '\\b', 'i');
      const bare = new RegExp('(^|[^.\\w])' + s.col + '\\b', 'i');
      if (qualified.test(e) || bare.test(e)) refs.push(s.table + '.' + s.col);
    });
    return [...new Set(refs)];
  };
  const rows = [];
  const pivot = st.transforms.find(t => t.type === 'PIVOT' && (t.category || '').trim() && (t.value || '').trim() && (t.values || '').trim());
  const agg = st.transforms.find(t => t.type === 'AGGREGATOR');
  const exprs = st.transforms.filter(t => t.type === 'EXPRESSION');
  if (pivot) {
    (pivot.groupBy || '').split(',').map(s => s.trim()).filter(Boolean)
      .forEach(g => rows.push({ out: g, expr: g, refs: findRefs(g), via: 'Pivot (group by)' }));
    pivot.values.split(',').map(s => s.trim()).filter(Boolean).forEach(v => {
      let alias = v.replace(/[^A-Za-z0-9_]/g, '_').toLowerCase();
      if (/^\d/.test(alias)) alias = 'p_' + alias;
      rows.push({ out: alias, expr: (pivot.agg || 'SUM') + '(CASE WHEN ' + pivot.category + '=\'' + v + '\' THEN ' + pivot.value + ' END)',
                  refs: [...new Set(findRefs(pivot.category).concat(findRefs(pivot.value)))], via: 'Pivot' });
    });
  } else if (agg) {
    (agg.groupBy || []).filter(Boolean).forEach(g => rows.push({ out: g, expr: g, refs: findRefs(g), via: 'Aggregator (group by)' }));
    (agg.aggregates || []).filter(a => a.expr && a.name)
      .forEach(a => rows.push({ out: a.name, expr: a.expr, refs: findRefs(a.expr), via: 'Aggregator' }));
  } else if (exprs.length) {
    exprs.forEach(t => (t.columns || []).forEach(c => {
      if (c.name && c.expr) rows.push({ out: c.name, expr: c.expr, refs: findRefs(c.expr), via: 'Expression' });
    }));
  } else if (st.colmap && st.colmap.length) {
    st.colmap.forEach(m => rows.push({ out: m.target, expr: m.source, refs: findRefs(m.source), via: 'Field map' }));
  } else {
    const proj = (typeof mcProjection === 'function') ? mcProjection() : null;
    (proj && proj.length ? proj : sourceCols.map(s => s.table + '.' + s.col))
      .forEach(p => rows.push({ out: String(p).split('.').pop(), expr: p, refs: findRefs(p), via: 'Pass-through' }));
  }
  st.transforms.forEach((t, ti) => {
    if (t.type === 'LOOKUP' && (t.returns || '').trim()) {
      const alias = (t.alias || '').trim() || ('lk' + (ti + 1));
      t.returns.split(',').map(s => s.trim()).filter(Boolean).forEach(r => {
        const out = (r.match(/\s+AS\s+(\w+)/i) || [])[1] || r.split('.').pop();
        rows.push({ out, expr: r, refs: [(t.table || '?') + ' (lookup ' + alias + ')'], via: 'Lookup' });
      });
    }
    if (t.type === 'SEQUENCE') rows.push({ out: (t.name || 'seq_no'), expr: 'ROW_NUMBER()', refs: [], via: 'Sequence (generated)' });
    if (t.type === 'RANK' && (t.orderBy || '').trim())
      rows.push({ out: (t.name || 'rank_in_group'), expr: 'ROW_NUMBER() OVER (…)',
                  refs: findRefs(t.orderBy + ' ' + (t.partitionBy || '')), via: 'Rank (generated)' });
  });
  const rowLevel = st.transforms.filter(t => ['FILTER', 'ROUTER', 'SORTER', 'DISTINCT', 'LIMIT', 'UNION'].includes(t.type))
    .map(t => (MAP_TRANSFORMS.find(x => x.type === t.type) || {}).label || t.type);
  const load = mapBuildLoadStatements();
  const targets = load ? load.targets.map(t => t.table) : (mapState.target.table ? [mapState.target.table] : []);
  return { rows, rowLevel, targets };
}

let mapLastLineage = null;
function mapRenderLineage() {
  const el = $('map-lineage');
  if (!el) return;
  if (!mapState.tables.length) { el.innerHTML = '<div class="empty">Add source tables first.</div>'; return; }
  const l = mapComputeLineage();
  mapLastLineage = l;
  const tgt = l.targets.length ? l.targets.join(', ') : '(preview only)';
  el.innerHTML = '<p class="sub tight">Row-level steps applied to all columns: '
    + (l.rowLevel.length ? l.rowLevel.map(esc).join(' → ') : 'none')
    + ' &nbsp;·&nbsp; Target: <b>' + esc(tgt) + '</b></p>'
    + '<table><tr><th>Output column</th><th>Expression</th><th>Sourced from</th><th>Via</th></tr>'
    + l.rows.map(r => '<tr><td><b>' + esc(r.out) + '</b></td><td class="mono">' + esc(r.expr) + '</td>'
      + '<td>' + (r.refs.length ? r.refs.map(x => '<code>' + esc(x) + '</code>').join(' ') : '<span class="muted">generated / literal</span>') + '</td>'
      + '<td>' + esc(r.via) + '</td></tr>').join('') + '</table>';
}

function mapLineageCsv() {
  if (!mapLastLineage) mapRenderLineage();
  const l = mapLastLineage;
  if (!l || !l.rows.length) return toast('Nothing to export — compute lineage first', 'err');
  const cell = s => '"' + String(s == null ? '' : s).replace(/"/g, '""') + '"';
  const lines = ['output_column,expression,sourced_from,via,target'].concat(
    l.rows.map(r => [r.out, r.expr, r.refs.join(' | '), r.via, l.targets.join(' | ')].map(cell).join(',')));
  const blob = new Blob([lines.join('\r\n')], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = (mapState.name || 'mapping') + '-lineage.csv';
  a.click();
  URL.revokeObjectURL(a.href);
}

/* ================= workflows ================= */
let wfState = { id: null, steps: [] };
let wfList = [], wfMappings = [], wfPollTimer = null;

async function wfLoadList() {
  const el = $('wf-list');
  if (!el) return;
  try {
    [wfList, wfMappings] = await Promise.all([api.get('/api/mappings/workflows'), api.get('/api/mappings')]);
    if ($('wf-count')) $('wf-count').textContent = wfList.length + ' workflow(s)';
    el.innerHTML = wfList.length
      ? '<table><tr><th>Workflow</th><th>Steps</th><th>Last run</th><th></th></tr>' + wfList.map(w => {
          let run = null;
          try { run = JSON.parse(w.lastRunJson || 'null'); } catch (e) { }
          const last = run ? statusPill(run.status) + ' <span class="mono" style="font-size:11px">' + esc(run.finishedAt || run.startedAt || '') + '</span>' : '<span class="muted">never</span>';
          let steps = 0;
          try { steps = JSON.parse(w.stepsJson || '[]').length; } catch (e) { }
          return '<tr><td><b>' + esc(w.name) + '</b>' + (w.description ? '<div class="muted" style="font-size:11.5px">' + esc(w.description) + '</div>' : '') + '</td>'
            + '<td>' + steps + '</td><td>' + last + '</td>'
            + '<td class="row-actions"><button class="small" onclick="wfRun(' + w.id + ')">Run</button> '
            + '<button class="small ghost" onclick="wfEdit(' + w.id + ')">Edit</button> '
            + '<button class="small ghost danger" onclick="wfDelete(' + w.id + ',' + js(w.name) + ')">Delete</button></td></tr>';
        }).join('') + '</table>'
      : '<div class="empty">No workflows yet — add steps above and save.</div>';
  } catch (e) { el.innerHTML = '<div class="empty">' + esc(e.message) + '</div>'; }
}

function wfRenderSteps() {
  const el = $('wf-steps');
  if (!el) return;
  el.innerHTML = wfState.steps.length ? wfState.steps.map((s, i) =>
    '<div class="map-tf"><div class="map-tf-head"><span class="n">' + (i + 1) + '</span><b>' + (s.type === 'SQL' ? 'SQL step' : 'Mapping step') + '</b>'
    + '<span class="grow"></span>'
    + '<select onchange="wfStep(' + i + ',\'onError\',this.value)" title="On failure">'
    + '<option value="STOP"' + (s.onError !== 'CONTINUE' ? ' selected' : '') + '>Stop on error</option>'
    + '<option value="CONTINUE"' + (s.onError === 'CONTINUE' ? ' selected' : '') + '>Continue on error</option></select>'
    + '<button class="iconbtn" onclick="wfMove(' + i + ',-1)">↑</button><button class="iconbtn" onclick="wfMove(' + i + ',1)">↓</button>'
    + '<button class="iconbtn danger" onclick="wfDelStep(' + i + ')">&times;</button></div>'
    + '<div class="map-tf-body">' + (s.type === 'SQL'
      ? '<div class="map-exprow"><select onchange="wfStep(' + i + ',\'dataSourceId\',this.value)"><option value="">Data source…</option>'
        + (dataSources || []).map(d => '<option value="' + d.id + '"' + (String(s.dataSourceId) === String(d.id) ? ' selected' : '') + '>' + esc(d.name) + '</option>').join('') + '</select>'
        + '<input value="' + esc(s.label || '') + '" oninput="wfStep(' + i + ',\'label\',this.value)" placeholder="label (optional)" style="max-width:200px"></div>'
        + '<textarea rows="2" style="width:100%;font-family:monospace" oninput="wfStep(' + i + ',\'sql\',this.value)" placeholder="INSERT INTO t SELECT … / CREATE TABLE t AS SELECT …">' + esc(s.sql || '') + '</textarea>'
      : '<div class="map-exprow"><select onchange="wfStep(' + i + ',\'mappingId\',this.value)"><option value="">Saved mapping…</option>'
        + wfMappings.map(m => '<option value="' + m.id + '"' + (String(s.mappingId) === String(m.id) ? ' selected' : '') + '>' + esc(m.name) + '</option>').join('') + '</select>'
        + '<input value="' + esc(s.label || '') + '" oninput="wfStep(' + i + ',\'label\',this.value)" placeholder="label (optional)" style="max-width:200px"></div>'
        + '<span class="sub">Runs the mapping\'s saved load statements — Save it in the Designer with Output = Table first.</span>')
    + '</div></div>').join('')
    : '<div class="empty">No steps yet — add mapping or SQL steps below. They run top to bottom.</div>';
}

function wfStep(i, k, v) { if (wfState.steps[i]) wfState.steps[i][k] = v; }
function wfAddStep(type) { wfState.steps.push({ type, onError: 'STOP' }); wfRenderSteps(); }
function wfDelStep(i) { wfState.steps.splice(i, 1); wfRenderSteps(); }
function wfMove(i, d) { const a = wfState.steps, j = i + d; if (j < 0 || j >= a.length) return; [a[i], a[j]] = [a[j], a[i]]; wfRenderSteps(); }
function wfNew() { wfState = { id: null, steps: [] }; $('wf-name').value = ''; $('wf-desc').value = ''; $('wf-run').innerHTML = ''; wfRenderSteps(); }

function wfEdit(id) {
  const w = wfList.find(x => x.id === id);
  if (!w) return;
  let steps = [];
  try { steps = JSON.parse(w.stepsJson || '[]'); } catch (e) { }
  wfState = { id: w.id, steps };
  $('wf-name').value = w.name;
  $('wf-desc').value = w.description || '';
  wfRenderSteps();
  wfShowRun(w);
  $('wf-name').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

async function wfSave() {
  try {
    const saved = await api.post('/api/mappings/workflows', {
      id: wfState.id, name: $('wf-name').value.trim(), description: $('wf-desc').value.trim() || null,
      stepsJson: JSON.stringify(wfState.steps)
    });
    wfState.id = saved.id;
    toast('Workflow "' + saved.name + '" saved', 'ok');
    await wfLoadList();
  } catch (e) { toast(e.message, 'err'); }
}

async function wfDelete(id, name) {
  if (!await uiConfirm('Delete workflow "' + name + '"?', { danger: true, okText: 'Delete' })) return;
  try { await api.del('/api/mappings/workflows/' + id); if (wfState.id === id) wfNew(); await wfLoadList(); toast('Deleted', 'ok'); }
  catch (e) { toast(e.message, 'err'); }
}

async function wfRun(id) {
  try {
    await api.post('/api/mappings/workflows/' + id + '/run', {});
    toast('Workflow started', 'ok');
    wfPoll(id);
  } catch (e) { toast(e.message, 'err'); }
}

function wfPoll(id) {
  clearTimeout(wfPollTimer);
  const tick = async () => {
    try {
      const w = await api.get('/api/mappings/workflows/' + id);
      wfShowRun(w);
      let run = null;
      try { run = JSON.parse(w.lastRunJson || 'null'); } catch (e) { }
      if (run && run.status === 'RUNNING') wfPollTimer = setTimeout(tick, 1500);
      else await wfLoadList();
    } catch (e) { /* stop polling on error */ }
  };
  tick();
}

function wfShowRun(w) {
  const el = $('wf-run');
  if (!el) return;
  let run = null;
  try { run = JSON.parse(w.lastRunJson || 'null'); } catch (e) { }
  if (!run) { el.innerHTML = ''; return; }
  el.innerHTML = '<div class="diff-panel"><div class="diff-head"><b>' + esc(w.name) + ' — ' + esc(run.status) + '</b>'
    + '<span class="mono" style="font-size:11px">' + esc(run.startedAt || '') + (run.finishedAt ? ' → ' + esc(run.finishedAt) : '') + '</span></div>'
    + '<table><tr><th>#</th><th>Step</th><th>Status</th><th>Rows</th><th>Time</th><th>Error</th></tr>'
    + (run.steps || []).map((s, i) => '<tr><td>' + (i + 1) + '</td><td>' + esc(s.label || '') + '</td><td>' + statusPill(s.status) + '</td>'
      + '<td>' + (s.rows != null ? Number(s.rows).toLocaleString() : '') + '</td>'
      + '<td class="mono">' + (s.elapsedMs != null ? s.elapsedMs + ' ms' : '') + '</td>'
      + '<td class="mono" style="font-size:11px">' + esc(s.error || '') + '</td></tr>').join('') + '</table></div>';
}
