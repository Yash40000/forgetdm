import type { MaskingRule, MaskingScript } from './types';

export const technicalInputProps = {
  autoCapitalize: 'none',
  autoCorrect: 'off',
  spellCheck: false
} as const;

export const BLANK_PARAM = '__FORGETDM_BLANK__';

export const maskParamMeta: Record<string, [string | null, string | null]> = {
  BY_INDICATOR: ['Indicator column', 'Map: P=PHONE|E=EMAIL|*=FORMAT_PRESERVE'],
  PARTIAL_MASK: ['Mask pattern (regex, default [A-Za-z]+)', 'Function per match (default FIRST_NAME)'],
  PHONE_SPLIT: ['THIS column name', 'Sibling columns in order: area_code,exchange,line_no'],
  SSN_SPLIT: ['THIS column name', 'Sibling columns in order: ssn_area,ssn_group,ssn_serial'],
  DATE_SPLIT: ['THIS column name', 'Role map: dd=dob_day,mm=dob_month,yyyy=dob_year'],
  AGE: ['Shift: +1y -2m +3w +10d', 'Date format'],
  SCRIPT: ['Script name (Policies -> Scripts)', 'Extra arg (script sees it as "param")'],
  FIRST_NAME: [null, 'Output case'],
  LAST_NAME: [null, 'Output case'],
  FULL_NAME: ['Name format', 'Output case'],
  EMAIL: ['Email mode', 'Domain handling'],
  PHONE: ['Phone mode', 'Phone handling'],
  SSN: ['SSN mode', 'SSN format'],
  CREDIT_CARD: ['Card mode', 'Card format'],
  COMPANY: [null, 'Output case'],
  ADDRESS_STREET: [null, 'Output case'],
  ADDRESS_US: ['Address part', 'State handling'],
  FORMAT_PRESERVE: [null, 'Output case'],
  CHARACTER_MAP: ['Preserve characters', 'Output case'],
  TOKENIZE: ['Token prefix', 'Token length'],
  SECURE_LOOKUP: ['Replacement values or seedlist', 'Output case'],
  DIRECT_LOOKUP: ['Mappings or @value-list', 'Lookup options'],
  HASH_LOOKUP: ['Rows or @value-list', 'Hash lookup options'],
  REDACT: ['Mask character', 'Redaction mode'],
  NUMERIC_NOISE: ['Noise amount', 'Clamp range'],
  MIN_MAX: ['Minimum value', 'Maximum value'],
  BANK_ACCOUNT: ['Account mode', null],
  IBAN: ['IBAN country', 'IBAN format'],
  SWIFT_BIC: ['BIC country', null],
  ABA_ROUTING: ['Routing mode', null],
  NATIONAL_ID: ['Country', 'ID format'],
  IP_ADDRESS: ['IP mode', null],
  MAC_ADDRESS: ['MAC mode', null],
  UUID: [null, null],
  DATE_SHIFT: ['Max days', 'Date format'],
  DOB_AGE_BAND: ['Band years', 'Date format'],
  CITY_STATE_ZIP: ['Part: CITY/STATE/ZIP/FULL', 'State handling'],
  HASH_LOV: ['Seedlist file', 'Output case'],
  FIXED: ['Fixed value', 'Output case'],
  SEQUENCE: ['Prefix', null]
};

const nameFormatOptions = ['FIRST LAST', 'FIRST MIDDLE LAST', 'FIRST MID LAST', 'LAST FIRST', 'LAST MIDDLE FIRST', 'LAST MID FIRST', 'LAST, FIRST', 'LAST, FIRST MIDDLE', 'FIRST, LAST'];
const caseOptions = ['PROPER', 'UPPER', 'LOWER', 'AS_IS'];
const dateFormatOptions: Array<[string, string]> = [
  ['', 'Auto-detect'],
  ['yyyy-MM-dd', 'yyyy-MM-dd'],
  ['dd/MM/yyyy', 'dd/MM/yyyy'],
  ['MM/dd/yyyy', 'MM/dd/yyyy'],
  ['dd-MM-yyyy', 'dd-MM-yyyy'],
  ['MM-dd-yyyy', 'MM-dd-yyyy'],
  ['yyyy/MM/dd', 'yyyy/MM/dd'],
  ['dd.MM.yyyy', 'dd.MM.yyyy'],
  ['yyyyMMdd', 'yyyyMMdd'],
  ['dd-MMM-yyyy', 'dd-MMM-yyyy'],
  ['MMM dd, yyyy', 'MMM dd, yyyy'],
  ['yyyyDDD', 'yyyyDDD Julian'],
  ['yyDDD', 'yyDDD Julian'],
  ['CYYDDD', 'CYYDDD Julian']
];

