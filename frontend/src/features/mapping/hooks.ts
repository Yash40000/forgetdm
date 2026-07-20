'use client';

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { MappingAsset, MappingEntity, MappingPlan, MappingRun } from './types';

export function useMappings(enabled = true) {
  return useQuery({ queryKey: keys.mappings.all, queryFn: () => apiFetch<MappingEntity[]>('/api/mappings'), enabled });
}
export function useMappingAssets(enabled = true) {
  return useQuery({ queryKey: keys.mappings.assets, queryFn: () => apiFetch<MappingAsset[]>('/api/mappings/assets'), enabled });
}
export function useMappingRuns(enabled = true) {
  return useQuery({ queryKey: keys.mappings.runs, queryFn: () => apiFetch<MappingRun[]>('/api/mappings/runs'), enabled, refetchInterval: enabled ? 2000 : false });
}
export function useMappingPlan(id: number | null, enabled = true) {
  return useQuery({ queryKey: keys.mappings.plan(id), queryFn: () => apiFetch<MappingPlan>(`/api/mappings/${id}/plan`), enabled: enabled && !!id });
}
