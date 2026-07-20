'use client';

import { Fragment, useEffect, useMemo, useRef, useState } from 'react';
import Image from 'next/image';
import {
  ActionIcon,
  Badge,
  Button,
  Divider,
  Drawer,
  Group,
  Loader,
  Modal,
  Paper,
  PasswordInput,
  Select,
  SimpleGrid,
  ScrollArea,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  ThemeIcon,
  Title,
  Tooltip,
  UnstyledButton
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle,
  IconCircleCheck,
  IconDatabase,
  IconDatabaseCog,
  IconEdit,
  IconFolderOpen,
  IconLoader2,
  IconPlugConnected,
  IconRefresh,
  IconSearch,
  IconServer,
  IconTrash,
  IconX
} from '@tabler/icons-react';
import { DiMsqlServer } from 'react-icons/di';
import { FaAws, FaDatabase } from 'react-icons/fa6';
import { GrOracle } from 'react-icons/gr';
import {
  SiGooglebigquery,
  SiMariadb,
  SiMysql,
  SiPostgresql,
  SiSap,
  SiSnowflake,
  SiTeradata
} from 'react-icons/si';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { NameInput } from '@/components/name-input';
import { useConfirm } from '@/components/confirm';
import { usePermissions } from '@/lib/use-permissions';
import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, DataSourceSchema, NativeLoaderStatus } from '@/lib/types';
import { dataSourceSchemasPath, useDataSources, useNativeLoaders } from './hooks';

type Draft = {
  version: number | null;
  name: string;
  kind: string;
  role: string;
  environment: string;
  tags: string;
  jdbcUrl: string;
  username: string;
  password: string;
};

type ProbeState = {
  status: 'idle' | 'testing' | 'ok' | 'error';
  message?: string;
  detail?: string;
};

type SchemaState = {
  status: 'loading' | 'ready' | 'error';
  rows?: DataSourceSchema[];
  message?: string;
};

type ConnectorIssue = {
  severity: 'HIGH' | 'WARN' | 'INFO' | string;
  code: string;
  title: string;
  detail: string;
  remediation: string;
};

type ConnectorDiagnostics = {
  readinessScore: number;
  status: string;
  connection: Record<string, unknown>;
  capabilities: Record<string, unknown>;
  schemaShape: Record<string, unknown>;
  issues: ConnectorIssue[];
  inspectedAt: string;
};

const ENGINE_TEMPLATES: Record<string, string> = {
  POSTGRES: 'jdbc:postgresql://localhost:5432/dbname',
  MYSQL: 'jdbc:mysql://localhost:3306/dbname',
  MARIADB: 'jdbc:mariadb://localhost:3306/dbname',
  ORACLE: 'jdbc:oracle:thin:@localhost:1521/orclpdb1',
  SQLSERVER: 'jdbc:sqlserver://localhost:1433;databaseName=dbname;encrypt=false',
  DB2: 'jdbc:db2://localhost:50000/dbname',
  DB2UDB: 'jdbc:db2://localhost:50000/dbname',
  DB2ZOS: 'jdbc:db2://host:446/LOCATION',
  REDSHIFT: 'jdbc:redshift://cluster.region.redshift.amazonaws.com:5439/dbname',
  SNOWFLAKE: 'jdbc:snowflake://account.snowflakecomputing.com/?db=DATABASE&schema=PUBLIC',
  TERADATA: 'jdbc:teradata://host/DATABASE=dbname,CHARSET=UTF8',
  SYBASE: 'jdbc:sybase:Tds:host:5000/dbname',
  SAP_HANA: 'jdbc:sap://host:30015/?databaseName=dbname',
  BIGQUERY: 'jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=project;',
  H2: 'jdbc:h2:mem:dbname;MODE=PostgreSQL',
  GENERIC: 'jdbc:vendor://host:port/dbname'
};

const ENGINE_OPTIONS = Object.keys(ENGINE_TEMPLATES).map((engine) => ({ value: engine, label: engine }));
const ENGINE_CATALOG: Array<{ value: string; label: string; family: string; description: string }> = [
  { value: 'POSTGRES', label: 'PostgreSQL', family: 'Open source', description: 'Transactional source, target, COPY, discovery, masking, and synthetic loads.' },
  { value: 'ORACLE', label: 'Oracle Database', family: 'Enterprise', description: 'Oracle schemas, LOB streaming, partition workflows, and SQL*Loader.' },
  { value: 'SQLSERVER', label: 'Microsoft SQL Server', family: 'Enterprise', description: 'SQL Server catalogs, adaptive streaming, parameter limits, and bcp.' },
  { value: 'DB2', label: 'IBM Db2', family: 'Enterprise', description: 'Db2 JDBC connection using the dedicated Db2 dialect and LOAD strategy.' },
  { value: 'DB2UDB', label: 'IBM Db2 LUW / UDB', family: 'Enterprise', description: 'Db2 on Linux, UNIX, and Windows, including legacy UDB naming.' },
  { value: 'DB2ZOS', label: 'IBM Db2 for z/OS', family: 'Mainframe', description: 'Type-4 DDF connectivity to Db2 subsystems on IBM z/OS.' },
  { value: 'MYSQL', label: 'MySQL', family: 'Open source', description: 'MySQL catalogs, cursor fetch, rewritten batches, and LOAD DATA.' },
  { value: 'MARIADB', label: 'MariaDB', family: 'Open source', description: 'MariaDB-native JDBC connectivity and MySQL-family load behavior.' },
  { value: 'SNOWFLAKE', label: 'Snowflake', family: 'Cloud warehouse', description: 'Administrator-supplied JDBC driver with staged COPY support.' },
  { value: 'REDSHIFT', label: 'Amazon Redshift', family: 'Cloud warehouse', description: 'Amazon Redshift through an approved plug-in JDBC driver.' },
  { value: 'BIGQUERY', label: 'Google BigQuery', family: 'Cloud warehouse', description: 'BigQuery through an approved plug-in JDBC driver.' },
  { value: 'TERADATA', label: 'Teradata', family: 'Data warehouse', description: 'Teradata through the customer-approved JDBC driver and charset.' },
  { value: 'SAP_HANA', label: 'SAP HANA', family: 'Enterprise', description: 'SAP HANA schemas through the approved SAP JDBC driver.' },
  { value: 'SYBASE', label: 'SAP / Sybase ASE', family: 'Enterprise', description: 'ASE connectivity through an approved jConnect or vendor driver.' },
  { value: 'H2', label: 'H2', family: 'Development', description: 'Embedded development and automated-test database. Not a production target.' },
  { value: 'GENERIC', label: 'Generic JDBC', family: 'Plug-in', description: 'Use an administrator-installed JDBC driver for another database engine.' }
];
const ROLE_OPTIONS = ['SOURCE', 'TARGET', 'BOTH'].map((role) => ({ value: role, label: role }));

