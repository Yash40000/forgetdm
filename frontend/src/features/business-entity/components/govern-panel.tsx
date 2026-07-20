'use client';

import { useState } from 'react';
import { Badge, Button, Group, Modal, Select, SimpleGrid, Stack, Tabs, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconBook2, IconHistory, IconRefresh } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import type { LooseMap } from '../hooks';
import type { BusinessEntityDetail } from '../types';
import { formatDate, listOfMaps, statusDot, str, technicalInputProps } from '../utils';

/** Govern: searchable catalog, maker-checker approvals, and the immutable evidence trail. */
export function GovernPanel({ detail, enterprise }: { detail: BusinessEntityDetail; enterprise: LooseMap }) {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const canApprove = can('provision.approve');
  const entityId = detail.entity.id!;
  const catalogAssets = listOfMaps(enterprise, 'catalogAssets');
  const requests = listOfMaps(enterprise, 'governanceRequests');
  const executionRuns = listOfMaps(enterprise, 'executionRuns');
  const packageVersions = listOfMaps(enterprise, 'packageVersions');
  const promotions = listOfMaps(enterprise, 'packagePromotions');
  const loaderStrategies = listOfMaps(enterprise, 'loaderStrategies');

  const [form, setForm] = useState({ action: 'RELEASE', risk: 'MEDIUM', reviewer: '', comments: '' });
  const [decisionDraft, setDecisionDraft] = useState<{
    request: LooseMap;
    decision: 'approve' | 'reject';
    reviewer: string;
    comments: string;
    eSignature: string;
  } | null>(null);
  const [decisionBusy, setDecisionBusy] = useState(false);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: keys.businessEntity.enterprise(entityId) });

  const syncCatalog = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Business Entity management permission is required.');
      return apiPost<LooseMap>(`/api/business-entities/${entityId}/catalog/sync`, {});
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Catalog synced', message: 'Ownership, lineage, and certification refreshed.' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Catalog sync failed', message: error.message })
  });

  const createRequest = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Business Entity management permission is required.');
      return apiPost<LooseMap>(`/api/business-entities/${entityId}/governance-requests`, {
        objectType: 'BUSINESS_ENTITY',
        action: form.action,
        riskLevel: form.risk,
        reviewer: form.reviewer.trim() || null,
        comments: form.comments.trim() || null
      });
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Approval requested', message: `${form.action} · a second person must sign off.` });
      setForm({ ...form, comments: '' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create request', message: error.message })
  });

  const decide = async () => {
    if (!canApprove) return;
    if (!decisionDraft || decisionBusy) return;
    if (!decisionDraft.comments.trim() || !decisionDraft.eSignature.trim()) {
      notifications.show({ color: 'red', title: 'Decision evidence required', message: 'Enter both the decision reason and e-signature.' });
      return;
    }
    setDecisionBusy(true);
    try {
      await apiPost<LooseMap>(`/api/business-entities/governance-requests/${decisionDraft.request.id}/${decisionDraft.decision}`, {
        reviewer: decisionDraft.reviewer.trim() || null,
        comments: decisionDraft.comments.trim(),
        eSignature: decisionDraft.eSignature.trim()
      });
      notifications.show({
        color: decisionDraft.decision === 'approve' ? 'green' : 'yellow',
        title: decisionDraft.decision === 'approve' ? 'Request approved' : 'Request rejected',
        message: `${str(decisionDraft.request.action)} #${str(decisionDraft.request.id)}`
      });
      setDecisionDraft(null);
      await invalidate();
    } catch (error) {
      notifications.show({ color: 'red', title: `Could not ${decisionDraft.decision}`, message: (error as Error).message });
    } finally {
      setDecisionBusy(false);
    }
  };

  return (
    <Stack gap="md">
      <div>
        <Group justify="space-between" align="flex-end" mb="xs">
          <div>
            <Text fw={650} size="sm">
              Maker-checker approvals
            </Text>
            <Text size="xs" c="dimmed">
              Releases, runs, exports, and promotions need a second pair of eyes — requesters can never approve their own request.
            </Text>
          </div>
        </Group>
        {canManage ? <SimpleGrid cols={{ base: 1, sm: 3, lg: 5 }} mb="xs">
          <Select size="xs" label="Action" data={['RELEASE', 'RUN', 'EXPORT', 'PROMOTE']} value={form.action} onChange={(value) => setForm({ ...form, action: value || 'RELEASE' })} />
          <Select size="xs" label="Risk" data={['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']} value={form.risk} onChange={(value) => setForm({ ...form, risk: value || 'MEDIUM' })} />
          <TextInput {...technicalInputProps} size="xs" label="Reviewer" placeholder="checker username" value={form.reviewer} onChange={(e) => setForm({ ...form, reviewer: e.currentTarget.value })} />
          <TextInput size="xs" label="Comments" placeholder="Release reason and evidence" value={form.comments} onChange={(e) => setForm({ ...form, comments: e.currentTarget.value })} />
          <Button size="xs" mt={22} loading={createRequest.isPending} onClick={() => createRequest.mutate()}>
            Request approval
          </Button>
        </SimpleGrid> : null}
        {requests.length ? (
          requests.map((request) => (
            <div className="be-line-row" key={str(request.id)}>
              <span className="be-dot" style={{ background: statusDot(str(request.status)) }} aria-hidden />
              <div className="be-line-body">
                <Group gap={6} wrap="nowrap">
                  <Text size="sm" fw={600}>
                    {str(request.action)} · {str(request.objectType)}
                  </Text>
                  <Badge size="xs" variant="light">
                    {str(request.status)}
                  </Badge>
                  <Badge size="xs" variant="light" color="gray">
                    risk {str(request.riskLevel, '-')}
                  </Badge>
                </Group>
                <Text size="xs" c="dimmed">
                  by {str(request.requestedBy, '-')} · reviewer {str(request.reviewer, 'any')} · {formatDate(str(request.updatedAt) || null)}
                </Text>
              </div>
              {canApprove && str(request.status) === 'PENDING' ? (
                <Group gap={4} wrap="nowrap">
                  <Button size="compact-xs" variant="subtle" onClick={() => setDecisionDraft({ request, decision: 'approve', reviewer: '', comments: '', eSignature: '' })}>
                    Approve
                  </Button>
                  <Button size="compact-xs" variant="subtle" color="red" onClick={() => setDecisionDraft({ request, decision: 'reject', reviewer: '', comments: '', eSignature: '' })}>
                    Reject
                  </Button>
                </Group>
              ) : null}
            </div>
          ))
        ) : (
          <Text size="sm" c="dimmed">
            No governance requests yet.
          </Text>
        )}
      </div>

      <div className="be-govern-data-grid">
        <section className="be-operational-panel" aria-labelledby="be-catalog-heading">
          <Group className="be-operational-panel-head" justify="space-between" align="center" wrap="nowrap">
            <Group gap={9} wrap="nowrap">
              <span className="be-operational-icon"><IconBook2 size={16} /></span>
              <div>
                <Text id="be-catalog-heading" fw={650} size="sm">Catalog</Text>
                <Text size="xs" c="dimmed">Ownership, lineage, certification, and quality.</Text>
              </div>
            </Group>
            {canManage ? <Button
              size="compact-xs"
              variant="subtle"
              leftSection={<IconRefresh size={13} />}
              loading={syncCatalog.isPending}
              onClick={() => syncCatalog.mutate()}
            >
              Sync
            </Button> : null}
          </Group>
          <Group className="be-operational-summary" gap={6} wrap="wrap">
            <Badge size="sm" variant="light" color="blue">{catalogAssets.length} assets</Badge>
            <Badge size="sm" variant="light" color="teal">
              {catalogAssets.filter((asset) => str(asset.certificationStatus).toUpperCase() === 'CERTIFIED').length} certified
            </Badge>
          </Group>
          <div className="be-operational-list">
            {catalogAssets.length ? (
              catalogAssets.map((asset, index) => (
                <div className="be-line-row" key={str(asset.id, String(index))}>
                  <span className="be-dot" style={{ background: statusDot(str(asset.certificationStatus, 'ACTIVE')) }} aria-hidden />
                  <div className="be-line-body">
                    <Group gap={6} wrap="nowrap">
                      <Text size="sm" fw={600} truncate="end">{str(asset.displayName, 'Catalog asset')}</Text>
                      <Badge size="xs" variant="light" color="gray">{str(asset.assetType, 'ASSET')}</Badge>
                    </Group>
                    <Text size="xs" c="dimmed">
                      {str(asset.certificationStatus, 'Uncertified')} · quality {str(asset.qualityScore, 'not scored')}
                    </Text>
                  </div>
                </div>
              ))
            ) : (
              <div className="be-operational-empty">
                <IconBook2 size={22} />
                <Text fw={600} size="sm">Catalog has not been synchronized</Text>
                <Text size="xs" c="dimmed">Sync to capture the current members, ownership, lineage, and quality evidence.</Text>
                {canManage ? <Button size="compact-xs" variant="light" onClick={() => syncCatalog.mutate()}>Sync catalog</Button> : null}
              </div>
            )}
          </div>
        </section>

        <section className="be-operational-panel" aria-labelledby="be-evidence-heading">
          <Group className="be-operational-panel-head" gap={9} wrap="nowrap">
            <span className="be-operational-icon"><IconHistory size={16} /></span>
            <div>
              <Text id="be-evidence-heading" fw={650} size="sm">Evidence</Text>
              <Text size="xs" c="dimmed">Read-only runs, releases, promotions, and loader decisions.</Text>
            </div>
          </Group>
          <Tabs className="be-evidence-tabs" defaultValue={executionRuns.length ? 'runs' : packageVersions.length ? 'versions' : promotions.length ? 'promotions' : 'loaders'}>
            <Tabs.List grow>
              <Tabs.Tab value="runs">Runs <Badge size="xs" variant="light">{executionRuns.length}</Badge></Tabs.Tab>
              <Tabs.Tab value="versions">Versions <Badge size="xs" variant="light">{packageVersions.length}</Badge></Tabs.Tab>
              <Tabs.Tab value="promotions">Promotions <Badge size="xs" variant="light">{promotions.length}</Badge></Tabs.Tab>
              <Tabs.Tab value="loaders">Loaders <Badge size="xs" variant="light">{loaderStrategies.length}</Badge></Tabs.Tab>
            </Tabs.List>
            <div className="be-operational-list be-evidence-list">
              <Tabs.Panel value="runs">
                {executionRuns.length ? executionRuns.map((run, index) => (
                  <div className="be-line-row" key={`run-${str(run.id, String(index))}`}>
                    <span className="be-dot" style={{ background: statusDot(str(run.status ?? run.engineStatus)) }} aria-hidden />
                    <div className="be-line-body">
                      <Text size="sm" fw={600}>{str(run.engine, 'RUN')} · plan #{str(run.executionPlanId, '-')}</Text>
                      <Text size="xs" c="dimmed">run {str(run.engineRunId, '-')} · {str(run.status ?? run.engineStatus, '-')}</Text>
                    </div>
                  </div>
                )) : <EvidenceEmpty label="No execution evidence has been recorded yet." />}
              </Tabs.Panel>
              <Tabs.Panel value="versions">
                {packageVersions.length ? packageVersions.map((version, index) => (
                  <div className="be-line-row" key={`ver-${str(version.id, String(index))}`}>
                    <span className="be-dot" style={{ background: statusDot(str(version.status)) }} aria-hidden />
                    <div className="be-line-body">
                      <Text size="sm" fw={600}>Package #{str(version.packageId, '-')} · v{str(version.versionNumber, '-')}</Text>
                      <Text size="xs" c="dimmed">hash {str(version.artifactHash).slice(0, 12) || '-'} · retention {str(version.retentionUntil, '-')}</Text>
                    </div>
                  </div>
                )) : <EvidenceEmpty label="No immutable package versions have been released." />}
              </Tabs.Panel>
              <Tabs.Panel value="promotions">
                {promotions.length ? promotions.map((promotion, index) => (
                  <div className="be-line-row" key={`promo-${str(promotion.id, String(index))}`}>
                    <span className="be-dot" style={{ background: statusDot(str(promotion.status)) }} aria-hidden />
                    <div className="be-line-body">
                      <Text size="sm" fw={600}>{str(promotion.fromEnvironment, '-')} → {str(promotion.toEnvironment, '-')}</Text>
                      <Text size="xs" c="dimmed">package #{str(promotion.packageId, '-')} · approval {str(promotion.approvedRequestId, '-')}</Text>
                    </div>
                  </div>
                )) : <EvidenceEmpty label="No environment promotions have been recorded." />}
              </Tabs.Panel>
              <Tabs.Panel value="loaders">
                {loaderStrategies.length ? loaderStrategies.map((strategy, index) => (
                  <div className="be-line-row" key={`loader-${str(strategy.id, String(index))}`}>
                    <span className="be-dot" style={{ background: '#94a3b8' }} aria-hidden />
                    <div className="be-line-body">
                      <Text size="sm" fw={600}>{str(strategy.table, 'table')} · {str(strategy.engine, '-')}</Text>
                      <Text size="xs" c="dimmed">{str(strategy.strategy, '-')} · fallback {str(strategy.fallback, '-')}</Text>
                    </div>
                  </div>
                )) : <EvidenceEmpty label="No loader strategy decisions have been captured." />}
              </Tabs.Panel>
            </div>
          </Tabs>
        </section>
      </div>

      <Modal opened={canApprove && Boolean(decisionDraft)} onClose={() => !decisionBusy && setDecisionDraft(null)} closeOnClickOutside={!decisionBusy} closeOnEscape={!decisionBusy} title={decisionDraft?.decision === 'reject' ? 'Reject governance request' : 'Approve governance request'}>
        <Stack gap="sm">
          <Text size="sm">
            Request #{str(decisionDraft?.request.id, '-')} - {str(decisionDraft?.request.action, 'decision')}. The requester cannot approve their own request.
          </Text>
          <TextInput
            {...technicalInputProps}
            label="Reviewer"
            description="Optional; defaults to the signed-in user."
            value={decisionDraft?.reviewer || ''}
            onChange={(event) => decisionDraft && setDecisionDraft({ ...decisionDraft, reviewer: event.currentTarget.value })}
          />
          <Textarea
            label={decisionDraft?.decision === 'reject' ? 'Rejection reason' : 'Approval evidence'}
            description="Required. Include the ticket, review result, and target environment."
            minRows={3}
            value={decisionDraft?.comments || ''}
            onChange={(event) => decisionDraft && setDecisionDraft({ ...decisionDraft, comments: event.currentTarget.value })}
          />
          <TextInput
            {...technicalInputProps}
            label="E-signature"
            description="Required acknowledgement recorded with the decision."
            placeholder="Approved by <username> under CHG-12345"
            value={decisionDraft?.eSignature || ''}
            onChange={(event) => decisionDraft && setDecisionDraft({ ...decisionDraft, eSignature: event.currentTarget.value })}
          />
          <Group justify="flex-end">
            <Button variant="subtle" disabled={decisionBusy} onClick={() => setDecisionDraft(null)}>Cancel</Button>
            <Button
              color={decisionDraft?.decision === 'reject' ? 'red' : 'green'}
              loading={decisionBusy}
              disabled={!decisionDraft?.comments.trim() || !decisionDraft?.eSignature.trim()}
              onClick={() => void decide()}
            >
              {decisionDraft?.decision === 'reject' ? 'Reject request' : 'Approve request'}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function EvidenceEmpty({ label }: { label: string }) {
  return (
    <div className="be-operational-empty">
      <IconHistory size={22} />
      <Text fw={600} size="sm">No evidence in this category</Text>
      <Text size="xs" c="dimmed">{label}</Text>
    </div>
  );
}
