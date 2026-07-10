'use client';

import { Fragment, useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  Drawer,
  Group,
  Loader,
  Paper,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertTriangle } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { ColumnOverride, DataSetDefinition, DataSource, MaskingPolicy, TableProfile } from '@/lib/types';
import { useColumns, usePolicyRules } from '../hooks';
import {
  actionPatchForRow,
  applyColumnActionToAll,
  autoMapRows,
  buildColumnRows,
  bulkActionLabel,
  columnMeta,
  columnLabel,
  dtypeLabel,
  effectiveColumnAction,
  emptyToNull,
  equalsIgnoreCase,
  matchingRule,
  normalizedName,
  numberOrNull,
  policyRuleHint,
  sourceName,
  technicalInputProps,
  updateColumnRow,
  type ColumnMapPreviewResult,
  type ColumnMapRow
} from '../utils';

export function ColumnMapDrawer({
  opened,
  onClose,
  blueprint,
  profile,
  policies,
  dataSources,
  overrides
}: {
  opened: boolean;
  onClose: () => void;
  blueprint: DataSetDefinition;
  profile: TableProfile | null;
  policies: MaskingPolicy[];
  dataSources: DataSource[];
  overrides: ColumnOverride[];
}) {
  const queryClient = useQueryClient();
  const [rows, setRows] = useState<ColumnMapRow[]>([]);
  const [selectedPolicyId, setSelectedPolicyId] = useState('');
  const [bulkAction, setBulkAction] = useState<ColumnMapRow['action']>('USE_POLICY');
  const [bulkLiteral, setBulkLiteral] = useState('');
  const [previewResult, setPreviewResult] = useState<ColumnMapPreviewResult | null>(null);
  const sourceDataSourceId = profile?.sourceDataSourceId || blueprint.dataSourceId || null;
  const sourceSchema = profile?.sourceSchemaName || blueprint.schemaName || '';
  const sourceTable = profile?.tableName || '';
  const targetDataSourceId = blueprint.targetDataSourceId || sourceDataSourceId;
  const targetSchema = blueprint.targetSchemaName || sourceSchema;
  const targetTable = profile?.targetTableName || profile?.tableName || '';
  const selectedPolicyNumber = numberOrNull(selectedPolicyId);

  const sourceColumnsQuery = useColumns(sourceDataSourceId, sourceTable, sourceSchema, opened);
  const targetColumnsQuery = useColumns(targetDataSourceId, targetTable, targetSchema, opened);
  const policyRulesQuery = usePolicyRules(selectedPolicyNumber, opened);

  const tableOverrides = useMemo(
    () => overrides.filter((item) => equalsIgnoreCase(item.tableName, profile?.tableName || '')),
    [overrides, profile?.tableName]
  );

  useEffect(() => {
    if (!opened || !profile) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setSelectedPolicyId(String(profile.policyId || blueprint.policyId || ''));
    setBulkAction('USE_POLICY');
    setBulkLiteral('');
    setPreviewResult(null);
  }, [opened, profile, profile?.policyId, blueprint.policyId]);

  useEffect(() => {
    if (!opened || !profile) return;
    const sourceColumns = sourceColumnsQuery.data || [];
    const targetColumns = targetColumnsQuery.data?.length ? targetColumnsQuery.data : sourceColumns;
    if (!targetColumns.length) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setRows(buildColumnRows(targetColumns, sourceColumns, tableOverrides));
  }, [opened, profile, sourceColumnsQuery.data, targetColumnsQuery.data, tableOverrides]);

  const saveOverrides = useMutation({
    mutationFn: async (payload: ColumnOverride[]) => {
      if (profile && selectedPolicyNumber !== (profile.policyId || null)) {
        await apiPost<TableProfile>(`/api/datasets/${blueprint.id}/profiles`, {
          ...profile,
          id: null,
          datasetId: blueprint.id,
          policyId: selectedPolicyNumber
        });
      }
      return apiPut<ColumnOverride[]>(`/api/datasets/${blueprint.id}/overrides`, payload);
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Column map saved', message: `${profile?.tableName} column map updated.` });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.profiles(blueprint.id) });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.overrides(blueprint.id) });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.piiCoverage(blueprint.id) });
      onClose();
    },
    onError: (error) => {
      notifications.show({ color: 'red', title: 'Could not save column map', message: error.message });
    }
  });

  const previewMasking = useMutation({
    mutationFn: () => {
      if (!profile) throw new Error('Choose a table first.');
      const columns = rows
        .map((row) => ({
          targetColumn: row.targetColumn,
          sourceColumn: row.sourceColumn || null,
          action: effectiveColumnAction(row),
          literalValue: row.literalValue || null
        }))
        .filter((row) => row.action !== 'SUPPRESS');
      if (!columns.length) throw new Error('All columns are marked unused. Nothing to preview.');
      return apiPost<ColumnMapPreviewResult>(`/api/datasets/${blueprint.id}/preview-mask`, {
        table: sourceTable,
        policyId: selectedPolicyNumber,
        rows: 5,
        columns
      });
    },
    onSuccess: (result) => setPreviewResult(result),
    onError: (error) => {
      setPreviewResult(null);
      notifications.show({ color: 'red', title: 'Could not preview masking', message: error.message });
    }
  });

  const loading = sourceColumnsQuery.isFetching || targetColumnsQuery.isFetching;
  const sourceColumns = sourceColumnsQuery.data || [];
  const usedSources = new Set(rows.map((row) => row.sourceColumn).filter(Boolean).map(normalizedName));
  const policyRules = policyRulesQuery.data || [];
  const policyOptions = [{ value: '', label: 'No masking - copy as-is' }].concat(
    policies.map((item) => ({ value: String(item.id), label: item.name }))
  );

  const sourceOptionsFor = (current: string) => {
    const currentKey = normalizedName(current);
    const options = sourceColumns
      .filter((col) => !usedSources.has(normalizedName(col.column)) || normalizedName(col.column) === currentKey)
      .map((col) => ({ value: col.column, label: columnLabel(col) }));
    return [{ value: '', label: 'No source mapping' }].concat(options);
  };

  const applyBulkAction = () => {
    if (bulkAction === 'LITERAL' && !bulkLiteral.trim()) {
      notifications.show({ color: 'red', title: 'Literal value required', message: 'Enter a literal before applying it to all columns.' });
      return;
    }
    setRows((current) => applyColumnActionToAll(current, bulkAction, bulkLiteral, sourceColumns));
    setPreviewResult(null);
    notifications.show({ color: 'green', title: 'Column action applied', message: bulkActionLabel(bulkAction) });
  };

  const runAutoMap = () => {
    const next = autoMapRows(rows, sourceColumns);
    const unmapped = next.filter((row) => row.action === 'SUPPRESS').length;
    setRows(next);
    setPreviewResult(null);
    notifications.show({
      color: unmapped ? 'yellow' : 'green',
      title: 'Auto map complete',
      message: unmapped ? `${unmapped} target column${unmapped === 1 ? '' : 's'} left unused.` : 'All target columns were mapped.'
    });
  };

  const save = () => {
    if (!profile) return;
    for (const row of rows) {
      if (row.action === 'LITERAL' && !row.literalValue.trim()) {
        notifications.show({ color: 'red', title: 'Literal value required', message: row.targetColumn });
        return;
      }
    }
    const next = overrides.filter((item) => !equalsIgnoreCase(item.tableName, profile.tableName));
    for (const row of rows) {
      const action = row.action === 'USE_POLICY' && !row.sourceColumn ? 'SUPPRESS' : row.action;
      next.push({
        tableName: profile.tableName,
        columnName: row.targetColumn,
        sourceColumnName: action === 'USE_POLICY' ? row.sourceColumn || null : null,
        overrideType: action,
        literalValue: action === 'LITERAL' ? row.literalValue.trim() : null,
        condColumn: null,
        condOperator: null,
        condValue: null,
        condJoinTable: null,
        condJoinSourceCol: null,
        condJoinTargetCol: null,
        condJson: null,
        condExpr: row.condEnabled ? emptyToNull(row.condExpr) : null,
        condJoin: row.condEnabled ? emptyToNull(row.condJoin) : null,
        note: row.note || null
      });
    }
    saveOverrides.mutate(next);
  };

  return (
    <Drawer opened={opened} onClose={onClose} title="Column Map" size="92%" position="right">
      {!profile ? (
        <Alert color="yellow">Choose a table first.</Alert>
      ) : (
        <Stack gap="md">
          <Paper className="forge-card" p="md">
            <Stack gap="sm">
              <Group justify="space-between" align="flex-start">
                <div>
                  <Text fw={850}>
                    {sourceName(sourceDataSourceId, dataSources)} / {sourceTable} {'->'} {targetTable}
                  </Text>
                  <Text size="sm" c="dimmed">
                    Map target columns to source columns, literals, nulls, or unused columns. Preview uses live source rows.
                  </Text>
                </div>
                <Group gap="xs">
                  <Badge variant="light">{rows.filter((row) => row.action !== 'SUPPRESS').length} active columns</Badge>
                  <Badge variant="light" color={rows.some((row) => row.condEnabled) ? 'blue' : 'gray'}>
                    {rows.filter((row) => row.condEnabled).length} conditional
                  </Badge>
                </Group>
              </Group>
              <SimpleGrid cols={{ base: 1, md: 4 }}>
                <Select
                  label="Masking mode"
                  description="Choose no masking to copy already-masked/source values as-is."
                  data={policyOptions}
                  value={selectedPolicyId}
                  searchable
                  onChange={(value) => {
                    setSelectedPolicyId(value || '');
                    setPreviewResult(null);
                  }}
                />
                <Select
                  label="Action for all"
                  data={[
                    { value: 'USE_POLICY', label: 'Map / mask if rule exists' },
                    { value: 'LITERAL', label: 'Literal' },
                    { value: 'NULL_OUT', label: 'Null' },
                    { value: 'SUPPRESS', label: 'Unused' }
                  ]}
                  value={bulkAction}
                  onChange={(value) => setBulkAction((value || 'USE_POLICY') as ColumnMapRow['action'])}
                />
                <TextInput
                  {...technicalInputProps}
                  label="Literal value"
                  placeholder="literal value"
                  value={bulkLiteral}
                  disabled={bulkAction !== 'LITERAL'}
                  onChange={(event) => setBulkLiteral(event.currentTarget.value)}
                />
                <Group align="flex-end" gap="xs">
                  <Button variant="light" onClick={applyBulkAction}>
                    Apply to all
                  </Button>
                  <Button variant="light" onClick={runAutoMap}>
                    Auto map
                  </Button>
                  <Button variant="light" loading={previewMasking.isPending} onClick={() => previewMasking.mutate()}>
                    Preview masking
                  </Button>
                  <Button loading={saveOverrides.isPending} onClick={save}>
                    Save
                  </Button>
                </Group>
              </SimpleGrid>
            </Stack>
          </Paper>

          {loading ? (
            <Group>
              <Loader size="sm" />
              <Text c="dimmed">Loading source and target columns...</Text>
            </Group>
          ) : !rows.length ? (
            <Alert color="yellow" icon={<IconAlertTriangle size={16} />}>
              No columns found for this source/target table. Check the DataScope source/target database and schema mapping.
            </Alert>
          ) : (
            <div className="forge-grid-panel">
              <ScrollArea>
                <table className="forge-table ds-column-map-table">
                  <thead>
                    <tr>
                      <th>Target column</th>
                      <th>Target dtype</th>
                      <th>Source column</th>
                      <th>Source dtype</th>
                      <th>Action</th>
                      <th>Literal</th>
                      <th>Condition</th>
                      <th>Policy rule</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((row, idx) => {
                      const rule = matchingRule(policyRules, profile, row);
                      const sourceMeta = columnMeta(sourceColumns, row.sourceColumn);
                      return (
                        <Fragment key={row.targetColumn}>
                          <tr>
                            <td>
                              <Text fw={750}>{row.targetColumn}</Text>
                              <Text size="xs" c="dimmed">
                                {row.targetNullable ? 'nullable' : 'not null'}
                              </Text>
                            </td>
                            <td>
                              <Text size="sm" className="ds-dtype">
                                {dtypeLabel({ type: row.targetType, size: row.targetSize, nullable: row.targetNullable })}
                              </Text>
                            </td>
                            <td style={{ minWidth: 240 }}>
                              <Select
                                data={sourceOptionsFor(row.sourceColumn)}
                                value={row.sourceColumn || ''}
                                searchable
                                onChange={(value) => {
                                  const nextSource = value || '';
                                  const nextAction: ColumnMapRow['action'] =
                                    nextSource && row.action === 'SUPPRESS'
                                      ? 'USE_POLICY'
                                      : !nextSource && row.action === 'USE_POLICY'
                                        ? 'SUPPRESS'
                                        : row.action;
                                  updateColumnRow(idx, { sourceColumn: nextSource, action: nextAction }, setRows);
                                  setPreviewResult(null);
                                }}
                              />
                            </td>
                            <td>
                              <Text size="sm" className="ds-dtype">
                                {dtypeLabel(sourceMeta)}
                              </Text>
                            </td>
                            <td style={{ minWidth: 180 }}>
                              <Select
                                data={[
                                  { value: 'USE_POLICY', label: 'Map / policy' },
                                  { value: 'LITERAL', label: 'Literal value' },
                                  { value: 'NULL_OUT', label: 'Null out' },
                                  { value: 'SUPPRESS', label: 'Unused' }
                                ]}
                                value={row.action}
                                onChange={(value) => {
                                  const action = (value || 'USE_POLICY') as ColumnMapRow['action'];
                                  updateColumnRow(idx, actionPatchForRow(row, idx, action, rows, sourceColumns), setRows);
                                  setPreviewResult(null);
                                }}
                              />
                            </td>
                            <td style={{ minWidth: 180 }}>
                              <TextInput
                                {...technicalInputProps}
                                value={row.literalValue}
                                disabled={row.action !== 'LITERAL'}
                                placeholder="literal"
                                onChange={(event) => {
                                  updateColumnRow(idx, { literalValue: event.currentTarget.value }, setRows);
                                  setPreviewResult(null);
                                }}
                              />
                            </td>
                            <td>
                              <Checkbox
                                label="Conditional"
                                checked={row.condEnabled}
                                onChange={(event) => {
                                  updateColumnRow(idx, { condEnabled: event.currentTarget.checked }, setRows);
                                  setPreviewResult(null);
                                }}
                              />
                            </td>
                            <td>
                              <Text size="sm" c={rule ? undefined : 'dimmed'}>
                                {policyRuleHint(row, rule, selectedPolicyNumber)}
                              </Text>
                            </td>
                          </tr>
                          {row.condEnabled ? (
                            <tr className="ds-condition-row">
                              <td colSpan={8}>
                                <div className="ds-condition-fields">
                                  <TextInput
                                    {...technicalInputProps}
                                    label="Condition SQL"
                                    description="Use alias t for the source row, for example t.status = 'ACTIVE'."
                                    placeholder="t.status = 'ACTIVE'"
                                    value={row.condExpr}
                                    onChange={(event) => updateColumnRow(idx, { condExpr: event.currentTarget.value }, setRows)}
                                  />
                                  <TextInput
                                    {...technicalInputProps}
                                    label="Optional JOIN"
                                    description="Use when the condition needs another table."
                                    placeholder="LEFT JOIN ref_table r ON r.id = t.ref_id"
                                    value={row.condJoin}
                                    onChange={(event) => updateColumnRow(idx, { condJoin: event.currentTarget.value }, setRows)}
                                  />
                                </div>
                              </td>
                            </tr>
                          ) : null}
                        </Fragment>
                      );
                    })}
                  </tbody>
                </table>
              </ScrollArea>
            </div>
          )}
          <ColumnMapPreviewPanel preview={previewResult} onClose={() => setPreviewResult(null)} />
        </Stack>
      )}
    </Drawer>
  );
}