const EMPTY_DRAFT: Draft = {
  version: null,
  name: '',
  kind: 'POSTGRES',
  role: 'BOTH',
  environment: '',
  tags: '',
  jdbcUrl: ENGINE_TEMPLATES.POSTGRES,
  username: '',
  password: ''
};

export function DataSourcesPage() {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('datasource.manage');
  const { confirm, confirmElement } = useConfirm();
  const dataSourcesQuery = useDataSources();
  const nativeLoadersQuery = useNativeLoaders();
  const dataSources = useMemo(() => dataSourcesQuery.data || [], [dataSourcesQuery.data]);
  const nativeLoaders = useMemo(() => nativeLoadersQuery.data || [], [nativeLoadersQuery.data]);

  const [draft, setDraft] = useState<Draft>(EMPTY_DRAFT);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [search, setSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState<string | null>(null);
  const [engineFilter, setEngineFilter] = useState<string | null>(null);
  const [testStates, setTestStates] = useState<Record<number, ProbeState>>({});
  const [draftTest, setDraftTest] = useState<ProbeState>({ status: 'idle' });
  const savedTestRequestSequence = useRef<Record<number, number>>({});
  const draftTestRequestSequence = useRef(0);
  const [schemaStates, setSchemaStates] = useState<Record<number, SchemaState>>({});
  const [deleteTarget, setDeleteTarget] = useState<DataSource | null>(null);
  const [diagnosticTarget, setDiagnosticTarget] = useState<DataSource | null>(null);
  const [diagnosticSchema, setDiagnosticSchema] = useState('');
  const [diagnosticReport, setDiagnosticReport] = useState<ConnectorDiagnostics | null>(null);
  const [diagnosticState, setDiagnosticState] = useState<'idle' | 'loading' | 'error'>('idle');
  const [diagnosticError, setDiagnosticError] = useState('');
  const [draftDirty, setDraftDirty] = useState(false);
  const [connectionDrawerOpened, setConnectionDrawerOpened] = useState(false);
  const [nativeLoaderOpened, setNativeLoaderOpened] = useState(false);
  const [enginePickerOpened, setEnginePickerOpened] = useState(false);
  const [enginePickerSearch, setEnginePickerSearch] = useState('');

  useEffect(() => {
    if (!draftDirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [draftDirty]);

  const sourceCount = dataSources.filter((source) => allowsRole(source.role, 'SOURCE')).length;
  const targetCount = dataSources.filter((source) => allowsRole(source.role, 'TARGET')).length;
  const nativeReadyCount = nativeLoaders.filter(loaderReady).length;

  const engineFilterOptions = useMemo(() => {
    const values = new Set([...ENGINE_OPTIONS.map((item) => item.value), ...dataSources.map((source) => source.kind)]);
    return [...values].filter(Boolean).sort().map((value) => ({ value, label: value }));
  }, [dataSources]);

  const visibleEngineCatalog = useMemo(() => {
    const needle = enginePickerSearch.trim().toLowerCase();
    if (!needle) return ENGINE_CATALOG;
    return ENGINE_CATALOG.filter((engine) =>
      [engine.value, engine.label, engine.family, engine.description].join(' ').toLowerCase().includes(needle)
    );
  }, [enginePickerSearch]);

  const filteredSources = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return dataSources.filter((source) => {
      const haystack = [source.name, source.kind, source.role, source.environment, source.tags, source.jdbcUrl, source.username]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      if (needle && !haystack.includes(needle)) return false;
      if (roleFilter && !allowsRole(source.role, roleFilter)) return false;
      if (engineFilter && source.kind !== engineFilter) return false;
      return true;
    });
  }, [dataSources, engineFilter, roleFilter, search]);

  const saveMutation = useMutation({
    mutationFn: async () => {
      if (!canManage) throw new Error('Data source management permission is required.');
      const payload = payloadFromDraft(draft);
      if (!payload.name || !payload.jdbcUrl) {
        throw new Error('Name and JDBC URL are required.');
      }
      return editingId
        ? apiPut<DataSource>(`/api/datasources/${editingId}`, payload)
        : apiPost<DataSource>('/api/datasources', payload);
    },
    onSuccess: (saved) => {
      notifications.show({
        color: 'green',
        title: editingId ? 'Connection updated' : 'Connection created',
        message: `${saved.name} is ready in the data source inventory.`
      });
      setConnectionDrawerOpened(false);
      setEditingId(null);
      setDraft(EMPTY_DRAFT);
      setDraftTest({ status: 'idle' });
      setDraftDirty(false);
      void queryClient.invalidateQueries({ queryKey: keys.dataSources.all });
    },
    onError: (error) => {
      notifications.show({
        color: 'red',
        title: 'Could not save connection',
        message: errorMessage(error)
      });
    }
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => {
      if (!canManage) throw new Error('Data source management permission is required.');
      return apiFetch<void>(`/api/datasources/${id}`, {
        method: 'DELETE'
      });
    },
    onSuccess: (_, deletedId) => {
      notifications.show({ color: 'green', title: 'Connection deleted', message: 'The data source was removed.' });
      if (editingId === deletedId) resetDraft();
      setDeleteTarget(null);
      void queryClient.invalidateQueries({ queryKey: keys.dataSources.all });
    },
    onError: (error) => {
      notifications.show({ color: 'red', title: 'Delete failed', message: errorMessage(error) });
    }
  });

  const updateDraft = (patch: Partial<Draft>) => {
    if (!canManage) return;
    draftTestRequestSequence.current += 1;
    setDraftDirty(true);
    setDraft((current) => ({ ...current, ...patch }));
    setDraftTest({ status: 'idle' });
  };

  const resetDraft = () => {
    draftTestRequestSequence.current += 1;
    setEditingId(null);
    setDraft(EMPTY_DRAFT);
    setDraftTest({ status: 'idle' });
    setDraftDirty(false);
  };

  const confirmDraftDiscard = async () => {
    if (!draftDirty) return true;
    return confirm({
      title: 'Discard unsaved connection changes?',
      message: 'The current connection editor contains changes that have not been saved.',
      okText: 'Discard changes',
      danger: true
    });
  };

  const startEdit = async (source: DataSource) => {
    if (!canManage) return;
    if (source.id === editingId && connectionDrawerOpened) return;
    if (!(await confirmDraftDiscard())) return;
    setEditingId(source.id);
    setDraft(draftFromDataSource(source));
    setDraftTest({ status: 'idle' });
    setDraftDirty(false);
    setConnectionDrawerOpened(true);
  };

  const startNew = async () => {
    if (!canManage) return;
    if (!(await confirmDraftDiscard())) return;
    resetDraft();
    setConnectionDrawerOpened(true);
  };

  const closeConnectionEditor = async () => {
    if (!(await confirmDraftDiscard())) return;
    setConnectionDrawerOpened(false);
    resetDraft();
  };

  const testDraftConnection = async () => {
    if (!canManage) return;
    const sequence = ++draftTestRequestSequence.current;
    setDraftTest({ status: 'testing', message: 'Testing unsaved connection...' });
    try {
      const result = await apiPost<Record<string, unknown>>('/api/datasources/test-connection', payloadFromDraft(draft));
      if (sequence === draftTestRequestSequence.current) {
        setDraftTest({ status: 'ok', message: probeSummary(result), detail: probeDetail(result) });
      }
    } catch (error) {
      if (sequence === draftTestRequestSequence.current) {
        setDraftTest({ status: 'error', message: errorMessage(error) });
      }
    }
  };

  const testSavedConnection = async (source: DataSource) => {
    if (!canManage) return;
    const sequence = (savedTestRequestSequence.current[source.id] ?? 0) + 1;
    savedTestRequestSequence.current[source.id] = sequence;
    setTestStates((current) => ({
      ...current,
      [source.id]: { status: 'testing', message: 'Testing live connection...' }
    }));
    try {
      const result = await apiPost<Record<string, unknown>>(`/api/datasources/${source.id}/test`, {});
      setTestStates((current) => ({
        ...current,
        ...(sequence === savedTestRequestSequence.current[source.id]
          ? { [source.id]: { status: 'ok', message: probeSummary(result), detail: probeDetail(result) } }
          : {})
      }));
    } catch (error) {
      setTestStates((current) => ({
        ...current,
        ...(sequence === savedTestRequestSequence.current[source.id]
          ? { [source.id]: { status: 'error', message: errorMessage(error) } }
          : {})
      }));
    }
  };

  const browseSchemas = async (source: DataSource) => {
    setSchemaStates((current) => ({
      ...current,
      [source.id]: { status: 'loading' }
    }));
    try {
      const rows = await apiFetch<DataSourceSchema[]>(dataSourceSchemasPath(source.id));
      setSchemaStates((current) => ({
        ...current,
        [source.id]: { status: 'ready', rows }
      }));
    } catch (error) {
      setSchemaStates((current) => ({
        ...current,
        [source.id]: { status: 'error', message: errorMessage(error) }
      }));
    }
  };

  const inspectConnection = async (source: DataSource, schema = diagnosticSchema) => {
    setDiagnosticTarget(source);
    setDiagnosticState('loading');
    setDiagnosticError('');
    try {
      const query = new URLSearchParams({ maxTables: '500' });
      if (schema.trim()) query.set('schema', schema.trim());
      const report = await apiFetch<ConnectorDiagnostics>(`/api/datasources/${source.id}/diagnostics?${query}`);
      setDiagnosticReport(report);
      setDiagnosticState('idle');
    } catch (error) {
      setDiagnosticState('error');
      setDiagnosticError(errorMessage(error));
    }
  };

  return (
    <main className="forge-page datasources-page">
        {confirmElement}
        <Stack gap="lg">
          <Group justify="space-between" align="flex-start">
            <Group className="dsx-page-heading" gap="md" align="center" wrap="nowrap">
              <ThemeIcon className="dsx-page-mark" size={48} radius={8} variant="light">
                <IconDatabaseCog size={25} />
              </ThemeIcon>
              <div>
                <Group gap="sm" align="center">
                  <Title order={1}>Data Sources</Title>
                  <Badge className="dsx-live-badge" variant="light" color="teal" leftSection={<span className="dsx-live-dot" />}>
                    {dataSources.length} connected
                  </Badge>
                </Group>
                <Text c="dimmed" className="dsx-page-description">
                  Connect, test, and govern every source and delivery target.
                </Text>
              </div>
            </Group>
            <Group gap="sm">
              {dataSourcesQuery.isFetching || nativeLoadersQuery.isFetching ? <Loader size="sm" /> : null}
              <Button
                leftSection={<IconServer size={16} />}
                variant="subtle"
                onClick={() => setNativeLoaderOpened(true)}
              >
                Native loaders {nativeReadyCount}/{nativeLoaders.length || 0}
              </Button>
              <Button
                leftSection={<IconRefresh size={16} />}
                variant="default"
                onClick={() => {
                  void queryClient.invalidateQueries({ queryKey: keys.dataSources.all });
                  void queryClient.invalidateQueries({ queryKey: keys.dataSources.nativeLoaders });
                }}
              >
                Refresh
              </Button>
              {canManage ? (
                <Button leftSection={<IconPlugConnected size={16} />} onClick={() => void startNew()}>
                  Add connection
                </Button>
              ) : null}
            </Group>
          </Group>

          <QueryErrorBanner
            errors={[dataSourcesQuery.error, nativeLoadersQuery.error]}
            onRetry={() => Promise.all([dataSourcesQuery.refetch(), nativeLoadersQuery.refetch()])}
            title="Data source inventory could not be loaded"
          />

          <section className="dsx-workspace">
            <Paper className="dsx-panel dsx-inventory-panel" p={0}>
              <div className="dsx-panel-head">
                <div>
                  <Text fw={750}>Connection inventory</Text>
                  <Text size="sm" c="dimmed">
                    One row per connection, with health and actions beside the system they affect.
                  </Text>
                </div>
                <div className="dsx-inventory-summary" aria-label="Connection inventory summary">
                  <span><b>{sourceCount}</b><small>Sources</small></span>
                  <span><b>{targetCount}</b><small>Targets</small></span>
                  <span className={nativeReadyCount === nativeLoaders.length && nativeLoaders.length ? 'is-ready' : 'is-warning'}><b>{nativeReadyCount}/{nativeLoaders.length || 0}</b><small>Native ready</small></span>
                  <span><b>{filteredSources.length}</b><small>Shown</small></span>
                </div>
              </div>
              <div className="dsx-filters">
                <TextInput
                  leftSection={<IconSearch size={15} />}
                  placeholder="Search name, engine, JDBC URL, tag..."
                  value={search}
                  onChange={(event) => setSearch(event.currentTarget?.value || '')}
                />
                <Select
                  placeholder="Any role"
                  data={ROLE_OPTIONS}
                  value={roleFilter}
                  onChange={setRoleFilter}
                  clearable
                />
                <Select
                  placeholder="Any engine"
                  data={engineFilterOptions}
                  value={engineFilter}
                  onChange={setEngineFilter}
                  searchable
                  clearable
                />
              </div>
              <div className="dsx-table-wrap">
                <Table verticalSpacing="xs" horizontalSpacing="md" striped={false} highlightOnHover>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>Connection</Table.Th>
                      <Table.Th>Engine</Table.Th>
                      <Table.Th>Role</Table.Th>
                      <Table.Th>Environment</Table.Th>
                      <Table.Th>Last test</Table.Th>
                      <Table.Th ta="right">Tools</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {filteredSources.length ? (
                      filteredSources.map((source) => {
                        const test = testStates[source.id] || { status: 'idle' as const };
                        const schemaState = schemaStates[source.id];
                        const tags = connectionTags(source.tags);
                        return (
                          <Fragment key={source.id}>
                            <Table.Tr className={`dsx-connection-row ${editingId === source.id ? 'is-editing' : ''}`}>
                              <Table.Td>
                                <div className="dsx-source-name-cell">
                                  <Group gap={7} wrap="nowrap">
                                    <span className="dsx-connection-status" aria-hidden="true" />
                                    <Text fw={780}>{source.name}</Text>
                                  </Group>
                                  <Text size="xs" c="dimmed" className="dsx-mono-line">
                                    {jdbcDisplay(source.jdbcUrl)}
                                  </Text>
                                  {tags.length ? <Group gap={4} className="dsx-tag-list">{tags.slice(0, 3).map((tag) => <Badge key={tag} size="xs" variant="light" color="gray">{tag}</Badge>)}{tags.length > 3 ? <Text size="xs" c="dimmed">+{tags.length - 3}</Text> : null}</Group> : null}
                                </div>
                              </Table.Td>
                              <Table.Td>
                                <div className="dsx-engine-cell">
                                  <span className="dsx-engine-cell-logo"><EngineLogo engine={source.kind || 'GENERIC'} size={21} /></span>
                                  <span><b>{engineLabel(source.kind || 'GENERIC')}</b><small>{source.kind || 'GENERIC'}</small></span>
                                </div>
                              </Table.Td>
                              <Table.Td>
                                <RoleBadge role={source.role} />
                              </Table.Td>
                              <Table.Td><EnvironmentBadge environment={source.environment} /></Table.Td>
                              <Table.Td>
                                <ProbeBadge state={test} />
                                {test.message ? <Text size="xs" c="dimmed" mt={4}>{test.message}</Text> : null}
                              </Table.Td>
                              <Table.Td>
                                <Group gap={4} wrap="nowrap" justify="flex-end" className="dsx-row-tools">
                                  {canManage ? (
                                    <Tooltip label="Test connection">
                                      <ActionIcon size="lg" variant="light" loading={test.status === 'testing'} aria-label={`Test ${source.name}`} onClick={() => void testSavedConnection(source)}>
                                        <IconPlugConnected size={16} />
                                      </ActionIcon>
                                    </Tooltip>
                                  ) : null}
                                  <Tooltip label="Browse schemas">
                                    <ActionIcon size="lg" variant="subtle" loading={schemaState?.status === 'loading'} aria-label={`Browse schemas for ${source.name}`} onClick={() => void browseSchemas(source)}>
                                      <IconFolderOpen size={16} />
                                    </ActionIcon>
                                  </Tooltip>
                                  <Tooltip label="Run diagnostics">
                                    <ActionIcon
                                      size="lg"
                                      variant="subtle"
                                      aria-label={`Diagnose ${source.name}`}
                                      onClick={() => {
                                        setDiagnosticSchema('');
                                        setDiagnosticReport(null);
                                        void inspectConnection(source, '');
                                      }}
                                    >
                                      <IconDatabaseCog size={16} />
                                    </ActionIcon>
                                  </Tooltip>
                                  {canManage ? (
                                    <>
                                      <Tooltip label="Edit connection">
                                        <ActionIcon size="lg" variant="default" aria-label={`Edit ${source.name}`} onClick={() => void startEdit(source)}>
                                          <IconEdit size={16} />
                                        </ActionIcon>
                                      </Tooltip>
                                      <Tooltip label="Delete connection">
                                        <ActionIcon
                                          size="lg"
                                          variant="subtle"
                                          color="red"
                                          aria-label={`Delete ${source.name}`}
                                          onClick={() => {
                                            if (canManage) setDeleteTarget(source);
                                          }}
                                        >
                                          <IconTrash size={15} />
                                        </ActionIcon>
                                      </Tooltip>
                                    </>
                                  ) : null}
                                </Group>
                              </Table.Td>
                            </Table.Tr>
                            {schemaState ? (
                              <Table.Tr>
                                <Table.Td colSpan={6}>
                                  <SchemaPreview state={schemaState} />
                                </Table.Td>
                              </Table.Tr>
                            ) : null}
                          </Fragment>
                        );
                      })
                    ) : (
                      <Table.Tr>
                        <Table.Td colSpan={6}>
                          <div className="dsx-empty">
                            <ThemeIcon variant="light" color="gray" size={34}>
                              <IconDatabase size={18} />
                            </ThemeIcon>
                            <div>
                              <Text fw={700}>No matching connections</Text>
                              <Text size="sm" c="dimmed">
                                Clear filters or use Add connection above.
                              </Text>
                            </div>
                          </div>
                        </Table.Td>
                      </Table.Tr>
                    )}
                  </Table.Tbody>
                </Table>
              </div>
            </Paper>

            <Drawer
              opened={canManage && connectionDrawerOpened}
              onClose={() => void closeConnectionEditor()}
              position="right"
              size={560}
              zIndex={300}
              title={editingId ? 'Edit connection' : 'Add connection'}
              overlayProps={{ backgroundOpacity: 0.32, blur: 2 }}
              classNames={{ body: 'dsx-connection-drawer-body' }}
            >
              <Group justify="space-between" align="center" mb="md">
                <Text size="sm" c="dimmed">
                  Test before saving. A blank password keeps the saved secret when editing.
                </Text>
                <Group gap={6} wrap="nowrap">
                  {draftDirty ? <Badge color="yellow" variant="light">Unsaved</Badge> : null}
                  {editingId ? <Badge variant="light">ID {editingId}</Badge> : <Badge color="blue">Draft</Badge>}
                </Group>
              </Group>
              <Stack gap="sm">
                <NameInput
                  label="Name"
                  placeholder="sourceDB, targetDB, customer360-prod"
                  value={draft.name}
                  disabled={!canManage}
                  onChange={(value) => updateDraft({ name: value })}
                />
                <Group grow align="flex-start">
                  <TextInput
                    label="Engine"
                    description="Choose from the supported connector catalog"
                    value={engineLabel(draft.kind)}
                    readOnly
                    spellCheck={false}
                    leftSection={<EngineLogo engine={draft.kind} size={17} />}
                    rightSection={
                      <Tooltip label="Browse database engines" withinPortal>
                        <ActionIcon
                          variant="subtle"
                          aria-label="Browse database engines"
                          disabled={!canManage}
                          onClick={() => {
                            if (canManage) setEnginePickerOpened(true);
                          }}
                        >
                          <IconFolderOpen size={17} />
                        </ActionIcon>
                      </Tooltip>
                    }
                    styles={{ section: { pointerEvents: 'auto' } }}
                    disabled={!canManage}
                    onClick={() => {
                      if (canManage) setEnginePickerOpened(true);
                    }}
                  />
                  <Select
                    label="Role"
                    data={ROLE_OPTIONS}
                    value={draft.role}
                    disabled={!canManage}
                    onChange={(value) => updateDraft({ role: value || 'BOTH' })}
                  />
                </Group>
                <Group grow align="flex-start">
                  <TextInput
                    label="Environment"
                    placeholder="DEV, QA, UAT, PROD"
                    value={draft.environment}
                    disabled={!canManage}
                    onChange={(event) => updateDraft({ environment: event.currentTarget?.value || '' })}
                    spellCheck={false}
                  />
                  <TextInput
                    label="Tags"
                    placeholder="pii, cards, claims"
                    value={draft.tags}
                    disabled={!canManage}
                    onChange={(event) => updateDraft({ tags: event.currentTarget?.value || '' })}
                    spellCheck={false}
                  />
                </Group>
                <Textarea
                  label="JDBC URL"
                  autosize
                  minRows={3}
                  value={draft.jdbcUrl}
                  disabled={!canManage}
                  onChange={(event) => updateDraft({ jdbcUrl: event.currentTarget?.value || '' })}
                  spellCheck={false}
                  classNames={{ input: 'dsx-jdbc-input' }}
                />
                <Group gap="xs">
                  <Button
                    size="xs"
                    variant="default"
                    disabled={!canManage}
                    onClick={() => updateDraft({ jdbcUrl: ENGINE_TEMPLATES[draft.kind] || ENGINE_TEMPLATES.GENERIC })}
                  >
                    Use {draft.kind} template
                  </Button>
                  <Text size="xs" c="dimmed">
                    Validate exact names against the backend by testing or browsing.
                  </Text>
                </Group>
                <Group grow align="flex-start">
                  <TextInput
                    label="Username"
                    value={draft.username}
                    disabled={!canManage}
                    onChange={(event) => updateDraft({ username: event.currentTarget?.value || '' })}
                    spellCheck={false}
                    autoComplete="off"
                  />
                  <PasswordInput
                    label="Password"
                    value={draft.password}
                    disabled={!canManage}
                    onChange={(event) => updateDraft({ password: event.currentTarget?.value || '' })}
                    autoComplete="new-password"
                    description={editingId ? 'Leave blank to keep saved password.' : undefined}
                  />
                </Group>

                <ConnectionProbeCard state={draftTest} />

                <Group justify="space-between" mt="xs" className="dsx-drawer-actions">
                  <Button variant="default" leftSection={<IconPlugConnected size={16} />} loading={draftTest.status === 'testing'} disabled={!canManage} onClick={() => void testDraftConnection()}>
                    Test draft
                  </Button>
                  <Group gap="xs">
                    {editingId ? (
                      <Button variant="subtle" color="gray" disabled={!canManage} onClick={() => void startNew()}>
                        New
                      </Button>
                    ) : null}
                    <Button loading={saveMutation.isPending} disabled={!canManage} onClick={() => {
                      if (canManage) saveMutation.mutate();
                    }}>
                      {editingId ? 'Save changes' : 'Save connection'}
                    </Button>
                  </Group>
                </Group>
              </Stack>
            </Drawer>
          </section>

        </Stack>

        <Modal
          opened={nativeLoaderOpened}
          onClose={() => setNativeLoaderOpened(false)}
          fullScreen
          zIndex={450}
          title="Native loader workspace"
          classNames={{ body: 'dsx-native-loader-modal-body' }}
        >
          <div className="dsx-native-loader-workspace">
            <Group justify="space-between" align="flex-start" className="dsx-native-loader-head">
              <div>
                <Text className="dsx-kicker">Bulk delivery</Text>
                <Title order={2}>Native loader readiness</Title>
                <Text c="dimmed" maw={820}>
                  Validate vendor bulk clients and binary paths before high-volume delivery. JDBC remains the portable
                  fallback whenever a native client is unavailable.
                </Text>
              </div>
              <Group gap="sm">
                <Badge color={nativeReadyCount === nativeLoaders.length && nativeLoaders.length ? 'green' : 'yellow'} variant="light" size="lg">
                  {nativeReadyCount}/{nativeLoaders.length || 0} ready
                </Badge>
                <Button
                  variant="default"
                  leftSection={<IconRefresh size={16} />}
                  loading={nativeLoadersQuery.isFetching}
                  onClick={() => void queryClient.invalidateQueries({ queryKey: keys.dataSources.nativeLoaders })}
                >
                  Refresh readiness
                </Button>
              </Group>
            </Group>
            {nativeLoaders.length ? (
              <div className="dsx-loader-grid dsx-loader-grid-fullscreen">
                {nativeLoaders.map((loader, index) => (
                  <NativeLoaderCard key={`${loaderName(loader)}-${index}`} loader={loader} />
                ))}
              </div>
            ) : (
              <div className="dsx-empty dsx-native-loader-empty">
                <ThemeIcon variant="light" color="gray" size={38}>
                  <IconServer size={20} />
                </ThemeIcon>
                <div>
                  <Text fw={700}>No loader readiness response</Text>
                  <Text size="sm" c="dimmed">The backend has not reported vendor loader status yet.</Text>
                </div>
              </div>
            )}
          </div>
        </Modal>

        <Modal
          opened={canManage && enginePickerOpened}
          onClose={() => setEnginePickerOpened(false)}
          title="Choose a database engine"
          size="xl"
          zIndex={520}
          centered
        >
          <Stack gap="md">
            <TextInput
              leftSection={<IconSearch size={16} />}
              placeholder="Search PostgreSQL, Oracle, Db2, cloud warehouse..."
              value={enginePickerSearch}
              onChange={(event) => setEnginePickerSearch(event.currentTarget?.value || '')}
              spellCheck={false}
              autoFocus
            />
            <ScrollArea.Autosize mah="65vh" offsetScrollbars>
              <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="sm" pr="xs">
                {visibleEngineCatalog.map((engine) => (
                  <UnstyledButton
                    key={engine.value}
                    className={`dsx-engine-option ${draft.kind === engine.value ? 'is-selected' : ''}`}
                    disabled={!canManage}
                    onClick={() => {
                      if (!canManage) return;
                      const previousTemplate = ENGINE_TEMPLATES[draft.kind];
                      const nextUrl = !draft.jdbcUrl.trim() || draft.jdbcUrl === previousTemplate
                        ? ENGINE_TEMPLATES[engine.value]
                        : draft.jdbcUrl;
                      updateDraft({ kind: engine.value, jdbcUrl: nextUrl });
                      setEnginePickerOpened(false);
                      setEnginePickerSearch('');
                    }}
                  >
                    <Group align="flex-start" wrap="nowrap">
                      <div className="dsx-engine-logo"><EngineLogo engine={engine.value} size={27} /></div>
                      <div className="dsx-engine-option-copy">
                        <Group justify="space-between" gap="xs" wrap="nowrap">
                          <Text fw={750} size="sm">{engine.label}</Text>
                          {draft.kind === engine.value ? <Badge size="xs" color="blue">Selected</Badge> : null}
                        </Group>
                        <Text size="xs" c="dimmed" fw={650}>{engine.family}</Text>
                        <Text size="xs" c="dimmed" mt={4}>{engine.description}</Text>
                        <Text size="xs" className="dsx-engine-jdbc" mt={7}>{ENGINE_TEMPLATES[engine.value]}</Text>
                      </div>
                    </Group>
                  </UnstyledButton>
                ))}
              </SimpleGrid>
            </ScrollArea.Autosize>
            {!visibleEngineCatalog.length ? (
              <Paper p="lg" withBorder><Text ta="center" c="dimmed">No engine matches that search.</Text></Paper>
            ) : null}
          </Stack>
        </Modal>

        <Modal
          opened={!!diagnosticTarget}
          onClose={() => setDiagnosticTarget(null)}
          title={`Connector preflight${diagnosticTarget ? ` - ${diagnosticTarget.name}` : ''}`}
          size="xl"
          scrollAreaComponent={ScrollArea.Autosize}
        >
          <Stack gap="md">
            <Group align="flex-end" wrap="nowrap">
              <TextInput
                label="Schema"
                description="Blank uses the connection's current schema"
                placeholder="public, dbo, YASH"
                value={diagnosticSchema}
                onChange={(event) => setDiagnosticSchema(event.currentTarget?.value || '')}
                spellCheck={false}
                style={{ flex: 1 }}
              />
              <Button
                variant="default"
                loading={diagnosticState === 'loading'}
                onClick={() => diagnosticTarget && void inspectConnection(diagnosticTarget)}
              >
                Run preflight
              </Button>
            </Group>

            {diagnosticState === 'loading' && !diagnosticReport ? (
              <Group justify="center" py="xl"><Loader size="sm" /><Text size="sm">Inspecting catalog metadata and key graph...</Text></Group>
            ) : null}
            {diagnosticState === 'error' ? (
              <Paper p="sm" withBorder><Text c="red" fw={700}>Preflight failed</Text><Text size="sm">{diagnosticError}</Text></Paper>
            ) : null}
            {diagnosticReport ? <ConnectorDiagnosticsView report={diagnosticReport} /> : null}
          </Stack>
        </Modal>

        <Modal
          opened={canManage && !!deleteTarget}
          onClose={() => setDeleteTarget(null)}
          title="Delete data source"
          centered
        >
          <Stack gap="sm">
            <Text size="sm">
              Delete <b>{deleteTarget?.name}</b>? Existing blueprints or jobs that reference it may stop working.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setDeleteTarget(null)}>
                Cancel
              </Button>
              <Button
                color="red"
                loading={deleteMutation.isPending}
                disabled={!canManage}
                onClick={() => {
                  if (canManage && deleteTarget) deleteMutation.mutate(deleteTarget.id);
                }}
              >
                Delete
              </Button>
            </Group>
          </Stack>
        </Modal>
    </main>
  );
}

