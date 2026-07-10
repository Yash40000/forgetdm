'use client';

import { useEffect, useMemo, useState } from 'react';
import { Alert, Badge, Button, Group, Loader, Paper, Select, SimpleGrid, Stack, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconLink, IconPlus } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { useConfirm } from '@/components/confirm';
import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { CustomPk, DataSetDefinition, RelationshipInfo, TableProfile, TraversalRule, UserDefinedRelationship } from '@/lib/types';
import { useCustomPks, useRelationships, useUserRels } from '../hooks';
import { equalsIgnoreCase, isProfileIncluded, qModeValue, technicalInputProps } from '../utils';

const DIRECTION_OPTIONS = [
  { value: '', label: 'Not set (use Q1/Q2 settings)' },
  { value: 'BOTH', label: 'Both (Q1 + Q2)' },
  { value: 'Q1_ONLY', label: 'Q1 only — parent pull' },
  { value: 'Q2_ONLY', label: 'Q2 only — child cascade' },
  { value: 'NONE', label: 'None — never traverse' }
];

/**
 * Relationship studio: every FK edge the closure will walk (DB catalog + user-defined),
 * per-edge traversal direction rules (these OVERRIDE per-table Q1/Q2), custom
 * relationships for FKs the database doesn't declare, and custom PKs for keyless tables.
 */
