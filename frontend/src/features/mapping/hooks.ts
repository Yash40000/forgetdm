'use client';

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { MappingAsset, MappingEntity, MappingPlan, MappingRun } from './types';

export function useMappings() {
  return useQuery({ queryKey: keys.mappings.all, queryFn: () => apiFetch<MappingEntity[]>('/api/mappings') });
}
export function useMappingAssets() {
  return useQuery({ queryKey: keys.mappings.assets, queryFn: () => apiFetch<MappingAsset[]>('/api/mappings/assets') });
}
export function useMappingRuns() {
  return useQuery({ queryKey: keys.mappings.runs, queryFn: () => apiFetch<MappingRun[]>('/api/mappings/runs'), refetchInterval: 2000 });
}
export function useMappingPlan(id: number | null) {
  return useQuery({ queryKey: keys.mappings.plan(id), queryFn: () => apiFetch<MappingPlan>(`/api/mappings/${id}/plan`), enabled: !!id });
}
