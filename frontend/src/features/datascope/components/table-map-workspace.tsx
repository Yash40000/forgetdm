'use client';

import { useEffect, useMemo, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Checkbox,
  Collapse,
  Drawer,
  Group,
  Loader,
  NumberInput,
  Paper,
  Radio,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Text,
  TextInput,
  Tooltip
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import {
  IconAdjustmentsHorizontal,
  IconAlertTriangle,
  IconArrowRight,
  IconChevronDown,
  IconDatabase,
  IconFolderOpen,
  IconPlus
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';

import { DataTable } from '@/components/data-table';
import { apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { ColumnOverride, DataSetDefinition, DataSource, MaskingPolicy, TableProfile } from '@/lib/types';
import { useSchemas, useTables } from '../hooks';
import {
  catalogHasName,
  catalogName,
  defaultsFromBlueprint,
  definitionPayload,
  duplicateTargets,
  emptyToNull,
  equalsIgnoreCase,
  isProfileIncluded,
  normalizeProfilesForSave,
  numberOrNull,
  policyName,
  profileIdentityKey,
  profileIdentityKeyFor,
  Q_MODE_OPTIONS,
  qModePatch,
  qModeValue,
  resolveDataSourceInput,
  sameNumber,
  sourceName,
  targetKey,
  technicalInputProps,
  type TableMapDefaults
} from '../utils';
import { ColumnMapDrawer } from './column-map-drawer';
import { DataSourceBrowseModal, SchemaBrowseModal } from './browse-modals';

type CatalogTableRow = { table: string; alreadyProfiled: boolean };

/**
 * Table-map editor. Render with key={blueprint.id} so switching blueprints resets
 * the draft. While the draft is dirty, background refetches never overwrite it.
 */
export function TableMapWorkspace({
  blueprint,
  rows,
  overrides,
  dataSources,
  policies,
  loading,
  onDirtyChange
}: {
  blueprint: DataSetDefinition;
  rows: TableProfile[];
  overrides: ColumnOverride[];
  dataSources: DataSource[];
  policies: MaskingPolicy[];
  loading: boolean;
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const blueprintDefaults = useMemo(() => defaultsFromBlueprint(blueprint, dataSources), [blueprint, dataSources]);
  const [defaults, setDefaults] = useState<TableMapDefaults>(blueprintDefaults);
  const [draftRows, setDraftRows] = useState<TableProfile[]>(rows);
  const [dirty, setDirty] = useState(false);
  const [selectedCatalogTables, setSelectedCatalogTables] = useState<string[]>([]);
  const [sourceBrowseOpened, sourceBrowse] = useDisclosure(false);
  const [targetBrowseOpened, targetBrowse] = useDisclosure(false);
  const [sourceSchemaBrowseOpened, sourceSchemaBrowse] = useDisclosure(false);
  const [targetSchemaBrowseOpened, targetSchemaBrowse] = useDisclosure(false);
  const [subsetControlsOpen, subsetControls] = useDisclosure(
    Boolean(
      blueprintDefaults.driverTable ||
        blueprintDefaults.driverFilter ||
        blueprintDefaults.maxDriverRows ||
        !blueprintDefaults.globalQ1 ||
        !blueprintDefaults.globalQ2
    )
  );
  const [mapOpened, mapDrawer] = useDisclosure(false);
  const [columnProfile, setColumnProfile] = useState<TableProfile | null>(null);
  const [columnOpened, columnDrawer] = useDisclosure(false);

  // Draft-clobber guard: only mirror fresh server data into the draft while it is CLEAN.
  // A background refetch (invalidation from another mutation, remount, etc.) must never
  // discard unsaved edits; after a successful save we mark the draft clean again.
  useEffect(() => {
    if (dirty) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDraftRows(rows);
  }, [rows, dirty]);
  useEffect(() => {
    if (dirty) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDefaults(blueprintDefaults);
  }, [blueprintDefaults, dirty]);

  useEffect(() => {
    onDirtyChange?.(dirty);
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, onDirtyChange]);

  const patchRow = (index: number, patch: Partial<TableProfile>) => {
    setDirty(true);
    setDraftRows((current) => current.map((row, idx) => (idx === index ? { ...row, ...patch } : row)));
  };
  const dropRow = (index: number) => {
    setDirty(true);
    setDraftRows((current) => current.filter((_, idx) => idx !== index));
  };
  const updateDefault = (patch: Partial<TableMapDefaults>) => {
    setDirty(true);
    setDefaults((current) => ({ ...current, ...patch }));
    setSelectedCatalogTables([]);
  };

  const commonSourceId =
    resolveDataSourceInput(defaults.sourceDataSourceId, dataSources) ||
    (!defaults.sourceDataSourceId.trim() ? blueprint.dataSourceId || null : null);
  const commonTargetId =
    resolveDataSourceInput(defaults.targetDataSourceId, dataSources) ||
    (!defaults.targetDataSourceId.trim() ? blueprint.targetDataSourceId || null : null);
  const effectiveBlueprint = useMemo(
    () => ({
      ...blueprint,
      dataSourceId: commonSourceId || blueprint.dataSourceId,
      schemaName: defaults.sourceSchemaName || blueprint.schemaName,
      targetDataSourceId: commonTargetId || blueprint.targetDataSourceId,
      targetSchemaName: defaults.targetSchemaName || blueprint.targetSchemaName
    }),
    [blueprint, commonSourceId, commonTargetId, defaults.sourceSchemaName, defaults.targetSchemaName]
  );

  const sourceSchemasQuery = useSchemas(commonSourceId);
  const targetSchemasQuery = useSchemas(commonTargetId);
  const sourceTablesQuery = useTables(commonSourceId, defaults.sourceSchemaName);

  const sourceDefaultError =
    defaults.sourceDataSourceId.trim() && !commonSourceId ? 'Unknown source DB. Type a valid id/name or use Browse.' : null;
  const targetDefaultError =
    defaults.targetDataSourceId.trim() && !commonTargetId ? 'Unknown target DB. Type a valid id/name or use Browse.' : null;
  const sourceSchemaDefaultError =
    defaults.sourceSchemaName.trim() &&
    sourceSchemasQuery.data?.length &&
    !catalogHasName(sourceSchemasQuery.data, 'schema', defaults.sourceSchemaName)
      ? 'Schema not found in this source. Type a valid schema or use Browse.'
      : null;
  const targetSchemaDefaultError =
    defaults.targetSchemaName.trim() &&
    targetSchemasQuery.data?.length &&
    !catalogHasName(targetSchemasQuery.data, 'schema', defaults.targetSchemaName)
      ? 'Schema not found in this target. Type a valid schema or use Browse.'
      : null;

  const driverOptions = useMemo(
    () =>
      [{ value: '', label: 'No driver (start from every included table)' }].concat(
        [...new Set(draftRows.map((row) => row.tableName.trim()).filter(Boolean))]
          .sort((a, b) => a.localeCompare(b))
          .map((table) => ({ value: table, label: table }))
      ),
    [draftRows]
  );
  const driverMissing =
    !!defaults.driverTable.trim() && !draftRows.some((row) => equalsIgnoreCase(row.tableName, defaults.driverTable));

  const saveWorkspace = useMutation({
    mutationFn: async () => {
      if (defaults.sourceDataSourceId.trim() && !commonSourceId) throw new Error('Source DB does not match a known data source.');
      if (defaults.targetDataSourceId.trim() && !commonTargetId) throw new Error('Target DB does not match a known data source.');
      if (sourceSchemaDefaultError) throw new Error(sourceSchemaDefaultError);
      if (targetSchemaDefaultError) throw new Error(targetSchemaDefaultError);
      if (driverMissing) throw new Error('Driver table is not in this profile anymore. Pick another driver before saving.');
      await apiPut<DataSetDefinition>(`/api/datasets/${blueprint.id}`, definitionPayload(blueprint, defaults, dataSources));
      return apiPut<TableProfile[]>(`/api/datasets/${blueprint.id}/profiles`, normalizeProfilesForSave(draftRows, effectiveBlueprint));
    },
    onSuccess: async (savedRows) => {
      notifications.show({ color: 'green', title: 'DataScope profile saved', message: 'Defaults and table mappings were updated.' });
      setDraftRows(savedRows);
      await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprints });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprint(blueprint.id) });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.profiles(blueprint.id) });
      setDirty(false); // the refetch has caught up, so future server changes may resync this clean draft
    },
    onError: (error) => {
      notifications.show({ color: 'red', title: 'Could not save profile setup', message: error.message });
    }
  });

  const sourceOptions = dataSources
    .filter((item) => ['SOURCE', 'BOTH'].includes(String(item.role || '').toUpperCase()))
    .map((item) => ({ value: String(item.id), label: `${item.name} (${item.kind})` }));
  const sourceCandidates = dataSources.filter((item) => ['SOURCE', 'BOTH'].includes(String(item.role || '').toUpperCase()));
  const targetCandidates = dataSources.filter((item) => ['TARGET', 'BOTH'].includes(String(item.role || '').toUpperCase()));
  const sourceSchemaNames = (sourceSchemasQuery.data || []).map((entry) => catalogName(entry, 'schema')).filter(Boolean);
  const targetSchemaNames = (targetSchemasQuery.data || []).map((entry) => catalogName(entry, 'schema')).filter(Boolean);
  const policyOptions = [{ value: '', label: 'No table policy' }].concat(
    policies.map((item) => ({ value: String(item.id), label: item.name }))
  );

  const duplicates = duplicateTargets(draftRows);
  const included = draftRows.filter(isProfileIncluded);
  const profiledTableKeys = useMemo(
    () => new Set(draftRows.map((profile) => profileIdentityKey(profile, effectiveBlueprint)).filter(Boolean)),
    [draftRows, effectiveBlueprint]
  );
  const catalogRows = useMemo<CatalogTableRow[]>(() => {
    const names = (sourceTablesQuery.data || [])
      .map((entry) => catalogName(entry, 'table'))
      .filter(Boolean)
      .sort((a, b) => a.localeCompare(b))
      .slice(0, 500);
    return names.map((table) => ({
      table,
      alreadyProfiled: profiledTableKeys.has(profileIdentityKeyFor(commonSourceId, defaults.sourceSchemaName, table))
    }));
  }, [sourceTablesQuery.data, profiledTableKeys, commonSourceId, defaults.sourceSchemaName]);

  const catalogColumns = useMemo<ColumnDef<CatalogTableRow>[]>(
    () => [
      {
        id: 'add',
        header: 'Add',
        enableSorting: false,
        cell: ({ row }) => (
          <Checkbox
            aria-label={`Add ${row.original.table}`}
            checked={selectedCatalogTables.some((item) => equalsIgnoreCase(item, row.original.table))}
            disabled={row.original.alreadyProfiled}
            onChange={(event) => {
              const checked = event.currentTarget.checked;
              setSelectedCatalogTables((current) =>
                checked ? current.concat(row.original.table) : current.filter((item) => !equalsIgnoreCase(item, row.original.table))
              );
            }}
          />
        )
      },
      {
        accessorKey: 'table',
        header: 'Table',
        cell: ({ row }) => <Text fw={700}>{row.original.table}</Text>
      },
      {
        id: 'status',
        header: 'Status',
        accessorFn: (row) => (row.alreadyProfiled ? 'already in profile' : 'available'),
        cell: ({ row }) =>
          row.original.alreadyProfiled ? (
            <Badge color="gray" variant="light">
              already in profile
            </Badge>
          ) : (
            <Badge color="green" variant="light">
              available
            </Badge>
          )
      }
    ],
    [selectedCatalogTables]
  );

  const addSelectedTables = () => {
    if (!commonSourceId) {
      notifications.show({ color: 'red', title: 'Choose source first', message: 'Select a source DB before adding tables.' });
      return;
    }
    const additions = selectedCatalogTables.filter(
      (table) =>
        catalogRows.some((row) => equalsIgnoreCase(row.table, table)) &&
        !profiledTableKeys.has(profileIdentityKeyFor(commonSourceId, defaults.sourceSchemaName, table))
    );
    if (!additions.length) {
      notifications.show({ color: 'yellow', title: 'No new tables selected', message: 'Selected tables are already in this profile.' });
      return;
    }
    setDirty(true);
    setDraftRows((current) =>
      current.concat(
        additions.map((table) => ({
          datasetId: blueprint.id,
          sourceDataSourceId: sameNumber(commonSourceId, blueprint.dataSourceId) ? null : commonSourceId,
          sourceSchemaName:
            defaults.sourceSchemaName && !equalsIgnoreCase(defaults.sourceSchemaName, blueprint.schemaName || '')
              ? defaults.sourceSchemaName
              : null,
          tableName: table,
          targetTableName: null,
          policyId: blueprint.policyId || null,
          included: false,
          filterExpr: null,
          rowLimit: null,
          referentialStrategy: 'INHERIT',
          note: null
        }))
      )
    );
    setSelectedCatalogTables([]);
  };

  const renderSaveButton = () => (
    <Group gap="xs">
      {dirty ? (
        <Badge color="yellow" variant="light">
          unsaved changes
        </Badge>
      ) : null}
      <Button loading={saveWorkspace.isPending} disabled={!!duplicates.size} onClick={() => saveWorkspace.mutate()}>
        Save profile setup
      </Button>
    </Group>
  );

  const browseButton = (label: string, action: () => void, disabled = false, loadingState = false) => (
    <Tooltip label={label} withArrow>
      <ActionIcon
        aria-label={label}
        title={label}
        variant="light"
        size={36}
        disabled={disabled}
        loading={loadingState}
        onClick={action}
      >
        <IconFolderOpen size={17} />
      </ActionIcon>
    </Tooltip>
  );

  const renderConnectionDefaults = () => (
    <div className="ds-profile-path">
      <div className="ds-profile-endpoint">
        <Group gap={7} mb={6}>
          <IconDatabase size={15} />
          <Text size="xs" fw={750} tt="uppercase" c="dimmed">
            Source
          </Text>
        </Group>
        <div className="ds-profile-endpoint-fields">
          <Group align="flex-end" wrap="nowrap" gap="xs">
            <TextInput
              {...technicalInputProps}
              label="Database"
              placeholder="name or id"
              value={defaults.sourceDataSourceId}
              error={sourceDefaultError}
              onChange={(event) => updateDefault({ sourceDataSourceId: event.currentTarget.value, sourceSchemaName: '' })}
              style={{ flex: 1 }}
            />
            {browseButton('Browse source databases', sourceBrowse.open)}
          </Group>
          <Group align="flex-end" wrap="nowrap" gap="xs">
            <TextInput
              {...technicalInputProps}
              label="Schema"
              placeholder="schema"
              value={defaults.sourceSchemaName}
              error={sourceSchemaDefaultError}
              onChange={(event) => updateDefault({ sourceSchemaName: event.currentTarget.value })}
              style={{ flex: 1 }}
            />
            {browseButton('Browse source schemas', sourceSchemaBrowse.open, !commonSourceId, sourceSchemasQuery.isFetching)}
          </Group>
        </div>
      </div>

      <IconArrowRight className="ds-profile-path-arrow" size={20} aria-hidden />

      <div className="ds-profile-endpoint">
        <Group gap={7} mb={6}>
          <IconDatabase size={15} />
          <Text size="xs" fw={750} tt="uppercase" c="dimmed">
            Target
          </Text>
        </Group>
        <div className="ds-profile-endpoint-fields">
          <Group align="flex-end" wrap="nowrap" gap="xs">
            <TextInput
              {...technicalInputProps}
              label="Database"
              placeholder="name or id"
              value={defaults.targetDataSourceId}
              error={targetDefaultError}
              onChange={(event) => updateDefault({ targetDataSourceId: event.currentTarget.value, targetSchemaName: '' })}
              style={{ flex: 1 }}
            />
            {browseButton('Browse target databases', targetBrowse.open)}
          </Group>
          <Group align="flex-end" wrap="nowrap" gap="xs">
            <TextInput
              {...technicalInputProps}
              label="Schema"
              placeholder="schema"
              value={defaults.targetSchemaName}
              error={targetSchemaDefaultError}
              onChange={(event) => updateDefault({ targetSchemaName: event.currentTarget.value })}
              style={{ flex: 1 }}
            />
            {browseButton('Browse target schemas', targetSchemaBrowse.open, !commonTargetId, targetSchemasQuery.isFetching)}
          </Group>
        </div>
      </div>
    </div>
  );

  const renderSubsetControls = () => (
    <>
      <button
        type="button"
        className="ds-subset-toggle"
        aria-expanded={subsetControlsOpen}
        aria-controls="datascope-subset-controls"
        onClick={subsetControls.toggle}
      >
        <span className="ds-subset-toggle-copy">
          <IconAdjustmentsHorizontal size={17} aria-hidden />
          <span>
            <strong>Subset behavior</strong>
            <small>Choose a driver only when this profile should extract a relational subset.</small>
          </span>
        </span>
        <span className="ds-subset-summary" aria-hidden>
          <Badge size="sm" variant="light" color={defaults.driverTable ? 'blue' : 'gray'}>
            {defaults.driverTable ? `Driver: ${defaults.driverTable}` : 'No driver'}
          </Badge>
          <Badge size="sm" variant="light" color={defaults.globalQ1 ? 'green' : 'gray'}>
            Parents {defaults.globalQ1 ? 'included' : 'off'}
          </Badge>
          <Badge size="sm" variant="light" color={defaults.globalQ2 ? 'blue' : 'gray'}>
            Children {defaults.globalQ2 ? 'included' : 'off'}
          </Badge>
          <IconChevronDown className={subsetControlsOpen ? 'is-open' : ''} size={17} />
        </span>
      </button>
      <Collapse in={subsetControlsOpen}>
        <SimpleGrid id="datascope-subset-controls" cols={{ base: 1, sm: 2, md: 3 }} mt="sm">
          <Select
            label="Driver table"
            description="Rows from this table start the subset."
            data={driverOptions}
            value={defaults.driverTable}
            searchable
            error={driverMissing ? 'Driver is not in this profile anymore. Pick another table.' : null}
            onChange={(value) => updateDefault({ driverTable: value || '' })}
          />
          <TextInput
            {...technicalInputProps}
            label="Driver filter"
            description="Optional SQL WHERE condition."
            placeholder="status = 'ACTIVE'"
            value={defaults.driverFilter}
            disabled={!defaults.driverTable.trim()}
            onChange={(event) => updateDefault({ driverFilter: event.currentTarget.value })}
          />
          <NumberInput
            label="Maximum seed rows"
            description="Optional limit on starting rows."
            min={1}
            value={defaults.maxDriverRows === '' ? '' : Number(defaults.maxDriverRows)}
            disabled={!defaults.driverTable.trim()}
            onChange={(value) => updateDefault({ maxDriverRows: value === '' || value === null ? '' : String(value) })}
          />
          <Select
            label="Include required parents"
            description="Q1: keeps selected child rows from becoming orphans."
            data={[
              { value: 'yes', label: 'Yes - preserve relationships' },
              { value: 'no', label: 'No - parents already exist' }
            ]}
            value={defaults.globalQ1 ? 'yes' : 'no'}
            onChange={(value) => updateDefault({ globalQ1: value !== 'no' })}
          />
          <Select
            label="Include dependent children"
            description="Q2: expands selected parents to their child rows."
            data={[
              { value: 'yes', label: 'Yes - cascade to children' },
              { value: 'no', label: 'No - keep subset smaller' }
            ]}
            value={defaults.globalQ2 ? 'yes' : 'no'}
            onChange={(value) => updateDefault({ globalQ2: value !== 'no' })}
          />
        </SimpleGrid>
      </Collapse>
    </>
  );

  if (loading) {
    return (
      <Group>
        <Loader size="sm" />
        <Text c="dimmed">Loading table map...</Text>
      </Group>
    );
  }

  return (
    <>
      <Stack gap="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={850}>Table Profile Setup</Text>
            <Text size="sm" c="dimmed">
              Pick only the source tables that belong in this DataScope, then open the full Optim-style table map.
            </Text>
          </div>
          <Group gap="xs">
            <Badge variant="light">{included.length} included</Badge>
            <Button variant="light" disabled={!draftRows.length} onClick={mapDrawer.open}>
              Open table map
            </Button>
            {renderSaveButton()}
          </Group>
        </Group>

        {duplicates.size ? (
          <Alert color="red" icon={<IconAlertTriangle size={16} />}>
            A target table can only be used once: {Array.from(duplicates).join(', ')}
          </Alert>
        ) : null}

        <Paper className="forge-card ds-profile-defaults" p="md">
          <Stack gap="sm">
            <Group justify="space-between" align="flex-start">
              <div>
                <Group gap="xs">
                  <Badge size="sm" variant="filled" color="blue">
                    1
                  </Badge>
                  <Text fw={800}>Choose source and target</Text>
                </Group>
                <Text size="sm" c="dimmed">
                  Tables inherit this path by default. You can override it later for any individual table.
                </Text>
              </div>
            </Group>
            {renderConnectionDefaults()}
            {renderSubsetControls()}
          </Stack>
        </Paper>

        <Paper className="forge-card" p="md">
          <Stack gap="sm">
            <Group justify="space-between" align="flex-start">
              <div>
                <Group gap="xs">
                  <Badge size="sm" variant="filled" color="blue">
                    2
                  </Badge>
                  <Text fw={800}>Add tables to profile</Text>
                </Group>
                <Text size="sm" c="dimmed">
                  Browse the selected schema and add only the tables needed for this DataScope. Added rows start unchecked in the final Use flag.
                </Text>
              </div>
              <Group gap="xs">
                <Badge variant="light">{catalogRows.length} source tables</Badge>
                <Badge variant="light" color={selectedCatalogTables.length ? 'blue' : 'gray'}>
                  {selectedCatalogTables.length} selected
                </Badge>
                <Button
                  variant="light"
                  disabled={!catalogRows.some((row) => !row.alreadyProfiled)}
                  onClick={() => setSelectedCatalogTables(catalogRows.filter((row) => !row.alreadyProfiled).map((row) => row.table))}
                >
                  Select all
                </Button>
                <Button variant="light" disabled={!selectedCatalogTables.length} onClick={() => setSelectedCatalogTables([])}>
                  Clear
                </Button>
                <Button leftSection={<IconPlus size={16} />} disabled={!selectedCatalogTables.length} onClick={addSelectedTables}>
                  Add selected
                </Button>
              </Group>
            </Group>
            {!commonSourceId ? (
              <Alert color="blue" variant="light">
                Choose a source DB and schema to browse tables.
              </Alert>
            ) : sourceTablesQuery.isFetching ? (
              <Group>
                <Loader size="sm" />
                <Text c="dimmed">Loading tables...</Text>
              </Group>
            ) : !catalogRows.length ? (
              <Alert color="yellow" icon={<IconAlertTriangle size={16} />}>
                No tables found for this source and schema.
              </Alert>
            ) : (
              <DataTable
                data={catalogRows}
                columns={catalogColumns}
                searchPlaceholder="customer, account, transaction..."
                maxHeight={300}
                tableClassName="ds-picker-table"
                initialSorting={[{ id: 'table', desc: false }]}
              />
            )}
          </Stack>
        </Paper>

        <div>
          <Group justify="space-between" align="flex-end" mb="xs">
            <div>
              <Group gap="xs">
                <Badge size="sm" variant="filled" color="blue">
                  3
                </Badge>
                <Text fw={800}>Review included tables</Text>
              </Group>
              <Text size="sm" c="dimmed">
                Select Use for tables that should run, choose an optional driver, then open Table Map for detailed mappings.
              </Text>
            </div>
            <Badge variant="light">{draftRows.length} profiled</Badge>
          </Group>
          <div className="forge-grid-panel">
            <table className="forge-table">
            <thead>
              <tr>
                <th>Use</th>
                <th>Driver</th>
                <th>Profile table</th>
                <th>Source</th>
                <th>Target table</th>
                <th>Policy</th>
                <th>Controls</th>
              </tr>
            </thead>
            <tbody>
              {draftRows.length ? (
                draftRows.map((profile, idx) => (
                  <tr key={`${profile.tableName}-${idx}`}>
                    <td>
                      <Checkbox
                        aria-label={`Use ${profile.tableName}`}
                        checked={isProfileIncluded(profile)}
                        onChange={(event) => patchRow(idx, { included: event.currentTarget.checked })}
                      />
                    </td>
                    <td>
                      <Radio
                        aria-label={`Use ${profile.tableName} as driver`}
                        name="ds-driver-table"
                        checked={equalsIgnoreCase(defaults.driverTable, profile.tableName)}
                        onChange={() => updateDefault({ driverTable: profile.tableName })}
                      />
                    </td>
                    <td>
                      <Group gap={6} wrap="nowrap">
                        <Text fw={750}>{profile.tableName}</Text>
                        {equalsIgnoreCase(defaults.driverTable, profile.tableName) ? (
                          <Badge size="xs" variant="light" color="blue">
                            driver
                          </Badge>
                        ) : null}
                      </Group>
                      <Text size="xs" c="dimmed">
                        {isProfileIncluded(profile) ? 'selected for run' : 'not selected'}
                      </Text>
                    </td>
                    <td>
                      <Text size="sm">{sourceName(profile.sourceDataSourceId || commonSourceId, dataSources)}</Text>
                      <Text size="xs" c="dimmed">
                        {profile.sourceSchemaName || defaults.sourceSchemaName || 'default schema'}
                      </Text>
                    </td>
                    <td>{profile.targetTableName || profile.tableName}</td>
                    <td>{profile.policyId ? policyName(profile.policyId, policies) : 'No table policy'}</td>
                    <td>
                      <Group gap="xs">
                        <Button size="xs" variant="light" onClick={mapDrawer.open}>
                          Map
                        </Button>
                        <Button size="xs" variant="subtle" color="red" onClick={() => dropRow(idx)}>
                          Remove
                        </Button>
                      </Group>
                    </td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan={7}>
                    <Text c="dimmed">No tables are in this profile yet. Add selected tables above before opening Table Map.</Text>
                  </td>
                </tr>
              )}
            </tbody>
            </table>
          </div>
        </div>
      </Stack>

      <Drawer opened={mapOpened} onClose={mapDrawer.close} title="Table Map" size="95%" position="right">
        <Stack gap="md">
          <Paper className="forge-card" p="md">
            <Group justify="space-between" align="flex-start">
              <div>
                <Text fw={850}>Optim-style table map</Text>
                <Text size="sm" c="dimmed">
                  One row per profiled table. Use common defaults at the top, then override individual source/table settings as needed.
                </Text>
              </div>
              <Group gap="xs">
                <Badge variant="light">{included.length} included</Badge>
                {renderSaveButton()}
              </Group>
            </Group>
            <Stack gap="sm" mt="sm">
              {renderConnectionDefaults()}
              {renderSubsetControls()}
            </Stack>
          </Paper>

          {duplicates.size ? (
            <Alert color="red" icon={<IconAlertTriangle size={16} />}>
              A target table can only be used once: {Array.from(duplicates).join(', ')}
            </Alert>
          ) : null}

          {draftRows.length ? (
            <div className="forge-grid-panel">
              <ScrollArea type="always">
                <table className="forge-table ds-map-table">
                  <thead>
                    <tr>
                      <th>Use</th>
                      <th>Driver</th>
                      <th>Source DB</th>
                      <th>Schema</th>
                      <th>Source table</th>
                      <th>Target table</th>
                      <th>Policy</th>
                      <th>Row limit</th>
                      <th>Filter / condition</th>
                      <th>Strategy</th>
                      <th title="Q1 child-to-parent: pull this table's parent rows? Global = the Q1 default above; Yes/No forces it; Defer = pull parents only after the primary closure finishes (tames FK cycles).">
                        Q1 parents
                      </th>
                      <th title="Q2 parent-to-child: cascade from this table's rows down to its children? Global = the Q2 default above; Yes/No forces it; Defer = cascade only after the primary closure finishes (tames FK cycles).">
                        Q2 children
                      </th>
                      <th>Note</th>
                      <th>Column map</th>
                      <th>Remove</th>
                    </tr>
                  </thead>
                  <tbody>
                    {draftRows.map((profile, idx) => {
                      const target = profile.targetTableName || profile.tableName;
                      const duplicate = duplicates.has(targetKey(target));
                      const tableOverrides = overrides.filter((item) => equalsIgnoreCase(item.tableName, profile.tableName));
                      return (
                        <tr key={`${profile.tableName}-${idx}`}>
                          <td>
                            <Checkbox
                              aria-label={`Include ${profile.tableName}`}
                              checked={isProfileIncluded(profile)}
                              onChange={(event) => patchRow(idx, { included: event.currentTarget.checked })}
                            />
                          </td>
                          <td>
                            <Radio
                              aria-label={`Use ${profile.tableName} as driver`}
                              name="ds-driver-table-map"
                              checked={equalsIgnoreCase(defaults.driverTable, profile.tableName)}
                              onChange={() => updateDefault({ driverTable: profile.tableName })}
                            />
                          </td>
                          <td>
                            <Select
                              data={sourceOptions}
                              value={String(profile.sourceDataSourceId || commonSourceId || '')}
                              searchable
                              onChange={(value) =>
                                patchRow(idx, { sourceDataSourceId: sameNumber(value, commonSourceId) ? null : numberOrNull(value) })
                              }
                            />
                          </td>
                          <td>
                            <TextInput
                              {...technicalInputProps}
                              value={profile.sourceSchemaName || defaults.sourceSchemaName || ''}
                              placeholder="default"
                              onChange={(event) =>
                                patchRow(idx, {
                                  sourceSchemaName:
                                    event.currentTarget.value && !equalsIgnoreCase(event.currentTarget.value, defaults.sourceSchemaName)
                                      ? event.currentTarget.value
                                      : null
                                })
                              }
                            />
                          </td>
                          <td>
                            <TextInput
                              {...technicalInputProps}
                              value={profile.tableName}
                              onChange={(event) => patchRow(idx, { tableName: event.currentTarget.value })}
                            />
                          </td>
                          <td>
                            <TextInput
                              {...technicalInputProps}
                              error={duplicate ? 'Already used' : null}
                              value={target}
                              onChange={(event) => {
                                const value = event.currentTarget.value;
                                patchRow(idx, { targetTableName: value && !equalsIgnoreCase(value, profile.tableName) ? value : null });
                              }}
                            />
                          </td>
                          <td>
                            <Select
                              data={policyOptions}
                              value={String(profile.policyId || '')}
                              searchable
                              onChange={(value) => patchRow(idx, { policyId: numberOrNull(value) })}
                            />
                          </td>
                          <td>
                            <NumberInput
                              min={0}
                              value={profile.rowLimit ?? ''}
                              onChange={(value) => patchRow(idx, { rowLimit: numberOrNull(value) })}
                            />
                          </td>
                          <td>
                            <TextInput
                              {...technicalInputProps}
                              placeholder="status = 'ACTIVE'"
                              value={profile.filterExpr || profile.filterSql || ''}
                              onChange={(event) => patchRow(idx, { filterExpr: emptyToNull(event.currentTarget.value) })}
                            />
                          </td>
                          <td>
                            <Select
                              data={[
                                { value: 'INHERIT', label: 'Inherit Q1/Q2' },
                                { value: 'FOLLOW_PARENT', label: 'Follow parent' },
                                { value: 'INDEPENDENT', label: 'Independent start' }
                              ]}
                              value={profile.referentialStrategy || profile.strategy || 'INHERIT'}
                              onChange={(value) => patchRow(idx, { referentialStrategy: value || 'INHERIT' })}
                            />
                          </td>
                          <td>
                            <Select
                              data={Q_MODE_OPTIONS}
                              value={qModeValue(profile.q1Mode, profile.q1Override)}
                              onChange={(value) => patchRow(idx, qModePatch('q1', value))}
                            />
                          </td>
                          <td>
                            <Select
                              data={Q_MODE_OPTIONS}
                              value={qModeValue(profile.q2Mode, profile.q2Override)}
                              onChange={(value) => patchRow(idx, qModePatch('q2', value))}
                            />
                          </td>
                          <td>
                            <TextInput
                              value={profile.note || ''}
                              placeholder="optional"
                              onChange={(event) => patchRow(idx, { note: emptyToNull(event.currentTarget.value) })}
                            />
                          </td>
                          <td className="ds-map-actions">
                            <Group gap={6} wrap="nowrap">
                              <Button
                                size="xs"
                                variant="light"
                                onClick={() => {
                                  setColumnProfile(profile);
                                  columnDrawer.open();
                                }}
                              >
                                Columns
                              </Button>
                              <Badge variant="light" color={tableOverrides.length ? 'blue' : 'gray'}>
                                {tableOverrides.length}
                              </Badge>
                            </Group>
                          </td>
                          <td>
                            <Button size="xs" variant="subtle" color="red" onClick={() => dropRow(idx)}>
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
          ) : (
            <Alert color="blue" variant="light">
              Add tables to this profile first, then reopen Table Map.
            </Alert>
          )}
        </Stack>
      </Drawer>

      <DataSourceBrowseModal
        opened={sourceBrowseOpened}
        onClose={sourceBrowse.close}
        title="Browse Source DB"
        candidates={sourceCandidates}
        onPick={(source) => updateDefault({ sourceDataSourceId: source.name, sourceSchemaName: '' })}
      />
      <DataSourceBrowseModal
        opened={targetBrowseOpened}
        onClose={targetBrowse.close}
        title="Browse Target DB"
        candidates={targetCandidates}
        onPick={(target) => updateDefault({ targetDataSourceId: target.name, targetSchemaName: '' })}
      />
      <SchemaBrowseModal
        opened={sourceSchemaBrowseOpened}
        onClose={sourceSchemaBrowse.close}
        title="Browse Source Schema"
        schemas={sourceSchemaNames}
        loading={sourceSchemasQuery.isFetching}
        onPick={(schema) => updateDefault({ sourceSchemaName: schema })}
      />
      <SchemaBrowseModal
        opened={targetSchemaBrowseOpened}
        onClose={targetSchemaBrowse.close}
        title="Browse Target Schema"
        schemas={targetSchemaNames}
        loading={targetSchemasQuery.isFetching}
        onPick={(schema) => updateDefault({ targetSchemaName: schema })}
      />

      <ColumnMapDrawer
        opened={columnOpened}
        onClose={columnDrawer.close}
        blueprint={effectiveBlueprint}
        profile={columnProfile}
        policies={policies}
        dataSources={dataSources}
        overrides={overrides}
      />
    </>
  );
}
