import type { DataColumn, DataSource } from '@/lib/types';
import type {
  CatalogEntry,
  ForeignKeyEntry,
  GeneratorSpec,
  ProfileResponse,
  SyntheticColumn,
  SyntheticDraft,
  SyntheticJob,
  SyntheticPlan,
  SyntheticPlanSummary,
  SyntheticSavedJob,
  SyntheticTable,
  SyntheticTargetSystem
} from './types';

export const GENERATOR_FALLBACKS = [
  'SEQUENCE',
  'PADDED_SEQUENCE',
  'ALPHANUMERIC',
  'FIRST_NAME',
  'LAST_NAME',
  'FULL_NAME',
  'EMAIL',
  'PHONE',
  'SSN',
  'CREDIT_CARD',
  'ADDRESS_US',
  'CITY_STATE_ZIP',
  'INT_RANGE',
  'DECIMAL_RANGE',
  'NORMAL_INT',
  'NORMAL_DECIMAL',
  'DATE_BETWEEN',
  'DATE_RECENT',
  'BOOLEAN_WEIGHTED',
  'WEIGHTED',
  'LITERAL',
  'NULL',
  'LOOKUP'
];

export function emptySyntheticDraft(): SyntheticDraft {
  return {
    dataset: 'customer360',
    seed: 42,
    receiver: 'DB',
    sourceDataSourceInput: '',
    sourceDataSourceId: null,
    sourceSchema: '',
    targetDataSourceInput: '',
    targetDataSourceId: null,
    targetSchema: '',
    createTable: false,
    dropTable: false,
    loadAction: 'INSERT',
    targetPrep: 'NONE',
    keyColumns: '',
    batchSize: '',
    commitEveryRows: '',
    continueOnError: false,
    maxRejects: '',
    fastLoad: true,
    executionMode: 'SINGLE',
    partitionCount: '',
    partitionSize: '',
    targetSystems: [],
    tables: [starterTable()]
  };
}

export function starterTable(): SyntheticTable {
  return {
    name: 'customers',
    rowCount: 1000,
    columns: [
      makeColumn('customer_id', 'SEQUENCE', '', '', true),
      makeColumn('first_name', 'FIRST_NAME'),
      makeColumn('last_name', 'LAST_NAME'),
      makeColumn('email', 'EMAIL'),
      makeColumn('phone', 'PHONE'),
      makeColumn('address', 'ADDRESS_US')
    ]
  };
}

export function makeColumn(
  name = '',
  generator = 'ALPHANUMERIC',
  param1: string | null = '',
  param2: string | null = '',
  primaryKey = false,
  fk?: string | null,
  sqlType?: string | null
): SyntheticColumn {
  const [fkTable, fkColumn] = splitFk(fk);
  return {
    name,
    generator,
    param1,
    param2,
    primaryKey,
    fkTable,
    fkColumn,
    sqlType: sqlType || sqlTypeForGenerator(generator),
    typeLocked: false
  };
}

