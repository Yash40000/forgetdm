'use client';

import { useMemo, useState } from 'react';
import {
  ActionIcon, Badge, Button, Divider, Drawer, FileInput, Group, Modal, NumberInput, Paper, ScrollArea,
  SegmentedControl, Select, SimpleGrid, Stack, Table, Tabs, Text, TextInput, ThemeIcon, Title
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle, IconArrowsExchange, IconCircleCheck, IconDatabase, IconFile, IconFileCode, IconFolderOpen,
  IconGitBranch, IconPlayerPlay, IconPlus, IconRefresh, IconRestore, IconTrash, IconUpload
} from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { useConfirm } from '@/components/confirm';
import { apiFetch, apiFormPost, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource } from '@/lib/types';
import { usePermissions } from '@/lib/use-permissions';
import { useDataSources } from '@/features/datasources/hooks';
import { ColumnMapPanel } from './components/column-map-panel';
import { FunctionLibrary, SqlLineagePanel } from './components/function-lineage-panels';
import { TransformationStudio } from './components/transformation-studio';
import { VisualMapCanvas } from './components/visual-map-canvas';
import { useMappingAssets, useMappings } from './hooks';
import type { MappingAsset, MappingColumn, MappingEntity, MappingSource, MappingSpec } from './types';
import { emptySpec } from './types';
import { compileSpec, dialectFor, functionsFor, lineageFor, newTransform } from './transform-library';

type Validation = { valid: boolean; errors: string[]; warnings: string[] };
type Preview = { columns: string[]; rows: Array<Record<string, unknown>>; rowCount: number; truncated: boolean; warnings?: string[] };

