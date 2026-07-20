'use client';

import { useMemo, useState, type ReactNode } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Paper,
  Progress,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  ThemeIcon,
  Tooltip
} from '@mantine/core';
import {
  IconAlertTriangle,
  IconCircleCheck,
  IconDatabase,
  IconLoader2,
  IconRegex,
  IconSearch,
  IconShieldSearch,
  IconTrash,
  IconX,
  IconZoomIn,
  IconZoomOut
} from '@tabler/icons-react';

import type {
  DiscoveryColumnReviewRow,
  DiscoveryFinding,
  DiscoveryGraph,
  DiscoveryGraphEdge,
  DiscoveryGraphNode,
  DiscoveryJob,
  ManualDraft,
  PiiPattern
} from './types';
import type { MaskingPolicy, MaskingRule } from '@/lib/types';
import {
  compatibleFunctions,
  defaultFunctionForPii,
  discoveryJobLive,
  paramLabels,
  paramOptions,
  shortParamLabel,
  statusTone
} from './utils';
import { defaultMaskParamsForMap } from '@/features/masking/utils';
import { isLookupOptionsFunction, LookupOptionsBuilder } from '@/features/masking/components';

export function MetricCard({
  label,
  value,
  detail,
  tone
}: {
  label: string;
  value: string | number;
  detail: string;
  tone?: 'good' | 'warn';
}) {
  return (
    <Paper className={`pii-metric-card ${tone ? `is-${tone}` : ''}`} p="md">
      <Text size="xs" tt="uppercase" fw={850} c="dimmed">
        {label}
      </Text>
      <Text className="pii-metric-value">{value}</Text>
      <Text size="sm" c="dimmed">
        {detail}
      </Text>
    </Paper>
  );
}

export function liveScanPresentation(job: DiscoveryJob) {
  const tables = job.tables || [];
  const status = String(job.status || 'PENDING').toUpperCase();
  const invalidZeroTableCompletion = status === 'COMPLETED' && Number(job.totalTables || tables.length || 0) === 0;
  const displayStatus = invalidZeroTableCompletion ? 'FAILED' : status;
  return {
    tables,
    invalidZeroTableCompletion,
    displayStatus,
    percent: invalidZeroTableCompletion ? 0 : clamp(job.percent || 0),
    running: discoveryJobLive(displayStatus)
  };
}

export function LiveScanPanel({ job, actions }: { job: DiscoveryJob | null; actions?: ReactNode }) {
  if (!job) {
    return (
      <Paper className="pii-live-empty" p="xl">
        <ThemeIcon variant="light" color="gray" size={44}>
          <IconShieldSearch size={24} />
        </ThemeIcon>
        <div>
          <Text fw={760}>No active scan yet</Text>
          <Text c="dimmed" size="sm">
            Select a data source and schema, then start a scan to watch table-by-table progress here.
          </Text>
        </div>
        {actions ? <Group gap={6} wrap="wrap" className="pii-live-board-actions">{actions}</Group> : null}
      </Paper>
    );
  }

  const { tables, invalidZeroTableCompletion, displayStatus, percent, running } = liveScanPresentation(job);

  return (
    <Paper className="pii-live-board" p="md">
        <div className="pii-live-board-head">
          <div>
            <Group gap="xs">
              <StatusBadge status={displayStatus} />
              <Text fw={780}>{invalidZeroTableCompletion ? 'Scan rejected' : running ? 'Scan in progress' : displayStatus === 'COMPLETED' ? 'Scan complete' : 'Scan status'}</Text>
            </Group>
            <Text size="sm" c="dimmed" mt={4}>
              {invalidZeroTableCompletion
                ? 'This scan had no scannable tables and cannot be treated as complete. Choose another schema and run again.'
                : job.message || 'Discovery job is queued.'}
            </Text>
          </div>
          <div className="pii-live-board-controls">
            {actions ? <Group gap={6} wrap="wrap" justify="flex-end" className="pii-live-board-actions">{actions}</Group> : null}
            <Text className="pii-live-percent">{percent}%</Text>
          </div>
        </div>
        <Progress value={percent} mt="md" radius="xl" size="lg" />
        <Group grow mt="md" gap="sm">
          <LiveStat label="Tables" value={`${job.completedTables || 0}/${job.totalTables || tables.length || 0}`} />
          <LiveStat label="Findings" value={job.findings || 0} />
          <LiveStat label="Current table" value={job.currentTable || 'None'} mono />
          <LiveStat label="Current column" value={job.currentColumn || 'None'} mono />
        </Group>
        {job.error ? <div className="pii-live-error">{job.error}</div> : null}
        <div className="pii-table-progress-grid">
          {tables.length ? (
            tables.map((table) => (
              <div key={table.tableName} className={`pii-table-progress-card is-${String(table.status || '').toLowerCase()}`}>
                <Group justify="space-between" gap="xs" wrap="nowrap">
                  <Text fw={760} className="pii-truncate">
                    {table.tableName}
                  </Text>
                  <StatusBadge status={table.status} />
                </Group>
                <Progress value={clamp(table.percent || 0)} size="sm" mt={8} />
                <Group justify="space-between" mt={6} gap="xs">
                  <Text size="xs" c="dimmed">
                    {table.scannedColumns || 0}/{table.totalColumns || 0} columns
                  </Text>
                  <Text size="xs" fw={760}>
                    {table.findings || 0} finding{table.findings === 1 ? '' : 's'}
                  </Text>
                </Group>
                {table.currentColumn ? (
                  <Text size="xs" c="dimmed" mt={4} className="pii-mono-line">
                    {table.currentColumn}
                  </Text>
                ) : null}
              </div>
            ))
          ) : (
            <div className="pii-live-preparing">Preparing table list...</div>
          )}
        </div>
    </Paper>
  );
}

export function ScanHistoryPanel({ history }: { history: DiscoveryJob[] }) {
  return <Paper className="pii-panel pii-history-panel" p={0}>
    <div className="pii-panel-head">
      <div><Text fw={760}>Discovery run history</Text><Text size="sm" c="dimmed">Completed and attempted scans for this source context.</Text></div>
      <Badge variant="light">{history.length} run{history.length === 1 ? '' : 's'}</Badge>
    </div>
    <div className="pii-history-list pii-history-workspace-list">
      {history.map((item) => (
        <div key={item.jobId} className="pii-history-row">
          <div className="pii-history-run-copy">
            <Text fw={720} size="sm" className="pii-mono-line">{item.jobId}</Text>
            <Text size="xs" c="dimmed">{dateTime(item.startedAt)}</Text>
          </div>
          <Text size="sm">{item.selectedTypes?.length ? `${item.selectedTypes.length} selected types` : 'All PII types'}</Text>
          <Text size="sm">{item.selectedTables?.length ? `${item.selectedTables.length} focused tables` : 'All tables'}</Text>
          <div><StatusBadge status={item.status} /><Progress value={clamp(item.percent || 0)} size="xs" mt={6} /></div>
          <Text size="sm" fw={700}>{item.findings || 0} findings</Text>
          <Text size="sm" c="dimmed">{item.completedTables || 0}/{item.totalTables || 0} tables</Text>
        </div>
      ))}
      {!history.length ? <div className="pii-empty-small">No scan history for this context.</div> : null}
    </div>
  </Paper>;
}