export function collectSyntheticPlan(draft: SyntheticDraft): SyntheticPlan {
  const loadAction = draft.loadAction || 'INSERT';
  const targetPrep = loadAction === 'TRUNCATE_ONLY' ? 'TRUNCATE' : draft.targetPrep || 'NONE';
  const executionMode = draft.receiver === 'DB' ? draft.executionMode || 'SINGLE' : 'SINGLE';
  const plan: SyntheticPlan = {
    dataset: String(draft.dataset || '').trim() || 'synthetic',
    seed: numberOr(draft.seed, 42),
    receiver: draft.receiver,
    targetDataSourceId: draft.targetDataSourceId || null,
    targetSchema: emptyToNull(draft.targetSchema),
    prepMode: prepModeFromLoad(Boolean(draft.dropTable), targetPrep),
    loadAction,
    targetPrep,
    keyColumns: splitCsv(draft.keyColumns),
    batchSize: positiveOrNull(draft.batchSize),
    commitEveryRows: numberOr(draft.commitEveryRows, 0),
    continueOnError: draft.continueOnError,
    maxRejects: draft.maxRejects === '' ? null : numberOr(draft.maxRejects, 0),
    fastLoad: draft.fastLoad,
    executionMode,
    partitionCount: executionMode === 'SINGLE' ? null : positiveOrNull(draft.partitionCount),
    partitionSize: executionMode === 'SINGLE' ? null : positiveOrNull(draft.partitionSize),
    createTable: draft.createTable || draft.dropTable,
    dropTable: draft.dropTable,
    tables: draft.tables
      .map((table) => ({
        name: table.name.trim(),
        rowCount: numberOr(table.rowCount, 0),
        columns: table.columns
          .filter((column) => column.name.trim())
          .map((column) => ({
            name: column.name.trim(),
            generator: column.generator || 'ALPHANUMERIC',
            param1: emptyToNull(column.param1),
            param2: emptyToNull(column.param2),
            primaryKey: Boolean(column.primaryKey),
            fkTable: emptyToNull(column.fkTable),
            fkColumn: emptyToNull(column.fkColumn),
            sqlType: column.sqlType || sqlTypeForGenerator(column.generator),
            fkMin: positiveOrNull(column.fkMin),
            fkMax: positiveOrNull(column.fkMax)
          }))
      }))
      .filter((table) => table.name)
  };
  const targetSystems = targetSystemPlan(draft);
  if (targetSystems.length) plan.targetSystems = targetSystems;
  return plan;
}

export function draftFromPlan(plan: SyntheticPlan): SyntheticDraft {
  const draft = emptySyntheticDraft();
  draft.dataset = plan.dataset || 'synthetic';
  draft.seed = plan.seed ?? 42;
  draft.receiver = (plan.receiver as SyntheticDraft['receiver']) || 'DB';
  draft.targetDataSourceInput = plan.targetDataSourceId ? String(plan.targetDataSourceId) : '';
  draft.targetDataSourceId = plan.targetDataSourceId || null;
  draft.targetSchema = plan.targetSchema || '';
  draft.createTable = Boolean(plan.createTable);
  draft.dropTable = Boolean(plan.dropTable);
  draft.loadAction = plan.loadAction || 'INSERT';
  draft.targetPrep = plan.targetPrep || 'NONE';
  draft.keyColumns = (plan.keyColumns || []).join(', ');
  draft.batchSize = plan.batchSize || '';
  draft.commitEveryRows = plan.commitEveryRows || '';
  draft.continueOnError = Boolean(plan.continueOnError);
  draft.maxRejects = plan.maxRejects ?? '';
  draft.fastLoad = Boolean(plan.fastLoad);
  draft.executionMode = (plan.executionMode as SyntheticDraft['executionMode']) || 'SINGLE';
  draft.partitionCount = plan.partitionCount || '';
  draft.partitionSize = plan.partitionSize || '';
  draft.targetSystems = plan.targetSystems || [];
  draft.tables = (plan.tables || []).map((table) => ({
    name: table.name || '',
    rowCount: table.rowCount ?? 100,
    columns: (table.columns || []).map((column) => ({
      name: column.name || '',
      generator: column.generator || 'ALPHANUMERIC',
      param1: column.param1 || '',
      param2: column.param2 || '',
      primaryKey: Boolean(column.primaryKey),
      fkTable: column.fkTable || '',
      fkColumn: column.fkColumn || '',
      sqlType: column.sqlType || sqlTypeForGenerator(column.generator || 'ALPHANUMERIC'),
      fkMin: column.fkMin || '',
      fkMax: column.fkMax || '',
      typeLocked: false
    }))
  }));
  if (!draft.tables.length) draft.tables = [starterTable()];
  return draft;
}