const optionSets: Record<string, Array<string | [string, string]>> = {
  'Name format': nameFormatOptions,
  'Output case': caseOptions,
  'Email mode': [
    ['NAME_SAFE', 'Name-based safe email'],
    ['USER_SAFE', 'Username safe email'],
    ['HASH_LOCAL', 'Hashed local-part'],
    ['REDACT_LOCAL', 'Redact local-part'],
    ['PRESERVE_DOMAIN', 'Name-based, keep domain']
  ],
  'Domain handling': [
    ['SAFE_DOMAIN', 'Use safe .test domain'],
    ['PRESERVE_DOMAIN', 'Preserve original domain']
  ],
  'Phone mode': [
    ['FORMAT_PRESERVE', 'Format-preserving'],
    ['PRESERVE_AREA', 'Preserve country/area'],
    ['KEEP_LAST4', 'Mask except last 4'],
    ['REDACT', 'Redact all digits'],
    ['DIGITS_ONLY', 'Generate digits only']
  ],
  'Phone handling': [
    ['PRESERVE_COUNTRY', 'Preserve country code'],
    ['OBFUSCATE_ALL', 'Obfuscate all digits']
  ],
  'SSN mode': [
    ['VALID_PRESERVE_AREA', 'Valid SSN, keep area'],
    ['VALID_RANDOM_AREA', 'Valid SSN, new area'],
    ['KEEP_LAST4', 'Mask except last 4'],
    ['REDACT', 'Redact all digits'],
    ['FORMAT_PRESERVE', 'Format-preserving digits']
  ],
  'SSN format': [
    ['PRESERVE_FORMAT', 'Preserve source format'],
    ['DASHED', 'Dashed 123-45-6789'],
    ['DIGITS_ONLY', 'Digits only']
  ],
  'Card mode': [
    ['VALID_PRESERVE_BIN', 'Valid card, keep BIN'],
    ['VALID_RANDOM_BIN', 'Valid card, new test BIN'],
    ['VALID_KEEP_LAST4', 'Valid card, keep last 4'],
    ['FORMAT_PRESERVE', 'Valid card, preserve format']
  ],
  'Card format': [
    ['PRESERVE_FORMAT', 'Preserve source separators'],
    ['SPACES', 'Groups with spaces'],
    ['DASHES', 'Groups with dashes'],
    ['DIGITS_ONLY', 'Digits only']
  ],
  'Address part': ['FULL', 'LINE1', 'LINE2', 'CITY', 'STATE', 'ZIP', 'COUNTRY'],
  'State handling': [
    ['PRESERVE_STATE', 'Preserve source state'],
    ['', 'Obfuscate state']
  ],
  'Part: CITY/STATE/ZIP/FULL': ['FULL', 'CITY', 'STATE', 'ZIP'],
  'Token length': ['16', '24', '32', '48', '64'],
  'Redaction mode': [
    ['FULL', 'Redact every letter and digit'],
    ['KEEP_LAST4', 'Keep last 4 characters'],
    ['KEEP_FIRST2', 'Keep first 2 characters'],
    ['KEEP_FIRST2_LAST4', 'Keep first 2 and last 4'],
    ['STANDARD:8', 'Return a fixed 8-character marker']
  ],
  'Account mode': [
    ['KEEP_LAST4', 'Generate account, retain last 4'],
    ['FORMAT_PRESERVE', 'Map every character'],
    ['REDACT', 'Redact the whole account']
  ],
  'IBAN country': [
    ['PRESERVE_COUNTRY', 'Preserve source country'],
    ['RANDOM_COUNTRY', 'Use another supported country']
  ],
  'IBAN format': [
    ['PRESERVE_FORMAT', 'Preserve spaces and grouping'],
    ['COMPACT', 'Compact IBAN']
  ],
  'BIC country': [
    ['PRESERVE_COUNTRY', 'Preserve source country'],
    ['RANDOM_COUNTRY', 'Use another supported country']
  ],
  'Routing mode': [
    ['PRESERVE_FED_DISTRICT', 'Preserve valid Federal Reserve district'],
    ['RANDOM_DISTRICT', 'Use another valid district']
  ],
  Country: ['GENERIC', 'US', 'CA', 'UK'],
  'ID format': [
    ['PRESERVE_FORMAT', 'Preserve source separators'],
    ['DASHED', 'Use standard separators'],
    ['DIGITS_ONLY', 'Digits only']
  ],
  'IP mode': [
    ['SAFE_TEST_RANGE', 'Use documentation-only IP ranges'],
    ['PRESERVE_PRIVATE', 'Keep private-network class when possible']
  ],
  'MAC mode': [
    ['LOCAL_ADMIN', 'Generate a locally administered unicast address'],
    ['PRESERVE_OUI', 'Preserve the vendor prefix']
  ],
  'Date format': dateFormatOptions
};