export function RelationshipsPanel({ blueprint, profiles }: { blueprint: DataSetDefinition; profiles: TableProfile[] }) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const relationshipsQuery = useRelationships(blueprint.id);
  const userRelsQuery = useUserRels(blueprint.id);
  const customPksQuery = useCustomPks(blueprint.id);

  /* per-edge direction draft */
  const [directions, setDirections] = useState<Record<string, string>>({});
  const [directionsDirty, setDirectionsDirty] = useState(false);
  const edges = useMemo(() => relationshipsQuery.data || [], [relationshipsQuery.data]);
  useEffect(() => {
    if (directionsDirty) return;
    const next: Record<string, string> = {};
    for (const edge of edges) next[edgeKey(edge)] = edge.traverseDirection || '';
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setDirections(next);
  }, [edges, directionsDirty]);

  const saveDirections = useMutation({
    mutationFn: () => {
      const rules: TraversalRule[] = edges
        .filter((edge) => (directions[edgeKey(edge)] || '') !== '')
        .map((edge) => ({
          datasetId: blueprint.id,
          parentTable: edge.parentTable,
          childTable: edge.childTable,
          relSource: edge.source || 'DB',
          relRefId: edge.relRefId || null,
          traverseDirection: directions[edgeKey(edge)],
          priority: edge.priority || 0,
          note: edge.traversalNote || null
        }));
      return apiPut<TraversalRule[]>(`/api/datasets/${blueprint.id}/traversal-rules`, rules);
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Traversal rules saved', message: 'Per-relationship directions updated.' });
      setDirectionsDirty(false);
      await queryClient.invalidateQueries({ queryKey: keys.datascope.relationships(blueprint.id) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not save traversal rules', message: error.message })
  });

  /* custom relationship form */
  const [relForm, setRelForm] = useState({ relName: '', parentTable: '', parentColumns: '', childTable: '', childColumns: '', note: '' });
  const createRel = useMutation({
    mutationFn: () => {
      if (!relForm.parentTable.trim() || !relForm.parentColumns.trim() || !relForm.childTable.trim() || !relForm.childColumns.trim()) {
        throw new Error('Parent/child table and columns are required.');
      }
      return apiPost<UserDefinedRelationship>(`/api/datasets/${blueprint.id}/user-rels`, {
        datasetId: blueprint.id,
        relName: relForm.relName.trim() || null,
        parentTable: relForm.parentTable.trim(),
        parentColumns: relForm.parentColumns.trim(),
        childTable: relForm.childTable.trim(),
        childColumns: relForm.childColumns.trim(),
        note: relForm.note.trim() || null
      });
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Relationship added', message: `${relForm.childTable} → ${relForm.parentTable}` });
      setRelForm({ relName: '', parentTable: '', parentColumns: '', childTable: '', childColumns: '', note: '' });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.userRels(blueprint.id) });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.relationships(blueprint.id) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not add relationship', message: error.message })
  });

  const deleteRel = async (rel: UserDefinedRelationship) => {
    const ok = await confirm({
      title: 'Delete custom relationship',
      message: `Delete ${rel.childTable}(${rel.childColumns}) → ${rel.parentTable}(${rel.parentColumns})? The closure stops walking this edge.`,
      danger: true,
      okText: 'Delete'
    });
    if (!ok) return;
    try {
      await apiFetch(`/api/datasets/user-rels/${rel.id}`, { method: 'DELETE' });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.userRels(blueprint.id) });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.relationships(blueprint.id) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete relationship', message: (error as Error).message });
    }
  };

  /* custom PK form */
  const [pkForm, setPkForm] = useState({ tableName: '', columnNames: '', note: '' });
  const createPk = useMutation({
    mutationFn: () => {
      if (!pkForm.tableName.trim() || !pkForm.columnNames.trim()) throw new Error('Table and key column(s) are required.');
      return apiPost<CustomPk>(`/api/datasets/${blueprint.id}/custom-pks`, {
        datasetId: blueprint.id,
        tableName: pkForm.tableName.trim(),
        columnNames: pkForm.columnNames.trim(),
        note: pkForm.note.trim() || null
      });
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Custom key added', message: pkForm.tableName });
      setPkForm({ tableName: '', columnNames: '', note: '' });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.customPks(blueprint.id) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not add custom key', message: error.message })
  });

  const deletePk = async (pk: CustomPk) => {
    const ok = await confirm({
      title: 'Delete custom key',
      message: `Remove the custom key on ${pk.tableName} (${pk.columnNames})?`,
      danger: true,
      okText: 'Delete'
    });
    if (!ok) return;
    try {
      await apiFetch(`/api/datasets/custom-pks/${pk.id}`, { method: 'DELETE' });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.customPks(blueprint.id) });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete custom key', message: (error as Error).message });
    }
  };

  return (
    <Stack gap="md">
      {confirmElement}

      <Paper className="forge-card" p="md">
        <Stack gap="sm">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={800}>Relationship traversal map</Text>
              <Text size="sm" c="dimmed">
                Every edge the subset closure can walk. A per-edge direction OVERRIDES the Q1/Q2 settings from the table map — use
                it to cut one noisy relationship without touching the whole table.
              </Text>
            </div>
            <Group gap="xs">
              {directionsDirty ? (
                <Badge color="yellow" variant="light">
                  unsaved changes
                </Badge>
              ) : null}
              <Badge variant="light">{edges.length} edge(s)</Badge>
              <Button loading={saveDirections.isPending} disabled={!directionsDirty} onClick={() => saveDirections.mutate()}>
                Save directions
              </Button>
            </Group>
          </Group>
          {relationshipsQuery.isFetching ? (
            <Group>
              <Loader size="sm" />
              <Text c="dimmed">Loading relationships...</Text>
            </Group>
          ) : !edges.length ? (
            <Alert color="blue" variant="light">
              No relationships discovered yet. Add tables to the profile (or define a custom relationship below).
            </Alert>
          ) : (
            <div className="forge-grid-panel">
              <table className="forge-table">
                <thead>
                  <tr>
                    <th>Child (FK side)</th>
                    <th>Parent (PK side)</th>
                    <th>Source</th>
                    <th style={{ minWidth: 230 }}>Traversal direction</th>
                  </tr>
                </thead>
                <tbody>
                  {edges.map((edge) => (
                    <tr key={edgeKey(edge)}>
                      <td>
                        <Text fw={700} size="sm">
                          {edge.childTable}
                        </Text>
                        <Text size="xs" c="dimmed">
                          {(edge.childColumns || []).join(', ')}
                        </Text>
                      </td>
                      <td>
                        <Text fw={700} size="sm">
                          {edge.parentTable}
                        </Text>
                        <Text size="xs" c="dimmed">
                          {(edge.parentColumns || []).join(', ')}
                        </Text>
                      </td>
                      <td>
                        <Badge variant="light" color={edge.source === 'USER' ? 'blue' : 'gray'}>
                          {edge.source === 'USER' ? edge.relName || 'custom' : 'DB catalog'}
                        </Badge>
                      </td>
                      <td>
                        <Select
                          data={DIRECTION_OPTIONS}
                          value={directions[edgeKey(edge)] ?? ''}
                          onChange={(value) => {
                            setDirectionsDirty(true);
                            setDirections((current) => ({ ...current, [edgeKey(edge)]: value || '' }));
                          }}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Stack>
      </Paper>

      <TraversalPathCard blueprint={blueprint} profiles={profiles} edges={edges} directions={directions} />

      <SimpleGrid cols={{ base: 1, lg: 2 }}>
        <Paper className="forge-card" p="md">
          <Stack gap="sm">
            <Group justify="space-between">
              <div>
                <Text fw={800}>Custom relationships</Text>
                <Text size="sm" c="dimmed">
                  Declare FK edges the database does not expose: legacy schemas, cross-schema links, soft keys.
                </Text>
              </div>
              <IconLink size={18} />
            </Group>
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <TextInput
                label="Name"
                placeholder="orders-to-customers"
                value={relForm.relName}
                onChange={(e) => setRelForm({ ...relForm, relName: e.currentTarget.value })}
              />
              <div />
              <TextInput
                {...technicalInputProps}
                label="Child table (FK side)"
                placeholder="orders"
                value={relForm.childTable}
                onChange={(e) => setRelForm({ ...relForm, childTable: e.currentTarget.value })}
              />
              <TextInput
                {...technicalInputProps}
                label="Child column(s)"
                placeholder="customer_id"
                value={relForm.childColumns}
                onChange={(e) => setRelForm({ ...relForm, childColumns: e.currentTarget.value })}
              />
              <TextInput
                {...technicalInputProps}
                label="Parent table (PK side)"
                placeholder="customers"
                value={relForm.parentTable}
                onChange={(e) => setRelForm({ ...relForm, parentTable: e.currentTarget.value })}
              />
              <TextInput
                {...technicalInputProps}
                label="Parent column(s)"
                placeholder="id"
                value={relForm.parentColumns}
                onChange={(e) => setRelForm({ ...relForm, parentColumns: e.currentTarget.value })}
              />
            </SimpleGrid>
            <Group justify="space-between" align="flex-end">
              <TextInput
                label="Note"
                placeholder="optional"
                style={{ flex: 1 }}
                value={relForm.note}
                onChange={(e) => setRelForm({ ...relForm, note: e.currentTarget.value })}
              />
              <Button leftSection={<IconPlus size={16} />} loading={createRel.isPending} onClick={() => createRel.mutate()}>
                Add relationship
              </Button>
            </Group>
            {(userRelsQuery.data || []).map((rel) => (
              <Group key={rel.id} justify="space-between" wrap="nowrap" className="forge-grid-panel" p={8}>
                <div>
                  <Text size="sm" fw={700}>
                    {rel.relName || `${rel.childTable} → ${rel.parentTable}`}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {rel.childTable}({rel.childColumns}) → {rel.parentTable}({rel.parentColumns})
                    {rel.note ? ` · ${rel.note}` : ''}
                  </Text>
                </div>
                <Button size="xs" variant="subtle" color="red" onClick={() => void deleteRel(rel)}>
                  Delete
                </Button>
              </Group>
            ))}
          </Stack>
        </Paper>

        <Paper className="forge-card" p="md">
          <Stack gap="sm">
            <div>
              <Text fw={800}>Custom keys (keyless tables)</Text>
              <Text size="sm" c="dimmed">
                Subsetting needs a key per table. Give tables without a real primary key a logical one here.
              </Text>
            </div>
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <TextInput
                {...technicalInputProps}
                label="Table"
                placeholder="audit_log"
                value={pkForm.tableName}
                onChange={(e) => setPkForm({ ...pkForm, tableName: e.currentTarget.value })}
              />
              <TextInput
                {...technicalInputProps}
                label="Key column(s)"
                placeholder="id or col1,col2"
                value={pkForm.columnNames}
                onChange={(e) => setPkForm({ ...pkForm, columnNames: e.currentTarget.value })}
              />
            </SimpleGrid>
            <Group justify="space-between" align="flex-end">
              <TextInput
                label="Note"
                placeholder="optional"
                style={{ flex: 1 }}
                value={pkForm.note}
                onChange={(e) => setPkForm({ ...pkForm, note: e.currentTarget.value })}
              />
              <Button leftSection={<IconPlus size={16} />} loading={createPk.isPending} onClick={() => createPk.mutate()}>
                Add key
              </Button>
            </Group>
            {(customPksQuery.data || []).map((pk) => (
              <Group key={pk.id} justify="space-between" wrap="nowrap" className="forge-grid-panel" p={8}>
                <div>
                  <Text size="sm" fw={700}>
                    {pk.tableName}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {pk.columnNames}
                    {pk.note ? ` · ${pk.note}` : ''}
                  </Text>
                </div>
                <Button size="xs" variant="subtle" color="red" onClick={() => void deletePk(pk)}>
                  Delete
                </Button>
              </Group>
            ))}
          </Stack>
        </Paper>
      </SimpleGrid>
    </Stack>
  );
}

function edgeKey(edge: RelationshipInfo) {
  return `${edge.source}|${edge.parentTable}|${(edge.parentColumns || []).join(',')}|${edge.childTable}|${(edge.childColumns || []).join(',')}`.toLowerCase();
}

/* ---------- traversal path (live preview of the effective walk) ---------- */

type PathPull = { table: string; cols: string; defer: boolean; viaEdgeRule: boolean };
type PathInfo = { q1: PathPull[]; q2: PathPull[]; skipped: string[] };
type QState = 'on' | 'off' | 'defer';

/**
 * Per-table map of what the closure will actually do, mirroring the engine's precedence:
 * per-edge direction rule (incl. unsaved draft) → per-table Q1/Q2 mode (YES/NO/DEFER) → blueprint global.
 */
function TraversalPathCard({
  blueprint,
  profiles,
  edges,
  directions
}: {
  blueprint: DataSetDefinition;
  profiles: TableProfile[];
  edges: RelationshipInfo[];
  directions: Record<string, string>;
}) {
  const included = profiles.filter(isProfileIncluded);
  const includedNames = new Set(included.map((p) => p.tableName.toLowerCase()));
  const profileByName = new Map(profiles.map((p) => [p.tableName.toLowerCase(), p]));
  const globalQ1 = blueprint.globalQ1 !== false;
  const globalQ2 = blueprint.globalQ2 !== false;

  const tableModeState = (table: string, which: 'q1' | 'q2'): QState => {
    const profile = profileByName.get(table.toLowerCase());
    const mode = which === 'q1' ? qModeValue(profile?.q1Mode, profile?.q1Override) : qModeValue(profile?.q2Mode, profile?.q2Override);
    if (mode === 'yes') return 'on';
    if (mode === 'no') return 'off';
    if (mode === 'defer') return 'defer';
    return (which === 'q1' ? globalQ1 : globalQ2) ? 'on' : 'off';
  };

  const map: Record<string, PathInfo> = {};
  const infoFor = (table: string): PathInfo => (map[table.toLowerCase()] ??= { q1: [], q2: [], skipped: [] });

  for (const edge of edges) {
    const dir = directions[edgeKey(edge)] || '';
    const cols = (edge.parentColumns || []).map((pc, i) => `${pc}=${(edge.childColumns || [])[i] ?? '?'}`).join(', ');
    const parentIncluded = includedNames.has(edge.parentTable.toLowerCase());
    const childIncluded = includedNames.has(edge.childTable.toLowerCase());
    const childProfile = profileByName.get(edge.childTable.toLowerCase());
    const childIndependent = (childProfile?.referentialStrategy || childProfile?.strategy) === 'INDEPENDENT';

    // Q1: this child pulls its parent rows
    if (childIncluded) {
      const state: QState = dir ? (dir === 'BOTH' || dir === 'Q1_ONLY' ? 'on' : 'off') : tableModeState(edge.childTable, 'q1');
      if (!parentIncluded) infoFor(edge.childTable).skipped.push(`↑ parent ${edge.parentTable} is not included in the profile`);
      else if (state === 'off') infoFor(edge.childTable).skipped.push(`↑ skip parent ${edge.parentTable}${dir ? ' (edge rule)' : ''}`);
      else infoFor(edge.childTable).q1.push({ table: edge.parentTable, cols, defer: state === 'defer', viaEdgeRule: !!dir });
    }

    // Q2: this parent cascades to its child rows
    if (parentIncluded) {
      const state: QState = dir ? (dir === 'BOTH' || dir === 'Q2_ONLY' ? 'on' : 'off') : tableModeState(edge.parentTable, 'q2');
      if (!childIncluded) infoFor(edge.parentTable).skipped.push(`↓ child ${edge.childTable} is not included in the profile`);
      else if (childIndependent) infoFor(edge.parentTable).skipped.push(`↓ ${edge.childTable} is INDEPENDENT — seeds itself from its own filter`);
      else if (state === 'off') infoFor(edge.parentTable).skipped.push(`↓ skip child ${edge.childTable}${dir ? ' (edge rule)' : ''}`);
      else infoFor(edge.parentTable).q2.push({ table: edge.childTable, cols, defer: state === 'defer', viaEdgeRule: !!dir });
    }
  }

  if (!included.length) {
    return (
      <Paper className="forge-card" p="md">
        <Text fw={800}>Traversal path</Text>
        <Alert color="blue" variant="light" mt="xs">
          Include tables in the profile first — the path preview shows how the closure walks from the driver.
        </Alert>
      </Paper>
    );
  }

  const driverTable = blueprint.driverTable || '';
  const ordered = [
    ...included.filter((p) => equalsIgnoreCase(p.tableName, driverTable)),
    ...included.filter((p) => !equalsIgnoreCase(p.tableName, driverTable))
  ];

  return (
    <Paper className="forge-card" p="md">
      <Stack gap="sm">
        <div>
          <Text fw={800}>Traversal path</Text>
          <Text size="sm" c="dimmed">
            The effective walk, per table — combining edge rules above (including unsaved changes), per-table Q1/Q2 modes, and the
            blueprint defaults. ↑ pulls parents (Q1), ↓ cascades to children (Q2).
          </Text>
        </div>
        <SimpleGrid cols={{ base: 1, md: 2 }}>
          {ordered.map((profile) => {
            const info = map[profile.tableName.toLowerCase()] || { q1: [], q2: [], skipped: [] };
            const isDriver = equalsIgnoreCase(profile.tableName, driverTable);
            const strategy = profile.referentialStrategy || profile.strategy || 'INHERIT';
            return (
              <Paper key={profile.tableName} withBorder p="sm" radius="md">
                <Group gap={6} mb={4} wrap="wrap">
                  {isDriver ? (
                    <Badge color="green" variant="filled" size="sm">
                      DRIVER
                    </Badge>
                  ) : null}
                  {strategy !== 'INHERIT' ? (
                    <Badge variant="light" size="sm" color={strategy === 'INDEPENDENT' ? 'blue' : 'gray'}>
                      {strategy}
                    </Badge>
                  ) : null}
                  <Text fw={800} size="sm">
                    {profile.tableName}
                  </Text>
                </Group>
                {isDriver ? (
                  <Text size="xs" c="dimmed" mb={4}>
                    seed: {blueprint.driverFilter ? `WHERE ${blueprint.driverFilter}` : 'all rows'}
                    {blueprint.maxDriverRows ? ` (max ${blueprint.maxDriverRows})` : ''}
                  </Text>
                ) : null}
                {profile.filterExpr || profile.filterSql ? (
                  <Text size="xs" c="dimmed" mb={4}>
                    filter: {profile.filterExpr || profile.filterSql}
                  </Text>
                ) : null}
                {info.q1.map((pull) => (
                  <Text key={`q1-${pull.table}-${pull.cols}`} size="xs">
                    ↑ pulls parent <b>{pull.table}</b> <span style={{ opacity: 0.65 }}>via {pull.cols}</span>
                    {pull.defer ? (
                      <Badge component="span" size="xs" variant="light" color="grape" ml={4}>
                        deferred
                      </Badge>
                    ) : null}
                  </Text>
                ))}
                {info.q2.map((pull) => (
                  <Text key={`q2-${pull.table}-${pull.cols}`} size="xs">
                    ↓ cascades to <b>{pull.table}</b> <span style={{ opacity: 0.65 }}>via {pull.cols}</span>
                    {pull.defer ? (
                      <Badge component="span" size="xs" variant="light" color="grape" ml={4}>
                        deferred
                      </Badge>
                    ) : null}
                  </Text>
                ))}
                {info.skipped.map((line) => (
                  <Text key={line} size="xs" c="dimmed">
                    {line}
                  </Text>
                ))}
                {!info.q1.length && !info.q2.length && !info.skipped.length ? (
                  <Text size="xs" c="dimmed">
                    No FK relationships touch this table.
                  </Text>
                ) : null}
              </Paper>
            );
          })}
        </SimpleGrid>
      </Stack>
    </Paper>
  );
}