export function targetSystemPlan(draft: SyntheticDraft): SyntheticTargetSystem[] {
  return (draft.targetSystems || [])
    .map((target) => ensureTargetMappings({ ...target, tables: [...(target.tables || [])] }, draft.tables))
    .filter((target) => target.targetDataSourceId)
    .map((target) => ({
      name: target.name || `Target ${target.targetDataSourceId}`,
      targetDataSourceId: target.targetDataSourceId,
      targetSchema: target.targetSchema || null,
      createTable: target.createTable == null ? draft.createTable : Boolean(target.createTable),
      dropTable: target.dropTable == null ? draft.dropTable : Boolean(target.dropTable),
      loadAction: target.loadAction || draft.loadAction || 'INSERT',
      targetPrep: target.targetPrep || draft.targetPrep || 'NONE',
      batchSize: target.batchSize || positiveOrNull(draft.batchSize),
      commitEveryRows: target.commitEveryRows ?? numberOr(draft.commitEveryRows, 0),
      continueOnError: Boolean(target.continueOnError),
      maxRejects: target.maxRejects ?? (draft.maxRejects === '' ? null : numberOr(draft.maxRejects, 0)),
      fastLoad: Boolean(target.fastLoad),
      tables: (target.tables || [])
        .filter((table) => table.logicalTable && table.physicalTable)
        .map((table) => ({
          logicalTable: table.logicalTable,
          physicalTable: table.physicalTable,
          columns: (table.columns || [])
            .filter((column) => column.logicalColumn && column.physicalColumn)
            .map((column) => ({
              logicalColumn: column.logicalColumn,
              physicalColumn: column.physicalColumn,
              sqlType: column.sqlType || null
            }))
        }))
    }));
}

export function defaultTargetSystem(draft: SyntheticDraft, sources: DataSource[]): SyntheticTargetSystem {
  const name = dataSourceName(draft.targetDataSourceId, sources);
  return ensureTargetMappings(
    {
      name: name === 'No source' ? '' : name,
      targetDataSourceId: draft.targetDataSourceId,
      targetSchema: draft.targetSchema || null,
      loadAction: draft.loadAction,
      targetPrep: draft.targetPrep,
      batchSize: positiveOrNull(draft.batchSize),
      commitEveryRows: numberOr(draft.commitEveryRows, 0),
      continueOnError: draft.continueOnError,
      maxRejects: draft.maxRejects === '' ? null : numberOr(draft.maxRejects, 0),
      fastLoad: draft.fastLoad,
      tables: []
    },
    draft.tables
  );
}

export function ensureTargetMappings(target: SyntheticTargetSystem, tables: SyntheticTable[]) {
  const byTable = new Map((target.tables || []).map((table) => [table.logicalTable.toLowerCase(), table]));
  for (const logical of tables) {
    if (!logical.name.trim()) continue;
    let mapped = byTable.get(logical.name.toLowerCase());
    if (!mapped) {
      mapped = { logicalTable: logical.name, physicalTable: logical.name, columns: [] };
      target.tables.push(mapped);
      byTable.set(logical.name.toLowerCase(), mapped);
    }
    const byColumn = new Map((mapped.columns || []).map((column) => [column.logicalColumn.toLowerCase(), column]));
    for (const column of logical.columns || []) {
      if (!column.name.trim() || byColumn.has(column.name.toLowerCase())) continue;
      mapped.columns.push({
        logicalColumn: column.name,
        physicalColumn: column.name,
        sqlType: column.sqlType || sqlTypeForGenerator(column.generator)
      });
    }
  }
  return target;
}

export function tableFromColumns(table: string, columns: DataColumn[], fks: ForeignKeyEntry[] = []): SyntheticTable {
  const fkByColumn = new Map(fks.map((fk) => [String(fk.column || '').toLowerCase(), fk]));
  return {
    name: table,
    rowCount: 100,
    columns: columns.map((column) => {
      const fk = fkByColumn.get(column.column.toLowerCase());
      const generator = suggestGenerator(column.column, column.type);
      return {
        name: column.column,
        generator,
        param1: defaultParam(generator, column.column),
        param2: defaultParam2(generator),
        primaryKey: false,
        fkTable: fk?.refTable || null,
        fkColumn: fk?.refColumn || null,
        sqlType: sqlTypeFromDb(column.type),
        typeLocked: true
      };
    })
  };
}