export function MappingDesignerPage() {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('mapping.manage');
  const mappingsQuery = useMappings();
  const assetsQuery = useMappingAssets();
  const dataSourcesQuery = useDataSources();
  const functionsQuery = useQuery({ queryKey: keys.policies.functions, queryFn: () => apiFetch<string[]>('/api/policies/functions') });
  const [mappingId, setMappingId] = useState<number | null>(null);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [spec, setSpec] = useState<MappingSpec>(() => emptySpec());
  const [dirty, setDirty] = useState(false);
  const [validation, setValidation] = useState<Validation | null>(null);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [assetModal, setAssetModal] = useState(false);
  const [assetFile, setAssetFile] = useState<File | null>(null);
  const [assetName, setAssetName] = useState('');
  const [assetFormat, setAssetFormat] = useState('AUTO');
  const [versionsOpened, setVersionsOpened] = useState(false);
  const [discoveredSourceColumns, setDiscoveredSourceColumns] = useState<string[]>([]);
  const [columnTypes, setColumnTypes] = useState<Record<string, string>>({});
  const [workspaceView, setWorkspaceView] = useState<string | null>('VISUAL');
  const [deleteOpened, setDeleteOpened] = useState(false);
  const [libraryOpened, setLibraryOpened] = useState(false);
  const [mappingSearch, setMappingSearch] = useState('');

  const mappings = useMemo(() => mappingsQuery.data || [], [mappingsQuery.data]);
  const assets = useMemo(() => assetsQuery.data || [], [assetsQuery.data]);
  const dataSources = useMemo(() => dataSourcesQuery.data || [], [dataSourcesQuery.data]);
  const sourceDataSources = dataSources.filter((source) => allows(source, 'SOURCE'));
  const targetDataSources = dataSources.filter((source) => allows(source, 'TARGET'));
  const sourceColumns = useMemo(() => [...new Set([...discoveredSourceColumns, ...spec.sources.flatMap((source) => [...(source.columns || []).map((column) => `${source.alias}.${column.name}`), ...columnsForSource(source, assets).map((column) => `${source.alias}.${column}`)]), ...spec.columns.map((column) => column.source).filter(Boolean)])], [assets, discoveredSourceColumns, spec.columns, spec.sources]);
  const dialect = useMemo(() => dialectFor(spec, dataSources), [dataSources, spec]);
  const compiledSpec = useMemo(() => compileSpec(spec, dataSources), [dataSources, spec]);
  const mappingLineage = useMemo(() => lineageFor(spec, sourceColumns), [sourceColumns, spec]);
  const filteredMappings = useMemo(() => {
    const query = mappingSearch.trim().toLowerCase();
    return mappings.filter((mapping) => !query || `${mapping.name} ${mapping.description || ''}`.toLowerCase().includes(query));
  }, [mappingSearch, mappings]);
  const configuredSourceCount = spec.sources.filter((source) => Boolean(source.dataSourceId || source.assetId || source.table)).length;
  const mappedColumnCount = spec.columns.filter((column) => column.action !== 'UNUSED').length;
  const targetLabel = spec.target.type === 'DATABASE'
    ? spec.target.table || 'Database target pending'
    : spec.target.type === 'FILE'
      ? `${spec.target.format || 'CSV'} output`
      : 'Preview only';

  const updateSpec = (next: MappingSpec | ((current: MappingSpec) => MappingSpec)) => {
    setSpec((current) => typeof next === 'function' ? next(current) : next);
    setDirty(true); setValidation(null); setPreview(null);
  };

  const saveMutation = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Mapping management permission is required');
      return apiPost<MappingEntity>('/api/mappings', { id: mappingId, name: name.trim(), description: description.trim(), specJson: JSON.stringify(compiledSpec) });
    },
    onSuccess: (saved) => {
      setMappingId(saved.id); setDirty(false); setValidation(null);
      void queryClient.invalidateQueries({ queryKey: keys.mappings.all });
      notifications.show({ color: 'green', title: 'Mapping saved', message: `${saved.name} was versioned and is ready for validation.` });
    },
    onError: (error) => notifyError('Could not save mapping', error)
  });

  const uploadMutation = useMutation({
    mutationFn: async () => {
      if (!canManage) throw new Error('Mapping management permission is required');
      if (!assetFile) throw new Error('Choose a file');
      const form = new FormData(); form.append('file', assetFile); form.append('name', assetName || assetFile.name); form.append('format', assetFormat); form.append('header', 'true');
      return apiFormPost<MappingAsset>('/api/mappings/assets', form);
    },
    onSuccess: (asset) => {
      void queryClient.invalidateQueries({ queryKey: keys.mappings.assets }); setAssetModal(false); setAssetFile(null); setAssetName('');
      notifications.show({ color: 'green', title: 'Managed file ready', message: `${asset.name} was encrypted and profiled.` });
    },
    onError: (error) => notifyError('File upload failed', error)
  });

  const deleteMutation = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Mapping management permission is required');
      return apiFetch<void>(`/api/mappings/${mappingId}`, { method: 'DELETE' });
    },
    onSuccess: () => {
      setDeleteOpened(false); newMapping(); void queryClient.invalidateQueries({ queryKey: keys.mappings.all });
      notifications.show({ color: 'green', title: 'Mapping deleted', message: 'The definition was removed. Immutable audit evidence remains.' });
    },
    onError: (error) => notifyError('Mapping could not be deleted', error)
  });

  const openMapping = (mapping: MappingEntity) => {
    try {
      setMappingId(mapping.id); setName(mapping.name); setDescription(mapping.description || ''); setSpec(toV2(mapping)); setDiscoveredSourceColumns([]); setColumnTypes({});
      setDirty(false); setValidation(null); setPreview(null);
      setLibraryOpened(false);
    } catch (error) { notifyError('Mapping could not be opened', error); }
  };
  const newMapping = () => { setMappingId(null); setName(''); setDescription(''); setSpec(emptySpec()); setDiscoveredSourceColumns([]); setColumnTypes({}); setDirty(false); setValidation(null); setPreview(null); setLibraryOpened(false); };
  const requestOpenMapping = async (mapping: MappingEntity) => {
    if (dirty && mapping.id !== mappingId) {
      const discard = await confirm({ title: 'Discard unsaved mapping changes?', message: `Open "${mapping.name}" and discard the current unsaved edits?`, okText: 'Discard and open', danger: true });
      if (!discard) return;
    }
    openMapping(mapping);
  };
  const requestNewMapping = async () => {
    if (dirty) {
      const discard = await confirm({ title: 'Start a new mapping?', message: 'The current mapping has unsaved changes. Discard them and start a new design?', okText: 'Discard and start new', danger: true });
      if (!discard) return;
    }
    newMapping();
  };

  const validate = async () => {
    try {
      const result = await apiPost<Validation>('/api/mappings/validate', compiledSpec); setValidation(result);
      notifications.show({ color: result.valid ? 'green' : 'red', title: result.valid ? 'Mapping is valid' : 'Mapping needs attention', message: result.valid ? `${result.warnings.length} warning(s)` : result.errors[0] });
    } catch (error) { notifyError('Validation failed', error); }
  };
  const runPreview = async () => {
    try { setPreview(await apiPost<Preview>('/api/mappings/preview-spec', compiledSpec)); }
    catch (error) { notifyError('Preview failed', error); }
  };

  const autoMap = async () => {
    try {
      const sourceMeta = await Promise.all(spec.sources.map((source) => fetchSourceColumnMeta(source, assets)));
      const sources = sourceMeta.map((columns) => columns.map((column) => column.name));
      const qualified = sources.flatMap((columns, index) => columns.map((column) => `${spec.sources[index].alias}.${column}`));
      setDiscoveredSourceColumns(qualified);
      const types: Record<string, string> = {};
      sourceMeta.forEach((columns, index) => columns.forEach((column) => { types[`${spec.sources[index].alias}.${column.name}`] = column.type || ''; }));
      let targetMeta: Array<{ name: string; type: string }> = [];
      if (spec.target.type === 'DATABASE' && spec.target.dataSourceId && spec.target.table)
        targetMeta = await fetchDbColumnMeta(spec.target.dataSourceId, spec.target.schema || '', spec.target.table);
      let targets = targetMeta.map((column) => column.name);
      targetMeta.forEach((column) => { types[`target.${column.name}`] = column.type || ''; });
      setColumnTypes(types);
      if (!targets.length) targets = sources.flatMap((columns) => columns);
      const available = [...qualified];
      const mapped: MappingColumn[] = targets.map((target) => {
        const exact = available.find((source) => normalize(source.split('.').pop() || '') === normalize(target));
        if (exact) available.splice(available.indexOf(exact), 1);
        return { id: crypto.randomUUID(), target, source: exact || '', action: exact ? 'COPY' : 'UNUSED' };
      });
      updateSpec((current) => ({ ...current, columns: mapped }));
      notifications.show({ color: 'green', title: 'Column map refreshed', message: `${mapped.filter((column) => column.action !== 'UNUSED').length} columns matched by normalized name.` });
    } catch (error) { notifyError('Auto map failed', error); }
  };

  const insertFunction = (expression: string) => {
    updateSpec((current) => {
      const transforms = [...(current.transforms || [])];
      let index = -1;
      for (let position = transforms.length - 1; position >= 0; position--) if (transforms[position].type === 'EXPRESSION') { index = position; break; }
      if (index < 0) {
        const transform = newTransform('EXPRESSION'); transform.columns = [{ name: '', expr: expression }];
        return { ...current, transforms: [...transforms, transform] };
      }
      const columns = Array.isArray(transforms[index].columns) ? transforms[index].columns as Array<Record<string, unknown>> : [];
      transforms[index] = { ...transforms[index], columns: [...columns, { name: '', expr: expression }] };
      return { ...current, transforms };
    });
    notifications.show({ color: 'blue', title: 'Function inserted', message: 'Complete its output column in the Transformations tab.' });
  };

  const testExpression = async (expression: string) => {
    const source = spec.sources.find((item) => item.type === 'DATABASE' && item.dataSourceId && item.table);
    if (!source?.dataSourceId) throw new Error('Choose a database source table first');
    const from = `${source.schema ? `${source.schema}.` : ''}${source.table} ${source.alias}`;
    let sql = `SELECT ${expression} AS expression_result FROM ${from}`;
    if (dialect === 'sqlserver') sql = sql.replace(/^SELECT /, 'SELECT TOP (1) ');
    else if (dialect === 'oracle' || dialect === 'db2') sql += ' FETCH FIRST 1 ROWS ONLY';
    else sql += ' LIMIT 1';
    const result = await apiPost<{ rows?: unknown[][] }>('/api/mappings/preview', { dataSourceId: source.dataSourceId, sql });
    return JSON.stringify(result.rows?.[0]?.[0] ?? null);
  };

  return (
    <main className="forge-page mapx-page">
      {confirmElement}
      <header className="mapx-header">
        <Group gap="sm" wrap="nowrap" className="mapx-heading-copy">
          <ThemeIcon size={40} radius="md" variant="light"><IconArrowsExchange size={21} /></ThemeIcon>
          <div><Group gap="xs"><Title order={1}>Mapping Designer</Title>{dirty ? <Badge color="yellow" variant="light">Unsaved</Badge> : mappingId ? <Badge color="green" variant="light">Versioned</Badge> : null}</Group><Text c="dimmed">Build governed database and file transformation flows.</Text></div>
        </Group>
        <Group gap="xs" className="mapx-header-actions">
          <Button size="sm" variant="subtle" leftSection={<IconFolderOpen size={16} />} onClick={() => setLibraryOpened(true)}>Mappings <Badge size="xs" variant="light">{mappings.length}</Badge></Button>
          <Button size="sm" variant="default" leftSection={<IconRefresh size={16} />} onClick={() => void requestNewMapping()}>New</Button>
          <Button size="sm" variant="default" leftSection={<IconCircleCheck size={16} />} onClick={() => void validate()}>Validate</Button>
          {canManage ? <Button size="sm" leftSection={<IconDatabase size={16} />} loading={saveMutation.isPending} disabled={!name.trim()} onClick={() => saveMutation.mutate()}>Save version</Button> : <Badge variant="light" color="gray">Read-only</Badge>}
        </Group>
      </header>

      <QueryErrorBanner
        errors={[mappingsQuery.error, assetsQuery.error, dataSourcesQuery.error, functionsQuery.error]}
        onRetry={() => Promise.all([mappingsQuery.refetch(), assetsQuery.refetch(), dataSourcesQuery.refetch(), functionsQuery.refetch()])}
      />

      <Stack gap="sm" className="mapx-workspace">
          <Paper className="mapx-panel mapx-definition-bar" p="sm">
            <div className="mapx-definition-grid">
              <TextInput size="sm" label="Mapping name" value={name} onChange={(event) => { setName(event.currentTarget?.value || ''); setDirty(true); }} placeholder="customer_to_qa" spellCheck={false} />
              <TextInput size="sm" label="Description" value={description} onChange={(event) => { setDescription(event.currentTarget?.value || ''); setDirty(true); }} placeholder="Purpose and owning application" />
              <Group gap={6} className="mapx-definition-actions">
                {mappingId ? <Button size="xs" variant="subtle" leftSection={<IconRestore size={14} />} onClick={() => setVersionsOpened(true)}>Versions</Button> : <Badge variant="light">New draft</Badge>}
                {mappingId && canManage ? <ActionIcon variant="subtle" color="red" aria-label="Delete mapping" onClick={() => setDeleteOpened(true)}><IconTrash size={16} /></ActionIcon> : null}
              </Group>
            </div>
            <Group gap={6} mt="xs" className="mapx-design-summary">
              <Badge variant="light" color="blue">{configuredSourceCount} source{configuredSourceCount === 1 ? '' : 's'}</Badge>
              <Badge variant="light" color="violet">{(spec.transforms || []).length} transform{(spec.transforms || []).length === 1 ? '' : 's'}</Badge>
              <Badge variant="light" color="cyan">{mappedColumnCount} mapped columns</Badge>
              <Badge variant="light" color="green">{targetLabel}</Badge>
            </Group>
          </Paper>

          <Tabs value={workspaceView} onChange={setWorkspaceView} className="mapx-workspace-tabs" keepMounted={false}>
            <Tabs.List>
              <Tabs.Tab value="VISUAL" leftSection={<IconArrowsExchange size={15} />}>Visual Map</Tabs.Tab>
              <Tabs.Tab value="CONFIGURE" leftSection={<IconDatabase size={15} />}>Sources & Target</Tabs.Tab>
              <Tabs.Tab value="TRANSFORMS" leftSection={<IconGitBranch size={15} />}>Transformations</Tabs.Tab>
              <Tabs.Tab value="COLUMNS" leftSection={<IconGitBranch size={15} />}>Column Map</Tabs.Tab>
              <Tabs.Tab value="FUNCTIONS" leftSection={<IconPlus size={15} />}>Function Library</Tabs.Tab>
              <Tabs.Tab value="SQL" leftSection={<IconFileCode size={15} />}>SQL & Lineage</Tabs.Tab>
              <Tabs.Tab value="PREVIEW" leftSection={<IconPlayerPlay size={15} />}>Validate & Preview</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="VISUAL" pt="md">
              <VisualMapCanvas
                spec={spec}
                sourceColumns={sourceColumns}
                onChange={updateSpec}
                onAutoMap={() => void autoMap()}
                onConfigure={() => setWorkspaceView('CONFIGURE')}
                onEditTransforms={() => setWorkspaceView('TRANSFORMS')}
                dataSources={dataSources}
                assets={assets}
                columnTypes={columnTypes}
              />
            </Tabs.Panel>
            <Tabs.Panel value="CONFIGURE" pt="md">
              <SourceTargetPanel spec={spec} dataSources={sourceDataSources} targets={targetDataSources} assets={assets} onChange={updateSpec} onUpload={canManage ? () => setAssetModal(true) : null} />
            </Tabs.Panel>
            <Tabs.Panel value="TRANSFORMS" pt="md">
              <TransformationStudio transforms={spec.transforms || []} onChange={(transforms) => updateSpec((current) => ({ ...current, transforms, sqlOverride: undefined }))} />
            </Tabs.Panel>
            <Tabs.Panel value="COLUMNS" pt="md">
              <ColumnMapPanel columns={spec.columns} sourceColumns={sourceColumns} functions={functionsQuery.data || []} onChange={(columns) => updateSpec((current) => ({ ...current, columns }))} onAutoMap={() => void autoMap()} />
            </Tabs.Panel>
            <Tabs.Panel value="FUNCTIONS" pt="md">
              <FunctionLibrary dialect={dialect} groups={functionsFor(dialect)} onInsert={insertFunction} onTest={testExpression} />
            </Tabs.Panel>
            <Tabs.Panel value="SQL" pt="md">
              <SqlLineagePanel sql={compiledSpec.compiledSql || ''} lineage={mappingLineage} onSqlChange={(sqlOverride) => updateSpec((current) => ({ ...current, sqlOverride }))} />
            </Tabs.Panel>
            <Tabs.Panel value="PREVIEW" pt="md">
              <Paper className="mapx-panel" p="md">
                <Group justify="space-between"><div><Text fw={800}>Validate and preview</Text><Text size="sm" c="dimmed">Preview reads at most 100 rows and never changes a target.</Text></div><Group><Button variant="default" onClick={() => void validate()}>Validate design</Button><Button leftSection={<IconPlayerPlay size={15} />} onClick={() => void runPreview()}>Preview rows</Button></Group></Group>
                {validation ? <Stack gap={6} mt="md">{validation.valid ? <Text c="green" fw={700}>Ready for preview and versioning.</Text> : validation.errors.map((error) => <Text key={error} c="red" size="sm"><IconAlertTriangle size={14} /> {error}</Text>)}{validation.warnings.map((warning) => <Text key={warning} c="yellow" size="sm">{warning}</Text>)}</Stack> : null}
                {preview ? <PreviewTable preview={preview} /> : null}
              </Paper>
            </Tabs.Panel>
          </Tabs>
      </Stack>

      {canManage ? <AssetModal opened={assetModal} onClose={() => setAssetModal(false)} file={assetFile} setFile={setAssetFile} name={assetName} setName={setAssetName} format={assetFormat} setFormat={setAssetFormat} loading={uploadMutation.isPending} onUpload={() => uploadMutation.mutate()} /> : null}
      <VersionsModal mappingId={mappingId} opened={versionsOpened} onClose={() => setVersionsOpened(false)} canRestore={canManage} onRestored={(mapping) => { openMapping(mapping); void queryClient.invalidateQueries({ queryKey: keys.mappings.all }); }} />
      {canManage ? <Modal opened={deleteOpened} onClose={() => setDeleteOpened(false)} title="Delete mapping"><Stack><Text size="sm">Delete <b>{name}</b>? Saved execution and audit evidence is retained, but this definition will no longer be available for new runs.</Text><Group justify="flex-end"><Button variant="default" onClick={() => setDeleteOpened(false)}>Keep mapping</Button><Button color="red" loading={deleteMutation.isPending} onClick={() => deleteMutation.mutate()}>Delete mapping</Button></Group></Stack></Modal> : null}
      <Drawer opened={libraryOpened} onClose={() => setLibraryOpened(false)} position="left" size="md" title="Mapping library">
        <Stack gap="sm">
          <Group justify="space-between"><Text size="sm" c="dimmed">Open a versioned mapping or start a clean design.</Text><Badge variant="light">{mappings.length}</Badge></Group>
          <TextInput placeholder="Search name or description" leftSection={<IconFolderOpen size={15} />} value={mappingSearch} onChange={(event) => setMappingSearch(event.currentTarget.value)} spellCheck={false} />
          <Button variant="light" leftSection={<IconPlus size={15} />} onClick={() => void requestNewMapping()}>New mapping</Button>
          <ScrollArea.Autosize mah="calc(100vh - 190px)">
            <Stack gap={6}>
              {filteredMappings.map((mapping) => <button type="button" key={mapping.id} className={`mapx-library-row ${mapping.id === mappingId ? 'is-active' : ''}`} onClick={() => void requestOpenMapping(mapping)}><span><b>{mapping.name}</b><small>{mapping.description || 'No description'}</small></span><IconArrowsExchange size={16} /></button>)}
              {!filteredMappings.length ? <Text size="sm" c="dimmed" py="xl" ta="center">No matching mappings.</Text> : null}
            </Stack>
          </ScrollArea.Autosize>
        </Stack>
      </Drawer>
    </main>
  );
}

