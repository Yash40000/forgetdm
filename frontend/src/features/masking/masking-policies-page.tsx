'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Checkbox, Drawer, Group, Modal, Paper, Select, SimpleGrid, Stack, Tabs, Text, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconEdit, IconLink, IconPlus, IconRefresh, IconShieldCheck, IconTrash, IconX } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPatch, apiPost } from '@/lib/api';
import { useConfirm } from '@/components/confirm';
import { usePermissions } from '@/lib/use-permissions';
import { NameInput } from '@/components/name-input';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { keys } from '@/lib/keys';
import type { MaskingPolicy, MaskingRule } from '@/lib/types';
import { EmptyPanel, InlineDanger, MaskingHeader, ParamControl } from './components';
import { useDataSources, useDiscoveryFindings, useMaskingFunctions, useMaskingLookupReferences, useMaskingScripts, useMaskingValueLists, usePolicies, usePolicyRules } from './hooks';
import type { DiscoveryFinding, PolicyDraft, RuleDraft } from './types';
import {
  defaultMaskParamsForMap,
  formatDate,
  numberOrNull,
  ruleSignature,
  safeInputValue,
  technicalInputProps
} from './utils';

const emptyPolicyDraft: PolicyDraft = {
  name: '',
  description: '',
  dataSourceId: '',
  schemaName: ''
};

const emptyRuleDraft: RuleDraft = {
  schemaName: '',
  tableName: '',
  columnName: '',
  functionName: '',
  param1: '',
  param2: ''
};