function ConnectorDiagnosticsView({ report }: { report: ConnectorDiagnostics }) {
  const shape = report.schemaShape;
  const scoreColor = report.readinessScore >= 85 ? 'green' : report.readinessScore >= 65 ? 'yellow' : 'red';
  const metrics: Array<[string, unknown]> = [
    ['Tables scanned', `${shape.tablesScanned ?? 0}/${shape.tablesFound ?? 0}`],
    ['Columns', shape.columns ?? 0],
    ['Missing keys', shape.tablesWithoutPrimaryKey ?? 0],
    ['Composite keys', Number(shape.compositePrimaryKeys || 0) + Number(shape.compositeForeignKeys || 0)],
    ['Cycle tables', shape.cycleTables ?? 0],
    ['LOB columns', shape.lobColumns ?? 0],
    ['Complex types', shape.complexColumns ?? 0],
    ['Partitioned', shape.partitionedTables ?? 'Unknown']
  ];
  return (
    <Stack gap="md">
      <Group justify="space-between" align="center">
        <div>
          <Text fw={800}>{String(report.connection.product || 'Database')} {String(report.connection.productVersion || '')}</Text>
          <Text size="xs" c="dimmed">
            {String(report.connection.driver || 'JDBC driver')} {String(report.connection.driverVersion || '')} - {String(report.connection.connectorMode || '')}
          </Text>
        </div>
        <Group gap="xs">
          <Badge color={scoreColor} variant="light">{report.status}</Badge>
          <Badge color={scoreColor} size="lg">{report.readinessScore}/100</Badge>
        </Group>
      </Group>
      <SimpleGrid cols={{ base: 2, sm: 4 }}>
        {metrics.map(([label, value]) => (
          <Paper key={label} p="sm" withBorder>
            <Text size="xs" c="dimmed" fw={750} tt="uppercase">{label}</Text>
            <Text fw={800} size="lg">{String(value)}</Text>
          </Paper>
        ))}
      </SimpleGrid>
      <Group gap={6}>
        {Object.entries(report.capabilities).map(([key, value]) =>
          typeof value === 'boolean' ? (
            <Badge key={key} color={value ? 'green' : 'gray'} variant="light">{humanize(key)}: {value ? 'yes' : 'no'}</Badge>
          ) : null
        )}
      </Group>
      <div>
        <Text fw={750} mb="xs">Findings and required actions</Text>
        <Stack gap="xs">
          {report.issues.length ? report.issues.map((issue) => (
            <Paper key={issue.code} p="sm" withBorder>
              <Group justify="space-between" mb={4}>
                <Text fw={750}>{issue.title}</Text>
                <Badge color={issue.severity === 'HIGH' ? 'red' : issue.severity === 'WARN' ? 'yellow' : 'blue'}>{issue.severity}</Badge>
              </Group>
              <Text size="sm">{issue.detail}</Text>
              <Text size="sm" c="dimmed" mt={4}><b>Action:</b> {issue.remediation}</Text>
            </Paper>
          )) : (
            <Paper p="md" withBorder><Text fw={750} c="green">No connector blockers found in the scanned schema.</Text></Paper>
          )}
        </Stack>
      </div>
    </Stack>
  );
}

