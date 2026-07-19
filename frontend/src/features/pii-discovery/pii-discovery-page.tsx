'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import {
  ActionIcon,
  Badge,
  Button,
  Drawer,
  Group,
  Loader,
  Modal,
  Paper,
  Select,
  Stack,
  Text,
  TextInput,
  ThemeIcon,
  Title,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconDatabase,
  IconDownload,
  IconFileCertificate,
  IconHistory,
  IconMap,
  IconPlus,
  IconRefresh,
  IconRegex,
  IconSearch,
  IconShieldCheck,
  IconShieldSearch,
  IconX
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { NameInput } from '@/components/name-input';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiPatch, apiPost } from '@/lib/api';
import { usePermissions } from '@/lib/use-permissions';
import { keys } from '@/lib/keys';
import type { DataSource } from '@/lib/types';
import type { MaskingPolicy, MaskingRule } from '@/lib/types';
import { usePolicies, usePolicyRules } from '@/features/masking/hooks';
import {
  ColumnReviewWorkspace,
  FindingsWorkspaceTable,
  ImpactDiagramPanel,
  ImpactMapPanel,
  LiveScanPanel,
  PolicyRulesWorkspace,
  ScanHistoryPanel
} from './components';
import {
  useDataSources,
  useDiscoveryColumnReview,
  useDiscoveryFindings,
  useDiscoveryGraph,
  useDiscoveryJobs,
  useMaskFunctions,
  usePiiTypes,
  useSchemas,
  useTables
} from './hooks';
import type {
  DiscoveryColumnReviewRow,
  DiscoveryFinding,
  DiscoveryGraph,
  DiscoveryJob,
  ManualDraft
} from './types';
import {
  completePiiTypeCatalog,
  DISCOVERY_SCAN_PROFILES,
  discoveryJobLive,
  findingSort,
  groupPiiTypes,
  orderPiiTypes,
} from './utils';

type DiscoveryWorkspace = 'findings' | 'columns' | 'impact' | 'history';
type PolicyDrawer = 'browse' | 'create' | null;

