import type {
  ColumnOverride,
  DataColumn,
  DataSetDefinition,
  DataSource,
  MaskingPolicy,
  MaskingRule,
  PiiCoverage,
  TableProfile
} from '@/lib/types';

/* ---------- shared feature types ---------- */

export type CreateBlueprintForm = {
  name: string;
  description: string;
  dataSourceId: string;
  schemaName: string;
};

export type CatalogEntry = {
  schema?: string | null;
  table?: string | null;
  name?: string | null;
  current?: boolean | null;
  [key: string]: unknown;
};

export type TableMapDefaults = {
  sourceDataSourceId: string;
  sourceSchemaName: string;
  targetDataSourceId: string;
  targetSchemaName: string;
  /** Subsetting starts here: the driver's rows (after filter/cap) seed the FK closure. */
  driverTable: string;
  driverFilter: string;
  maxDriverRows: string;
  /** Q1 (Optim): child-to-parent — selected child rows pull their parent rows (RI, no orphans). */
  globalQ1: boolean;
  /** Q2 (Optim): parent-to-child — selected parent rows cascade to all their child rows. */
  globalQ2: boolean;
};

export type ColumnMapRow = {
  targetColumn: string;
  targetType?: string | null;
  targetSize?: number | null;
  targetNullable?: boolean;
  sourceColumn: string;
  action: 'USE_POLICY' | 'LITERAL' | 'NULL_OUT' | 'SUPPRESS';
  literalValue: string;
  condEnabled: boolean;
  condExpr: string;
  condJoin: string;
  note?: string | null;
};

export type ColumnMapPreviewResult = {
  table?: string;
  columns?: Array<{
    targetColumn?: string | null;
    sourceColumn?: string | null;
    state?: string | null;
  }>;
  rows?: Array<
    Array<{
      original?: string | null;
      masked?: string | null;
    }>
  >;
};

export const emptyBlueprintForm: CreateBlueprintForm = {
  name: '',
  description: '',
  dataSourceId: '',
  schemaName: ''
};

export const technicalInputProps = {
  autoCapitalize: 'none',
  autoCorrect: 'off',
  spellCheck: false
} as const;

/* ---------- lookups & formatting ---------- */

export function sourceName(id: number | null | undefined, rows: DataSource[]) {
  if (!id) return 'No source';
  return rows.find((item) => item.id === id)?.name || `Source #${id}`;
}

export function isProfileIncluded(profile: TableProfile) {
  return profile.included === true;
}

export function resolveDataSourceInput(value: string | number | null | undefined, rows: DataSource[]) {
  const raw = String(value || '').trim();
  if (!raw) return null;
  const id = numberOrNull(raw);
  if (id && rows.some((item) => item.id === id)) return id;
  const exact = rows.find((item) => equalsIgnoreCase(item.name, raw));
  return exact?.id || null;
}

export function dataSourceInputValue(id: number | null | undefined, rows: DataSource[]) {
  if (!id) return '';
  return rows.find((item) => item.id === id)?.name || String(id);
}

export function policyName(id: number | null | undefined, rows: MaskingPolicy[]) {
  if (!id) return 'No policy';
  return rows.find((item) => item.id === id)?.name || `Policy #${id}`;
}

/* ---------- draft-row updates ---------- */

export function updateProfile(
  index: number,
  patch: Partial<TableProfile>,
  setRows: (fn: (rows: TableProfile[]) => TableProfile[]) => void
) {
  setRows((rows) => rows.map((row, idx) => (idx === index ? { ...row, ...patch } : row)));
}

export function removeProfile(index: number, setRows: (fn: (rows: TableProfile[]) => TableProfile[]) => void) {
  setRows((rows) => rows.filter((_, idx) => idx !== index));
}

export function updateColumnRow(
  index: number,
  patch: Partial<ColumnMapRow>,
  setRows: (fn: (rows: ColumnMapRow[]) => ColumnMapRow[]) => void
) {
  setRows((rows) => rows.map((row, idx) => (idx === index ? { ...row, ...patch } : row)));
}

/* ---------- blueprint / profile payloads ---------- */

