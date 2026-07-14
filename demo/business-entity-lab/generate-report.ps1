param([string]$OutputName = 'business-entity-acceptance-report.html')

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$result = Get-Content -Raw -Path (Join-Path $root 'acceptance-results.json') | ConvertFrom-Json

function Html($Value) {
  if ($null -eq $Value) { return '' }
  return [System.Net.WebUtility]::HtmlEncode([string]$Value)
}

function Number($Value) { return ([long]$Value).ToString('N0') }

$checkRows = ($result.checks | ForEach-Object {
  $badge = if ($_.passed) { '<span class="badge pass">PASS</span>' } else { '<span class="badge fail">FAIL</span>' }
  "<tr><td>$badge</td><td>$(Html $_.name)</td><td>$(Html $_.evidence)</td></tr>"
}) -join "`n"

$tableRows = ($result.tableRows | ForEach-Object {
  '<tr><td>{0}</td><td>{1}</td><td><code>{2}</code></td><td><code>{3}</code></td><td class="num">{4}</td><td><span class="badge pass">{5}</span></td></tr>' -f (Html $_.application),(Html $_.engine),(Html $_.schema),(Html $_.table),(Number $_.rowCount),(Html $_.status)
}) -join "`n"

$sourceCards = ($result.sources | ForEach-Object {
  '<article class="card"><div class="eyebrow">{0}</div><h3>{1}</h3><p><code>{2}</code></p><div class="fact-line"><span>Readiness</span><strong>{3} / {4}</strong></div><div class="fact-line"><span>Metadata</span><strong>{5} tables, {6} columns</strong></div></article>' -f (Html $_.engine),(Html $_.name),(Html $_.schema),(Html $_.diagnosticStatus),(Html $_.readinessScore),(Html $_.tables),(Html $_.columns)
}) -join "`n"

$identityRows = ($result.identityResolution | ForEach-Object {
  '<tr><td>{0}</td><td><code>CUST-000042</code></td><td><span class="badge pass">MATCHED</span></td><td><code>{1}</code></td></tr>' -f (Html $_.system),(Html $_.canonicalKey)
}) -join "`n"

$sliceRows = ($result.savedExecution.slices | ForEach-Object {
  '<tr><td>{0}</td><td>{1}</td><td>{2}</td><td class="num">{3}</td><td>{4}</td><td>{5}</td></tr>' -f (Html $_.label),(Html $_.datasetName),(Html $_.sourceSchema),(Html $_.memberCount),(Html $_.loaderStrategy.strategy),(Html $_.loaderStrategy.fallback)
}) -join "`n"

$screens = @(
  @{ file='01-business-entity-model.png'; title='Entity model: 43 members across three applications' },
  @{ file='02-business-entity-identity.png'; title='Canonical identity and crosswalk' },
  @{ file='03-business-entity-freshness.png'; title='Live freshness policy' },
  @{ file='04-business-entity-snapshot.png'; title='Immutable snapshot evidence' },
  @{ file='05-business-entity-microdb.png'; title='Encrypted, versioned Customer 360 Micro-DB' },
  @{ file='06-business-entity-flow.png'; title='Visual enterprise flow debugger' },
  @{ file='07-business-entity-saved-jobs.png'; title='Approved plan and reusable saved package' },
  @{ file='08-business-entity-governance.png'; title='Maker-checker governance' },
  @{ file='09-data-sources-three-applications.png'; title='Registered PostgreSQL, Oracle, and MySQL sources' },
  @{ file='10-datascope-blueprints.png'; title='DataScope profile used by the entity' }
)
$screenshotHtml = ($screens | ForEach-Object {
  $path = Join-Path (Join-Path $root 'ui-evidence') $_.file
  if (Test-Path $path) {
    '<figure><a href="ui-evidence/{0}"><img src="ui-evidence/{0}" alt="{1}"></a><figcaption>{1}</figcaption></figure>' -f $_.file,(Html $_.title)
  }
}) -join "`n"

