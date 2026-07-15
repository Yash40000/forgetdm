import type { DiscoveryFinding } from './types';

export const PII_DEFAULT_FUNCTIONS: Record<string, string> = {
  EMAIL: 'EMAIL',
  SSN: 'SSN',
  CREDIT_CARD: 'CREDIT_CARD',
  FIRST_NAME: 'FIRST_NAME',
  LAST_NAME: 'LAST_NAME',
  FULL_NAME: 'FULL_NAME',
  DOB: 'DOB_AGE_BAND',
  PHONE: 'PHONE',
  FULL_ADDRESS: 'ADDRESS_US',
  ADDRESS: 'ADDRESS_STREET',
  CITY: 'CITY_STATE_ZIP',
  STATE: 'CITY_STATE_ZIP',
  ZIP: 'CITY_STATE_ZIP',
  COMPANY: 'COMPANY',
  BANK_ACCOUNT: 'BANK_ACCOUNT',
  IBAN: 'IBAN',
  SWIFT_BIC: 'SWIFT_BIC',
  ROUTING: 'ABA_ROUTING',
  TAX_ID: 'NATIONAL_ID',
  IP_ADDRESS: 'IP_ADDRESS',
  PASSPORT: 'CHARACTER_MAP',
  DRIVER_LICENSE: 'CHARACTER_MAP',
  USERNAME: 'TOKENIZE',
  PASSWORD: 'NULLIFY',
  MAC_ADDRESS: 'MAC_ADDRESS',
  GENDER: 'SECURE_LOOKUP',
  MEDICAL_RECORD_NUMBER: 'TOKENIZE',
  HEALTH_PLAN_ID: 'TOKENIZE',
  DIAGNOSIS_CODE: 'CHARACTER_MAP',
  PRESCRIPTION_ID: 'TOKENIZE',
  BIOMETRIC_ID: 'TOKENIZE',
  GENETIC_DATA: 'TOKENIZE',
  CVV: 'NULLIFY',
  CARD_EXPIRY: 'CHARACTER_MAP',
  DEVICE_ID: 'TOKENIZE',
  COOKIE_ID: 'TOKENIZE',
  GEOLOCATION: 'REDACT',
  RACE_ETHNICITY: 'REDACT',
  RELIGION: 'REDACT',
  FAX: 'PHONE',
  AGE: 'NUMERIC_NOISE',
  HEALTH_DATE: 'DATE_SHIFT',
  HEALTH_DATA: 'REDACT',
  PERSON_ID: 'TOKENIZE',
  CERTIFICATE_LICENSE: 'CHARACTER_MAP',
  VEHICLE_ID: 'TOKENIZE',
  URL: 'TOKENIZE',
  PHOTO_IMAGE: 'NULLIFY',
  SIGNATURE: 'NULLIFY',
  CARD_SERVICE_CODE: 'CHARACTER_MAP',
  FULL_TRACK_DATA: 'NULLIFY',
  PIN_BLOCK: 'NULLIFY',
  ACCOUNT_CREDENTIAL: 'NULLIFY',
  POLITICAL_OPINION: 'REDACT',
  UNION_MEMBERSHIP: 'REDACT',
  SEXUAL_ORIENTATION: 'REDACT',
  CRIMINAL_RECORD: 'REDACT',
  EDUCATION_RECORD: 'REDACT',
  EMPLOYMENT_DATA: 'REDACT'
};

export const FALLBACK_PII_TYPES = [
  'FIRST_NAME',
  'LAST_NAME',
  'FULL_NAME',
  'EMAIL',
  'PHONE',
  'SSN',
  'CREDIT_CARD',
  'DOB',
  'FULL_ADDRESS',
  'ADDRESS',
  'CITY',
  'STATE',
  'ZIP',
  'COMPANY',
  'BANK_ACCOUNT',
  'IBAN',
  'SWIFT_BIC',
  'ROUTING',
  'IP_ADDRESS',
  'MAC_ADDRESS',
  'USERNAME',
  'PASSWORD',
  'PASSPORT',
  'DRIVER_LICENSE',
  'GENDER',
  'TAX_ID',
  'MEDICAL_RECORD_NUMBER',
  'HEALTH_PLAN_ID',
  'DIAGNOSIS_CODE',
  'PRESCRIPTION_ID',
  'BIOMETRIC_ID',
  'GENETIC_DATA',
  'CVV',
  'CARD_EXPIRY',
  'DEVICE_ID',
  'COOKIE_ID',
  'GEOLOCATION',
  'RACE_ETHNICITY',
  'RELIGION',
  'FAX',
  'AGE',
  'HEALTH_DATE',
  'HEALTH_DATA',
  'PERSON_ID',
  'CERTIFICATE_LICENSE',
  'VEHICLE_ID',
  'URL',
  'PHOTO_IMAGE',
  'SIGNATURE',
  'CARD_SERVICE_CODE',
  'FULL_TRACK_DATA',
  'PIN_BLOCK',
  'ACCOUNT_CREDENTIAL',
  'POLITICAL_OPINION',
  'UNION_MEMBERSHIP',
  'SEXUAL_ORIENTATION',
  'CRIMINAL_RECORD',
  'EDUCATION_RECORD',
  'EMPLOYMENT_DATA',
  'MANUAL_PII'
];