export function defaultsFromBlueprint(blueprint: DataSetDefinition, dataSources: DataSource[]): TableMapDefaults {
  return {
    sourceDataSourceId: dataSourceInputValue(blueprint.dataSourceId, dataSources),
    sourceSchemaName: blueprint.schemaName || '',
    targetDataSourceId: dataSourceInputValue(blueprint.targetDataSourceId, dataSources),
    targetSchemaName: blueprint.targetSchemaName || '',
    driverTable: blueprint.driverTable || '',
    driverFilter: blueprint.driverFilter || '',
    maxDriverRows: blueprint.maxDriverRows ? String(blueprint.maxDriverRows) : '',
    globalQ1: blueprint.globalQ1 !== false,
    globalQ2: blueprint.globalQ2 !== false
  };
}

export function definitionPayload(
  blueprint: DataSetDefinition,
  defaults: TableMapDefaults,
  dataSources: DataSource[]
): DataSetDefinition {
  return {
    ...blueprint,
    dataSourceId: resolveDataSourceInput(defaults.sourceDataSourceId, dataSources) || blueprint.dataSourceId,
    schemaName: defaults.sourceSchemaName || '',
    targetDataSourceId: resolveDataSourceInput(defaults.targetDataSourceId, dataSources) || blueprint.targetDataSourceId,
    targetSchemaName: defaults.targetSchemaName || '',
    driverTable: emptyToNull(defaults.driverTable),
    driverFilter: defaults.driverTable.trim() ? emptyToNull(defaults.driverFilter) : null,
    maxDriverRows: defaults.driverTable.trim() ? numberOrNull(defaults.maxDriverRows) : null,
    globalQ1: defaults.globalQ1,
    globalQ2: defaults.globalQ2
  };
}

/** Per-table Q1/Q2 modes (Optim): Global (null) / Yes / No / Defer. */
export const Q_MODE_OPTIONS = [
  { value: 'global', label: 'Global' },
  { value: 'yes', label: 'Yes' },
  { value: 'no', label: 'No' },
  { value: 'defer', label: 'Defer' }
];

/** Current select value: new-style mode wins, legacy boolean override is the fallback. */
export function qModeValue(mode: string | null | undefined, legacy: boolean | null | undefined) {
  const clean = String(mode || '').trim().toUpperCase();
  if (clean === 'YES') return 'yes';
  if (clean === 'NO') return 'no';
  if (clean === 'DEFER') return 'defer';
  return legacy === true ? 'yes' : legacy === false ? 'no' : 'global';
}

/** Patch for a Q1/Q2 select change — keeps the legacy boolean mirrored for the classic console. */
export function qModePatch(field: 'q1' | 'q2', value: string | null): Partial<TableProfile> {
  const mode = value === 'yes' ? 'YES' : value === 'no' ? 'NO' : value === 'defer' ? 'DEFER' : null;
  const legacy = value === 'yes' ? true : value === 'no' ? false : null;
  return field === 'q1' ? { q1Mode: mode, q1Override: legacy } : { q2Mode: mode, q2Override: legacy };
}

export function normalizeProfilesForSave(rows: TableProfile[], blueprint: DataSetDefinition) {
  return rows.map((row) => ({
    ...row,
    datasetId: blueprint.id,
    included: isProfileIncluded(row),
    tableName: row.tableName.trim(),
    targetTableName:
      row.targetTableName && !equalsIgnoreCase(row.targetTableName, row.tableName) ? row.targetTableName.trim() : null,
    sourceSchemaName:
      row.sourceSchemaName && !equalsIgnoreCase(row.sourceSchemaName, blueprint.schemaName || '')
        ? row.sourceSchemaName.trim()
        : null,
    filterExpr: emptyToNull(row.filterExpr || row.filterSql || ''),
    rowLimit: row.rowLimit || null,
    referentialStrategy: row.referentialStrategy || row.strategy || 'INHERIT',
    policyId: row.policyId || null,
    note: emptyToNull(row.note || '')
  }));
}

export function duplicateTargets(rows: TableProfile[]) {
  const counts = new Map<string, number>();
  rows.filter(isProfileIncluded).forEach((row) => {
    const key = targetKey(row.targetTableName || row.tableName);
    if (key) counts.set(key, (counts.get(key) || 0) + 1);
  });
  return new Set(
    Array.from(counts.entries())
      .filter(([, count]) => count > 1)
      .map(([key]) => key)
  );
}

export function targetKey(value: string | null | undefined) {
  return String(value || '').trim().toLowerCase();
}

/* ---------- primitives ---------- */

export function sameNumber(value: string | number | null | undefined, other: string | number | null | undefined) {
  const left = numberOrNull(value);
  const right = numberOrNull(other);
  return left !== null && right !== null && left === right;
}

