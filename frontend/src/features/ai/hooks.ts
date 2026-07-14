'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { AgentEvent, AgentRun, AiStatus, DataStoreDocument, DataStoreStatus } from './types';

export function useAgentRuns() {
  return useQuery({ queryKey: keys.ai.runs, queryFn: () => apiFetch<AgentRun[]>('/api/agent/runs'), refetchInterval: 5000 });
}

export function useAgentEvents(runId: number | null) {
  return useQuery({
    queryKey: keys.ai.events(runId),
    queryFn: () => apiFetch<AgentEvent[]>(`/api/agent/runs/${runId}/events`),
    enabled: Boolean(runId)
  });
}

export function useAiStatus() {
  return useQuery({ queryKey: keys.ai.status, queryFn: () => apiFetch<AiStatus>('/api/ai/status'), staleTime: 30_000 });
}

export function useDataStoreStatus() {
  return useQuery({ queryKey: keys.ai.dataStoreStatus, queryFn: () => apiFetch<DataStoreStatus>('/api/agent/data-store/status') });
}

export function useDataStoreDocuments(query: string, type: string) {
  const params = new URLSearchParams();
  if (query.trim()) params.set('q', query.trim());
  if (type) params.set('type', type);
  params.set('limit', '100');
  return useQuery({
    queryKey: keys.ai.documents(query.trim(), type),
    queryFn: () => apiFetch<DataStoreDocument[]>(`/api/agent/data-store/documents?${params}`)
  });
}
