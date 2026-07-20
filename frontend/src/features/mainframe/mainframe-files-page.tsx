'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Checkbox,
  Group,
  Modal,
  Paper,
  PasswordInput,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconFolderSearch,
  IconMap,
  IconPlayerPlay,
  IconRefresh,
  IconServerCog,
  IconTrash,
  IconX
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { useConfirm } from '@/components/confirm';
import { NameInput } from '@/components/name-input';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { keys } from '@/lib/keys';
import type { MaskingScript } from '@/lib/types';
import { usePermissions } from '@/lib/use-permissions';
import { useMaskingFunctions, useMaskingScripts } from '@/features/masking/hooks';
import {
  ConnectionName,
  EmptyState,
  JobProgress,
  MainframeHeader,
  MainframeMetric,
  ParamInput,
  StatusBadge,
  TinyButton,
  maskFunctionOptions
} from './components';
import {
  useCopybookFields,
  useCopybookMasks,
  useMainframeConnections,
  useMainframeCopybook,
  useMainframeCopybooks,
  useMainframeJobDetail,
  useMainframeJobs
} from './hooks';
import type {
  CopybookDef,
  CopybookField,
  CopybookMask,
  CopybookSummary,
  MainframeConnection,
  MainframeFileInfo,
  MainframeJob,
  MainframeJobFile,
  MaskDraft
} from './types';
import {
  MAINFRAME_COPYBOOK_SAMPLE,
  boolInputChecked,
  errorMessage,
  formatDate,
  guessRegistryMaskFunction,
  numberOrNull,
  safeInputValue,
  technicalInputProps
} from './utils';

const JOB_TARGET = '__JOB_TARGET__';

type ConnDraft = {
  name: string;
  type: 'LOCAL' | 'ZOWE';
  codePage: string;
  baseDir: string;
  host: string;
  port: string;
  basePath: string;
  username: string;
  password: string;
  trustAllCerts: boolean;
};

type CopybookDraft = {
  name: string;
  codePage: string;
  source: string;
};

type JobDraft = {
  name: string;
  sourceConnectionId: string | null;
  targetConnectionId: string | null;
  maskingSeed: string;
};

type JobFileDraft = {
  uid: string;
  sourceName: string;
  copybookId: string | null;
  recfm: 'FB' | 'VB';
  lrecl: string;
  codePage: string;
  targetConnectionId: string | null;
  targetName: string;
};

type TestState = {
  status: 'testing' | 'ok' | 'failed';
  message: string;
};

const emptyConnDraft: ConnDraft = {
  name: '',
  type: 'LOCAL',
  codePage: 'Cp037',
  baseDir: '',
  host: '',
  port: '443',
  basePath: '/zosmf',
  username: '',
  password: '',
  trustAllCerts: false
};

const emptyCopybookDraft: CopybookDraft = {
  name: 'customer-record',
  codePage: 'Cp037',
  source: MAINFRAME_COPYBOOK_SAMPLE
};

const emptyJobDraft: JobDraft = {
  name: 'nightly-mainframe-mask',
  sourceConnectionId: null,
  targetConnectionId: null,
  maskingSeed: ''
};

