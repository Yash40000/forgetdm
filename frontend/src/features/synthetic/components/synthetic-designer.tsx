'use client';

import { useMemo, useState } from 'react';
import {
  Accordion,
  Alert,
  Badge,
  Button,
  Card,
  Checkbox,
  Divider,
  Group,
  Loader,
  Modal,
  NumberInput,
  Paper,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  TextInput,
  Textarea
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { IconDeviceFloppy, IconPlayerPlay, IconPlus, IconRefresh, IconTrash } from '@tabler/icons-react';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataColumn, DataSource } from '@/lib/types';
import type {
  GeneratorSpec,
  ProfileResponse,
  SyntheticColumn,
  SyntheticDraft,
  SyntheticJob,
  SyntheticPlan,
  SyntheticPlanSummary,
  SyntheticTable,
  SyntheticTargetSystem
} from '../types';
import { fetchColumns, fetchForeignKeys, schemaOptions, sourceOptions, tableOptions, useSchemas, useTables } from '../hooks';
import {
  applyProfile,
  collectSyntheticPlan,
  dataSourceCandidates,
  dataSourceName,
  defaultTargetSystem,
  downloadTextFile,
  draftFromPlan,
  emptySyntheticDraft,
  ensureTargetMappings,
  formatRows,
  generatorOptions,
  makeColumn,
  normalizeName,
  optionHasValue,
  parseNameList,
  planFingerprint,
  resolveDataSourceInput,
  safeInputChecked,
  safeInputValue,
  tableFromColumns,
  technicalInputProps,
  unknownNameList
} from '../utils';
import { PlanSummaryCard } from './plan-summary-card';

type SyntheticDesignerProps = {
  dataSources: DataSource[];
  generators: GeneratorSpec[];
  initialPlan?: SyntheticPlan | null;
  onGenerated: (job: SyntheticJob, plan: SyntheticPlan) => void;
};

export function SyntheticDesigner({ dataSources, generators, initialPlan, onGenerated }: SyntheticDesignerProps) {
  const queryClient = useQueryClient();
  const [draft, setDraft] = useState<SyntheticDraft>(() => (initialPlan ? draftFromPlan(initialPlan) : emptySyntheticDraft()));
  const [selectedSourceTables, setSelectedSourceTables] = useState<string[]>([]);
  const [sourceTableText, setSourceTableText] = useState('');
  const [summary, setSummary] = useState<SyntheticPlanSummary | null>(null);
  const [saveOpened, setSaveOpened] = useState(false);
  const [saveName, setSaveName] = useState('');
  const [saveDescription, setSaveDescription] = useState('');

  const sourceSchemas = useSchemas(draft.sourceDataSourceId);
  const sourceTables = useTables(draft.sourceDataSourceId, draft.sourceSchema);
  const targetSchemas = useSchemas(draft.targetDataSourceId);
  const plan = useMemo(() => collectSyntheticPlan(draft), [draft]);
  const fingerprint = useMemo(() => planFingerprint(plan), [plan]);
  const genOptions = useMemo(() => generatorOptions(generators), [generators]);

  const previewPlan = useMutation({
    mutationFn: (nextPlan: SyntheticPlan) => apiPost<SyntheticPlanSummary>('/api/synthetic/plan-summary', nextPlan),
    onSuccess: (result) => setSummary(result),
    onError: (error) =>
      notifications.show({ color: 'red', title: 'Plan preview failed', message: error instanceof Error ? error.message : 'Could not preview plan' })
  });

  const startRun = useMutation({
    mutationFn: (nextPlan: SyntheticPlan) => apiPost<SyntheticJob>('/api/synthetic/generate/start', nextPlan),
    onSuccess: async (job, launchedPlan) => {
      notifications.show({ color: 'green', title: 'Synthetic generation launched', message: job.id });
      onGenerated(job, launchedPlan);
      await queryClient.invalidateQueries({ queryKey: keys.synthetic.jobs });
    },
    onError: (error) =>
      notifications.show({ color: 'red', title: 'Could not launch synthetic generation', message: error instanceof Error ? error.message : 'Launch failed' })
  });

  const saveJob = useMutation({
    mutationFn: () =>
      apiPost('/api/synthetic/saved-jobs', {
        name: saveName.trim(),
        description: saveDescription.trim(),
        plan
      }),
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Synthetic job saved', message: saveName.trim() });
      setSaveOpened(false);
      await queryClient.invalidateQueries({ queryKey: keys.synthetic.savedJobs });
    },
    onError: (error) =>
      notifications.show({ color: 'red', title: 'Could not save synthetic job', message: error instanceof Error ? error.message : 'Save failed' })
  });

  const importTables = useMutation({
    mutationFn: async ({ tables, learn }: { tables: string[]; learn: boolean }) => {
      if (!draft.sourceDataSourceId || !draft.sourceSchema.trim()) throw new Error('Type or browse a valid source DB and schema first.');
      const requestedTables = parseNameList(tables);
      if (!requestedTables.length) throw new Error('Type or browse at least one source table.');
      const existing = new Set(draft.tables.map((table) => table.name.toLowerCase()));
      const added: SyntheticTable[] = [];
      const warnings: string[] = [];
      for (const table of requestedTables) {
        if (existing.has(table.toLowerCase())) continue;
        const [columns, fks] = await Promise.all([
          fetchColumns(draft.sourceDataSourceId, draft.sourceSchema, table),
          fetchForeignKeys(draft.sourceDataSourceId, draft.sourceSchema, table)
        ]);
        if (!columns.length) throw new Error(`No columns found for ${draft.sourceSchema}.${table}. Check the source schema and table name.`);
        let imported = tableFromColumns(table, columns, fks);
        if (learn) {
          const profile = await apiPost<ProfileResponse>('/api/synthetic/profile', {
            dataSourceId: draft.sourceDataSourceId,
            schema: draft.sourceSchema,
            table
          });
          if (profile.warnings?.length) warnings.push(...profile.warnings.map((warning) => `${table}: ${warning}`));
          imported = applyProfile(imported, profile);
        }
        added.push(imported);
        existing.add(table.toLowerCase());
      }
      return { added, warnings };
    },
    onSuccess: ({ added, warnings }) => {
      if (!added.length) {
        notifications.show({ color: 'yellow', title: 'No new tables added', message: 'Selected tables were already in the design.' });
        return;
      }
      setDraft((current) => ({
        ...current,
        targetDataSourceId: current.targetDataSourceId || current.sourceDataSourceId,
        targetDataSourceInput: current.targetDataSourceInput || current.sourceDataSourceInput,
        targetSchema: current.targetSchema || current.sourceSchema,
        tables: current.tables.length === 1 && current.tables[0]?.name === 'customers' ? added : current.tables.concat(added),
        targetSystems: current.targetSystems.map((target) => ensureTargetMappings(target, current.tables.concat(added)))
      }));
      setSelectedSourceTables([]);
      setSourceTableText('');
      notifications.show({ color: warnings.length ? 'yellow' : 'green', title: 'Tables imported', message: `${added.length} table(s) added.` });
      if (warnings.length) notifications.show({ color: 'yellow', title: 'Profile warnings', message: warnings.slice(0, 2).join(' | ') });
    },
    onError: (error) =>
      notifications.show({ color: 'red', title: 'Could not import tables', message: error instanceof Error ? error.message : 'Import failed' })
  });

  const validate = (nextPlan: SyntheticPlan) => {
    if (!nextPlan.tables.length) throw new Error('Add at least one table before running.');
    if (nextPlan.tables.some((table) => !table.columns.length)) throw new Error('Every synthetic table needs at least one column.');
    if (nextPlan.receiver === 'DB' && !nextPlan.targetDataSourceId && !nextPlan.targetSystems?.length) {
      throw new Error('Pick a target data source or configure target systems.');
    }
  };

  const launch = () => {
    try {
      validate(plan);
      startRun.mutate(plan);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Plan is not ready', message: error instanceof Error ? error.message : 'Check the design.' });
    }
  };

  const openSave = () => {
    try {
      validate(plan);
      setSaveName(`${plan.dataset || 'synthetic'} job`);
      setSaveDescription('');
      setSaveOpened(true);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Plan is not ready', message: error instanceof Error ? error.message : 'Check the design.' });
    }
  };

  return (
    <>
      <Stack gap="md">
        <DesignerHeader draft={draft} plan={plan} summary={summary} setDraft={setDraft} />

        <SourceImportPanel
          draft={draft}
          setDraft={setDraft}
          dataSources={dataSources}
          sourceSchemasLoading={sourceSchemas.isFetching}
          sourceSchemaOptions={schemaOptions(sourceSchemas.data)}
          sourceTableOptions={tableOptions(sourceTables.data)}
          sourceTablesLoading={sourceTables.isFetching}
          sourceTablesError={sourceTables.error instanceof Error ? sourceTables.error.message : null}
          selectedSourceTables={selectedSourceTables}
          setSelectedSourceTables={setSelectedSourceTables}
          sourceTableText={sourceTableText}
          setSourceTableText={setSourceTableText}
          onImport={(learn) => importTables.mutate({ tables: selectedSourceTables, learn })}
          onImportAll={(learn) => importTables.mutate({ tables: tableOptions(sourceTables.data).map((row) => row.value), learn })}
          busy={importTables.isPending}
        />

        <TableEditor draft={draft} setDraft={setDraft} generatorOptions={genOptions} />

        <OutputPanel
          draft={draft}
          setDraft={setDraft}
          dataSources={dataSources}
          targetSchemaOptions={schemaOptions(targetSchemas.data)}
          targetSchemasLoading={targetSchemas.isFetching}
        />

        <EnterpriseTargetPanel draft={draft} setDraft={setDraft} dataSources={dataSources} />

        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={850}>Preview and launch</Text>
            <Text size="sm" c="dimmed">
              Preview captures constraints, streaming mode, partition count, and banking readiness before launch.
            </Text>
          </div>
          <Group gap="xs">
            <Button variant="light" leftSection={<IconRefresh size={16} />} loading={previewPlan.isPending} onClick={() => previewPlan.mutate(plan)}>
              Preview plan
            </Button>
            <Button variant="light" leftSection={<IconDeviceFloppy size={16} />} onClick={openSave}>
              Save job
            </Button>
            <Button leftSection={<IconPlayerPlay size={16} />} loading={startRun.isPending} onClick={launch}>
              Generate
            </Button>
          </Group>
        </Group>

        {summary && fingerprint ? <PlanSummaryCard plan={plan} summary={summary} /> : null}
      </Stack>

      <Modal opened={saveOpened} onClose={() => setSaveOpened(false)} title="Save synthetic job" size="md">
        <Stack gap="sm">
          <TextInput label="Job name" value={saveName} onChange={(event) => setSaveName(safeInputValue(event))} />
          <Textarea label="Description" value={saveDescription} onChange={(event) => setSaveDescription(safeInputValue(event))} />
          <Alert color="blue" variant="light">
            Saved jobs can be loaded, approved, exported as shell runners, and run without rebuilding the design.
          </Alert>
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setSaveOpened(false)}>
              Cancel
            </Button>
            <Button loading={saveJob.isPending} disabled={!saveName.trim()} onClick={() => saveJob.mutate()}>
              Save
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