export function applyProfile(table: SyntheticTable, profile: ProfileResponse): SyntheticTable {
  const byName = new Map((profile.columns || []).map((column) => [String(column.name || '').toLowerCase(), column]));
  return {
    ...table,
    rowCount: profile.rowCount ? Math.min(profile.rowCount, 100000) : table.rowCount,
    columns: table.columns.map((column) => {
      if (column.fkTable) return column;
      const profiled = byName.get(column.name.toLowerCase());
      if (!profiled) return column;
      return {
        ...column,
        generator: profiled.generator || column.generator,
        param1: profiled.param1 || '',
        param2: profiled.param2 || '',
        primaryKey: Boolean(profiled.primaryKey || column.primaryKey),
        sqlType: profiled.sqlType || column.sqlType
      };
    })
  };
}

export function generatorOptions(specs: GeneratorSpec[]) {
  const names = specs.map((spec) => generatorName(spec)).filter(Boolean);
  return Array.from(new Set(names.length ? names : GENERATOR_FALLBACKS)).sort();
}

export function generatorName(spec: GeneratorSpec) {
  return String(spec.name || spec.id || spec.label || '').trim();
}

export function catalogName(entry: CatalogEntry, field: 'schema' | 'table' | 'column') {
  if (field === 'column') return String(entry.column || entry.name || '').trim();
  const value = entry[field] ?? entry.name ?? entry[field.toUpperCase() as keyof CatalogEntry];
  return String(value || '').trim();
}

export function dataSourceName(id: number | null | undefined, rows: DataSource[]) {
  if (!id) return 'No source';
  return rows.find((item) => item.id === id)?.name || `Source #${id}`;
}

export const technicalInputProps = {
  autoCapitalize: 'none',
  autoCorrect: 'off',
  spellCheck: false
} as const;

type SafeInputEvent = {
  currentTarget?: { value?: string | number | null; checked?: boolean | null } | null;
  target?: { value?: string | number | null; checked?: boolean | null } | null;
};

export function safeInputValue(event: SafeInputEvent | null | undefined) {
  const value = event?.currentTarget?.value ?? event?.target?.value ?? '';
  return value == null ? '' : String(value);
}

export function safeInputChecked(event: SafeInputEvent | null | undefined) {
  return Boolean(event?.currentTarget?.checked ?? event?.target?.checked ?? false);
}

export function dataSourceCandidates(rows: DataSource[] | undefined, role: 'source' | 'target' | 'any' = 'any') {
  return (rows || []).filter((source) => {
    const clean = String(source.role || '').toUpperCase();
    if (role === 'source') return clean === 'SOURCE' || clean === 'BOTH';
    if (role === 'target') return clean === 'TARGET' || clean === 'BOTH';
    return true;
  });
}

export function resolveDataSourceInput(
  value: string | number | null | undefined,
  rows: DataSource[] | undefined,
  role: 'source' | 'target' | 'any' = 'any'
) {
  const raw = String(value || '').trim();
  if (!raw) return null;
  const candidates = dataSourceCandidates(rows, role);
  const numericId = Number(raw);
  if (Number.isInteger(numericId) && candidates.some((item) => item.id === numericId)) return numericId;
  const exact = candidates.find((item) => equalsIgnoreCase(item.name, raw));
  if (exact) return exact.id;
  return null;
}

export function dataSourceInputValue(id: number | null | undefined, rows: DataSource[] | undefined) {
  if (!id) return '';
  return (rows || []).find((item) => item.id === id)?.name || String(id);
}

export function optionHasValue(options: Array<{ value: string; label?: string }> | undefined, value: string | null | undefined) {
  const clean = String(value || '').trim();
  if (!clean) return true;
  return Boolean((options || []).some((option) => equalsIgnoreCase(option.value, clean) || equalsIgnoreCase(option.label || '', clean)));
}

export function parseNameList(value: string | string[]) {
  const pieces = Array.isArray(value) ? value : String(value || '').split(/[\n,]+/);
  return uniqueNames(pieces);
}

export function unknownNameList(names: string[], options: Array<{ value: string; label?: string }> | undefined) {
  if (!(options || []).length) return [];
  return names.filter((name) => !optionHasValue(options, name));
}

