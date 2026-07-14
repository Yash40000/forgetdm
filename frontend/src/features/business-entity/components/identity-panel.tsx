'use client';

import { useState } from 'react';
import { Badge, Button, Group, NumberInput, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { LooseMap } from '../hooks';
import type { BusinessEntityDetail } from '../types';
import { statusDot, str, technicalInputProps } from '../utils';

/**
 * Identity crosswalk: one canonical key (CUST-10025) mapped to every application's own
 * identifier — resolve from any system key back to the whole entity.
 */
export function IdentityPanel({ detail, identities }: { detail: BusinessEntityDetail; identities: LooseMap[] }) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const entityId = detail.entity.id!;
  const members = detail.members || [];
  const [form, setForm] = useState({ canonicalKey: '', identityType: 'CUSTOMER', confidence: '1' });
  const [links, setLinks] = useState<Record<number, string>>({});
  const [resolveForm, setResolveForm] = useState({ memberId: '', externalId: '' });
  const [resolved, setResolved] = useState<LooseMap | null>(null);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: keys.businessEntity.identities(entityId) });

  const saveIdentity = useMutation({
    mutationFn: () => {
      if (!form.canonicalKey.trim()) throw new Error('Enter the canonical key, e.g. CUST-10025.');
      return apiPost<LooseMap>(`/api/business-entities/${entityId}/identities`, {
        canonicalKey: form.canonicalKey.trim(),
        identityType: form.identityType,
        status: 'ACTIVE',
        confidence: Number(form.confidence) || 1,
        links: members
          .filter((member) => member.id && (links[member.id] || '').trim())
          .map((member) => ({
            memberId: member.id,
            systemName: member.systemName || null,
            dataSourceId: member.dataSourceId || null,
            tableName: member.tableName || null,
            logicalRole: member.logicalRole || null,
            keyColumns: member.keyColumns || null,
            externalId: links[member.id!].trim()
          }))
      });
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Crosswalk saved', message: form.canonicalKey.trim() });
      setForm({ canonicalKey: '', identityType: form.identityType, confidence: '1' });
      setLinks({});
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not save crosswalk', message: error.message })
  });

  const resolve = useMutation({
    mutationFn: () => {
      if (!resolveForm.externalId.trim()) throw new Error('Enter the external id to resolve.');
      return apiPost<LooseMap>(`/api/business-entities/${entityId}/identities/resolve`, {
        memberId: resolveForm.memberId ? Number(resolveForm.memberId) : null,
        externalId: resolveForm.externalId.trim()
      });
    },
    onSuccess: (result) => setResolved(result),
    onError: (error) => {
      setResolved(null);
      notifications.show({ color: 'red', title: 'Resolve failed', message: error.message });
    }
  });

  const removeIdentity = async (identity: LooseMap) => {
    const ok = await confirm({
      title: 'Delete crosswalk',
      danger: true,
      okText: 'Delete',
      message: `Delete the identity crosswalk for ${str(identity.canonicalKey)} and all its system links?`
    });
    if (!ok) return;
    try {
      await apiFetch(`/api/business-entities/identities/${identity.id}`, { method: 'DELETE' });
      await invalidate();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete crosswalk', message: (error as Error).message });
    }
  };

  const resolvedCrosswalk = resolved?.crosswalk as LooseMap | undefined;

  return (
    <Stack gap="lg">
      {confirmElement}

      <div>
        <Text fw={650} size="sm">
          Create or update a crosswalk
        </Text>
        <Text size="xs" c="dimmed" mb="xs">
          The same customer is one id in DB2, another in the CRM. Store the mapping once; any system key resolves back to the entity.
        </Text>
        <SimpleGrid cols={{ base: 1, sm: 4 }} mb="xs">
          <TextInput {...technicalInputProps} size="xs" label="Canonical key" placeholder="CUST-10025" value={form.canonicalKey} onChange={(e) => setForm({ ...form, canonicalKey: e.currentTarget.value })} />
          <Select size="xs" label="Type" data={['CUSTOMER', 'ACCOUNT', 'PARTY', 'HOUSEHOLD', 'BUSINESS_ENTITY']} value={form.identityType} onChange={(value) => setForm({ ...form, identityType: value || 'CUSTOMER' })} />
          <NumberInput size="xs" label="Confidence" min={0} max={1} step={0.01} value={form.confidence === '' ? '' : Number(form.confidence)} onChange={(value) => setForm({ ...form, confidence: value === '' || value === null ? '' : String(value) })} />
          <Button size="xs" mt={22} loading={saveIdentity.isPending} onClick={() => saveIdentity.mutate()}>
            Save crosswalk
          </Button>
        </SimpleGrid>
        {members.length ? (
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
            {members.map((member) => (
              <TextInput
                {...technicalInputProps}
                key={member.id}
                size="xs"
                label={`${member.systemName || member.logicalRole || member.tableName || 'system'}`}
                description={member.tableName || undefined}
                placeholder={member.keyColumns ? `${member.keyColumns.split(',')[0].trim()}=10025` : 'external id'}
                value={member.id ? links[member.id] || '' : ''}
                onChange={(e) => member.id && setLinks({ ...links, [member.id]: e.currentTarget.value })}
              />
            ))}
          </SimpleGrid>
        ) : (
          <Text size="sm" c="dimmed">
            Add member tables on the Model tab first — each member gets a link field here.
          </Text>
        )}
      </div>

      <div>
        <Text fw={650} size="sm">
          Resolve any system key
        </Text>
        <Group gap="xs" align="flex-end" mb="xs">
          <Select
            size="xs"
            label="Member / system"
            placeholder="Any system"
            clearable
            data={members.filter((member) => member.id).map((member) => ({ value: String(member.id), label: member.systemName || member.tableName || `#${member.id}` }))}
            value={resolveForm.memberId}
            onChange={(value) => setResolveForm({ ...resolveForm, memberId: value || '' })}
            w={200}
          />
          <TextInput {...technicalInputProps} size="xs" label="External id" placeholder="customer_id=10025 or 10025" value={resolveForm.externalId} onChange={(e) => setResolveForm({ ...resolveForm, externalId: e.currentTarget.value })} w={240} />
          <Button size="xs" variant="light" loading={resolve.isPending} onClick={() => resolve.mutate()}>
            Resolve
          </Button>
        </Group>
        {resolved ? (
          resolved.matched ? (
            <div className="be-line-row">
              <span className="be-dot" style={{ background: statusDot('ACTIVE') }} aria-hidden />
              <div className="be-line-body">
                <Text size="sm" fw={600}>
                  Matched {str(resolvedCrosswalk?.canonicalKey, '-')}
                </Text>
                <Text size="xs" c="dimmed">
                  {((resolvedCrosswalk?.links as unknown[] | undefined)?.length ?? 0)} linked system identifier(s)
                </Text>
              </div>
            </div>
          ) : (
            <Text size="sm" c="dimmed">
              {str(resolved.message, 'No match found.')}
            </Text>
          )
        ) : null}
      </div>

      <div>
        <Text fw={650} size="sm" mb={6}>
          Stored crosswalks
        </Text>
        {identities.length ? (
          identities.map((identity) => {
            const identityLinks = Array.isArray(identity.links) ? (identity.links as LooseMap[]) : [];
            return (
              <div className="be-line-row" key={str(identity.id)}>
                <span className="be-dot" style={{ background: statusDot(str(identity.status, 'ACTIVE')) }} aria-hidden />
                <div className="be-line-body">
                  <Group gap={6} wrap="nowrap">
                    <Text size="sm" fw={600}>
                      {str(identity.canonicalKey)}
                    </Text>
                    <Badge size="xs" variant="light">
                      {str(identity.identityType, 'IDENTITY')}
                    </Badge>
                  </Group>
                  <Text size="xs" c="dimmed">
                    {identityLinks.map((link) => `${str(link.systemName || link.tableName, 'system')}: ${str(link.externalId)}`).join(' · ') || 'No system links'}
                  </Text>
                </div>
                <Button size="compact-xs" variant="subtle" color="red" onClick={() => void removeIdentity(identity)}>
                  Delete
                </Button>
              </div>
            );
          })
        ) : (
          <Text size="sm" c="dimmed">
            No crosswalks yet.
          </Text>
        )}
      </div>
    </Stack>
  );
}