export function PolicyRulesWorkspace({ policy, rules, loading }: { policy: MaskingPolicy; rules: MaskingRule[]; loading: boolean }) {
  return <Paper className="pii-panel" p={0}>
    <div className="pii-panel-head">
      <div><Group gap="xs"><Text fw={780}>{policy.name}</Text><Badge variant="light">Existing policy</Badge></Group><Text size="sm" c="dimmed">Read-only policy evidence. Edit the rule set in Masking Policies.</Text></div>
      <Badge variant="light">{rules.length} rule{rules.length === 1 ? '' : 's'}</Badge>
    </div>
    {loading ? <div className="pii-empty-state"><Loader size="sm" /><Text>Loading policy rules...</Text></div> : rules.length ? <div className="pii-policy-rule-list">
      <div className="pii-policy-rule-head"><span>Table</span><span>Column</span><span>Mask function</span><span>Parameters</span></div>
      {rules.map((rule) => <div className="pii-policy-rule-row" key={rule.id}>
        <Text size="sm" fw={700} className="pii-mono-line">{rule.tableName}</Text>
        <Text size="sm" fw={760} className="pii-mono-line">{rule.columnName}</Text>
        <Badge variant="light">{rule.function}</Badge>
        <Text size="xs" c="dimmed" className="pii-mono-line">{[rule.param1, rule.param2].filter(Boolean).join(' / ') || 'No parameters'}</Text>
      </div>)}
    </div> : <div className="pii-empty-state"><ThemeIcon variant="light" color="gray" size={40}><IconShieldSearch size={22} /></ThemeIcon><div><Text fw={760}>Policy has no rules</Text><Text size="sm" c="dimmed">Add rules in Masking Policies or select another policy.</Text></div></div>}
  </Paper>;
}