export function PiiDiscoveryPage() {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('discovery.manage');
  const completedJobRef = useRef<string | null>(null);
  const [workspace, setWorkspace] = useState<DiscoveryWorkspace | null>(null);
  const [policyDrawer, setPolicyDrawer] = useState<PolicyDrawer>(null);
  const [selectedPolicyId, setSelectedPolicyId] = useState<number | null>(null);
  const [policySearch, setPolicySearch] = useState('');
  const [dataSourceId, setDataSourceId] = useState<number | null>(null);
  const [dataSourceInput, setDataSourceInput] = useState('');
  const [schema, setSchema] = useState<string | null>(null);
  const [scanProfile, setScanProfile] = useState('GENERIC');
  const [selectedTypes, setSelectedTypes] = useState<string[]>([]);
  const [customScopeEdited, setCustomScopeEdited] = useState(false);
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

  useEffect(() => {
    if (!workspace) return;
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = previous; };
  }, [workspace]);

  const dataSourcesQuery = useDataSources();
  const schemasQuery = useSchemas(dataSourceId);
  const tablesQuery = useTables(dataSourceId, schema);
  const piiTypesQuery = usePiiTypes();
  const functionsQuery = useMaskFunctions();
  const jobsQuery = useDiscoveryJobs(dataSourceId, schema);
  const findingsQuery = useDiscoveryFindings(dataSourceId, schema, resultTypeScope);
  const columnReviewQuery = useDiscoveryColumnReview(dataSourceId, schema, reviewTable, resultTypeScope);
  const graphQuery = useDiscoveryGraph(dataSourceId, schema, resultTypeScope);
  const policiesQuery = usePolicies();
  const policyRulesQuery = usePolicyRules(selectedPolicyId);

  const dataSources = dataSourcesQuery.data || [];
  const schemas = schemasQuery.data || [];
  const tables = tablesQuery.data || [];
  const piiTypes = useMemo(
    () => completePiiTypeCatalog(piiTypesQuery.data || []),
    [piiTypesQuery.data]
  );
  const maskFunctions = functionsQuery.data || [];
  const findings = (findingsQuery.data || []).slice().sort(findingSort);
  const jobs = jobsQuery.data || [];
  const latestJob = jobs[0] || null;
  const liveJob = jobs.find((job) => discoveryJobLive(job.status)) || latestJob;
  const policies = useMemo(() => policiesQuery.data || [], [policiesQuery.data]);
  const selectedPolicy = policies.find((policy) => policy.id === selectedPolicyId) || null;
  const policyRules = useMemo(() => policyRulesQuery.data || [], [policyRulesQuery.data]);
  const currentScanComplete = String(liveJob?.status || '').toUpperCase() === 'COMPLETED' && Number(liveJob?.totalTables || 0) > 0;
  const hasReviewContext = currentScanComplete || Boolean(selectedPolicy);
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
  const selectedScanProfile = DISCOVERY_SCAN_PROFILES.find((profile) => profile.value === scanProfile)
    || DISCOVERY_SCAN_PROFILES[0];
  const customTypeScope = scanProfile === 'CUSTOM';
  const profileTypeScope = customTypeScope
    ? customScopeEdited ? selectedTypes : piiTypes
    : selectedScanProfile.types?.length
      ? [...selectedScanProfile.types]
      : piiTypes;
  const displayedTypeScopeText = customTypeScope
    ? customScopeEdited ? typeScopeText : piiTypes.join(', ')
    : profileTypeScope.join(', ');

  const chooseDataSource = (source: DataSource) => {
    if (source.id !== dataSourceId) {
      setSelectedPolicyId(null);
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
    if (scanProfile === 'CUSTOM' && !customScopeEdited) return piiTypes;
    const types = parseTypeScope(typeScopeText);
    if (scanProfile === 'CUSTOM') setCustomScopeEdited(true);
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
  const approved = findings.filter((finding) => finding.status === 'APPROVED').length;
  const policyNameError = validatePolicyName(policyName);
  const filteredPolicies = useMemo(() => {
    const needle = policySearch.trim().toLowerCase();
    return policies
      .filter((policy) => !needle || [policy.name, policy.description, policy.schemaName].filter(Boolean).join(' ').toLowerCase().includes(needle))
      .sort((left, right) => {
        const leftExact = Number(left.dataSourceId === dataSourceId && (!schema || left.schemaName === schema));
        const rightExact = Number(right.dataSourceId === dataSourceId && (!schema || right.schemaName === schema));
        return rightExact - leftExact || left.name.localeCompare(right.name);
      });
  }, [dataSourceId, policies, policySearch, schema]);
  const effectiveGraph = useMemo(
    () => selectedPolicy ? graphWithPolicyRules(graphQuery.data || {}, policyRules) : (graphQuery.data || {}),
    [graphQuery.data, policyRules, selectedPolicy]
  );

  const startScanMutation = useMutation({
    mutationFn: async ({ sourceId, schemaName, piiTypes, tableNames }: { sourceId: number; schemaName: string; piiTypes: string[]; tableNames: string[] }) => {
      const job = await apiPost<DiscoveryJob>(`/api/discovery/scan-jobs/${sourceId}?schema=${encodeURIComponent(schemaName)}`, {
        piiTypes,
        tableNames
      });
      return job;
    },
    onSuccess: (job, variables) => {
      setSelectedPolicyId(null);
      setResultTypeScope([...(job.selectedTypes || variables.piiTypes)]);
      completedJobRef.current = null;
      setWorkspace(null);
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
      const nameError = validatePolicyName(policyName);
      if (nameError) throw new Error(nameError);
      const name = policyName.trim();
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
      if (policy.id) setSelectedPolicyId(policy.id);
      setPolicyDrawer(null);
      void queryClient.invalidateQueries({ queryKey: keys.policies.all });
    },
    onError: (error) => notifyError('Policy generation failed', error)
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
    if (tablesQuery.isLoading || tablesQuery.isFetching) {
      notifyError('Table catalog is still loading', new Error('Wait for the schema table list to finish loading, then start the scan.'));
      return;
    }
    if (tablesQuery.isSuccess && tableOptions.length === 0) {
      notifyError('Schema has no tables', new Error(`Schema ${schemaName} contains no scannable tables. Choose another schema or add tables first.`));
      return;
    }
    startScanMutation.mutate({ sourceId: source.id, schemaName, piiTypes: types, tableNames });
  };

  const changeScanProfile = (value: string | null) => {
    const next = value || 'GENERIC';
    setScanProfile(next);
    setCustomScopeEdited(false);
    const profile = DISCOVERY_SCAN_PROFILES.find((item) => item.value === next);
    // Custom begins with the complete catalogue visible and selected; users can then remove only
    // the types they do not want. This avoids a misleading empty field meaning an invisible "all".
    const types = profile?.types == null ? piiTypes : [...profile.types];
    applyTypeSelection(types);
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
    setWorkspace('findings');
    notifications.show({
      color: 'blue',
      title: 'Result scope applied',
      message: types.length ? `Showing results for ${types.length} selected PII type${types.length === 1 ? '' : 's'}.` : 'Showing results for all PII types.'
    });
  };

  const handleSelectAllTypes = () => {
    setCustomScopeEdited(false);
    applyTypeSelection(piiTypes);
  };

  const handleClearTypeScope = () => {
    setCustomScopeEdited(true);
    applyTypeSelection([]);
  };

  const selectPolicyContext = (policy: MaskingPolicy) => {
    const source = policy.dataSourceId ? dataSources.find((item) => item.id === policy.dataSourceId) : null;
    if (source) chooseDataSource(source);
    else if (policy.dataSourceId) setDataSourceId(policy.dataSourceId);
    if (policy.schemaName) setSchema(policy.schemaName);
    setSelectedPolicyId(policy.id);
    setResultTypeScope([]);
    setPolicyDrawer(null);
    setWorkspace(null);
    notifications.show({ color: 'blue', title: 'Policy context selected', message: `${policy.name} is ready for findings and impact review.` });
  };

  const openCreatePolicy = () => {
    if (!currentScanComplete) {
      notifyError('Complete a scan first', new Error('A masking policy can only be generated from a completed scan with at least one scanned table.'));
      return;
    }
    if (!approved) {
      notifyError('No approved findings', new Error('Approve at least one discovery finding before generating a policy.'));
      return;
    }
    if (!policyName.trim()) setPolicyName(suggestedPolicyName(dataSourceInput, schema));
    setPolicyDrawer('create');
  };

  const filteredSources = dataSources
    .filter(sourceCapable)
    .filter((source) => matchBrowse(source.name, source.kind, source.role, source.jdbcUrl, browseSearch));
  const filteredSchemas = schemaOptions.filter((item) => matchBrowse(item.label, browseSearch));
  const filteredTables = tableOptions.filter((item) => matchBrowse(item.label, browseSearch));
  const groupedPiiTypes = groupPiiTypes(customTypeScope ? piiTypes : profileTypeScope, browseSearch);

  return (
    <main className="forge-page pii-page">
        <Stack gap="md">
          <Group justify="space-between" align="center" wrap="nowrap" className="pii-page-heading">
            <Group gap="sm" wrap="nowrap">
              <ThemeIcon size={40} radius="md" variant="light"><IconShieldSearch size={21} /></ThemeIcon>
              <div>
                <Group gap="xs"><Title order={1}>PII Discovery</Title>{discoveryJobLive(liveJob?.status) ? <Badge color="blue" variant="light">Scan active</Badge> : null}</Group>
                <Text c="dimmed">Find, review, and protect sensitive data from one operational workspace.</Text>
              </div>
            </Group>
          <Group gap="xs" className="pii-page-actions">
              {jobsQuery.isFetching || findingsQuery.isFetching ? <Loader size="sm" /> : null}
              {jobs.length ? (
                <Button size="xs" variant="subtle" leftSection={<IconHistory size={15} />} onClick={() => setWorkspace('history')}>
                  Recent scans
                </Button>
              ) : null}
              <Button
                size="xs"
                variant={selectedPolicy ? 'light' : 'subtle'}
                leftSection={<IconShieldCheck size={15} />}
                rightSection={selectedPolicy ? <Badge size="xs" variant="filled">Selected</Badge> : null}
                onClick={() => setPolicyDrawer('browse')}
              >
                Policies
              </Button>
              <Button component={Link} href="/pii-discovery/patterns" size="xs" variant="subtle" leftSection={<IconRegex size={15} />}>
                Detection patterns
              </Button>
              <Button
                size="xs"
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
              policiesQuery.error,
              policyRulesQuery.error
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
                  setSelectedPolicyId(null);
                  setSchema(value || null);
                  setSelectedTables([]);
                  setTableFocusText('');
                  setReviewTable(null);
                }}
                onBrowse={() => openBrowse('schema')}
                disabled={!dataSourceId}
              />
              <Select
                size="xs"
                label="Scan profile"
                data={DISCOVERY_SCAN_PROFILES.map((profile) => ({ value: profile.value, label: profile.label }))}
                value={scanProfile}
                onChange={changeScanProfile}
                allowDeselect={false}
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
                value={displayedTypeScopeText}
                onChange={(value) => {
                  setCustomScopeEdited(true);
                  setTypeScopeText(value);
                }}
                onBlur={() => applyTypedTypes()}
                onBrowse={() => openBrowse('types')}
                readOnly={!customTypeScope}
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
                <InlineScope
                  label="PII"
                  values={profileTypeScope}
                  emptyLabel="all types"
                  maxVisible={5}
                  onClear={customTypeScope ? handleClearTypeScope : undefined}
                />
                <Badge size="sm" variant="light" title={selectedScanProfile.detail}>{selectedScanProfile.label}</Badge>
                <span className="pii-reviewing-text">Reviewing {reviewScopeLabel}</span>
              </div>
              <Group gap={6} className="pii-command-actions">
                <Button size="xs" variant="default" onClick={handleUseScopeForResults}>
                  Use type scope for results
                </Button>
                {canManage ? (
                  <Button
                    size="xs"
                    leftSection={<IconShieldSearch size={14} />}
                    loading={startScanMutation.isPending}
                    onClick={handleStartScan}
                  >
                    Start scan
                  </Button>
                ) : null}
              </Group>
            </div>
          </Paper>

          <section className="pii-live-command-center">
            <LiveScanPanel job={liveJob} actions={<>
                <Button size="xs" variant="light" leftSection={<IconSearch size={14} />} rightSection={<Badge size="xs" variant="filled">{selectedPolicy ? policyRules.length : findings.length}</Badge>} disabled={!hasReviewContext} onClick={() => setWorkspace('findings')}>
                  Findings
                </Button>
                <Button size="xs" variant="default" leftSection={<IconDatabase size={14} />} onClick={() => setWorkspace('columns')}>
                  Column review
                </Button>
                <Button size="xs" variant="default" leftSection={<IconFileCertificate size={14} />} disabled={!currentScanComplete || approved === 0} onClick={openCreatePolicy}>
                  Create policy
                </Button>
                <Button size="xs" variant="default" leftSection={<IconMap size={14} />} disabled={!hasReviewContext} onClick={() => setWorkspace('impact')}>
                  Impact map
                </Button>
              </>} />
          </section>

          {workspace ? (
            <div className="pii-focus-workspace" role="dialog" aria-modal="true" aria-label={`${workspaceTitle(workspace)} workspace`}>
              <header className="pii-focus-workspace-head">
                <Group gap="sm" wrap="nowrap">
                  <ThemeIcon size={38} radius="md" variant="light">{workspaceIcon(workspace)}</ThemeIcon>
                  <div>
                    <Group gap="xs"><Text fw={800}>{workspaceTitle(workspace)}</Text><Badge variant="light">{workspace === 'history' ? `${jobs.length} runs` : selectedPolicy ? `${policyRules.length} policy rules` : `${findings.length} findings`}</Badge></Group>
                    <Text size="xs" c="dimmed">{dataSourceInput || 'No source'} / {schema || 'No schema'} - {reviewScopeLabel}</Text>
                  </div>
                </Group>
                <Group gap="xs" wrap="nowrap">
                  {workspace === 'findings' ? (
                    <Button
                      size="xs"
                      variant="default"
                      leftSection={<IconDownload size={15} />}
                      disabled={selectedPolicy ? !policyRules.length : !visibleFindings.length}
                      onClick={() => selectedPolicy
                        ? downloadPolicyRulesCsv(selectedPolicy, policyRules)
                        : downloadFindingsCsv(dataSourceInput, schema, visibleFindings)}
                    >
                      Download CSV
                    </Button>
                  ) : null}
                  <Tooltip label="Close workspace">
                    <ActionIcon size="lg" variant="subtle" aria-label="Close focused workspace" onClick={() => setWorkspace(null)}><IconX size={20} /></ActionIcon>
                  </Tooltip>
                </Group>
              </header>
              <div className="pii-focus-workspace-body">

            {workspace === 'findings' && selectedPolicy ? (
              <PolicyRulesWorkspace policy={selectedPolicy} rules={policyRules} loading={policyRulesQuery.isLoading || policyRulesQuery.isFetching} />
            ) : null}

            {workspace === 'findings' && !selectedPolicy ? (
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
                <FindingsWorkspaceTable
                  rows={visibleFindings}
                  functions={maskFunctions}
                  updating={updateFindingMutation.isPending}
                  onUpdate={(id, body) => updateFindingMutation.mutate({ id, body })}
                />
              </Paper>
            ) : null}

            {workspace === 'columns' ? (
              <ColumnReviewWorkspace
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
            ) : null}

            {workspace === 'impact' ? (
              <Stack gap="md">
                <ImpactDiagramPanel graph={effectiveGraph} loading={graphQuery.isLoading || graphQuery.isFetching || policyRulesQuery.isFetching} />
                <ImpactMapPanel graph={effectiveGraph} loading={graphQuery.isLoading || graphQuery.isFetching || policyRulesQuery.isFetching} />
              </Stack>
            ) : null}

            {workspace === 'history' ? <ScanHistoryPanel history={jobs} /> : null}

              </div>
            </div>
          ) : null}
        </Stack>

        <Drawer
          opened={policyDrawer === 'browse'}
          onClose={() => setPolicyDrawer(null)}
          position="right"
          size="lg"
          title="Masking policies"
          classNames={{ body: 'pii-policy-drawer-body' }}
        >
          <Stack gap="md">
            <Text size="sm" c="dimmed">
              Select an existing policy to review its governed rules and relationship impact without running another scan.
            </Text>
            {selectedPolicy ? (
              <Paper className="pii-policy-context" p="sm">
                <Group justify="space-between" align="flex-start">
                  <div>
                    <Group gap="xs"><Text fw={780}>{selectedPolicy.name}</Text><Badge variant="light" color="green">Active context</Badge></Group>
                    <Text size="xs" c="dimmed">{policyContext(selectedPolicy, dataSources)}</Text>
                  </div>
                  <Button size="xs" variant="subtle" color="gray" onClick={() => setSelectedPolicyId(null)}>Clear</Button>
                </Group>
              </Paper>
            ) : null}
            <TextInput
              leftSection={<IconSearch size={15} />}
              placeholder="Search policy name, description, or schema..."
              value={policySearch}
              onChange={(event) => setPolicySearch(event.currentTarget?.value || '')}
              spellCheck={false}
            />
            <div className="pii-policy-browser-list">
              {filteredPolicies.map((policy) => {
                const exact = policy.dataSourceId === dataSourceId && (!schema || policy.schemaName === schema);
                return (
                  <button
                    key={policy.id}
                    type="button"
                    className={`pii-policy-choice ${selectedPolicyId === policy.id ? 'is-selected' : ''}`}
                    onClick={() => selectPolicyContext(policy)}
                  >
                    <span>
                      <b>{policy.name}</b>
                      <small>{policyContext(policy, dataSources)}</small>
                      {policy.description ? <small>{policy.description}</small> : null}
                    </span>
                    <span className="pii-policy-choice-badges">
                      {exact ? <Badge size="xs" variant="light" color="green">Current source</Badge> : null}
                      <Badge size="xs" variant="light">{policy.status || 'DRAFT'}</Badge>
                    </span>
                  </button>
                );
              })}
              {!filteredPolicies.length ? <div className="pii-empty-small">No matching masking policies.</div> : null}
            </div>
            <Group justify="space-between">
              <Button component={Link} href="/masking-policies" variant="subtle">Open Masking Policies</Button>
              <Button leftSection={<IconPlus size={15} />} disabled={!currentScanComplete || approved === 0} onClick={openCreatePolicy}>
                Create from scan
              </Button>
            </Group>
          </Stack>
        </Drawer>

        <Drawer
          opened={policyDrawer === 'create'}
          onClose={() => setPolicyDrawer(null)}
          position="right"
          size="md"
          title="Create policy from scan"
        >
          <Stack gap="md">
            <Paper className="pii-policy-context" p="sm">
              <Text size="xs" fw={850} tt="uppercase" c="dimmed">Scan evidence</Text>
              <Text fw={760}>{dataSourceInput || 'No source'} / {schema || 'No schema'}</Text>
              <Text size="sm" c="dimmed">{approved} approved finding{approved === 1 ? '' : 's'} will become policy rules.</Text>
            </Paper>
            <NameInput
              label="Policy name"
              description="8-120 characters. Start with a letter or number; spaces, dots, hyphens, and underscores are allowed."
              placeholder="CUSTOMER DATA PROTECTION"
              maxLength={120}
              value={policyName}
              error={policyName && policyNameError ? policyNameError : undefined}
              onChange={setPolicyName}
            />
            <Text size="sm" c="dimmed">
              Only approved findings are included. Suggested and rejected classifications remain discovery evidence and are not added to the policy.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setPolicyDrawer(null)}>Discard</Button>
              <Button
                leftSection={<IconFileCertificate size={16} />}
                loading={generatePolicyMutation.isPending}
                disabled={Boolean(policyNameError) || !policyName.trim() || !currentScanComplete || approved === 0}
                onClick={() => generatePolicyMutation.mutate()}
              >
                Create policy
              </Button>
            </Group>
          </Stack>
        </Drawer>

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
                      <small>{source.kind || 'database'} / {source.role || 'BOTH'}</small>
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
                      setSelectedPolicyId(null);
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
                          const selected = customTypeScope ? profileTypeScope.includes(type) : true;
                          return (
                            <button
                              key={type}
                              type="button"
                              className={`pii-type-choice ${selected ? 'is-selected' : ''}`}
                              onClick={() => {
                                if (!customTypeScope) return;
                                const next = selected ? profileTypeScope.filter((item) => item !== type) : [...profileTypeScope, type];
                                setCustomScopeEdited(true);
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
                    {customTypeScope
                      ? profileTypeScope.length ? `${profileTypeScope.length} selected` : 'Blank means all PII types'
                      : `${profileTypeScope.length} types included in ${selectedScanProfile.label}`}
                  </Text>
                  <Group gap="xs">
                    {customTypeScope ? <Button size="xs" variant="default" onClick={handleSelectAllTypes}>Select all</Button> : null}
                    {customTypeScope ? <Button size="xs" variant="subtle" color="gray" onClick={handleClearTypeScope}>Clear</Button> : null}
                    <Button size="xs" onClick={() => setBrowseModal(null)}>
                      {customTypeScope ? 'Apply' : 'Close'}
                    </Button>
                  </Group>
                </Group>
              </>
            ) : null}
          </Stack>
        </Modal>

    </main>
  );
}

