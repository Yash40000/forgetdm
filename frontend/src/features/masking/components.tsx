'use client';

import { useState, type ReactNode } from 'react';
import { Autocomplete, Badge, Button, Collapse, Group, Paper, SegmentedControl, Select, SimpleGrid, Stack, Switch, Text, TextInput, ThemeIcon } from '@mantine/core';
import { IconArrowRight, IconShieldCheck, IconSparkles } from '@tabler/icons-react';

import type { MaskingScript } from '@/lib/types';
import { displayParam, functionCategory, functionSummary, maskParamLabel, normalizeParam, optionDataForParam, safeInputValue, technicalInputProps } from './utils';

export function MaskingHeader({
  title,
  eyebrow,
  description,
  action
}: {
  title: string;
  eyebrow: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="masking-page-head">
      <div>
        <Text size="xs" tt="uppercase" fw={850} c="dimmed">
          {eyebrow}
        </Text>
        <h1>{title}</h1>
        <Text c="dimmed" maw={820}>
          {description}
        </Text>
      </div>
      {action}
    </div>
  );
}

export function MaskingMetric({ label, value, detail }: { label: string; value: string | number; detail: string }) {
  return (
    <Paper className="masking-metric" p="md">
      <Text size="xs" tt="uppercase" fw={850} c="dimmed">
        {label}
      </Text>
      <Text className="masking-metric-value">{value}</Text>
      <Text size="sm" c="dimmed">
        {detail}
      </Text>
    </Paper>
  );
}

export function ParamControl({
  functionName,
  index,
  value,
  onChange,
  scripts,
  valueLists,
  lookupReferences,
  size = 'xs'
}: {
  functionName: string;
  index: 1 | 2;
  value: string;
  onChange: (value: string) => void;
  scripts?: MaskingScript[];
  valueLists?: Array<{ name: string }>;
  lookupReferences?: string[];
  size?: 'xs' | 'sm';
}) {
  const label = maskParamLabel(functionName, index);
  if (!label) {
    return (
      <TextInput
        size={size}
        label={`Param ${index}`}
        value=""
        disabled
        placeholder="Not used"
        {...technicalInputProps}
      />
    );
  }

  if (label.includes('@value-list')) {
    const references = [...new Set([...(valueLists || []).map((list) => `@${list.name}`), ...(lookupReferences || [])])];
    return (
      <Autocomplete
        size={size}
        label={shortLabel(label)}
        value={value}
        data={references}
        placeholder="Type inline values or choose @list"
        onChange={onChange}
        {...technicalInputProps}
      />
    );
  }

  const options = optionDataForParam(label, scripts || []);
  if (options.length) {
    const current = displayParam(value);
    const hasCurrent = !current || options.some((option) => option.value === current);
    const data = hasCurrent ? options : [{ value: current, label: `${current} (custom)` }, ...options];
    return (
      <Select
        size={size}
        label={shortLabel(label)}
        data={data}
        value={current || null}
        placeholder="Optional"
        searchable
        clearable
        onChange={(next) => onChange(normalizeParam(next) || '')}
      />
    );
  }

  return (
    <TextInput
      size={size}
      label={shortLabel(label)}
      value={value}
      placeholder="Optional"
      onChange={(event) => onChange(safeInputValue(event))}
      {...technicalInputProps}
    />
  );
}

/** Detect the lookup functions whose param2 is a free-form options string. */
export function isLookupOptionsFunction(functionName: string) {
  return functionName === 'HASH_LOOKUP' || functionName === 'DIRECT_LOOKUP';
}

function parseLookupOptions(value?: string): Record<string, string> {
  const map: Record<string, string> = {};
  for (const part of (value || '').split(';')) {
    const token = part.trim();
    if (!token) continue;
    const eq = token.indexOf('=');
    if (eq < 0) map[token.toUpperCase()] = '';
    else map[token.slice(0, eq).trim().toUpperCase()] = token.slice(eq + 1).trim();
  }
  return map;
}

function serializeLookupOptions(map: Record<string, string>): string {
  return Object.entries(map)
    .filter(([key]) => key)
    .map(([key, val]) => (val === '' ? key : `${key}=${val}`))
    .join(';');
}

const LOOKUP_ACTION_DATA = [
  { value: '', label: 'Error (fail closed)' },
  { value: 'PRESERVE', label: 'Keep source value' },
  { value: 'NULL', label: 'Null out' },
  { value: 'REDACT', label: 'Redact' },
  { value: 'DEFAULT', label: 'Default value' }
];

function stripLookupPrefix(param1: string) {
  return param1.replace(/^@lookup:(hash|direct):/i, '').replace(/^@/, '');
}

/**
 * Single form for a lookup rule — the governed lookup table (or inline rows), the source column(s)
 * that are hashed/matched, the destination column(s) and value column, plus trim/case/cache/reserved
 * options. Table + rows live in param1; everything else compiles to the param2 options string. A raw
 * override stays available under Advanced so nothing is lost.
 */
