import type { ValidationFinding, ValidationReport } from './types';

export function parseFindings(report?: ValidationReport | null): ValidationFinding[] {
  if (!report?.findingsJson) return [];
  try {
    const parsed = JSON.parse(report.findingsJson);
    return Array.isArray(parsed) ? (parsed as ValidationFinding[]) : [];
  } catch {
    return [];
  }
}

export function resultColor(result?: string | null): string {
  const clean = String(result || '').toUpperCase();
  if (clean === 'PASS') return 'green';
  if (clean === 'WARN') return 'yellow';
  if (clean === 'FAIL') return 'red';
  return 'gray';
}

export function severityColor(severity?: string | null): string {
  const clean = String(severity || '').toUpperCase();
  if (clean === 'FAIL') return 'red';
  if (clean === 'WARN') return 'yellow';
  if (clean === 'INFO') return 'blue';
  return 'gray';
}

export function checkColor(check?: string | null): string {
  const clean = String(check || '').toUpperCase();
  if (clean === 'LEAK') return 'red';
  if (clean === 'FORMAT') return 'orange';
  if (clean === 'RI') return 'grape';
  if (clean === 'DOMAIN') return 'yellow';
  return 'green';
}

export const CHECK_LABELS: Record<string, string> = {
  LEAK: 'Leak',
  FORMAT: 'Format',
  RI: 'Referential integrity',
  DOMAIN: 'Domain',
  ALL: 'All checks'
};

export function summarizeFindings(findings: ValidationFinding[]) {
  const byCheck: Record<string, number> = {};
  let fails = 0;
  let warns = 0;
  let infos = 0;
  for (const finding of findings) {
    const severity = String(finding.severity || '').toUpperCase();
    if (severity === 'FAIL') fails += 1;
    else if (severity === 'WARN') warns += 1;
    else infos += 1;
    const check = String(finding.check || 'OTHER').toUpperCase();
    if (check !== 'ALL') byCheck[check] = (byCheck[check] || 0) + 1;
  }
  const actionable = fails + warns;
  return { total: findings.length, fails, warns, infos, actionable, byCheck };
}

export function validationWhen(iso?: string | null): string {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? String(iso) : date.toLocaleString();
}
