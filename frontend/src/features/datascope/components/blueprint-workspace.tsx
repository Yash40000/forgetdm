'use client';

import { useState } from 'react';
import { Badge, Button, Card, Drawer, Group, Modal, Select, Stack, Text, Textarea, Title } from '@mantine/core';
import {
  IconDatabase,
  IconEdit,
  IconFolderOpen,
  IconHistory,
  IconLock,
  IconPlayerPlay,
  IconPlus,
  IconRoute,
  IconShieldCheck,
  IconTable,
  IconVersions
} from '@tabler/icons-react';
import { notifications } from '@mantine/notifications';
import { useQueryClient } from '@tanstack/react-query';

import { NameInput } from '@/components/name-input';
import { useConfirm } from '@/components/confirm';
import { StatusPill } from '@/components/status-pill';
import { apiFetch, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import type {
  ColumnOverride,
  DataSetDefinition,
  DataSource,
  DriftReport,
  MaskingPolicy,
  PiiCoverage,
  SavedDataScopeJob,
  TableProfile
} from '@/lib/types';
import {
  DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH,
  DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH,
  isProfileIncluded,
  numberOrNull,
  piiCoverageCount,
  sourceName
} from '../utils';
import { GuardrailsPanel } from './guardrails-panel';
import { RelationshipsPanel } from './relationships-panel';
import { RunPanel, type RunPanelSection } from './run-panel';
import { TableMapWorkspace } from './table-map-workspace';
import { VersionsPanel } from './versions-panel';

type BlueprintWorkspaceView = 'profiles' | 'relationships' | 'guardrails' | 'provision' | null;

export function SelectedBlueprintWorkspace({
  blueprint,
  dataSources,
  policies,
  profiles,
  overrides,
  piiCoverage,
  drift,
  savedJobs,
  isProfilesLoading,
  isGuardrailsLoading,
  onOpenLibrary,
  onOpenCreate,
  onDeleted,
  onDraftDirtyChange
}: {
  blueprint: DataSetDefinition | null;
  dataSources: DataSource[];
  policies: MaskingPolicy[];
  profiles: TableProfile[];
  overrides: ColumnOverride[];
  piiCoverage?: PiiCoverage;
  drift?: DriftReport;
  savedJobs: SavedDataScopeJob[];
  isProfilesLoading: boolean;
  isGuardrailsLoading: boolean;
  onOpenLibrary?: () => void;
  onOpenCreate?: () => void;
  onDeleted?: () => void;
  onDraftDirtyChange?: (dirty: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('datascope.manage');
  const [editOpened, setEditOpened] = useState(false);
  const [editName, setEditName] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editPolicyId, setEditPolicyId] = useState('');
  const [workspaceView, setWorkspaceView] = useState<BlueprintWorkspaceView>(null);
  const [runSection, setRunSection] = useState<RunPanelSection>('build');
  const [versionsOpened, setVersionsOpened] = useState(false);
  const [draftDirty, setDraftDirty] = useState(false);
  const [savingEdit, setSavingEdit] = useState(false);
  const [deleting, setDeleting] = useState(false);

  if (!blueprint) {
    return (
      <section className="datascope-empty-workspace">
        <span className="datascope-empty-icon"><IconDatabase size={24} /></span>
        <div>
          <Text fw={850}>Choose a blueprint to begin</Text>
          <Text c="dimmed" size="sm">Open an existing definition or create one without filling the page with setup forms.</Text>
        </div>
        <Group gap="xs">
          <Button variant="light" leftSection={<IconFolderOpen size={16} />} onClick={onOpenLibrary}>Open blueprints</Button>
          {canManage ? <Button leftSection={<IconPlus size={16} />} onClick={onOpenCreate}>New blueprint</Button> : null}
        </Group>
      </section>
    );
  }

  const includedProfiles = profiles.filter(isProfileIncluded);
  const includedCount = includedProfiles.length;
  const profileReady = includedCount > 0 && Boolean(blueprint.driverTable);
  const piiGapCount = piiCoverageCount(piiCoverage, 'unmasked');
  const driftCount = drift?.issues?.length || (drift?.missingTables?.length || 0) + (drift?.missingColumns?.length || 0) + (drift?.changedColumns?.length || 0);
  const guardrailIssueCount = piiGapCount + driftCount;
  const target = blueprint.targetDataSourceId ? sourceName(blueprint.targetDataSourceId, dataSources) : 'Not configured';
  const policy = policies.find((item) => item.id === blueprint.policyId);
  const editNameLength = editName.trim().length;
  const editNameError = editNameLength > 0 && editNameLength < DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH
    ? `Use at least ${DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH} characters.`
    : editNameLength > DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH
      ? `Use no more than ${DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH} characters.`
      : null;

  const openEdit = () => {
    if (!canManage) return;
    setEditName(blueprint.name);
    setEditDescription(blueprint.description || '');
    setEditPolicyId(blueprint.policyId ? String(blueprint.policyId) : '');
    setEditOpened(true);
  };

  const saveEdit = async () => {
    if (!canManage || savingEdit || editNameError || editNameLength < DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH) return;
    setSavingEdit(true);
    try {
      await apiPut<DataSetDefinition>(`/api/datasets/${blueprint.id}`, {
        ...blueprint,
        name: editName.trim(),
        description: editDescription.trim() || null
      });
      if ((numberOrNull(editPolicyId) || null) !== (blueprint.policyId || null)) {
        await apiPut<DataSetDefinition>(`/api/datasets/${blueprint.id}/policy`, { policyId: numberOrNull(editPolicyId) });
      }
      notifications.show({ color: 'green', title: 'Blueprint updated', message: editName.trim() });
      setEditOpened(false);
      await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprints });
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not update blueprint', message: (error as Error).message });
    } finally {
      setSavingEdit(false);
    }
  };

  const deleteBlueprint = async () => {
    if (!canManage || deleting) return;
    const ok = await confirm({
      title: 'Delete blueprint',
      danger: true,
      okText: 'Delete',
      message: `Delete "${blueprint.name}" and its table profiles, column maps, custom relationships, traversal rules, and versions? Saved jobs that reference it will fail. This cannot be undone.`
    });
    if (!ok) return;
    setDeleting(true);
    try {
      await apiFetch(`/api/datasets/${blueprint.id}`, { method: 'DELETE' });
      notifications.show({ color: 'green', title: 'Blueprint deleted', message: blueprint.name });
      await queryClient.invalidateQueries({ queryKey: keys.datascope.blueprints });
      onDeleted?.();
    } catch (error) {
      notifications.show({ color: 'red', title: 'Could not delete blueprint', message: (error as Error).message });
    } finally {
      setDeleting(false);
    }
  };

  const handleDraftDirty = (dirty: boolean) => {
    setDraftDirty(dirty);
    onDraftDirtyChange?.(dirty);
  };

  const closeWorkspace = async () => {
    if (draftDirty) {
      const discard = await confirm({
        title: 'Discard unsaved DataScope changes?',
        message: 'Closing this workspace will discard unsaved profile, map, or relationship edits.',
        okText: 'Discard changes',
        danger: true
      });
      if (!discard) return;
    }
    handleDraftDirty(false);
    setWorkspaceView(null);
  };

  const openRunWorkspace = (section: RunPanelSection) => {
    setRunSection(section);
    setWorkspaceView('provision');
  };

  return (
    <Card className="forge-card datascope-blueprint-workspace" p={0}>
      {confirmElement}
      <header className="datascope-blueprint-header">
        <Group gap="sm" wrap="nowrap" align="flex-start">
          <span className="datascope-blueprint-header-icon"><IconDatabase size={18} /></span>
          <div>
            <Group gap="xs">
              <Title order={2} size="h3">{blueprint.name}</Title>
              <StatusPill value={profileReady ? 'READY' : 'DRAFT'} />
            </Group>
            <Text c="dimmed" size="sm">{blueprint.description || 'Relational subset and provisioning definition.'}</Text>
          </div>
        </Group>
        <Group gap={6} className="datascope-blueprint-header-actions">
          <Badge variant="light">{sourceName(blueprint.dataSourceId, dataSources)}</Badge>
          {blueprint.schemaName ? <Badge variant="outline">{blueprint.schemaName}</Badge> : null}
          <Button size="xs" variant="subtle" leftSection={<IconHistory size={14} />} onClick={() => openRunWorkspace('history')}>Run history</Button>
          <Button size="xs" variant="subtle" leftSection={<IconDatabase size={14} />} onClick={() => openRunWorkspace('saved')}>Saved jobs {savedJobs.length ? `(${savedJobs.length})` : ''}</Button>
          <Button size="xs" variant="subtle" leftSection={<IconVersions size={14} />} onClick={() => setVersionsOpened(true)}>Versions</Button>
          {canManage ? <Button size="xs" variant="light" leftSection={<IconEdit size={14} />} onClick={openEdit}>Edit</Button> : null}
          {canManage ? <Button size="xs" variant="subtle" color="red" loading={deleting} onClick={() => void deleteBlueprint()}>Delete</Button> : null}
        </Group>
      </header>

      <section className="datascope-workflow" aria-labelledby="datascope-workflow-title">
        <Group justify="space-between" align="center" mb="xs">
          <div>
            <Text id="datascope-workflow-title" fw={850} size="sm">Blueprint workflow</Text>
            <Text size="xs" c="dimmed">Open only the stage you need; completed stages keep their saved backend state.</Text>
          </div>
          <Badge variant="light" color={profileReady ? (guardrailIssueCount ? 'yellow' : 'green') : 'yellow'}>
            {!profileReady ? 'Setup incomplete' : guardrailIssueCount ? `${guardrailIssueCount} guardrail issue${guardrailIssueCount === 1 ? '' : 's'}` : 'Ready to provision'}
          </Badge>
        </Group>

        <div className="datascope-workflow-grid">
          <button type="button" className="datascope-workflow-action" onClick={() => setWorkspaceView('profiles')}>
            <span className="datascope-workflow-icon" data-step="1"><IconTable size={18} /></span>
            <span className="datascope-workflow-copy"><strong>Table profile &amp; map</strong><small>{includedCount ? `${includedCount} included table${includedCount === 1 ? '' : 's'} · driver ${blueprint.driverTable || 'not selected'}` : 'Choose source tables, mappings, filters, and driver'}</small></span>
            <Badge size="xs" variant="light" color={profileReady ? 'green' : 'yellow'}>{profileReady ? 'Saved' : 'Configure'}</Badge>
            <span className="datascope-workflow-affordance"><IconEdit size={13} /> {canManage ? 'Edit' : 'View'}</span>
          </button>
          <button type="button" className="datascope-workflow-action" disabled={!includedCount} onClick={() => setWorkspaceView('relationships')}>
            <span className="datascope-workflow-icon" data-step="2"><IconRoute size={18} /></span>
            <span className="datascope-workflow-copy"><strong>Relationships</strong><small>{includedCount ? `FK traversal, custom keys · parents ${blueprint.globalQ1 === false ? 'off' : 'on'} · children ${blueprint.globalQ2 === false ? 'off' : 'on'}` : 'Add profile tables first'}</small></span>
            <Badge size="xs" variant="light" color={includedCount ? 'blue' : 'gray'}>{includedCount ? 'Review' : 'Locked'}</Badge>
            <span className="datascope-workflow-affordance">{includedCount ? <IconEdit size={13} /> : <IconLock size={13} />}{includedCount ? (canManage ? ' Edit' : ' View') : ' Locked'}</span>
          </button>
          <button type="button" className="datascope-workflow-action" disabled={!includedCount} onClick={() => setWorkspaceView('guardrails')}>
            <span className="datascope-workflow-icon" data-step="3"><IconShieldCheck size={18} /></span>
            <span className="datascope-workflow-copy"><strong>Guardrails</strong><small>{isGuardrailsLoading ? 'Checking PII coverage and schema drift' : guardrailIssueCount ? `${piiGapCount} PII gap${piiGapCount === 1 ? '' : 's'} · ${driftCount} drift issue${driftCount === 1 ? '' : 's'}` : 'PII coverage and schema drift are clear'}</small></span>
            <Badge size="xs" variant="light" color={!includedCount ? 'gray' : guardrailIssueCount ? 'yellow' : 'green'}>{!includedCount ? 'Locked' : guardrailIssueCount ? 'Review' : 'Clear'}</Badge>
            <span className="datascope-workflow-affordance">{includedCount ? <IconShieldCheck size={13} /> : <IconLock size={13} />}{includedCount ? ' Review' : ' Locked'}</span>
          </button>
          <button type="button" className="datascope-workflow-action" disabled={!profileReady} onClick={() => openRunWorkspace('build')}>
            <span className="datascope-workflow-icon" data-step="4"><IconPlayerPlay size={18} /></span>
            <span className="datascope-workflow-copy"><strong>Preview &amp; provision</strong><small>{profileReady ? `${target} · preview closure, save job, or launch` : 'Save a driver and included tables first'}</small></span>
            <Badge size="xs" variant="light" color={profileReady ? 'blue' : 'gray'}>{profileReady ? 'Ready' : 'Locked'}</Badge>
            <span className="datascope-workflow-affordance">{profileReady ? <IconPlayerPlay size={13} /> : <IconLock size={13} />}{profileReady ? ' Open' : ' Locked'}</span>
          </button>
        </div>

        <div className="datascope-workflow-summary" aria-label="Current DataScope blueprint summary">
          <div><span>Source</span><strong>{sourceName(blueprint.dataSourceId, dataSources)}</strong><small>{blueprint.schemaName || 'Default schema'}</small></div>
          <div><span>Subset driver</span><strong>{blueprint.driverTable || 'Not selected'}</strong><small>{blueprint.driverFilter || 'No row filter'}</small></div>
          <div><span>Destination</span><strong>{target}</strong><small>{blueprint.targetSchemaName || 'Target schema pending'}</small></div>
          <div><span>Protection</span><strong>{policy?.name || 'Per-table / unmasked'}</strong><small>{guardrailIssueCount ? `${guardrailIssueCount} issue${guardrailIssueCount === 1 ? '' : 's'} to review` : 'Guardrails clear'}</small></div>
        </div>
      </section>

      <Modal opened={workspaceView === 'profiles'} onClose={() => void closeWorkspace()} title="Table profile and mapping workspace" fullScreen>
        <TableMapWorkspace
          key={blueprint.id}
          blueprint={blueprint}
          rows={profiles}
          overrides={overrides}
          dataSources={dataSources}
          policies={policies}
          loading={isProfilesLoading}
          onDirtyChange={handleDraftDirty}
        />
      </Modal>

      <Modal opened={workspaceView === 'relationships'} onClose={() => void closeWorkspace()} title="Relationship traversal workspace" fullScreen>
        <RelationshipsPanel key={blueprint.id} blueprint={blueprint} profiles={profiles} onDirtyChange={handleDraftDirty} />
      </Modal>

      <Modal opened={workspaceView === 'guardrails'} onClose={() => setWorkspaceView(null)} title="DataScope guardrail review" size="90vw" centered>
        <GuardrailsPanel coverage={piiCoverage} drift={drift} loading={isGuardrailsLoading} />
      </Modal>

      <Modal opened={workspaceView === 'provision'} onClose={() => setWorkspaceView(null)} title="Preview, provision, and operate" fullScreen>
        <RunPanel
          key={`${blueprint.id}-${runSection}`}
          blueprint={blueprint}
          profiles={profiles}
          policies={policies}
          dataSources={dataSources}
          drift={drift}
          savedJobs={savedJobs}
          initialSection={runSection}
        />
      </Modal>

      <Drawer opened={versionsOpened} onClose={() => setVersionsOpened(false)} position="right" size="xl" title="Blueprint versions">
        <VersionsPanel key={blueprint.id} blueprint={blueprint} />
      </Drawer>

      <Modal opened={editOpened} onClose={() => setEditOpened(false)} title="Edit blueprint" size="lg">
        <Stack gap="sm">
          <NameInput
            label="Name"
            description={`${DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH}-${DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH} characters`}
             value={editName}
             disabled={!canManage}
            onChange={setEditName}
            maxLength={DATASCOPE_BLUEPRINT_NAME_MAX_LENGTH}
            error={editNameError}
          />
          <Textarea label="Description" minRows={2} value={editDescription} disabled={!canManage} onChange={(event) => setEditDescription(event.currentTarget.value)} />
          <Select
            label="Default masking policy"
            description="Per-table policies in the table map override this."
            data={[{ value: '', label: 'No default policy' }].concat(policies.map((item) => ({ value: String(item.id), label: item.name })))}
            value={editPolicyId}
             searchable
             disabled={!canManage}
            onChange={(value) => setEditPolicyId(value || '')}
          />
          <Group justify="flex-end">
            <Button variant="light" onClick={() => setEditOpened(false)}>Cancel</Button>
            <Button loading={savingEdit} disabled={!canManage || !!editNameError || editNameLength < DATASCOPE_BLUEPRINT_NAME_MIN_LENGTH} onClick={() => void saveEdit()}>Save</Button>
          </Group>
        </Stack>
      </Modal>
    </Card>
  );
}