export const scriptSamples = [
  {
    name: 'keep-last4',
    flavour: 'Partial',
    description: 'Mask all but the last 4 characters.',
    luaSource:
      '-- Keep the last 4 characters; format-preserve the rest so length and shape hold.\nif value == nil then return nil end\nif #value <= 4 then return value end\nreturn forge.fpe(string.sub(value, 1, #value - 4)) .. string.sub(value, -4)'
  },
  {
    name: 'by-indicator',
    flavour: 'Conditional',
    description: 'Mask differently by type column.',
    luaSource:
      '-- A polymorphic column: type_ind decides how ref_value is masked.\nlocal ind = row["type_ind"]\nif ind == "P" then return forge.mask("PHONE", value) end\nif ind == "E" then return forge.mask("EMAIL", value) end\nreturn forge.fpe(value)'
  },
  {
    name: 'email-from-name',
    flavour: 'Composite',
    description: "Build email from sibling masked name values.",
    luaSource:
      '-- Compose a safe email from already-masked name columns in this row.\nlocal f = forge.masked("first_name") or "user"\nlocal l = forge.masked("last_name") or tostring(forge.hash(value, 9999))\nreturn string.lower(f .. "." .. l) .. "@example.test"'
  },
  {
    name: 'deterministic-token',
    flavour: 'Tokenize',
    description: 'Stable TKN token for the same source value.',
    luaSource:
      '-- TKN + a deterministic, zero-padded number. Same value maps to the same token everywhere.\nif value == nil then return nil end\nreturn string.format("TKN%010d", forge.hash(value, 1000000000))'
  },
  {
    name: 'redact-keep-length',
    flavour: 'Redaction',
    description: 'Replace every character with *.',
    luaSource: '-- Full redaction that preserves the original length.\nif value == nil then return nil end\nreturn string.rep("*", #value)'
  }
];

export const scriptHints = [
  'return forge.mask("FIRST_NAME", value)',
  'return forge.mask("EMAIL", value)',
  'forge.hash(value, 100000)',
  'forge.pick("first_names.txt", value)',
  'forge.fpe(value)',
  'forge.masked("first_name")',
  'row["column_name"]',
  'if value == nil then return nil end',
  'return value'
];

export function maskParamLabel(fn: string | null | undefined, index: 1 | 2) {
  return (maskParamMeta[String(fn || '').toUpperCase()] || [])[index - 1] || null;
}