function humanize(value: string) {
  return value.replace(/([A-Z])/g, ' $1').replace(/^./, (letter) => letter.toUpperCase());
}

function engineLabel(value: string) {
  return ENGINE_CATALOG.find((engine) => engine.value === value)?.label || value || 'Generic JDBC';
}

function EngineLogo({ engine, size = 20 }: { engine: string; size?: number }) {
  const props = { size, 'aria-hidden': true };
  switch (engine) {
    case 'POSTGRES': return <SiPostgresql {...props} color="#4169e1" />;
    case 'MYSQL': return <SiMysql {...props} color="#4479a1" />;
    case 'MARIADB': return <SiMariadb {...props} color="#003545" />;
    case 'ORACLE': return <GrOracle {...props} color="#f80000" />;
    case 'SQLSERVER': return <DiMsqlServer {...props} color="#cc2927" />;
    case 'DB2':
    case 'DB2UDB':
    case 'DB2ZOS': return <Image src="/brands/ibm-db2.svg" width={size} height={size} alt="" aria-hidden />;
    case 'SNOWFLAKE': return <SiSnowflake {...props} color="#29b5e8" />;
    case 'REDSHIFT': return <FaAws {...props} color="#8c4fff" />;
    case 'BIGQUERY': return <SiGooglebigquery {...props} color="#4285f4" />;
    case 'TERADATA': return <SiTeradata {...props} color="#f37440" />;
    case 'SAP_HANA':
    case 'SYBASE': return <SiSap {...props} color="#0faaff" />;
    default: return <FaDatabase {...props} color="#64748b" />;
  }
}