function SourceTargetPanel({ spec, dataSources, targets, assets, onChange, onUpload }: { spec: MappingSpec; dataSources: DataSource[]; targets: DataSource[]; assets: MappingAsset[]; onChange: (next: MappingSpec | ((current: MappingSpec) => MappingSpec)) => void; onUpload: (() => void) | null }) {
  const patchSource = (id: string, patch: Partial<MappingSource>) => onChange((current) => ({ ...current, sources: current.sources.map((source) => source.id === id ? { ...source, ...patch } : source) }));
  return <Paper className="mapx-panel" p="md">
    <Group justify="space-between"><div><Text fw={800}>Source to target flow</Text><Text size="sm" c="dimmed">Database and encrypted file sources share one column-mapping model.</Text></div><Button size="xs" variant="light" leftSection={<IconPlus size={15} />} onClick={() => onChange((current) => ({ ...current, sources: [...current.sources, { id: crypto.randomUUID(), type: 'DATABASE', alias: `source_${current.sources.length + 1}`, dataSourceId: null, schema: '', table: '' }] }))}>Add source</Button></Group>
    <Stack gap="sm" mt="md">
      {spec.sources.map((source, index) => <SourceRow key={source.id} source={source} index={index} dataSources={dataSources} assets={assets} patch={(patch) => patchSource(source.id, patch)} remove={() => onChange((current) => ({ ...current, sources: current.sources.filter((item) => item.id !== source.id) }))} onUpload={onUpload} />)}
    </Stack>
    {spec.sources.length > 1 ? <><Divider my="md" label="Join rules" labelPosition="left" /><Stack gap="xs">{spec.joins.map((join) => <Group key={join.id} wrap="nowrap"><Select data={['INNER', 'LEFT', 'RIGHT', 'FULL']} value={join.type} onChange={(value) => onChange((current) => ({ ...current, joins: current.joins.map((item) => item.id === join.id ? { ...item, type: (value || 'INNER') as typeof item.type } : item) }))} w={110} /><TextInput value={join.left} placeholder="source_1.customer_id" onChange={(event) => onChange((current) => ({ ...current, joins: current.joins.map((item) => item.id === join.id ? { ...item, left: event.currentTarget?.value || '' } : item) }))} style={{ flex: 1 }} /><Text>=</Text><TextInput value={join.right} placeholder="source_2.customer_id" onChange={(event) => onChange((current) => ({ ...current, joins: current.joins.map((item) => item.id === join.id ? { ...item, right: event.currentTarget?.value || '' } : item) }))} style={{ flex: 1 }} /><ActionIcon color="red" variant="subtle" onClick={() => onChange((current) => ({ ...current, joins: current.joins.filter((item) => item.id !== join.id) }))}><IconTrash size={15} /></ActionIcon></Group>)}<Button variant="subtle" size="xs" leftSection={<IconGitBranch size={14} />} onClick={() => onChange((current) => ({ ...current, joins: [...current.joins, { id: crypto.randomUUID(), type: 'INNER', left: '', right: '' }] }))}>Add join condition</Button></Stack></> : null}
    <Divider my="md" label="Target" labelPosition="left" />
    <SimpleGrid cols={{ base: 1, sm: 3, xl: 6 }} spacing="sm">
      <SegmentedControl data={[{ label: 'Preview', value: 'PREVIEW' }, { label: 'Database', value: 'DATABASE' }, { label: 'File', value: 'FILE' }]} value={spec.target.type} onChange={(value) => onChange((current) => ({ ...current, target: { ...current.target, type: value as MappingSpec['target']['type'] } }))} />
      {spec.target.type === 'DATABASE' ? <><Select label="Target connection" searchable data={targets.map((source) => ({ value: String(source.id), label: source.name }))} value={spec.target.dataSourceId ? String(spec.target.dataSourceId) : null} onChange={(value) => onChange((current) => ({ ...current, target: { ...current.target, dataSourceId: value ? Number(value) : null } }))} /><TextInput label="Schema" value={spec.target.schema || ''} onChange={(event) => onChange((current) => ({ ...current, target: { ...current.target, schema: event.currentTarget?.value || '' } }))} spellCheck={false} /><TextInput label="Table" value={spec.target.table || ''} onChange={(event) => onChange((current) => ({ ...current, target: { ...current.target, table: event.currentTarget?.value || '' } }))} spellCheck={false} /><Select label="Before load" data={[{ value: 'NONE', label: 'Keep rows' }, { value: 'DELETE', label: 'Delete rows' }, { value: 'TRUNCATE', label: 'Truncate' }]} value={spec.target.preAction || 'NONE'} onChange={(value) => onChange((current) => ({ ...current, target: { ...current.target, preAction: (value || 'NONE') as MappingSpec['target']['preAction'] } }))} /></> : null}
      {spec.target.type === 'FILE' ? <Select label="Output format" data={['CSV', 'JSON', 'JSONL']} value={spec.target.format || 'CSV'} onChange={(value) => onChange((current) => ({ ...current, target: { ...current.target, format: (value || 'CSV') as MappingSpec['target']['format'] } }))} /> : null}
      <NumberInput label="Optional row limit" value={spec.rowLimit || ''} min={1} allowDecimal={false} placeholder="No limit" onChange={(value) => onChange((current) => ({ ...current, rowLimit: typeof value === 'number' ? value : null }))} />
    </SimpleGrid>
  </Paper>;
}