export function LookupOptionsBuilder({
  functionName,
  param1,
  param2,
  onParam1Change,
  onParam2Change,
  lookupReferences
}: {
  functionName: string;
  param1: string;
  param2: string;
  onParam1Change: (value: string) => void;
  onParam2Change: (value: string) => void;
  lookupReferences?: string[];
}) {
  const isHash = functionName === 'HASH_LOOKUP';
  const mode = isHash ? 'hash' : 'direct';
  const governed = param1.trim().toLowerCase().startsWith('@lookup:');
  const [sourceMode, setSourceMode] = useState<'GOVERNED' | 'INLINE'>(governed || !param1.trim() ? 'GOVERNED' : 'INLINE');
  const [advanced, setAdvanced] = useState(false);
  const map = parseLookupOptions(param2);
  const setOpt = (key: string, val: string) => {
    const next = { ...map };
    if (!val) delete next[key];
    else next[key] = val;
    onParam2Change(serializeLookupOptions(next));
  };
  const cacheOn = map.CACHE !== 'OFF' && !('NOCACHE' in map);
  const setCache = (on: boolean) => {
    const next = { ...map };
    delete next.NOCACHE;
    if (on) delete next.CACHE;
    else next.CACHE = 'OFF';
    onParam2Change(serializeLookupOptions(next));
  };
  const caseValue = map.CASE && map.CASE.toUpperCase() !== 'SENSITIVE' ? map.CASE.toUpperCase() : '';
  const usesDefault = ['NOT_FOUND', 'NULL', 'SPACES', 'ZERO_LEN'].some((key) => map[key] === 'DEFAULT');
  const tableName = stripLookupPrefix(param1);
  const tableOptions = (lookupReferences || [])
    .filter((ref) => ref.toLowerCase().startsWith(`@lookup:${mode}:`))
    .map((ref) => stripLookupPrefix(ref));

  return (
    <div className="masking-lookup-builder">
      <Text size="xs" fw={850} tt="uppercase" c="dimmed" mb={8}>
        {isHash ? 'Hash lookup' : 'Direct lookup'}
      </Text>

      <SegmentedControl
        size="xs"
        value={sourceMode}
        onChange={(next) => setSourceMode(next as 'GOVERNED' | 'INLINE')}
        data={[
          { label: 'Governed table', value: 'GOVERNED' },
          { label: 'Inline rows', value: 'INLINE' }
        ]}
      />

      {sourceMode === 'GOVERNED' ? (
        <Autocomplete
          size="xs"
          mt="xs"
          label="Lookup table name"
          description="Governed lookup catalog entry"
          placeholder="demo.us-first-names"
          data={tableOptions}
          value={tableName}
          onChange={(next) => onParam1Change(next ? `@lookup:${mode}:${next.trim()}` : '')}
          {...technicalInputProps}
        />
      ) : (
        <TextInput
          size="xs"
          mt="xs"
          label={isHash ? 'Lookup rows' : 'Mappings'}
          description="key=>value pairs separated by |"
          placeholder={isHash ? '1=>Ava|2=>Liam|3=>Noah' : 'CHK=>EVERYDAY|SAV=>RESERVE'}
          value={param1}
          onChange={(event) => onParam1Change(safeInputValue(event))}
          {...technicalInputProps}
        />
      )}

      <Text size="xs" fw={800} c="dimmed" mt="sm" mb={4}>
        Columns
      </Text>
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
        <TextInput
          size="xs"
          label="Source column(s)"
          description="Hashed/matched instead of this column"
          placeholder={isHash ? 'first_name, last_name' : 'region, tier'}
          value={map.SOURCE || ''}
          onChange={(event) => setOpt('SOURCE', safeInputValue(event))}
          {...technicalInputProps}
        />
        <TextInput
          size="xs"
          label="Destination column(s)"
          description="Target column(s) this rule feeds"
          placeholder="first_name, last_name"
          value={map.DEST || ''}
          onChange={(event) => setOpt('DEST', safeInputValue(event))}
          {...technicalInputProps}
        />
        {isHash ? (
          <TextInput
            size="xs"
            label="Value column #"
            description="Which lookup-row column this field takes"
            placeholder="1"
            value={map.VALUE || ''}
            onChange={(event) => setOpt('VALUE', safeInputValue(event))}
            {...technicalInputProps}
          />
        ) : null}
        {isHash ? (
          <TextInput
            size="xs"
            label="Column separator"
            placeholder="~"
            value={map.VCOLSEP || ''}
            onChange={(event) => setOpt('VCOLSEP', safeInputValue(event))}
            {...technicalInputProps}
          />
        ) : null}
      </SimpleGrid>

      <Text size="xs" fw={800} c="dimmed" mt="sm" mb={4}>
        Matching options
      </Text>
      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
        <Select
          size="xs"
          label="Case before match"
          data={[
            { value: '', label: 'Case-sensitive' },
            { value: 'UPPER', label: 'Uppercase' },
            { value: 'LOWER', label: 'Lowercase' }
          ]}
          value={caseValue}
          onChange={(next) => setOpt('CASE', next || '')}
        />
        <Select
          size="xs"
          label="Trim whitespace"
          data={[
            { value: '', label: 'None' },
            { value: 'BOTH', label: 'Both sides' },
            { value: 'LEFT', label: 'Left' },
            { value: 'RIGHT', label: 'Right' }
          ]}
          value={map.TRIM || ''}
          onChange={(next) => setOpt('TRIM', next || '')}
        />
        {isHash ? (
          <TextInput
            size="xs"
            label="Hash seed"
            placeholder="0"
            value={map.SEED || ''}
            onChange={(event) => setOpt('SEED', safeInputValue(event))}
            {...technicalInputProps}
          />
        ) : (
          <Select
            size="xs"
            label="If no match"
            data={LOOKUP_ACTION_DATA}
            value={map.NOT_FOUND || ''}
            onChange={(next) => setOpt('NOT_FOUND', next || '')}
          />
        )}
        <TextInput
          size="xs"
          label="Trim characters"
          placeholder="e.g. , -"
          value={map.TRIM_CHARS || ''}
          onChange={(event) => setOpt('TRIM_CHARS', safeInputValue(event))}
          {...technicalInputProps}
        />
      </SimpleGrid>

      <Group justify="space-between" mt="sm">
        <Switch size="xs" label="Cache lookup values" checked={cacheOn} onChange={(event) => setCache(event.currentTarget.checked)} />
        <Button size="compact-xs" variant="subtle" onClick={() => setAdvanced((open) => !open)}>
          {advanced ? 'Hide advanced' : 'Advanced'}
        </Button>
      </Group>

      <Collapse in={advanced}>
        <Stack gap="xs" mt="xs">
          {isHash ? (
            <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="xs">
              <Select size="xs" label="NULL source" data={LOOKUP_ACTION_DATA} value={map.NULL || ''} onChange={(next) => setOpt('NULL', next || '')} />
              <Select size="xs" label="Spaces source" data={LOOKUP_ACTION_DATA} value={map.SPACES || ''} onChange={(next) => setOpt('SPACES', next || '')} />
              <Select size="xs" label="Zero-length" data={LOOKUP_ACTION_DATA} value={map.ZERO_LEN || ''} onChange={(next) => setOpt('ZERO_LEN', next || '')} />
            </SimpleGrid>
          ) : null}
          {usesDefault ? (
            <TextInput
              size="xs"
              label="Default value"
              description="Inserted for any option set to Default value"
              value={map.DEFAULT || ''}
              onChange={(event) => setOpt('DEFAULT', safeInputValue(event))}
              {...technicalInputProps}
            />
          ) : null}
          <TextInput
            size="xs"
            label="Raw options"
            description="Everything above compiles to this — edit directly if you prefer"
            value={param2}
            onChange={(event) => onParam2Change(safeInputValue(event))}
            {...technicalInputProps}
          />
        </Stack>
      </Collapse>
    </div>
  );
}