export function optionDataForParam(label: string | null | undefined, scripts: MaskingScript[] = []) {
  if (!label) return [];
  if (label.startsWith('Script name')) {
    return scripts.map((script) => ({ value: script.name, label: script.name }));
  }
  const options = label.startsWith('Date format') ? optionSets['Date format'] : optionSets[label];
  return (options || []).map((item) => {
    const [value, labelText] = Array.isArray(item) ? item : [item, item];
    return { value: value || BLANK_PARAM, label: labelText };
  });
}

export function displayParam(value: string | null | undefined) {
  return value && value !== BLANK_PARAM ? value : '';
}

export function normalizeParam(value: string | null | undefined) {
  return !value || value === BLANK_PARAM ? null : value;
}

export function ruleSignature(rule: MaskingRule) {
  return [rule.schemaName, rule.tableName, rule.columnName].filter(Boolean).join('.');
}

export function defaultMaskParamsForMap(fn: string | null | undefined, pii: string | null | undefined) {
  if (fn === 'CITY_STATE_ZIP') {
    return { param1: pii === 'CITY' ? 'CITY' : pii === 'STATE' ? 'STATE' : pii === 'ZIP' ? 'ZIP' : 'FULL', param2: 'PRESERVE_STATE' };
  }
  if (fn === 'ADDRESS_US') return { param1: 'FULL', param2: 'PRESERVE_STATE' };
  if (fn === 'FULL_NAME') return { param1: 'FIRST LAST', param2: 'PROPER' };
  if (fn === 'EMAIL') return { param1: 'NAME_SAFE', param2: 'SAFE_DOMAIN' };
  if (fn === 'PHONE') return { param1: 'FORMAT_PRESERVE', param2: 'PRESERVE_COUNTRY' };
  if (fn === 'SSN') return { param1: 'VALID_PRESERVE_AREA', param2: 'PRESERVE_FORMAT' };
  if (fn === 'CREDIT_CARD') return { param1: 'VALID_PRESERVE_BIN', param2: 'PRESERVE_FORMAT' };
  if (fn === 'CHARACTER_MAP') return { param1: null, param2: 'AS_IS' };
  if (fn === 'TOKENIZE') return { param1: pii === 'USERNAME' ? 'USR_' : 'TKN_', param2: pii === 'USERNAME' ? '24' : '32' };
  if (fn === 'SECURE_LOOKUP') return { param1: pii === 'GENDER' ? 'F|M|X' : 'ALPHA|BETA|GAMMA', param2: pii === 'GENDER' ? 'UPPER' : 'AS_IS' };
  if (fn === 'DIRECT_LOOKUP') return { param1: '@lookup:direct:demo.account-tier', param2: 'NOT_FOUND=ERROR;TRIM=BOTH;CASE=UPPER;CACHE=ON' };
  if (fn === 'HASH_LOOKUP') return { param1: '@lookup:hash:demo.us-first-names', param2: 'SEED=0;CASE=SENSITIVE;CACHE=ON' };
  if (fn === 'REDACT') return { param1: '*', param2: 'FULL' };
  if (fn === 'NUMERIC_NOISE') return { param1: 'PERCENT:10', param2: null };
  if (fn === 'MIN_MAX') return { param1: '0', param2: '100' };
  if (fn === 'BANK_ACCOUNT') return { param1: 'KEEP_LAST4', param2: null };
  if (fn === 'IBAN') return { param1: 'PRESERVE_COUNTRY', param2: 'PRESERVE_FORMAT' };
  if (fn === 'SWIFT_BIC') return { param1: 'PRESERVE_COUNTRY', param2: null };
  if (fn === 'ABA_ROUTING') return { param1: 'PRESERVE_FED_DISTRICT', param2: null };
  if (fn === 'NATIONAL_ID') return { param1: 'GENERIC', param2: 'PRESERVE_FORMAT' };
  if (fn === 'IP_ADDRESS') return { param1: 'SAFE_TEST_RANGE', param2: null };
  if (fn === 'MAC_ADDRESS') return { param1: 'LOCAL_ADMIN', param2: null };
  if (['FIRST_NAME', 'LAST_NAME', 'COMPANY', 'ADDRESS_STREET'].includes(String(fn))) return { param1: null, param2: 'PROPER' };
  return { param1: null, param2: null };
}