function DesignerHeader({
  draft,
  plan,
  summary,
  setDraft
}: {
  draft: SyntheticDraft;
  plan: SyntheticPlan;
  summary: SyntheticPlanSummary | null;
  setDraft: (fn: (draft: SyntheticDraft) => SyntheticDraft) => void;
}) {
  const fkCount = plan.tables.reduce((total, table) => total + table.columns.filter((column) => column.fkTable).length, 0);
  return (
    <Paper className="forge-card" p="md">
      <SimpleGrid cols={{ base: 1, md: 4 }}>
        <TextInput
          label="Dataset"
          value={draft.dataset ?? ''}
          onChange={(event) => {
            const value = safeInputValue(event);
            setDraft((current) => ({ ...current, dataset: value }));
          }}
        />
        <TextInput
          {...technicalInputProps}
          label="Seed"
          inputMode="numeric"
          value={String(draft.seed ?? '')}
          onChange={(event) => {
            const value = safeInputValue(event);
            setDraft((current) => ({ ...current, seed: value }));
          }}
        />
        <div className="syn-preview-metric">
          <span>Tables</span>
          <b>{plan.tables.length}</b>
        </div>
        <div className="syn-preview-metric">
          <span>Rows / FK links</span>
          <b>
            {formatRows(summary?.plannedRows ?? plan.tables.reduce((total, table) => total + table.rowCount, 0))} / {fkCount}
          </b>
        </div>
      </SimpleGrid>
    </Paper>
  );
}