function SourceRow({ source, index, dataSources, assets, patch, remove, onUpload }: { source: MappingSource; index: number; dataSources: DataSource[]; assets: MappingAsset[]; patch: (patch: Partial<MappingSource>) => void; remove: () => void; onUpload: (() => void) | null }) {
  const schemas = useQuery({ queryKey: keys.dataSources.schemas(source.dataSourceId), queryFn: () => apiFetch<Array<{ schema?: string }>>(`/api/datasources/${source.dataSourceId}/schemas`), enabled: source.type === 'DATABASE' && !!source.dataSourceId });
  const tables = useQuery({ queryKey: keys.dataSources.tables(source.dataSourceId, source.schema), queryFn: () => apiFetch<Array<{ name?: string; table?: string }>>(`/api/datasources/${source.dataSourceId}/tables?schema=${encodeURIComponent(source.schema || '')}`), enabled: source.type === 'DATABASE' && !!source.dataSourceId });
  return <div className="mapx-source-row"><Group justify="space-between"><Group gap="xs"><ThemeIcon variant="light" size="sm">{source.type === 'FILE' ? <IconFile size={14} /> : <IconDatabase size={14} />}</ThemeIcon><Text fw={750}>Source {index + 1}</Text></Group>{index > 0 ? <ActionIcon color="red" variant="subtle" onClick={remove}><IconTrash size={15} /></ActionIcon> : null}</Group><SimpleGrid cols={{ base: 1, sm: 2, xl: source.type === 'FILE' ? 4 : 5 }} mt="sm" spacing="sm"><SegmentedControl data={['DATABASE', 'FILE']} value={source.type} onChange={(value) => patch({ type: value as MappingSource['type'], dataSourceId: null, assetId: null, schema: '', table: '' })} /><TextInput label="Alias" value={source.alias} onChange={(event) => patch({ alias: event.currentTarget?.value || '' })} spellCheck={false} />{source.type === 'DATABASE' ? <><Select label="Connection" searchable data={dataSources.map((item) => ({ value: String(item.id), label: `${item.name} (${item.kind})` }))} value={source.dataSourceId ? String(source.dataSourceId) : null} onChange={(value) => patch({ dataSourceId: value ? Number(value) : null, schema: '', table: '' })} /><Select label="Schema" searchable data={(schemas.data || []).map((item) => item.schema || '').filter(Boolean)} value={source.schema || null} onChange={(value) => patch({ schema: value || '', table: '' })} /><Select label="Table" searchable data={(tables.data || []).map((item) => item.name || item.table || '').filter(Boolean)} value={source.table || null} onChange={(value) => patch({ table: value || '' })} /><TextInput label="Optional filter" value={source.filter || ''} onChange={(event) => patch({ filter: event.currentTarget?.value || '' })} placeholder="status = 'ACTIVE'" /></> : <><Select label="Managed file" searchable data={assets.map((asset) => ({ value: String(asset.id), label: `${asset.name} (${asset.format})` }))} value={source.assetId ? String(source.assetId) : null} onChange={(value) => patch({ assetId: value ? Number(value) : null })} />{onUpload ? <Button mt={24} variant="default" leftSection={<IconUpload size={15} />} onClick={onUpload}>Upload file</Button> : null}</>}</SimpleGrid></div>;
}

