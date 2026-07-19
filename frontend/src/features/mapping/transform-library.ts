import type { DataSource } from '@/lib/types';
import type { MappingSpec, MappingTransform } from './types';

export const TRANSFORM_CATALOG = [
  ['FILTER', 'Filter', 'Keep rows matching a condition'],
  ['EXPRESSION', 'Expression', 'Derive and project output columns'],
  ['AGGREGATOR', 'Aggregator', 'Group rows and calculate aggregates'],
  ['SORTER', 'Sorter', 'Order the result set'],
  ['DISTINCT', 'Distinct', 'Remove duplicate rows'],
  ['LIMIT', 'Limit', 'Cap the number of rows'],
  ['ROUTER', 'Router', 'Select a named condition route'],
  ['UNION', 'Union', 'Append compatible rows'],
  ['LOOKUP', 'Lookup', 'Enrich from a reference table'],
  ['RANK', 'Rank / Top-N', 'Rank rows within optional groups'],
  ['SEQUENCE', 'Sequence', 'Add a deterministic row number'],
  ['PIVOT', 'Pivot', 'Convert category rows into columns']
] as const;

export type Dialect = 'postgres' | 'h2' | 'oracle' | 'sqlserver' | 'db2' | 'mysql';

export function dialectFor(spec: MappingSpec, sources: DataSource[]): Dialect {
  const id = spec.sources.find((source) => source.type === 'DATABASE')?.dataSourceId;
  const source = sources.find((item) => item.id === id);
  const value = `${source?.kind || ''} ${source?.jdbcUrl || ''}`.toLowerCase();
  if (value.includes('oracle')) return 'oracle';
  if (value.includes('sqlserver') || value.includes('mssql')) return 'sqlserver';
  if (value.includes('db2')) return 'db2';
  if (value.includes('mysql') || value.includes('mariadb')) return 'mysql';
  if (value.includes('h2')) return 'h2';
  return 'postgres';
}