function RoleBadge({ role }: { role?: string | null }) {
  const value = String(role || 'BOTH').toUpperCase();
  const color = value === 'SOURCE' ? 'blue' : value === 'TARGET' ? 'green' : 'violet';
  return (
    <Badge color={color} variant="light">
      {value}
    </Badge>
  );
}

function EnvironmentBadge({ environment }: { environment?: string | null }) {
  const value = String(environment || '').trim().toUpperCase();
  if (!value) return <Text size="sm" c="dimmed">Not set</Text>;
  const color = value === 'PROD' || value === 'PRODUCTION' ? 'red'
    : value === 'UAT' ? 'violet'
      : value === 'QA' || value === 'TEST' ? 'blue'
        : value === 'STAGE' || value === 'STAGING' ? 'yellow'
          : 'gray';
  return <Badge color={color} variant="light">{value}</Badge>;
}

function ProbeBadge({ state }: { state: ProbeState }) {
  if (state.status === 'testing') {
    return (
      <span className="dsx-health is-testing"><IconLoader2 size={13} className="dsx-spin" />Testing</span>
    );
  }
  if (state.status === 'ok') {
    return (
      <span className="dsx-health is-online"><IconCircleCheck size={13} />Online</span>
    );
  }
  if (state.status === 'error') {
    return (
      <span className="dsx-health is-failed"><IconAlertTriangle size={13} />Failed</span>
    );
  }
  return (
    <span className="dsx-health is-idle"><i aria-hidden="true" />Not tested</span>
  );
}