function PreviewTable({ preview }: { preview: Preview }) { return <div className="mapx-preview"><Group justify="space-between" my="sm"><Text fw={750}>Preview result</Text><Badge variant="light">{preview.rowCount}{preview.truncated ? '+' : ''} rows</Badge></Group><ScrollArea><Table striped withTableBorder><Table.Thead><Table.Tr>{preview.columns.map((column) => <Table.Th key={column}>{column}</Table.Th>)}</Table.Tr></Table.Thead><Table.Tbody>{preview.rows.slice(0, 100).map((row, index) => <Table.Tr key={index}>{preview.columns.map((column) => <Table.Td key={column}>{display(row[column])}</Table.Td>)}</Table.Tr>)}</Table.Tbody></Table></ScrollArea></div>; }

function AssetModal({ opened, onClose, file, setFile, name, setName, format, setFormat, loading, onUpload }: { opened: boolean; onClose: () => void; file: File | null; setFile: (file: File | null) => void; name: string; setName: (value: string) => void; format: string; setFormat: (value: string) => void; loading: boolean; onUpload: () => void }) { return <Modal opened={opened} onClose={onClose} title="Add managed mapping file" size="lg"><Stack><Text size="sm" c="dimmed">The uploaded file is encrypted with AES-GCM, profiled for columns, and referenced by immutable SHA-256 digest.</Text><FileInput label="CSV, TSV, JSON, JSONL, or XML" value={file} onChange={setFile} accept=".csv,.tsv,.json,.jsonl,.ndjson,.xml" leftSection={<IconFile size={15} />} /><TextInput label="Asset name" value={name} onChange={(event) => setName(event.currentTarget?.value || '')} placeholder={file?.name || 'Customer extract'} /><Select label="Format" value={format} data={['AUTO', 'CSV', 'TSV', 'JSON', 'JSONL', 'XML']} onChange={(value) => setFormat(value || 'AUTO')} /><Group justify="flex-end"><Button variant="default" onClick={onClose}>Cancel</Button><Button loading={loading} disabled={!file} onClick={onUpload}>Encrypt and profile</Button></Group></Stack></Modal>; }