export function MaskingPoliciesPage() {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('policy.manage');
  const policiesQuery = usePolicies();
  const functionsQuery = useMaskingFunctions();
  const scriptsQuery = useMaskingScripts();
  const valueListsQuery = useMaskingValueLists();
  const lookupReferencesQuery = useMaskingLookupReferences();
  const dataSourcesQuery = useDataSources();
  const policies = useMemo(() => policiesQuery.data || [], [policiesQuery.data]);
  const functions = useMemo(() => functionsQuery.data || [], [functionsQuery.data]);
  const scripts = useMemo(() => scriptsQuery.data || [], [scriptsQuery.data]);
  const valueLists = useMemo(() => valueListsQuery.data || [], [valueListsQuery.data]);
  const lookupReferences = useMemo(() => lookupReferencesQuery.data || [], [lookupReferencesQuery.data]);
  const dataSources = useMemo(() => dataSourcesQuery.data || [], [dataSourcesQuery.data]);
  const [policyDraft, setPolicyDraft] = useState<PolicyDraft>(emptyPolicyDraft);
  const [selectedPolicyId, setSelectedPolicyId] = useState<number | null>(null);
  const [createOpened, setCreateOpened] = useState(false);
  const [editorOpened, setEditorOpened] = useState(false);
  const [ruleDraft, setRuleDraft] = useState<RuleDraft>(emptyRuleDraft);
  const [policySearch, setPolicySearch] = useState('');
  const [mapDataSourceId, setMapDataSourceId] = useState('');
  const [mapSchema, setMapSchema] = useState('');
  const [mapContextDirty, setMapContextDirty] = useState(false);
  const [selectedFindingIds, setSelectedFindingIds] = useState<number[]>([]);

  const effectivePolicyId = selectedPolicyId;
  const selectedPolicy = policies.find((policy) => policy.id === effectivePolicyId) || null;
  const rulesQuery = usePolicyRules(effectivePolicyId);
  const rules = rulesQuery.data || [];
  const effectiveMapDataSourceId = mapContextDirty
    ? mapDataSourceId
    : selectedPolicy?.dataSourceId
      ? String(selectedPolicy.dataSourceId)
      : '';
  const effectiveMapSchema = mapContextDirty ? mapSchema : selectedPolicy?.schemaName || '';
  const effectiveRuleSchema = ruleDraft.schemaName || selectedPolicy?.schemaName || '';
  const effectiveRuleFunction = ruleDraft.functionName || (functions.includes('FIRST_NAME') ? 'FIRST_NAME' : functions[0] || '');
  const mapSourceNumber = numberOrNull(effectiveMapDataSourceId);
  const findingsQuery = useDiscoveryFindings(mapSourceNumber, effectiveMapSchema);
  const findings = useMemo(() => findingsQuery.data || [], [findingsQuery.data]);

  const dataSourceOptions = dataSources.map((source) => ({
    value: String(source.id),
    label: `${source.name} (${source.kind || source.role || 'DB'})`
  }));

  const createPolicy = useMutation({
    mutationFn: () =>
      apiPost<MaskingPolicy>('/api/policies', {
        name: policyDraft.name.trim(),
        description: policyDraft.description.trim() || null,
        dataSourceId: numberOrNull(policyDraft.dataSourceId),
        schemaName: policyDraft.schemaName.trim() || null
      }),
    onSuccess: (created) => {
      notifications.show({ color: 'green', title: 'Policy created', message: created.name });
      setPolicyDraft(emptyPolicyDraft);
      setSelectedPolicyId(created.id);
      setCreateOpened(false);
      setEditorOpened(true);
      setMapContextDirty(false);
      setRuleDraft(emptyRuleDraft);
      queryClient.invalidateQueries({ queryKey: keys.policies.all });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create policy', message: (error as Error).message })
  });

  const deletePolicy = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/policies/${id}`, { method: 'DELETE' }),
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Policy deleted', message: 'Rules were removed with the policy.' });
      setSelectedPolicyId(null);
      setEditorOpened(false);
      queryClient.invalidateQueries({ queryKey: keys.policies.all });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not delete policy', message: (error as Error).message })
  });

  const addRule = useMutation({
    mutationFn: () =>
      apiPost<MaskingRule>(`/api/policies/${effectivePolicyId}/rules`, {
        schemaName: effectiveRuleSchema.trim() || null,
        tableName: ruleDraft.tableName.trim(),
        columnName: ruleDraft.columnName.trim(),
        function: effectiveRuleFunction,
        param1: ruleDraft.param1 || null,
        param2: ruleDraft.param2 || null
      }),
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Rule added', message: `${ruleDraft.tableName}.${ruleDraft.columnName}` });
      setRuleDraft((current) => ({ ...emptyRuleDraft, schemaName: current.schemaName, functionName: current.functionName }));
      queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not add rule', message: (error as Error).message })
  });

  const patchRule = async (rule: MaskingRule, patch: Record<string, string | null>) => {
    try {
      await apiPatch<MaskingRule>(`/api/policies/rules/${rule.id}`, patch);
      queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Rule update failed', message: (error as Error).message });
    }
  };

  const removeRule = async (rule: MaskingRule) => {
    const ok = await confirm({
      title: 'Delete masking rule',
      message: `Delete ${ruleSignature(rule)} from this policy?`,
      okText: 'Delete',
      danger: true
    });
    if (!ok) return;
    try {
      await apiFetch(`/api/policies/rules/${rule.id}`, { method: 'DELETE' });
      notifications.show({ color: 'green', title: 'Rule removed', message: ruleSignature(rule) });
      queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not remove rule', message: (error as Error).message });
    }
  };

  const addMappedRules = useMutation({
    mutationFn: async () => {
      if (!effectivePolicyId) throw new Error('Open a policy first.');
      const selected = findings.filter((finding) => selectedFindingIds.includes(finding.id));
      for (const finding of selected) {
        const fn = finding.suggestedFunction || 'FORMAT_PRESERVE';
        const defaults = defaultMaskParamsForMap(fn, finding.piiType);
        await apiPost<MaskingRule>(`/api/policies/${effectivePolicyId}/rules`, {
          schemaName: finding.schemaName || effectiveMapSchema || null,
          tableName: finding.tableName,
          columnName: finding.columnName,
          function: fn,
          param1: finding.suggestedParam1 || defaults.param1,
          param2: finding.suggestedParam2 || defaults.param2
        });
      }
      return selected.length;
    },
    onSuccess: (count) => {
      notifications.show({ color: 'green', title: 'Discovery rules added', message: `${count} rule(s) added to ${selectedPolicy?.name || 'policy'}.` });
      setSelectedFindingIds([]);
      queryClient.invalidateQueries({ queryKey: keys.policies.rules(effectivePolicyId) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not bind discovery findings', message: (error as Error).message })
  });

  const filteredPolicies = useMemo(() => {
    const q = policySearch.trim().toLowerCase();
    if (!q) return policies;
    return policies.filter((policy) => `${policy.name} ${policy.description || ''} ${policy.schemaName || ''}`.toLowerCase().includes(q));
  }, [policies, policySearch]);

  const findingsByTable = useMemo(() => {
    const map = new Map<string, DiscoveryFinding[]>();
    for (const finding of findings) {
      const list = map.get(finding.tableName) || [];
      list.push(finding);
      map.set(finding.tableName, list);
    }
    return Array.from(map.entries()).sort(([a], [b]) => a.localeCompare(b));
  }, [findings]);

  const selectedRuleReady = !!effectivePolicyId && !!ruleDraft.tableName.trim() && !!ruleDraft.columnName.trim() && !!effectiveRuleFunction;
  const selectedFindingSet = new Set(selectedFindingIds);

  const toggleFinding = (id: number, checked: boolean) => {
    setSelectedFindingIds((ids) => (checked ? Array.from(new Set([...ids, id])) : ids.filter((item) => item !== id)));
  };

  const toggleTable = (rows: DiscoveryFinding[], checked: boolean) => {
    const ids = rows.map((row) => row.id);
    setSelectedFindingIds((current) => (checked ? Array.from(new Set([...current, ...ids])) : current.filter((id) => !ids.includes(id))));
  };

  const selectPolicy = (policy: MaskingPolicy) => {
    setSelectedPolicyId(policy.id);
    setEditorOpened(true);
    setMapContextDirty(false);
    setSelectedFindingIds([]);
    setRuleDraft((current) => ({ ...current, schemaName: policy.schemaName || '' }));
  };

  const removePolicy = async (policy: MaskingPolicy) => {
    const ok = await confirm({
      title: 'Delete masking policy',
      message: `Delete "${policy.name}" and all of its rules? DataScope and saved jobs that reference it may no longer be runnable.`,
      okText: 'Delete',
      danger: true
    });
    if (ok) deletePolicy.mutate(policy.id);
  };

  return (
    <main className="forge-page masking-page">
      {confirmElement}
      <MaskingHeader
        eyebrow="Mask"
        title="Masking Policies"
        description="Govern reusable masking rules for discovery, DataScope, and provision runs."
        action={
          <Group gap="xs">
            <Tooltip label="Refresh policies">
              <ActionIcon size="lg" variant="default" aria-label="Refresh policies" onClick={() => policiesQuery.refetch()}>
                <IconRefresh size={17} />
              </ActionIcon>
            </Tooltip>
            {canManage ? (
              <Button leftSection={<IconPlus size={16} />} onClick={() => setCreateOpened(true)}>
                New policy
              </Button>
            ) : null}
          </Group>
        }
      />

      <QueryErrorBanner
        errors={[policiesQuery.error, functionsQuery.error, scriptsQuery.error, valueListsQuery.error, lookupReferencesQuery.error, dataSourcesQuery.error, rulesQuery.error, findingsQuery.error]}
        onRetry={() => Promise.all([policiesQuery.refetch(), functionsQuery.refetch(), scriptsQuery.refetch(), valueListsQuery.refetch(), lookupReferencesQuery.refetch(), dataSourcesQuery.refetch(), rulesQuery.refetch(), findingsQuery.refetch()])}
        title="Masking Policy could not load all backend data"
      />

      <Paper className="forge-card masking-panel" p={0}>
        <div className="masking-panel-head">
          <div>
            <Group gap="xs">
              <Text fw={800}>Policy inventory</Text>
              <Badge variant="light">{filteredPolicies.length}</Badge>
            </Group>
            <Text size="sm" c="dimmed">Open a policy only when its rules need attention.</Text>
          </div>
          <TextInput
            placeholder="Search name, schema, or description"
            value={policySearch}
            onChange={(event) => setPolicySearch(safeInputValue(event))}
            w={360}
          />
        </div>
        {filteredPolicies.length ? (
          <div className="forge-grid-panel">
            <table className="forge-table masking-policy-inventory">
              <thead>
                <tr>
                  <th>Policy</th>
                  <th>Scope</th>
                  <th>Status</th>
                  <th>Created</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {filteredPolicies.map((policy) => (
                  <tr key={policy.id}>
                    <td>
                      <Text fw={780}>{policy.name}</Text>
                      <Text size="xs" c="dimmed">{policy.description || 'Reusable masking rule set'}</Text>
                    </td>
                    <td>
                      <Text size="sm">{policy.schemaName || 'Any schema'}</Text>
                      <Text size="xs" c="dimmed">
                        {policy.dataSourceId ? dataSources.find((source) => source.id === policy.dataSourceId)?.name || `Source ${policy.dataSourceId}` : 'Any data source'}
                      </Text>
                    </td>
                    <td><Badge variant="light" color="green">{policy.status || 'ACTIVE'}</Badge></td>
                    <td><Text size="sm">{formatDate(policy.createdAt)}</Text></td>
                    <td>
                      <Group gap={4} wrap="nowrap">
                        <Button size="xs" variant="subtle" leftSection={<IconEdit size={15} />} onClick={() => selectPolicy(policy)}>
                          {canManage ? 'Open' : 'View'}
                        </Button>
                        {canManage ? (
                          <Tooltip label={`Delete ${policy.name}`}>
                            <ActionIcon variant="subtle" color="red" aria-label={`Delete ${policy.name}`} onClick={() => void removePolicy(policy)}>
                              <IconTrash size={16} />
                            </ActionIcon>
                          </Tooltip>
                        ) : null}
                      </Group>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyPanel title="No policies found" detail="Create a policy, or generate one from PII Discovery." />
        )}
      </Paper>

      <Drawer opened={createOpened} onClose={() => setCreateOpened(false)} position="right" size="lg" title="New masking policy">
        <Stack gap="md">
          <Text size="sm" c="dimmed">
            Bind to a data source and schema when the policy is application-specific, or leave both blank to keep it reusable.
          </Text>
          <NameInput
            label="Policy name"
            description="8 to 120 characters"
            value={policyDraft.name}
            placeholder="CUSTOMER360-MASK"
            maxLength={120}
            onChange={(value) => setPolicyDraft({ ...policyDraft, name: value })}
          />
          <Select label="Data source" data={dataSourceOptions} value={policyDraft.dataSourceId || null} clearable searchable onChange={(value) => setPolicyDraft({ ...policyDraft, dataSourceId: value || '' })} />
          <TextInput label="Schema" value={policyDraft.schemaName} placeholder="optional" onChange={(event) => setPolicyDraft({ ...policyDraft, schemaName: safeInputValue(event) })} {...technicalInputProps} />
          <TextInput label="Description" value={policyDraft.description} onChange={(event) => setPolicyDraft({ ...policyDraft, description: safeInputValue(event) })} />
          <Group justify="flex-end" mt="sm">
            <Button variant="default" onClick={() => setCreateOpened(false)}>Cancel</Button>
            <Button leftSection={<IconPlus size={16} />} loading={createPolicy.isPending} disabled={policyDraft.name.trim().length < 8} onClick={() => createPolicy.mutate()}>
              Create policy
            </Button>
          </Group>
        </Stack>
      </Drawer>

      <Modal opened={editorOpened && Boolean(selectedPolicy)} onClose={() => setEditorOpened(false)} fullScreen withCloseButton={false}>
        <Paper className="masking-panel masking-policy-editor" p={0}>
          {selectedPolicy ? (
            <>
              <div className="masking-panel-head">
                <div>
                  <Group gap="xs">
                    <Text fw={820}>{selectedPolicy.name}</Text>
                    <Badge variant="light">{selectedPolicy.schemaName || 'Any schema'}</Badge>
                  </Group>
                  <Text size="sm" c="dimmed">
                    {selectedPolicy.description || 'No description'} · created {formatDate(selectedPolicy.createdAt)}
                  </Text>
                </div>
                <Group gap="xs">
                  {canManage ? <InlineDanger onClick={() => void removePolicy(selectedPolicy)}>Delete policy</InlineDanger> : null}
                  <Button variant="default" leftSection={<IconX size={16} />} onClick={() => setEditorOpened(false)}>
                    Close workspace
                  </Button>
                </Group>
              </div>
              <Tabs defaultValue="rules" classNames={{ list: 'forge-tabs-list' }}>
                <Tabs.List px="md">
                  <Tabs.Tab value="rules" leftSection={<IconShieldCheck size={15} />}>Rules</Tabs.Tab>
                  <Tabs.Tab value="discovery" leftSection={<IconLink size={15} />}>Bind from Discovery</Tabs.Tab>
                </Tabs.List>
                <Tabs.Panel value="rules" p="md">
                  {canManage ? (
                  <>
                  <SimpleGrid cols={{ base: 1, md: 6 }} spacing="sm" className="masking-rule-add-grid">
                    <TextInput label="Schema" value={effectiveRuleSchema} placeholder="optional" onChange={(event) => setRuleDraft({ ...ruleDraft, schemaName: safeInputValue(event) })} {...technicalInputProps} />
                    <TextInput label="Table" value={ruleDraft.tableName} onChange={(event) => setRuleDraft({ ...ruleDraft, tableName: safeInputValue(event) })} {...technicalInputProps} />
                    <TextInput label="Column" value={ruleDraft.columnName} onChange={(event) => setRuleDraft({ ...ruleDraft, columnName: safeInputValue(event) })} {...technicalInputProps} />
                    <Select label="Function" data={functions} searchable value={effectiveRuleFunction || null} onChange={(value) => setRuleDraft({ ...ruleDraft, functionName: value || '', param1: '', param2: '' })} />
                    <ParamControl functionName={effectiveRuleFunction} index={1} value={ruleDraft.param1} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => setRuleDraft({ ...ruleDraft, param1: value })} />
                    <ParamControl functionName={effectiveRuleFunction} index={2} value={ruleDraft.param2} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => setRuleDraft({ ...ruleDraft, param2: value })} />
                  </SimpleGrid>
                  <Group justify="flex-end" mt="sm">
                    <Button leftSection={<IconPlus size={16} />} loading={addRule.isPending} disabled={!selectedRuleReady} onClick={() => addRule.mutate()}>
                      Add rule
                    </Button>
                  </Group>
                  </>
                  ) : null}

                  <div className="forge-grid-panel masking-rules-table">
                    <table className="forge-table">
                      <thead>
                        <tr>
                          <th>Column</th>
                          <th>Function</th>
                          <th>Param 1</th>
                          <th>Param 2</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        {rules.map((rule) => (
                          <tr key={rule.id}>
                            <td>
                              <Text fw={760}>{rule.columnName}</Text>
                              <Text size="xs" c="dimmed" className="masking-mono-line">
                                {rule.schemaName ? `${rule.schemaName}.` : ''}{rule.tableName}
                              </Text>
                            </td>
                            <td>
                              <Select size="xs" data={functions} searchable value={rule.function} disabled={!canManage} onChange={(value) => value && patchRule(rule, { function: value })} />
                            </td>
                            <td>
                              <ParamControl functionName={rule.function} index={1} value={rule.param1 || ''} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => patchRule(rule, { param1: value || null })} />
                            </td>
                            <td>
                              <ParamControl functionName={rule.function} index={2} value={rule.param2 || ''} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => patchRule(rule, { param2: value || null })} />
                            </td>
                            <td>
                              {canManage ? (
                                <Tooltip label="Delete rule">
                                  <ActionIcon variant="subtle" color="red" aria-label={`Delete rule ${ruleSignature(rule)}`} onClick={() => removeRule(rule)}>
                                    <IconTrash size={16} />
                                  </ActionIcon>
                                </Tooltip>
                              ) : null}
                            </td>
                          </tr>
                        ))}
                        {!rules.length ? (
                          <tr>
                            <td colSpan={5}>
                              <Text c="dimmed">No rules yet. Add one manually or bind from Discovery.</Text>
                            </td>
                          </tr>
                        ) : null}
                      </tbody>
                    </table>
                  </div>
                </Tabs.Panel>
                <Tabs.Panel value="discovery" p="md">
                  <Group justify="space-between" align="flex-start">
                    <div>
                      <Text fw={780}>Bind discovery findings</Text>
                      <Text size="sm" c="dimmed">
                        Load approved/suggested PII findings, select only the tables and columns you want, and add them as rules.
                      </Text>
                    </div>
                    <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={() => findingsQuery.refetch()} disabled={!mapSourceNumber || !effectiveMapSchema.trim()}>
                      Load
                    </Button>
                  </Group>
                  <SimpleGrid cols={{ base: 1, md: 3 }} spacing="sm" mt="md">
                    <Select label="Discovery data source" data={dataSourceOptions} value={effectiveMapDataSourceId || null} searchable clearable onChange={(value) => { setMapContextDirty(true); setMapDataSourceId(value || ''); }} />
                    <TextInput label="Schema" value={effectiveMapSchema} onChange={(event) => { setMapContextDirty(true); setMapSchema(safeInputValue(event)); }} {...technicalInputProps} />
                    {canManage ? (
                      <Button mt={22} leftSection={<IconLink size={16} />} loading={addMappedRules.isPending} disabled={!selectedFindingIds.length} onClick={() => addMappedRules.mutate()}>
                        Add selected ({selectedFindingIds.length})
                      </Button>
                    ) : null}
                  </SimpleGrid>
                  <div className="masking-discovery-map">
                    {findingsByTable.map(([table, rows]) => {
                      const allSelected = rows.every((row) => selectedFindingSet.has(row.id));
                      return (
                        <Paper key={table} className="masking-finding-table" p="sm">
                          <Group justify="space-between">
                            <Checkbox label={<Text fw={780}>{table}</Text>} checked={allSelected} onChange={(event) => toggleTable(rows, event.currentTarget.checked)} />
                            <Badge variant="light">{rows.length} finding{rows.length === 1 ? '' : 's'}</Badge>
                          </Group>
                          <div className="masking-finding-list">
                            {rows.map((finding) => (
                              <Checkbox
                                key={finding.id}
                                checked={selectedFindingSet.has(finding.id)}
                                onChange={(event) => toggleFinding(finding.id, event.currentTarget.checked)}
                                label={
                                  <span>
                                    <b>{finding.columnName}</b> <span className="masking-muted">· {finding.piiType} · {finding.suggestedFunction || 'FORMAT_PRESERVE'}</span>
                                  </span>
                                }
                              />
                            ))}
                          </div>
                        </Paper>
                      );
                    })}
                    {!findingsByTable.length ? (
                      <EmptyPanel
                        title={findingsQuery.isFetching ? 'Loading discovery findings...' : 'No discovery findings loaded'}
                        detail="Pick a data source and schema where PII Discovery has run. Then select only the findings you want this policy to own."
                      />
                    ) : null}
                  </div>
                </Tabs.Panel>
              </Tabs>
            </>
          ) : (
            <EmptyPanel title="Policy unavailable" detail="Close this workspace and open a policy from the inventory." />
          )}
        </Paper>
      </Modal>
    </main>
  );
}