export function functionsFor(dialect: Dialect): Record<string, string[]> {
  const windowFns = ['ROW_NUMBER() OVER (PARTITION BY p ORDER BY o)', 'RANK() OVER (ORDER BY o)', 'DENSE_RANK() OVER (ORDER BY o)', 'LAG(col) OVER (ORDER BY o)', 'LEAD(col) OVER (ORDER BY o)', 'NTILE(4) OVER (ORDER BY o)'];
  const numeric = ['ABS(n)', 'CEIL(n)', 'FLOOR(n)', 'ROUND(n, d)', 'MOD(a, b)', 'POWER(a, b)', 'SQRT(n)', 'SIGN(n)'];
  const conditional = ['CASE WHEN cond THEN a ELSE b END', 'COALESCE(a, b)', 'NULLIF(a, b)'];
  if (dialect === 'oracle') return {
    String: ['a || b', 'SUBSTR(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'INITCAP(s)', 'TRIM(s)', 'LENGTH(s)', 'REPLACE(s, f, t)', 'LPAD(s, n, c)', 'RPAD(s, n, c)', 'INSTR(s, sub)', 'REGEXP_REPLACE(s, pat, rep)'],
    Numeric: [...numeric, 'TRUNC(n, d)', 'GREATEST(a, b)', 'LEAST(a, b)'], Date: ['SYSDATE', 'CURRENT_TIMESTAMP', "TRUNC(d, 'MM')", 'EXTRACT(YEAR FROM d)', 'ADD_MONTHS(d, n)', 'MONTHS_BETWEEN(a, b)', "TO_DATE(s, 'YYYY-MM-DD')", 'LAST_DAY(d)'],
    Conditional: [...conditional, 'NVL(a, b)', 'NVL2(x, a, b)', 'DECODE(x, v1, r1, d)'], Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDDEV(col)', "LISTAGG(col, ',') WITHIN GROUP (ORDER BY col)"], Conversion: ['CAST(x AS INTEGER)', 'TO_NUMBER(s)', 'TO_CHAR(n, fmt)'], Window: windowFns
  };
  if (dialect === 'sqlserver') return {
    String: ['CONCAT(a, b)', 'a + b', 'SUBSTRING(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'LTRIM(s)', 'RTRIM(s)', 'LEN(s)', 'REPLACE(s, f, t)', 'LEFT(s, n)', 'RIGHT(s, n)', 'CHARINDEX(sub, s)'],
    Numeric: numeric, Date: ['GETDATE()', 'SYSDATETIME()', 'DATEADD(day, n, d)', 'DATEDIFF(day, a, b)', 'DATEPART(year, d)', 'EOMONTH(d)'], Conditional: [...conditional, 'ISNULL(a, b)', 'IIF(cond, a, b)'], Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDEV(col)', "STRING_AGG(col, ',')"], Conversion: ['CAST(x AS INT)', 'TRY_CAST(x AS INT)'], Window: windowFns
  };
  if (dialect === 'db2') return {
    String: ['a || b', 'SUBSTR(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'TRIM(s)', 'LENGTH(s)', 'REPLACE(s, f, t)', 'LPAD(s, n, c)', 'RPAD(s, n, c)', 'LOCATE(sub, s)'], Numeric: [...numeric, 'TRUNCATE(n, d)'], Date: ['CURRENT DATE', 'CURRENT TIMESTAMP', 'd + 30 DAYS', 'YEAR(d)', 'MONTH(d)', 'DAY(d)', 'DATE(s)', 'LAST_DAY(d)'], Conditional: [...conditional, 'NVL(a, b)', 'DECODE(x, v1, r1, d)'], Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDDEV(col)', "LISTAGG(col, ',')"], Conversion: ['CAST(x AS INTEGER)', 'INT(x)', 'CHAR(x)', 'DECIMAL(x, 12, 2)'], Window: windowFns
  };
  if (dialect === 'mysql') return {
    String: ['CONCAT(a, b)', 'SUBSTRING(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'TRIM(s)', 'LENGTH(s)', 'REPLACE(s, f, t)', 'LPAD(s, n, c)', 'RPAD(s, n, c)', 'LOCATE(sub, s)'], Numeric: [...numeric, 'TRUNCATE(n, d)', 'GREATEST(a, b)', 'LEAST(a, b)'], Date: ['CURRENT_DATE', 'CURRENT_TIMESTAMP', 'DATE_ADD(d, INTERVAL n DAY)', 'DATEDIFF(a, b)', 'YEAR(d)', 'MONTH(d)', 'LAST_DAY(d)'], Conditional: [...conditional, 'IFNULL(a, b)', 'IF(cond, a, b)'], Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDDEV(col)', "GROUP_CONCAT(col ORDER BY col SEPARATOR ',')"], Conversion: ['CAST(x AS SIGNED)', 'CAST(x AS DECIMAL(12,2))'], Window: windowFns
  };
  return {
    String: ['CONCAT(a, b)', 'a || b', 'SUBSTRING(s, pos, len)', 'UPPER(s)', 'LOWER(s)', 'INITCAP(s)', 'TRIM(s)', 'LENGTH(s)', 'REPLACE(s, f, t)', 'LPAD(s, n, c)', 'RPAD(s, n, c)', 'LEFT(s, n)', 'RIGHT(s, n)', 'POSITION(sub IN s)', 'REGEXP_REPLACE(s, pat, rep)', 'SPLIT_PART(s, delim, n)'], Numeric: [...numeric, 'TRUNC(n, d)', 'GREATEST(a, b)', 'LEAST(a, b)'], Date: ['CURRENT_DATE', 'CURRENT_TIMESTAMP', 'NOW()', "DATE_TRUNC('day', d)", 'EXTRACT(YEAR FROM d)', "d + INTERVAL '30 days'", "TO_DATE(s, 'YYYY-MM-DD')"], Conditional: conditional, Aggregate: ['COUNT(*)', 'COUNT(DISTINCT col)', 'SUM(col)', 'AVG(col)', 'MIN(col)', 'MAX(col)', 'STDDEV(col)', "STRING_AGG(col, ',')"], Conversion: ['CAST(x AS INTEGER)', 'CAST(x AS NUMERIC(12,2))', 'x::text'], Window: windowFns
  };
}

export function compileSpec(spec: MappingSpec, dataSources: DataSource[]): MappingSpec {
  const databaseSources = spec.sources.filter((source) => source.type === 'DATABASE');
  const ids = new Set(databaseSources.map((source) => source.dataSourceId).filter(Boolean));
  if (!databaseSources.length || databaseSources.length !== spec.sources.length || ids.size !== 1) return { ...spec, compiledSql: undefined, compiledDataSourceId: null };
  const dialect = dialectFor(spec, dataSources);
  const sql = spec.sqlOverride?.trim() || compileSql(spec, dialect);
  const router = (spec.transforms || []).find((transform) => transform.type === 'ROUTER');
  const groups = array<Record<string, unknown>>(router?.groups).filter((group) => text(group.target));
  if (router && groups.length && spec.target.type === 'DATABASE' && spec.target.dataSourceId) {
    const explicit = groups.map((group) => text(group.condition)).filter(Boolean);
    const statements = groups.map((group) => {
      const condition = text(group.condition) || (explicit.length ? explicit.map((item) => `(${item}) IS NOT TRUE`).join(' AND ') : '1=1');
      const transforms = (spec.transforms || []).map((transform) => transform.id === router.id ? { ...transform, groups: [{ ...group, condition }], active: 0 } : transform);
      const routeSql = compileSql({ ...spec, transforms, sqlOverride: undefined }, dialect);
      const target = `${text(group.targetSchema) || spec.target.schema ? `${text(group.targetSchema) || spec.target.schema}.` : ''}${text(group.target)}`;
      return `INSERT INTO ${target}\n${routeSql}`;
    });
    return { ...spec, target: { ...spec.target, table: spec.target.table || text(groups[0].target) }, compiledSql: sql, compiledDataSourceId: databaseSources[0].dataSourceId || null, sql, routeExecution: true, loadStatements: statements, loadTargets: groups.map((group) => ({ table: text(group.target), condition: text(group.condition) || null })) };
  }
  return { ...spec, compiledSql: sql, compiledDataSourceId: databaseSources[0].dataSourceId || null, sql, routeExecution: false, loadStatements: undefined, loadTargets: undefined };
}

export function compileSql(spec: MappingSpec, dialect: Dialect): string {
  if (!spec.sources.length) return '';
  const transforms = spec.transforms || [];
  const qualified = (source: MappingSpec['sources'][number]) => `${source.schema ? `${source.schema}.` : ''}${source.table} ${source.alias}`;
  let from = `FROM ${qualified(spec.sources[0])}`;
  const joined = new Set([spec.sources[0].alias.toLowerCase()]);
  const remaining = spec.joins.map((join) => ({ ...join, leftAlias: join.left.split('.')[0].toLowerCase(), rightAlias: join.right.split('.')[0].toLowerCase() }));
  while (remaining.length) {
    const index = remaining.findIndex((join) => joined.has(join.leftAlias) !== joined.has(join.rightAlias));
    if (index < 0) break;
    const first = remaining[index]; const nextAlias = joined.has(first.leftAlias) ? first.rightAlias : first.leftAlias;
    const source = spec.sources.find((item) => item.alias.toLowerCase() === nextAlias);
    if (!source) { remaining.splice(index, 1); continue; }
    const pair = remaining.filter((join) => (joined.has(join.leftAlias) && join.rightAlias === nextAlias) || (joined.has(join.rightAlias) && join.leftAlias === nextAlias));
    from += `\n${first.type} JOIN ${qualified(source)} ON ${pair.map((join) => `${join.left} = ${join.right}`).join(' AND ')}`;
    joined.add(nextAlias); pair.forEach((join) => remaining.splice(remaining.indexOf(join), 1));
  }
  spec.sources.slice(1).filter((source) => !joined.has(source.alias.toLowerCase())).forEach((source) => { from += `\nCROSS JOIN ${qualified(source)}`; });

  const lookups = transforms.filter((transform) => transform.type === 'LOOKUP');
  const lookupReturns: string[] = [];
  lookups.forEach((transform, index) => {
    if (!text(transform.table) || !text(transform.on)) return;
    const alias = text(transform.alias) || `lk${index + 1}`;
    from += `\nLEFT JOIN ${text(transform.schema) ? `${text(transform.schema)}.` : ''}${text(transform.table)} ${alias} ON ${text(transform.on)}`;
    lookupReturns.push(...list(transform.returns));
  });

  const filters = transforms.filter((transform) => transform.type === 'FILTER' && text(transform.condition)).map((transform) => `(${text(transform.condition)})`);
  spec.sources.filter((source) => source.filter).forEach((source) => filters.push(`(${source.filter})`));
  transforms.filter((transform) => transform.type === 'ROUTER').forEach((transform) => {
    const groups = array<Record<string, unknown>>(transform.groups); const active = Number(transform.active || 0);
    const condition = text(groups[Math.min(active, Math.max(0, groups.length - 1))]?.condition); if (condition) filters.push(`(${condition})`);
  });
  const aggregator = transforms.find((transform) => transform.type === 'AGGREGATOR');
  const pivot = transforms.find((transform) => transform.type === 'PIVOT' && text(transform.category) && text(transform.value) && text(transform.values));
  const expressions = transforms.filter((transform) => transform.type === 'EXPRESSION');
  const distinct = transforms.some((transform) => transform.type === 'DISTINCT');
  let selectList = '*'; let groupBy: string[] = [];
  if (pivot) {
    groupBy = list(pivot.groupBy);
    const cells = list(pivot.values).map((value) => `${text(pivot.agg) || 'SUM'}(CASE WHEN ${text(pivot.category)} = '${value.replaceAll("'", "''")}' THEN ${text(pivot.value)} END) AS ${cleanIdentifier(value)}`);
    selectList = [...groupBy, ...cells].join(', ') || '*';
  } else if (aggregator) {
    groupBy = list(aggregator.groupBy);
    const aggregates = array<Record<string, unknown>>(aggregator.aggregates).filter((item) => text(item.name) && text(item.expr)).map((item) => `${text(item.expr)} AS ${cleanIdentifier(text(item.name))}`);
    selectList = [...groupBy, ...aggregates].join(', ') || '*';
  } else if (expressions.length) {
    const projected = expressions.flatMap((transform) => array<Record<string, unknown>>(transform.columns)).filter((item) => text(item.expr)).map((item, index) => `${text(item.expr)} AS ${cleanIdentifier(text(item.name) || `expr_${index + 1}`)}`);
    selectList = projected.join(', ') || '*';
  }
  if (lookupReturns.length && selectList !== '*') selectList += `, ${lookupReturns.join(', ')}`;
  const sequence = transforms.find((transform) => transform.type === 'SEQUENCE');
  if (sequence) selectList += `${selectList === '*' ? '' : ','} ROW_NUMBER() OVER (ORDER BY ${text(sequence.orderBy) || '1'}) AS ${cleanIdentifier(text(sequence.name) || 'seq_no')}`;

  let sql = `SELECT ${distinct ? 'DISTINCT ' : ''}${selectList}\n${from}`;
  if (filters.length) sql += `\nWHERE ${filters.join(' AND ')}`;
  if (groupBy.length) sql += `\nGROUP BY ${groupBy.join(', ')}`;
  transforms.filter((transform) => transform.type === 'UNION' && text(transform.table)).forEach((transform) => {
    sql += `\nUNION ${transform.all === false ? '' : 'ALL '}SELECT ${text(transform.columns) || '*'} FROM ${text(transform.schema) ? `${text(transform.schema)}.` : ''}${text(transform.table)}`;
    if (text(transform.condition)) sql += ` WHERE ${text(transform.condition)}`;
  });
  const rank = transforms.find((transform) => transform.type === 'RANK' && text(transform.orderBy));
  if (rank) {
    const name = cleanIdentifier(text(rank.name) || 'rank_in_group');
    sql = `SELECT __r.* FROM (\nSELECT __s.*, ROW_NUMBER() OVER (${text(rank.partitionBy) ? `PARTITION BY ${text(rank.partitionBy)} ` : ''}ORDER BY ${text(rank.orderBy)}) AS ${name}\nFROM (\n${sql}\n) __s\n) __r`;
    if (Number(rank.topN || 0) > 0) sql += `\nWHERE ${name} <= ${Number(rank.topN)}`;
  }
  const sorter = transforms.find((transform) => transform.type === 'SORTER');
  const sort = array<Record<string, unknown>>(sorter?.sort).filter((item) => text(item.col));
  if (sort.length) sql += `\nORDER BY ${sort.map((item) => `${text(item.col)} ${text(item.dir) || 'ASC'}`).join(', ')}`;
  const limit = Number(transforms.find((transform) => transform.type === 'LIMIT')?.rows || spec.rowLimit || 0);
  if (limit > 0) {
    if (dialect === 'sqlserver') sql = sql.replace(/^SELECT\s+/i, `SELECT TOP (${Math.floor(limit)}) `);
    else if (dialect === 'oracle' || dialect === 'db2') sql += `\nFETCH FIRST ${Math.floor(limit)} ROWS ONLY`;
    else sql += `\nLIMIT ${Math.floor(limit)}`;
  }
  (spec.stagingTables || []).forEach((staging, index) => {
    const incoming = (spec.canvas?.links || []).filter((link) => link.target.toLowerCase().startsWith(`${staging.name.toLowerCase()}.`));
    if (!incoming.length || !staging.columns.length) return;
    const projections = staging.columns.map((column) => {
      const link = incoming.find((candidate) => candidate.target.split('.').pop()?.toLowerCase() === column.toLowerCase());
      const sourceName = link ? cleanIdentifier(link.source.split('.').pop() || link.source) : 'NULL';
      const dataType = safeSqlType(staging.columnTypes?.[column]);
      const expression = sourceName === 'NULL' ? sourceName : dataType ? `CAST(${sourceName} AS ${dataType})` : sourceName;
      return `${expression} AS ${cleanIdentifier(column)}`;
    });
    sql = `SELECT ${projections.join(', ')}\nFROM (\n${sql}\n) __stage_${index + 1}`;
  });
  return sql;
}

export function newTransform(type: string): MappingTransform {
  const base: MappingTransform = { id: crypto.randomUUID(), type };
  if (type === 'EXPRESSION') base.columns = [{ name: '', expr: '' }];
  if (type === 'AGGREGATOR') { base.groupBy = []; base.aggregates = [{ name: '', expr: '' }]; }
  if (type === 'SORTER') base.sort = [{ col: '', dir: 'ASC' }];
  if (type === 'ROUTER') { base.groups = [{ name: 'Route 1', condition: '', target: '', targetSchema: '' }]; base.active = 0; }
  if (type === 'UNION') base.all = true;
  if (type === 'PIVOT') base.agg = 'SUM';
  return base;
}

export function lineageFor(spec: MappingSpec, sourceColumns: string[]) {
  const refs = (expression: string) => sourceColumns.filter((column) => new RegExp(`\\b${escapeRegExp(column)}\\b|\\b${escapeRegExp(column.split('.').pop() || '')}\\b`, 'i').test(expression));
  const rows: Array<{ output: string; expression: string; sources: string[]; via: string }> = [];
  const expressions = (spec.transforms || []).filter((transform) => transform.type === 'EXPRESSION');
  if (expressions.length) expressions.flatMap((transform) => array<Record<string, unknown>>(transform.columns)).filter((item) => text(item.name) && text(item.expr)).forEach((item) => rows.push({ output: text(item.name), expression: text(item.expr), sources: refs(text(item.expr)), via: 'Expression' }));
  else spec.columns.filter((column) => column.action !== 'UNUSED').forEach((column) => rows.push({ output: column.target, expression: column.action === 'LITERAL' ? String(column.literal || '') : column.source, sources: column.source ? refs(column.source) : [], via: column.action }));
  (spec.transforms || []).filter((transform) => transform.type === 'SEQUENCE' || transform.type === 'RANK').forEach((transform) => rows.push({ output: text(transform.name) || (transform.type === 'RANK' ? 'rank_in_group' : 'seq_no'), expression: 'ROW_NUMBER()', sources: refs(`${text(transform.orderBy)} ${text(transform.partitionBy)}`), via: transform.type }));
  return rows;
}

export function transformName(type: string) { return TRANSFORM_CATALOG.find((item) => item[0] === type)?.[1] || type; }
function text(value: unknown) { return value == null ? '' : String(value).trim(); }
function list(value: unknown) { return Array.isArray(value) ? value.map(text).filter(Boolean) : text(value).split(',').map((item) => item.trim()).filter(Boolean); }
function array<T>(value: unknown): T[] { return Array.isArray(value) ? value as T[] : []; }
function cleanIdentifier(value: string) { let clean = value.split('.').pop()?.replace(/[^A-Za-z0-9_]/g, '_').replace(/^_+|_+$/g, '') || 'column'; if (/^\d/.test(clean)) clean = `c_${clean}`; return clean; }
function safeSqlType(value: unknown) { const type = text(value); return /^[A-Za-z][A-Za-z0-9_ ]*(?:\(\s*\d+\s*(?:,\s*\d+\s*)?\))?(?:\[\])?$/.test(type) ? type : ''; }
function escapeRegExp(value: string) { return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