export function FunctionCard({
  name,
  active,
  onSelect
}: {
  name: string;
  active?: boolean;
  onSelect: (name: string) => void;
}) {
  return (
    <button className={`masking-function-card ${active ? 'is-active' : ''}`} onClick={() => onSelect(name)} type="button">
      <Group justify="space-between" align="flex-start" gap="sm" wrap="nowrap">
        <div>
          <Text fw={780}>{name}</Text>
          <Text size="xs" c="dimmed">
            {functionSummary(name)}
          </Text>
        </div>
        <ThemeIcon variant="light" color={active ? 'blue' : 'gray'} size={30}>
          {name === 'SCRIPT' ? <IconSparkles size={16} /> : <IconShieldCheck size={16} />}
        </ThemeIcon>
      </Group>
      <Group gap={6} mt={8}>
        <Badge variant="light" color="gray">{functionCategory(name)}</Badge>
        {maskParamLabel(name, 1) ? <Badge variant="light">param1</Badge> : null}
        {maskParamLabel(name, 2) ? <Badge variant="light">param2</Badge> : null}
        {!maskParamLabel(name, 1) && !maskParamLabel(name, 2) ? <Badge variant="light" color="gray">no params</Badge> : null}
      </Group>
    </button>
  );
}

export function PreviewResult({ original, masked }: { original?: string | null; masked?: string | null }) {
  if (original == null && masked == null) return null;
  return (
    <div className="masking-preview-result">
      <code>{original || '(empty)'}</code>
      <IconArrowRight size={18} />
      <code className="is-masked">{masked || '(null)'}</code>
    </div>
  );
}

export function EmptyPanel({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return (
    <Paper className="masking-empty" p="xl">
      <Text fw={780}>{title}</Text>
      <Text c="dimmed" size="sm" maw={560}>
        {detail}
      </Text>
      {action ? <div>{action}</div> : null}
    </Paper>
  );
}

export function InlineDanger({ children, onClick }: { children: ReactNode; onClick: () => void }) {
  return (
    <Button size="compact-xs" variant="subtle" color="red" onClick={onClick}>
      {children}
    </Button>
  );
}

function shortLabel(label: string) {
  if (label === 'Part: CITY/STATE/ZIP/FULL') return 'Part';
  if (label.startsWith('Script name')) return 'Script';
  return label.replace('Output ', '').replace(' handling', '');
}