function VersionsModal({ mappingId, opened, onClose, canRestore, onRestored }: { mappingId: number | null; opened: boolean; onClose: () => void; canRestore: boolean; onRestored: (mapping: MappingEntity) => void }) { const versions = useQuery({ queryKey: keys.mappings.versions(mappingId), queryFn: () => apiFetch<Array<{ id: number; versionNo: number; specHash: string; createdBy: string; createdAt: string }>>(`/api/mappings/${mappingId}/versions`), enabled: opened && !!mappingId }); return <Modal opened={opened} onClose={onClose} title="Immutable mapping versions" size="lg"><Stack>{(versions.data || []).map((version) => <Paper key={version.id} p="sm" withBorder><Group justify="space-between"><div><Text fw={750}>Version {version.versionNo}</Text><Text size="xs" c="dimmed">{new Date(version.createdAt).toLocaleString()} by {version.createdBy}</Text><Text size="xs" className="mapx-hash">{version.specHash}</Text></div>{canRestore ? <Button size="xs" variant="default" onClick={async () => { if (!mappingId) return; const restored = await apiPost<MappingEntity>(`/api/mappings/${mappingId}/versions/${version.id}/restore`, {}); onRestored(restored); onClose(); }}>Restore as new version</Button> : null}</Group></Paper>)}{!versions.data?.length ? <Text c="dimmed" ta="center" py="lg">No versions found.</Text> : null}</Stack></Modal>; }

