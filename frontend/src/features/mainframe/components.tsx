'use client';

import type { ReactNode } from 'react';
import { Badge, Button, Group, Paper, Select, Table, Text, TextInput, ThemeIcon } from '@mantine/core';
import { IconFileText, IconServerCog } from '@tabler/icons-react';

import type { MaskingScript } from '@/lib/types';
import { BLANK_PARAM, maskParamLabel, normalizeParam, optionDataForParam } from '@/features/masking/utils';
import type { CopybookField, MainframeConnection, MainframeJob, MaskDraft } from './types';
import { FALLBACK_MASK_FUNCTIONS, technicalInputProps } from './utils';

export function MainframeHeader({
  eyebrow,
  title,
  description,
  action
}: {
  eyebrow: string;
  title: string;
  description: string;
  action?: ReactNode;
}) {
  return (
    <div className="mf-page-head">
      <div>
        <Text className="mf-kicker">{eyebrow}</Text>
        <h1>{title}</h1>
        <Text c="dimmed" maw={920}>
          {description}
        </Text>
      </div>
      {action}
    </div>
  );
}

export function MainframeMetric({ label, value, detail, icon = 'file' }: { label: string; value: ReactNode; detail: string; icon?: 'file' | 'server' }) {
  return (
    <Paper className="mf-metric" p="md">
      <Group justify="space-between" align="flex-start">
        <div>
          <Text size="xs" c="dimmed" fw={850} tt="uppercase">
            {label}
          </Text>
          <div className="mf-metric-value">{value}</div>
          <Text size="sm" c="dimmed">
            {detail}
          </Text>
        </div>
        <ThemeIcon variant="light" radius="md">
          {icon === 'server' ? <IconServerCog size={18} /> : <IconFileText size={18} />}
        </ThemeIcon>
      </Group>
    </Paper>
  );
}

export function EmptyState({ title, detail }: { title: string; detail: string }) {
  return (
    <div className="mf-empty">
      <Text fw={780}>{title}</Text>
      <Text size="sm" c="dimmed">
        {detail}
      </Text>
    </div>
  );
}

export function StatusBadge({ status }: { status?: string | null }) {
  const value = String(status || 'UNKNOWN').toUpperCase();
  const color = value === 'COMPLETED' || value === 'OK' ? 'green' : value === 'FAILED' || value === 'ERROR' ? 'red' : value === 'RUNNING' || value === 'TESTING' || value === 'PENDING' ? 'yellow' : 'gray';
  return (
    <Badge color={color} variant="light">
      {value}
    </Badge>
  );
}

export function ConnectionName({ id, connections }: { id?: number | null; connections: MainframeConnection[] }) {
  const connection = connections.find((item) => item.id === id);
  return <span>{connection ? `${connection.name} (${connection.type})` : '-'}</span>;
}

export function JobProgress({ job }: { job: MainframeJob }) {
  const done = Number(job.filesDone || 0);
  const total = Number(job.filesTotal || 0);
  const pct = total ? Math.round((done / total) * 100) : 0;
  return (
    <div className="mf-progress">
      <Group justify="space-between" gap="xs">
        <Text size="xs" fw={750}>
          {done}/{total} files
        </Text>
        <Text size="xs" c="dimmed">
          {pct}%
        </Text>
      </Group>
      <div>
        <span style={{ width: `${Math.max(0, Math.min(100, pct))}%` }} />
      </div>
    </div>
  );
}

export function FieldTable({ fields, compact = false }: { fields: CopybookField[]; compact?: boolean }) {
  if (!fields.length) return <EmptyState title="No fields yet" detail="Parse or select a copybook to see byte-level fields." />;
  return (
    <div className="mf-table-wrap">
      <Table striped={false} highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Field</Table.Th>
            <Table.Th>Offset</Table.Th>
            <Table.Th>Length</Table.Th>
            <Table.Th>Type</Table.Th>
            {compact ? null : <Table.Th>Picture</Table.Th>}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {fields.map((field) => (
            <Table.Tr key={`${field.path}-${field.offset}`}>
              <Table.Td>
                <Text fw={760} className="mf-mono-line">
                  {field.path}
                </Text>
              </Table.Td>
              <Table.Td>{field.offset}</Table.Td>
              <Table.Td>{field.length}</Table.Td>
              <Table.Td>
                <code>{field.type}</code>
              </Table.Td>
              {compact ? null : <Table.Td>{field.picture || '-'}</Table.Td>}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

export function ParamInput({
  fn,
  index,
  value,
  onChange,
  scripts = [],
  disabled = false
}: {
  fn: string;
  index: 1 | 2;
  value: string;
  onChange: (value: string) => void;
  scripts?: MaskingScript[];
  disabled?: boolean;
}) {
  const label = fn === 'NONE' ? null : maskParamLabel(fn, index);
  if (!label) {
    return <span className="mf-param-na">-</span>;
  }
  const options = optionDataForParam(label, scripts);
  if (options.length) {
    return (
      <Select
        size="xs"
        label={label}
        disabled={disabled}
        data={options}
        value={value || BLANK_PARAM}
        onChange={(next) => onChange(normalizeParam(next) || '')}
      />
    );
  }
  return <TextInput {...technicalInputProps} size="xs" label={label} value={value} disabled={disabled} onChange={(event) => onChange(event.currentTarget.value)} />;
}

export function maskFunctionOptions(functions: string[] = [], includeNone = false) {
  const values = new Set([...(includeNone ? ['NONE'] : []), ...FALLBACK_MASK_FUNCTIONS, ...functions]);
  return [...values].filter(Boolean).map((value) => ({ value, label: value }));
}

export function maskPayloadFromDrafts(drafts: Record<string, MaskDraft>, enabledOnly = false) {
  return Object.entries(drafts)
    .filter(([, draft]) => draft.function && draft.function !== 'NONE' && (!enabledOnly || draft.enabled))
    .map(([fieldPath, draft]) => ({
      fieldPath,
      path: fieldPath,
      function: draft.function,
      param1: draft.param1 || null,
      param2: draft.param2 || null
    }));
}

export function ActionRow({ children }: { children: ReactNode }) {
  return (
    <Group gap="xs" justify="flex-end" className="mf-action-row">
      {children}
    </Group>
  );
}

type TinyButtonProps = {
  children?: ReactNode;
  variant?: string;
  color?: string;
  disabled?: boolean;
  loading?: boolean;
  leftSection?: ReactNode;
  onClick?: () => void;
};

export function TinyButton({ variant = 'light', ...props }: TinyButtonProps) {
  return <Button size="compact-xs" variant={variant} {...props} />;
}