export const DISCOVERY_SCAN_PROFILES = [
  { value: 'GENERIC', label: 'Generic sensitive data', detail: 'All built-in and custom detection types', types: [] },
  {
    value: 'HIPAA',
    label: 'HIPAA / PHI',
    detail: 'PHI and HIPAA Safe Harbor identifier classes',
    types: ['FULL_NAME', 'FIRST_NAME', 'LAST_NAME', 'DOB', 'AGE', 'HEALTH_DATE', 'SSN', 'PHONE', 'FAX', 'EMAIL', 'FULL_ADDRESS', 'ADDRESS', 'CITY', 'ZIP', 'MEDICAL_RECORD_NUMBER', 'HEALTH_PLAN_ID', 'BANK_ACCOUNT', 'CERTIFICATE_LICENSE', 'VEHICLE_ID', 'DEVICE_ID', 'URL', 'IP_ADDRESS', 'BIOMETRIC_ID', 'PHOTO_IMAGE', 'SIGNATURE', 'PERSON_ID', 'DIAGNOSIS_CODE', 'PRESCRIPTION_ID', 'HEALTH_DATA', 'GENETIC_DATA']
  },
  {
    value: 'PCI_DSS',
    label: 'PCI DSS',
    detail: 'Cardholder data and sensitive authentication data',
    types: ['CREDIT_CARD', 'FULL_NAME', 'CARD_EXPIRY', 'CARD_SERVICE_CODE', 'FULL_TRACK_DATA', 'CVV', 'PIN_BLOCK']
  },
  {
    value: 'GLBA',
    label: 'GLBA / NPI',
    detail: 'Nonpublic personal and financial information',
    types: ['FULL_NAME', 'FIRST_NAME', 'LAST_NAME', 'DOB', 'SSN', 'TAX_ID', 'EMAIL', 'PHONE', 'FULL_ADDRESS', 'BANK_ACCOUNT', 'IBAN', 'ROUTING', 'SWIFT_BIC', 'CREDIT_CARD', 'ACCOUNT_CREDENTIAL', 'EMPLOYMENT_DATA']
  },
  {
    value: 'GDPR',
    label: 'GDPR personal data',
    detail: 'Direct, online, location, and Article 9 data classes',
    types: ['FULL_NAME', 'FIRST_NAME', 'LAST_NAME', 'PERSON_ID', 'DOB', 'EMAIL', 'PHONE', 'FULL_ADDRESS', 'BANK_ACCOUNT', 'IP_ADDRESS', 'MAC_ADDRESS', 'DEVICE_ID', 'COOKIE_ID', 'URL', 'GEOLOCATION', 'BIOMETRIC_ID', 'GENETIC_DATA', 'HEALTH_DATA', 'RACE_ETHNICITY', 'RELIGION', 'POLITICAL_OPINION', 'UNION_MEMBERSHIP', 'SEXUAL_ORIENTATION', 'GENDER', 'CRIMINAL_RECORD']
  },
  {
    value: 'CCPA',
    label: 'CCPA / CPRA',
    detail: 'California personal and sensitive information classes',
    types: ['FULL_NAME', 'PERSON_ID', 'SSN', 'TAX_ID', 'DRIVER_LICENSE', 'PASSPORT', 'EMAIL', 'PHONE', 'FULL_ADDRESS', 'BANK_ACCOUNT', 'CREDIT_CARD', 'ACCOUNT_CREDENTIAL', 'IP_ADDRESS', 'DEVICE_ID', 'COOKIE_ID', 'URL', 'GEOLOCATION', 'BIOMETRIC_ID', 'GENETIC_DATA', 'HEALTH_DATA', 'RACE_ETHNICITY', 'RELIGION', 'UNION_MEMBERSHIP', 'SEXUAL_ORIENTATION', 'EMPLOYMENT_DATA', 'EDUCATION_RECORD']
  },
  { value: 'CUSTOM', label: 'Custom scope', detail: 'Choose individual PII types', types: null }
] as const;

