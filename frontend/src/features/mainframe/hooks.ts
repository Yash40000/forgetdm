'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type {
  CopybookDef,
  CopybookField,
  CopybookMask,
  CopybookSummary,
  MainframeConnection,
  MainframeJob,
  MainframeJobDetail
} from './types';

export function useMainframeConnections() {
  return useQuery({
    queryKey: keys.mainframe.connections,
    queryFn: () => apiFetch<MainframeConnection[]>('/api/mainframe/connections')
  });
}

export function useMainframeCopybooks() {
  return useQuery({
    queryKey: keys.mainframe.copybooks,
    queryFn: () => apiFetch<CopybookSummary[]>('/api/mainframe/copybooks')
  });
}

export function useMainframeCopybook(id: number | null) {
  return useQuery({
    queryKey: keys.mainframe.copybook(id),
    enabled: !!id,
    queryFn: () => apiFetch<CopybookDef>(`/api/mainframe/copybooks/${id}`)
  });
}

export function useCopybookFields(id: number | null) {
  return useQuery({
    queryKey: keys.mainframe.copybookFields(id),
    enabled: !!id,
    queryFn: () => apiFetch<CopybookField[]>(`/api/mainframe/copybooks/${id}/fields`)
  });
}

export function useCopybookMasks(id: number | null) {
  return useQuery({
    queryKey: keys.mainframe.copybookMasks(id),
    enabled: !!id,
    queryFn: () => apiFetch<CopybookMask[]>(`/api/mainframe/copybooks/${id}/masks`)
  });
}

export function useMainframeJobs() {
  return useQuery({
    queryKey: keys.mainframe.jobs,
    queryFn: () => apiFetch<MainframeJob[]>('/api/mainframe/jobs'),
    refetchInterval: (query) => {
      const rows = query.state.data || [];
      return rows.some((job) => ['PENDING', 'RUNNING'].includes(String(job.status || '').toUpperCase())) ? 1500 : false;
    }
  });
}

export function useMainframeJobDetail(id: number | null) {
  return useQuery({
    queryKey: keys.mainframe.job(id),
    enabled: !!id,
    queryFn: () => apiFetch<MainframeJobDetail>(`/api/mainframe/jobs/${id}`),
    refetchInterval: (query) => {
      const status = String(query.state.data?.job?.status || '').toUpperCase();
      return ['PENDING', 'RUNNING'].includes(status) ? 1500 : false;
    }
  });
}
