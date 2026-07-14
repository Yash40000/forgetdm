'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActionIcon,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Title,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconDatabase,
  IconFileCertificate,
  IconMap,
  IconRefresh,
  IconRegex,
  IconSearch,
  IconShieldSearch
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { NameInput } from '@/components/name-input';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiFetch, apiPatch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource } from '@/lib/types';
import {
  ColumnReviewPanel,
  FindingsTable,
  ImpactDiagramPanel,
  ImpactMapPanel,
  LiveScanPanel,
  MetricCard,
  PatternsTable
} from './components';
import {
  useDataSources,
  useDiscoveryColumnReview,
  useDiscoveryFindings,
  useDiscoveryGraph,
  useDiscoveryJobs,
  useMaskFunctions,
  usePiiPatternGroups,
  usePiiPatterns,
  usePiiTypes,
  useSchemas,
  useTables
} from './hooks';
import type {
  DiscoveryColumnReviewRow,
  DiscoveryFinding,
  DiscoveryJob,
  ManualDraft,
  PatternDraft,
  PiiPattern
} from './types';
import {
  FALLBACK_PII_TYPES,
  discoveryJobLive,
  findingSort,
  groupPiiTypes,
  orderPiiTypes,
} from './utils';

const EMPTY_PATTERN: PatternDraft = {
  piiType: '',
  kind: 'NAME',
  regex: '',
  suggestedFunction: '',
  description: '',
  visibility: 'PRIVATE',
  ownerGroupId: ''
};