function SourceImportPanel({
  draft,
  setDraft,
  dataSources,
  sourceSchemasLoading,
  sourceSchemaOptions,
  sourceTableOptions,
  sourceTablesLoading,
  sourceTablesError,
  selectedSourceTables,
  setSelectedSourceTables,
  sourceTableText,
  setSourceTableText,
  onImport,
  onImportAll,
  busy
}: {
  draft: SyntheticDraft;
  setDraft: (fn: (draft: SyntheticDraft) => SyntheticDraft) => void;
  dataSources: DataSource[];
  sourceSchemasLoading: boolean;
  sourceSchemaOptions: Array<{ value: string; label: string }>;
  sourceTableOptions: Array<{ value: string; label: string }>;
  sourceTablesLoading: boolean;
  sourceTablesError: string | null;
  selectedSourceTables: string[];
  setSelectedSourceTables: (tables: string[]) => void;
  sourceTableText: string;
  setSourceTableText: (value: string) => void;
  onImport: (learn: boolean) => void;
  onImportAll: (learn: boolean) => void;
  busy: boolean;
}) {
  const [sourceBrowseOpened, setSourceBrowseOpened] = useState(false);
  const [schemaBrowseOpened, setSchemaBrowseOpened] = useState(false);
  const [tableBrowseOpened, setTableBrowseOpened] = useState(false);

  const selectedSourceId = draft.sourceDataSourceId || resolveDataSourceInput(draft.sourceDataSourceInput, dataSources, 'source');
  const sourceCandidates = useMemo(() => dataSourceCandidates(dataSources, 'source'), [dataSources]);
  const schemaNames = useMemo(() => sourceSchemaOptions.map((option) => option.value).filter(Boolean), [sourceSchemaOptions]);
  const unknownTables = unknownNameList(selectedSourceTables, sourceTableOptions);
  const sourceInputError =
    draft.sourceDataSourceInput.trim() && !selectedSourceId ? 'Unknown source DB. Type a valid id/name or use Browse.' : null;
  const schemaInputError =
    draft.sourceSchema.trim() && sourceSchemaOptions.length && !optionHasValue(sourceSchemaOptions, draft.sourceSchema)
      ? 'Schema not found in this source. Type a valid schema or use Browse.'
      : null;
  const tableInputError =
    selectedSourceTables.length > 0 &&
    !sourceTablesLoading &&
    !sourceTablesError &&
    sourceTableOptions.length > 0 &&
    unknownTables.length > 0
      ? `Unknown table(s): ${unknownTables.slice(0, 4).join(', ')}${unknownTables.length > 4 ? '...' : ''}`
      : null;
  const canUseSource = Boolean(selectedSourceId) && !sourceInputError;
  const canUseSchema = canUseSource && Boolean(draft.sourceSchema.trim()) && !schemaInputError;
  const importDisabled = busy || !selectedSourceTables.length || Boolean(sourceInputError || schemaInputError || tableInputError);
  const selectedCount = selectedSourceTables.length;
  const catalogCount = sourceTableOptions.length;

  const setTablesFromList = (tables: string[]) => {
    const next = parseNameList(tables);
    setSelectedSourceTables(next);
    setSourceTableText(next.join(', '));
  };

  const setTablesFromText = (value: string) => {
    setSourceTableText(value);
    setSelectedSourceTables(parseNameList(value));
  };

  const clearTables = () => {
    setSelectedSourceTables([]);
    setSourceTableText('');
  };

  const updateSourceInput = (value: string) => {
    setDraft((current) => {
      const sourceDataSourceId = resolveDataSourceInput(value, dataSources, 'source');
      return {
        ...current,
        sourceDataSourceInput: value,
        sourceDataSourceId,
        sourceSchema: sourceDataSourceId && sourceDataSourceId === current.sourceDataSourceId ? current.sourceSchema : ''
      };
    });
    clearTables();
  };

  const updateSourceSchema = (value: string) => {
    setDraft((current) => ({ ...current, sourceSchema: value }));
    clearTables();
  };

  return (
    <>
      <Card className="forge-card" p="md">
        <Stack gap="sm">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={850}>Import or profile source tables</Text>
              <Text size="sm" c="dimmed">
                Type source details directly or browse the live catalog. Bad names are checked before tables are added.
              </Text>
            </div>
            {busy ? (
              <Loader size="sm" />
            ) : (
              <Badge color={sourceTablesError ? 'yellow' : 'blue'} variant="light">
                {sourceTableOptions.length} table(s)
              </Badge>
            )}
          </Group>
          <SimpleGrid cols={{ base: 1, md: 2 }}>
            <Group align="flex-end" wrap="nowrap">
              <TextInput
                {...technicalInputProps}
                label="Source DB"
                description="Type id/name or browse."
                placeholder="demo-source or 1"
                value={draft.sourceDataSourceInput}
                error={sourceInputError}
                onChange={(event) => updateSourceInput(safeInputValue(event))}
                style={{ flex: 1 }}
              />
              <Button variant="light" onClick={() => setSourceBrowseOpened(true)}>
                Browse
              </Button>
            </Group>
            <Group align="flex-end" wrap="nowrap">
              <TextInput
                {...technicalInputProps}
                label="Source schema"
                description="Type schema or browse."
                placeholder="public"
                disabled={!canUseSource}
                value={draft.sourceSchema}
                error={schemaInputError}
                onChange={(event) => updateSourceSchema(safeInputValue(event))}
                style={{ flex: 1 }}
              />
              <Button variant="light" disabled={!canUseSource} loading={sourceSchemasLoading} onClick={() => setSchemaBrowseOpened(true)}>
                Browse
              </Button>
            </Group>
          </SimpleGrid>

          <div className="syn-table-import-zone">
            <Group justify="space-between" align="flex-start" gap="sm">
              <div>
                <Text size="sm" fw={850}>
                  Tables to add
                </Text>
                <Text size="xs" c="dimmed">
                  Type names, paste a list, or browse. These buttons add the tables shown in this box.
                </Text>
              </div>
              <Group gap={6}>
                <Badge variant="light">{selectedCount ? `${selectedCount} selected` : 'none selected'}</Badge>
                <Badge color={sourceTablesError ? 'yellow' : 'blue'} variant="light">
                  {catalogCount} in catalog
                </Badge>
              </Group>
            </Group>
            <div className="syn-table-import-row">
              <Textarea
                {...technicalInputProps}
                label="Table names"
                description="Comma or newline separated."
                placeholder="customers, accounts"
                autosize
                minRows={1}
                maxRows={4}
                disabled={!canUseSchema}
                value={sourceTableText}
                error={tableInputError}
                onChange={(event) => setTablesFromText(safeInputValue(event))}
                className="syn-table-import-input"
              />
              <div className="syn-table-import-actions">
                <Button variant="light" disabled={!canUseSchema} loading={sourceTablesLoading} onClick={() => setTableBrowseOpened(true)}>
                  Browse tables
                </Button>
                <Button disabled={importDisabled} onClick={() => onImport(false)}>
                  Add these tables
                </Button>
                <Button variant="light" disabled={importDisabled} onClick={() => onImport(true)}>
                  Add + learn
                </Button>
              </div>
            </div>
            <Group gap="xs">
              <Button variant="subtle" disabled={!catalogCount || busy || Boolean(sourceInputError || schemaInputError)} onClick={() => onImportAll(false)}>
                {catalogCount ? `Add all ${catalogCount} tables` : 'Add all tables'}
              </Button>
              <Button variant="subtle" disabled={!catalogCount || busy || Boolean(sourceInputError || schemaInputError)} onClick={() => onImportAll(true)}>
                Add all + learn
              </Button>
              {selectedCount ? (
                <Button variant="subtle" color="red" onClick={clearTables}>
                  Clear selection
                </Button>
              ) : null}
            </Group>
          </div>

          {sourceTablesError ? (
            <Alert color="yellow" variant="light">
              Could not load the table catalog: {sourceTablesError}. You can still type table names; import will validate them against the backend.
            </Alert>
          ) : null}
        </Stack>
      </Card>

      <DataSourceBrowseModal
        opened={sourceBrowseOpened}
        onClose={() => setSourceBrowseOpened(false)}
        title="Browse Source DB"
        candidates={sourceCandidates}
        onPick={(source) => {
          setDraft((current) => ({
            ...current,
            sourceDataSourceInput: source.name,
            sourceDataSourceId: source.id,
            sourceSchema: ''
          }));
          clearTables();
        }}
      />
      <SchemaBrowseModal
        opened={schemaBrowseOpened}
        onClose={() => setSchemaBrowseOpened(false)}
        title="Browse Source Schema"
        schemas={schemaNames}
        loading={sourceSchemasLoading}
        onPick={(schema) => updateSourceSchema(schema)}
      />
      <TableBrowseModal
        opened={tableBrowseOpened}
        onClose={() => setTableBrowseOpened(false)}
        title="Browse Source Tables"
        tables={sourceTableOptions.map((option) => option.value)}
        selected={selectedSourceTables}
        loading={sourceTablesLoading}
        onChange={setTablesFromList}
      />
    </>
  );
}

