'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import type { DataSource } from '@/lib/types';
import type {
  CdcAnchor,
  CdcApplyResult,
  CdcChange,
  CdcPollSummary,
  CdcPreflight,
  CdcStatus,
  StartedOperation,
  VirtOperation
} from './types';

const BASE = '/api/cdc/datasources';

/** Engines with a log-based CDC provider today. */
export function isCdcCapable(ds: DataSource): boolean {
  const kind = (ds.kind || '').toLowerCase();
  return kind.includes('postgres') || kind.includes('oracle');
}

export function useCdcDataSources() {
  return useQuery({
    queryKey: ['cdc', 'datasources'],
    queryFn: async () => (await apiFetch<DataSource[]>('/api/datasources')).filter(isCdcCapable)
  });
}

export function useAllDataSources() {
  return useQuery({
    queryKey: ['cdc', 'all-datasources'],
    queryFn: () => apiFetch<DataSource[]>('/api/datasources')
  });
}

export function useCdcStatus(dataSourceId: number | null) {
  return useQuery({
    queryKey: ['cdc', 'status', dataSourceId],
    enabled: dataSourceId != null,
    queryFn: () => apiFetch<CdcStatus>(`${BASE}/${dataSourceId}/status`),
    refetchInterval: (query) => ((query.state.data as CdcStatus | undefined)?.active ? 5000 : false)
  });
}

export function useCdcPreflight(dataSourceId: number | null) {
  return useQuery({
    queryKey: ['cdc', 'preflight', dataSourceId],
    enabled: dataSourceId != null,
    retry: false,
    queryFn: () => apiFetch<CdcPreflight>(`${BASE}/${dataSourceId}/preflight`)
  });
}

export function useCdcChanges(dataSourceId: number | null, active: boolean) {
  return useQuery({
    queryKey: ['cdc', 'changes', dataSourceId],
    enabled: dataSourceId != null && active,
    queryFn: () => apiFetch<CdcChange[]>(`${BASE}/${dataSourceId}/changes?limit=100`)
  });
}

export function useCdcMutations(dataSourceId: number | null) {
  const qc = useQueryClient();
  const invalidate = () => {
    qc.invalidateQueries({ queryKey: ['cdc', 'status', dataSourceId] });
    qc.invalidateQueries({ queryKey: ['cdc', 'preflight', dataSourceId] });
    qc.invalidateQueries({ queryKey: ['cdc', 'changes', dataSourceId] });
  };

  const enable = useMutation({
    mutationFn: (body: { schema?: string; tables?: string[] }) =>
      apiPost<CdcStatus>(`${BASE}/${dataSourceId}/enable`, body),
    onSuccess: invalidate
  });
  const disable = useMutation({
    mutationFn: () => apiPost<CdcStatus>(`${BASE}/${dataSourceId}/disable`, {}),
    onSuccess: invalidate
  });
  const poll = useMutation({
    mutationFn: () => apiPost<CdcPollSummary>(`${BASE}/${dataSourceId}/poll`, {}),
    onSuccess: invalidate
  });
  const apply = useMutation({
    mutationFn: (body: { targetDataSourceId: number; purge: boolean; throughChangeId?: number }) =>
      apiPost<CdcApplyResult>(`${BASE}/${dataSourceId}/apply`, body),
    onSuccess: invalidate
  });

  return { enable, disable, poll, apply };
}

export function useCdcAnchors(dataSourceId: number | null, active: boolean) {
  return useQuery({
    queryKey: ['cdc', 'anchors', dataSourceId],
    enabled: dataSourceId != null && active,
    queryFn: () => apiFetch<CdcAnchor[]>(`${BASE}/${dataSourceId}/timeflow/anchors`)
  });
}

export function useCdcTimeFlowMutations(dataSourceId: number | null) {
  const qc = useQueryClient();
  const createAnchor = useMutation({
    mutationFn: (body: { name?: string; schemaName?: string }) =>
      apiPost<StartedOperation>(`${BASE}/${dataSourceId}/timeflow/anchors`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['cdc', 'status', dataSourceId] })
  });
  const provision = useMutation({
    mutationFn: (body: {
      anchorSnapshotId: number;
      name: string;
      targetDataSourceId?: number;
      throughChangeId?: number;
      throughTimestamp?: string;
    }) => apiPost<StartedOperation>(`${BASE}/${dataSourceId}/timeflow/vdbs`, body)
  });
  return { createAnchor, provision };
}

export function useCdcOperation(opId: string | null) {
  return useQuery({
    queryKey: ['virtualization', 'operation', opId],
    enabled: Boolean(opId),
    queryFn: () => apiFetch<VirtOperation>(`/api/virtualization/operations/${opId}`),
    refetchInterval: (query) =>
      (query.state.data as VirtOperation | undefined)?.status === 'RUNNING' ? 1000 : false
  });
}

export function useCancelCdcOperation() {
  return useMutation({
    mutationFn: (opId: string) =>
      apiPost<VirtOperation>(`/api/virtualization/operations/${opId}/cancel`, {})
  });
}
