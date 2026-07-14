'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource } from '@/lib/types';
import type {
  VirtDocker,
  VirtEngineTest,
  VirtEnvironment,
  VirtOperation,
  VirtPool,
  VirtSnapshot,
  VirtTimeflow,
  VirtVdb,
  VirtZfs
} from './types';

const BASE = '/api/virtualization';

export function useSnapshots() {
  return useQuery({ queryKey: keys.virtualization.snapshots, queryFn: () => apiFetch<VirtSnapshot[]>(`${BASE}/snapshots`) });
}
export function useVdbs() {
  return useQuery({ queryKey: keys.virtualization.vdbs, queryFn: () => apiFetch<VirtVdb[]>(`${BASE}/vdbs`) });
}
export function useTimeflows() {
  return useQuery({ queryKey: keys.virtualization.timeflows, queryFn: () => apiFetch<VirtTimeflow[]>(`${BASE}/timeflows`) });
}
export function useEnvironments() {
  return useQuery({ queryKey: keys.virtualization.environments, queryFn: () => apiFetch<VirtEnvironment[]>(`${BASE}/environments`) });
}
export function usePool() {
  return useQuery({ queryKey: keys.virtualization.pool, queryFn: () => apiFetch<VirtPool>(`${BASE}/pool`) });
}
export function useZfs() {
  return useQuery({ queryKey: keys.virtualization.zfs, queryFn: () => apiFetch<VirtZfs>(`${BASE}/zfs`) });
}
export function useDocker() {
  return useQuery({ queryKey: keys.virtualization.docker, queryFn: () => apiFetch<VirtDocker>(`${BASE}/docker`) });
}

export function useOperations() {
  return useQuery({
    queryKey: keys.virtualization.operations,
    queryFn: () => apiFetch<VirtOperation[]>(`${BASE}/operations`),
    refetchInterval: (query) => {
      const data = query.state.data as VirtOperation[] | undefined;
      return data?.some((op) => op.status === 'RUNNING') ? 2000 : false;
    }
  });
}

export function useVirtDataSources() {
  return useQuery({ queryKey: keys.dataSources.all, queryFn: () => apiFetch<DataSource[]>('/api/datasources') });
}

export function useVirtSchemas(dataSourceId: number | null) {
  return useQuery({
    queryKey: keys.dataSources.schemas(dataSourceId),
    enabled: Boolean(dataSourceId),
    queryFn: () =>
      dataSourceId
        ? apiFetch<Array<Record<string, unknown>>>(`/api/datasources/${dataSourceId}/schemas`)
        : Promise.resolve([] as Array<Record<string, unknown>>)
  });
}

export function useVirtualizationMutations() {
  const queryClient = useQueryClient();
  const invalidateAll = () => {
    for (const key of [
      keys.virtualization.snapshots,
      keys.virtualization.vdbs,
      keys.virtualization.timeflows,
      keys.virtualization.operations,
      keys.virtualization.pool
    ]) {
      queryClient.invalidateQueries({ queryKey: key });
    }
  };

  const captureSnapshot = useMutation({
    mutationFn: (body: { dataSourceId: number; schemaName?: string; name?: string; note?: string; provider?: string }) =>
      apiPost(`${BASE}/snapshots`, body),
    onSuccess: invalidateAll
  });
  const deleteSnapshot = useMutation({
    mutationFn: (id: number) => apiFetch(`${BASE}/snapshots/${id}`, { method: 'DELETE' }),
    onSuccess: invalidateAll
  });
  const provision = useMutation({
    mutationFn: (body: { snapshotId: number; name?: string; targetDataSourceId?: number | null; pointInTime?: string | null; environmentId?: number | null }) =>
      apiPost(`${BASE}/vdbs`, body),
    onSuccess: invalidateAll
  });
  const deleteVdb = useMutation({
    mutationFn: (id: number) => apiFetch(`${BASE}/vdbs/${id}`, { method: 'DELETE' }),
    onSuccess: invalidateAll
  });
  const refresh = useMutation({
    mutationFn: ({ id, snapshotId }: { id: number; snapshotId: number }) => apiPost(`${BASE}/vdbs/${id}/refresh`, { snapshotId }),
    onSuccess: invalidateAll
  });
  const rewind = useMutation({
    mutationFn: ({ id, snapshotId }: { id: number; snapshotId: number }) => apiPost(`${BASE}/vdbs/${id}/rewind`, { snapshotId }),
    onSuccess: invalidateAll
  });
  const snapshotVdb = useMutation({
    mutationFn: ({ id, name, bookmark }: { id: number; name?: string; bookmark?: boolean }) =>
      apiPost(`${BASE}/vdbs/${id}/snapshots`, { name, bookmark }),
    onSuccess: invalidateAll
  });
  const cancelOperation = useMutation({
    mutationFn: (id: string) => apiPost(`${BASE}/operations/${id}/cancel`, {}),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.virtualization.operations })
  });
  const createEnvironment = useMutation({
    mutationFn: (body: Partial<VirtEnvironment>) => apiPost(`${BASE}/environments`, body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.virtualization.environments })
  });
  const deleteEnvironment = useMutation({
    mutationFn: (id: number) => apiFetch(`${BASE}/environments/${id}`, { method: 'DELETE' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.virtualization.environments })
  });
  const engineTest = useMutation({
    mutationFn: () => apiFetch<VirtEngineTest>(`${BASE}/engine-test`)
  });

  return {
    captureSnapshot,
    deleteSnapshot,
    provision,
    deleteVdb,
    refresh,
    rewind,
    snapshotVdb,
    cancelOperation,
    createEnvironment,
    deleteEnvironment,
    engineTest
  };
}
