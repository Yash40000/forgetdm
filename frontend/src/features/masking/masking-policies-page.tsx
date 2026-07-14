'use client';

import { useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Checkbox, Group, Paper, Select, SimpleGrid, Stack, Tabs, Text, TextInput, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconLink, IconPlus, IconRefresh, IconShieldCheck, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPatch, apiPost } from '@/lib/api';
import { useConfirm } from '@/components/confirm';
import { NameInput } from '@/components/name-input';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { keys } from '@/lib/keys';
import type { MaskingPolicy, MaskingRule } from '@/lib/types';
import { EmptyPanel, InlineDanger, MaskingHeader, MaskingMetric, ParamControl } from './components';
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
  const [ruleDraft, setRuleDraft] = useState<RuleDraft>(emptyRuleDraft);
  const [policySearch, setPolicySearch] = useState('');
  const [mapDataSourceId, setMapDataSourceId] = useState('');
  const [mapSchema, setMapSchema] = useState('');
  const [mapContextDirty, setMapContextDirty] = useState(false);
  const [selectedFindingIds, setSelectedFindingIds] = useState<number[]>([]);

  const effectivePolicyId = selectedPolicyId ?? policies[0]?.id ?? null;
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
        title="Masking Policy"
        description="Create the reusable column-to-function contract that DataScope, business entities, and provision runs execute. Policies stay calm on the surface, but every rule is explicit."
        action={
          <Button leftSection={<IconRefresh size={16} />} variant="default" onClick={() => policiesQuery.refetch()}>
            Refresh
          </Button>
        }
      />

      <QueryErrorBanner
        errors={[policiesQuery.error, functionsQuery.error, scriptsQuery.error, valueListsQuery.error, lookupReferencesQuery.error, dataSourcesQuery.error, rulesQuery.error, findingsQuery.error]}
        onRetry={() => Promise.all([policiesQuery.refetch(), functionsQuery.refetch(), scriptsQuery.refetch(), valueListsQuery.refetch(), lookupReferencesQuery.refetch(), dataSourcesQuery.refetch(), rulesQuery.refetch(), findingsQuery.refetch()])}
        title="Masking Policy could not load all backend data"
      />

      <SimpleGrid cols={{ base: 1, md: 4 }} spacing="sm">
        <MaskingMetric label="Policies" value={policies.length} detail="Reusable contracts" />
        <MaskingMetric label="Selected rules" value={rules.length} detail={selectedPolicy?.name || 'Open a policy'} />
        <MaskingMetric label="Functions" value={functions.length} detail="Available mask actions" />
        <MaskingMetric label="Scripts" value={scripts.length} detail="SCRIPT exits available" />
      </SimpleGrid>

      <Paper className="forge-card masking-panel" p="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={780}>Create policy</Text>
            <Text size="sm" c="dimmed">
              Optional data source and schema make discovery binding faster, but policies can stay reusable across contexts.
            </Text>
          </div>
          <Badge variant="light">Governed reusable rule set</Badge>
        </Group>
        <div className="masking-policy-create-grid">
          <NameInput label="Policy name" value={policyDraft.name} placeholder="customer360-mask" onChange={(value) => setPolicyDraft({ ...policyDraft, name: value })} />
          <Select label="Data source" data={dataSourceOptions} value={policyDraft.dataSourceId || null} clearable searchable onChange={(value) => setPolicyDraft({ ...policyDraft, dataSourceId: value || '' })} />
          <TextInput label="Schema" value={policyDraft.schemaName} placeholder="optional" onChange={(event) => setPolicyDraft({ ...policyDraft, schemaName: safeInputValue(event) })} {...technicalInputProps} />
          <TextInput label="Description" value={policyDraft.description} onChange={(event) => setPolicyDraft({ ...policyDraft, description: safeInputValue(event) })} />
          <Button mt={22} leftSection={<IconPlus size={16} />} loading={createPolicy.isPending} disabled={!policyDraft.name.trim()} onClick={() => createPolicy.mutate()}>
            Create
          </Button>
        </div>
      </Paper>

      <section className="masking-workspace">
        <Paper className="forge-card masking-rail" p="md">
          <Group justify="space-between">
            <Text fw={780}>Policies</Text>
            <Badge variant="light">{filteredPolicies.length}</Badge>
          </Group>
          <TextInput mt="sm" placeholder="Search policies..." value={policySearch} onChange={(event) => setPolicySearch(safeInputValue(event))} />
          <Stack gap={4} mt="sm">
            {filteredPolicies.map((policy) => (
              <button
                key={policy.id}
                type="button"
                className={`masking-policy-row ${policy.id === effectivePolicyId ? 'is-active' : ''}`}
                onClick={() => selectPolicy(policy)}
              >
                <Text fw={760}>{policy.name}</Text>
                <Text size="xs" c="dimmed">
                  {policy.schemaName || 'Any schema'} · {policy.description || 'No description'}
                </Text>
              </button>
            ))}
            {!filteredPolicies.length ? <EmptyPanel title="No policies yet" detail="Create a policy, or generate one from PII Discovery." /> : null}
          </Stack>
        </Paper>

        <Paper className="forge-card masking-panel" p={0}>
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
                <InlineDanger onClick={() => void removePolicy(selectedPolicy)}>Delete policy</InlineDanger>
              </div>
              <Tabs defaultValue="rules" classNames={{ list: 'forge-tabs-list' }}>
                <Tabs.List px="md">
                  <Tabs.Tab value="rules" leftSection={<IconShieldCheck size={15} />}>Rules</Tabs.Tab>
                  <Tabs.Tab value="discovery" leftSection={<IconLink size={15} />}>Bind from Discovery</Tabs.Tab>
                </Tabs.List>
                <Tabs.Panel value="rules" p="md">
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
                              <Select size="xs" data={functions} searchable value={rule.function} onChange={(value) => value && patchRule(rule, { function: value })} />
                            </td>
                            <td>
                              <ParamControl functionName={rule.function} index={1} value={rule.param1 || ''} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => patchRule(rule, { param1: value || null })} />
                            </td>
                            <td>
                              <ParamControl functionName={rule.function} index={2} value={rule.param2 || ''} scripts={scripts} valueLists={valueLists} lookupReferences={lookupReferences} onChange={(value) => patchRule(rule, { param2: value || null })} />
                            </td>
                            <td>
                              <Tooltip label="Delete rule">
                              <ActionIcon variant="subtle" color="red" aria-label={`Delete rule ${ruleSignature(rule)}`} onClick={() => removeRule(rule)}>
                                  <IconTrash size={16} />
                                </ActionIcon>
                              </Tooltip>
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
                    <Button mt={22} leftSection={<IconLink size={16} />} loading={addMappedRules.isPending} disabled={!selectedFindingIds.length} onClick={() => addMappedRules.mutate()}>
                      Add selected ({selectedFindingIds.length})
                    </Button>
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
            <EmptyPanel title="Open or create a policy" detail="Select a policy from the left rail to edit rules, bind discovery findings, and prepare it for DataScope." />
          )}
        </Paper>
      </section>
    </main>
  );
}
