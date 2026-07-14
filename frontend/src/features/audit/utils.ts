export function auditWhen(iso?: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso) : date.toLocaleString();
}

const CATEGORY_COLORS: Record<string, string> = {
  AUTH: 'indigo',
  SECURITY: 'red',
  VIRTUALIZATION: 'cyan',
  MASKING: 'grape',
  PROVISIONING: 'blue',
  SYNTHETIC: 'teal',
  DISCOVERY: 'orange',
  BUSINESS_ENTITY: 'violet',
  MAPPING: 'lime',
  DATA_SOURCE: 'blue',
  MAINFRAME: 'pink',
  GENERAL: 'gray'
};

export function categoryColor(category?: string | null): string {
  return CATEGORY_COLORS[String(category || 'GENERAL').toUpperCase()] || 'gray';
}

export function outcomeColor(outcome?: string | null): string {
  return String(outcome || '').toUpperCase() === 'FAILURE' ? 'red' : 'green';
}

export function severityColor(severity?: string | null): string {
  const clean = String(severity || 'INFO').toUpperCase();
  if (clean === 'CRITICAL') return 'red';
  if (clean === 'WARNING') return 'orange';
  if (clean === 'NOTICE') return 'yellow';
  return 'gray';
}

const RANGE_DAYS: Record<string, number> = { '1d': 1, '7d': 7, '30d': 30, '90d': 90 };

export function rangeToFrom(range: string): string | undefined {
  const days = RANGE_DAYS[range];
  if (!days) return undefined;
  return new Date(Date.now() - days * 86_400_000).toISOString();
}