export function MainframeFilesPage() {
  const queryClient = useQueryClient();
  const { confirm, confirmElement } = useConfirm();
  const { can } = usePermissions();
  const canManage = can('mainframe.manage');
  const connectionsQuery = useMainframeConnections();
  const copybooksQuery = useMainframeCopybooks();
  const jobsQuery = useMainframeJobs();
  const functionsQuery = useMaskingFunctions();
  const scriptsQuery = useMaskingScripts();
  const connections = useMemo(() => connectionsQuery.data || [], [connectionsQuery.data]);
  const copybooks = useMemo(() => copybooksQuery.data || [], [copybooksQuery.data]);
  const jobs = useMemo(() => jobsQuery.data || [], [jobsQuery.data]);
  const functions = useMemo(() => functionsQuery.data || [], [functionsQuery.data]);
  const scripts = useMemo(() => scriptsQuery.data || [], [scriptsQuery.data]);

  const [connDraft, setConnDraft] = useState<ConnDraft>(emptyConnDraft);
  const [editingConnectionId, setEditingConnectionId] = useState<number | null>(null);
  const [copybookDraft, setCopybookDraft] = useState<CopybookDraft>(emptyCopybookDraft);
  const [editingCopybookId, setEditingCopybookId] = useState<number | null>(null);
  const [mapCopybookId, setMapCopybookId] = useState<number | null>(null);
  const [mapDrafts, setMapDrafts] = useState<Record<string, MaskDraft>>({});
  const [mapDirty, setMapDirty] = useState(false);
  const [connectionDirty, setConnectionDirty] = useState(false);
  const [copybookDirty, setCopybookDirty] = useState(false);
  const [testStates, setTestStates] = useState<Record<number, TestState>>({});
  const [jobDraft, setJobDraft] = useState<JobDraft>(emptyJobDraft);
  const [jobFiles, setJobFiles] = useState<JobFileDraft[]>([newJobFile()]);
  const [browseOpen, setBrowseOpen] = useState(false);
  const [browseFiles, setBrowseFiles] = useState<MainframeFileInfo[]>([]);
  const [selectedJobId, setSelectedJobId] = useState<number | null>(null);
  const hydratedMapId = useRef<number | null>(null);
  const hydratedEditId = useRef<number | null>(null);

  const effectiveMapCopybookId = mapCopybookId ?? copybooks[0]?.id ?? null;
  const selectedMapCopybook = copybooks.find((copybook) => copybook.id === effectiveMapCopybookId) || null;
  const mapFieldsQuery = useCopybookFields(effectiveMapCopybookId);
  const mapMasksQuery = useCopybookMasks(effectiveMapCopybookId);
  const editCopybookQuery = useMainframeCopybook(editingCopybookId);
  const jobDetailQuery = useMainframeJobDetail(selectedJobId);

  const connectionOptions = connections.map((connection) => ({
    value: String(connection.id),
    label: `${connection.name} (${connection.type})`
  }));
  const targetOptions = [{ value: JOB_TARGET, label: '(job target)' }, ...connectionOptions];
  const copybookOptions = copybooks.map((copybook) => ({
    value: String(copybook.id),
    label: `${copybook.name}${copybook.recordLength ? ` - ${copybook.recordLength} bytes` : ''}`
  }));

  const effectiveJobSourceId = jobDraft.sourceConnectionId ?? (connections[0] ? String(connections[0].id) : null);
  const effectiveJobTargetId = jobDraft.targetConnectionId ?? (connections[0] ? String(connections[0].id) : null);

  useEffect(() => {
    const fields = mapFieldsQuery.data || [];
    if (!mapFieldsQuery.isSuccess || !mapMasksQuery.isSuccess || !effectiveMapCopybookId) return;
    if (mapDirty || hydratedMapId.current === effectiveMapCopybookId) return;
    hydratedMapId.current = effectiveMapCopybookId;
    // This is an explicit one-time hydration for a newly selected copybook.
    setMapDrafts(buildMapDrafts(fields, mapMasksQuery.data || []));
  }, [effectiveMapCopybookId, mapDirty, mapFieldsQuery.data, mapFieldsQuery.isSuccess, mapMasksQuery.data, mapMasksQuery.isSuccess]);

  useEffect(() => {
    const copybook = editCopybookQuery.data;
    if (!copybook || copybookDirty || hydratedEditId.current === copybook.id) return;
    hydratedEditId.current = copybook.id;
    // Hydrate the editor once per explicit edit selection; refetches cannot clobber typing.
    setCopybookDraft({
      name: copybook.name || '',
      codePage: copybook.codePage || 'Cp037',
      source: copybook.source || ''
    });
    setCopybookDirty(false);
  }, [copybookDirty, editCopybookQuery.data]);

  useEffect(() => {
    if (!connectionDirty && !copybookDirty && !mapDirty) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [connectionDirty, copybookDirty, mapDirty]);

  const runningJobs = jobs.filter((job) => ['PENDING', 'RUNNING'].includes(String(job.status || '').toUpperCase())).length;
  const recordsProcessed = jobs.reduce((total, job) => total + Number(job.recordsProcessed || 0), 0);

  const saveConnectionMutation = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Mainframe management permission is required');
      return editingConnectionId
        ? apiPut<MainframeConnection>(`/api/mainframe/connections/${editingConnectionId}`, connectionPayload(connDraft))
        : apiPost<MainframeConnection>('/api/mainframe/connections', connectionPayload(connDraft));
    },
    onSuccess: (saved) => {
      notifications.show({ color: 'green', title: editingConnectionId ? 'Connection updated' : 'Connection saved', message: `${saved.name} is available for mainframe jobs.` });
      setConnDraft(emptyConnDraft);
      setEditingConnectionId(null);
      setConnectionDirty(false);
      void queryClient.invalidateQueries({ queryKey: keys.mainframe.connections });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Connection failed', message: errorMessage(error) })
  });

  const deleteConnectionMutation = useMutation({
    mutationFn: (id: number) => {
      if (!canManage) throw new Error('Mainframe management permission is required');
      return apiFetch(`/api/mainframe/connections/${id}`, { method: 'DELETE' });
    },
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Connection deleted', message: 'The LPAR/local endpoint was removed.' });
      setEditingConnectionId(null);
      setConnDraft(emptyConnDraft);
      setConnectionDirty(false);
      void queryClient.invalidateQueries({ queryKey: keys.mainframe.connections });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Delete failed', message: errorMessage(error) })
  });

  const saveCopybookMutation = useMutation({
    mutationFn: async () => {
      if (!canManage) throw new Error('Mainframe management permission is required');
      const payload = { name: copybookDraft.name, codePage: copybookDraft.codePage, source: copybookDraft.source };
      return editingCopybookId
        ? apiPut<CopybookDef>(`/api/mainframe/copybooks/${editingCopybookId}`, payload)
        : apiPost<CopybookDef>('/api/mainframe/copybooks', payload);
    },
    onSuccess: (saved) => {
      notifications.show({ color: 'green', title: editingCopybookId ? 'Copybook updated' : 'Copybook saved', message: `${saved.name} parsed as ${saved.recordLength || 0} bytes.` });
      setEditingCopybookId(null);
      setCopybookDraft(emptyCopybookDraft);
      setCopybookDirty(false);
      setMapCopybookId(saved.id);
      void queryClient.invalidateQueries({ queryKey: keys.mainframe.copybooks });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Copybook failed', message: errorMessage(error) })
  });

  const deleteCopybookMutation = useMutation({
    mutationFn: (id: number) => {
      if (!canManage) throw new Error('Mainframe management permission is required');
      return apiFetch(`/api/mainframe/copybooks/${id}`, { method: 'DELETE' });
    },
    onSuccess: (_, id) => {
      notifications.show({ color: 'green', title: 'Copybook deleted', message: 'Copybook and field map were removed.' });
      if (editingCopybookId === id) setEditingCopybookId(null);
      if (mapCopybookId === id) setMapCopybookId(null);
      void queryClient.invalidateQueries({ queryKey: keys.mainframe.copybooks });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Delete failed', message: errorMessage(error) })
  });

  const saveMapMutation = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Mainframe management permission is required');
      return apiPut<CopybookMask[]>(
        `/api/mainframe/copybooks/${effectiveMapCopybookId}/masks`,
        Object.entries(mapDrafts)
          .filter(([, draft]) => draft.function && draft.function !== 'NONE')
          .map(([fieldPath, draft]) => ({
            fieldPath,
            function: draft.function,
            param1: draft.param1 || null,
            param2: draft.param2 || null
          }))
      );
    },
    onSuccess: (saved) => {
      notifications.show({ color: 'green', title: 'Field map saved', message: `${saved.length} masking rule(s) saved for this copybook.` });
      setMapDirty(false);
      void queryClient.invalidateQueries({ queryKey: keys.mainframe.copybookMasks(effectiveMapCopybookId) });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Field map failed', message: errorMessage(error) })
  });

  const launchJobMutation = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Mainframe management permission is required');
      return apiPost('/api/mainframe/jobs', {
        name: jobDraft.name,
        sourceConnectionId: numberOrNull(effectiveJobSourceId),
        targetConnectionId: numberOrNull(effectiveJobTargetId),
        maskingSeed: jobDraft.maskingSeed || null,
        files: jobFiles
          .filter((file) => file.sourceName.trim())
          .map((file) => ({
            sourceName: file.sourceName.trim(),
            copybookId: numberOrNull(file.copybookId),
            recfm: file.recfm,
            lrecl: numberOrNull(file.lrecl),
            codePage: file.codePage || null,
            targetConnectionId: file.targetConnectionId && file.targetConnectionId !== JOB_TARGET ? numberOrNull(file.targetConnectionId) : null,
            targetName: file.targetName || null
          }))
      });
    },
    onSuccess: () => {
      notifications.show({ color: 'green', title: 'Mainframe job launched', message: 'Files will update as the backend worker processes them.' });
      void queryClient.invalidateQueries({ queryKey: keys.mainframe.jobs });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Job launch failed', message: errorMessage(error) })
  });

  const testConnection = async (connection: MainframeConnection) => {
    if (!canManage) return;
    setTestStates((current) => ({
      ...current,
      [connection.id]: {
        status: 'testing',
        message: connection.type === 'ZOWE' ? 'Contacting z/OSMF and listing datasets...' : 'Checking landing folder...'
      }
    }));
    try {
      const result = await apiPost<Record<string, unknown>>(`/api/mainframe/connections/${connection.id}/test`, {});
      setTestStates((current) => ({
        ...current,
        [connection.id]: {
          status: result.ok ? 'ok' : 'failed',
          message: result.ok ? `${result.count || 0} file(s) visible` : String(result.error || 'Connection failed')
        }
      }));
    } catch (error) {
      setTestStates((current) => ({
        ...current,
        [connection.id]: { status: 'failed', message: errorMessage(error) }
      }));
    }
  };

  const browseSourceFiles = async () => {
    const sourceId = numberOrNull(effectiveJobSourceId);
    if (!sourceId) {
      notifications.show({ color: 'red', title: 'Source LPAR required', message: 'Select a source connection before browsing files.' });
      return;
    }
    setBrowseOpen(true);
    setBrowseFiles([]);
    try {
      const files = await apiFetch<MainframeFileInfo[]>(`/api/mainframe/connections/${sourceId}/files?pattern=*`);
      setBrowseFiles(files || []);
    } catch (error) {
      notifications.show({ color: 'red', title: 'Browse failed', message: errorMessage(error) });
    }
  };

  const addFromBrowse = (file: MainframeFileInfo) => {
    setJobFiles((current) => [
      ...current.filter((row) => row.sourceName.trim()),
      newJobFile({ sourceName: file.name, recfm: file.recfm === 'VB' ? 'VB' : 'FB', lrecl: file.lrecl ? String(file.lrecl) : '' })
    ]);
  };

  const updateJobFile = (uid: string, patch: Partial<JobFileDraft>) => {
    setJobFiles((current) => current.map((file) => (file.uid === uid ? { ...file, ...patch } : file)));
  };

  const removeJobFile = (uid: string) => {
    setJobFiles((current) => (current.length <= 1 ? [newJobFile()] : current.filter((file) => file.uid !== uid)));
  };

  const updateMapDraft = (path: string, patch: Partial<MaskDraft>) => {
    setMapDirty(true);
    setMapDrafts((current) => {
      const base = current[path] || {
        enabled: true,
        function: 'NONE',
        param1: '',
        param2: ''
      };
      return {
        ...current,
        [path]: {
          ...base,
          ...patch
        }
      };
    });
  };

  const selectMapCopybook = async (value: string | null) => {
    const id = numberOrNull(value);
    if (id === effectiveMapCopybookId) return;
    if (mapDirty) {
      const discard = await confirm({
        title: 'Discard unsaved field-map changes?',
        message: 'Switching copybooks will replace the masking rules currently being edited.',
        okText: 'Discard and switch',
        danger: true
      });
      if (!discard) return;
    }
    setMapCopybookId(id);
    setMapDrafts({});
    setMapDirty(false);
    hydratedMapId.current = null;
  };

  const beginEditCopybook = async (id: number) => {
    if (id === editingCopybookId) return;
    if (copybookDirty) {
      const discard = await confirm({
        title: 'Discard unsaved copybook changes?',
        message: 'Loading another copybook will replace the source currently in the editor.',
        okText: 'Discard and edit',
        danger: true
      });
      if (!discard) return;
    }
    hydratedEditId.current = null;
    setCopybookDirty(false);
    setEditingCopybookId(id);
  };

  const removeConnection = async (connection: MainframeConnection) => {
    if (!canManage) return;
    const ok = await confirm({
      title: 'Delete mainframe connection',
      message: `Delete "${connection.name}"? Existing jobs that reference this endpoint may no longer be rerunnable.`,
      okText: 'Delete',
      danger: true
    });
    if (ok) deleteConnectionMutation.mutate(connection.id);
  };

  const beginEditConnection = async (connection: MainframeConnection) => {
    if (!canManage) return;
    if (connection.id === editingConnectionId) return;
    if (connectionDirty) {
      const discard = await confirm({
        title: 'Discard unsaved connection changes?',
        message: 'Loading another endpoint will replace the connection currently in the editor.',
        okText: 'Discard and edit',
        danger: true
      });
      if (!discard) return;
    }
    setEditingConnectionId(connection.id);
    setConnDraft({
      name: connection.name || '',
      type: connection.type === 'ZOWE' ? 'ZOWE' : 'LOCAL',
      codePage: connection.codePage || 'Cp037',
      baseDir: connection.baseDir || '',
      host: connection.host || '',
      port: connection.port == null ? '443' : String(connection.port),
      basePath: connection.basePath || '/zosmf',
      username: connection.username || '',
      password: '',
      trustAllCerts: Boolean(connection.trustAllCerts)
    });
    setConnectionDirty(false);
  };

  const cancelConnectionEdit = async () => {
    if (connectionDirty) {
      const discard = await confirm({
        title: 'Discard connection changes?',
        message: 'The unsaved endpoint changes will be lost.',
        okText: 'Discard',
        danger: true
      });
      if (!discard) return;
    }
    setEditingConnectionId(null);
    setConnDraft(emptyConnDraft);
    setConnectionDirty(false);
  };

  const updateConnectionDraft = (patch: Partial<ConnDraft>) => {
    setConnectionDirty(true);
    setConnDraft((current) => ({ ...current, ...patch }));
  };

  const updateCopybookDraft = (patch: Partial<CopybookDraft>) => {
    setCopybookDirty(true);
    setCopybookDraft((current) => ({ ...current, ...patch }));
  };

  const resetCopybookDraft = async () => {
    if (copybookDirty) {
      const discard = await confirm({
        title: 'Replace the current copybook?',
        message: 'This loads the starter sample and discards the unsaved source in the editor.',
        okText: 'Load sample',
        danger: true
      });
      if (!discard) return;
    }
    setEditingCopybookId(null);
    hydratedEditId.current = null;
    setCopybookDraft(emptyCopybookDraft);
    setCopybookDirty(false);
  };

  const removeCopybook = async (copybook: CopybookSummary) => {
    if (!canManage) return;
    const ok = await confirm({
      title: 'Delete copybook',
      message: `Delete "${copybook.name}" and its masking field map? Mainframe jobs that reference it may no longer be rerunnable.`,
      okText: 'Delete',
      danger: true
    });
    if (ok) deleteCopybookMutation.mutate(copybook.id);
  };

  const connectionReady = Boolean(
    connDraft.name.trim() &&
      connDraft.codePage.trim() &&
      (connDraft.type === 'LOCAL' ? connDraft.baseDir.trim() : connDraft.host.trim())
  );
  const copybookReady = Boolean(copybookDraft.name.trim() && copybookDraft.source.trim());
  const jobReady = Boolean(
    jobDraft.name.trim() &&
      effectiveJobSourceId &&
      effectiveJobTargetId &&
      jobFiles.some((file) => file.sourceName.trim() && file.copybookId)
  );

  return (
    <main className="forge-page mf-page mainframe-files-page">
      {confirmElement}
      <MainframeHeader
        eyebrow="Mainframe"
        title="Mainframe Files"
        description="Register LOCAL or Zowe/zOSMF endpoints, maintain copybook field maps, browse source datasets, and run governed masking jobs across mainframe files."
        action={
          <Button leftSection={<IconRefresh size={16} />} variant="default" onClick={() => void Promise.all([connectionsQuery.refetch(), copybooksQuery.refetch(), jobsQuery.refetch()])}>
            Refresh
          </Button>
        }
      />

      <QueryErrorBanner
        errors={[
          connectionsQuery.error,
          copybooksQuery.error,
          jobsQuery.error,
          functionsQuery.error,
          scriptsQuery.error,
          mapFieldsQuery.error,
          mapMasksQuery.error,
          editCopybookQuery.error,
          jobDetailQuery.error
        ]}
        onRetry={() => Promise.all([connectionsQuery.refetch(), copybooksQuery.refetch(), jobsQuery.refetch(), functionsQuery.refetch(), scriptsQuery.refetch()])}
        title="Mainframe Files could not load all backend data"
      />

      <SimpleGrid cols={{ base: 1, md: 4 }} spacing="sm">
        <MainframeMetric label="Connections" value={connections.length} detail="LOCAL and Zowe endpoints" icon="server" />
        <MainframeMetric label="Copybooks" value={copybooks.length} detail="Reusable record layouts" />
        <MainframeMetric label="Running" value={runningJobs} detail="Pending or active jobs" />
        <MainframeMetric label="Records" value={recordsProcessed.toLocaleString()} detail="Processed by MF jobs" />
      </SimpleGrid>

      <Tabs defaultValue="connections" classNames={{ list: 'forge-tabs-list' }}>
        <Tabs.List>
          <Tabs.Tab value="connections" leftSection={<IconServerCog size={15} />}>
            Connections
          </Tabs.Tab>
          <Tabs.Tab value="copybooks" leftSection={<IconMap size={15} />}>
            Copybook maps
          </Tabs.Tab>
          <Tabs.Tab value="jobs" leftSection={<IconPlayerPlay size={15} />}>
            Masking jobs
          </Tabs.Tab>
        </Tabs.List>

        <Tabs.Panel value="connections" pt="md">
          <section className="mf-two-column">
            <Paper className="forge-card mf-panel" p="md">
              <Text fw={820}>{editingConnectionId ? 'Edit connection' : 'Add connection'}</Text>
              <Text size="sm" c="dimmed">
                LOCAL uses a landing folder. ZOWE uses z/OSMF over HTTPS.
              </Text>
              <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="sm" mt="md">
                <NameInput label="Name" value={connDraft.name} onChange={(value) => updateConnectionDraft({ name: value })} />
                <Select label="Type" data={['LOCAL', 'ZOWE']} value={connDraft.type} onChange={(value) => updateConnectionDraft({ type: value === 'ZOWE' ? 'ZOWE' : 'LOCAL' })} />
                <TextInput {...technicalInputProps} label="Code page" value={connDraft.codePage} onChange={(event) => updateConnectionDraft({ codePage: safeInputValue(event) })} />
              </SimpleGrid>
              {connDraft.type === 'LOCAL' ? (
                <TextInput {...technicalInputProps} mt="sm" label="Base directory" placeholder="C:\\mf-landing\\lparA" value={connDraft.baseDir} onChange={(event) => updateConnectionDraft({ baseDir: safeInputValue(event) })} />
              ) : (
                <Stack gap="sm" mt="sm">
                  <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="sm">
                    <TextInput {...technicalInputProps} label="Host" value={connDraft.host} onChange={(event) => updateConnectionDraft({ host: safeInputValue(event) })} />
                    <TextInput {...technicalInputProps} label="Port" value={connDraft.port} onChange={(event) => updateConnectionDraft({ port: safeInputValue(event) })} />
                    <TextInput {...technicalInputProps} label="z/OSMF base path" value={connDraft.basePath} onChange={(event) => updateConnectionDraft({ basePath: safeInputValue(event) })} />
                  </SimpleGrid>
                  <SimpleGrid cols={{ base: 1, sm: 3 }} spacing="sm">
                    <TextInput {...technicalInputProps} label="Username" value={connDraft.username} onChange={(event) => updateConnectionDraft({ username: safeInputValue(event) })} />
                    <PasswordInput
                      label="Password"
                      description={editingConnectionId ? 'Leave blank to keep the saved secret.' : undefined}
                      value={connDraft.password}
                      onChange={(event) => updateConnectionDraft({ password: safeInputValue(event) })}
                    />
                    <Checkbox mt={30} label="Trust self-signed cert" checked={connDraft.trustAllCerts} onChange={(event) => updateConnectionDraft({ trustAllCerts: boolInputChecked(event) })} />
                  </SimpleGrid>
                </Stack>
              )}
              <Group justify="flex-end" mt="md">
                {editingConnectionId ? (
                  <Button variant="default" onClick={() => void cancelConnectionEdit()}>
                    Cancel
                  </Button>
                ) : null}
                <Button loading={saveConnectionMutation.isPending} disabled={!canManage || !connectionReady} onClick={() => saveConnectionMutation.mutate()}>
                  {editingConnectionId ? 'Save changes' : 'Add connection'}
                </Button>
              </Group>
            </Paper>

            <Paper className="forge-card mf-panel" p="md">
              <Group justify="space-between">
                <div>
                  <Text fw={820}>Registered endpoints</Text>
                  <Text size="sm" c="dimmed">
                    Test gives immediate status text while the backend calls z/OSMF or the local folder.
                  </Text>
                </div>
                <Badge variant="light">{connections.length}</Badge>
              </Group>
              <ConnectionsTable
                connections={connections}
                canManage={canManage}
                testStates={testStates}
                onTest={testConnection}
                onEdit={beginEditConnection}
                onDelete={(connection) => void removeConnection(connection)}
              />
            </Paper>
          </section>
        </Tabs.Panel>

        <Tabs.Panel value="copybooks" pt="md">
          <section className="mf-two-column">
            <Paper className="forge-card mf-panel" p="md">
              <Group justify="space-between" align="flex-start">
                <div>
                  <Text fw={820}>{editingCopybookId ? 'Edit copybook' : 'Register copybook'}</Text>
                  <Text size="sm" c="dimmed">
                    Saving validates the copybook and records layout metadata.
                  </Text>
                </div>
                {editingCopybookId ? (
                  <TinyButton onClick={() => void resetCopybookDraft()} leftSection={<IconX size={14} />}>
                    Cancel edit
                  </TinyButton>
                ) : null}
              </Group>
              <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm" mt="md">
                <NameInput label="Name" value={copybookDraft.name} onChange={(value) => updateCopybookDraft({ name: value })} />
                <TextInput {...technicalInputProps} label="Code page" value={copybookDraft.codePage} onChange={(event) => updateCopybookDraft({ codePage: safeInputValue(event) })} />
              </SimpleGrid>
              <Textarea
                {...technicalInputProps}
                className="mf-code-editor"
                minRows={16}
                mt="sm"
                label="COBOL copybook source"
                value={copybookDraft.source}
                onChange={(event) => updateCopybookDraft({ source: safeInputValue(event) })}
              />
              <Group justify="flex-end" mt="md">
                <Button variant="default" onClick={() => void resetCopybookDraft()}>
                  Load sample
                </Button>
                <Button loading={saveCopybookMutation.isPending} disabled={!canManage || !copybookReady} onClick={() => saveCopybookMutation.mutate()}>
                  {editingCopybookId ? 'Update copybook' : 'Save copybook'}
                </Button>
              </Group>
            </Paper>

            <Paper className="forge-card mf-panel" p="md">
              <Group justify="space-between">
                <div>
                  <Text fw={820}>Copybook registry</Text>
                  <Text size="sm" c="dimmed">
                    Open field map to configure reusable masking per copybook.
                  </Text>
                </div>
                <Badge variant="light">{copybooks.length}</Badge>
              </Group>
              <CopybookRegistry copybooks={copybooks} canManage={canManage} onEdit={(id) => void beginEditCopybook(id)} onMap={(id) => void selectMapCopybook(String(id))} onDelete={(copybook) => void removeCopybook(copybook)} />
            </Paper>
          </section>

          <Paper className="forge-card mf-panel" p="md" mt="md">
            <Group justify="space-between" align="flex-start">
              <div>
                <Text fw={820}>Field map {selectedMapCopybook ? `- ${selectedMapCopybook.name}` : ''}</Text>
                <Text size="sm" c="dimmed">
                  Set NONE to leave a field unmasked. Saved maps are applied by every mainframe masking job using this copybook.
                </Text>
              </div>
              <Group gap="xs">
                <Select
                  data={copybookOptions}
                  placeholder="Select copybook"
                  value={effectiveMapCopybookId ? String(effectiveMapCopybookId) : null}
                  onChange={(value) => void selectMapCopybook(value)}
                />
                <Button size="xs" loading={saveMapMutation.isPending} disabled={!canManage || !effectiveMapCopybookId || !mapFieldsQuery.data?.length || !mapDirty} onClick={() => saveMapMutation.mutate()}>
                  Save field map
                </Button>
              </Group>
            </Group>
            <FieldMapTable fields={mapFieldsQuery.data || []} drafts={mapDrafts} functions={functions} scripts={scripts} editable={canManage} updateDraft={updateMapDraft} />
          </Paper>
        </Tabs.Panel>

        <Tabs.Panel value="jobs" pt="md">
          <section className="mf-job-grid">
            <Paper className="forge-card mf-panel" p="md">
              <Text fw={820}>Launch masking job</Text>
              <Text size="sm" c="dimmed">
                Each source file can use its own copybook and target override.
              </Text>
              <SimpleGrid cols={{ base: 1, md: 4 }} spacing="sm" mt="md">
                <NameInput label="Job name" value={jobDraft.name} onChange={(value) => setJobDraft({ ...jobDraft, name: value })} />
                <Select label="Source LPAR" data={connectionOptions} value={effectiveJobSourceId} onChange={(value) => setJobDraft({ ...jobDraft, sourceConnectionId: value })} />
                <Select label="Target LPAR" data={connectionOptions} value={effectiveJobTargetId} onChange={(value) => setJobDraft({ ...jobDraft, targetConnectionId: value })} />
                <TextInput {...technicalInputProps} label="Masking seed" placeholder="blank = default" value={jobDraft.maskingSeed} onChange={(event) => setJobDraft({ ...jobDraft, maskingSeed: safeInputValue(event) })} />
              </SimpleGrid>

              <Group justify="space-between" mt="md">
                <Text fw={760}>Files</Text>
                <Group gap="xs">
                  <Button size="xs" variant="default" leftSection={<IconFolderSearch size={15} />} onClick={browseSourceFiles}>
                    Browse source
                  </Button>
                  <Button size="xs" variant="default" onClick={() => setJobFiles((current) => [...current, newJobFile()])}>
                    Add file
                  </Button>
                </Group>
              </Group>
              <JobFileEditor rows={jobFiles} copybookOptions={copybookOptions} targetOptions={targetOptions} update={updateJobFile} remove={removeJobFile} />
              <Group justify="flex-end" mt="md">
                <Button leftSection={<IconPlayerPlay size={16} />} loading={launchJobMutation.isPending} disabled={!canManage || !jobReady} onClick={() => launchJobMutation.mutate()}>
                  Launch masking job
                </Button>
              </Group>
            </Paper>

            <Paper className="forge-card mf-panel" p="md">
              <Group justify="space-between">
                <div>
                  <Text fw={820}>Jobs</Text>
                  <Text size="sm" c="dimmed">
                    File and record counters refresh automatically.
                  </Text>
                </div>
                <Badge variant="light">{jobs.length}</Badge>
              </Group>
              <JobsTable jobs={jobs} onDetail={setSelectedJobId} />
            </Paper>
          </section>

          <Paper className="forge-card mf-panel" p="md" mt="md">
            <Group justify="space-between">
              <div>
                <Text fw={820}>Job detail</Text>
                <Text size="sm" c="dimmed">
                  Select a job to inspect file-level status and backend messages.
                </Text>
              </div>
              {jobDetailQuery.data?.job ? <StatusBadge status={jobDetailQuery.data.job.status} /> : null}
            </Group>
            <JobDetailPanel detail={jobDetailQuery.data} connections={connections} />
          </Paper>
        </Tabs.Panel>
      </Tabs>

      <Modal opened={browseOpen} onClose={() => setBrowseOpen(false)} title="Browse source files" size="xl" centered>
        <Stack gap="sm">
          <Alert color="blue" variant="light">
            Listing uses the selected source connection. For Zowe this calls z/OSMF dataset listing.
          </Alert>
          <BrowseFilesTable files={browseFiles} onAdd={addFromBrowse} />
        </Stack>
      </Modal>
    </main>
  );
}