function ConnectionProbeCard({ state }: { state: ProbeState }) {
  if (state.status === 'idle') {
    return (
      <div className="dsx-probe-card">
        <ThemeIcon variant="light" color="gray" size={30}>
          <IconDatabaseCog size={17} />
        </ThemeIcon>
        <div>
          <Text size="sm" fw={700}>
            No draft test yet
          </Text>
          <Text size="xs" c="dimmed">
            Run Test draft before saving credentials for a cleaner setup loop.
          </Text>
        </div>
      </div>
    );
  }
  const color = state.status === 'ok' ? 'green' : state.status === 'error' ? 'red' : 'blue';
  return (
    <div className={`dsx-probe-card is-${state.status}`}>
      <ThemeIcon variant="light" color={color} size={30}>
        {state.status === 'ok' ? <IconCircleCheck size={17} /> : state.status === 'error' ? <IconX size={17} /> : <IconLoader2 size={17} className="dsx-spin" />}
      </ThemeIcon>
      <div>
        <Text size="sm" fw={700}>
          {state.message || 'Testing...'}
        </Text>
        {state.detail ? (
          <Text size="xs" c="dimmed">
            {state.detail}
          </Text>
        ) : null}
      </div>
    </div>
  );
}

function SchemaPreview({ state }: { state: SchemaState }) {
  if (state.status === 'loading') {
    return (
      <div className="dsx-schema-preview">
        <Loader size="xs" />
        <Text size="sm">Reading schemas...</Text>
      </div>
    );
  }
  if (state.status === 'error') {
    return (
      <div className="dsx-schema-preview is-error">
        <IconAlertTriangle size={15} />
        <Text size="sm">{state.message}</Text>
      </div>
    );
  }
  const rows = state.rows || [];
  return (
    <div className="dsx-schema-preview">
      <Text size="xs" fw={800} tt="uppercase" c="dimmed">
        Schemas
      </Text>
      {rows.length ? (
        <Group gap={6}>
          {rows.slice(0, 18).map((row, index) => (
            <Badge key={`${row.schema || 'schema'}-${index}`} variant={row.current ? 'filled' : 'light'} color={row.current ? 'blue' : 'gray'}>
              {row.schema || 'default'}
            </Badge>
          ))}
          {rows.length > 18 ? <Badge variant="outline">+{rows.length - 18} more</Badge> : null}
        </Group>
      ) : (
        <Text size="sm" c="dimmed">
          No user schemas returned.
        </Text>
      )}
    </div>
  );
}

