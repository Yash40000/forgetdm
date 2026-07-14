import type { CopybookField, GeneratorDraft, MaskDraft, MainframeConnection } from './types';

export const technicalInputProps = {
  autoCapitalize: 'none',
  autoCorrect: 'off',
  spellCheck: false
} as const;

export const COPYBOOK_STUDIO_SAMPLE = [
  '01 CUSTOMER-RECORD.',
  '   05 CUST-ID      PIC 9(5).',
  '   05 CUST-NAME    PIC X(10).',
  '   05 BALANCE      PIC S9(5)V99 COMP-3.',
  '   05 ACCT-COUNT   PIC 9(2) COMP.',
  '   05 STATUS-FLAG  PIC X.'
].join('\n');

export const COPYBOOK_STUDIO_SAMPLE_HEX = 'F1F2F3F4F5 C1D3C9C3C5 4040404040 0012345D 0007 C1';

export const MAINFRAME_COPYBOOK_SAMPLE = [
  '01 CUSTOMER-RECORD.',
  '   05 CUST-ID        PIC 9(6).',
  '   05 CUST-NAME      PIC X(12).',
  '   05 CARD-NUMBER    PIC 9(16) COMP-3.',
  '   05 DATE-OF-BIRTH  PIC 9(8)  COMP-3.',
  '   05 PHONE-NUMBER   PIC 9(10) COMP-3.',
  '   05 STATUS-FLAG    PIC X.'
].join('\n');

export const MF_GENERATOR_COPYBOOK_SAMPLE = [
  '01 CUSTOMER-RECORD.',
  '   05 CUST-ID         PIC 9(6).',
  '   05 CUST-NAME       PIC X(20).',
  '   05 ACCOUNT-NUMBER  PIC 9(16) COMP-3.',
  '   05 BALANCE         PIC S9(7)V99 COMP-3.',
  '   05 OPEN-DATE       PIC 9(8).',
  '   05 STATUS-FLAG     PIC X.'
].join('\n');

export const FALLBACK_MASK_FUNCTIONS = [
  'FORMAT_PRESERVE',
  'FIRST_NAME',
  'LAST_NAME',
  'FULL_NAME',
  'EMAIL',
  'PHONE',
  'SSN',
  'CREDIT_CARD',
  'DATE_SHIFT',
  'DOB_AGE_BAND',
  'ADDRESS_STREET',
  'ADDRESS_US',
  'CITY_STATE_ZIP',
  'COMPANY',
  'CHARACTER_MAP',
  'TOKENIZE',
  'SECURE_LOOKUP',
  'DIRECT_LOOKUP',
  'HASH_LOOKUP',
  'REDACT',
  'REDACT_KEEP_LAST4',
  'NUMERIC_NOISE',
  'MIN_MAX',
  'BANK_ACCOUNT',
  'IBAN',
  'SWIFT_BIC',
  'ABA_ROUTING',
  'NATIONAL_ID',
  'IP_ADDRESS',
  'MAC_ADDRESS',
  'UUID',
  'HASH_LOV',
  'FIXED',
  'NULLIFY',
  'SEQUENCE',
  'PASSTHROUGH'
];

export const FALLBACK_GENERATORS = [
  'FIRST_NAME',
  'LAST_NAME',
  'FULL_NAME',
  'EMAIL',
  'PHONE_US',
  'SSN',
  'CREDIT_CARD_VISA',
  'CREDIT_CARD_MC',
  'DOB_ADULT',
  'DATE_RECENT',
  'STREET_ADDRESS',
  'CITY',
  'STATE',
  'ZIP',
  'COMPANY',
  'UUID',
  'SEQUENCE',
  'INT_RANGE',
  'DECIMAL_RANGE',
  'BOOLEAN',
  'STATUS',
  'CURRENCY_USD',
  'IBAN_LIKE',
  'ALPHA',
  'NUMERIC_STRING',
  'ALPHANUMERIC'
];

export function safeInputValue(event: { currentTarget?: { value?: string } } | null | undefined) {
  return event?.currentTarget?.value || '';
}

export function boolInputChecked(event: { currentTarget?: { checked?: boolean } } | null | undefined) {
  return !!event?.currentTarget?.checked;
}

export function numberOrNull(value: string | number | null | undefined) {
  const raw = String(value ?? '').trim();
  if (!raw) return null;
  const parsed = Number(raw);
  return Number.isFinite(parsed) ? parsed : null;
}