export const PII_TYPE_GROUPS = [
  {
    label: 'Names',
    types: ['FIRST_NAME', 'LAST_NAME', 'FULL_NAME', 'MALE_FIRST_NAME', 'FEMALE_FIRST_NAME', 'FULL_NAME_BY_LOCALE']
  },
  {
    label: 'Contact',
    types: ['EMAIL', 'PHONE', 'PHONE_US', 'FAX']
  },
  {
    label: 'Address & Location',
    types: ['FULL_ADDRESS', 'ADDRESS', 'ADDRESS_STREET', 'ADDRESS_US', 'CITY', 'STATE', 'ZIP', 'CITY_STATE_ZIP', 'COUNTRY', 'GEOLOCATION']
  },
  {
    label: 'Identity & Demographics',
    types: ['PERSON_ID', 'SSN', 'TAX_ID', 'DOB', 'AGE', 'GENDER', 'PASSPORT', 'DRIVER_LICENSE', 'CERTIFICATE_LICENSE', 'VEHICLE_ID', 'BIOMETRIC_ID', 'GENETIC_DATA', 'PHOTO_IMAGE', 'SIGNATURE', 'RACE_ETHNICITY', 'RELIGION', 'POLITICAL_OPINION', 'UNION_MEMBERSHIP', 'SEXUAL_ORIENTATION', 'CRIMINAL_RECORD']
  },
  {
    label: 'Financial',
    types: ['CREDIT_CARD', 'CVV', 'CARD_EXPIRY', 'CARD_SERVICE_CODE', 'FULL_TRACK_DATA', 'PIN_BLOCK', 'BANK_ACCOUNT', 'IBAN', 'IBAN_LIKE', 'ROUTING', 'SWIFT_BIC', 'CURRENCY_USD']
  },
  {
    label: 'Accounts & Technical',
    types: ['USERNAME', 'PASSWORD', 'ACCOUNT_CREDENTIAL', 'IP_ADDRESS', 'MAC_ADDRESS', 'DEVICE_ID', 'COOKIE_ID', 'URL']
  },
  {
    label: 'Health',
    types: ['MEDICAL_RECORD_NUMBER', 'HEALTH_PLAN_ID', 'HEALTH_DATE', 'HEALTH_DATA', 'DIAGNOSIS_CODE', 'PRESCRIPTION_ID']
  },
  {
    label: 'Employment & Education',
    types: ['EMPLOYMENT_DATA', 'EDUCATION_RECORD']
  },
  {
    label: 'Organization',
    types: ['COMPANY']
  },
  {
    label: 'Custom & Other',
    types: ['MANUAL_PII']
  }
];