function ColumnMapPreviewPanel({ preview, onClose }: { preview: ColumnMapPreviewResult | null; onClose: () => void }) {
  if (!preview) return null;
  const columns = preview.columns || [];
  const rows = preview.rows || [];
  if (!rows.length) {
    return (
      <Alert color="blue" variant="light">
        Source table has no rows to preview.
      </Alert>
    );
  }
  return (
    <Paper className="forge-card ds-preview-panel" p="md">
      <Group justify="space-between" mb="xs">
        <div>
          <Text fw={800}>Masked preview</Text>
          <Text size="sm" c="dimmed">
            {rows.length} live row{rows.length === 1 ? '' : 's'} from {preview.table || 'source table'}
          </Text>
        </div>
        <Button size="xs" variant="light" onClick={onClose}>
          Close
        </Button>
      </Group>
      <ScrollArea type="always">
        <table className="forge-table ds-preview-table">
          <thead>
            <tr>
              {columns.map((column, idx) => (
                <th key={`${column.targetColumn || idx}`}>
                  {column.targetColumn || `Column ${idx + 1}`}
                  <Text size="xs" c="dimmed" tt="none">
                    {column.state || 'preview'}
                  </Text>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((cells, rowIdx) => (
              <tr key={rowIdx}>
                {cells.map((cell, cellIdx) => {
                  const original = cell.original ?? '';
                  const masked = cell.masked ?? '';
                  const changed = original !== masked;
                  return (
                    <td key={`${rowIdx}-${cellIdx}`}>
                      {changed ? (
                        <div>
                          <Text size="xs" c="dimmed" td="line-through">
                            {original || 'NULL'}
                          </Text>
                          <Text size="sm" fw={750}>
                            {masked || 'NULL'}
                          </Text>
                        </div>
                      ) : (
                        <Text size="sm" c="dimmed">
                          {original || 'NULL'}
                        </Text>
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </ScrollArea>
    </Paper>
  );
}