export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error || 'Unknown error');
}

export function formatDate(value: string | null | undefined) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

export function connectionLabel(connection: MainframeConnection | null | undefined) {
  if (!connection) return '-';
  return `${connection.name} (${connection.type || 'LPAR'})`;
}

export function guessMaskFunction(path: string | null | undefined) {
  const p = String(path || '').toUpperCase();
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

export function guessRegistryMaskFunction(path: string | null | undefined) {
  const guessed = guessMaskFunction(path);
  return guessed === 'FORMAT_PRESERVE' ? 'NONE' : guessed;
}

export function ensureMaskDrafts<T extends { path: string }>(fields: T[], current: Record<string, MaskDraft> = {}, enabled = false) {
  const next: Record<string, MaskDraft> = {};
  for (const field of fields) {
    const existing = current[field.path];
    next[field.path] = existing || {
      enabled,
      function: guessMaskFunction(field.path),
      param1: '',
      param2: ''
    };
  }
  return next;
}

export function parsePicture(type: string | null | undefined) {
  const text = String(type || '').toUpperCase();
  let total = 0;
  let scale = 0;
  let afterV = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (ch === '9') {
      let repeat = 1;
      if (text[i + 1] === '(') {
        const close = text.indexOf(')', i + 2);
        repeat = Number.parseInt(text.slice(i + 2, close), 10) || 1;
        i = close;
      }
      total += repeat;
      if (afterV) scale += repeat;
    } else if (ch === 'V') {
      afterV = true;
    }
  }
  return { total, scale, intDigits: Math.max(1, total - scale) };
}

export function suggestGenerator(field: CopybookField): GeneratorDraft {
  const name = String(field.path || '').toUpperCase();
  if (!field.numeric) {
    if (name.includes('CARD')) return { field: field.path, generator: 'CREDIT_CARD_VISA', param1: '', param2: '' };
    if (name.includes('PHONE')) return { field: field.path, generator: 'PHONE_US', param1: '', param2: '' };
    if (name.includes('SSN')) return { field: field.path, generator: 'SSN', param1: '', param2: '' };
    if (name.includes('EMAIL')) return { field: field.path, generator: 'EMAIL', param1: '', param2: '' };
    if (name.includes('FIRST')) return { field: field.path, generator: 'FIRST_NAME', param1: '', param2: '' };
    if (name.includes('LAST')) return { field: field.path, generator: 'LAST_NAME', param1: '', param2: '' };
    if (name.includes('NAME')) return { field: field.path, generator: 'FULL_NAME', param1: '', param2: '' };
    if (name.includes('CITY')) return { field: field.path, generator: 'CITY', param1: '', param2: '' };
    if (name.includes('STATE')) return { field: field.path, generator: 'STATE', param1: '', param2: '' };
    if (name.includes('STATUS') || name.includes('FLAG')) return { field: field.path, generator: 'ALPHA', param1: String(field.length), param2: '' };
    return { field: field.path, generator: 'ALPHANUMERIC', param1: String(field.length), param2: '' };
  }
  const parsed = parsePicture(field.type);
  const length = parsed.total || field.length || 10;
  if (name.includes('ACCOUNT')) return { field: field.path, generator: 'NUMERIC_STRING', param1: String(length), param2: '' };
  if (name.includes('DATE')) return { field: field.path, generator: 'NUMERIC_STRING', param1: String(length), param2: '' };
  if (parsed.scale > 0) {
    const max = Math.max(1, Math.pow(10, parsed.intDigits) - 1);
    return { field: field.path, generator: 'DECIMAL_RANGE', param1: '0', param2: String(max) };
  }
  return { field: field.path, generator: 'NUMERIC_STRING', param1: String(length), param2: '' };
}

export function downloadText(name: string, content: string) {
  downloadBlob(name, new Blob([content], { type: 'text/plain;charset=utf-8' }));
}

export function downloadBase64(name: string, base64: string) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  downloadBlob(name, new Blob([bytes], { type: 'application/octet-stream' }));
}

function downloadBlob(name: string, blob: Blob) {
  const anchor = document.createElement('a');
  anchor.href = URL.createObjectURL(blob);
  anchor.download = name || 'download.dat';
  document.body.appendChild(anchor);
  anchor.click();
  setTimeout(() => {
    URL.revokeObjectURL(anchor.href);
    anchor.remove();
  }, 0);
}