export const MASK_PARAM_META: Record<string, Array<string | null>> = {
  BY_INDICATOR: ['Indicator column', 'Map: P=PHONE|E=EMAIL|*=FORMAT_PRESERVE'],
  PARTIAL_MASK: ['Mask pattern (regex, default [A-Za-z]+)', 'Function per match (default FIRST_NAME)'],
  PHONE_SPLIT: ['THIS column name', 'Sibling columns in order: area_code,exchange,line_no'],
  SSN_SPLIT: ['THIS column name', 'Sibling columns in order: ssn_area,ssn_group,ssn_serial'],
  DATE_SPLIT: ['THIS column name', 'Role map: dd=dob_day,mm=dob_month,yyyy=dob_year'],
  AGE: ['Shift: +1y -2m +3w +10d', 'Date format'],
  SCRIPT: ['Script name', 'Extra arg'],
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

export const PARAM_OPTIONS: Record<string, string[]> = {
  'Name format': [
    'FIRST LAST',
    'FIRST MIDDLE LAST',
    'FIRST MID LAST',
    'LAST FIRST',
    'LAST MIDDLE FIRST',
    'LAST MID FIRST',
    'LAST, FIRST',
    'LAST, FIRST MIDDLE',
    'FIRST, LAST'
  ],
  'Output case': ['PROPER', 'UPPER', 'LOWER', 'AS_IS'],
  'Email mode': ['NAME_SAFE', 'USER_SAFE', 'HASH_LOCAL', 'REDACT_LOCAL', 'PRESERVE_DOMAIN'],
  'Domain handling': ['SAFE_DOMAIN', 'PRESERVE_DOMAIN'],
  'Phone mode': ['FORMAT_PRESERVE', 'PRESERVE_AREA', 'KEEP_LAST4', 'REDACT', 'DIGITS_ONLY'],
  'Phone handling': ['PRESERVE_COUNTRY', 'OBFUSCATE_ALL'],
  'SSN mode': ['VALID_PRESERVE_AREA', 'VALID_RANDOM_AREA', 'KEEP_LAST4', 'REDACT', 'FORMAT_PRESERVE'],
  'SSN format': ['PRESERVE_FORMAT', 'DASHED', 'DIGITS_ONLY'],
  'Card mode': ['VALID_PRESERVE_BIN', 'VALID_RANDOM_BIN', 'VALID_KEEP_LAST4', 'FORMAT_PRESERVE'],
  'Card format': ['PRESERVE_FORMAT', 'SPACES', 'DASHES', 'DIGITS_ONLY'],
  'Address part': ['FULL', 'LINE1', 'LINE2', 'CITY', 'STATE', 'ZIP', 'COUNTRY'],
  'State handling': ['PRESERVE_STATE', 'OBFUSCATE_ALL'],
  'Part: CITY/STATE/ZIP/FULL': ['FULL', 'CITY', 'STATE', 'ZIP'],
  'Token length': ['16', '24', '32', '48', '64'],
  'Redaction mode': ['FULL', 'KEEP_LAST4', 'KEEP_FIRST2', 'KEEP_FIRST2_LAST4', 'STANDARD:8'],
  'Account mode': ['KEEP_LAST4', 'FORMAT_PRESERVE', 'REDACT'],
  'IBAN country': ['PRESERVE_COUNTRY', 'RANDOM_COUNTRY'],
  'IBAN format': ['PRESERVE_FORMAT', 'COMPACT'],
  'BIC country': ['PRESERVE_COUNTRY', 'RANDOM_COUNTRY'],
  'Routing mode': ['PRESERVE_FED_DISTRICT', 'RANDOM_DISTRICT'],
  Country: ['GENERIC', 'US', 'CA', 'UK'],
  'ID format': ['PRESERVE_FORMAT', 'DASHED', 'DIGITS_ONLY'],
  'IP mode': ['SAFE_TEST_RANGE', 'PRESERVE_PRIVATE'],
  'MAC mode': ['LOCAL_ADMIN', 'PRESERVE_OUI'],
  'Date format': [
    '',
    'yyyy-MM-dd',
    'dd/MM/yyyy',
    'MM/dd/yyyy',
    'dd-MM-yyyy',
    'MM-dd-yyyy',
    'yyyy/MM/dd',
    'dd.MM.yyyy',
    'yyyyMMdd',
    'dd-MMM-yyyy',
    'MMM dd, yyyy',
    'yyyyDDD',
    'yyDDD',
    'CYYDDD'
  ]
};

const MASK_TYPE_AGNOSTIC = ['NULLIFY', 'FIXED', 'PASSTHROUGH'];

export function normalizeTypeKey(types: string[]) {
  return [...types].filter(Boolean).sort().join(',');
}

export function orderPiiTypes(types: string[]) {
  return [...new Set(types.map((type) => type.trim().toUpperCase()).filter(Boolean))].sort((a, b) => {
    const ar = piiTypeRank(a);
    const br = piiTypeRank(b);
    return ar.group - br.group || ar.type - br.type || a.localeCompare(b);
  });
}

/** Custom is the complete built-in catalogue plus every backend/custom detection type. */
export function completePiiTypeCatalog(backendTypes: string[] = []) {
  return orderPiiTypes([
    ...FALLBACK_PII_TYPES,
    ...Object.keys(PII_DEFAULT_FUNCTIONS),
    ...backendTypes
  ]);
}

export function groupPiiTypes(types: string[], search = '') {
  const needle = search.trim().toLowerCase();
  const ordered = orderPiiTypes(types).filter((type) => !needle || type.toLowerCase().includes(needle));
  return PII_TYPE_GROUPS.map((group) => ({
    label: group.label,
    types: ordered.filter((type) => piiGroupLabel(type) === group.label)
  })).filter((group) => group.types.length);
}

export function selectedTypeParams(types: string[]) {
  return types.length ? types.map((type) => `&piiTypes=${encodeURIComponent(type)}`).join('') : '';
}

export function discoveryJobLive(status?: string | null) {
  const clean = String(status || '').toUpperCase();
  return clean === 'PENDING' || clean === 'RUNNING';
}

export function statusTone(status?: string | null): 'blue' | 'green' | 'red' | 'gray' | 'yellow' {
  const clean = String(status || '').toUpperCase();
  if (clean === 'COMPLETED' || clean === 'APPROVED') return 'green';
  if (clean === 'FAILED' || clean === 'REJECTED') return 'red';
  if (clean === 'RUNNING') return 'blue';
  if (clean === 'SUGGESTED' || clean === 'PENDING') return 'yellow';
  return 'gray';
}

export function defaultFunctionForPii(piiType?: string | null) {
  return PII_DEFAULT_FUNCTIONS[String(piiType || '').toUpperCase()] || 'FORMAT_PRESERVE';
}

export function typeCategory(typeName?: string | null) {
  const type = String(typeName || '').toLowerCase();
  if (type.includes('bool') || type === 'bit') return 'BOOLEAN';
  if (type.includes('date') || type.includes('time')) return 'DATE';
  if (type.includes('char') || type.includes('text') || type.includes('clob') || type.includes('json') || type.includes('uuid') || type.includes('enum')) return 'TEXT';
  if (
    type.includes('int') ||
    type.includes('serial') ||
    type.includes('numeric') ||
    type.includes('decimal') ||
    type.includes('real') ||
    type.includes('double') ||
    type.includes('float') ||
    type.includes('money') ||
    type.includes('number')
  ) {
    return 'NUMERIC';
  }
  if (type.includes('binary') || type.includes('blob') || type.includes('bytea') || type.includes('raw')) return 'BINARY';
  return 'TEXT';
}

export function compatibleFunctions(functions: string[], typeName?: string | null, selected?: string | null) {
  const base = functions.length ? functions : ['FORMAT_PRESERVE', 'FIXED', 'NULLIFY', 'PASSTHROUGH'];
  const category = typeCategory(typeName);
  let allowed = base.filter((fn) => maskCompatible(fn, category));
  if (!allowed.length) allowed = base.filter((fn) => MASK_TYPE_AGNOSTIC.includes(fn));
  if (selected && !allowed.includes(selected)) allowed = [selected, ...allowed];
  return allowed.map((fn) => ({ value: fn, label: fn }));
}

export function paramLabels(fn?: string | null) {
  return (MASK_PARAM_META[String(fn || '').toUpperCase()] || [])
    .map((label, index) => (label ? { label, n: (index + 1) as 1 | 2 } : null))
    .filter((item): item is { label: string; n: 1 | 2 } => item !== null);
}

export function paramOptions(label: string) {
  const normalized = label.startsWith('Date format') ? 'Date format' : label;
  return PARAM_OPTIONS[normalized] || [];
}

export function shortParamLabel(label: string) {
  if (label === 'Part: CITY/STATE/ZIP/FULL') return 'Part';
  return label.replace('Output ', '').replace(' handling', '');
}

export function findingSort(a: DiscoveryFinding, b: DiscoveryFinding) {
  return (
    String(a.tableName || '').localeCompare(String(b.tableName || '')) ||
    String(a.columnName || '').localeCompare(String(b.columnName || '')) ||
    Number(a.id || 0) - Number(b.id || 0)
  );
}

function maskCompatible(fn: string, category: string) {
  if (MASK_TYPE_AGNOSTIC.includes(fn)) return true;
  switch (category) {
    case 'TEXT':
      return true;
    case 'NUMERIC':
      return ['FORMAT_PRESERVE', 'CHARACTER_MAP', 'SEQUENCE', 'NUMERIC_NOISE', 'MIN_MAX', 'SSN', 'CREDIT_CARD', 'PHONE', 'BANK_ACCOUNT', 'ABA_ROUTING'].includes(fn);
    case 'DATE':
      return fn === 'DATE_SHIFT' || fn === 'DOB_AGE_BAND' || fn === 'AGE';
    default:
      return false;
  }
}

function piiTypeRank(type: string) {
  const clean = type.trim().toUpperCase();
  for (let groupIndex = 0; groupIndex < PII_TYPE_GROUPS.length; groupIndex++) {
    const typeIndex = PII_TYPE_GROUPS[groupIndex].types.indexOf(clean);
    if (typeIndex >= 0) return { group: groupIndex, type: typeIndex };
  }
  return { group: PII_TYPE_GROUPS.length - 1, type: 999 };
}

function piiGroupLabel(type: string) {
  const clean = type.trim().toUpperCase();
  for (const group of PII_TYPE_GROUPS) {
    if (group.types.includes(clean)) return group.label;
  }
  return 'Custom & Other';
}