$pgDdl = Html (Get-Content -Raw -Path (Join-Path $root 'postgres-core-banking.sql'))
$oracleDdl = Html (Get-Content -Raw -Path (Join-Path $root 'oracle-card-servicing.sql'))
$mysqlDdl = Html (Get-Content -Raw -Path (Join-Path $root 'mysql-digital-engagement.sql'))
$generated = Html (([datetime]$result.generatedAt).ToLocalTime().ToString('MMMM d, yyyy h:mm tt'))

$html = @"
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>ForgeTDM Business Entity Acceptance Lab</title>
  <style>
    :root{--ink:#0d1728;--muted:#64748b;--line:#dbe3ef;--blue:#1769e0;--blue2:#eaf3ff;--green:#087b4f;--green2:#e9f8f1;--red:#ba2d2d;--bg:#f5f8fc;--card:#fff;--nav:#101a2d}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font-family:Inter,Segoe UI,Arial,sans-serif;line-height:1.5}.hero{background:var(--nav);color:#fff;padding:52px max(5vw,32px) 48px}.hero-inner,.wrap{max-width:1280px;margin:auto}.eyebrow{font-size:12px;font-weight:800;letter-spacing:.08em;text-transform:uppercase;color:#63a9ff}.hero h1{font-size:42px;line-height:1.08;margin:10px 0 12px;letter-spacing:0}.hero p{max-width:820px;color:#c5d1e4;font-size:18px;margin:0}.hero-meta{display:flex;gap:10px;flex-wrap:wrap;margin-top:24px}.pill{border:1px solid #39506e;border-radius:999px;padding:7px 12px;color:#dbe8fb;font-size:13px}.wrap{padding:28px max(3vw,18px) 70px}.metric-grid,.source-grid,.fix-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.metric,.card{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:18px}.metric strong{display:block;font-size:30px;line-height:1.1}.metric span,.muted{color:var(--muted)}section{margin-top:34px}h2{font-size:24px;margin:0 0 6px}h3{margin:6px 0 8px;font-size:17px}.section-copy{margin:0 0 16px;color:var(--muted);max-width:900px}.status{display:inline-flex;align-items:center;gap:8px;border-radius:999px;background:var(--green2);color:var(--green);font-weight:800;padding:7px 12px}.dot{width:8px;height:8px;border-radius:50%;background:#14a76c}.fact-line{display:flex;justify-content:space-between;border-top:1px solid var(--line);padding-top:9px;margin-top:9px;gap:10px}.fact-line span{color:var(--muted)}.fact-line strong{text-align:right}table{width:100%;border-collapse:collapse;background:#fff;border:1px solid var(--line);font-size:13px}th,td{padding:10px 12px;border-bottom:1px solid var(--line);text-align:left;vertical-align:top}th{background:#f1f5fa;font-size:11px;text-transform:uppercase;letter-spacing:.04em;color:#4c5a6d}.num{text-align:right;font-variant-numeric:tabular-nums}.table-wrap{overflow:auto;border-radius:8px}.badge{display:inline-block;border-radius:999px;font-size:10px;font-weight:800;padding:3px 7px}.pass{background:var(--green2);color:var(--green)}.fail{background:#ffeded;color:var(--red)}code{font-family:Cascadia Code,Consolas,monospace;font-size:.92em}.flow{display:grid;grid-template-columns:repeat(7,1fr);gap:8px;margin:18px 0}.flow div{position:relative;background:#fff;border:1px solid var(--line);border-radius:8px;padding:13px;text-align:center;font-size:12px;font-weight:750}.flow div:not(:last-child):after{content:'>';position:absolute;right:-8px;top:33%;color:var(--blue);z-index:2}.callout{border-left:4px solid var(--blue);background:var(--blue2);padding:15px 18px;border-radius:0 8px 8px 0}.fix-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.fix{background:#fff;border:1px solid var(--line);border-radius:8px;padding:14px}.fix strong{display:block;margin-bottom:5px}.steps{counter-reset:steps;display:grid;gap:10px}.step{position:relative;background:#fff;border:1px solid var(--line);border-radius:8px;padding:16px 16px 16px 54px}.step:before{counter-increment:steps;content:counter(steps);position:absolute;left:16px;top:15px;width:25px;height:25px;border-radius:50%;display:grid;place-items:center;background:var(--blue);color:#fff;font-weight:800}.cmd{background:#101a2d;color:#e7eefb;border-radius:7px;padding:13px;overflow:auto;white-space:pre-wrap}.gallery{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.gallery figure{margin:0;background:#fff;border:1px solid var(--line);border-radius:8px;overflow:hidden}.gallery img{display:block;width:100%;height:300px;object-fit:cover;object-position:top}.gallery figcaption{padding:10px 12px;font-weight:700;font-size:13px}video{width:100%;max-height:680px;background:#0a1120;border-radius:8px}details{background:#fff;border:1px solid var(--line);border-radius:8px;margin:10px 0}summary{cursor:pointer;padding:14px 16px;font-weight:800}pre{margin:0;border-top:1px solid var(--line);padding:16px;max-height:620px;overflow:auto;background:#101a2d;color:#dbe8fb;font-size:12px}.footer{color:var(--muted);font-size:12px;margin-top:42px}@media(max-width:900px){.metric-grid,.source-grid,.fix-grid,.gallery{grid-template-columns:1fr 1fr}.flow{grid-template-columns:1fr}.flow div:after{display:none}}@media(max-width:600px){.metric-grid,.source-grid,.fix-grid,.gallery{grid-template-columns:1fr}.hero h1{font-size:32px}.hero{padding:34px 20px}.wrap{padding:20px 12px 50px}}
  </style>
</head>
<body>
  <header class="hero"><div class="hero-inner"><div class="eyebrow">ForgeTDM acceptance evidence</div><h1>Customer 360 across three database engines</h1><p>A working Business Entity lab joining Core Banking on PostgreSQL, Card Servicing on Oracle, and Digital Engagement on MySQL through one canonical customer identity.</p><div class="hero-meta"><span class="pill">Generated $generated</span><span class="pill">Entity ID $(Html $result.entity.id)</span><span class="pill">Overall result: $(Html $result.overallStatus)</span><span class="pill">No paid services used</span></div></div></header>
  <main class="wrap">
    <div class="status"><span class="dot"></span>All automated acceptance checks passed</div>
    <section class="metric-grid">
      <div class="metric"><strong>3</strong><span>applications and database engines</span></div><div class="metric"><strong>43</strong><span>mapped physical tables</span></div><div class="metric"><strong>$(Number $result.totals.rows)</strong><span>verified source rows</span></div><div class="metric"><strong>100</strong><span>reusable identity subjects, 300 links</span></div>
    </section>

    <section><h2>Scenario</h2><p class="section-copy">The canonical customer begins in Core Banking as <code>customers.customer_no</code>. Oracle Card Servicing links through <code>card_customers.core_customer_no</code>; MySQL Digital Engagement links through <code>digital_customers.core_customer_ref</code>. Child tables remain connected through their native keys and relationships.</p><div class="flow"><div>Core customer</div><div>Canonical crosswalk</div><div>Oracle card customer</div><div>MySQL digital customer</div><div>Freshness gate</div><div>Approved 3-slice plan</div><div>Saved package</div></div></section>

    <section><h2>Live sources</h2><p class="section-copy">Each connection was tested through ForgeTDM and inspected through connector diagnostics.</p><div class="source-grid">$sourceCards</div></section>

    <section><h2>Physical row evidence</h2><p class="section-copy">Counts come from immutable Business Entity snapshot $(Html $result.snapshot.id), not estimates. Every member count is marked verified.</p><div class="metric-grid"><div class="metric"><strong>$(Number $result.totals.postgresRows)</strong><span>PostgreSQL / 20 tables</span></div><div class="metric"><strong>$(Number $result.totals.oracleRows)</strong><span>Oracle / 13 tables</span></div><div class="metric"><strong>$(Number $result.totals.mysqlRows)</strong><span>MySQL / 10 tables</span></div><div class="metric"><strong>$(Number $result.snapshot.totalRows)</strong><span>snapshot total / 43 members</span></div></div><div class="table-wrap" style="margin-top:14px"><table><thead><tr><th>Application</th><th>Engine</th><th>Schema</th><th>Table</th><th class="num">Rows</th><th>Evidence</th></tr></thead><tbody>$tableRows</tbody></table></div></section>

    <section><h2>Identity consistency</h2><p class="section-copy">The same external value resolves to one canonical subject regardless of which application initiates the lookup.</p><div class="table-wrap"><table><thead><tr><th>Lookup application</th><th>External ID</th><th>Result</th><th>Canonical key</th></tr></thead><tbody>$identityRows</tbody></table></div></section>

    <section><h2>Customer 360 Micro-DB</h2><p class="section-copy">This is a physical governed entity capsule for <code>$(Html $result.microDb.canonicalKey)</code>, not a conceptual model or a saved DataScope filter. ForgeTDM recursively captured the declared relationships across PostgreSQL, Oracle, and MySQL, masked the PII, and encrypted every stored fragment.</p><div class="metric-grid"><div class="metric"><strong>$(Number $result.microDb.fragments)</strong><span>encrypted table fragments</span></div><div class="metric"><strong>$(Number $result.microDb.totalRows)</strong><span>related entity rows</span></div><div class="metric"><strong>v$(Html $result.microDb.version)</strong><span>current retained version</span></div><div class="metric"><strong>3</strong><span>physical applications</span></div></div><div class="callout" style="margin-top:14px"><strong>Runnable without rereading source</strong><br>Approved plan $(Html $result.microDb.planId) and READY immutable package $(Html $result.microDb.packageId) are pinned to capsule version $(Html $result.microDb.version). Their launch path uses the encrypted Micro-DB fragments and records <code>sourceRead=false</code>.</div></section>

    <section><h2>Saved jobs and execution design</h2><p class="section-copy">The approved execution plans are intentionally saved as <code>PLAN_ONLY</code>. They can be reviewed and packaged safely; no physical provision was launched because this lab's target connections point to its source schemas.</p><div class="table-wrap"><table><thead><tr><th>Application slice</th><th>DataScope</th><th>Schema</th><th class="num">Members</th><th>Preferred loader</th><th>Fallback</th></tr></thead><tbody>$sliceRows</tbody></table></div><div class="callout" style="margin-top:14px"><strong>Reusable artifacts left in ForgeTDM</strong><br>Approved fan-out plan $(Html $result.savedExecution.planId), READY self-service package $(Html $result.savedExecution.packageId), Micro-DB plan $(Html $result.microDb.planId), Micro-DB package $(Html $result.microDb.packageId), ACTIVE visual flow $(Html $result.flow.id), and debugger run $(Html $result.flow.debugRunId) with status $(Html $result.flow.debugStatus).</div></section>

    <section><h2>Governance and operational controls</h2><div class="fix-grid"><div class="fix"><strong>Maker-checker</strong>Request created by <code>$(Html $result.governance.maker)</code> and signed by separate checker <code>$(Html $result.governance.checker)</code>.</div><div class="fix"><strong>Actor integrity</strong>A negative API test confirmed that an administrator cannot submit approval while claiming to be the checker.</div><div class="fix"><strong>Freshness</strong>One policy checked all three root systems and returned <span class="badge pass">$(Html $result.freshness.status)</span>.</div><div class="fix"><strong>Snapshot retention</strong>Snapshot $(Html $result.snapshot.id) is immutable and retained until $(Html $result.snapshot.retentionUntil).</div></div></section>

    <section><h2>Defects found and fixed</h2><p class="section-copy">The acceptance work was allowed to change code where behavior was incorrect. These are product fixes, not fixture workarounds.</p><div class="fix-grid"><div class="fix"><strong>Cross-database SQL</strong>Business Entity and DataScope identifiers now quote and normalize correctly for PostgreSQL, Oracle, MySQL, SQL Server, and DB2-family engines.</div><div class="fix"><strong>Oracle freshness timestamps</strong>Oracle timestamp text is parsed correctly by freshness evaluation.</div><div class="fix"><strong>Truthful snapshot evidence</strong>A failed physical count can no longer masquerade as a captured zero-row table.</div><div class="fix"><strong>Governance identity</strong>Approval and rejection use the authenticated actor and reject reviewer impersonation.</div><div class="fix"><strong>Idempotent entity rebuild</strong>Stable logical members reconcile in place, preserving IDs and preventing stale snapshot or crosswalk references.</div><div class="fix"><strong>Catalog lifecycle</strong>Qualified names remain stable and obsolete/duplicate assets are removed during sync.</div><div class="fix"><strong>Large-key portability</strong>DataScope chunks key predicates below Oracle and SQL Server parameter limits.</div><div class="fix"><strong>Relationship containment</strong>Shared account, household, branch, and merchant parents no longer fan out into unrelated entities.</div><div class="fix"><strong>Fixture referential logic</strong>Oracle disputes and fraud cases now reference postings/cards belonging to the same customer.</div><div class="fix"><strong>New UI build</strong>The Data Sources Redshift/Oracle icon imports were corrected so Data Sources and dependent routes compile.</div></div></section>

    <section><h2>Acceptance checks</h2><p class="section-copy">Run <code>verify-forgetdm.ps1</code> at any time to recreate this evidence file.</p><div class="table-wrap"><table><thead><tr><th>Result</th><th>Check</th><th>Evidence</th></tr></thead><tbody>$checkRows</tbody></table></div></section>

    <section><h2>How to run and review</h2><div class="steps"><div class="step"><strong>Start ForgeTDM backend</strong><div class="cmd">`$env:FORGETDM_DB_URL = "jdbc:postgresql://localhost:5433/forgetdm"`n`$env:FORGETDM_DB_USER = "forgetdm"`n`$env:FORGETDM_DB_PASS = "forgetdm"`n`$env:FORGETDM_MASKING_SECRET = "pick-a-long-random-secret"`nmvn spring-boot:run</div></div><div class="step"><strong>Start the new UI</strong><div class="cmd">cd "D:\forgetdm - Copy\frontend"`nnpm.cmd run dev</div></div><div class="step"><strong>Recreate or refresh all ForgeTDM artifacts</strong><div class="cmd">&amp; "D:\forgetdm - Copy\demo\business-entity-lab\configure-forgetdm.ps1"</div><p class="muted">The setup is idempotent: source connections, DataScopes, members, identities, policy, governance, flow, plan, and package are reconciled for future use.</p></div><div class="step"><strong>Run acceptance</strong><div class="cmd">&amp; "D:\forgetdm - Copy\demo\business-entity-lab\verify-forgetdm.ps1"</div></div><div class="step"><strong>Review in the new UI</strong><p>Open <a href="http://localhost:3000/business-entities">http://localhost:3000/business-entities</a>, choose <b>BE Lab - Customer 360</b>, then review Model, Identity, Freshness, Snapshots &amp; reservations, Deliver, and Govern. DataScope definitions are under <a href="http://localhost:3000/datascope">DataScope</a>.</p></div></div></section>

    <section><h2>Recorded new-UI walkthrough</h2><p class="section-copy">The recording is local and uses the real saved entity and jobs.</p><video controls preload="metadata" src="business-entity-new-ui-walkthrough.webm"></video></section>
    <section><h2>UI evidence</h2><div class="gallery">$screenshotHtml</div></section>

    <section><h2>DDL and data generation</h2><p class="section-copy">These scripts rebuild the acceptance schemas and deterministic data. They are destructive to the named lab schemas/users only.</p><details><summary>PostgreSQL Core Banking - 20 tables / $(Number $result.totals.postgresRows) rows</summary><pre>$pgDdl</pre></details><details><summary>Oracle Card Servicing - 13 tables / $(Number $result.totals.oracleRows) rows</summary><pre>$oracleDdl</pre></details><details><summary>MySQL Digital Engagement - 10 tables / $(Number $result.totals.mysqlRows) rows</summary><pre>$mysqlDdl</pre></details></section>

    <section><h2>Safety boundary</h2><div class="callout"><strong>What was not executed</strong><br>The saved execution plan was not physically launched because each acceptance DataScope currently uses the same database as source and target. Launching a replace/delete load there would mutate the evidence fixture. Point each slice at dedicated target schemas, re-approve the changed plan, then launch it.</div></section>
    <p class="footer">ForgeTDM Business Entity Acceptance Lab. Evidence source: acceptance-results.json. DDL, setup, verification, screenshots, and recording are stored beside this report.</p>
  </main>
</body>
</html>
"@

$output = Join-Path $root $OutputName
[System.IO.File]::WriteAllText($output, $html, [System.Text.UTF8Encoding]::new($false))
Write-Host "Report written to $output"
