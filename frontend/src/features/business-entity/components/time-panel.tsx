'use client';

import { useState } from 'react';
import { Badge, Button, Group, NumberInput, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { NameInput } from '@/components/name-input';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { MaskingPolicy } from '@/lib/types';
import type { BeReservation, BeSnapshot, BusinessEntityDetail } from '../types';
import { formatDate, numberOrNull, statusDot } from '../utils';

/** Snapshots (bookmark + rollback) and reservations (exclusive entity locks with TTL). */
export function TimePanel({
  detail,
  snapshots,
  reservations,
  policies
}: {
  detail: BusinessEntityDetail;
  snapshots: BeSnapshot[];
  reservations: BeReservation[];
  policies: MaskingPolicy[];
}) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const entityId = detail.entity.id!;
  const [snapForm, setSnapForm] = useState({ name: '', mode: 'EVIDENCE_ONLY', retention: '', criteria: '', note: '' });
  const [resForm, setResForm] = useState({ name: '', count: '1', ttl: '24', environment: '', criteria: '', purpose: '', capsule: '' });

  const invalidate = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.businessEntity.snapshots(entityId) });
    await queryClient.invalidateQueries({ queryKey: keys.businessEntity.reservations(entityId) });
    await queryClient.invalidateQueries({ queryKey: keys.businessEntity.capsules(entityId) });
  };

  const createSnapshot = useMutation({
    mutationFn: () =>
      apiPost(`/api/business-entities/${entityId}/snapshots`, {
        name: snapForm.name.trim() || `${detail.entity.name} baseline`,
        mode: snapForm.mode,
        retentionDays: numberOrNull(snapForm.retention),
        criteria: snapForm.criteria.trim() || null,
        note: snapForm.note.trim() || null
      }),
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Snapshot created', message: snapForm.name.trim() || 'Baseline captured.' });
      setSnapForm({ name: '', mode: 'EVIDENCE_ONLY', retention: '', criteria: '', note: '' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create snapshot', message: error.message })
  });

  const rollback = async (snapshot: BeSnapshot) => {
    const ok = await confirm({
      title: 'Roll back to snapshot',
      danger: true,
      okText: 'Roll back',
      message: `Roll the entity back to "${snapshot.name || `snapshot #${snapshot.id}`}"? Physical snapshots restore the linked virtual databases; evidence-only snapshots record the rollback decision.`
    });
    if (!ok) return;
    try {
      await apiPost(`/api/business-entities/snapshots/${snapshot.id}/rollback`, { confirm: true });
      notifications.show({ color: 'green', title: 'Rollback executed', message: snapshot.name || `#${snapshot.id}` });
      await invalidate();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Rollback failed', message: (error as Error).message });
    }
  };

  const reserve = useMutation({
    mutationFn: () =>
      apiPost(`/api/business-entities/${entityId}/reservations`, {
        name: resForm.name.trim() || null,
        count: numberOrNull(resForm.count) || 1,
        ttlHours: numberOrNull(resForm.ttl) || 24,
        environment: resForm.environment.trim() || null,
        criteria: resForm.criteria.trim() || null,
        purpose: resForm.purpose.trim() || null,
        materializeCapsules: resForm.capsule !== '',
        capsulePolicyId: resForm.capsule && resForm.capsule !== '0' ? Number(resForm.capsule) : null
      }),
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Entity reserved', message: 'Keys locked for your team.' });
      setResForm({ name: '', count: '1', ttl: '24', environment: '', criteria: '', purpose: '', capsule: '' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not reserve', message: error.message })
  });

  const release = async (reservation: BeReservation) => {
    try {
      await apiPost(`/api/business-entities/reservations/${reservation.id}/release`, {});
      notifications.show({ color: 'green', title: 'Reservation released', message: reservation.name || `#${reservation.id}` });
      await invalidate();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not release', message: (error as Error).message });
    }
  };

  return (
    <Stack gap="lg">
      {confirmElement}

      <div>
        <Text fw={650} size="sm">
          Snapshots
        </Text>
        <Text size="xs" c="dimmed" mb="xs">
          Bookmark the entity at a point in time. Physical mode links to Virtualization for real rollback.
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 3, lg: 6 }} mb="sm">
          <NameInput size="xs" label="Name" placeholder={`${detail.entity.name} baseline`} value={snapForm.name} onChange={(value) => setSnapForm({ ...snapForm, name: value })} />
          <Select
            size="xs"
            label="Mode"
            data={[
              { value: 'EVIDENCE_ONLY', label: 'Evidence only' },
              { value: 'PHYSICAL_SNAPSHOT', label: 'Physical snapshot' }
            ]}
            value={snapForm.mode}
            onChange={(value) => setSnapForm({ ...snapForm, mode: value || 'EVIDENCE_ONLY' })}
          />
          <NumberInput size="xs" label="Retention days" min={0} placeholder="optional" value={snapForm.retention === '' ? '' : Number(snapForm.retention)} onChange={(value) => setSnapForm({ ...snapForm, retention: value === '' || value === null ? '' : String(value) })} />
          <TextInput size="xs" label="Criteria" placeholder="status = 'ACTIVE'" value={snapForm.criteria} onChange={(e) => setSnapForm({ ...snapForm, criteria: e.currentTarget.value })} />
          <TextInput size="xs" label="Note" placeholder="Why this point matters" value={snapForm.note} onChange={(e) => setSnapForm({ ...snapForm, note: e.currentTarget.value })} />
          <Button size="xs" mt={22} loading={createSnapshot.isPending} onClick={() => createSnapshot.mutate()}>
            Create snapshot
          </Button>
        </SimpleGrid>
        {snapshots.length ? (
          snapshots.map((snapshot) => (
            <div className="be-line-row" key={snapshot.id}>
              <span className="be-dot" style={{ background: statusDot(snapshot.status) }} aria-hidden />
              <div className="be-line-body">
                <Text size="sm" fw={600}>
                  {snapshot.name || `Snapshot #${snapshot.id}`}
                </Text>
                <Text size="xs" c="dimmed">
                  {snapshot.mode || 'EVIDENCE_ONLY'} · {formatDate(snapshot.createdAt)}
                  {snapshot.note ? ` · ${snapshot.note}` : ''}
                </Text>
              </div>
              <Button size="compact-xs" variant="subtle" onClick={() => void rollback(snapshot)}>
                Roll back
              </Button>
            </div>
          ))
        ) : (
          <Text size="sm" c="dimmed">
            No snapshots yet.
          </Text>
        )}
      </div>

      <div>
        <Text fw={650} size="sm">
          Reservations
        </Text>
        <Text size="xs" c="dimmed" mb="xs">
          Lock whole business objects for one team with a TTL — two teams never collide on the same test customer.
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 3, lg: 7 }} mb="sm">
          <NameInput size="xs" label="Name" placeholder="QA cycle" value={resForm.name} onChange={(value) => setResForm({ ...resForm, name: value })} />
          <NumberInput size="xs" label="Count" min={1} value={resForm.count === '' ? '' : Number(resForm.count)} onChange={(value) => setResForm({ ...resForm, count: value === '' || value === null ? '' : String(value) })} />
          <NumberInput size="xs" label="TTL hours" min={1} value={resForm.ttl === '' ? '' : Number(resForm.ttl)} onChange={(value) => setResForm({ ...resForm, ttl: value === '' || value === null ? '' : String(value) })} />
          <TextInput size="xs" label="Environment" placeholder="UAT" value={resForm.environment} onChange={(e) => setResForm({ ...resForm, environment: e.currentTarget.value })} />
          <TextInput size="xs" label="Criteria" placeholder="region = 'US'" value={resForm.criteria} onChange={(e) => setResForm({ ...resForm, criteria: e.currentTarget.value })} />
          <Select
            size="xs"
            label="Micro-DB capsule"
            data={[{ value: '', label: "Don't materialize" }]
              .concat(policies.map((policy) => ({ value: String(policy.id), label: `Masked (${policy.name})` })))
              .concat([{ value: '0', label: 'Row-count pointer only' }])}
            value={resForm.capsule}
            onChange={(value) => setResForm({ ...resForm, capsule: value || '' })}
          />
          <Button size="xs" mt={22} loading={reserve.isPending} onClick={() => reserve.mutate()}>
            Reserve
          </Button>
        </SimpleGrid>
        {reservations.length ? (
          reservations.map((reservation) => (
            <div className="be-line-row" key={reservation.id}>
              <span className="be-dot" style={{ background: statusDot(reservation.status) }} aria-hidden />
              <div className="be-line-body">
                <Group gap={6} wrap="nowrap">
                  <Text size="sm" fw={600}>
                    {reservation.name || `Reservation #${reservation.id}`}
                  </Text>
                  <Badge size="xs" variant="light">
                    {reservation.status}
                  </Badge>
                </Group>
                <Text size="xs" c="dimmed">
                  {reservation.reservedBy} · {reservation.requestedCount || 1} key(s)
                  {reservation.environment ? ` · ${reservation.environment}` : ''} · expires {formatDate(reservation.expiresAt)}
                </Text>
              </div>
              {reservation.status === 'ACTIVE' ? (
                <Button size="compact-xs" variant="subtle" color="red" onClick={() => void release(reservation)}>
                  Release
                </Button>
              ) : null}
            </div>
          ))
        ) : (
          <Text size="sm" c="dimmed">
            No reservations yet.
          </Text>
        )}
      </div>
    </Stack>
  );
}