function NativeLoaderCard({ loader }: { loader: NativeLoaderStatus }) {
  const ready = loaderReady(loader);
  const name = loaderName(loader);
  const strategy = stringValue(loader.strategy || loader.label || (ready ? 'Native path available' : 'JDBC fallback'));
  const path = stringValue(loader.binaryPath || loader.path || loader.hint || loader.fallback);
  const env = stringValue(loader.binaryEnv || loader.enabledEnv);
  return (
    <div className={`dsx-loader-card ${ready ? 'is-ready' : 'is-waiting'}`}>
      <Group justify="space-between" gap="sm" wrap="nowrap">
        <div>
          <Text fw={750}>{name}</Text>
          <Text size="xs" c="dimmed">
            {strategy}
          </Text>
        </div>
        <Badge color={ready ? 'green' : 'yellow'} variant="light">
          {ready ? 'Ready' : 'Setup needed'}
        </Badge>
      </Group>
      <Divider my="xs" />
      <Text size="xs" c="dimmed" className="dsx-mono-line">
        {path || 'No binary path reported'}
      </Text>
      {env ? (
        <Text size="xs" c="dimmed" className="dsx-mono-line">
          {env}
        </Text>
      ) : null}
    </div>
  );
}

function payloadFromDraft(draft: Draft) {
  return {
    version: draft.version,
    name: draft.name.trim(),
    kind: draft.kind.trim() || 'GENERIC',
    role: draft.role.trim() || 'BOTH',
    environment: emptyToNull(draft.environment),
    tags: emptyToNull(draft.tags),
    jdbcUrl: draft.jdbcUrl.trim(),
    username: emptyToNull(draft.username),
    password: emptyToNull(draft.password)
  };
}