export function isJobDone(status: string | null | undefined) {
  return ['COMPLETED', 'FAILED', 'CANCELLED', 'CANCELED'].includes(String(status || '').toUpperCase());
}

export function jobTone(status: string | null | undefined): 'green' | 'red' | 'gray' | 'yellow' | 'blue' {
  const clean = String(status || '').toUpperCase();
  if (clean === 'COMPLETED') return 'green';
  if (clean === 'FAILED') return 'red';
  if (clean === 'CANCELLED' || clean === 'CANCELED') return 'gray';
  if (clean === 'CANCELLING') return 'yellow';
  return 'blue';
}

export function formatRows(value: unknown) {
  const n = Number(value || 0);
  return Number.isFinite(n) ? n.toLocaleString() : '0';
}

export function formatTime(value: string | null | undefined) {
  if (!value) return '';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString();
}

export function progressDetail(job: SyntheticJob) {
  if (job.detail) return job.detail;
  if (job.currentTable) {
    const table =
      job.tableRowsTotal && job.tableRowsTotal > 0
        ? `${job.currentTable}: ${formatRows(job.tableRowsDone)} / ${formatRows(job.tableRowsTotal)} rows`
        : `${job.currentTable}: in progress`;
    const total = job.rowsTotal ? `total ${formatRows(job.rowsDone)} / ${formatRows(job.rowsTotal)}` : '';
    return [table, total].filter(Boolean).join(' | ');
  }
  if (job.partitions?.length) {
    const complete = job.partitions.filter((p) => String(p.status || '').toUpperCase() === 'COMPLETED').length;
    return `${complete} / ${job.partitions.length} partitions completed`;
  }
  return job.message || job.stage || '';
}

export function generationStages(plan?: SyntheticPlan | null) {
  const receiver = plan?.receiver || 'DB';
  const action = String(plan?.loadAction || 'INSERT').toUpperCase();
  const prep = String(plan?.targetPrep || 'NONE').toUpperCase();
  if (receiver === 'DB') {
    const prepLabel = plan?.dropTable
      ? 'Drop/recreate'
      : action === 'TRUNCATE_ONLY' || prep === 'TRUNCATE'
        ? 'Truncate'
        : prep === 'DELETE'
          ? 'Delete rows'
          : 'Prepare';
    const loadLabel =
      action === 'TRUNCATE_ONLY' ? 'Clear target' : action === 'UPDATE' ? 'Update' : action === 'INSERT_UPDATE' ? 'Upsert' : 'Load rows';
    return action === 'TRUNCATE_ONLY' ? [prepLabel, 'Target cleared'] : ['Generate', prepLabel, loadLabel, 'Loaded'];
  }
  return ['Generate', `Build ${receiver}`, 'Download', 'Generated'];
}

export function planWarnings(summary?: SyntheticPlanSummary | null) {
  if (!summary) return [];
  const warnings: string[] = [];
  if (summary.error) warnings.push(`Plan summary fallback: ${summary.error}`);
  if (summary.warning) warnings.push(summary.warning);
  if (summary.constraintCaptureWarning) warnings.push(summary.constraintCaptureWarning);
  if (Number(summary.constraintsCaptured || 0) > Number(summary.constraintsEnforced || 0)) {
    warnings.push(
      `${summary.constraintsCaptured || 0} CHECK constraint(s) captured; ${summary.constraintsEnforced || 0} simple rule(s) will be enforced.`
    );
  }
  for (const table of summary.tables || []) {
    if (table.hasApiGenerator) warnings.push(`${table.table || table.name}: API generator will call an allowlisted endpoint.`);
    if (table.hasLookupGenerator) warnings.push(`${table.table || table.name}: LOOKUP depends on parent rows or existing values.`);
  }
  const readiness = summary.bankingReadiness;
  if (readiness?.score != null && readiness.score < 88) {
    warnings.push(`Banking readiness is ${readiness.score}/100 (${readiness.rating || 'NEEDS_REVIEW'}).`);
  }
  return warnings;
}

