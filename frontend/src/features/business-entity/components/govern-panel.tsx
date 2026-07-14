'use client';

import { useState } from 'react';
import { Badge, Button, Group, Modal, Select, SimpleGrid, Stack, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { LooseMap } from '../hooks';
import type { BusinessEntityDetail } from '../types';
import { formatDate, listOfMaps, statusDot, str, technicalInputProps } from '../utils';

/** Govern: searchable catalog, maker-checker approvals, and the immutable evidence trail. */
export function GovernPanel({ detail, enterprise }: { detail: BusinessEntityDetail; enterprise: LooseMap }) {
  const queryClient = useQueryClient();
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
    mutationFn: () => apiPost<LooseMap>(`/api/business-entities/${entityId}/catalog/sync`, {}),
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Catalog synced', message: 'Ownership, lineage, and certification refreshed.' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Catalog sync failed', message: error.message })
  });

  const createRequest = useMutation({
    mutationFn: () =>
      apiPost<LooseMap>(`/api/business-entities/${entityId}/governance-requests`, {
        objectType: 'BUSINESS_ENTITY',
        action: form.action,
        riskLevel: form.risk,
        reviewer: form.reviewer.trim() || null,
        comments: form.comments.trim() || null
      }),
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Approval requested', message: `${form.action} · a second person must sign off.` });
      setForm({ ...form, comments: '' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create request', message: error.message })
  });

  const decide = async () => {
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
    <Stack gap="lg">
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
          <Button size="xs" variant="light" loading={syncCatalog.isPending} onClick={() => syncCatalog.mutate()}>
            Sync catalog
          </Button>
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 3, lg: 5 }} mb="xs">
          <Select size="xs" label="Action" data={['RELEASE', 'RUN', 'EXPORT', 'PROMOTE']} value={form.action} onChange={(value) => setForm({ ...form, action: value || 'RELEASE' })} />
          <Select size="xs" label="Risk" data={['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']} value={form.risk} onChange={(value) => setForm({ ...form, risk: value || 'MEDIUM' })} />
          <TextInput {...technicalInputProps} size="xs" label="Reviewer" placeholder="checker username" value={form.reviewer} onChange={(e) => setForm({ ...form, reviewer: e.currentTarget.value })} />
          <TextInput size="xs" label="Comments" placeholder="Release reason and evidence" value={form.comments} onChange={(e) => setForm({ ...form, comments: e.currentTarget.value })} />
          <Button size="xs" mt={22} loading={createRequest.isPending} onClick={() => createRequest.mutate()}>
            Request approval
          </Button>
        </SimpleGrid>
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
              {str(request.status) === 'PENDING' ? (
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

      <div>
        <Text fw={650} size="sm" mb={6}>
          Catalog
        </Text>
        {catalogAssets.length ? (
          catalogAssets.slice(0, 12).map((asset, index) => (
            <div className="be-line-row" key={index}>
              <span className="be-dot" style={{ background: statusDot(str(asset.certificationStatus, 'ACTIVE')) }} aria-hidden />
              <div className="be-line-body">
                <Text size="sm" fw={600}>
                  {str(asset.displayName)}
                </Text>
                <Text size="xs" c="dimmed">
                  {str(asset.assetType)} · {str(asset.certificationStatus, '-')} · quality {str(asset.qualityScore, '-')}
                </Text>
              </div>
            </div>
          ))
        ) : (
          <Text size="sm" c="dimmed">
            Catalog is empty — run Sync catalog above.
          </Text>
        )}
      </div>

      <div>
        <Text fw={650} size="sm" mb={6}>
          Evidence
        </Text>
        <Text size="xs" c="dimmed" mb="xs">
          Read-only audit trail: runs, loader decisions, immutable versions, promotions.
        </Text>
        {executionRuns.slice(0, 10).map((run, index) => (
          <div className="be-line-row" key={`run-${index}`}>
            <span className="be-dot" style={{ background: statusDot(str(run.status ?? run.engineStatus)) }} aria-hidden />
            <div className="be-line-body">
              <Text size="sm" fw={600}>
                {str(run.engine, 'RUN')} · plan #{str(run.executionPlanId, '-')}
              </Text>
              <Text size="xs" c="dimmed">
                run {str(run.engineRunId, '-')} · {str(run.status ?? run.engineStatus, '-')}
              </Text>
            </div>
          </div>
        ))}
        {packageVersions.slice(0, 6).map((version, index) => (
          <div className="be-line-row" key={`ver-${index}`}>
            <span className="be-dot" style={{ background: statusDot(str(version.status)) }} aria-hidden />
            <div className="be-line-body">
              <Text size="sm" fw={600}>
                Package #{str(version.packageId, '-')} · v{str(version.versionNumber, '-')}
              </Text>
              <Text size="xs" c="dimmed">
                hash {str(version.artifactHash).slice(0, 12) || '-'} · retention {str(version.retentionUntil, '-')}
              </Text>
            </div>
          </div>
        ))}
        {promotions.slice(0, 6).map((promotion, index) => (
          <div className="be-line-row" key={`promo-${index}`}>
            <span className="be-dot" style={{ background: statusDot(str(promotion.status)) }} aria-hidden />
            <div className="be-line-body">
              <Text size="sm" fw={600}>
                {str(promotion.fromEnvironment, '-')} → {str(promotion.toEnvironment, '-')}
              </Text>
              <Text size="xs" c="dimmed">
                package #{str(promotion.packageId, '-')} · approval {str(promotion.approvedRequestId, '-')}
              </Text>
            </div>
          </div>
        ))}
        {loaderStrategies.slice(0, 6).map((strategy, index) => (
          <div className="be-line-row" key={`loader-${index}`}>
            <span className="be-dot" style={{ background: '#94a3b8' }} aria-hidden />
            <div className="be-line-body">
              <Text size="sm" fw={600}>
                {str(strategy.table, 'table')} · {str(strategy.engine, '-')}
              </Text>
              <Text size="xs" c="dimmed">
                {str(strategy.strategy, '-')} · fallback {str(strategy.fallback, '-')}
              </Text>
            </div>
          </div>
        ))}
        {!executionRuns.length && !packageVersions.length && !promotions.length && !loaderStrategies.length ? (
          <Text size="sm" c="dimmed">
            No evidence recorded yet — it appears here after the first runs and promotions.
          </Text>
        ) : null}
      </div>

      <Modal opened={Boolean(decisionDraft)} onClose={() => !decisionBusy && setDecisionDraft(null)} closeOnClickOutside={!decisionBusy} closeOnEscape={!decisionBusy} title={decisionDraft?.decision === 'reject' ? 'Reject governance request' : 'Approve governance request'}>
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