function workspaceTitle(workspace: DiscoveryWorkspace) {
  if (workspace === 'findings') return 'Findings review';
  if (workspace === 'columns') return 'Column review';
  if (workspace === 'impact') return 'PII impact map';
  if (workspace === 'history') return 'Discovery run history';
  return 'PII Discovery';
}

function workspaceIcon(workspace: DiscoveryWorkspace) {
  if (workspace === 'findings') return <IconSearch size={19} />;
  if (workspace === 'columns') return <IconDatabase size={19} />;
  if (workspace === 'impact') return <IconMap size={19} />;
  if (workspace === 'history') return <IconHistory size={19} />;
  return <IconShieldSearch size={19} />;
}

function BrowseField({
  label,
  value,
  placeholder,
  disabled,
  readOnly,
  onChange,
  onBlur,
  onBrowse
}: {
  label: string;
  value: string;
  placeholder: string;
  disabled?: boolean;
  readOnly?: boolean;
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
        readOnly={readOnly}
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
  onClear?: () => void;
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
          {onClear ? <button type="button" className="pii-inline-clear" onClick={onClear}>clear</button> : null}
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

function validatePolicyName(value: string) {
  const clean = value.trim();
  if (clean.length < 8 || clean.length > 120) return 'Policy name must be 8-120 characters.';
  if (!/^[A-Za-z0-9][A-Za-z0-9 _.-]*$/.test(clean)) {
    return 'Start with a letter or number and use only letters, numbers, spaces, dots, hyphens, or underscores.';
  }
  return null;
}

function suggestedPolicyName(sourceName: string, schema: string | null) {
  const base = `${sourceName || 'SOURCE'} ${schema || 'SCHEMA'} PII POLICY`
    .toUpperCase()
    .replace(/[^A-Z0-9 _.-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  return (base || 'DISCOVERY PII POLICY').slice(0, 120);
}

function policyContext(policy: MaskingPolicy, dataSources: DataSource[]) {
  const source = dataSources.find((item) => item.id === policy.dataSourceId);
  return `${source?.name || (policy.dataSourceId ? `Source ${policy.dataSourceId}` : 'Any source')} / ${policy.schemaName || 'Any schema'}`;
}

function graphWithPolicyRules(graph: DiscoveryGraph, rules: MaskingRule[]): DiscoveryGraph {
  const rulesByTable = new Map<string, MaskingRule[]>();
  for (const rule of rules) {
    const key = String(rule.tableName || '').trim().toLowerCase();
    if (!key) continue;
    const current = rulesByTable.get(key) || [];
    current.push(rule);
    rulesByTable.set(key, current);
  }

  const seen = new Set<string>();
  const nodes = (graph.nodes || []).map((node) => {
    const key = String(node.label || node.id || '').trim().toLowerCase();
    const tableRules = rulesByTable.get(key);
    if (!tableRules) return node;
    seen.add(key);
    return {
      ...node,
      piiCount: tableRules.length,
      piiColumns: tableRules.map((rule) => ({
        column: rule.columnName,
        piiType: 'POLICY_RULE',
        function: rule.function,
        param1: rule.param1,
        param2: rule.param2,
        status: 'APPROVED',
        confidence: 1
      }))
    };
  });

  for (const [key, tableRules] of rulesByTable) {
    if (seen.has(key)) continue;
    const label = tableRules[0]?.tableName || key;
    nodes.push({
      id: label,
      label,
      piiCount: tableRules.length,
      piiColumns: tableRules.map((rule) => ({
        column: rule.columnName,
        piiType: 'POLICY_RULE',
        function: rule.function,
        param1: rule.param1,
        param2: rule.param2,
        status: 'APPROVED',
        confidence: 1
      }))
    });
  }

  return { ...graph, nodes };
}

function downloadFindingsCsv(sourceName: string, schema: string | null, findings: DiscoveryFinding[]) {
  const rows = findings.map((finding) => [
    sourceName,
    schema || '',
    finding.tableName,
    finding.columnName,
    finding.dataType || '',
    finding.piiType,
    finding.confidence,
    finding.status,
    finding.suggestedFunction || '',
    finding.suggestedParam1 || '',
    finding.suggestedParam2 || ''
  ]);
  downloadCsv(
    `pii-findings-${sourceName || 'source'}-${schema || 'schema'}.csv`,
    ['Data source', 'Schema', 'Table', 'Column', 'Data type', 'PII type', 'Confidence', 'Status', 'Mask function', 'Param 1', 'Param 2'],
    rows
  );
}

function downloadPolicyRulesCsv(policy: MaskingPolicy, rules: MaskingRule[]) {
  downloadCsv(
    `policy-${policy.name}.csv`,
    ['Policy', 'Schema', 'Table', 'Column', 'Mask function', 'Param 1', 'Param 2', 'Deterministic'],
    rules.map((rule) => [
      policy.name,
      rule.schemaName || policy.schemaName || '',
      rule.tableName,
      rule.columnName,
      rule.function,
      rule.param1 || '',
      rule.param2 || '',
      rule.deterministic === false ? 'No' : 'Yes'
    ])
  );
}

function downloadCsv(fileName: string, headers: string[], rows: Array<Array<unknown>>) {
  const content = [headers, ...rows].map((row) => row.map(csvCell).join(',')).join('\r\n');
  const blob = new Blob(['\uFEFF', content], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/-+/g, '-').slice(0, 160);
  document.body.appendChild(link);
  link.click();
  link.remove();
  URL.revokeObjectURL(url);
}

function csvCell(value: unknown) {
  let text = value == null ? '' : String(value);
  if (/^[=+\-@]/.test(text)) text = `'${text}`;
  return `"${text.replace(/"/g, '""')}"`;
}

function notifyError(title: string, error: unknown) {
  notifications.show({
    color: 'red',
    title,
    message: error instanceof Error ? error.message : String(error || 'Unknown error')
  });
}