function DataSourceBrowseModal({
  opened,
  onClose,
  title,
  candidates,
  onPick
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  candidates: DataSource[];
  onPick: (source: DataSource) => void;
}) {
  const [search, setSearch] = useState('');
  const rows = useMemo(() => {
    const clean = search.trim().toLowerCase();
    return candidates
      .filter((source) =>
        clean
          ? [source.name, source.kind, source.role, source.environment || ''].some((value) => String(value || '').toLowerCase().includes(clean))
          : true
      )
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [candidates, search]);

  return (
    <Modal opened={opened} onClose={onClose} title={title} size="lg">
      <Stack gap="sm">
        <TextInput
          {...technicalInputProps}
          placeholder="Search data sources"
          value={search}
          onChange={(event) => setSearch(safeInputValue(event))}
        />
        {!rows.length ? (
          <Alert color="yellow">No matching data sources.</Alert>
        ) : (
          <ScrollArea h={320}>
            <table className="forge-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Kind</th>
                  <th>Role</th>
                  <th>Environment</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {rows.map((source) => (
                  <tr key={source.id}>
                    <td>{source.name}</td>
                    <td>{source.kind}</td>
                    <td>{source.role}</td>
                    <td>{source.environment || '-'}</td>
                    <td>
                      <Button
                        size="xs"
                        variant="light"
                        onClick={() => {
                          onPick(source);
                          onClose();
                        }}
                      >
                        Use
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </ScrollArea>
        )}
      </Stack>
    </Modal>
  );
}

function SchemaBrowseModal({
  opened,
  onClose,
  title,
  schemas,
  loading,
  onPick
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  schemas: string[];
  loading: boolean;
  onPick: (schema: string) => void;
}) {
  const [search, setSearch] = useState('');
  const rows = useMemo(() => {
    const clean = search.trim().toLowerCase();
    return schemas
      .filter(Boolean)
      .filter((schema) => (clean ? schema.toLowerCase().includes(clean) : true))
      .sort((a, b) => a.localeCompare(b));
  }, [schemas, search]);

  return (
    <Modal opened={opened} onClose={onClose} title={title} size="md">
      <Stack gap="sm">
        {loading ? (
          <Group>
            <Loader size="sm" />
            <Text c="dimmed">Loading schemas...</Text>
          </Group>
        ) : (
          <>
            <TextInput
              {...technicalInputProps}
              placeholder="Search schemas"
              value={search}
              onChange={(event) => setSearch(safeInputValue(event))}
            />
            {!rows.length ? (
              <Alert color="yellow">No schemas found. You can still type one manually.</Alert>
            ) : (
              <ScrollArea h={300}>
                <table className="forge-table">
                  <tbody>
                    {rows.map((schema) => (
                      <tr key={schema}>
                        <td>{schema}</td>
                        <td>
                          <Button
                            size="xs"
                            variant="light"
                            onClick={() => {
                              onPick(schema);
                              onClose();
                            }}
                          >
                            Use
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollArea>
            )}
          </>
        )}
      </Stack>
    </Modal>
  );
}

function TableBrowseModal({
  opened,
  onClose,
  title,
  tables,
  selected,
  loading,
  onChange
}: {
  opened: boolean;
  onClose: () => void;
  title: string;
  tables: string[];
  selected: string[];
  loading: boolean;
  onChange: (tables: string[]) => void;
}) {
  const [search, setSearch] = useState('');
  const selectedKeys = useMemo(() => new Set(selected.map((table) => table.toLowerCase())), [selected]);
  const rows = useMemo(() => {
    const clean = search.trim().toLowerCase();
    return tables
      .filter(Boolean)
      .filter((table) => (clean ? table.toLowerCase().includes(clean) : true))
      .sort((a, b) => a.localeCompare(b));
  }, [tables, search]);

  const toggle = (table: string, checked: boolean) => {
    const next = checked ? selected.concat(table) : selected.filter((item) => item.toLowerCase() !== table.toLowerCase());
    onChange(parseNameList(next));
  };

  return (
    <Modal opened={opened} onClose={onClose} title={title} size="lg">
      <Stack gap="sm">
        {loading ? (
          <Group>
            <Loader size="sm" />
            <Text c="dimmed">Loading tables...</Text>
          </Group>
        ) : (
          <>
            <Group align="flex-end" wrap="nowrap">
              <TextInput
                {...technicalInputProps}
                placeholder="Search tables"
                value={search}
                onChange={(event) => setSearch(safeInputValue(event))}
                style={{ flex: 1 }}
              />
              <Button variant="subtle" disabled={!selected.length} onClick={() => onChange([])}>
                Clear
              </Button>
            </Group>
            {!rows.length ? (
              <Alert color="yellow">No tables found. You can still type table names manually.</Alert>
            ) : (
              <ScrollArea h={340}>
                <table className="forge-table">
                  <tbody>
                    {rows.map((table) => (
                      <tr key={table}>
                        <td width={44}>
                          <Checkbox checked={selectedKeys.has(table.toLowerCase())} onChange={(event) => toggle(table, safeInputChecked(event))} />
                        </td>
                        <td>{table}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </ScrollArea>
            )}
            <Group justify="space-between">
              <Text size="sm" c="dimmed">
                {selected.length} selected
              </Text>
              <Button onClick={onClose}>Done</Button>
            </Group>
          </>
        )}
      </Stack>
    </Modal>
  );
}

function TableEditor({
  draft,
  setDraft,
  generatorOptions
}: {
  draft: SyntheticDraft;
  setDraft: (fn: (draft: SyntheticDraft) => SyntheticDraft) => void;
  generatorOptions: string[];
}) {
  const updateTable = (index: number, patch: Partial<SyntheticTable>) => {
    setDraft((current) => ({
      ...current,
      tables: current.tables.map((table, idx) => (idx === index ? { ...table, ...patch } : table))
    }));
  };
  const updateColumn = (tableIndex: number, columnIndex: number, patch: Partial<SyntheticColumn>) => {
    setDraft((current) => ({
      ...current,
      tables: current.tables.map((table, idx) =>
        idx === tableIndex
          ? {
              ...table,
              columns: table.columns.map((column, colIdx) => (colIdx === columnIndex ? { ...column, ...patch } : column))
            }
          : table
      )
    }));
  };
  const addTable = () =>
    setDraft((current) => ({
      ...current,
      tables: current.tables.concat({
        name: `table${current.tables.length + 1}`,
        rowCount: 100,
        columns: [makeColumn('id', 'SEQUENCE', '', '', true)]
      })
    }));

  return (
    <Card className="forge-card" p="md">
      <Stack gap="sm">
        <Group justify="space-between">
          <div>
            <Text fw={850}>Tables and generators</Text>
            <Text size="sm" c="dimmed">
              FK-linked fields are generated from parent keys. Literal generators expose Param 1 as the literal value.
            </Text>
          </div>
          <Button variant="light" leftSection={<IconPlus size={16} />} onClick={addTable}>
            Blank table
          </Button>
        </Group>
        {!draft.tables.length ? (
          <Alert color="yellow" variant="light">
            No tables are in this design yet. Add a blank table, or import source tables above.
          </Alert>
        ) : null}
        <Accordion multiple defaultValue={draft.tables.slice(0, 2).map((table) => table.name)}>
          {draft.tables.map((table, tableIndex) => (
            <Accordion.Item key={`${table.name}-${tableIndex}`} value={`${table.name}-${tableIndex}`}>
              <Accordion.Control>
                <Group justify="space-between" wrap="nowrap">
                  <div>
                    <Text fw={800}>{table.name || `Table ${tableIndex + 1}`}</Text>
                    <Text size="xs" c="dimmed">
                      {table.columns.length} column(s), {formatRows(table.rowCount)} rows
                    </Text>
                  </div>
                  <Badge variant="light">{table.columns.filter((column) => column.fkTable).length} FK</Badge>
                </Group>
              </Accordion.Control>
              <Accordion.Panel>
                <Stack gap="sm">
                  <SimpleGrid cols={{ base: 1, md: 3 }}>
                    <TextInput label="Table name" value={table.name} onChange={(event) => updateTable(tableIndex, { name: safeInputValue(event) })} />
                    <NumberInput label="Rows" min={0} value={table.rowCount} onChange={(value) => updateTable(tableIndex, { rowCount: value || 0 })} />
                    <Group align="flex-end">
                      <Button
                        variant="light"
                        leftSection={<IconPlus size={16} />}
                        onClick={() => updateTable(tableIndex, { columns: table.columns.concat(makeColumn(`field${table.columns.length + 1}`)) })}
                      >
                        Field
                      </Button>
                      <Button
                        variant="subtle"
                        color="red"
                        leftSection={<IconTrash size={16} />}
                        onClick={() =>
                          setDraft((current) => ({ ...current, tables: current.tables.filter((_, idx) => idx !== tableIndex) }))
                        }
                      >
                        Remove table
                      </Button>
                    </Group>
                  </SimpleGrid>
                  <div className="forge-grid-panel">
                    <ScrollArea type="always">
                      <table className="forge-table syn-column-table">
                        <thead>
                          <tr>
                            <th>Column</th>
                            <th>Generator</th>
                            <th>Param 1 / literal</th>
                            <th>Param 2</th>
                            <th>SQL type</th>
                            <th>PK</th>
                            <th>FK table.column</th>
                            <th>Children min</th>
                            <th>max</th>
                            <th></th>
                          </tr>
                        </thead>
                        <tbody>
                          {table.columns.map((column, columnIndex) => {
                            const fkValue = column.fkTable && column.fkColumn ? `${column.fkTable}.${column.fkColumn}` : '';
                            return (
                              <tr key={`${column.name}-${columnIndex}`}>
                                <td>
                                  <TextInput value={column.name} onChange={(event) => updateColumn(tableIndex, columnIndex, { name: safeInputValue(event) })} />
                                </td>
                                <td>
                                  <Select
                                    data={generatorOptions}
                                    searchable
                                    value={column.generator || 'ALPHANUMERIC'}
                                    disabled={Boolean(column.fkTable)}
                                    onChange={(value) => updateColumn(tableIndex, columnIndex, { generator: value || 'ALPHANUMERIC' })}
                                  />
                                </td>
                                <td>
                                  <TextInput
                                    value={column.param1 || ''}
                                    placeholder={column.generator === 'LITERAL' ? 'literal value' : 'optional'}
                                    disabled={Boolean(column.fkTable)}
                                    onChange={(event) => updateColumn(tableIndex, columnIndex, { param1: safeInputValue(event) })}
                                  />
                                </td>
                                <td>
                                  <TextInput
                                    value={column.param2 || ''}
                                    placeholder="optional"
                                    disabled={Boolean(column.fkTable)}
                                    onChange={(event) => updateColumn(tableIndex, columnIndex, { param2: safeInputValue(event) })}
                                  />
                                </td>
                                <td>
                                  <TextInput
                                    value={column.sqlType || ''}
                                    onChange={(event) => updateColumn(tableIndex, columnIndex, { sqlType: safeInputValue(event) })}
                                  />
                                </td>
                                <td>
                                  <Checkbox
                                    checked={Boolean(column.primaryKey)}
                                    onChange={(event) => updateColumn(tableIndex, columnIndex, { primaryKey: safeInputChecked(event) })}
                                  />
                                </td>
                                <td>
                                  <TextInput
                                    value={fkValue}
                                    placeholder="customers.customer_id"
                                    onChange={(event) => {
                                      const value = safeInputValue(event);
                                      if (value.includes('.')) {
                                        const idx = value.indexOf('.');
                                        updateColumn(tableIndex, columnIndex, { fkTable: value.slice(0, idx), fkColumn: value.slice(idx + 1) });
                                      } else {
                                        updateColumn(tableIndex, columnIndex, { fkTable: null, fkColumn: null });
                                      }
                                    }}
                                  />
                                </td>
                                <td>
                                  <NumberInput
                                    min={0}
                                    value={column.fkMin || ''}
                                    onChange={(value) => updateColumn(tableIndex, columnIndex, { fkMin: value || null })}
                                  />
                                </td>
                                <td>
                                  <NumberInput
                                    min={0}
                                    value={column.fkMax || ''}
                                    onChange={(value) => updateColumn(tableIndex, columnIndex, { fkMax: value || null })}
                                  />
                                </td>
                                <td>
                                  <Button
                                    size="xs"
                                    variant="subtle"
                                    color="red"
                                    onClick={() =>
                                      updateTable(tableIndex, { columns: table.columns.filter((_, idx) => idx !== columnIndex) })
                                    }
                                  >
                                    Remove
                                  </Button>
                                </td>
                              </tr>
                            );
                          })}
                        </tbody>
                      </table>
                    </ScrollArea>
                  </div>
                </Stack>
              </Accordion.Panel>
            </Accordion.Item>
          ))}
        </Accordion>
      </Stack>
    </Card>
  );
}

function OutputPanel({
  draft,
  setDraft,
  dataSources,
  targetSchemaOptions,
  targetSchemasLoading
}: {
  draft: SyntheticDraft;
  setDraft: (fn: (draft: SyntheticDraft) => SyntheticDraft) => void;
  dataSources: DataSource[];
  targetSchemaOptions: Array<{ value: string; label: string }>;
  targetSchemasLoading: boolean;
}) {
  const [targetBrowseOpened, setTargetBrowseOpened] = useState(false);
  const [targetSchemaBrowseOpened, setTargetSchemaBrowseOpened] = useState(false);
  const update = (patch: Partial<SyntheticDraft>) => setDraft((current) => ({ ...current, ...patch }));
  const needsKeys = ['UPDATE', 'INSERT_UPDATE'].includes(draft.loadAction);
  const selectedTargetId = draft.targetDataSourceId || resolveDataSourceInput(draft.targetDataSourceInput, dataSources, 'target');
  const targetCandidates = useMemo(() => dataSourceCandidates(dataSources, 'target'), [dataSources]);
  const targetSchemaNames = useMemo(() => targetSchemaOptions.map((option) => option.value).filter(Boolean), [targetSchemaOptions]);
  const targetInputError =
    draft.targetDataSourceInput.trim() && !selectedTargetId ? 'Unknown target DB. Type a valid id/name or use Browse.' : null;
  const targetSchemaError =
    draft.targetSchema.trim() && targetSchemaOptions.length && !optionHasValue(targetSchemaOptions, draft.targetSchema)
      ? 'Schema not found in this target. Type a valid schema or use Browse.'
      : null;

  const updateTargetInput = (value: string) => {
    setDraft((current) => {
      const targetDataSourceId = resolveDataSourceInput(value, dataSources, 'target');
      return {
        ...current,
        targetDataSourceInput: value,
        targetDataSourceId,
        targetSchema: targetDataSourceId && targetDataSourceId === current.targetDataSourceId ? current.targetSchema : ''
      };
    });
  };

  return (
    <>
      <Card className="forge-card" p="md">
        <Stack gap="sm">
          <Group justify="space-between">
            <div>
              <Text fw={850}>Output, load, and partition execution</Text>
              <Text size="sm" c="dimmed">
                This preserves the provision behavior: insert/update/upsert/truncate-only, delete/truncate prep, fast load, and worker partitioning.
              </Text>
            </div>
            <Badge variant="light">{draft.receiver}</Badge>
          </Group>
          <SimpleGrid cols={{ base: 1, md: 4 }}>
            <Select
              label="Output"
              data={[
                { value: 'DB', label: 'Database load' },
                { value: 'CSV', label: 'CSV files' },
                { value: 'JSON', label: 'JSON files' },
                { value: 'SQL', label: 'SQL script' }
              ]}
              value={draft.receiver}
              onChange={(value) => update({ receiver: (value || 'DB') as SyntheticDraft['receiver'] })}
            />
            {draft.receiver === 'DB' ? (
              <>
                <Group align="flex-end" wrap="nowrap">
                  <TextInput
                    {...technicalInputProps}
                    label="Target DB"
                    description="Type id/name or browse."
                    placeholder="demo-target or 2"
                    value={draft.targetDataSourceInput}
                    error={targetInputError}
                    onChange={(event) => updateTargetInput(safeInputValue(event))}
                    style={{ flex: 1 }}
                  />
                  <Button variant="light" onClick={() => setTargetBrowseOpened(true)}>
                    Browse
                  </Button>
                </Group>
                <Group align="flex-end" wrap="nowrap">
                  <TextInput
                    {...technicalInputProps}
                    label="Target schema"
                    description="Type schema or browse."
                    placeholder="public"
                    disabled={!selectedTargetId}
                    value={draft.targetSchema}
                    error={targetSchemaError}
                    onChange={(event) => update({ targetSchema: safeInputValue(event) })}
                    style={{ flex: 1 }}
                  />
                  <Button variant="light" disabled={!selectedTargetId} loading={targetSchemasLoading} onClick={() => setTargetSchemaBrowseOpened(true)}>
                    Browse
                  </Button>
                </Group>
                <Select
                  label="Load action"
                  data={[
                    { value: 'REPLACE', label: 'Load replace' },
                    { value: 'INSERT', label: 'Insert only' },
                    { value: 'UPDATE', label: 'Update only' },
                    { value: 'INSERT_UPDATE', label: 'Insert-update' },
                    { value: 'TRUNCATE_ONLY', label: 'Truncate only' }
                  ]}
                  value={draft.loadAction}
                  onChange={(value) => {
                    const loadAction = value || 'INSERT';
                    update({
                      loadAction,
                      targetPrep:
                        loadAction === 'TRUNCATE_ONLY'
                          ? 'TRUNCATE'
                          : loadAction === 'REPLACE' && draft.targetPrep === 'NONE'
                            ? 'DELETE'
                            : loadAction === 'INSERT' && draft.targetPrep === 'DELETE'
                              ? 'NONE'
                              : draft.targetPrep
                    });
                  }}
                />
              </>
            ) : (
              <>
                <Switch label="Include CREATE TABLE" checked={draft.createTable} onChange={(event) => update({ createTable: safeInputChecked(event) })} />
                <Switch
                  label="Include DROP and recreate"
                  checked={draft.dropTable}
                  onChange={(event) => update({ dropTable: safeInputChecked(event), createTable: safeInputChecked(event) || draft.createTable })}
                />
              </>
            )}
          </SimpleGrid>
          {draft.receiver === 'DB' ? (
            <>
              <SimpleGrid cols={{ base: 1, md: 4 }}>
              <Select
                label="Target prep"
                data={[
                  { value: 'NONE', label: 'Do not clear target' },
                  { value: 'DELETE', label: 'Delete rows first' },
                  { value: 'TRUNCATE', label: 'Truncate selected tables' }
                ]}
                value={draft.loadAction === 'TRUNCATE_ONLY' ? 'TRUNCATE' : draft.targetPrep}
                disabled={draft.loadAction === 'TRUNCATE_ONLY'}
                onChange={(value) => update({ targetPrep: value || 'NONE' })}
              />
              <TextInput
                label="Key columns"
                placeholder="id, account_id"
                disabled={!needsKeys}
                value={draft.keyColumns}
                onChange={(event) => update({ keyColumns: safeInputValue(event) })}
              />
              <NumberInput label="Batch size" min={1} value={draft.batchSize} onChange={(value) => update({ batchSize: value || '' })} />
              <NumberInput
                label="Commit every rows"
                min={0}
                value={draft.commitEveryRows}
                onChange={(value) => update({ commitEveryRows: value || '' })}
              />
            </SimpleGrid>
            <Group gap="md">
              <Switch label="Create missing tables" checked={draft.createTable} onChange={(event) => update({ createTable: safeInputChecked(event) })} />
              <Switch
                label="Drop and recreate first"
                checked={draft.dropTable}
                onChange={(event) =>
                  update({
                    dropTable: safeInputChecked(event),
                    createTable: safeInputChecked(event) || draft.createTable,
                    loadAction: safeInputChecked(event) ? 'REPLACE' : draft.loadAction,
                    targetPrep: safeInputChecked(event) ? 'NONE' : draft.targetPrep
                  })
                }
              />
              <Switch
                label="Skip bad rows"
                checked={draft.continueOnError}
                onChange={(event) => update({ continueOnError: safeInputChecked(event) })}
              />
              <Switch label="Fast load" checked={draft.fastLoad} onChange={(event) => update({ fastLoad: safeInputChecked(event) })} />
              <NumberInput
                label="Max rejects"
                min={0}
                value={draft.maxRejects}
                disabled={!draft.continueOnError}
                onChange={(value) => update({ maxRejects: value || '' })}
                w={140}
              />
            </Group>
            <Divider />
            <SimpleGrid cols={{ base: 1, md: 3 }}>
              <Select
                label="Execution mode"
                data={[
                  { value: 'SINGLE', label: 'Single worker' },
                  { value: 'LOCAL_PARTITIONED', label: 'Parallel on this server' },
                  { value: 'DISTRIBUTED', label: 'Distributed workers' }
                ]}
                value={draft.executionMode}
                onChange={(value) =>
                  update({
                    executionMode: (value || 'SINGLE') as SyntheticDraft['executionMode'],
                    partitionCount: value === 'SINGLE' ? '' : draft.partitionCount,
                    partitionSize: value === 'SINGLE' ? '' : draft.partitionSize
                  })
                }
              />
              <NumberInput
                label="Worker count"
                min={1}
                max={32}
                value={draft.partitionCount}
                disabled={draft.executionMode === 'SINGLE'}
                onChange={(value) => update({ partitionCount: value || '' })}
              />
              <NumberInput
                label="Rows per partition"
                min={1000}
                value={draft.partitionSize}
                disabled={draft.executionMode === 'SINGLE'}
                onChange={(value) => update({ partitionSize: value || '' })}
              />
            </SimpleGrid>
            <Text size="xs" c="dimmed">
              {executionHint(draft.executionMode)}
            </Text>
          </>
          ) : null}
        </Stack>
      </Card>

      <DataSourceBrowseModal
        opened={targetBrowseOpened}
        onClose={() => setTargetBrowseOpened(false)}
        title="Browse Target DB"
        candidates={targetCandidates}
        onPick={(target) =>
          setDraft((current) => ({
            ...current,
            targetDataSourceInput: target.name,
            targetDataSourceId: target.id,
            targetSchema: ''
          }))
        }
      />
      <SchemaBrowseModal
        opened={targetSchemaBrowseOpened}
        onClose={() => setTargetSchemaBrowseOpened(false)}
        title="Browse Target Schema"
        schemas={targetSchemaNames}
        loading={targetSchemasLoading}
        onPick={(schema) => update({ targetSchema: schema })}
      />
    </>
  );
}

function EnterpriseTargetPanel({
  draft,
  setDraft,
  dataSources
}: {
  draft: SyntheticDraft;
  setDraft: (fn: (draft: SyntheticDraft) => SyntheticDraft) => void;
  dataSources: DataSource[];
}) {
  const addTarget = () =>
    setDraft((current) => ({ ...current, targetSystems: current.targetSystems.concat(defaultTargetSystem(current, dataSources)) }));
  const updateTarget = (index: number, patch: Partial<SyntheticTargetSystem>) =>
    setDraft((current) => ({
      ...current,
      targetSystems: current.targetSystems.map((target, idx) =>
        idx === index ? ensureTargetMappings({ ...target, ...patch }, current.tables) : target
      )
    }));
  const updateTargetTable = (targetIndex: number, tableIndex: number, patch: Record<string, string>) =>
    setDraft((current) => ({
      ...current,
      targetSystems: current.targetSystems.map((target, idx) =>
        idx === targetIndex
          ? {
              ...target,
              tables: target.tables.map((table, tIdx) => (tIdx === tableIndex ? { ...table, ...patch } : table))
            }
          : target
      )
    }));
  const updateTargetColumn = (targetIndex: number, tableIndex: number, columnIndex: number, patch: Record<string, string>) =>
    setDraft((current) => ({
      ...current,
      targetSystems: current.targetSystems.map((target, idx) =>
        idx === targetIndex
          ? {
              ...target,
              tables: target.tables.map((table, tIdx) =>
                tIdx === tableIndex
                  ? {
                      ...table,
                      columns: table.columns.map((column, cIdx) => (cIdx === columnIndex ? { ...column, ...patch } : column))
                    }
                  : table
              )
            }
          : target
      )
    }));
  const loadTargetColumns = async (targetIndex: number, tableIndex: number) => {
    const target = draft.targetSystems[targetIndex];
    const table = target?.tables?.[tableIndex];
    if (!target?.targetDataSourceId || !table?.physicalTable) {
      notifications.show({ color: 'red', title: 'Target table needed', message: 'Pick a target source and physical table first.' });
      return;
    }
    try {
      const columns = await fetchColumns(target.targetDataSourceId, target.targetSchema || '', table.physicalTable);
      const byNorm = new Map(columns.map((column: DataColumn) => [normalizeName(column.column), column]));
      setDraft((current) => ({
        ...current,
        targetSystems: current.targetSystems.map((item, idx) =>
          idx === targetIndex
            ? {
                ...item,
                tables: item.tables.map((mapped, tIdx) =>
                  tIdx === tableIndex
                    ? {
                        ...mapped,
                        columns: mapped.columns.map((column) => {
                          const match = byNorm.get(normalizeName(column.logicalColumn)) || byNorm.get(normalizeName(column.physicalColumn));
                          return match ? { ...column, physicalColumn: match.column, sqlType: match.type || column.sqlType || 'VARCHAR' } : column;
                        })
                      }
                    : mapped
                )
              }
            : item
        )
      }));
      notifications.show({ color: 'green', title: 'Target columns loaded', message: table.physicalTable });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not load target columns', message: error instanceof Error ? error.message : 'Load failed' });
    }
  };

  if (draft.receiver !== 'DB') return null;

  return (
    <Card className="forge-card" p="md">
      <Stack gap="sm">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={850}>Enterprise target systems</Text>
            <Text size="sm" c="dimmed">
              Optional. Use when one logical synthetic design loads into multiple applications or databases with different physical names.
            </Text>
          </div>
          <Group gap="xs">
            <Badge variant="light">{draft.targetSystems.length ? `${draft.targetSystems.length} target(s)` : 'single target'}</Badge>
            <Button variant="light" leftSection={<IconPlus size={16} />} onClick={addTarget}>
              Add target
            </Button>
            <Button
              variant="subtle"
              onClick={() => setDraft((current) => ({ ...current, targetSystems: [defaultTargetSystem(current, dataSources)] }))}
              disabled={!draft.targetDataSourceId}
            >
              Use selected target
            </Button>
          </Group>
        </Group>
        {draft.targetSystems.length ? (
          <Accordion multiple>
            {draft.targetSystems.map((target, targetIndex) => (
              <Accordion.Item key={`${target.name}-${targetIndex}`} value={`target-${targetIndex}`}>
                <Accordion.Control>
                  <Group justify="space-between">
                    <Text fw={800}>{target.name || dataSourceName(target.targetDataSourceId, dataSources) || `Target ${targetIndex + 1}`}</Text>
                    <Badge variant="light">{target.tables.length} mapped table(s)</Badge>
                  </Group>
                </Accordion.Control>
                <Accordion.Panel>
                  <Stack gap="sm">
                    <SimpleGrid cols={{ base: 1, md: 5 }}>
                      <TextInput label="Target name" value={target.name || ''} onChange={(event) => updateTarget(targetIndex, { name: safeInputValue(event) })} />
                      <Select
                        label="Data source"
                        data={sourceOptions(dataSources, 'target')}
                        searchable
                        clearable
                        value={target.targetDataSourceId ? String(target.targetDataSourceId) : null}
                        onChange={(value) => updateTarget(targetIndex, { targetDataSourceId: value ? Number(value) : null, targetSchema: '' })}
                      />
                      <TextInput label="Schema" value={target.targetSchema || ''} onChange={(event) => updateTarget(targetIndex, { targetSchema: safeInputValue(event) })} />
                      <Select
                        label="Load action"
                        data={['REPLACE', 'INSERT', 'UPDATE', 'INSERT_UPDATE', 'TRUNCATE_ONLY']}
                        value={target.loadAction || draft.loadAction}
                        onChange={(value) => updateTarget(targetIndex, { loadAction: value || 'INSERT' })}
                      />
                      <Select
                        label="Prep"
                        data={['NONE', 'DELETE', 'TRUNCATE']}
                        value={target.targetPrep || draft.targetPrep}
                        onChange={(value) => updateTarget(targetIndex, { targetPrep: value || 'NONE' })}
                      />
                    </SimpleGrid>
                    <Group gap="xs">
                      <Switch label="Create missing" checked={Boolean(target.createTable ?? draft.createTable)} onChange={(event) => updateTarget(targetIndex, { createTable: safeInputChecked(event) })} />
                      <Switch label="Drop/recreate" checked={Boolean(target.dropTable ?? draft.dropTable)} onChange={(event) => updateTarget(targetIndex, { dropTable: safeInputChecked(event) })} />
                      <Switch label="Fast load" checked={Boolean(target.fastLoad)} onChange={(event) => updateTarget(targetIndex, { fastLoad: safeInputChecked(event) })} />
                      <Switch label="Skip bad rows" checked={Boolean(target.continueOnError)} onChange={(event) => updateTarget(targetIndex, { continueOnError: safeInputChecked(event) })} />
                      <Button
                        variant="subtle"
                        color="red"
                        onClick={() =>
                          setDraft((current) => ({ ...current, targetSystems: current.targetSystems.filter((_, idx) => idx !== targetIndex) }))
                        }
                      >
                        Remove target
                      </Button>
                    </Group>
                    <div className="forge-grid-panel">
                      <ScrollArea type="always">
                        <table className="forge-table syn-target-map-table">
                          <thead>
                            <tr>
                              <th>Logical table</th>
                              <th>Physical table</th>
                              <th>Columns</th>
                            </tr>
                          </thead>
                          <tbody>
                            {target.tables.map((table, tableIndex) => (
                              <tr key={`${table.logicalTable}-${tableIndex}`}>
                                <td>
                                  <Text fw={750}>{table.logicalTable}</Text>
                                </td>
                                <td>
                                  <Group wrap="nowrap">
                                    <TextInput
                                      value={table.physicalTable}
                                      onChange={(event) => updateTargetTable(targetIndex, tableIndex, { physicalTable: safeInputValue(event) })}
                                    />
                                    <Button size="xs" variant="light" onClick={() => loadTargetColumns(targetIndex, tableIndex)}>
                                      Load columns
                                    </Button>
                                  </Group>
                                </td>
                                <td>
                                  <Accordion variant="contained">
                                    <Accordion.Item value="columns">
                                      <Accordion.Control>{table.columns.length} column map(s)</Accordion.Control>
                                      <Accordion.Panel>
                                        <Stack gap={6}>
                                          {table.columns.map((column, columnIndex) => (
                                            <SimpleGrid key={`${column.logicalColumn}-${columnIndex}`} cols={{ base: 1, md: 3 }}>
                                              <TextInput value={column.logicalColumn} readOnly label="Logical" />
                                              <TextInput
                                                label="Physical"
                                                value={column.physicalColumn}
                                                onChange={(event) =>
                                                  updateTargetColumn(targetIndex, tableIndex, columnIndex, { physicalColumn: safeInputValue(event) })
                                                }
                                              />
                                              <TextInput
                                                label="SQL type"
                                                value={column.sqlType || ''}
                                                onChange={(event) =>
                                                  updateTargetColumn(targetIndex, tableIndex, columnIndex, { sqlType: safeInputValue(event) })
                                                }
                                              />
                                            </SimpleGrid>
                                          ))}
                                        </Stack>
                                      </Accordion.Panel>
                                    </Accordion.Item>
                                  </Accordion>
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      </ScrollArea>
                    </div>
                  </Stack>
                </Accordion.Panel>
              </Accordion.Item>
            ))}
          </Accordion>
        ) : (
          <Alert color="blue" variant="light">
            Single target mode is active. Add a target system only when this synthetic package must fan out to multiple apps/databases.
          </Alert>
        )}
      </Stack>
    </Card>
  );
}

function executionHint(mode: string) {
  if (mode === 'DISTRIBUTED') {
    return 'Workers claim persisted row ranges through the shared ForgeTDM database. Every worker node must reach the target DB.';
  }
  if (mode === 'LOCAL_PARTITIONED') {
    return 'One job is split into deterministic row ranges and processed across local CPU workers. Target prep runs once.';
  }
  return 'Runs tables through one generation stream. Best for small loads and file receivers.';
}

export function exportJobDesign(plan: SyntheticPlan) {
  downloadTextFile(`${plan.dataset || 'synthetic'}-plan.json`, JSON.stringify(plan, null, 2));
}