export function downloadTextFile(name: string, content: string) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

export function savedJobScript(job: SyntheticSavedJob, kind: 'ps1' | 'sh') {
  const base = typeof window === 'undefined' ? 'http://localhost:8088' : window.location.origin;
  if (kind === 'sh') {
    return [
      '#!/usr/bin/env bash',
      'set -euo pipefail',
      `BASE_URL=\${FORGETDM_URL:-${shQuote(base)}}`,
      `JOB_ID=${shQuote(job.id)}`,
      'TOKEN=${FORGETDM_TOKEN:-}',
      'COOKIE_JAR="${TMPDIR:-/tmp}/forgetdm-synthetic-cookies.txt"',
      'AUTH_ARGS=()',
      'if [[ -n "$TOKEN" ]]; then AUTH_ARGS=(-H "Authorization: Bearer $TOKEN"); fi',
      'RUN_JSON=$(curl -fsS -b "$COOKIE_JAR" "${AUTH_ARGS[@]}" -H "Content-Type: application/json" -X POST -d "{}" "$BASE_URL/api/synthetic/saved-jobs/$JOB_ID/run")',
      'RUN_ID=$(python -c \'import json,sys; print(json.loads(sys.stdin.read()).get("id",""))\' <<< "$RUN_JSON")',
      'echo "Synthetic run id: $RUN_ID"',
      'while true; do',
      '  STATUS_JSON=$(curl -fsS -b "$COOKIE_JAR" "${AUTH_ARGS[@]}" "$BASE_URL/api/synthetic/jobs/$RUN_ID")',
      '  STATUS=$(python -c \'import json,sys; print(json.loads(sys.stdin.read()).get("status",""))\' <<< "$STATUS_JSON")',
      '  echo "$STATUS_JSON"',
      '  [[ "$STATUS" == "COMPLETED" ]] && break',
      '  [[ "$STATUS" == "FAILED" || "$STATUS" == "CANCELLED" || "$STATUS" == "CANCELED" ]] && exit 2',
      '  sleep 2',
      'done'
    ].join('\n');
  }
  return [
    'param(',
    '  [string]$Url = $env:FORGETDM_URL,',
    '  [string]$Token = $env:FORGETDM_TOKEN',
    ')',
    `if ([string]::IsNullOrWhiteSpace($Url)) { $Url = ${psQuote(base)} }`,
    `$JobId = ${psQuote(job.id)}`,
    '$Headers = @{"Content-Type"="application/json"}',
    'if ($Token) { $Headers["Authorization"] = "Bearer $Token" }',
    '$Run = Invoke-RestMethod -Method Post -Uri "$Url/api/synthetic/saved-jobs/$JobId/run" -Headers $Headers -Body "{}"',
    '$RunId = $Run.id',
    'Write-Host "Synthetic run id: $RunId"',
    'do {',
    '  Start-Sleep -Seconds 2',
    '  $Status = Invoke-RestMethod -Method Get -Uri "$Url/api/synthetic/jobs/$RunId" -Headers $Headers',
    '  $Status | ConvertTo-Json -Depth 10',
    '} while ($Status.status -notin @("COMPLETED","FAILED","CANCELLED","CANCELED"))',
    'if ($Status.status -ne "COMPLETED") { throw "Synthetic job ended with $($Status.status)" }'
  ].join('\n');
}

export function planFingerprint(plan: SyntheticPlan) {
  return JSON.stringify(plan);
}

export function normalizeName(value: string | null | undefined) {
  return String(value || '').trim().toLowerCase().replace(/[^a-z0-9]+/g, '');
}

function equalsIgnoreCase(a: string | null | undefined, b: string | null | undefined) {
  return String(a || '').trim().toLowerCase() === String(b || '').trim().toLowerCase();
}

function uniqueNames(values: Array<string | null | undefined>) {
  const seen = new Set<string>();
  const result: string[] = [];
  for (const value of values) {
    const clean = String(value || '').trim();
    if (!clean) continue;
    const key = clean.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    result.push(clean);
  }
  return result;
}

