'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, MaskingPolicy, DataSetDefinition } from '@/lib/types';
import type {
  BeReservation,
  BeSnapshot,
  BusinessEntityDetail,
  BusinessEntitySummary,
  CapsuleDetail,
  CapsuleInstance
} from './types';

export function useBusinessEntities() {
  return useQuery({
    queryKey: keys.businessEntity.all,
    queryFn: () => apiFetch<BusinessEntitySummary[]>('/api/business-entities')
  });
}

export function useBusinessEntityDetail(id: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.detail(id),
    enabled: !!id,
    queryFn: () => apiFetch<BusinessEntityDetail>(`/api/business-entities/${id}`)
  });
}

export function useSnapshots(id: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.snapshots(id),
    enabled: !!id,
    queryFn: () => apiFetch<BeSnapshot[]>(`/api/business-entities/${id}/snapshots`)
  });
}

export function useReservations(id: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.reservations(id),
    enabled: !!id,
    queryFn: () => apiFetch<BeReservation[]>(`/api/business-entities/${id}/reservations`)
  });
}

export function useCapsules(id: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.capsules(id),
    enabled: !!id,
    queryFn: () => apiFetch<CapsuleInstance[]>(`/api/business-entities/${id}/capsules`)
  });
}

export function useCapsuleDetail(instanceId: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.capsuleDetail(instanceId),
    enabled: !!instanceId,
    queryFn: () => apiFetch<CapsuleDetail>(`/api/business-entities/capsules/${instanceId}`)
  });
}

export type LooseMap = Record<string, unknown>;

export function useIdentities(id: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.identities(id),
    enabled: !!id,
    queryFn: () => apiFetch<LooseMap[]>(`/api/business-entities/${id}/identities`)
  });
}

export function useSyncPolicies(id: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.syncPolicies(id),
    enabled: !!id,
    queryFn: () => apiFetch<LooseMap[]>(`/api/business-entities/${id}/sync-policies`)
  });
}

export function useEnterprise(id: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.enterprise(id),
    enabled: !!id,
    queryFn: () => apiFetch<LooseMap>(`/api/business-entities/${id}/enterprise`)
  });
}

export function useFlows(id: number | null) {
  return useQuery({
    queryKey: keys.businessEntity.flows(id),
    enabled: !!id,
    queryFn: () => apiFetch<LooseMap[]>(`/api/business-entities/${id}/flows`)
  });
}

export function useDataSources() {
  return useQuery({
    queryKey: keys.dataSources.all,
    queryFn: () => apiFetch<DataSource[]>('/api/datasources')
  });
}

export function usePolicies() {
  return useQuery({
    queryKey: keys.policies.all,
    queryFn: () => apiFetch<MaskingPolicy[]>('/api/policies')
  });
}

export function useBlueprints() {
  return useQuery({
    queryKey: keys.datascope.blueprints,
    queryFn: () => apiFetch<DataSetDefinition[]>('/api/datasets')
  });
}