export function PiiDiscoveryPage() {
  const queryClient = useQueryClient();
  const completedJobRef = useRef<string | null>(null);
  const [activeTab, setActiveTab] = useState<string | null>('scan');
  const [dataSourceId, setDataSourceId] = useState<number | null>(null);
  const [dataSourceInput, setDataSourceInput] = useState('');
  const [schema, setSchema] = useState<string | null>(null);
  const [selectedTypes, setSelectedTypes] = useState<string[]>([]);
  const [typeScopeText, setTypeScopeText] = useState('');
  const [resultTypeScope, setResultTypeScope] = useState<string[]>([]);
  const [selectedTables, setSelectedTables] = useState<string[]>([]);
  const [tableFocusText, setTableFocusText] = useState('');
  const [reviewTable, setReviewTable] = useState<string | null>(null);
  const [typeFilter, setTypeFilter] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [browseModal, setBrowseModal] = useState<'source' | 'schema' | 'types' | 'table' | null>(null);
  const [browseSearch, setBrowseSearch] = useState('');
  const [policyName, setPolicyName] = useState('');
  const [manualDrafts, setManualDrafts] = useState<Record<string, ManualDraft>>({});
  const [patternDraft, setPatternDraft] = useState<PatternDraft>(EMPTY_PATTERN);
  const [deletePattern, setDeletePattern] = useState<PiiPattern | null>(null);

  const dataSourcesQuery = useDataSources();
  const schemasQuery = useSchemas(dataSourceId);
  const tablesQuery = useTables(dataSourceId, schema);
  const piiTypesQuery = usePiiTypes();
  const functionsQuery = useMaskFunctions();
  const jobsQuery = useDiscoveryJobs(dataSourceId, schema);
  const findingsQuery = useDiscoveryFindings(dataSourceId, schema, resultTypeScope);
  const columnReviewQuery = useDiscoveryColumnReview(dataSourceId, schema, reviewTable, resultTypeScope);
  const graphQuery = useDiscoveryGraph(dataSourceId, schema, resultTypeScope);
  const patternsQuery = usePiiPatterns();
  const groupsQuery = usePiiPatternGroups();

  const dataSources = dataSourcesQuery.data || [];
  const schemas = schemasQuery.data || [];
  const tables = tablesQuery.data || [];
  const piiTypes = piiTypesQuery.data?.length ? piiTypesQuery.data : FALLBACK_PII_TYPES;
  const maskFunctions = functionsQuery.data || [];
  const findings = (findingsQuery.data || []).slice().sort(findingSort);
  const jobs = jobsQuery.data || [];
  const latestJob = jobs[0] || null;
  const liveJob = jobs.find((job) => discoveryJobLive(job.status)) || latestJob;
  const refreshDiscoveryData = useCallback(
    () => queryClient.invalidateQueries({ queryKey: ['discovery'] }),
    [queryClient]
  );

  useEffect(() => {
    if (!latestJob || latestJob.status !== 'COMPLETED' || completedJobRef.current === latestJob.jobId) return;
    completedJobRef.current = latestJob.jobId;
    setResultTypeScope([...(latestJob.selectedTypes || [])]);
    void refreshDiscoveryData();
    notifications.show({
      color: 'green',
      title: 'PII scan completed',
      message: `${latestJob.findings || 0} finding${latestJob.findings === 1 ? '' : 's'} are ready for review.`
    });
  }, [latestJob, refreshDiscoveryData]);

  const schemaOptions = schemas
    .map((row) => catalogName(row, 'schema'))
    .filter(Boolean)
    .map((value) => ({ value, label: value }));
  const tableOptions = tables
    .map((row) => catalogName(row, 'table'))
    .filter(Boolean)
    .map((value) => ({ value, label: value }));
  const maskFunctionOptions = maskFunctions.map((fn) => ({ value: fn, label: fn }));

  const chooseDataSource = (source: DataSource) => {
    if (source.id !== dataSourceId) {
      setSchema(null);
      setSelectedTables([]);
      setTableFocusText('');
      setReviewTable(null);
    }
    setDataSourceId(source.id);
    setDataSourceInput(source.name);
    setBrowseModal(null);
    setBrowseSearch('');
  };

  const resolveTypedDataSource = (showError = false) => {
    const typed = dataSourceInput.trim();
    if (!typed) {
      if (showError) notifyError('Data source required', new Error('Type a data source name/id or choose Browse.'));
      return null;
    }
    const match = findDataSource(dataSources, typed);
    if (!match) {
      if (showError) notifyError('Data source not found', new Error(`No source named "${typed}" exists. Use Browse to select one.`));
      return null;
    }
    if (match.id !== dataSourceId) chooseDataSource(match);
    return match;
  };

  const applyTypedTypes = () => {
    const types = parseTypeScope(typeScopeText);
    setSelectedTypes(types);
    return types;
  };

  const applyTypedTables = () => {
    const tables = parseTableFocus(tableFocusText);
    applyTableSelection(tables);
    return tables;
  };

  const openBrowse = (modal: 'source' | 'schema' | 'types' | 'table') => {
    setBrowseModal(modal);
    setBrowseSearch('');
  };

  const visibleFindings = useMemo(() => {
    const needle = search.trim().toLowerCase();
    const selectedTableSet = new Set(selectedTables.map((table) => table.toLowerCase()));
    return findings.filter((finding) => {
      if (selectedTableSet.size && !selectedTableSet.has(String(finding.tableName || '').toLowerCase())) return false;
      if (typeFilter && finding.piiType !== typeFilter) return false;
      if (statusFilter && finding.status !== statusFilter) return false;
      if (needle) {
        const haystack = [
          finding.tableName,
          finding.columnName,
          finding.dataType,
          finding.piiType,
          finding.suggestedFunction,
          finding.sampleValue,
          finding.status
        ]
          .filter(Boolean)
          .join(' ')
          .toLowerCase();
        if (!haystack.includes(needle)) return false;
      }
      return true;
    });
  }, [findings, search, selectedTables, statusFilter, typeFilter]);

  const resultTypes = [...new Set(findings.map((item) => item.piiType).filter(Boolean))].sort();
  const resultStatuses = [...new Set(findings.map((item) => item.status).filter(Boolean))].sort();
  const highConfidence = findings.filter((finding) => Number(finding.confidence || 0) >= 0.8).length;
  const approved = findings.filter((finding) => finding.status === 'APPROVED').length;
  const suggested = findings.filter((finding) => finding.status !== 'APPROVED' && finding.status !== 'REJECTED').length;
  const rejected = findings.filter((finding) => finding.status === 'REJECTED').length;

  const startScanMutation = useMutation({
    mutationFn: async ({ sourceId, schemaName, piiTypes, tableNames }: { sourceId: number; schemaName: string; piiTypes: string[]; tableNames: string[] }) => {
      const job = await apiPost<DiscoveryJob>(`/api/discovery/scan-jobs/${sourceId}?schema=${encodeURIComponent(schemaName)}`, {
        piiTypes,
        tableNames
      });
      return job;
    },
    onSuccess: (job, variables) => {
      setResultTypeScope([...(job.selectedTypes || variables.piiTypes)]);
      completedJobRef.current = null;
      setActiveTab('scan');
      notifications.show({ color: 'blue', title: 'PII scan started', message: job.message || 'Live progress is open.' });
      void queryClient.invalidateQueries({ queryKey: keys.discovery.jobs(variables.sourceId, variables.schemaName) });
    },
    onError: (error) => notifyError('Scan failed to start', error)
  });

  const updateFindingMutation = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Record<string, string | null> }) =>
      apiPatch<DiscoveryFinding>(`/api/discovery/classifications/${id}`, body),
    onSuccess: () => refreshDiscoveryData(),
    onError: (error) => notifyError('Finding update failed', error)
  });

  const bulkMutation = useMutation({
    mutationFn: async (status: 'APPROVED' | 'REJECTED') => {
      if (!visibleFindings.length) return { count: 0 };
      return apiPost<{ count: number }>('/api/discovery/classifications/bulk', {
        status,
        ids: visibleFindings.map((finding) => finding.id)
      });
    },
    onSuccess: (result, status) => {
      notifications.show({
        color: status === 'APPROVED' ? 'green' : 'red',
        title: status === 'APPROVED' ? 'Visible findings approved' : 'Visible findings rejected',
        message: `${result.count || 0} finding${result.count === 1 ? '' : 's'} updated.`
      });
      void refreshDiscoveryData();
    },
    onError: (error) => notifyError('Bulk action failed', error)
  });

  const manualMutation = useMutation({
    mutationFn: async ({ row, draft }: { row: DiscoveryColumnReviewRow; draft: ManualDraft }) => {
      if (!dataSourceId || !schema) throw new Error('Select a data source and schema first.');
      return apiPost<DiscoveryFinding>(`/api/discovery/manual/${dataSourceId}`, {
        schemaName: schema,
        tableName: row.tableName,
        columnName: row.columnName,
        piiType: draft.piiType,
        suggestedFunction: draft.suggestedFunction,
        suggestedParam1: draft.suggestedParam1,
        suggestedParam2: draft.suggestedParam2,
        status: 'APPROVED'
      });
    },
    onSuccess: (finding) => {
      notifications.show({
        color: 'green',
        title: 'Column marked as PII',
        message: `${finding.tableName}.${finding.columnName} was added and approved.`
      });
      void refreshDiscoveryData();
    },
    onError: (error) => notifyError('Manual classification failed', error)
  });

  const generatePolicyMutation = useMutation({
    mutationFn: async () => {
      if (!dataSourceId || !schema) throw new Error('Select a data source and schema first.');
      const name = policyName.trim() || `policy-ds-${dataSourceId}-${schema}`;
      return apiPost<{ id?: number; name?: string }>(`/api/discovery/generate-policy/${dataSourceId}?schema=${encodeURIComponent(schema)}`, {
        name
      });
    },
    onSuccess: (policy) => {
      notifications.show({
        color: 'green',
        title: 'Policy generated',
        message: `${policy.name || 'Policy'} was created from approved findings.`
      });
      void queryClient.invalidateQueries({ queryKey: keys.policies.all });
    },
    onError: (error) => notifyError('Policy generation failed', error)
  });

  const createPatternMutation = useMutation({
    mutationFn: async () => {
      if (!patternDraft.piiType.trim() || !patternDraft.regex.trim()) {
        throw new Error('PII type and regex are required.');
      }
      return apiPost<Record<string, unknown>>('/api/discovery/patterns', {
        piiType: patternDraft.piiType,
        kind: patternDraft.kind,
        regex: patternDraft.regex,
        suggestedFunction: patternDraft.suggestedFunction || null,
        description: patternDraft.description || null,
        visibility: patternDraft.visibility,
        ownerGroupId: patternDraft.visibility === 'GROUP' ? Number(patternDraft.ownerGroupId) || null : null
      });
    },
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Custom pattern added', message: 'The scanner will use it on the next run.' });
      setPatternDraft(EMPTY_PATTERN);
      void queryClient.invalidateQueries({ queryKey: keys.discovery.patterns });
      void queryClient.invalidateQueries({ queryKey: keys.discovery.piiTypes });
    },
    onError: (error) => notifyError('Pattern save failed', error)
  });

  const deletePatternMutation = useMutation({
    mutationFn: (pattern: PiiPattern) =>
      apiFetch<void>(`/api/discovery/patterns/${pattern.id}`, {
        method: 'DELETE'
      }),
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Pattern deleted', message: 'The custom pattern was removed.' });
      setDeletePattern(null);
      void queryClient.invalidateQueries({ queryKey: keys.discovery.patterns });
      void queryClient.invalidateQueries({ queryKey: keys.discovery.piiTypes });
    },
    onError: (error) => notifyError('Pattern delete failed', error)
  });

  const reviewScopeLabel = resultTypeScope.length
    ? `${resultTypeScope.length} selected PII type${resultTypeScope.length === 1 ? '' : 's'}`
    : 'all PII types';

  const handleStartScan = () => {
    startScanWithTypes(applyTypedTypes(), applyTypedTables());
  };

  const startScanWithTypes = (types: string[], tableNames: string[]) => {
    const source = resolveTypedDataSource(true);
    if (!source) return;
    const schemaName = (schema || '').trim();
    if (!schemaName) {
      notifyError('Schema required', new Error('Type a schema name or use Browse to select one.'));
      return;
    }
    startScanMutation.mutate({ sourceId: source.id, schemaName, piiTypes: types, tableNames });
  };

  const applyTypeSelection = (types: string[]) => {
    const unique = orderPiiTypes(types);
    setSelectedTypes(unique);
    setTypeScopeText(unique.join(', '));
  };

  const applyTableSelection = (tables: string[]) => {
    const unique = [...new Set(tables.map((table) => table.trim()).filter(Boolean))];
    setSelectedTables(unique);
    setTableFocusText(unique.join(', '));
    if (unique.length === 1) setReviewTable(unique[0]);
  };

  const handleUseScopeForResults = () => {
    const types = applyTypedTypes();
    setResultTypeScope(types);
    setActiveTab('findings');
    notifications.show({
      color: 'blue',
      title: 'Result scope applied',
      message: types.length ? `Showing results for ${types.length} selected PII type${types.length === 1 ? '' : 's'}.` : 'Showing results for all PII types.'
    });
  };

  const handleSelectAllTypes = () => {
    applyTypeSelection(piiTypes);
  };

  const handleClearTypeScope = () => {
    applyTypeSelection([]);
  };

  const handleUseAllTypes = () => {
    applyTypeSelection([]);
    setResultTypeScope([]);
    notifications.show({
      color: 'blue',
      title: 'PII scope set to all types',
      message: 'Click Start scan when you are ready to run discovery for all PII types.'
    });
  };

  const filteredSources = dataSources
    .filter(sourceCapable)
    .filter((source) => matchBrowse(source.name, source.kind, source.role, source.jdbcUrl, browseSearch));
  const filteredSchemas = schemaOptions.filter((item) => matchBrowse(item.label, browseSearch));
  const filteredTables = tableOptions.filter((item) => matchBrowse(item.label, browseSearch));
  const groupedPiiTypes = groupPiiTypes(piiTypes, browseSearch);

  return (
    <main className="forge-page pii-page">
        <Stack gap="lg">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text className="pii-kicker">Sensitive data intelligence</Text>
              <Title order={1}>PII Discovery</Title>
              <Text c="dimmed" maw={820}>
                Scan source metadata and sampled values, review findings with masking recommendations, add manual PII,
                generate policies, and govern custom detection patterns.
              </Text>
            </div>
          <Group gap="sm">
              {jobsQuery.isFetching || findingsQuery.isFetching ? <Loader size="sm" /> : null}
              <Button
                leftSection={<IconRefresh size={16} />}
                variant="default"
                onClick={() => {
                  void queryClient.invalidateQueries({ queryKey: keys.discovery.jobs(dataSourceId, schema) });
                  void refreshDiscoveryData();
                }}
              >
                Refresh
              </Button>
            </Group>
          </Group>

          <QueryErrorBanner
            errors={[
              dataSourcesQuery.error,
              schemasQuery.error,
              tablesQuery.error,
              piiTypesQuery.error,
              functionsQuery.error,
              jobsQuery.error,
              findingsQuery.error,
              columnReviewQuery.error,
              graphQuery.error,
              patternsQuery.error,
              groupsQuery.error
            ]}
            onRetry={refreshDiscoveryData}
            title="PII Discovery could not load all backend data"
          />

          <Paper className="pii-command-card" p="sm">
            <div className="pii-command-grid">
              <BrowseField
                label="Data source"
                placeholder="Type source name/id"
                value={dataSourceInput}
                onChange={(value) => {
                  setDataSourceInput(value);
                  const match = findDataSource(dataSources, value);
                  if (match) chooseDataSource(match);
                  else if (value.trim()) {
                    setDataSourceId(null);
                    setSchema(null);
                    setSelectedTables([]);
                    setTableFocusText('');
                    setReviewTable(null);
                  }
                }}
                onBlur={() => resolveTypedDataSource(false)}
                onBrowse={() => openBrowse('source')}
              />
              <BrowseField
                label="Schema"
                placeholder="Type schema"
                value={schema || ''}
                onChange={(value) => {
                  setSchema(value || null);
                  setSelectedTables([]);
                  setTableFocusText('');
                  setReviewTable(null);
                }}
                onBrowse={() => openBrowse('schema')}
                disabled={!dataSourceId}
              />
              <BrowseField
                label="Table focus"
                placeholder="Blank means all. Example: customers, accounts"
                value={tableFocusText}
                onChange={setTableFocusText}
                onBlur={() => applyTypedTables()}
                onBrowse={() => openBrowse('table')}
                disabled={!schema}
              />
              <BrowseField
                label="PII type scope"
                placeholder="Blank means all. Example: EMAIL, SSN"
                value={typeScopeText}
                onChange={setTypeScopeText}
                onBlur={() => applyTypedTypes()}
                onBrowse={() => openBrowse('types')}
              />
            </div>
            <div className="pii-command-footer">
              <div className="pii-scope-line">
                <InlineScope
                  label="Table"
                  values={selectedTables}
                  emptyLabel="all tables"
                  maxVisible={3}
                  onClear={() => applyTableSelection([])}
                />
                <InlineScope label="PII" values={selectedTypes} emptyLabel="all types" maxVisible={5} onClear={handleClearTypeScope} />
                <span className="pii-reviewing-text">Reviewing {reviewScopeLabel}</span>
              </div>
              <Group gap={6} className="pii-command-actions">
                <Button size="xs" variant="subtle" onClick={handleSelectAllTypes}>
                  Select all types
                </Button>
                <Button size="xs" variant="subtle" color="gray" onClick={handleUseAllTypes}>
                  Set scope to all types
                </Button>
                <Button size="xs" variant="default" onClick={handleUseScopeForResults}>
                  Use type scope for results
                </Button>
                <Button
                  size="xs"
                  leftSection={<IconShieldSearch size={14} />}
                  loading={startScanMutation.isPending}
                  onClick={handleStartScan}
                >
                  Start scan
                </Button>
              </Group>
            </div>
          </Paper>

          <SimpleGrid cols={{ base: 1, sm: 2, lg: 5 }}>
            <MetricCard label="Findings" value={findings.length} detail="PII columns in review scope" />
            <MetricCard label="PII types" value={resultTypes.length} detail="Distinct detected categories" />
            <MetricCard label="High confidence" value={highConfidence} detail="Confidence at or above 80%" />
            <MetricCard label="Approved" value={approved} detail="Ready for policy generation" tone="good" />
            <MetricCard label="To review" value={suggested} detail={`${rejected} rejected / not PII`} tone={suggested ? 'warn' : 'good'} />
          </SimpleGrid>

          <Tabs value={activeTab} onChange={setActiveTab} classNames={{ list: 'forge-tabs-list pii-tabs-list' }}>
            <Tabs.List>
              <Tabs.Tab value="scan" leftSection={<IconShieldSearch size={16} />}>
                Live scan
              </Tabs.Tab>
              <Tabs.Tab value="findings" leftSection={<IconSearch size={16} />}>
                Findings
              </Tabs.Tab>
              <Tabs.Tab value="columns" leftSection={<IconDatabase size={16} />}>
                Column review
              </Tabs.Tab>
              <Tabs.Tab value="impact" leftSection={<IconMap size={16} />}>
                Impact map
              </Tabs.Tab>
              <Tabs.Tab value="govern" leftSection={<IconRegex size={16} />}>
                Policy & patterns
              </Tabs.Tab>
            </Tabs.List>

            <Tabs.Panel value="scan" pt="md">
              <LiveScanPanel job={liveJob} history={jobs} />
            </Tabs.Panel>

            <Tabs.Panel value="findings" pt="md">
              <Paper className="pii-panel" p={0}>
                <div className="pii-panel-head">
                  <div>
                    <Text fw={760}>Findings review</Text>
                    <Text size="sm" c="dimmed">
                      Approve true PII, reject false positives, and tune suggested masking rules in place.
                    </Text>
                  </div>
                  <Group gap="xs">
                    <Button size="xs" variant="default" onClick={() => bulkMutation.mutate('APPROVED')} loading={bulkMutation.isPending}>
                      Approve visible
                    </Button>
                    <Button size="xs" variant="light" color="red" onClick={() => bulkMutation.mutate('REJECTED')} loading={bulkMutation.isPending}>
                      Reject visible
                    </Button>
                  </Group>
                </div>
                <div className="pii-filter-row">
                  <TextInput
                    leftSection={<IconSearch size={15} />}
                    placeholder="Search table, column, type, sample..."
                    value={search}
                    onChange={(event) => setSearch(event.currentTarget?.value || '')}
                    spellCheck={false}
                  />
                  <Select
                    placeholder="Any PII type"
                    data={resultTypes.map((type) => ({ value: type, label: type }))}
                    value={typeFilter}
                    onChange={setTypeFilter}
                    clearable
                    searchable
                  />
                  <Select
                    placeholder="Any status"
                    data={resultStatuses.map((status) => ({ value: status, label: status }))}
                    value={statusFilter}
                    onChange={setStatusFilter}
                    clearable
                  />
                </div>
                <FindingsTable
                  rows={visibleFindings}
                  functions={maskFunctions}
                  updating={updateFindingMutation.isPending}
                  onUpdate={(id, body) => updateFindingMutation.mutate({ id, body })}
                />
              </Paper>
            </Tabs.Panel>

            <Tabs.Panel value="columns" pt="md">
              <ColumnReviewPanel
                selectedTable={reviewTable}
                tableOptions={tableOptions}
                onTableChange={setReviewTable}
                rows={columnReviewQuery.data || []}
                loading={columnReviewQuery.isLoading || columnReviewQuery.isFetching}
                piiTypes={piiTypes}
                functions={maskFunctions}
                manualDrafts={manualDrafts}
                setManualDrafts={setManualDrafts}
                onUpdate={(id, body) => updateFindingMutation.mutate({ id, body })}
                onManual={(row, draft) => manualMutation.mutate({ row, draft })}
                manualPending={manualMutation.isPending}
              />
            </Tabs.Panel>

            <Tabs.Panel value="impact" pt="md">
              <Stack gap="md">
                <ImpactDiagramPanel graph={graphQuery.data || {}} loading={graphQuery.isLoading || graphQuery.isFetching} />
                <ImpactMapPanel graph={graphQuery.data || {}} loading={graphQuery.isLoading || graphQuery.isFetching} />
              </Stack>
            </Tabs.Panel>

            <Tabs.Panel value="govern" pt="md">
              <section className="pii-govern-grid">
                <Paper className="pii-panel" p="md">
                  <Text fw={760}>Generate masking policy</Text>
                  <Text size="sm" c="dimmed" mb="md">
                    Creates policy rules from approved findings only. Suggested and rejected rows are intentionally ignored.
                  </Text>
                  <NameInput
                    label="Policy name"
                    placeholder={dataSourceId && schema ? `policy-ds-${dataSourceId}-${schema}` : 'policy name'}
                    value={policyName}
                    onChange={(value) => setPolicyName(value)}
                  />
                  <Group justify="space-between" mt="md">
                    <Text size="sm" c="dimmed">
                      {approved} approved finding{approved === 1 ? '' : 's'} available.
                    </Text>
                    <Button
                      leftSection={<IconFileCertificate size={16} />}
                      loading={generatePolicyMutation.isPending}
                      onClick={() => generatePolicyMutation.mutate()}
                    >
                      Generate policy
                    </Button>
                  </Group>
                </Paper>
                <Paper className="pii-panel" p="md">
                  <Text fw={760}>Custom detection pattern</Text>
                  <Text size="sm" c="dimmed" mb="md">
                    Add a private, group, or global regex used by future scans. NAME matches column names; VALUE matches sampled values.
                  </Text>
                  <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm">
                    <TextInput
                      label="PII type"
                      placeholder="LOYALTY_ID"
                      value={patternDraft.piiType}
                      onChange={(event) => {
                        const value = event.currentTarget?.value || '';
                        setPatternDraft((current) => ({ ...current, piiType: value }));
                      }}
                      spellCheck={false}
                    />
                    <Select
                      label="Match"
                      data={[
                        { value: 'NAME', label: 'Column name' },
                        { value: 'VALUE', label: 'Sample value' }
                      ]}
                      value={patternDraft.kind}
                      onChange={(value) => setPatternDraft((current) => ({ ...current, kind: value === 'VALUE' ? 'VALUE' : 'NAME' }))}
                    />
                  </SimpleGrid>
                  <Textarea
                    mt="sm"
                    label="Regex"
                    autosize
                    minRows={2}
                    value={patternDraft.regex}
                    onChange={(event) => {
                      const value = event.currentTarget?.value || '';
                      setPatternDraft((current) => ({ ...current, regex: value }));
                    }}
                    spellCheck={false}
                    classNames={{ input: 'pii-mono-input' }}
                  />
                  <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm" mt="sm">
                    <Select
                      label="Suggested mask"
                      data={maskFunctionOptions}
                      value={patternDraft.suggestedFunction || null}
                      onChange={(value) => setPatternDraft((current) => ({ ...current, suggestedFunction: value || '' }))}
                      searchable
                      clearable
                    />
                    <Select
                      label="Visibility"
                      data={[
                        { value: 'PRIVATE', label: 'Private' },
                        { value: 'GROUP', label: 'Group' },
                        { value: 'GLOBAL', label: 'Global' }
                      ]}
                      value={patternDraft.visibility}
                      onChange={(value) =>
                        setPatternDraft((current) => ({ ...current, visibility: (value as PatternDraft['visibility']) || 'PRIVATE' }))
                      }
                    />
                  </SimpleGrid>
                  {patternDraft.visibility === 'GROUP' ? (
                    <Select
                      mt="sm"
                      label="Group"
                      data={(groupsQuery.data || []).map((group) => ({ value: String(group.id), label: group.name }))}
                      value={patternDraft.ownerGroupId || null}
                      onChange={(value) => setPatternDraft((current) => ({ ...current, ownerGroupId: value || '' }))}
                    />
                  ) : null}
                  <TextInput
                    mt="sm"
                    label="Description"
                    value={patternDraft.description}
                    onChange={(event) => {
                      const value = event.currentTarget?.value || '';
                      setPatternDraft((current) => ({ ...current, description: value }));
                    }}
                    spellCheck={false}
                  />
                  <Group justify="flex-end" mt="md">
                    <Button loading={createPatternMutation.isPending} onClick={() => createPatternMutation.mutate()}>
                      Add pattern
                    </Button>
                  </Group>
                </Paper>
              </section>
              <Paper className="pii-panel" p={0} mt="md">
                <div className="pii-panel-head">
                  <div>
                    <Text fw={760}>Pattern library</Text>
                    <Text size="sm" c="dimmed">
                      Visible custom patterns for this user, group, and global scope.
                    </Text>
                  </div>
                  {patternsQuery.isFetching ? <Loader size="sm" /> : null}
                </div>
                <PatternsTable rows={patternsQuery.data || []} onDelete={setDeletePattern} />
              </Paper>
            </Tabs.Panel>
          </Tabs>
        </Stack>

        <Modal
          opened={!!browseModal}
          onClose={() => setBrowseModal(null)}
          title={
            browseModal === 'source'
              ? 'Browse data sources'
              : browseModal === 'schema'
                ? 'Browse schemas'
                : browseModal === 'types'
                  ? 'Browse PII types'
                  : 'Browse tables'
          }
          size={browseModal === 'types' ? 'lg' : 'md'}
          centered
        >
          <Stack gap="sm">
            <TextInput
              placeholder="Search..."
              value={browseSearch}
              onChange={(event) => setBrowseSearch(event.currentTarget?.value || '')}
              spellCheck={false}
            />
            {browseModal === 'source' ? (
              <div className="pii-browse-list">
                {filteredSources.map((source) => (
                  <button key={source.id} type="button" className="pii-browse-row" onClick={() => chooseDataSource(source)}>
                    <span>
                      <b>{source.name}</b>
                      <small>{source.kind || 'database'} · {source.role || 'BOTH'}</small>
                    </span>
                    <Badge variant="light">{source.environment || 'env not set'}</Badge>
                  </button>
                ))}
                {!filteredSources.length ? <div className="pii-empty-small">No matching data sources.</div> : null}
              </div>
            ) : null}
            {browseModal === 'schema' ? (
              <div className="pii-browse-list">
                {filteredSchemas.map((item) => (
                  <button
                    key={item.value}
                    type="button"
                    className="pii-browse-row"
                    onClick={() => {
                      setSchema(item.value);
                      setSelectedTables([]);
                      setTableFocusText('');
                      setReviewTable(null);
                      setBrowseModal(null);
                    }}
                  >
                    <span>
                      <b>{item.label}</b>
                      <small>Schema</small>
                    </span>
                    {item.value === schema ? <Badge variant="light" color="blue">Selected</Badge> : null}
                  </button>
                ))}
                {!dataSourceId ? <div className="pii-empty-small">Select or type a data source first.</div> : null}
                {dataSourceId && !filteredSchemas.length ? <div className="pii-empty-small">No matching schemas.</div> : null}
              </div>
            ) : null}
            {browseModal === 'table' ? (
              <>
                <div className="pii-browse-list">
                  {filteredTables.map((item) => {
                    const selected = selectedTables.includes(item.value);
                    return (
                      <button
                        key={item.value}
                        type="button"
                        className={`pii-browse-row ${selected ? 'is-selected' : ''}`}
                        onClick={() => {
                          const next = selected
                            ? selectedTables.filter((table) => table !== item.value)
                            : [...selectedTables, item.value];
                          applyTableSelection(next);
                        }}
                      >
                        <span>
                          <b>{item.label}</b>
                          <small>Table</small>
                        </span>
                        {selected ? <Badge variant="light" color="blue">Selected</Badge> : null}
                      </button>
                    );
                  })}
                  {!schema ? <div className="pii-empty-small">Type or browse a schema first.</div> : null}
                  {schema && !filteredTables.length ? <div className="pii-empty-small">No matching tables.</div> : null}
                </div>
                <Group justify="space-between">
                  <Text size="sm" c="dimmed">
                    {selectedTables.length ? `${selectedTables.length} selected` : 'Blank means all tables'}
                  </Text>
                  <Group gap="xs">
                    <Button size="xs" variant="subtle" color="gray" onClick={() => applyTableSelection([])}>
                      Clear
                    </Button>
                    <Button size="xs" onClick={() => setBrowseModal(null)}>
                      Apply
                    </Button>
                  </Group>
                </Group>
              </>
            ) : null}
            {browseModal === 'types' ? (
              <>
                <div className="pii-type-browse-groups">
                  {groupedPiiTypes.map((group) => (
                    <section key={group.label} className="pii-type-group">
                      <Text size="xs" tt="uppercase" fw={850} c="dimmed">
                        {group.label}
                      </Text>
                      <div className="pii-type-browse-grid">
                        {group.types.map((type) => {
                          const selected = selectedTypes.includes(type);
                          return (
                            <button
                              key={type}
                              type="button"
                              className={`pii-type-choice ${selected ? 'is-selected' : ''}`}
                              onClick={() => {
                                const next = selected ? selectedTypes.filter((item) => item !== type) : [...selectedTypes, type];
                                applyTypeSelection(next);
                              }}
                            >
                              {type}
                            </button>
                          );
                        })}
                      </div>
                    </section>
                  ))}
                  {!groupedPiiTypes.length ? <div className="pii-empty-small">No matching PII types.</div> : null}
                </div>
                <Group justify="space-between">
                  <Text size="sm" c="dimmed">
                    {selectedTypes.length ? `${selectedTypes.length} selected` : 'Blank means all PII types'}
                  </Text>
                  <Group gap="xs">
                    <Button size="xs" variant="default" onClick={handleSelectAllTypes}>
                      Select all
                    </Button>
                    <Button size="xs" variant="subtle" color="gray" onClick={handleClearTypeScope}>
                      Clear
                    </Button>
                    <Button size="xs" onClick={() => setBrowseModal(null)}>
                      Apply
                    </Button>
                  </Group>
                </Group>
              </>
            ) : null}
          </Stack>
        </Modal>

        <Modal opened={!!deletePattern} onClose={() => setDeletePattern(null)} title="Delete custom pattern" centered>
          <Stack gap="sm">
            <Text size="sm">
              Delete <b>{deletePattern?.piiType}</b> pattern? Future scans will stop using this regex.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setDeletePattern(null)}>
                Cancel
              </Button>
              <Button
                color="red"
                loading={deletePatternMutation.isPending}
                onClick={() => {
                  if (deletePattern) deletePatternMutation.mutate(deletePattern);
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

function BrowseField({
  label,
  value,
  placeholder,
  disabled,
  onChange,
  onBlur,
  onBrowse
}: {
  label: string;
  value: string;
  placeholder: string;
  disabled?: boolean;
  onChange: (value: string) => void;
  onBlur?: () => void;
  onBrowse: () => void;
}) {
  return (
    <div className="pii-browse-field">
      <TextInput
        size="xs"
        label={label}
        placeholder={placeholder}
        value={value}
        onChange={(event) => onChange(event.currentTarget?.value || '')}
        onBlur={onBlur}
        disabled={disabled}
        spellCheck={false}
      />
      <Tooltip label={`Browse ${label.toLowerCase()}`}>
        <ActionIcon
          size={30}
          variant="default"
          onClick={onBrowse}
          disabled={disabled}
          aria-label={`Browse ${label.toLowerCase()}`}
        >
          <IconSearch size={15} />
        </ActionIcon>
      </Tooltip>
    </div>
  );
}

function InlineScope({
  label,
  values,
  emptyLabel,
  maxVisible,
  onClear
}: {
  label: string;
  values: string[];
  emptyLabel: string;
  maxVisible: number;
  onClear: () => void;
}) {
  const visible = values.slice(0, maxVisible);
  return (
    <div className="pii-inline-scope">
      <Text size="xs" fw={850} c="dimmed">
        {label}:
      </Text>
      {values.length ? (
        <Group gap={4} wrap="nowrap" className="pii-inline-chips">
          {visible.map((value) => (
            <Badge key={value} variant="light" color="blue" className="pii-selection-chip">
              {value}
            </Badge>
          ))}
          {values.length > visible.length ? <Badge variant="outline">+{values.length - visible.length}</Badge> : null}
          <button type="button" className="pii-inline-clear" onClick={onClear}>
            clear
          </button>
        </Group>
      ) : (
        <Text size="xs" fw={700}>
          {emptyLabel}
        </Text>
      )}
    </div>
  );
}

function findDataSource(dataSources: DataSource[], typed: string) {
  const clean = typed.trim().toLowerCase();
  if (!clean) return null;
  return (
    dataSources.find((source) => String(source.id) === clean) ||
    dataSources.find((source) => source.name.toLowerCase() === clean) ||
    dataSources.find((source) => `${source.name} (${source.kind || 'database'})`.toLowerCase() === clean) ||
    null
  );
}

function parseTypeScope(value: string) {
  return [
    ...new Set(
      value
        .split(/[,\n]/)
        .map((part) => part.trim())
        .filter(Boolean)
        .map((part) => part.toUpperCase().replace(/\s+/g, '_'))
    )
  ];
}

function parseTableFocus(value: string) {
  return [
    ...new Set(
      value
        .split(/[,\n]/)
        .map((part) => part.trim())
        .filter(Boolean)
    )
  ];
}

function matchBrowse(...values: Array<unknown>) {
  const search = String(values[values.length - 1] || '').trim().toLowerCase();
  if (!search) return true;
  return values
    .slice(0, -1)
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
    .includes(search);
}

function sourceCapable(source: DataSource) {
  const role = String(source.role || 'BOTH').toUpperCase();
  return role === 'SOURCE' || role === 'BOTH';
}

function catalogName(row: Record<string, unknown> | null | undefined, key: 'schema' | 'table') {
  if (!row) return '';
  const value = row[key] ?? row.name ?? row.label;
  return typeof value === 'string' ? value : value == null ? '' : String(value);
}

function notifyError(title: string, error: unknown) {
  notifications.show({
    color: 'red',
    title,
    message: error instanceof Error ? error.message : String(error || 'Unknown error')
  });
}