export function FindingsTable({
  rows,
  functions,
  updating,
  canManage,
  onUpdate
}: {
  rows: DiscoveryFinding[];
  functions: string[];
  updating: boolean;
  canManage: boolean;
  onUpdate: (id: number, body: Record<string, string | null>) => void;
}) {
  if (!rows.length) {
    return (
      <div className="pii-empty-state">
        <ThemeIcon variant="light" color="gray" size={40}>
          <IconSearch size={22} />
        </ThemeIcon>
        <div>
          <Text fw={760}>No findings match this view</Text>
          <Text c="dimmed" size="sm">
            Run a scan or loosen the current table, type, status, or search filters.
          </Text>
        </div>
      </div>
    );
  }
  return (
    <div className="pii-table-wrap">
      <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Column</Table.Th>
            <Table.Th>Type</Table.Th>
            <Table.Th>PII</Table.Th>
            <Table.Th>Confidence</Table.Th>
            <Table.Th>Mask</Table.Th>
            <Table.Th>Params</Table.Th>
            <Table.Th>Sample</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Decision</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row) => (
            <Table.Tr key={row.id}>
              <Table.Td>
                <Text size="sm" fw={760}>
                  <span className="pii-mono-muted">{row.tableName}</span>.{row.columnName}
                </Text>
              </Table.Td>
              <Table.Td className="pii-mono-muted">{row.dataType || '-'}</Table.Td>
              <Table.Td>
                <Badge color="blue" variant="light">
                  {row.piiType}
                </Badge>
              </Table.Td>
              <Table.Td>
                <Confidence value={row.confidence} />
              </Table.Td>
              <Table.Td>
                {canManage ? (
                  <Select
                    size="xs"
                    data={compatibleFunctions(functions, row.dataType, row.suggestedFunction)}
                    value={row.suggestedFunction || null}
                    onChange={(value) => {
                      if (!canManage || !value) return;
                      const params = defaultMaskParamsForMap(value, row.piiType);
                      onUpdate(row.id, {
                        suggestedFunction: value,
                        suggestedParam1: params.param1,
                        suggestedParam2: params.param2
                      });
                    }}
                    searchable
                    disabled={updating}
                    w={170}
                  />
                ) : <Text size="sm">{row.suggestedFunction || 'Not configured'}</Text>}
              </Table.Td>
              <Table.Td>
                {canManage ? (
                  <ParamEditors
                    fn={row.suggestedFunction}
                    param1={row.suggestedParam1 ?? ''}
                    param2={row.suggestedParam2 ?? ''}
                    onParam={(n, value) => {
                      if (!canManage) return;
                      onUpdate(row.id, n === 1 ? { suggestedParam1: value } : { suggestedParam2: value });
                    }}
                  />
                ) : <Text size="xs" c="dimmed">{[row.suggestedParam1, row.suggestedParam2].filter(Boolean).join(' / ') || 'No parameters'}</Text>}
              </Table.Td>
              <Table.Td className="pii-mono-muted">{row.sampleValue || ''}</Table.Td>
              <Table.Td>
                <StatusBadge status={row.status} />
              </Table.Td>
              <Table.Td>
                {canManage ? (
                  <Group gap={6} wrap="nowrap">
                    <Button size="xs" variant="light" onClick={() => { if (canManage) onUpdate(row.id, { status: 'APPROVED' }); }}>
                      Approve
                    </Button>
                    <Button size="xs" variant="subtle" color="red" onClick={() => { if (canManage) onUpdate(row.id, { status: 'REJECTED' }); }}>
                      Not PII
                    </Button>
                  </Group>
                ) : null}
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

export function ColumnReviewPanel({
  selectedTable,
  tableOptions,
  onTableChange,
  rows,
  loading,
  piiTypes,
  functions,
  manualDrafts,
  setManualDrafts,
  onUpdate,
  onManual,
  manualPending,
  canManage
}: {
  selectedTable: string | null;
  tableOptions: Array<{ value: string; label: string }>;
  onTableChange: (value: string | null) => void;
  rows: DiscoveryColumnReviewRow[];
  loading: boolean;
  piiTypes: string[];
  functions: string[];
  manualDrafts: Record<string, ManualDraft>;
  setManualDrafts: (updater: (current: Record<string, ManualDraft>) => Record<string, ManualDraft>) => void;
  onUpdate: (id: number, body: Record<string, string | null>) => void;
  onManual: (row: DiscoveryColumnReviewRow, draft: ManualDraft) => void;
  manualPending: boolean;
  canManage: boolean;
}) {
  const piiOptions = piiTypes.map((type) => ({ value: type, label: type }));
  return (
    <Paper className="pii-panel" p={0}>
      <div className="pii-panel-head">
        <div>
          <Text fw={760}>Full column review</Text>
          <Text size="sm" c="dimmed">
            Review every column in one table, including columns that were not automatically flagged.
          </Text>
        </div>
        <Select
          placeholder="Pick one table"
          data={tableOptions}
          value={selectedTable}
          onChange={onTableChange}
          searchable
          clearable
          w={260}
        />
      </div>
      {!selectedTable ? (
        <div className="pii-empty-state">
          <ThemeIcon variant="light" color="gray" size={40}>
            <IconDatabase size={22} />
          </ThemeIcon>
          <div>
            <Text fw={760}>Pick a table</Text>
            <Text c="dimmed" size="sm">
              Column review is intentionally table-level so manual PII decisions stay precise.
            </Text>
          </div>
        </div>
      ) : loading ? (
        <div className="pii-empty-state">
          <Loader size="sm" />
          <Text>Loading columns...</Text>
        </div>
      ) : (
        <div className="pii-table-wrap">
          <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Column</Table.Th>
                <Table.Th>Type</Table.Th>
                <Table.Th>PII type</Table.Th>
                <Table.Th>Mask</Table.Th>
                <Table.Th>Params</Table.Th>
                <Table.Th>Sample</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th>Action</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((row, index) => {
                const key = reviewKey(row, index);
                const hasFinding = Boolean(row.classificationId);
                const initialFunction = row.suggestedFunction || defaultFunctionForPii(row.piiType || 'MANUAL_PII');
                const initialParams = defaultMaskParamsForMap(initialFunction, row.piiType || 'MANUAL_PII');
                const draft = manualDrafts[key] || {
                  piiType: row.piiType || 'MANUAL_PII',
                  suggestedFunction: initialFunction,
                  suggestedParam1: row.suggestedParam1 ?? initialParams.param1 ?? '',
                  suggestedParam2: row.suggestedParam2 ?? initialParams.param2 ?? ''
                };
                const setDraft = (patch: Partial<ManualDraft>) => {
                  if (!canManage) return;
                  setManualDrafts((current) => ({
                    ...current,
                    [key]: { ...draft, ...patch }
                  }));
                };
                return (
                  <Table.Tr key={key}>
                    <Table.Td>
                      <Text fw={760}>{row.columnName}</Text>
                      {row.nullable ? (
                        <Text size="xs" c="dimmed">
                          nullable
                        </Text>
                      ) : null}
                    </Table.Td>
                    <Table.Td className="pii-mono-muted">{row.dataType || '-'}</Table.Td>
                    <Table.Td>
                      {hasFinding ? (
                        <Badge color="blue" variant="light">
                          {row.piiType}
                        </Badge>
                      ) : canManage ? (
                        <Select
                          size="xs"
                          data={piiOptions}
                          value={draft.piiType}
                          onChange={(value) => {
                            const piiType = value || 'MANUAL_PII';
                            const suggestedFunction = defaultFunctionForPii(piiType);
                            const params = defaultMaskParamsForMap(suggestedFunction, piiType);
                            setDraft({
                              piiType,
                              suggestedFunction,
                              suggestedParam1: params.param1 || '',
                              suggestedParam2: params.param2 || ''
                            });
                          }}
                          searchable
                          w={160}
                        />
                      ) : <Text size="sm" c="dimmed">Not classified</Text>}
                    </Table.Td>
                    <Table.Td>
                      {canManage ? (
                        <Select
                          size="xs"
                          data={compatibleFunctions(functions, row.dataType, hasFinding ? row.suggestedFunction : draft.suggestedFunction)}
                          value={hasFinding ? row.suggestedFunction || null : draft.suggestedFunction}
                          onChange={(value) => {
                            if (!canManage || !value) return;
                            if (hasFinding && row.classificationId) {
                              const params = defaultMaskParamsForMap(value, row.piiType);
                              onUpdate(row.classificationId, {
                                suggestedFunction: value,
                                suggestedParam1: params.param1,
                                suggestedParam2: params.param2
                              });
                            }
                            else {
                              const params = defaultMaskParamsForMap(value, draft.piiType);
                              setDraft({ suggestedFunction: value, suggestedParam1: params.param1 || '', suggestedParam2: params.param2 || '' });
                            }
                          }}
                          searchable
                          w={170}
                        />
                      ) : <Text size="sm">{row.suggestedFunction || 'Not configured'}</Text>}
                    </Table.Td>
                    <Table.Td>
                      {canManage ? (
                        hasFinding && row.classificationId ? (
                          <ParamEditors
                            fn={row.suggestedFunction}
                            param1={row.suggestedParam1 || ''}
                            param2={row.suggestedParam2 || ''}
                            onParam={(n, value) => {
                              if (!canManage) return;
                              onUpdate(row.classificationId as number, n === 1 ? { suggestedParam1: value } : { suggestedParam2: value });
                            }}
                          />
                        ) : (
                          <ParamEditors
                            fn={draft.suggestedFunction}
                            param1={draft.suggestedParam1}
                            param2={draft.suggestedParam2}
                            onParam={(n, value) => setDraft(n === 1 ? { suggestedParam1: value } : { suggestedParam2: value })}
                          />
                        )
                      ) : <Text size="xs" c="dimmed">{[row.suggestedParam1, row.suggestedParam2].filter(Boolean).join(' / ') || 'No parameters'}</Text>}
                    </Table.Td>
                    <Table.Td className="pii-mono-muted">{row.sampleValue || ''}</Table.Td>
                    <Table.Td>
                      <StatusBadge status={row.status || (hasFinding ? 'SUGGESTED' : 'NOT_PII')} />
                    </Table.Td>
                    <Table.Td>
                      {canManage && hasFinding && row.classificationId ? (
                        <Group gap={6} wrap="nowrap">
                          <Button size="xs" variant="light" onClick={() => { if (canManage) onUpdate(row.classificationId as number, { status: 'APPROVED' }); }}>
                            Approve
                          </Button>
                          <Button
                            size="xs"
                            variant="subtle"
                            color="red"
                            onClick={() => { if (canManage) onUpdate(row.classificationId as number, { status: 'REJECTED' }); }}
                          >
                            Not PII
                          </Button>
                        </Group>
                      ) : canManage ? (
                        <Button size="xs" loading={manualPending} onClick={() => { if (canManage) onManual(row, draft); }}>
                          Mark PII
                        </Button>
                      ) : null}
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </div>
      )}
    </Paper>
  );
}

export function FindingsWorkspaceTable({
  rows,
  functions,
  updating,
  canManage,
  onUpdate
}: {
  rows: DiscoveryFinding[];
  functions: string[];
  updating: boolean;
  canManage: boolean;
  onUpdate: (id: number, body: Record<string, string | null>) => void;
}) {
  const [tableFilter, setTableFilter] = useState<string | null>(null);
  const [editing, setEditing] = useState<{
    row: DiscoveryFinding;
    fn: string;
    param1: string;
    param2: string;
  } | null>(null);
  const tableOptions = useMemo(
    () => Array.from(new Set(rows.map((row) => row.tableName).filter(Boolean)))
      .sort((left, right) => left.localeCompare(right))
      .map((table) => ({ value: table, label: table })),
    [rows]
  );
  const visibleRows = useMemo(
    () => tableFilter ? rows.filter((row) => row.tableName === tableFilter) : rows,
    [rows, tableFilter]
  );

  if (!rows.length) {
    return <div className="pii-empty-state"><ThemeIcon variant="light" color="gray" size={40}><IconSearch size={22} /></ThemeIcon><div><Text fw={760}>No findings match this view</Text><Text c="dimmed" size="sm">Run a scan or loosen the current table, type, status, or search filters.</Text></div></div>;
  }

  const openEditor = (row: DiscoveryFinding) => {
    if (!canManage) return;
    setEditing({
      row,
      fn: row.suggestedFunction || defaultFunctionForPii(row.piiType),
      param1: row.suggestedParam1 || '',
      param2: row.suggestedParam2 || ''
    });
  };

  return <>
    <div className="pii-review-toolbar">
      <div>
        <Text fw={760}>Discovered fields</Text>
        <Text size="xs" c="dimmed">{visibleRows.length} of {rows.length} findings shown</Text>
      </div>
      <Select
        aria-label="Filter findings by table"
        placeholder="All tables"
        leftSection={<IconDatabase size={15} />}
        data={tableOptions}
        value={tableFilter}
        onChange={setTableFilter}
        searchable
        clearable
        nothingFoundMessage="No matching table"
        className="pii-table-filter"
      />
    </div>
    <div className="pii-review-list pii-findings-list">
      <div className="pii-review-list-head"><span>Table</span><span>Column</span><span>Classification</span><span>Masking</span><span>Status</span><span>Decision</span></div>
      {visibleRows.map((row) => (
        <div className="pii-review-list-row" key={row.id}>
          <Text size="sm" fw={700} className="pii-review-table" title={row.tableName}>{row.tableName}</Text>
          <div className="pii-review-primary">
            <Text size="sm" fw={760} className="pii-review-column" title={row.columnName}>{row.columnName}</Text>
            <Text size="xs" c="dimmed" className="pii-review-sample">{row.dataType || 'type unknown'}{row.sampleValue ? ` - sample ${row.sampleValue}` : ''}</Text>
          </div>
          <div><Badge color="blue" variant="light">{row.piiType}</Badge><Confidence value={row.confidence} /></div>
          <div className="pii-review-mask"><Text size="sm" fw={700}>{row.suggestedFunction || 'Not configured'}</Text><Text size="xs" c="dimmed" truncate="end">{[row.suggestedParam1, row.suggestedParam2].filter(Boolean).join(' / ') || 'No parameters'}</Text>{canManage ? <Button size="compact-xs" variant="subtle" onClick={() => openEditor(row)}>Configure</Button> : null}</div>
          <StatusIcon status={row.status} />
          {canManage ? (
            <Group gap={5} wrap="nowrap" justify="flex-end">
              <Tooltip label="Approve as PII"><ActionIcon aria-label={`Approve ${row.tableName}.${row.columnName} as PII`} variant="light" color="green" disabled={updating} onClick={() => { if (canManage) onUpdate(row.id, { status: 'APPROVED' }); }}><IconCircleCheck size={17} /></ActionIcon></Tooltip>
              <Tooltip label="Mark as not PII"><ActionIcon aria-label={`Mark ${row.tableName}.${row.columnName} as not PII`} variant="subtle" color="red" disabled={updating} onClick={() => { if (canManage) onUpdate(row.id, { status: 'REJECTED' }); }}><IconX size={17} /></ActionIcon></Tooltip>
            </Group>
          ) : <span />}
        </div>
      ))}
      {!visibleRows.length ? <div className="pii-empty-small">No findings match the selected table.</div> : null}
    </div>

    <Modal opened={canManage && Boolean(editing)} onClose={() => setEditing(null)} title={editing ? `Configure ${editing.row.tableName}.${editing.row.columnName}` : 'Configure masking'} size="md" centered>
      {editing ? <Stack gap="sm">
        <Select
          label="Masking function"
          data={compatibleFunctions(functions, editing.row.dataType, editing.fn)}
          value={editing.fn}
          searchable
          onChange={(value) => {
            if (!value) return;
            const params = defaultMaskParamsForMap(value, editing.row.piiType);
            setEditing((current) => current ? { ...current, fn: value, param1: params.param1 || '', param2: params.param2 || '' } : current);
          }}
        />
        <ParamEditors
          fn={editing.fn}
          param1={editing.param1}
          param2={editing.param2}
          onParam={(n, value) => setEditing((current) => current ? { ...current, [n === 1 ? 'param1' : 'param2']: value } : current)}
        />
        <Group justify="flex-end"><Button variant="default" onClick={() => setEditing(null)}>Discard</Button><Button loading={updating} disabled={!canManage} onClick={() => { if (!canManage) return; onUpdate(editing.row.id, { suggestedFunction: editing.fn, suggestedParam1: editing.param1 || null, suggestedParam2: editing.param2 || null }); setEditing(null); }}>Apply recommendation</Button></Group>
      </Stack> : null}
    </Modal>
  </>;
}

export function ColumnReviewWorkspace({
  selectedTable,
  tableOptions,
  onTableChange,
  rows,
  loading,
  piiTypes,
  functions,
  manualDrafts,
  setManualDrafts,
  onUpdate,
  onManual,
  manualPending,
  canManage
}: {
  selectedTable: string | null;
  tableOptions: Array<{ value: string; label: string }>;
  onTableChange: (value: string | null) => void;
  rows: DiscoveryColumnReviewRow[];
  loading: boolean;
  piiTypes: string[];
  functions: string[];
  manualDrafts: Record<string, ManualDraft>;
  setManualDrafts: (updater: (current: Record<string, ManualDraft>) => Record<string, ManualDraft>) => void;
  onUpdate: (id: number, body: Record<string, string | null>) => void;
  onManual: (row: DiscoveryColumnReviewRow, draft: ManualDraft) => void;
  manualPending: boolean;
  canManage: boolean;
}) {
  const [editing, setEditing] = useState<{ key: string; row: DiscoveryColumnReviewRow; draft: ManualDraft; hasFinding: boolean } | null>(null);
  const piiOptions = piiTypes.map((type) => ({ value: type, label: type }));

  const openEditor = (row: DiscoveryColumnReviewRow, index: number) => {
    if (!canManage) return;
    const key = reviewKey(row, index);
    const hasFinding = Boolean(row.classificationId);
    const piiType = row.piiType || 'MANUAL_PII';
    const fn = row.suggestedFunction || defaultFunctionForPii(piiType);
    const params = defaultMaskParamsForMap(fn, piiType);
    setEditing({
      key,
      row,
      hasFinding,
      draft: manualDrafts[key] || {
        piiType,
        suggestedFunction: fn,
        suggestedParam1: row.suggestedParam1 ?? params.param1 ?? '',
        suggestedParam2: row.suggestedParam2 ?? params.param2 ?? ''
      }
    });
  };

  return <Paper className="pii-panel" p={0}>
    <div className="pii-panel-head">
      <div><Text fw={760}>Full column review</Text><Text size="sm" c="dimmed">Review every column in one table. Detailed PII and masking parameters open only when needed.</Text></div>
      <Select placeholder="Pick one table" data={tableOptions} value={selectedTable} onChange={onTableChange} searchable clearable w={280} />
    </div>
    {!selectedTable ? <div className="pii-empty-state"><ThemeIcon variant="light" color="gray" size={40}><IconDatabase size={22} /></ThemeIcon><div><Text fw={760}>Pick a table</Text><Text c="dimmed" size="sm">Choose one table to review all columns and add precise manual classifications.</Text></div></div>
      : loading ? <div className="pii-empty-state"><Loader size="sm" /><Text>Loading columns...</Text></div>
        : <div className="pii-review-list pii-column-review-list">
          <div className="pii-review-list-head"><span>Column</span><span>Classification</span><span>Masking</span><span>Status</span><span>Action</span></div>
          {rows.map((row, index) => {
            const hasFinding = Boolean(row.classificationId);
            return <div className="pii-review-list-row" key={reviewKey(row, index)}>
              <div className="pii-review-primary"><Text size="sm" fw={760}>{row.columnName}</Text><Text size="xs" c="dimmed" className="pii-review-sample">{row.dataType || 'type unknown'}{row.nullable ? ' - nullable' : ''}{row.sampleValue ? ` - sample ${row.sampleValue}` : ''}</Text></div>
              <div>{hasFinding ? <Badge color="blue" variant="light">{row.piiType}</Badge> : <Text size="sm" c="dimmed">Not classified</Text>}</div>
              <div className="pii-review-mask"><Text size="sm" fw={700}>{row.suggestedFunction || (hasFinding ? 'Not configured' : 'Configure to classify')}</Text>{canManage ? <Button size="compact-xs" variant="subtle" onClick={() => openEditor(row, index)}>Configure</Button> : null}</div>
              <StatusBadge status={row.status || (hasFinding ? 'SUGGESTED' : 'NOT_PII')} />
              {canManage ? <Group gap={5} wrap="nowrap" justify="flex-end">{hasFinding && row.classificationId ? <><Button size="compact-xs" variant="light" onClick={() => { if (canManage) onUpdate(row.classificationId as number, { status: 'APPROVED' }); }}>Approve</Button><Button size="compact-xs" variant="subtle" color="red" onClick={() => { if (canManage) onUpdate(row.classificationId as number, { status: 'REJECTED' }); }}>Not PII</Button></> : <Button size="compact-xs" onClick={() => openEditor(row, index)}>Mark PII</Button>}</Group> : <span />}
            </div>;
          })}
        </div>}

    <Modal opened={canManage && Boolean(editing)} onClose={() => setEditing(null)} title={editing ? `Configure ${editing.row.columnName}` : 'Configure column'} size="md" centered>
      {editing ? <Stack gap="sm">
        <Select
          label="PII type"
          data={piiOptions}
          value={editing.draft.piiType}
          searchable
          disabled={editing.hasFinding}
          onChange={(value) => {
            const piiType = value || 'MANUAL_PII';
            const suggestedFunction = defaultFunctionForPii(piiType);
            const params = defaultMaskParamsForMap(suggestedFunction, piiType);
            setEditing((current) => current ? { ...current, draft: { ...current.draft, piiType, suggestedFunction, suggestedParam1: params.param1 || '', suggestedParam2: params.param2 || '' } } : current);
          }}
        />
        <Select
          label="Masking function"
          data={compatibleFunctions(functions, editing.row.dataType, editing.draft.suggestedFunction)}
          value={editing.draft.suggestedFunction}
          searchable
          onChange={(value) => {
            if (!value) return;
            const params = defaultMaskParamsForMap(value, editing.draft.piiType);
            setEditing((current) => current ? { ...current, draft: { ...current.draft, suggestedFunction: value, suggestedParam1: params.param1 || '', suggestedParam2: params.param2 || '' } } : current);
          }}
        />
        <ParamEditors
          fn={editing.draft.suggestedFunction}
          param1={editing.draft.suggestedParam1}
          param2={editing.draft.suggestedParam2}
          onParam={(n, value) => setEditing((current) => current ? { ...current, draft: { ...current.draft, [n === 1 ? 'suggestedParam1' : 'suggestedParam2']: value } } : current)}
        />
        <Group justify="flex-end"><Button variant="default" onClick={() => setEditing(null)}>Discard</Button><Button loading={manualPending} disabled={!canManage} onClick={() => {
          if (!canManage) return;
          if (editing.hasFinding && editing.row.classificationId) onUpdate(editing.row.classificationId, { suggestedFunction: editing.draft.suggestedFunction, suggestedParam1: editing.draft.suggestedParam1 || null, suggestedParam2: editing.draft.suggestedParam2 || null });
          else {
            setManualDrafts((current) => ({ ...current, [editing.key]: editing.draft }));
            onManual(editing.row, editing.draft);
          }
          setEditing(null);
        }}>{editing.hasFinding ? 'Apply configuration' : 'Mark as PII'}</Button></Group>
      </Stack> : null}
    </Modal>
  </Paper>;
}

export function ImpactMapPanel({
  graph,
  loading
}: {
  graph: { nodes?: DiscoveryGraphNode[]; edges?: Array<Record<string, unknown>>; traversalMode?: string | null; cycles?: unknown[] };
  loading: boolean;
}) {
  const nodes = graph.nodes || [];
  const edges = graph.edges || [];
  const piiNodes = nodes.filter((node) => Number(node.piiCount || 0) > 0).sort((a, b) => Number(b.piiCount || 0) - Number(a.piiCount || 0));
  if (loading) {
    return (
      <Paper className="pii-panel" p="xl">
        <Group>
          <Loader size="sm" />
          <Text>Building impact map...</Text>
        </Group>
      </Paper>
    );
  }
  return (
    <section className="pii-impact-grid">
      <Paper className="pii-panel" p="md">
        <Group justify="space-between" align="flex-start" mb="md">
          <div>
            <Text fw={760}>PII table impact</Text>
            <Text size="sm" c="dimmed">
              Tables with discovered PII, sorted by risk concentration.
            </Text>
          </div>
          <Badge variant="light" color={graph.traversalMode === 'CYCLE_GUARDED' ? 'yellow' : 'green'}>
            {graph.traversalMode || 'UNKNOWN'}
          </Badge>
        </Group>
        <div className="pii-impact-node-grid">
          {piiNodes.length ? (
            piiNodes.map((node) => (
              <div key={node.id} className="pii-impact-node">
                <Group justify="space-between" gap="sm">
                  <Text fw={760}>{node.label || node.id}</Text>
                  <Badge color="blue" variant="light">
                    {node.piiCount || 0} PII
                  </Badge>
                </Group>
                <Stack gap={4} mt="sm">
                  {(node.piiColumns || []).slice(0, 6).map((column) => (
                    <Text key={`${node.id}-${column.column}-${column.piiType}`} size="xs" className="pii-mono-line">
                      {column.column} - {column.piiType} - {column.function || 'mask TBD'}
                    </Text>
                  ))}
                  {(node.piiColumns || []).length > 6 ? (
                    <Text size="xs" c="dimmed">
                      +{(node.piiColumns || []).length - 6} more
                    </Text>
                  ) : null}
                </Stack>
              </div>
            ))
          ) : (
            <div className="pii-empty-small">No PII-bearing tables in the current review scope.</div>
          )}
        </div>
      </Paper>
      <Paper className="pii-panel" p={0}>
        <div className="pii-panel-head">
          <div>
            <Text fw={760}>Relationship edges</Text>
            <Text size="sm" c="dimmed">
              Parent-to-child joins used to understand downstream exposure.
            </Text>
          </div>
          <Badge variant="light" color="gray">
            {edges.length} edges
          </Badge>
        </div>
        <div className="pii-edge-list">
          {edges.slice(0, 80).map((edge, index) => (
            <div key={String(edge.id || index)} className="pii-edge-row">
              <Text size="sm" fw={720}>
                {String(edge.from || '?')} {'->'} {String(edge.to || '?')}
              </Text>
              <Text size="xs" c="dimmed" className="pii-mono-line">
                {String(edge.label || `${edge.fkColumn || ''} -> ${edge.pkColumn || ''}`)}
              </Text>
            </div>
          ))}
          {edges.length > 80 ? <div className="pii-empty-small">+{edges.length - 80} more edges</div> : null}
          {!edges.length ? <div className="pii-empty-small">No FK relationships returned for this schema.</div> : null}
        </div>
      </Paper>
    </section>
  );
}

/* ---- Interactive relationship / impact diagram (node-link ER view) ---- */

const ERD_NODE_W = 214;
const ERD_COL_GAP = 124;
const ERD_ROW_GAP = 28;
const ERD_PAD = 28;
const ERD_HDR = 34;
const ERD_LINE = 16;
const ERD_MAX_COLS = 5;

type ErdPos = { x: number; y: number; h: number };

function erdNodeHeight(node: DiscoveryGraphNode) {
  const count = (node.piiColumns || []).length;
  const shown = Math.min(count, ERD_MAX_COLS) + (count > ERD_MAX_COLS ? 1 : 0);
  return ERD_HDR + (shown === 0 ? 26 : shown * (ERD_LINE * 2 + 4) + 12);
}

/* longest-path layering: FK parents on the left, children to the right */
function erdLevels(nodes: DiscoveryGraphNode[], edges: DiscoveryGraphEdge[]) {
  const ids = nodes.map((node) => node.id);
  const level: Record<string, number> = Object.fromEntries(ids.map((id) => [id, 0]));
  const real = edges.filter((edge) => edge.from && edge.to && edge.from !== edge.to);
  for (let pass = 0; pass < ids.length; pass += 1) {
    let changed = false;
    for (const edge of real) {
      const from = edge.from as string;
      const to = edge.to as string;
      if (level[from] === undefined || level[to] === undefined) continue;
      if (level[to] < level[from] + 1 && level[from] + 1 < ids.length) {
        level[to] = level[from] + 1;
        changed = true;
      }
    }
    if (!changed) break;
  }
  return level;
}

function erdReachable(start: string, edges: DiscoveryGraphEdge[], direction: 'down' | 'up') {
  const seen = new Set<string>();
  const queue = [start];
  while (queue.length) {
    const current = queue.shift() as string;
    for (const edge of edges) {
      if (edge.from === edge.to) continue;
      const next = direction === 'down' ? (edge.from === current ? edge.to : null) : edge.to === current ? edge.from : null;
      if (next && !seen.has(next)) {
        seen.add(next);
        queue.push(next);
      }
    }
  }
  seen.delete(start);
  return seen;
}

export function ImpactDiagramPanel({ graph, loading }: { graph: DiscoveryGraph; loading: boolean }) {
  const nodes = useMemo(() => graph.nodes || [], [graph.nodes]);
  const edges = useMemo(() => graph.edges || [], [graph.edges]);
  const [selected, setSelected] = useState<string | null>(null);
  const [zoom, setZoom] = useState(1);

  const cycleEdgeIds = useMemo(() => new Set(graph.cycleEdgeIds || []), [graph.cycleEdgeIds]);
  const cycleTables = useMemo(() => {
    const tables = new Set<string>();
    for (const cycle of graph.cycles || []) {
      for (const table of ((cycle as { tables?: string[] }).tables || [])) tables.add(table);
    }
    return tables;
  }, [graph.cycles]);

  const layout = useMemo(() => {
    const levels = erdLevels(nodes, edges);
    const byLevel: Record<number, DiscoveryGraphNode[]> = {};
    for (const node of nodes) (byLevel[levels[node.id] ?? 0] ||= []).push(node);
    const levelKeys = Object.keys(byLevel).map(Number).sort((a, b) => a - b);
    const pos: Record<string, ErdPos> = {};
    let width = 0;
    let height = 0;
    levelKeys.forEach((lv, columnIndex) => {
      let y = ERD_PAD;
      const x = ERD_PAD + columnIndex * (ERD_NODE_W + ERD_COL_GAP);
      for (const node of byLevel[lv]) {
        const h = erdNodeHeight(node);
        pos[node.id] = { x, y, h };
        y += h + ERD_ROW_GAP;
      }
      width = Math.max(width, x + ERD_NODE_W);
      height = Math.max(height, y);
    });
    return { pos, width: width + ERD_PAD, height: height + ERD_PAD };
  }, [nodes, edges]);

  const descendants = useMemo(() => (selected ? erdReachable(selected, edges, 'down') : new Set<string>()), [selected, edges]);
  const ancestors = useMemo(() => (selected ? erdReachable(selected, edges, 'up') : new Set<string>()), [selected, edges]);

  if (loading) {
    return (
      <Paper className="pii-panel" p="xl">
        <Group>
          <Loader size="sm" />
          <Text>Building relationship diagram...</Text>
        </Group>
      </Paper>
    );
  }

  if (!nodes.length) {
    return (
      <Paper className="pii-panel" p="xl">
        <div className="pii-empty-small">No tables in the current review scope — run a scan first.</div>
      </Paper>
    );
  }

  const inFocus = (id: string) => id === selected || descendants.has(id) || ancestors.has(id);
  const selectedNode = nodes.find((node) => node.id === selected) || null;
  const childrenOf = (table: string) => edges.filter((edge) => edge.from === table && edge.to !== table);
  const parentsOf = (table: string) => edges.filter((edge) => edge.to === table && edge.from !== table);
  const piiOf = (table: string) => nodes.find((node) => node.id === table)?.piiColumns || [];

  return (
    <Paper className="pii-panel pii-diagram-panel" p="md">
      <Group justify="space-between" align="flex-start" mb="sm">
        <div>
          <Text fw={760}>Relationship impact diagram</Text>
          <Text size="sm" c="dimmed">
            Tables laid out parents-first by FK depth. Click a table to trace what masking it touches upstream and downstream.
          </Text>
        </div>
        <Badge variant="light" color={graph.traversalMode === 'CYCLE_GUARDED' ? 'yellow' : 'green'}>
          {graph.traversalMode || 'UNKNOWN'}
        </Badge>
      </Group>

      <div className="pii-diagram-toolbar">
        <Group gap={6}>
          <Tooltip label="Zoom out" withArrow>
            <ActionIcon variant="default" onClick={() => setZoom((z) => Math.max(0.5, Math.round((z - 0.1) * 10) / 10))}>
              <IconZoomOut size={16} />
            </ActionIcon>
          </Tooltip>
          <Tooltip label="Zoom in" withArrow>
            <ActionIcon variant="default" onClick={() => setZoom((z) => Math.min(1.8, Math.round((z + 0.1) * 10) / 10))}>
              <IconZoomIn size={16} />
            </ActionIcon>
          </Tooltip>
          <Text size="xs" c="dimmed" ml={4}>
            {Math.round(zoom * 100)}%
          </Text>
        </Group>
        <Group gap="md" className="pii-diagram-legend">
          <span className="pii-legend-chip is-selected">Selected</span>
          <span className="pii-legend-chip is-ancestor">Parent chain</span>
          <span className="pii-legend-chip is-descendant">Child chain</span>
          <span className="pii-legend-chip is-cycle">In cycle</span>
        </Group>
      </div>

      <div className="pii-diagram-canvas">
        <svg
          role="img"
          aria-label="Table relationship diagram"
          width={layout.width * zoom}
          height={layout.height * zoom}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          xmlns="http://www.w3.org/2000/svg"
        >
          <defs>
            <marker id="pii-erd-arrow" markerWidth="9" markerHeight="9" refX="8" refY="4.5" orient="auto">
              <path d="M0,0 L9,4.5 L0,9 z" fill="var(--forge-border)" />
            </marker>
          </defs>

          {edges.map((edge, index) => {
            const a = layout.pos[edge.from || ''];
            const b = layout.pos[edge.to || ''];
            if (!a || !b) return null;
            const x1 = a.x + ERD_NODE_W;
            const y1 = a.y + Math.min(a.h / 2, 40);
            const x2 = b.x;
            const y2 = b.y + Math.min(b.h / 2, 40);
            const mx = (x1 + x2) / 2;
            const isCycle = edge.id ? cycleEdgeIds.has(edge.id) : false;
            const hot =
              Boolean(selected) &&
              inFocus(edge.from || '') &&
              inFocus(edge.to || '') &&
              !(ancestors.has(edge.from || '') && descendants.has(edge.to || ''));
            const cls = `pii-erd-edge${hot ? ' is-hot' : ''}${isCycle ? ' is-cycle' : ''}${selected && !hot ? ' is-dim' : ''}`;
            return (
              <g key={String(edge.id || index)}>
                <path className={cls} d={`M ${x1} ${y1} C ${mx} ${y1}, ${mx} ${y2}, ${x2 - 6} ${y2}`} markerEnd="url(#pii-erd-arrow)" />
                {edge.fkColumn || edge.label ? (
                  <text className={`pii-erd-edge-label${hot ? ' is-hot' : ''}`} x={mx} y={(y1 + y2) / 2 - 6} textAnchor="middle">
                    {edge.fkColumn || edge.label}
                  </text>
                ) : null}
              </g>
            );
          })}

          {nodes.map((node) => {
            const p = layout.pos[node.id];
            if (!p) return null;
            const cols = node.piiColumns || [];
            const shown = cols.slice(0, ERD_MAX_COLS);
            const state = node.id === selected ? 'is-selected' : descendants.has(node.id) ? 'is-descendant' : ancestors.has(node.id) ? 'is-ancestor' : '';
            const dim = selected && !inFocus(node.id) ? ' is-dim' : '';
            const cycle = cycleTables.has(node.id) ? ' is-cycle' : '';
            return (
              <g
                key={node.id}
                className={`pii-erd-node ${state}${cycle}${dim}`}
                role="button"
                tabIndex={0}
                onClick={() => setSelected((prev) => (prev === node.id ? null : node.id))}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    setSelected((prev) => (prev === node.id ? null : node.id));
                  }
                }}
              >
                <rect className="pii-erd-box" x={p.x} y={p.y} width={ERD_NODE_W} height={p.h} rx={9} />
                <rect className="pii-erd-hdr" x={p.x + 1} y={p.y + 1} width={ERD_NODE_W - 2} height={ERD_HDR - 4} rx={8} />
                <text className="pii-erd-title" x={p.x + 12} y={p.y + 21}>
                  {node.label || node.id}
                </text>
                <text className="pii-erd-count" x={p.x + ERD_NODE_W - 12} y={p.y + 21} textAnchor="end">
                  {cols.length} PII
                </text>
                {cols.length === 0 ? (
                  <text className="pii-erd-nopii" x={p.x + 12} y={p.y + ERD_HDR + 18}>
                    no PII detected
                  </text>
                ) : (
                  shown.map((column, i) => {
                    const cy = p.y + ERD_HDR + 10 + i * (ERD_LINE * 2 + 4);
                    const mark = column.status === 'APPROVED' ? ' ✓' : column.status === 'REJECTED' ? ' ✕' : '';
                    return (
                      <g key={`${node.id}-${column.column}-${i}`}>
                        <text className="pii-erd-col" x={p.x + 12} y={cy + ERD_LINE - 4}>
                          {column.column} <tspan className="pii-erd-coltype">[{column.piiType}]</tspan>
                        </text>
                        <text className="pii-erd-colfn" x={p.x + 20} y={cy + ERD_LINE * 2 - 4}>
                          {(column.function || 'mask TBD') + mark}
                        </text>
                      </g>
                    );
                  })
                )}
                {cols.length > ERD_MAX_COLS ? (
                  <text className="pii-erd-colfn" x={p.x + 12} y={p.y + ERD_HDR + 10 + ERD_MAX_COLS * (ERD_LINE * 2 + 4) + ERD_LINE - 4}>
                    + {cols.length - ERD_MAX_COLS} more PII column(s)
                  </text>
                ) : null}
              </g>
            );
          })}
        </svg>
      </div>

      {selectedNode ? (
        <div className="pii-diagram-detail">
          <Group justify="space-between" mb="xs">
            <Text fw={760}>Rule impact — {selectedNode.label || selectedNode.id}</Text>
            <Button size="compact-xs" variant="subtle" onClick={() => setSelected(null)}>
              Clear
            </Button>
          </Group>
          <div className="pii-diagram-detail-grid">
            <div className="pii-impact-node">
              <Text size="xs" fw={800} tt="uppercase" c="dimmed" mb={6}>
                This table
              </Text>
              {piiOf(selectedNode.id).length ? (
                <Stack gap={4}>
                  {piiOf(selectedNode.id).map((column) => (
                    <Text key={`own-${column.column}`} size="xs" className="pii-mono-line">
                      {column.column} · {column.piiType} · {column.function || 'mask TBD'}
                    </Text>
                  ))}
                </Stack>
              ) : (
                <Text size="xs" c="dimmed">
                  No PII detected on this table itself.
                </Text>
              )}
            </div>
            <div className="pii-impact-node">
              <Text size="xs" fw={800} tt="uppercase" c="dimmed" mb={6}>
                Children reached via FK
              </Text>
              {childrenOf(selectedNode.id).length ? (
                <Stack gap={8}>
                  {childrenOf(selectedNode.id).map((edge) => (
                    <div key={`child-${edge.to}-${edge.fkColumn}`}>
                      <Text size="xs" fw={720}>
                        {edge.to} <Text span c="dimmed">via {edge.fkColumn || edge.label || 'FK'}</Text>
                      </Text>
                      {piiOf(edge.to || '').length ? (
                        piiOf(edge.to || '').slice(0, 4).map((column) => (
                          <Text key={`child-${edge.to}-${column.column}`} size="xs" c="dimmed" className="pii-mono-line">
                            {column.column} · {column.piiType}
                          </Text>
                        ))
                      ) : (
                        <Text size="xs" c="dimmed">
                          No PII findings on this child.
                        </Text>
                      )}
                    </div>
                  ))}
                </Stack>
              ) : (
                <Text size="xs" c="dimmed">
                  No child tables reference this table.
                </Text>
              )}
            </div>
          </div>
          {parentsOf(selectedNode.id).length ? (
            <Text size="xs" c="dimmed" mt="xs">
              Parents: {parentsOf(selectedNode.id).map((edge) => `${edge.from} (via ${edge.fkColumn || 'FK'})`).join(', ')}
            </Text>
          ) : null}
        </div>
      ) : (
        <div className="pii-diagram-hint">Click any table to see the masking ripple across its parents and children.</div>
      )}
    </Paper>
  );
}

export function PatternsTable({ rows, canManage, onDelete }: { rows: PiiPattern[]; canManage: boolean; onDelete: (pattern: PiiPattern) => void }) {
  if (!rows.length) {
    return (
      <div className="pii-empty-state">
        <ThemeIcon variant="light" color="gray" size={40}>
          <IconRegex size={22} />
        </ThemeIcon>
        <div>
          <Text fw={760}>No custom patterns yet</Text>
          <Text c="dimmed" size="sm">
            Built-in patterns are still available. Add custom rules for client-specific identifiers.
          </Text>
        </div>
      </div>
    );
  }
  return (
    <div className="pii-table-wrap">
      <Table verticalSpacing="sm" horizontalSpacing="md" highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>PII type</Table.Th>
            <Table.Th>Match</Table.Th>
            <Table.Th>Regex</Table.Th>
            <Table.Th>Mask</Table.Th>
            <Table.Th>Scope</Table.Th>
            <Table.Th>Owner</Table.Th>
            {canManage ? <Table.Th /> : null}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {rows.map((row) => (
            <Table.Tr key={row.id}>
              <Table.Td>
                <Text fw={760}>{row.piiType}</Text>
              </Table.Td>
              <Table.Td>{row.kind}</Table.Td>
              <Table.Td className="pii-mono-muted">{row.regex}</Table.Td>
              <Table.Td>{row.suggestedFunction || '-'}</Table.Td>
              <Table.Td>
                <Badge variant="light" color={row.visibility === 'GLOBAL' ? 'violet' : row.visibility === 'GROUP' ? 'blue' : 'gray'}>
                  {row.visibility || 'PRIVATE'}
                </Badge>
              </Table.Td>
              <Table.Td>{row.ownerUsername || '-'}</Table.Td>
              {canManage ? (
                <Table.Td>
                  <Tooltip label="Delete pattern">
                    <ActionIcon variant="subtle" color="red" onClick={() => { if (canManage) onDelete(row); }} aria-label={`Delete ${row.piiType} pattern`}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Table.Td>
              ) : null}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function LiveStat({ label, value, mono }: { label: string; value: string | number; mono?: boolean }) {
  return (
    <div className="pii-live-stat">
      <Text size="xs" tt="uppercase" fw={850} c="dimmed">
        {label}
      </Text>
      <Text fw={780} className={mono ? 'pii-mono-line' : undefined}>
        {value}
      </Text>
    </div>
  );
}

function ParamEditors({
  fn,
  param1,
  param2,
  onParam
}: {
  fn?: string | null;
  param1: string;
  param2: string;
  onParam: (n: 1 | 2, value: string) => void;
}) {
  const [lookupOpen, setLookupOpen] = useState(false);
  if (isLookupOptionsFunction(fn || '')) {
    const configured = Boolean(param1.trim() || param2.trim());
    const summary = param1.trim()
      ? param1.replace(/^@lookup:(hash|direct):/i, '').replace(/^@/, '') || 'inline rows'
      : 'not set';
    return (
      <>
        <Button
          size="compact-xs"
          variant="light"
          leftSection={<IconDatabase size={12} />}
          onClick={() => setLookupOpen(true)}
        >
          {configured ? 'Edit lookup parameters' : 'Add lookup parameters'}
        </Button>
        <Text size="xs" c="dimmed" mt={2} className="pii-mono-muted">
          {summary}
        </Text>
        <Modal
          opened={lookupOpen}
          onClose={() => setLookupOpen(false)}
          title={`${fn} parameters`}
          size="lg"
        >
          <LookupOptionsBuilder
            functionName={fn as string}
            param1={param1}
            param2={param2}
            onParam1Change={(value) => onParam(1, value)}
            onParam2Change={(value) => onParam(2, value)}
          />
          <Group justify="flex-end" mt="md">
            <Button size="xs" onClick={() => setLookupOpen(false)}>
              Done
            </Button>
          </Group>
        </Modal>
      </>
    );
  }
  const labels = paramLabels(fn);
  if (!labels.length) return <Text size="xs" c="dimmed">No params</Text>;
  return (
    <Stack gap={4} className="pii-param-stack">
      {labels.map(({ label, n }) => {
        const value = n === 1 ? param1 : param2;
        const options = paramOptions(label);
        return options.length ? (
          <Select
            key={`${fn}-${label}-${n}`}
            size="xs"
            label={shortParamLabel(label)}
            data={options.map((option) => ({ value: option, label: option || 'Auto-detect' }))}
            value={value}
            onChange={(next) => onParam(n, next || '')}
            w={160}
          />
        ) : (
          <TextInput
            key={`${fn}-${label}-${n}`}
            size="xs"
            label={shortParamLabel(label)}
            value={value}
            onChange={(event) => onParam(n, event.currentTarget?.value || '')}
            spellCheck={false}
            w={160}
          />
        );
      })}
    </Stack>
  );
}

function Confidence({ value }: { value?: number | null }) {
  const pct = Math.round(Number(value || 0) * 100);
  return (
    <Group gap={8} wrap="nowrap" className="pii-confidence">
      <Progress value={pct} size="sm" w={74} />
      <Text size="xs" c="dimmed">
        {pct}%
      </Text>
    </Group>
  );
}

function StatusBadge({ status }: { status?: string | null }) {
  const clean = String(status || 'UNKNOWN').toUpperCase();
  const icon =
    clean === 'FAILED' || clean === 'REJECTED' ? (
      <IconX size={11} />
    ) : clean === 'RUNNING' ? (
      <IconLoader2 size={11} className="pii-spin" />
    ) : clean === 'COMPLETED' || clean === 'APPROVED' ? (
      <IconCircleCheck size={11} />
    ) : clean === 'SUGGESTED' ? (
      <IconAlertTriangle size={11} />
    ) : undefined;
  return (
    <Badge color={statusTone(clean)} variant="light" leftSection={icon}>
      {clean}
    </Badge>
  );
}

function StatusIcon({ status }: { status?: string | null }) {
  const clean = String(status || 'UNKNOWN').toUpperCase();
  const icon =
    clean === 'FAILED' || clean === 'REJECTED' ? <IconX size={15} />
      : clean === 'RUNNING' ? <IconLoader2 size={15} className="pii-spin" />
        : clean === 'COMPLETED' || clean === 'APPROVED' ? <IconCircleCheck size={15} />
          : clean === 'SUGGESTED' ? <IconAlertTriangle size={15} />
            : <IconShieldSearch size={15} />;
  return <Tooltip label={clean.replaceAll('_', ' ')}><ThemeIcon aria-label={`Status ${clean}`} color={statusTone(clean)} variant="light" size={28}>{icon}</ThemeIcon></Tooltip>;
}

function reviewKey(row: DiscoveryColumnReviewRow, index: number) {
  return `${index}-${row.tableName || ''}-${row.columnName || ''}`.replace(/[^A-Za-z0-9_]+/g, '_');
}

function clamp(value: number) {
  return Math.max(0, Math.min(100, Math.round(Number(value || 0))));
}

function dateTime(value?: string | null) {
  if (!value) return 'not started';
  try {
    return new Date(value).toLocaleString();
  } catch {
    return value;
  }
}