export function safeInputValue(event: { currentTarget?: { value?: string } } | null | undefined) {
  return event?.currentTarget?.value || '';
}

export function numberOrNull(value: string | number | null | undefined) {
  const raw = String(value ?? '').trim();
  if (!raw) return null;
  const n = Number(raw);
  return Number.isFinite(n) ? n : null;
}

export function functionSummary(fn: string) {
  if (fn === 'SCRIPT') return 'Run a governed Lua masking exit by script name.';
  if (fn === 'TOKENIZE') return 'Irreversible deterministic HMAC token with configurable length and prefix.';
  if (fn === 'SECURE_LOOKUP') return 'Stable cryptographic substitution from an approved list or seedlist.';
  if (fn === 'DIRECT_LOOKUP') return 'Exact join-style replacement from source keys to governed lookup values.';
  if (fn === 'HASH_LOOKUP') return 'Optim-style deterministic hash to sequential lookup rows, with seed and reserved-key controls.';
  if (fn === 'CHARACTER_MAP') return 'Preserve length and character classes, with optional visible ranges.';
  if (fn === 'REDACT' || fn === 'REDACT_KEEP_LAST4') return 'Configurable full or partial redaction without exposing the source.';
  if (fn === 'NUMERIC_NOISE') return 'Deterministic percentage or absolute noise with optional regulatory bounds.';
  if (fn === 'MIN_MAX') return 'Generate a deterministic numeric value inside an enforced minimum and maximum.';
  if (['BANK_ACCOUNT', 'IBAN', 'SWIFT_BIC', 'ABA_ROUTING', 'NATIONAL_ID'].includes(fn)) return 'Checksum- and format-aware protection for regulated identifiers.';
  if (fn === 'IP_ADDRESS' || fn === 'MAC_ADDRESS') return 'Valid, deterministic network identifier using safe or local ranges.';
  if (fn === 'UUID') return 'Deterministic RFC 4122 version-4-shaped identifier replacement.';
  if (fn.includes('NAME')) return 'Deterministic semantic substitution with reusable salts.';
  if (fn === 'EMAIL') return 'Safe email output with domain and local-part modes.';
  if (fn === 'SSN' || fn === 'CREDIT_CARD') return 'Valid-looking protected identifier with format controls.';
  if (fn.includes('ADDRESS') || fn.includes('CITY')) return 'Geographic substitution with state/part controls.';
  if (fn.includes('DATE') || fn.includes('AGE')) return 'Date-safe masking with shift or age-band behavior.';
  if (fn.includes('HASH')) return 'Deterministic tokenization / lookup style masking.';
  return 'Reusable masking function available to policies, DataScope, and scripts.';
}

export function functionCategory(fn: string) {
  if (['BANK_ACCOUNT', 'IBAN', 'SWIFT_BIC', 'ABA_ROUTING', 'CREDIT_CARD', 'NUMERIC_NOISE', 'MIN_MAX'].includes(fn)) return 'Financial';
  if (['FIRST_NAME', 'LAST_NAME', 'FULL_NAME', 'EMAIL', 'PHONE', 'SSN', 'NATIONAL_ID', 'COMPANY'].includes(fn)) return 'Identity';
  if (fn.includes('ADDRESS') || fn.includes('CITY')) return 'Location';
  if (fn.includes('DATE') || fn === 'AGE') return 'Date';
  if (['IP_ADDRESS', 'MAC_ADDRESS', 'UUID'].includes(fn)) return 'Network';
  if (['TOKENIZE', 'SECURE_LOOKUP', 'DIRECT_LOOKUP', 'HASH_LOOKUP', 'HASH_LOV', 'FORMAT_PRESERVE', 'CHARACTER_MAP'].includes(fn)) return 'Substitution';
  if (['REDACT', 'REDACT_KEEP_LAST4', 'NULLIFY', 'FIXED'].includes(fn)) return 'Redaction';
  return 'Advanced';
}

export function formatDate(value: string | null | undefined) {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