function draftFromDataSource(source: DataSource): Draft {
  return {
    version: source.version,
    name: source.name || '',
    kind: source.kind || 'GENERIC',
    role: source.role || 'BOTH',
    environment: source.environment || '',
    tags: source.tags || '',
    jdbcUrl: source.jdbcUrl || '',
    username: source.username || '',
    password: ''
  };
}

function emptyToNull(value: string) {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}

function allowsRole(role: string | null | undefined, desired: string) {
  const normalized = String(role || 'BOTH').toUpperCase();
  return normalized === 'BOTH' || normalized === desired;
}

function jdbcDisplay(value?: string | null) {
  if (!value) return 'No JDBC URL';
  return value.length > 82 ? `${value.slice(0, 79)}...` : value;
}

function connectionTags(value?: string | null) {
  return String(value || '').split(',').map((tag) => tag.trim()).filter(Boolean);
}

function probeSummary(result: Record<string, unknown>) {
  const product = stringValue(result.product);
  const ok = result.ok === true;
  return product ? `${product} connection ${ok ? 'online' : 'tested'}` : ok ? 'Connection online' : 'Connection tested';
}

function probeDetail(result: Record<string, unknown>) {
  return stringValue(result.version || result.message || result.detail);
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error || 'Unknown error');
}

function loaderName(loader: NativeLoaderStatus) {
  return stringValue(loader.engine || loader.database || loader.label || loader.strategy) || 'Loader';
}

function loaderReady(loader: NativeLoaderStatus) {
  return Boolean(loader.ready ?? loader.nativeAvailable ?? loader.available ?? loader.enabled ?? loader.builtIn);
}

function stringValue(value: unknown) {
  return typeof value === 'string' ? value : value == null ? '' : String(value);
}