function splitFk(fk?: string | null): [string | null, string | null] {
  if (!fk || !fk.includes('.')) return [null, null];
  const idx = fk.indexOf('.');
  return [fk.slice(0, idx), fk.slice(idx + 1)];
}

function splitCsv(value: string | null | undefined) {
  return String(value || '')
    .split(',')
    .map((part) => part.trim())
    .filter(Boolean);
}

function emptyToNull(value: string | null | undefined) {
  const clean = String(value || '').trim();
  return clean ? clean : null;
}

function numberOr(value: unknown, fallback: number) {
  const n = Number(value);
  return Number.isFinite(n) ? n : fallback;
}

function positiveOrNull(value: unknown) {
  const n = Number(value);
  return Number.isFinite(n) && n > 0 ? n : null;
}

function prepModeFromLoad(dropTable: boolean, targetPrep: string) {
  if (dropTable) return 'DROP_RECREATE';
  if (targetPrep === 'TRUNCATE') return 'TRUNCATE_CASCADE';
  if (targetPrep === 'DELETE') return 'DELETE';
  return 'APPEND';
}

function suggestGenerator(column: string, type?: string | null) {
  const name = column.toLowerCase();
  const dbType = String(type || '').toLowerCase();
  if (name.includes('email')) return 'EMAIL';
  if (name.includes('phone') || name.includes('mobile')) return 'PHONE';
  if (name.includes('ssn')) return 'SSN';
  if (name.includes('card')) return 'CREDIT_CARD';
  if (name.includes('first')) return 'FIRST_NAME';
  if (name.includes('last')) return 'LAST_NAME';
  if (name.includes('name')) return 'FULL_NAME';
  if (name.includes('addr')) return 'ADDRESS_US';
  if (name.includes('city') || name.includes('state') || name.includes('zip')) return 'CITY_STATE_ZIP';
  if (name.endsWith('id') || name.includes('_id')) return 'SEQUENCE';
  if (dbType.includes('date') || dbType.includes('time')) return 'DATE_RECENT';
  if (dbType.includes('int') || dbType.includes('number') || dbType.includes('numeric') || dbType.includes('decimal')) return 'INT_RANGE';
  if (dbType.includes('bool')) return 'BOOLEAN_WEIGHTED';
  return 'ALPHANUMERIC';
}

function defaultParam(generator: string, column: string) {
  if (generator === 'SEQUENCE' && !/(id|num|count)$/i.test(column)) return '';
  if (generator === 'ALPHANUMERIC') return '12';
  if (generator === 'INT_RANGE') return '1';
  if (generator === 'DECIMAL_RANGE') return '0.01';
  return '';
}

function defaultParam2(generator: string) {
  if (generator === 'INT_RANGE') return '1000000';
  if (generator === 'DECIMAL_RANGE') return '999.99';
  return '';
}

function sqlTypeForGenerator(generator: string) {
  const gen = String(generator || '').toUpperCase();
  if (gen.includes('DATE')) return 'DATE';
  if (gen.includes('INT') || gen === 'SEQUENCE') return 'INTEGER';
  if (gen.includes('DECIMAL')) return 'DECIMAL';
  if (gen.includes('BOOLEAN')) return 'BOOLEAN';
  return 'VARCHAR';
}

function sqlTypeFromDb(type?: string | null) {
  const t = String(type || '').toUpperCase();
  if (t.includes('CHAR') || t.includes('TEXT') || t.includes('CLOB')) return 'VARCHAR';
  if (t.includes('INT')) return 'INTEGER';
  if (t.includes('NUM') || t.includes('DEC') || t.includes('REAL') || t.includes('DOUBLE')) return 'DECIMAL';
  if (t.includes('DATE') || t.includes('TIME')) return 'DATE';
  if (t.includes('BOOL') || t === 'BIT') return 'BOOLEAN';
  return t || 'VARCHAR';
}

function psQuote(value: string) {
  return `'${value.replace(/'/g, "''")}'`;
}

function shQuote(value: string) {
  return `'${value.replace(/'/g, `'\"'\"'`)}'`;
}