function ConnectionsTable({
  connections,
  canManage,
  testStates,
  onTest,
  onEdit,
  onDelete
}: {
  connections: MainframeConnection[];
  canManage: boolean;
  testStates: Record<number, TestState>;
  onTest: (connection: MainframeConnection) => void;
  onEdit: (connection: MainframeConnection) => void;
  onDelete: (connection: MainframeConnection) => void;
}) {
  if (!connections.length) return <EmptyState title="No connections yet" detail="Add a LOCAL landing folder or Zowe endpoint to start." />;
  return (
    <div className="mf-table-wrap">
      <Table highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Name</Table.Th>
            <Table.Th>Type</Table.Th>
            <Table.Th>Endpoint</Table.Th>
            <Table.Th>Code page</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {connections.map((connection) => {
            const state = testStates[connection.id];
            return (
              <Table.Tr key={connection.id}>
                <Table.Td>
                  <Text fw={760}>{connection.name}</Text>
                </Table.Td>
                <Table.Td>
                  <Badge variant="light">{connection.type}</Badge>
                </Table.Td>
                <Table.Td className="mf-mono-muted">{connection.type === 'ZOWE' ? `${connection.host || '-'}:${connection.port || 443}${connection.basePath || '/zosmf'}` : connection.baseDir || '-'}</Table.Td>
                <Table.Td>{connection.codePage || 'Cp037'}</Table.Td>
                <Table.Td>
                  {state ? (
                    <div>
                      <StatusBadge status={state.status === 'ok' ? 'OK' : state.status === 'failed' ? 'FAILED' : 'TESTING'} />
                      <Text size="xs" c="dimmed" mt={3}>
                        {state.message}
                      </Text>
                    </div>
                  ) : (
                    <Text size="xs" c="dimmed">
                      Not tested
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  {canManage ? <Group gap={4} justify="flex-end">
                    <TinyButton loading={state?.status === 'testing'} onClick={() => onTest(connection)}>
                      Test
                    </TinyButton>
                    <TinyButton variant="subtle" onClick={() => onEdit(connection)}>
                      Edit
                    </TinyButton>
                    <Tooltip label="Delete">
                      <ActionIcon variant="subtle" color="red" aria-label={`Delete ${connection.name}`} onClick={() => onDelete(connection)}>
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Tooltip>
                  </Group> : null}
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function CopybookRegistry({
  copybooks,
  canManage,
  onEdit,
  onMap,
  onDelete
}: {
  copybooks: CopybookSummary[];
  canManage: boolean;
  onEdit: (id: number) => void;
  onMap: (id: number) => void;
  onDelete: (copybook: CopybookSummary) => void;
}) {
  if (!copybooks.length) return <EmptyState title="No copybooks yet" detail="Save a copybook to make it available to masking and generation jobs." />;
  return (
    <div className="mf-table-wrap">
      <Table highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Name</Table.Th>
            <Table.Th>Record</Table.Th>
            <Table.Th>Length</Table.Th>
            <Table.Th>Code page</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {copybooks.map((copybook) => (
            <Table.Tr key={copybook.id}>
              <Table.Td>
                <Text fw={760}>{copybook.name}</Text>
              </Table.Td>
              <Table.Td>{copybook.recordName || '-'}</Table.Td>
              <Table.Td>{copybook.recordLength || '-'}</Table.Td>
              <Table.Td>{copybook.codePage || 'Cp037'}</Table.Td>
              <Table.Td>
                <Group gap={4} justify="flex-end">
                  {canManage ? <TinyButton onClick={() => onEdit(copybook.id)}>Edit</TinyButton> : null}
                  <TinyButton onClick={() => onMap(copybook.id)}>Field map</TinyButton>
                  {canManage ? <Tooltip label="Delete">
                    <ActionIcon variant="subtle" color="red" aria-label={`Delete ${copybook.name}`} onClick={() => onDelete(copybook)}>
                      <IconTrash size={16} />
                    </ActionIcon>
                  </Tooltip> : null}
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function FieldMapTable({
  fields,
  drafts,
  functions,
  scripts,
  editable,
  updateDraft
}: {
  fields: CopybookField[];
  drafts: Record<string, MaskDraft>;
  functions: string[];
  scripts: MaskingScript[];
  editable: boolean;
  updateDraft: (path: string, patch: Partial<MaskDraft>) => void;
}) {
  if (!fields.length) return <EmptyState title="No field map open" detail="Select a copybook to configure field-level masking." />;
  return (
    <div className="mf-table-wrap mf-field-map">
      <Table highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Field</Table.Th>
            <Table.Th>Type</Table.Th>
            <Table.Th>Off</Table.Th>
            <Table.Th>Len</Table.Th>
            <Table.Th>Function</Table.Th>
            <Table.Th>Param 1</Table.Th>
            <Table.Th>Param 2</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {fields.map((field) => {
            const draft = drafts[field.path] || { function: 'NONE', param1: '', param2: '' };
            return (
              <Table.Tr key={field.path}>
                <Table.Td>
                  <Text fw={760} className="mf-mono-line">
                    {field.path}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <code>{field.type}</code>
                </Table.Td>
                <Table.Td>{field.offset}</Table.Td>
                <Table.Td>{field.length}</Table.Td>
                <Table.Td>
                  <Select
                    size="xs"
                    disabled={!editable}
                    data={maskFunctionOptions(functions, true)}
                    value={draft.function}
                    onChange={(value) => updateDraft(field.path, { function: value || 'NONE', param1: '', param2: '' })}
                  />
                </Table.Td>
                <Table.Td>
                  <ParamInput fn={draft.function} index={1} value={draft.param1} scripts={scripts} disabled={!editable} onChange={(value) => updateDraft(field.path, { param1: value })} />
                </Table.Td>
                <Table.Td>
                  <ParamInput fn={draft.function} index={2} value={draft.param2} scripts={scripts} disabled={!editable} onChange={(value) => updateDraft(field.path, { param2: value })} />
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function JobFileEditor({
  rows,
  copybookOptions,
  targetOptions,
  update,
  remove
}: {
  rows: JobFileDraft[];
  copybookOptions: Array<{ value: string; label: string }>;
  targetOptions: Array<{ value: string; label: string }>;
  update: (uid: string, patch: Partial<JobFileDraft>) => void;
  remove: (uid: string) => void;
}) {
  return (
    <div className="mf-job-file-list">
      {rows.map((row) => (
        <div key={row.uid} className="mf-job-file-row">
          <TextInput {...technicalInputProps} label="Source dataset/file" value={row.sourceName} onChange={(event) => update(row.uid, { sourceName: safeInputValue(event) })} />
          <Select label="Copybook" data={copybookOptions} value={row.copybookId} onChange={(value) => update(row.uid, { copybookId: value })} />
          <Select label="RECFM" data={['FB', 'VB']} value={row.recfm} onChange={(value) => update(row.uid, { recfm: value === 'VB' ? 'VB' : 'FB' })} />
          <TextInput {...technicalInputProps} label="LRECL" placeholder="auto" value={row.lrecl} onChange={(event) => update(row.uid, { lrecl: safeInputValue(event) })} />
          <TextInput {...technicalInputProps} label="Code page" placeholder="inherit" value={row.codePage} onChange={(event) => update(row.uid, { codePage: safeInputValue(event) })} />
          <Select label="Target LPAR" data={targetOptions} value={row.targetConnectionId || JOB_TARGET} onChange={(value) => update(row.uid, { targetConnectionId: value || JOB_TARGET })} />
          <TextInput {...technicalInputProps} label="Target name" placeholder="same as source" value={row.targetName} onChange={(event) => update(row.uid, { targetName: safeInputValue(event) })} />
            <ActionIcon className="mf-row-delete" color="red" variant="subtle" aria-label={`Remove ${row.sourceName || 'file row'}`} onClick={() => remove(row.uid)}>
            <IconTrash size={16} />
          </ActionIcon>
        </div>
      ))}
    </div>
  );
}

function JobsTable({ jobs, onDetail }: { jobs: MainframeJob[]; onDetail: (id: number) => void }) {
  if (!jobs.length) return <EmptyState title="No jobs yet" detail="Launch a masking job to see progress and file outcomes." />;
  return (
    <div className="mf-table-wrap">
      <Table highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Job</Table.Th>
            <Table.Th>Status</Table.Th>
            <Table.Th>Progress</Table.Th>
            <Table.Th>Records</Table.Th>
            <Table.Th>Created</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {jobs.map((job) => (
            <Table.Tr key={job.id}>
              <Table.Td>
                <Text fw={760}>{job.name}</Text>
              </Table.Td>
              <Table.Td>
                <StatusBadge status={job.status} />
              </Table.Td>
              <Table.Td>
                <JobProgress job={job} />
              </Table.Td>
              <Table.Td>{Number(job.recordsProcessed || 0).toLocaleString()}</Table.Td>
              <Table.Td>{formatDate(job.createdAt)}</Table.Td>
              <Table.Td>
                <TinyButton onClick={() => onDetail(job.id)}>Details</TinyButton>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function JobDetailPanel({
  detail,
  connections
}: {
  detail?: { job?: { name?: string; status?: string | null; message?: string | null; sourceConnectionId?: number | null; targetConnectionId?: number | null }; files?: MainframeJobFile[] };
  connections: MainframeConnection[];
}) {
  if (!detail?.job) return <EmptyState title="No job selected" detail="Open Details from the job list to inspect file status." />;
  return (
    <Stack gap="sm" mt="md">
      <Group gap="lg">
        <Text size="sm">
          Source: <ConnectionName id={detail.job.sourceConnectionId} connections={connections} />
        </Text>
        <Text size="sm">
          Target: <ConnectionName id={detail.job.targetConnectionId} connections={connections} />
        </Text>
      </Group>
      {detail.job.message ? (
        <Alert color={String(detail.job.status).toUpperCase() === 'FAILED' ? 'red' : 'blue'} variant="light">
          {detail.job.message}
        </Alert>
      ) : null}
      <div className="mf-table-wrap">
        <Table highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Source</Table.Th>
              <Table.Th>RECFM</Table.Th>
              <Table.Th>Records</Table.Th>
              <Table.Th>Status</Table.Th>
              <Table.Th>Target</Table.Th>
              <Table.Th>Message</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(detail.files || []).map((file) => (
              <Table.Tr key={file.id || file.sourceName}>
                <Table.Td className="mf-mono-muted">{file.sourceName}</Table.Td>
                <Table.Td>{file.recfm || 'FB'}</Table.Td>
                <Table.Td>{Number(file.recordCount || 0).toLocaleString()}</Table.Td>
                <Table.Td>
                  <StatusBadge status={file.status} />
                </Table.Td>
                <Table.Td className="mf-mono-muted">{file.targetName || file.sourceName}</Table.Td>
                <Table.Td>{file.message || '-'}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </div>
    </Stack>
  );
}

function BrowseFilesTable({ files, onAdd }: { files: MainframeFileInfo[]; onAdd: (file: MainframeFileInfo) => void }) {
  if (!files.length) return <EmptyState title="No files listed" detail="The selected connection did not return files, or the listing is still loading." />;
  return (
    <div className="mf-table-wrap">
      <Table highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Name</Table.Th>
            <Table.Th>RECFM</Table.Th>
            <Table.Th>LRECL</Table.Th>
            <Table.Th>Bytes</Table.Th>
            <Table.Th />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {files.map((file) => (
            <Table.Tr key={file.name}>
              <Table.Td className="mf-mono-muted">{file.name}</Table.Td>
              <Table.Td>{file.recfm || '-'}</Table.Td>
              <Table.Td>{file.lrecl || '-'}</Table.Td>
              <Table.Td>{file.sizeBytes || '-'}</Table.Td>
              <Table.Td>
                <TinyButton onClick={() => onAdd(file)}>Add</TinyButton>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function buildMapDrafts(fields: CopybookField[], masks: CopybookMask[]) {
  const byPath = new Map(masks.map((mask) => [mask.fieldPath.toUpperCase(), mask]));
  const drafts: Record<string, MaskDraft> = {};
  for (const field of fields) {
    const saved = byPath.get(field.path.toUpperCase());
    drafts[field.path] = {
      enabled: true,
      function: saved?.function || guessRegistryMaskFunction(field.path),
      param1: saved?.param1 || '',
      param2: saved?.param2 || ''
    };
  }
  return drafts;
}

function connectionPayload(draft: ConnDraft) {
  return draft.type === 'LOCAL'
    ? {
        name: draft.name,
        type: 'LOCAL',
        codePage: draft.codePage || 'Cp037',
        baseDir: draft.baseDir
      }
    : {
        name: draft.name,
        type: 'ZOWE',
        codePage: draft.codePage || 'Cp037',
        host: draft.host,
        port: numberOrNull(draft.port),
        basePath: draft.basePath || '/zosmf',
        username: draft.username,
        password: draft.password,
        trustAllCerts: draft.trustAllCerts
      };
}

function newJobFile(patch: Partial<JobFileDraft> = {}): JobFileDraft {
  return {
    uid: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
    sourceName: '',
    copybookId: null,
    recfm: 'FB',
    lrecl: '',
    codePage: '',
    targetConnectionId: JOB_TARGET,
    targetName: '',
    ...patch
  };
}
