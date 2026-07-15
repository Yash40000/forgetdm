'use client';

import { useMemo, useState } from 'react';
import {
  Accordion,
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Checkbox,
  Divider,
  Drawer,
  Group,
  Loader,
  Modal,
  NumberInput,
  Paper,
  Progress,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Switch,
  Text,
  TextInput,
  Textarea,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import {
  IconAdjustments,
  IconDatabaseImport,
  IconDeviceFloppy,
  IconEdit,
  IconLock,
  IconPlayerPlay,
  IconPlus,
  IconRefresh,
  IconSearch,
  IconServer2,
  IconTrash
} from '@tabler/icons-react';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { NameInput } from '@/components/name-input';
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
  GENERATOR_FALLBACKS,
  generatorName,
  makeColumn,
  normalizeName,
  optionHasValue,
  parseNameList,
  planFingerprint,
  resolveDataSourceInput,
  safeInputChecked,
  safeInputValue,
  SYNTHETIC_JOB_NAME_MIN_LENGTH,
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

type ImportStatus = {
  phase: string;
  table: string;
  done: number;
  total: number;
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
  const [importStatus, setImportStatus] = useState<ImportStatus | null>(null);
  const [sourceSetupOpened, setSourceSetupOpened] = useState(false);
  const [outputSetupOpened, setOutputSetupOpened] = useState(false);
  const [targetSetupOpened, setTargetSetupOpened] = useState(false);
  const [previewOpened, setPreviewOpened] = useState(false);
  const [sourceImportOpened, setSourceImportOpened] = useState(false);
  const [sourceDraftSnapshot, setSourceDraftSnapshot] = useState<SyntheticDraft | null>(null);
  const [outputDraftSnapshot, setOutputDraftSnapshot] = useState<SyntheticDraft | null>(null);
  const [sourceSetupSaved, setSourceSetupSaved] = useState(Boolean(initialPlan?.tables?.length));
  const [outputSetupSaved, setOutputSetupSaved] = useState(Boolean(initialPlan));

  const sourceSchemas = useSchemas(draft.sourceDataSourceId);
  const sourceTables = useTables(draft.sourceDataSourceId, draft.sourceSchema);
  const targetSchemas = useSchemas(draft.targetDataSourceId);
  const plan = useMemo(() => collectSyntheticPlan(draft), [draft]);
  const fingerprint = useMemo(() => planFingerprint(plan), [plan]);
  const setupLaunchReady = sourceSetupSaved && outputSetupSaved &&
    (draft.receiver !== 'DB' || Boolean(draft.targetDataSourceId || draft.targetSystems.length));
  const saveNameLength = saveName.trim().length;
  const saveNameTooShort = saveNameLength > 0 && saveNameLength < SYNTHETIC_JOB_NAME_MIN_LENGTH;

  const previewPlan = useMutation({
    mutationFn: (nextPlan: SyntheticPlan) => apiPost<SyntheticPlanSummary>('/api/synthetic/plan-summary', nextPlan),
    onSuccess: (result) => {
      setSummary(result);
      setPreviewOpened(true);
    },
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
      const pending = requestedTables.filter((table) => !existing.has(table.toLowerCase()));
      const total = pending.length;
      const added: SyntheticTable[] = [];
      const warnings: string[] = [];
      let index = 0;
      for (const table of pending) {
        setImportStatus({ phase: 'Reading columns', table, done: index, total });
        const [columns, fks] = await Promise.all([
          fetchColumns(draft.sourceDataSourceId, draft.sourceSchema, table),
          fetchForeignKeys(draft.sourceDataSourceId, draft.sourceSchema, table)
        ]);
        if (!columns.length) throw new Error(`No columns found for ${draft.sourceSchema}.${table}. Check the source schema and table name.`);
        let imported = tableFromColumns(table, columns, fks);
        if (learn) {
          setImportStatus({ phase: 'Sampling live data', table, done: index, total });
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
        index += 1;
        setImportStatus({ phase: 'Added', table, done: index, total });
      }
      return { added, warnings };
    },
    onSettled: () => setImportStatus(null),
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
      <Stack className="syn-designer-stack" gap="md">
        <DesignerHeader draft={draft} plan={plan} summary={summary} setDraft={setDraft} />

        <BuildSetupBar
          draft={draft}
          dataSources={dataSources}
          sourceSaved={sourceSetupSaved}
          outputSaved={outputSetupSaved}
          onOpenSource={() => {
            setSourceDraftSnapshot(draft);
            setSourceSetupOpened(true);
          }}
          onOpenOutput={() => {
            if (!sourceSetupSaved) return;
            setOutputDraftSnapshot(draft);
            setOutputSetupOpened(true);
          }}
          onOpenTargets={() => {
            if (!outputSetupSaved) return;
            setTargetSetupOpened(true);
          }}
        />

        <Group className="syn-build-action-bar" justify="space-between" align="flex-start" wrap="wrap">
          <div>
            <Text fw={850}>Review and launch</Text>
            <Text size="xs" c="dimmed">
              {summary ? 'Plan evidence is ready. Preview again after changing the design.' : 'Preview validates constraints, partitions, and banking readiness.'}
            </Text>
          </div>
          <Group className="syn-launch-actions" gap="xs">
            <Button variant="light" disabled={!setupLaunchReady} leftSection={<IconRefresh size={16} />} loading={previewPlan.isPending} onClick={() => previewPlan.mutate(plan)}>
              Preview plan
            </Button>
            <Button variant="light" disabled={!setupLaunchReady} leftSection={<IconDeviceFloppy size={16} />} onClick={openSave}>
              Save job
            </Button>
            <Button disabled={!setupLaunchReady} leftSection={<IconPlayerPlay size={16} />} loading={startRun.isPending} onClick={launch}>
              Generate
            </Button>
          </Group>
        </Group>

      </Stack>

      <Modal
        opened={previewOpened && Boolean(summary && fingerprint)}
        onClose={() => setPreviewOpened(false)}
        title="Synthetic plan preview"
        size="92vw"
        centered
        scrollAreaComponent={ScrollArea.Autosize}
      >
        {summary && fingerprint ? (
          <Stack gap="md">
            <PlanSummaryCard plan={plan} summary={summary} />
            <Group justify="flex-end">
              <Button variant="light" onClick={() => setPreviewOpened(false)}>Back to design</Button>
              <Button leftSection={<IconPlayerPlay size={16} />} loading={startRun.isPending} onClick={launch}>Generate</Button>
            </Group>
          </Stack>
        ) : null}
      </Modal>

      <Modal
        opened={sourceSetupOpened}
        onClose={() => {
          if (importTables.isPending) return;
          setSourceImportOpened(false);
          setSourceSetupOpened(false);
        }}
        title="Source table workspace"
        fullScreen
        withCloseButton={false}
        closeOnClickOutside={false}
        closeOnEscape={false}
      >
        <Stack className="syn-source-workspace" gap="md">
          <Group justify="space-between" align="flex-start" wrap="wrap">
            <div>
              <Group gap={7}>
                <Text fw={850} size="lg">Synthetic source tables</Text>
                <Badge variant="light">{draft.tables.length} table{draft.tables.length === 1 ? '' : 's'}</Badge>
              </Group>
              <Text size="sm" c="dimmed">Import live metadata, then configure rows, generators, keys, and table relationships.</Text>
            </div>
            <Button leftSection={<IconDatabaseImport size={16} />} onClick={() => setSourceImportOpened(true)}>
              Add / import tables
            </Button>
          </Group>

          <TableEditor draft={draft} setDraft={setDraft} generators={generators} allowBlankTable={false} />

          <Group className="syn-source-workspace-footer" justify="space-between" align="center">
            <Text size="xs" c="dimmed">Save keeps these table and generator changes in the current synthetic design.</Text>
            <Group gap="xs">
              <Button
                variant="subtle"
                color="red"
                disabled={importTables.isPending}
                onClick={() => {
                  if (sourceDraftSnapshot) setDraft(() => sourceDraftSnapshot);
                  setSourceImportOpened(false);
                  setSourceSetupOpened(false);
                }}
              >
                Discard changes
              </Button>
              <Button
                disabled={importTables.isPending}
                onClick={() => {
                  if (!draft.tables.length || draft.tables.some((table) => !table.name.trim() || !table.columns.length)) {
                    notifications.show({ color: 'red', title: 'Source setup is incomplete', message: 'Every source table needs a name and at least one field.' });
                    return;
                  }
                  const sourceChanged = sourceDraftSnapshot
                    ? JSON.stringify({ source: sourceDraftSnapshot.sourceDataSourceId, schema: sourceDraftSnapshot.sourceSchema, tables: sourceDraftSnapshot.tables }) !==
                      JSON.stringify({ source: draft.sourceDataSourceId, schema: draft.sourceSchema, tables: draft.tables })
                    : true;
                  setSourceSetupSaved(true);
                  if (sourceChanged) setOutputSetupSaved(false);
                  setSourceDraftSnapshot(null);
                  setSourceImportOpened(false);
                  setSourceSetupOpened(false);
                }}
              >
                Save &amp; close
              </Button>
            </Group>
          </Group>
        </Stack>
      </Modal>

      <Drawer
        opened={sourceSetupOpened && sourceImportOpened}
        onClose={() => !importTables.isPending && setSourceImportOpened(false)}
        title="Add or import source tables"
        position="right"
        size="xl"
        closeOnClickOutside={!importTables.isPending}
        closeOnEscape={!importTables.isPending}
      >
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
          importStatus={importStatus}
        />
      </Drawer>

      <Modal
        opened={outputSetupOpened}
        onClose={() => {
          if (outputDraftSnapshot) setDraft(() => outputDraftSnapshot);
          setOutputDraftSnapshot(null);
          setOutputSetupOpened(false);
        }}
        title="Output, load, and partition execution"
        size="92vw"
        centered
        withCloseButton={false}
        closeOnClickOutside={false}
        closeOnEscape={false}
      >
        <Stack gap="md">
          <OutputPanel
            draft={draft}
            setDraft={setDraft}
            dataSources={dataSources}
            targetSchemaOptions={schemaOptions(targetSchemas.data)}
            targetSchemasLoading={targetSchemas.isFetching}
          />
          <Group className="syn-output-modal-footer" justify="space-between" align="center">
            <Text size="xs" c="dimmed">Save this step to unlock enterprise targets and plan review.</Text>
            <Group gap="xs">
              <Button
                variant="subtle"
                color="red"
                onClick={() => {
                  if (outputDraftSnapshot) setDraft(() => outputDraftSnapshot);
                  setOutputDraftSnapshot(null);
                  setOutputSetupOpened(false);
                }}
              >
                Discard changes
              </Button>
              <Button
                leftSection={<IconDeviceFloppy size={16} />}
                onClick={() => {
                  setOutputSetupSaved(true);
                  setOutputDraftSnapshot(null);
                  setOutputSetupOpened(false);
                }}
              >
                Save &amp; close
              </Button>
            </Group>
          </Group>
        </Stack>
      </Modal>

      <Modal
        opened={targetSetupOpened}
        onClose={() => setTargetSetupOpened(false)}
        title="Enterprise target systems"
        fullScreen
      >
        <EnterpriseTargetPanel draft={draft} setDraft={setDraft} dataSources={dataSources} />
      </Modal>

      <Modal opened={saveOpened} onClose={() => setSaveOpened(false)} title="Save synthetic job" size="md">
        <Stack gap="sm">
          <NameInput
            label="Job name"
            description={`At least ${SYNTHETIC_JOB_NAME_MIN_LENGTH} characters`}
            error={saveNameTooShort ? `Enter at least ${SYNTHETIC_JOB_NAME_MIN_LENGTH} characters` : undefined}
            value={saveName}
            onChange={(value) => setSaveName(value)}
          />
          <Textarea label="Description" value={saveDescription} onChange={(event) => setSaveDescription(safeInputValue(event))} />
          <Alert color="blue" variant="light">
            Saved jobs can be loaded, approved, exported as shell runners, and run without rebuilding the design.
          </Alert>
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setSaveOpened(false)}>
              Cancel
            </Button>
            <Button loading={saveJob.isPending} disabled={saveNameLength < SYNTHETIC_JOB_NAME_MIN_LENGTH} onClick={() => saveJob.mutate()}>
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

function BuildSetupBar({
  draft,
  dataSources,
  sourceSaved,
  outputSaved,
  onOpenSource,
  onOpenOutput,
  onOpenTargets
}: {
  draft: SyntheticDraft;
  dataSources: DataSource[];
  sourceSaved: boolean;
  outputSaved: boolean;
  onOpenSource: () => void;
  onOpenOutput: () => void;
  onOpenTargets: () => void;
}) {
  const sourceName = dataSourceName(draft.sourceDataSourceId, dataSources) || draft.sourceDataSourceInput || 'Not selected';
  const targetName = dataSourceName(draft.targetDataSourceId, dataSources) || draft.targetDataSourceInput || 'Not selected';
  const sourceReady = sourceSaved && Boolean(draft.tables.length);
  const outputReady = outputSaved;
  const destinationReady = draft.receiver !== 'DB' || Boolean(draft.targetDataSourceId || draft.targetSystems.length);
  const coreReady = sourceReady && outputReady && destinationReady;
  const totalRows = draft.tables.reduce((sum, table) => sum + (Number(table.rowCount) || 0), 0);
  const totalColumns = draft.tables.reduce((sum, table) => sum + table.columns.length, 0);
  const relationshipCount = draft.tables.reduce((sum, table) => sum + table.columns.filter((column) => column.fkTable).length, 0);

  return (
    <section className="syn-build-setup" aria-labelledby="syn-build-setup-title">
      <Group justify="space-between" align="center" mb="xs">
        <div>
          <Text id="syn-build-setup-title" fw={850} size="sm">Design setup</Text>
          <Text size="xs" c="dimmed">Open only the part of the plan you need to configure.</Text>
        </div>
        <Group gap={6}>
          <Badge className="syn-build-editable-badge" variant="light" color="blue">Editable setup</Badge>
          <Badge variant="light" color={coreReady ? 'green' : 'yellow'}>
            {coreReady ? 'Core setup ready' : 'Setup incomplete'}
          </Badge>
        </Group>
      </Group>
      <div className="syn-build-setup-grid">
        <button type="button" className="syn-build-setup-action" onClick={onOpenSource}>
          <span className="syn-build-setup-icon" data-step="1"><IconDatabaseImport size={18} /></span>
          <span className="syn-build-setup-copy">
            <strong>Source tables</strong>
            <small>{sourceName}{draft.sourceSchema ? ` / ${draft.sourceSchema}` : ''}</small>
          </span>
          <Badge size="xs" variant="light" color={sourceReady ? 'green' : 'yellow'}>{sourceReady ? 'Saved' : 'Save source'}</Badge>
          <span className="syn-build-edit-affordance"><IconEdit size={13} /> Edit</span>
        </button>
        <button type="button" className="syn-build-setup-action" onClick={onOpenOutput} disabled={!sourceReady} title={!sourceReady ? 'Save Source tables first' : undefined}>
          <span className="syn-build-setup-icon" data-step="2"><IconAdjustments size={18} /></span>
          <span className="syn-build-setup-copy">
            <strong>Output &amp; execution</strong>
            <small>{draft.receiver === 'DB' ? `${targetName} · ${draft.loadAction} · ${draft.executionMode}` : `${draft.receiver} output · ${draft.executionMode}`}</small>
          </span>
          <Badge size="xs" variant="light" color={outputReady ? 'green' : 'yellow'}>{outputReady ? 'Saved' : sourceReady ? 'Configure' : 'Locked'}</Badge>
          <span className="syn-build-edit-affordance">
            {sourceReady ? <IconEdit size={13} /> : <IconLock size={13} />}
            {sourceReady ? 'Edit' : 'Locked'}
          </span>
        </button>
        <button
          type="button"
          className="syn-build-setup-action"
          onClick={onOpenTargets}
          disabled={draft.receiver !== 'DB' || !outputReady}
          title={!outputReady ? 'Save Output & execution first' : draft.receiver !== 'DB' ? 'Enterprise targets require database output' : undefined}
        >
          <span className="syn-build-setup-icon" data-step="3"><IconServer2 size={18} /></span>
          <span className="syn-build-setup-copy">
            <strong>Enterprise targets</strong>
            <small>{draft.receiver === 'DB' ? `${draft.targetSystems.length} application target${draft.targetSystems.length === 1 ? '' : 's'}` : 'Available for database output'}</small>
          </span>
          <Badge size="xs" variant="light" color="blue">{draft.targetSystems.length ? `${draft.targetSystems.length} mapped` : 'Optional'}</Badge>
          <span className="syn-build-edit-affordance">
            {outputReady ? <IconEdit size={13} /> : <IconLock size={13} />}
            {outputReady ? 'Edit' : 'Locked'}
          </span>
        </button>
      </div>
      {sourceReady ? <div className="syn-build-setup-summary" aria-label="Current synthetic design summary">
        <div className="syn-build-summary-item">
          <span>Data model</span>
          <strong>{draft.tables.length} table{draft.tables.length === 1 ? '' : 's'} · {formatRows(totalRows)} rows</strong>
          <small>{totalColumns} fields · {relationshipCount} FK relationship{relationshipCount === 1 ? '' : 's'}</small>
        </div>
        <div className="syn-build-summary-item">
          <span>Load plan</span>
          <strong>{outputReady ? (draft.receiver === 'DB' ? `${draft.loadAction} · ${draft.targetPrep}` : `${draft.receiver} output`) : 'Not saved'}</strong>
          <small>{outputReady ? `${draft.executionMode} · ${draft.executionMode === 'SINGLE' ? '1 worker' : `${draft.partitionCount || 'Auto'} workers`}` : 'Complete Output & execution next'}</small>
        </div>
        <div className="syn-build-summary-item">
          <span>Destination</span>
          <strong>{destinationReady ? (draft.receiver === 'DB' ? targetName : `${draft.receiver} files`) : 'Target pending'}</strong>
          <small>{outputReady ? (draft.receiver === 'DB' ? `${draft.targetSchema || 'Schema not selected'} · ${draft.targetSystems.length} additional target${draft.targetSystems.length === 1 ? '' : 's'}` : 'Generated as a file package') : 'Available after Output is saved'}</small>
        </div>
      </div> : null}
    </section>
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
  busy,
  importStatus
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
  importStatus: ImportStatus | null;
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
          {busy || importStatus ? (
            <div className="syn-import-status">
              <Group justify="space-between" gap="sm" mb={6} wrap="nowrap">
                <Group gap={8} wrap="nowrap">
                  <Loader size="xs" />
                  <Text size="sm" fw={720}>
                    {importStatus ? `${importStatus.phase}: ${importStatus.table}` : 'Preparing import…'}
                  </Text>
                </Group>
                {importStatus && importStatus.total ? (
                  <Text size="xs" c="dimmed" style={{ whiteSpace: 'nowrap' }}>
                    {importStatus.done}/{importStatus.total} table(s)
                  </Text>
                ) : null}
              </Group>
              <Progress
                size="sm"
                striped
                animated
                value={importStatus && importStatus.total ? (importStatus.done / importStatus.total) * 100 : 100}
              />
            </div>
          ) : null}
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
              <Tooltip label="Browse source databases" withArrow>
                <ActionIcon size="lg" variant="light" aria-label="Browse source databases" onClick={() => setSourceBrowseOpened(true)}>
                  <IconSearch size={17} />
                </ActionIcon>
              </Tooltip>
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
              <Tooltip label="Browse source schemas" withArrow>
                <ActionIcon size="lg" variant="light" aria-label="Browse source schemas" disabled={!canUseSource} loading={sourceSchemasLoading} onClick={() => setSchemaBrowseOpened(true)}>
                  <IconSearch size={17} />
                </ActionIcon>
              </Tooltip>
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
                <Tooltip label="Browse source tables" withArrow>
                  <ActionIcon size="lg" variant="light" aria-label="Browse source tables" disabled={!canUseSchema} loading={sourceTablesLoading} onClick={() => setTableBrowseOpened(true)}>
                    <IconSearch size={17} />
                  </ActionIcon>
                </Tooltip>
                <Tooltip label="Imports the columns of these tables into the design below, with type-matched generators." withArrow>
                  <Button leftSection={<IconPlus size={15} />} disabled={importDisabled} onClick={() => onImport(false)}>
                    {selectedCount ? `Add ${selectedCount} table${selectedCount === 1 ? '' : 's'} to design` : 'Add to design'}
                  </Button>
                </Tooltip>
                <Tooltip
                  label="Same as Add, plus it samples LIVE rows to learn each column's real value mix (statuses, categories, ranges) so generated data keeps production's shape. Slower — it reads the source."
                  withArrow
                  multiline
                  w={300}
                >
                  <Button variant="light" disabled={importDisabled} onClick={() => onImport(true)}>
                    Add + learn from live data
                  </Button>
                </Tooltip>
              </div>
            </div>
            <Text size="xs" c="dimmed">
              {importDisabled && !busy
                ? sourceInputError || schemaInputError || tableInputError
                  ? 'Fix the highlighted field above, then the Add buttons unlock.'
                  : !canUseSource
                    ? 'Step 1: pick a Source DB — then a schema, then the tables.'
                    : !canUseSchema
                      ? 'Step 2: pick the source schema.'
                      : 'Step 3: type table names above (or Browse) — the Add buttons unlock as soon as there is at least one.'
                : `"Add to design" imports columns with matching generators. "Learn from live data" also copies each column's real value distribution.`}
            </Text>
            <Group gap="xs">
              <Tooltip label="Adds every table in this schema's catalog — no need to type names." withArrow>
                <Button variant="subtle" disabled={!catalogCount || busy || Boolean(sourceInputError || schemaInputError)} onClick={() => onImportAll(false)}>
                  {catalogCount ? `Add all ${catalogCount} catalog tables` : 'Add all tables'}
                </Button>
              </Tooltip>
              <Tooltip label="Adds every catalog table AND learns value distributions from live rows (slowest, most realistic)." withArrow>
                <Button variant="subtle" disabled={!catalogCount || busy || Boolean(sourceInputError || schemaInputError)} onClick={() => onImportAll(true)}>
                  Add all + learn
                </Button>
              </Tooltip>
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
  generators,
  allowBlankTable = true
}: {
  draft: SyntheticDraft;
  setDraft: (fn: (draft: SyntheticDraft) => SyntheticDraft) => void;
  generators: GeneratorSpec[];
  allowBlankTable?: boolean;
}) {
  const [browseTarget, setBrowseTarget] = useState<{ columnIndex: number } | null>(null);
  const [editingTableIndex, setEditingTableIndex] = useState<number | null>(null);
  const [editingTable, setEditingTable] = useState<SyntheticTable | null>(null);
  const browseColumn = browseTarget != null ? editingTable?.columns[browseTarget.columnIndex] : null;
  const cloneTable = (table: SyntheticTable): SyntheticTable => ({
    ...table,
    columns: table.columns.map((column) => ({ ...column }))
  });
  const openTableEditor = (index: number) => {
    const table = draft.tables[index];
    if (!table) return;
    setBrowseTarget(null);
    setEditingTableIndex(index);
    setEditingTable(cloneTable(table));
  };
  const updateEditingTable = (patch: Partial<SyntheticTable>) => {
    setEditingTable((current) => (current ? { ...current, ...patch } : current));
  };
  const updateEditingColumn = (columnIndex: number, patch: Partial<SyntheticColumn>) => {
    setEditingTable((current) =>
      current
        ? {
            ...current,
            columns: current.columns.map((column, index) => (index === columnIndex ? { ...column, ...patch } : column))
          }
        : current
    );
  };
  const closeTableEditor = () => {
    setBrowseTarget(null);
    setEditingTableIndex(null);
    setEditingTable(null);
  };
  const saveTableEditor = (close: boolean) => {
    if (editingTableIndex === null || !editingTable) return;
    const tableName = editingTable.name.trim();
    const fieldNames = editingTable.columns.map((column) => column.name.trim());
    if (!tableName) {
      notifications.show({ color: 'red', title: 'Table name required', message: 'Enter a table name before saving.' });
      return;
    }
    if (!editingTable.columns.length) {
      notifications.show({ color: 'red', title: 'Add at least one field', message: 'A synthetic table cannot be saved without fields.' });
      return;
    }
    if (fieldNames.some((name) => !name)) {
      notifications.show({ color: 'red', title: 'Field name required', message: 'Every field needs a name before this table can be saved.' });
      return;
    }
    const normalizedNames = fieldNames.map((name) => name.toLowerCase());
    if (new Set(normalizedNames).size !== normalizedNames.length) {
      notifications.show({ color: 'red', title: 'Duplicate field names', message: 'Field names must be unique within a table.' });
      return;
    }
    const savedTable = cloneTable({ ...editingTable, name: tableName });
    setDraft((current) => ({
      ...current,
      tables: current.tables.map((table, index) => (index === editingTableIndex ? savedTable : table))
    }));
    setEditingTable(savedTable);
    notifications.show({ color: 'green', title: 'Table saved', message: `${tableName} is updated in the current design.` });
    if (close) closeTableEditor();
  };
  const addTable = () => {
    const nextIndex = draft.tables.length;
    const nextTable: SyntheticTable = {
      name: `table${draft.tables.length + 1}`,
      rowCount: 100,
      columns: [makeColumn('id', 'SEQUENCE', '', '', true)]
    };
    setDraft((current) => ({
      ...current,
      tables: current.tables.concat(nextTable)
    }));
    setEditingTableIndex(nextIndex);
    setEditingTable(cloneTable(nextTable));
  };
  const removeTable = (index: number) => {
    setDraft((current) => ({ ...current, tables: current.tables.filter((_, idx) => idx !== index) }));
    setEditingTableIndex((current) => {
      if (current === null || current === index) return null;
      return current > index ? current - 1 : current;
    });
    setEditingTable(null);
    setBrowseTarget(null);
  };

  return (
    <>
      <Card className="forge-card" p="md">
        <Stack gap="sm">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={850}>Tables and generators</Text>
              <Text size="sm" c="dimmed">One row per table. Open a table to configure columns, generators, keys, and relationships.</Text>
            </div>
            {allowBlankTable ? <Button variant="light" leftSection={<IconPlus size={16} />} onClick={addTable}>Blank table</Button> : null}
          </Group>
          {!draft.tables.length ? (
            <Alert color="yellow" variant="light">
              {allowBlankTable ? 'No tables are in this design yet. Add a blank table or import source tables.' : 'No tables are in this design yet. Use Add / import tables above.'}
            </Alert>
          ) : (
            <div className="syn-build-table-inventory">
              <div className="syn-build-table-head" aria-hidden>
                <span>Table</span><span>Rows</span><span>Columns</span><span>Relationships</span><span />
              </div>
              {draft.tables.map((table, tableIndex) => {
                const fkCount = table.columns.filter((column) => column.fkTable).length;
                const pkCount = table.columns.filter((column) => column.primaryKey).length;
                return (
                  <div className="syn-build-table-row" key={`${table.name}-${tableIndex}`}>
                    <div className="syn-build-table-name">
                      <Text fw={750} size="sm" truncate="end">{table.name || `Table ${tableIndex + 1}`}</Text>
                      <Text size="xs" c="dimmed">{pkCount ? `${pkCount} primary key field${pkCount === 1 ? '' : 's'}` : 'No primary key selected'}</Text>
                    </div>
                    <Text size="sm" ff="monospace">{formatRows(table.rowCount)}</Text>
                    <Badge size="sm" variant="light" color="gray">{table.columns.length}</Badge>
                    <Group gap={5} wrap="wrap">
                      <Badge size="xs" variant="light" color={fkCount ? 'blue' : 'gray'}>{fkCount} FK</Badge>
                    </Group>
                    <Group gap={3} justify="flex-end" wrap="nowrap">
                      <Button size="compact-xs" variant="subtle" leftSection={<IconEdit size={13} />} onClick={() => openTableEditor(tableIndex)}>Edit</Button>
                      <Tooltip label="Remove table">
                        <ActionIcon color="red" variant="subtle" size="sm" aria-label={`Remove ${table.name || `table ${tableIndex + 1}`}`} onClick={() => removeTable(tableIndex)}>
                          <IconTrash size={14} />
                        </ActionIcon>
                      </Tooltip>
                    </Group>
                  </div>
                );
              })}
            </div>
          )}
        </Stack>
      </Card>

      <Modal
        opened={editingTableIndex !== null && Boolean(editingTable)}
        onClose={closeTableEditor}
        title={editingTable ? `Configure ${editingTable.name || `table ${Number(editingTableIndex) + 1}`}` : 'Configure table'}
        fullScreen
        withCloseButton={false}
        closeOnClickOutside={false}
        closeOnEscape={false}
        classNames={{ content: 'syn-table-editor-modal', body: 'syn-table-editor-modal-body' }}
      >
        {editingTable && editingTableIndex !== null ? (
          <div className="syn-table-editor-shell">
            <div className="syn-table-editor-toolbar">
              <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }}>
                <TextInput label="Table name" value={editingTable.name} onChange={(event) => updateEditingTable({ name: safeInputValue(event) })} />
                <NumberInput label="Rows" min={0} value={editingTable.rowCount} onChange={(value) => updateEditingTable({ rowCount: value === '' || value === null ? '' : value })} />
                <Group align="flex-end" justify="flex-end">
                  <Button variant="light" leftSection={<IconPlus size={16} />} onClick={() => updateEditingTable({ columns: editingTable.columns.concat(makeColumn(`field${editingTable.columns.length + 1}`)) })}>Add field</Button>
                  <Button variant="subtle" color="red" leftSection={<IconTrash size={16} />} onClick={() => removeTable(editingTableIndex)}>Remove table</Button>
                </Group>
              </SimpleGrid>
              <Group gap="xs" justify="space-between" wrap="wrap">
                <Text size="xs" c="dimmed">FK-linked fields inherit parent keys. LITERAL uses Param 1 as its fixed value.</Text>
                <Badge variant="light">{editingTable.columns.length} field{editingTable.columns.length === 1 ? '' : 's'}</Badge>
              </Group>
            </div>
            <div className="forge-grid-panel syn-table-editor-grid">
              <div className="syn-table-editor-scroll">
                <table className="forge-table syn-column-table">
                  <thead>
                    <tr>
                      <th>Column</th><th>Generator</th><th>Param 1 / literal</th><th>Param 2</th><th>SQL type</th><th>PK</th><th>FK table.column</th><th>Children min</th><th>Max</th><th />
                    </tr>
                  </thead>
                  <tbody>
                    {editingTable.columns.map((column, columnIndex) => {
                      const fkValue = column.fkTable && column.fkColumn ? `${column.fkTable}.${column.fkColumn}` : '';
                      return (
                        <tr key={`${column.name}-${columnIndex}`}>
                          <td><TextInput value={column.name} onChange={(event) => updateEditingColumn(columnIndex, { name: safeInputValue(event) })} /></td>
                          <td>
                            <Group gap={4} wrap="nowrap" align="center">
                              <TextInput readOnly value={column.generator || 'ALPHANUMERIC'} disabled={Boolean(column.fkTable)} style={{ flex: 1, minWidth: 0 }} styles={{ input: { cursor: column.fkTable ? 'not-allowed' : 'pointer' } }} onClick={() => { if (!column.fkTable) setBrowseTarget({ columnIndex }); }} />
                              <Tooltip label="Browse generators" withArrow>
                                <ActionIcon variant="light" aria-label="Browse generators" disabled={Boolean(column.fkTable)} onClick={() => setBrowseTarget({ columnIndex })}><IconSearch size={16} /></ActionIcon>
                              </Tooltip>
                            </Group>
                          </td>
                          <td><TextInput value={column.param1 || ''} placeholder={column.generator === 'LITERAL' ? 'literal value' : 'optional'} disabled={Boolean(column.fkTable)} onChange={(event) => updateEditingColumn(columnIndex, { param1: safeInputValue(event) })} /></td>
                          <td><TextInput value={column.param2 || ''} placeholder="optional" disabled={Boolean(column.fkTable)} onChange={(event) => updateEditingColumn(columnIndex, { param2: safeInputValue(event) })} /></td>
                          <td><TextInput value={column.sqlType || ''} onChange={(event) => updateEditingColumn(columnIndex, { sqlType: safeInputValue(event) })} /></td>
                          <td><Checkbox checked={Boolean(column.primaryKey)} onChange={(event) => updateEditingColumn(columnIndex, { primaryKey: safeInputChecked(event) })} /></td>
                          <td><TextInput value={fkValue} placeholder="customers.customer_id" onChange={(event) => { const value = safeInputValue(event); if (value.includes('.')) { const idx = value.indexOf('.'); updateEditingColumn(columnIndex, { fkTable: value.slice(0, idx), fkColumn: value.slice(idx + 1) }); } else { updateEditingColumn(columnIndex, { fkTable: null, fkColumn: null }); } }} /></td>
                          <td><NumberInput min={0} value={column.fkMin ?? ''} onChange={(value) => updateEditingColumn(columnIndex, { fkMin: value === '' || value === null ? null : value })} /></td>
                          <td><NumberInput min={0} value={column.fkMax ?? ''} onChange={(value) => updateEditingColumn(columnIndex, { fkMax: value === '' || value === null ? null : value })} /></td>
                          <td><ActionIcon color="red" variant="subtle" aria-label={`Remove ${column.name}`} onClick={() => updateEditingTable({ columns: editingTable.columns.filter((_, idx) => idx !== columnIndex) })}><IconTrash size={15} /></ActionIcon></td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            </div>
            <Group className="syn-table-editor-footer" justify="space-between" align="center">
              <Text size="xs" c="dimmed">Changes stay in this table draft until you save them.</Text>
              <Group gap="xs">
                <Button variant="subtle" color="red" onClick={closeTableEditor}>Discard</Button>
                <Button variant="light" leftSection={<IconDeviceFloppy size={16} />} onClick={() => saveTableEditor(false)}>Save</Button>
                <Button leftSection={<IconDeviceFloppy size={16} />} onClick={() => saveTableEditor(true)}>Save &amp; close</Button>
              </Group>
            </Group>
          </div>
        ) : null}
      </Modal>

      <GeneratorBrowseModal
        opened={browseTarget != null}
        generators={generators}
        current={browseColumn?.generator || ''}
        onClose={() => setBrowseTarget(null)}
        onSelect={(name) => {
          if (browseTarget) updateEditingColumn(browseTarget.columnIndex, { generator: name });
          setBrowseTarget(null);
        }}
      />
    </>
  );
}

type BrowseItem = {
  name: string;
  category: string;
  description: string;
  param1: string;
  param2: string;
};

function buildBrowseItems(generators: GeneratorSpec[]): BrowseItem[] {
  const byName = new Map<string, BrowseItem>();
  for (const fallback of GENERATOR_FALLBACKS) {
    const name = String(fallback).trim().toUpperCase();
    if (!name) continue;
    byName.set(name, { name, category: 'Other', description: '', param1: '', param2: '' });
  }
  for (const spec of generators || []) {
    const name = generatorName(spec).toUpperCase();
    if (!name) continue;
    byName.set(name, {
      name,
      category: spec.category || 'Other',
      description: spec.description || '',
      param1: spec.param1 || '',
      param2: spec.param2 || ''
    });
  }
  return Array.from(byName.values()).sort(
    (a, b) => a.category.localeCompare(b.category) || a.name.localeCompare(b.name)
  );
}

function GeneratorBrowseModal({
  opened,
  generators,
  current,
  onClose,
  onSelect
}: {
  opened: boolean;
  generators: GeneratorSpec[];
  current: string;
  onClose: () => void;
  onSelect: (name: string) => void;
}) {
  const [search, setSearch] = useState('');
  const [category, setCategory] = useState<string | null>('ALL');
  const items = useMemo(() => buildBrowseItems(generators), [generators]);
  const categories = useMemo(
    () => ['ALL'].concat(Array.from(new Set(items.map((item) => item.category))).sort()),
    [items]
  );
  const filtered = useMemo(() => {
    const clean = search.trim().toLowerCase();
    return items.filter((item) => {
      const categoryMatch = !category || category === 'ALL' || item.category === category;
      const searchMatch =
        !clean ||
        [item.name, item.category, item.description, item.param1, item.param2].some((part) =>
          part.toLowerCase().includes(clean)
        );
      return categoryMatch && searchMatch;
    });
  }, [items, category, search]);

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={`Choose a generator (${items.length} available)`}
      fullScreen
      scrollAreaComponent={ScrollArea.Autosize}
    >
      <Stack gap="sm">
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
          <TextInput
            {...technicalInputProps}
            label="Search"
            placeholder="name, category, parameter"
            value={search}
            onChange={(event) => setSearch(safeInputValue(event))}
            data-autofocus
          />
          <Select
            label="Category"
            data={categories.map((value) => ({ value, label: value === 'ALL' ? 'All categories' : value }))}
            value={category}
            onChange={(value) => setCategory(value || 'ALL')}
          />
        </SimpleGrid>
        {!filtered.length ? (
          <Alert color="yellow" variant="light">
            No generators match this filter.
          </Alert>
        ) : (
          <div className="masking-function-grid syn-generator-grid is-studio">
            {filtered.map((item) => (
              <article
                key={item.name}
                role="button"
                tabIndex={0}
                className={`masking-function-card syn-generator-card ${current.toUpperCase() === item.name ? 'is-active' : ''}`}
                onClick={() => onSelect(item.name)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    onSelect(item.name);
                  }
                }}
              >
                <Group justify="space-between" align="flex-start" wrap="nowrap">
                  <div>
                    <Text fw={850}>{item.name}</Text>
                    {item.description ? (
                      <Text size="xs" c="dimmed" className="syn-generator-description">
                        {item.description}
                      </Text>
                    ) : null}
                  </div>
                  <Badge size="xs" variant="light">
                    {item.category}
                  </Badge>
                </Group>
                <div className="syn-generator-meta">
                  <span>
                    {[item.param1 && `p1: ${item.param1}`, item.param2 && `p2: ${item.param2}`]
                      .filter(Boolean)
                      .join(' | ') || 'No params'}
                  </span>
                </div>
              </article>
            ))}
          </div>
        )}
      </Stack>
    </Modal>
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
      <Stack className="syn-output-panel" gap="md">
          <Group justify="space-between" align="center">
            <Text size="sm" c="dimmed">
              Configure the destination, load behavior, safeguards, and partition strategy.
            </Text>
            <Badge variant="light">{draft.receiver}</Badge>
          </Group>
          <div className="syn-output-field-grid">
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
                  <Tooltip label="Browse target databases" withArrow>
                    <ActionIcon size="lg" variant="light" aria-label="Browse target databases" onClick={() => setTargetBrowseOpened(true)}>
                      <IconSearch size={17} />
                    </ActionIcon>
                  </Tooltip>
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
                  <Tooltip label="Browse target schemas" withArrow>
                    <ActionIcon size="lg" variant="light" aria-label="Browse target schemas" disabled={!selectedTargetId} loading={targetSchemasLoading} onClick={() => setTargetSchemaBrowseOpened(true)}>
                      <IconSearch size={17} />
                    </ActionIcon>
                  </Tooltip>
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
          </div>
          {draft.receiver === 'DB' ? (
            <>
              <div className="syn-output-field-grid">
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
              <NumberInput
                label="Batch size"
                min={1}
                value={draft.batchSize}
                onChange={(value) => update({ batchSize: value === '' || value === null ? '' : value })}
              />
              <NumberInput
                label="Commit every rows"
                min={0}
                value={draft.commitEveryRows}
                onChange={(value) => update({ commitEveryRows: value === '' || value === null ? '' : value })}
              />
            </div>
            <div className="syn-output-options-band">
              <Text fw={750} size="sm">Load controls</Text>
              <div className="syn-output-options-grid">
                <div className="syn-output-switch-cell">
                  <Switch label="Create missing tables" checked={draft.createTable} onChange={(event) => update({ createTable: safeInputChecked(event) })} />
                </div>
                <div className="syn-output-switch-cell">
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
                </div>
                <div className="syn-output-switch-cell">
                  <Switch
                    label="Skip bad rows"
                    checked={draft.continueOnError}
                    onChange={(event) => update({ continueOnError: safeInputChecked(event) })}
                  />
                </div>
                <div className="syn-output-switch-cell">
                  <Switch label="Fast load" checked={draft.fastLoad} onChange={(event) => update({ fastLoad: safeInputChecked(event) })} />
                </div>
                <NumberInput
                  label="Max rejects"
                  min={0}
                  value={draft.maxRejects}
                  disabled={!draft.continueOnError}
                  onChange={(value) => update({ maxRejects: value === '' || value === null ? '' : value })}
                />
              </div>
            </div>
            <Divider />
            <div>
              <Text fw={750} size="sm">Parallel execution</Text>
              <Text size="xs" c="dimmed">Choose one worker for smaller jobs or partition large jobs across workers.</Text>
            </div>
            <div className="syn-output-parallel-grid">
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
                onChange={(value) => update({ partitionCount: value === '' || value === null ? '' : value })}
              />
              <NumberInput
                label="Rows per partition"
                min={1000}
                value={draft.partitionSize}
                disabled={draft.executionMode === 'SINGLE'}
                onChange={(value) => update({ partitionSize: value === '' || value === null ? '' : value })}
              />
            </div>
            <Text size="xs" c="dimmed">
              {executionHint(draft.executionMode)}
            </Text>
          </>
          ) : null}
      </Stack>

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
  const [deliveryOverrideIndex, setDeliveryOverrideIndex] = useState<number | null>(null);
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
                    <SimpleGrid cols={{ base: 1, md: 3 }}>
                      <NameInput label="Target name" value={target.name || ''} onChange={(value) => updateTarget(targetIndex, { name: value })} />
                      <Select
                        label="Data source"
                        data={sourceOptions(dataSources, 'target')}
                        searchable
                        clearable
                        value={target.targetDataSourceId ? String(target.targetDataSourceId) : null}
                        onChange={(value) => updateTarget(targetIndex, { targetDataSourceId: value ? Number(value) : null, targetSchema: '' })}
                      />
                      <TextInput label="Target schema" value={target.targetSchema || ''} onChange={(event) => updateTarget(targetIndex, { targetSchema: safeInputValue(event) })} />
                    </SimpleGrid>
                    <Paper className="syn-target-delivery-summary" withBorder p="sm" radius="sm">
                      <Group justify="space-between" align="center" wrap="wrap">
                        <div>
                          <Group gap={6}>
                            <Text fw={750} size="sm">Delivery settings</Text>
                            <Badge size="xs" variant="light" color={target.loadAction || target.targetPrep || target.createTable != null || target.dropTable != null || target.fastLoad != null || target.continueOnError != null ? 'blue' : 'gray'}>
                              {target.loadAction || target.targetPrep || target.createTable != null || target.dropTable != null || target.fastLoad != null || target.continueOnError != null ? 'Custom' : 'Inherited'}
                            </Badge>
                          </Group>
                          <Text size="xs" c="dimmed">
                            {target.loadAction || draft.loadAction} · {target.targetPrep || draft.targetPrep} · {target.fastLoad == null ? (draft.fastLoad ? 'fast load' : 'standard load') : target.fastLoad ? 'fast load' : 'standard load'}
                          </Text>
                        </div>
                        <Group gap="xs">
                          <Button variant="subtle" size="compact-sm" onClick={() => setDeliveryOverrideIndex((current) => current === targetIndex ? null : targetIndex)}>
                            {deliveryOverrideIndex === targetIndex ? 'Hide overrides' : 'Override delivery'}
                          </Button>
                          <Button
                            variant="subtle"
                            color="red"
                            size="compact-sm"
                            onClick={() =>
                              setDraft((current) => ({ ...current, targetSystems: current.targetSystems.filter((_, idx) => idx !== targetIndex) }))
                            }
                          >
                            Remove target
                          </Button>
                        </Group>
                      </Group>
                      {deliveryOverrideIndex === targetIndex ? (
                        <Stack gap="sm" mt="sm">
                          <Divider />
                          <Group justify="space-between" align="flex-end" wrap="wrap">
                            <SimpleGrid className="syn-target-delivery-fields" cols={{ base: 1, sm: 2 }}>
                              <Select
                                label="Load action override"
                                data={['REPLACE', 'INSERT', 'UPDATE', 'INSERT_UPDATE', 'TRUNCATE_ONLY']}
                                value={target.loadAction || draft.loadAction}
                                onChange={(value) => updateTarget(targetIndex, { loadAction: value || null })}
                              />
                              <Select
                                label="Prep override"
                                data={['NONE', 'DELETE', 'TRUNCATE']}
                                value={target.targetPrep || draft.targetPrep}
                                onChange={(value) => updateTarget(targetIndex, { targetPrep: value || null })}
                              />
                            </SimpleGrid>
                            <Button
                              variant="light"
                              size="compact-sm"
                              onClick={() => updateTarget(targetIndex, {
                                loadAction: null,
                                targetPrep: null,
                                createTable: null,
                                dropTable: null,
                                fastLoad: null,
                                continueOnError: null
                              })}
                            >
                              Use common defaults
                            </Button>
                          </Group>
                          <Group gap="lg">
                            <Switch label="Create missing" checked={Boolean(target.createTable ?? draft.createTable)} onChange={(event) => updateTarget(targetIndex, { createTable: safeInputChecked(event) })} />
                            <Switch label="Drop/recreate" checked={Boolean(target.dropTable ?? draft.dropTable)} onChange={(event) => updateTarget(targetIndex, { dropTable: safeInputChecked(event) })} />
                            <Switch label="Fast load" checked={Boolean(target.fastLoad ?? draft.fastLoad)} onChange={(event) => updateTarget(targetIndex, { fastLoad: safeInputChecked(event) })} />
                            <Switch label="Skip bad rows" checked={Boolean(target.continueOnError ?? draft.continueOnError)} onChange={(event) => updateTarget(targetIndex, { continueOnError: safeInputChecked(event) })} />
                          </Group>
                        </Stack>
                      ) : null}
                    </Paper>
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