async function fetchSourceColumnMeta(source: MappingSource, assets: MappingAsset[]) { if (source.type === 'FILE') return columnMetaForSource(source, assets); if (!source.dataSourceId || !source.table) return []; return fetchDbColumnMeta(source.dataSourceId, source.schema || '', source.table); }
async function fetchDbColumnMeta(dataSourceId: number, schema: string, table: string) { const rows = await apiFetch<Array<Record<string, unknown>>>(`/api/datasources/${dataSourceId}/tables/${encodeURIComponent(table)}/columns?schema=${encodeURIComponent(schema)}`); return rows.map((row) => ({ name: String(row.name || row.column || row.columnName || ''), type: String(row.type || row.dataType || row.typeName || '') })).filter((column) => column.name); }
function columnsForSource(source: MappingSource, assets: MappingAsset[]) { if (source.type !== 'FILE' || !source.assetId) return []; const asset = assets.find((item) => item.id === source.assetId); try { return (JSON.parse(asset?.schemaJson || '[]') as Array<{ name: string }>).map((column) => column.name); } catch { return []; } }
function columnMetaForSource(source: MappingSource, assets: MappingAsset[]) { if (source.type !== 'FILE' || !source.assetId) return []; const asset = assets.find((item) => item.id === source.assetId); try { return (JSON.parse(asset?.schemaJson || '[]') as Array<{ name: string; type?: string }>).map((column) => ({ name: column.name, type: column.type || '' })); } catch { return []; } }
function toV2(mapping: MappingEntity): MappingSpec {
  const parsed = JSON.parse(mapping.specJson || '{}') as Record<string, unknown>;
  if (Array.isArray(parsed.sources)) {
    const current = parsed as unknown as MappingSpec;
    return { ...current, transforms: current.transforms || [], canvas: current.canvas || { positions: {} } };
  }

  const legacyTables = Array.isArray(parsed.tables) ? parsed.tables as Array<Record<string, unknown>> : [];
  const aliases = new Set<string>();
  const sources: MappingSource[] = legacyTables.map((table, index) => {
    const base = normalizeAlias(String(table.alias || table.name || `source_${index + 1}`));
    let alias = base; let suffix = 2;
    while (aliases.has(alias)) alias = `${base}_${suffix++}`;
    aliases.add(alias);
    return {
      id: crypto.randomUUID(), type: 'DATABASE', alias,
      dataSourceId: Number(table.dsId || table.dataSourceId || 0) || null,
      schema: String(table.schema || ''), table: String(table.name || table.table || ''), filter: ''
    };
  });
  const target = asRecord(parsed.target);
  const mode = String(target.mode || 'PREVIEW').toUpperCase();
  const targetType = mode === 'TABLE' || mode === 'DATABASE' ? 'DATABASE' : mode === 'CSV' || mode === 'JSON' || mode === 'JSONL' || mode === 'FILE' ? 'FILE' : 'PREVIEW';
  const legacyColumns = Array.isArray(parsed.colmap) ? parsed.colmap as Array<Record<string, unknown>> : [];
  const legacyJoins = Array.isArray(parsed.joins) ? parsed.joins as Array<Record<string, unknown>> : [];
  const transforms = Array.isArray(parsed.transforms) ? parsed.transforms as Array<Record<string, unknown>> : [];
  const limit = transforms.find((transform) => String(transform.type || '').toUpperCase() === 'LIMIT');
  const canvas = asRecord(parsed.canvas);

  return {
    ...emptySpec(),
    sources: sources.length ? sources : emptySpec().sources,
    joins: legacyJoins.map((join) => ({
      id: crypto.randomUUID(),
      type: (String(join.type || 'INNER').toUpperCase() as MappingSpec['joins'][number]['type']),
      left: String(join.left || `${join.leftTable || ''}.${join.leftCol || ''}`),
      right: String(join.right || `${join.rightTable || ''}.${join.rightCol || ''}`)
    })),
    columns: legacyColumns.map((column) => ({
      id: crypto.randomUUID(), target: String(column.target || ''), source: String(column.source || ''), action: 'COPY'
    })),
    transforms: transforms.map((transform, index) => ({ ...transform, id: String(transform.id || `legacy-transform-${index}`), type: String(transform.type || 'TRANSFORM') })),
    canvas: {
      positions: {}, zoom: Number(canvas.zoom || 1), view: String(canvas.view || 'normal'),
      legacyNodes: asRecord(canvas.nodes), legacyLinks: Array.isArray(canvas.links) ? canvas.links : []
    },
    sql: typeof parsed.sql === 'string' ? parsed.sql : undefined,
    loadStatements: Array.isArray(parsed.loadStatements) ? parsed.loadStatements.map(String) : undefined,
    loadTargets: Array.isArray(parsed.loadTargets) ? parsed.loadTargets.map(asRecord) : undefined,
    rowLimit: limit && Number(limit.rows || 0) > 0 ? Number(limit.rows) : null,
    target: {
      type: targetType,
      dataSourceId: Number(target.dsId || target.dataSourceId || 0) || null,
      schema: String(target.schema || ''), table: String(target.table || ''), preAction: 'NONE',
      format: mode === 'JSON' || mode === 'JSONL' ? mode : 'CSV'
    }
  };
}
function asRecord(value: unknown): Record<string, unknown> { return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}; }
function normalizeAlias(value: string) { const alias = value.toLowerCase().replace(/[^a-z0-9_]/g, '_').replace(/^_+/, ''); return /^[a-z_]/.test(alias) ? alias || 'source' : `source_${alias || '1'}`; }
function normalize(value: string) { return value.toLowerCase().replace(/[^a-z0-9]/g, ''); }
function allows(source: DataSource, role: string) { return source.role === 'BOTH' || source.role === role; }
function display(value: unknown) { if (value == null) return 'NULL'; if (typeof value === 'object') return JSON.stringify(value); const text = String(value); return text.length > 160 ? `${text.slice(0, 157)}...` : text; }
function notifyError(title: string, error: unknown) { notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) }); }
