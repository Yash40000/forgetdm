export function schemaNames(rows?: Array<Record<string, unknown>>): string[] {
  return Array.from(
    new Set(
      (rows || [])
        .map((row) => String(row.schema ?? row.name ?? row.SCHEMA ?? '').trim())
        .filter(Boolean)
    )
  );
}

export function formatBytes(bytes?: number | null): string {
  const value = Number(bytes || 0);
  if (value <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
  const exp = Math.min(units.length - 1, Math.floor(Math.log(value) / Math.log(1024)));
  return `${(value / Math.pow(1024, exp)).toFixed(exp === 0 ? 0 : 1)} ${units[exp]}`;
}

export function formatRows(rows?: number | null): string {
  const value = Number(rows || 0);
  if (value >= 1_000_000) return `${(value / 1_000_000).toFixed(1)}M`;
  if (value >= 1_000) return `${(value / 1_000).toFixed(1)}K`;
  return String(value);
}

export function formatWhen(iso?: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso) : date.toLocaleString();
}

export function opStatusColor(status: string): string {
  const clean = status.toUpperCase();
  if (clean === 'DONE') return 'green';
  if (clean === 'FAILED') return 'red';
  if (clean === 'CANCELLED') return 'gray';
  if (clean === 'RUNNING') return 'blue';
  return 'gray';
}

export function vdbStatusColor(status?: string | null): string {
  const clean = String(status || '').toUpperCase();
  if (clean === 'ACTIVE') return 'green';
  if (clean === 'STOPPED' || clean === 'INACTIVE') return 'gray';
  if (clean.includes('FAIL') || clean === 'ERROR') return 'red';
  return 'blue';
}