export function numberOrNull(value: string | number | null | undefined) {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export function emptyToNull(value: string | null | undefined) {
  const clean = String(value || '').trim();
  return clean ? clean : null;
}

export function equalsIgnoreCase(a: string | null | undefined, b: string | null | undefined) {
  return String(a || '').toLowerCase() === String(b || '').toLowerCase();
}

/* ---------- API paths ---------- */

export function columnsPath(dataSourceId: number | null, table: string, schema?: string | null) {
  const query = schema ? `?schema=${encodeURIComponent(schema)}` : '';
  return `/api/datasources/${dataSourceId}/tables/${encodeURIComponent(table)}/columns${query}`;
}

export function schemasPath(dataSourceId: number) {
  return `/api/datasources/${dataSourceId}/schemas`;
}

export function tablesPath(dataSourceId: number, schema?: string | null) {
  const query = schema ? `?schema=${encodeURIComponent(schema)}` : '';
  return `/api/datasources/${dataSourceId}/tables${query}`;
}

/* ---------- catalog entries ---------- */

export function catalogName(entry: CatalogEntry, field: 'schema' | 'table') {
  const value = entry[field] ?? entry.name ?? entry[field.toUpperCase()];
  return String(value || '').trim();
}

export function catalogHasName(rows: CatalogEntry[], field: 'schema' | 'table', value: string | null | undefined) {
  return rows.some((entry) => equalsIgnoreCase(catalogName(entry, field), value));
}

export function profileIdentityKey(profile: TableProfile, blueprint: DataSetDefinition) {
  return profileIdentityKeyFor(
    profile.sourceDataSourceId || blueprint.dataSourceId || null,
    profile.sourceSchemaName || blueprint.schemaName || '',
    profile.tableName
  );
}

export function profileIdentityKeyFor(
  dataSourceId: number | null | undefined,
  schema: string | null | undefined,
  table: string | null | undefined
) {
  const tableName = String(table || '').trim().toLowerCase();
  if (!tableName) return '';
  return `${dataSourceId || 'default'}|${String(schema || '').trim().toLowerCase()}|${tableName}`;
}

/* ---------- column map ---------- */

export function buildColumnRows(
  targetColumns: DataColumn[],
  sourceColumns: DataColumn[],
  overrides: ColumnOverride[]
): ColumnMapRow[] {
  const used = new Set<string>();
  return targetColumns.map((target) => {
    const saved = overrides.find((item) => equalsIgnoreCase(item.columnName, target.column));
    const action = (saved?.overrideType || 'USE_POLICY') as ColumnMapRow['action'];
    let sourceColumn = saved?.sourceColumnName || '';
    if (!sourceColumn && action === 'USE_POLICY') {
      sourceColumn = autoSourceForTarget(target.column, sourceColumns, used);
    }
    if (sourceColumn) used.add(normalizedName(sourceColumn));
    return {
      targetColumn: target.column,
      targetType: target.type,
      targetSize: target.size,
      targetNullable: target.nullable,
      sourceColumn,
      action,
      literalValue: saved?.literalValue || '',
      condEnabled: !!(saved?.condExpr || saved?.condJoin || saved?.condJson || saved?.condColumn),
      condExpr: saved?.condExpr || '',
      condJoin: saved?.condJoin || '',
      note: saved?.note || null
    };
  });
}

export function autoMapRows(rows: ColumnMapRow[], sourceColumns: DataColumn[]): ColumnMapRow[] {
  const used = new Set<string>();
  return rows.map((row) => {
    const next = autoSourceForTarget(row.targetColumn, sourceColumns, used);
    if (next) used.add(normalizedName(next));
    const action: ColumnMapRow['action'] = next ? 'USE_POLICY' : 'SUPPRESS';
    return { ...row, sourceColumn: next || '', action };
  });
}

export function applyColumnActionToAll(
  rows: ColumnMapRow[],
  action: ColumnMapRow['action'],
  literalValue: string,
  sourceColumns: DataColumn[]
): ColumnMapRow[] {
  if (action === 'USE_POLICY') return autoMapRows(rows, sourceColumns);
  return rows.map((row) => ({
    ...row,
    action,
    sourceColumn: action === 'SUPPRESS' ? '' : row.sourceColumn,
    literalValue: action === 'LITERAL' ? literalValue : row.literalValue
  }));
}

export function actionPatchForRow(
  row: ColumnMapRow,
  index: number,
  action: ColumnMapRow['action'],
  rows: ColumnMapRow[],
  sourceColumns: DataColumn[]
): Partial<ColumnMapRow> {
  if (action === 'SUPPRESS') return { action, sourceColumn: '' };
  if (action === 'USE_POLICY' && !row.sourceColumn) {
    const used = new Set(
      rows
        .filter((_, idx) => idx !== index)
        .map((item) => item.sourceColumn)
        .filter(Boolean)
        .map(normalizedName)
    );
    const sourceColumn = autoSourceForTarget(row.targetColumn, sourceColumns, used);
    return sourceColumn ? { action, sourceColumn } : { action: 'SUPPRESS', sourceColumn: '' };
  }
  return { action };
}

export function autoSourceForTarget(targetName: string, sourceColumns: DataColumn[], used: Set<string>) {
  const exact = sourceColumns.find((col) => equalsIgnoreCase(col.column, targetName) && !used.has(normalizedName(col.column)));
  if (exact) return exact.column;
  const key = normalizedName(targetName);
  const normalized = sourceColumns.find((col) => normalizedName(col.column) === key && !used.has(normalizedName(col.column)));
  return normalized?.column || '';
}

export function effectiveColumnAction(row: ColumnMapRow) {
  return row.action === 'USE_POLICY' && !row.sourceColumn ? 'SUPPRESS' : row.action;
}

export function normalizedName(value: string | null | undefined) {
  return String(value || '').toLowerCase().replace(/[^a-z0-9]/g, '');
}

export function columnLabel(column: DataColumn) {
  const type = [column.type, column.size ? `(${column.size})` : ''].filter(Boolean).join('');
  return `${column.column}${type ? ` - ${type}` : ''}`;
}

export function columnMeta(columns: DataColumn[], columnName: string | null | undefined) {
  return columns.find((column) => equalsIgnoreCase(column.column, columnName)) || null;
}

export function dtypeLabel(column: Pick<DataColumn, 'type' | 'size' | 'nullable'> | null) {
  if (!column) return '-';
  const size = column.size ? `(${column.size})` : '';
  const nullable = column.nullable === true ? ' null' : column.nullable === false ? ' not null' : '';
  return `${column.type || 'type'}${size}${nullable}`;
}

export function matchingRule(rules: MaskingRule[], profile: TableProfile, row: ColumnMapRow) {
  const sourceColumn = row.sourceColumn || row.targetColumn;
  return (
    rules.find((rule) => equalsIgnoreCase(rule.tableName, profile.tableName) && equalsIgnoreCase(rule.columnName, sourceColumn)) ||
    rules.find(
      (rule) =>
        equalsIgnoreCase(rule.tableName, profile.targetTableName || profile.tableName) &&
        equalsIgnoreCase(rule.columnName, row.targetColumn)
    ) ||
    null
  );
}

export function ruleLabel(rule: MaskingRule | null) {
  if (!rule) return 'No matching policy rule';
  const params = [rule.param1, rule.param2].filter(Boolean).join(', ');
  return params ? `${rule.function}(${params})` : rule.function;
}

export function policyRuleHint(row: ColumnMapRow, rule: MaskingRule | null, policyId: number | null) {
  const action = effectiveColumnAction(row);
  if (action === 'SUPPRESS') return 'Unused';
  if (action === 'LITERAL') return 'Literal';
  if (action === 'NULL_OUT') return 'Null';
  if (!row.sourceColumn) return 'No source';
  if (!policyId) return 'Copy as-is (no masking)';
  return ruleLabel(rule);
}

export function bulkActionLabel(action: ColumnMapRow['action']) {
  if (action === 'LITERAL') return 'Literal applied to all columns';
  if (action === 'NULL_OUT') return 'Null action applied to all columns';
  if (action === 'SUPPRESS') return 'All columns marked unused';
  return 'Auto mapping applied to all columns';
}

export function piiCoverageCount(coverage: PiiCoverage | undefined, kind: 'approved' | 'masked' | 'unmasked') {
  if (!coverage) return 0;
  if (kind === 'approved') return Number(coverage.approvedTotal ?? coverage.approvedPii ?? 0);
  if (kind === 'masked') return Number(coverage.approvedMasked ?? coverage.maskedApproved ?? 0);
  const value = coverage.unmaskedApproved;
  return Array.isArray(value) ? value.length : Number(value ?? 0);
}
