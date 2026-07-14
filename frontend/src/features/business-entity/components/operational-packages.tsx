'use client';

import { useState } from 'react';
import { Badge, Button, Group, Modal, NumberInput, ScrollArea, Select, SimpleGrid, Stack, Table, Text, Textarea, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDownload, IconEye, IconPackageExport, IconVersions } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { apiPost, apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { LooseMap } from '../hooks';
import { formatDate, statusDot, str, technicalInputProps } from '../utils';

export function OperationalPackageList({ entityId, packages }: { entityId: number; packages: LooseMap[] }) {
  const queryClient = useQueryClient();
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [versionForm, setVersionForm] = useState({ retentionPolicy: 'STANDARD_7_YEAR', retentionDays: '2555', changeNote: '' });
  const [promotionForm, setPromotionForm] = useState({ versionId: '', fromEnvironment: 'DEV', toEnvironment: 'QA', approver: '', comments: '' });

  const detailQuery = useQuery({
    queryKey: ['business-entities', 'operational-package', selectedId],
    enabled: Boolean(selectedId),
    queryFn: () => apiFetch<LooseMap>(`/api/business-entities/operational-packages/${selectedId}`)
  });

  const refresh = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.businessEntity.enterprise(entityId) });
    if (selectedId) await detailQuery.refetch();
  };

  const createVersion = useMutation({
    mutationFn: () => {
      if (!selectedId) throw new Error('Choose a package first.');
      if (!versionForm.changeNote.trim()) throw new Error('Enter the version change note.');
      return apiPost<LooseMap>(`/api/business-entities/operational-packages/${selectedId}/versions`, {
        retentionPolicy: versionForm.retentionPolicy,
        retentionDays: Number(versionForm.retentionDays) || 2555,
        changeNote: versionForm.changeNote.trim()
      });
    },
    onSuccess: async (version) => {
      notifications.show({ color: 'green', title: 'Immutable version created', message: `Version ${str(version.versionNumber, '-')} is retained under ${versionForm.retentionPolicy}.` });
      setVersionForm((current) => ({ ...current, changeNote: '' }));
      await refresh();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create package version', message: error.message })
  });

  const promote = useMutation({
    mutationFn: () => {
      if (!selectedId) throw new Error('Choose a package first.');
      if (!promotionForm.toEnvironment.trim() || !promotionForm.comments.trim()) throw new Error('Target environment and promotion evidence are required.');
      return apiPost<LooseMap>(`/api/business-entities/operational-packages/${selectedId}/promotions`, {
        versionId: promotionForm.versionId ? Number(promotionForm.versionId) : null,
        fromEnvironment: promotionForm.fromEnvironment.trim() || 'DEV',
        toEnvironment: promotionForm.toEnvironment.trim(),
        approver: promotionForm.approver.trim() || null,
        comments: promotionForm.comments.trim()
      });
    },
    onSuccess: async (promotion) => {
      const status = str(promotion.status, 'READY_FOR_APPROVAL');
      notifications.show({
        color: status === 'PROMOTED' ? 'green' : 'yellow',
        title: status === 'PROMOTED' ? 'Package promoted' : 'Promotion needs approval',
        message: `${promotionForm.fromEnvironment || 'DEV'} to ${promotionForm.toEnvironment}`
      });
      setPromotionForm((current) => ({ ...current, comments: '' }));
      await refresh();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not promote package', message: error.message })
  });

  const detail = detailQuery.data || {};
  const versions = Array.isArray(detail.versions) ? (detail.versions as LooseMap[]) : [];
  const promotions = Array.isArray(detail.promotions) ? (detail.promotions as LooseMap[]) : [];
  const versionOptions = versions.map((version) => ({ value: str(version.id), label: `v${str(version.versionNumber)} - ${str(version.artifactHash).slice(0, 12)}` }));

  const downloadRunner = () => {
    const script = str(detail.shellScript);
    if (!script) {
      notifications.show({ color: 'yellow', title: 'Runner unavailable', message: 'This package does not contain a shell runner.' });
      return;
    }
    const blob = new Blob([script], { type: 'text/x-shellscript;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `${str(detail.name, 'forgetdm-package').replace(/[^a-z0-9._-]+/gi, '-')}.sh`;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  return (
    <>
      <Stack gap={6}>
        {packages.length ? packages.map((pkg) => (
          <div className="be-line-row" key={str(pkg.id)}>
            <span className="be-dot" style={{ background: statusDot(str(pkg.status)) }} aria-hidden />
            <div className="be-line-body">
              <Group gap={6} wrap="nowrap">
                <Text size="sm" fw={600}>{str(pkg.name)}</Text>
                <Badge size="xs" variant="light">{str(pkg.status, 'READY')}</Badge>
              </Group>
              <Text size="xs" c="dimmed">{str(pkg.packageType)} - plan #{str(pkg.executionPlanId, '-')} - {formatDate(str(pkg.updatedAt) || null)}</Text>
            </div>
            <Button size="compact-xs" variant="light" leftSection={<IconEye size={13} />} onClick={() => setSelectedId(Number(pkg.id))}>Manage</Button>
          </div>
        )) : <Text size="sm" c="dimmed">No operational packages yet.</Text>}
      </Stack>

      <Modal opened={Boolean(selectedId)} onClose={() => setSelectedId(null)} title="Operational package" size="92%">
        <Stack gap="md">
          <QueryErrorBanner errors={[detailQuery.error]} onRetry={() => detailQuery.refetch()} title="Package details could not be loaded" />
          {detailQuery.isLoading ? <Text c="dimmed">Loading package evidence...</Text> : (
            <>
              <Group justify="space-between" align="flex-start">
                <div>
                  <Group gap="xs"><Text fw={850}>{str(detail.name, 'Operational package')}</Text><Badge variant="light">{str(detail.status, 'READY')}</Badge></Group>
                  <Text size="xs" c="dimmed">Package #{str(detail.id, '-')} - execution plan #{str(detail.executionPlanId, '-')} - {str(detail.packageType, '-')}</Text>
                </div>
                <Button variant="light" leftSection={<IconDownload size={15} />} disabled={!str(detail.shellScript)} onClick={downloadRunner}>Download runner</Button>
              </Group>

              <Textarea {...technicalInputProps} label="Scheduler runner" readOnly autosize minRows={8} maxRows={16} value={str(detail.shellScript)} />

              <SimpleGrid cols={{ base: 1, lg: 2 }}>
                <Stack gap="sm">
                  <Group gap="xs"><IconVersions size={17} /><Text fw={800}>Create immutable version</Text></Group>
                  <SimpleGrid cols={{ base: 1, sm: 2 }}>
                    <Select label="Retention policy" data={['STANDARD_7_YEAR', 'REGULATORY_10_YEAR', 'PROJECT', 'CUSTOM']} value={versionForm.retentionPolicy} onChange={(value) => setVersionForm({ ...versionForm, retentionPolicy: value || 'STANDARD_7_YEAR' })} />
                    <NumberInput label="Retention days" min={1} value={versionForm.retentionDays === '' ? '' : Number(versionForm.retentionDays)} onChange={(value) => setVersionForm({ ...versionForm, retentionDays: value === '' || value === null ? '' : String(value) })} />
                  </SimpleGrid>
                  <Textarea label="Change note" minRows={2} value={versionForm.changeNote} onChange={(event) => setVersionForm({ ...versionForm, changeNote: event.currentTarget.value })} />
                  <Button leftSection={<IconVersions size={15} />} loading={createVersion.isPending} disabled={!versionForm.changeNote.trim()} onClick={() => createVersion.mutate()}>Create version</Button>
                </Stack>

                <Stack gap="sm">
                  <Group gap="xs"><IconPackageExport size={17} /><Text fw={800}>Promote version</Text></Group>
                  <Select label="Immutable version" placeholder="Latest version" clearable data={versionOptions} value={promotionForm.versionId || null} onChange={(value) => setPromotionForm({ ...promotionForm, versionId: value || '' })} />
                  <SimpleGrid cols={{ base: 1, sm: 2 }}>
                    <TextInput {...technicalInputProps} label="From" value={promotionForm.fromEnvironment} onChange={(event) => setPromotionForm({ ...promotionForm, fromEnvironment: event.currentTarget.value })} />
                    <TextInput {...technicalInputProps} label="To" value={promotionForm.toEnvironment} onChange={(event) => setPromotionForm({ ...promotionForm, toEnvironment: event.currentTarget.value })} />
                  </SimpleGrid>
                  <TextInput {...technicalInputProps} label="Approver" description="Used when an approved PROMOTE request exists." value={promotionForm.approver} onChange={(event) => setPromotionForm({ ...promotionForm, approver: event.currentTarget.value })} />
                  <Textarea label="Promotion evidence" minRows={2} value={promotionForm.comments} onChange={(event) => setPromotionForm({ ...promotionForm, comments: event.currentTarget.value })} />
                  <Button leftSection={<IconPackageExport size={15} />} loading={promote.isPending} disabled={!promotionForm.toEnvironment.trim() || !promotionForm.comments.trim()} onClick={() => promote.mutate()}>Promote</Button>
                </Stack>
              </SimpleGrid>

              <div className="forge-grid-panel">
                <ScrollArea>
                  <Table miw={760} verticalSpacing="sm">
                    <Table.Thead><Table.Tr><Table.Th>Version</Table.Th><Table.Th>Status</Table.Th><Table.Th>Artifact hash</Table.Th><Table.Th>Retention</Table.Th><Table.Th>Created</Table.Th></Table.Tr></Table.Thead>
                    <Table.Tbody>
                      {versions.map((version) => <Table.Tr key={str(version.id)}><Table.Td>v{str(version.versionNumber)}</Table.Td><Table.Td><Badge variant="light">{str(version.status)}</Badge></Table.Td><Table.Td><Text ff="monospace" size="xs">{str(version.artifactHash)}</Text></Table.Td><Table.Td>{str(version.retentionPolicy, '-')}<Text size="xs" c="dimmed">until {formatDate(str(version.retentionUntil) || null)}</Text></Table.Td><Table.Td>{formatDate(str(version.createdAt) || null)}</Table.Td></Table.Tr>)}
                      {!versions.length ? <Table.Tr><Table.Td colSpan={5}><Text c="dimmed">No immutable versions yet.</Text></Table.Td></Table.Tr> : null}
                    </Table.Tbody>
                  </Table>
                </ScrollArea>
              </div>

              {promotions.length ? (
                <Stack gap={5}>
                  <Text fw={800} size="sm">Promotion history</Text>
                  {promotions.map((promotion) => <div className="be-line-row" key={str(promotion.id)}><span className="be-dot" style={{ background: statusDot(str(promotion.status)) }} aria-hidden /><div className="be-line-body"><Text size="sm" fw={600}>{str(promotion.fromEnvironment, '-')} to {str(promotion.toEnvironment, '-')}</Text><Text size="xs" c="dimmed">{str(promotion.status)} - version #{str(promotion.versionId, '-')} - approval #{str(promotion.approvedRequestId, '-')}</Text></div></div>)}
                </Stack>
              ) : null}
            </>
          )}
        </Stack>
      </Modal>
    </>
  );
}
