'use client';

import { useEffect, useMemo, useState } from 'react';
import { ActionIcon, Badge, Button, Group, Modal, Paper, Select, SimpleGrid, Stack, Text, TextInput, Textarea, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconCode, IconEdit, IconEye, IconPlayerPlay, IconRefresh, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import { useConfirm } from '@/components/confirm';
import { usePermissions } from '@/lib/use-permissions';
import { NameInput } from '@/components/name-input';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { keys } from '@/lib/keys';
import type { MaskPreview, MaskingScript } from '@/lib/types';
import { EmptyPanel, MaskingHeader, PreviewResult } from './components';
import { useMaskingScripts } from './hooks';
import type { ScriptDraft } from './types';
import { formatDate, safeInputValue, scriptHints, scriptSamples, technicalInputProps } from './utils';

const emptyDraft: ScriptDraft = {
  name: '',
  description: '',
  visibility: 'GLOBAL',
  luaSource: ''
};

export function MaskingScriptsPage() {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('policy.manage');
  const canPreview = can('policy.read');
  const scriptsQuery = useMaskingScripts();
  const scripts = useMemo(() => scriptsQuery.data || [], [scriptsQuery.data]);
  const [draft, setDraft] = useState<ScriptDraft>(emptyDraft);
  const [testValue, setTestValue] = useState('yash1234');
  const [testResult, setTestResult] = useState<MaskPreview | null>(null);
  const [search, setSearch] = useState('');
  const [sampleName, setSampleName] = useState<string | null>(null);
  const [browseOpen, setBrowseOpen] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (!dirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const updateDraft = (patch: Partial<ScriptDraft>) => {
    if (!canManage) return;
    setDirty(true);
    setDraft((current) => ({ ...current, ...patch }));
    setTestResult(null);
  };

  const confirmDiscard = async (message: string) => {
    if (!dirty) return true;
    return confirm({ title: 'Discard unsaved script changes?', message, okText: 'Discard changes', danger: true });
  };

  const loadSample = async (name: string | null) => {
    if (!canManage) return;
    if (!(await confirmDiscard('Loading a sample will replace the Lua source currently in the editor.'))) return;
    setSampleName(name);
    const sample = scriptSamples.find((item) => item.name === name);
    if (sample) {
      setDraft({
        name: sample.name,
        description: sample.description,
        visibility: 'GLOBAL',
        luaSource: sample.luaSource
      });
      setTestResult(null);
      setDirty(false);
    }
  };

  const saveMutation = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Policy management permission is required.');
      return apiPost<MaskingScript>('/api/policies/scripts', draft);
    },
    onSuccess: (saved) => {
      notifications.show({ color: 'green', title: 'Script saved', message: `Use function SCRIPT with param1 = ${saved.name}` });
      queryClient.invalidateQueries({ queryKey: keys.policies.scripts });
      setDraft((current) => ({ ...current, name: saved.name, description: saved.description || current.description }));
      setDirty(false);
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not save script', message: (error as Error).message })
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => {
      if (!canManage) throw new Error('Policy management permission is required.');
      return apiFetch(`/api/policies/scripts/${id}`, { method: 'DELETE' });
    },
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Script deleted', message: 'Rules referencing it must be repointed before running.' });
      queryClient.invalidateQueries({ queryKey: keys.policies.scripts });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not delete script', message: (error as Error).message })
  });

  const testMutation = useMutation({
    mutationFn: () => {
      if (!canPreview) throw new Error('Policy read permission is required.');
      return apiPost<MaskPreview>('/api/policies/preview', {
        function: 'SCRIPT',
        param1: draft.name,
        value: testValue
      });
    },
    onSuccess: setTestResult,
    onError: (error) => notifications.show({ color: 'red', title: 'Script test failed', message: (error as Error).message })
  });

  const filteredScripts = useMemo(() => {
    const q = search.trim().toLowerCase();
    if (!q) return scripts;
    return scripts.filter((script) => `${script.name} ${script.description || ''} ${script.visibility || ''}`.toLowerCase().includes(q));
  }, [scripts, search]);

  const edit = async (script: MaskingScript) => {
    if (!(await confirmDiscard('Loading a saved script will replace the Lua source currently in the editor.'))) return;
    setDraft({
      name: script.name,
      description: script.description || '',
      visibility: script.visibility === 'PRIVATE' ? 'PRIVATE' : 'GLOBAL',
      luaSource: script.luaSource || ''
    });
    setTestResult(null);
    setDirty(false);
    setBrowseOpen(false);
  };

  const removeScript = async (script: MaskingScript) => {
    if (!canManage) return;
    const ok = await confirm({
      title: 'Delete masking script',
      message: `Delete "${script.name}"? Policy rules that reference this script must be repointed before they can run.`,
      okText: 'Delete',
      danger: true
    });
    if (ok) deleteMutation.mutate(script.id);
  };

  const saveDisabled = !canManage || !draft.name.trim() || !draft.luaSource.trim();

  return (
    <main className="forge-page masking-page">
      {confirmElement}
      <MaskingHeader
        eyebrow="Governed transformation code"
        title="Masking Scripts"
        description="Write, test, and govern deterministic Lua masking exits for policy rules."
        action={
          <Group gap="xs">
            <Badge variant="light">{scripts.length} saved</Badge>
            <Button size="sm" leftSection={<IconRefresh size={16} />} variant="default" onClick={() => scriptsQuery.refetch()}>
              Refresh
            </Button>
            <Button size="sm" leftSection={<IconEdit size={16} />} onClick={() => setBrowseOpen(true)}>
              Browse existing
            </Button>
          </Group>
        }
      />

      <QueryErrorBanner
        errors={[scriptsQuery.error]}
        onRetry={() => scriptsQuery.refetch()}
        title="Masking scripts could not be loaded"
      />

      <section className="masking-script-workbench">
        <Paper className="forge-card masking-panel" p="md">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={780}>Script editor</Text>
              <Text size="sm" c="dimmed">
                Save by name. Existing names update in place, which keeps policy references stable.
              </Text>
            </div>
            <Group gap="xs">
              {dirty ? <Badge color="yellow" variant="light">Unsaved</Badge> : null}
              <Badge variant="light">{draft.visibility}</Badge>
            </Group>
          </Group>

          <SimpleGrid cols={{ base: 1, md: 2 }} spacing="sm" mt="md">
            <NameInput label="Name" placeholder="bank-a.custom-ref" value={draft.name} readOnly={!canManage} onChange={(value) => updateDraft({ name: value })} />
            <Select
              label="Visibility"
              data={[
                { value: 'GLOBAL', label: 'GLOBAL - usable in jobs' },
                { value: 'PRIVATE', label: 'PRIVATE - draft' }
              ]}
              value={draft.visibility}
              disabled={!canManage}
              onChange={(value) => updateDraft({ visibility: value === 'PRIVATE' ? 'PRIVATE' : 'GLOBAL' })}
            />
          </SimpleGrid>
          <TextInput mt="sm" label="Description" value={draft.description} readOnly={!canManage} onChange={(event) => updateDraft({ description: safeInputValue(event) })} />
          {canManage ? (
            <Select
              mt="sm"
              label="Load sample"
              placeholder="Pick an example"
              clearable
              data={scriptSamples.map((sample) => ({ value: sample.name, label: `${sample.flavour}: ${sample.name}` }))}
              value={sampleName}
              onChange={(value) => void loadSample(value)}
            />
          ) : null}
          <Textarea
            mt="sm"
            className="masking-script-editor"
            minRows={24}
            label="Lua source"
            value={draft.luaSource}
            readOnly={!canManage}
            onChange={(event) => updateDraft({ luaSource: safeInputValue(event) })}
            placeholder={'-- inputs: value, param, rowIndex, row["col"]\nreturn forge.mask("FIRST_NAME", value)'}
            {...technicalInputProps}
          />
          {canManage ? (
            <Group gap="xs" mt="sm">
              {scriptHints.map((hint) => (
                <button key={hint} type="button" className="masking-hint-chip" onClick={() => updateDraft({ luaSource: `${draft.luaSource}${draft.luaSource.endsWith('\n') || !draft.luaSource ? '' : '\n'}${hint}` })}>
                  {hint}
                </button>
              ))}
            </Group>
          ) : null}
          <Group mt="md" justify="space-between" align="end">
            {canManage ? (
              <Button leftSection={<IconCode size={16} />} loading={saveMutation.isPending} disabled={saveDisabled} onClick={() => {
                if (canManage) saveMutation.mutate();
              }}>
                Save script
              </Button>
            ) : <div />}
            <Group align="end">
              <TextInput label="Test value" value={testValue} disabled={!canPreview} onChange={(event) => setTestValue(safeInputValue(event))} {...technicalInputProps} />
              <Button variant="default" leftSection={<IconPlayerPlay size={16} />} loading={testMutation.isPending} disabled={!canPreview || !draft.name.trim()} onClick={() => {
                if (canPreview) testMutation.mutate();
              }}>
                Test saved
              </Button>
            </Group>
          </Group>
          <PreviewResult original={testResult?.original} masked={testResult?.masked} />
        </Paper>
      </section>

      <Modal opened={browseOpen} onClose={() => setBrowseOpen(false)} title="Browse existing scripts" size="xl" centered>
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            Load a saved script into the editor. Rule param1 must match the script name exactly.
          </Text>
          <Group justify="space-between" align="end">
            <TextInput className="masking-script-browser-search" placeholder="Search scripts..." value={search} onChange={(event) => setSearch(safeInputValue(event))} />
            <Badge variant="light">{filteredScripts.length} scripts</Badge>
          </Group>
          <Stack gap={0} className="masking-list masking-script-browser-list">
            {filteredScripts.map((script) => (
              <div key={script.id} className="masking-list-row">
                <div>
                  <Group gap="xs">
                    <Text fw={760} className="masking-mono-line">
                      {script.name}
                    </Text>
                    <Badge variant="light" color={script.visibility === 'PRIVATE' ? 'yellow' : 'green'}>
                      {script.visibility || 'GLOBAL'}
                    </Badge>
                  </Group>
                  <Text size="sm" c="dimmed">
                    {script.description || 'No description'} - updated {formatDate(script.updatedAt)}
                  </Text>
                </div>
                <Group gap={4}>
                  <Tooltip label={canManage ? 'Edit' : 'View'}>
                    <ActionIcon variant="subtle" aria-label={`${canManage ? 'Edit' : 'View'} ${script.name}`} onClick={() => void edit(script)}>
                      {canManage ? <IconEdit size={16} /> : <IconEye size={16} />}
                    </ActionIcon>
                  </Tooltip>
                  {canManage ? (
                    <Tooltip label="Delete">
                      <ActionIcon variant="subtle" color="red" aria-label={`Delete ${script.name}`} onClick={() => void removeScript(script)}>
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Tooltip>
                  ) : null}
                </Group>
              </div>
            ))}
            {!filteredScripts.length ? <EmptyPanel title="No scripts found" detail="Create one from a sample or paste a Lua exit. It must return the masked value." /> : null}
          </Stack>
        </Stack>
      </Modal>
    </main>
  );
}
