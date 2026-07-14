'use client';

import { useQuery } from '@tanstack/react-query';

import { apiFetch } from '@/lib/api';
import { keys } from '@/lib/keys';
import type {
  ColumnOverride,
  CustomPk,
  DataColumn,
  DataScopeVersion,
  DataSetDefinition,
  DataSource,
  DriftReport,
  MaskingPolicy,
  MaskingRule,
  PiiCoverage,
  ProvisionJob,
  RelationshipInfo,
  SavedDataScopeJob,
  TableProfile,
  UserDefinedRelationship
} from '@/lib/types';
import { columnsPath, schemasPath, tablesPath, type CatalogEntry } from './utils';

/* Shared reference data */

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

export function usePolicyRules(policyId: number | null, enabled = true) {
  return useQuery({
    queryKey: keys.policies.rules(policyId),
    enabled: enabled && !!policyId,
    queryFn: () => apiFetch<MaskingRule[]>(`/api/policies/${policyId}/rules`)
  });
}

/* Catalog browsing */

export function useSchemas(dataSourceId: number | null) {
  return useQuery({
    queryKey: keys.dataSources.schemas(dataSourceId),
    enabled: !!dataSourceId,
    queryFn: () => (dataSourceId ? apiFetch<CatalogEntry[]>(schemasPath(dataSourceId)) : Promise.resolve([]))
  });
}

export function useTables(dataSourceId: number | null, schema: string | null | undefined) {
  return useQuery({
    queryKey: keys.dataSources.tables(dataSourceId, schema),
    enabled: !!dataSourceId,
    queryFn: () => (dataSourceId ? apiFetch<CatalogEntry[]>(tablesPath(dataSourceId, schema)) : Promise.resolve([]))
  });
}

export function useColumns(dataSourceId: number | null, table: string, schema: string | null | undefined, enabled = true) {
  return useQuery({
    queryKey: keys.dataSources.columns(dataSourceId, table, schema),
    enabled: enabled && !!dataSourceId && !!table,
    queryFn: () => apiFetch<DataColumn[]>(columnsPath(dataSourceId, table, schema))
  });
}

/* DataScope entities */

export function useBlueprints() {
  return useQuery({
    queryKey: keys.datascope.blueprints,
    queryFn: () => apiFetch<DataSetDefinition[]>('/api/datasets')
  });
}

export function useSavedJobs() {
  return useQuery({
    queryKey: keys.datascope.savedJobs,
    queryFn: () => apiFetch<SavedDataScopeJob[]>('/api/datascope/saved-jobs')
  });
}

export function useProvisionJobs() {
  return useQuery({
    queryKey: keys.datascope.jobs,
    queryFn: () => apiFetch<ProvisionJob[]>('/api/jobs'),
    refetchInterval: (query) => {
      const rows = query.state.data || [];
      return rows.some((job) => ['PENDING', 'RUNNING', 'CANCEL_REQUESTED', 'AWAITING_APPROVAL'].includes(String(job.status || '').toUpperCase()))
        ? 1500
        : false;
    }
  });
}

export function useProfiles(datasetId: number | null) {
  return useQuery({
    queryKey: keys.datascope.profiles(datasetId),
    enabled: !!datasetId,
    queryFn: () => apiFetch<TableProfile[]>(`/api/datasets/${datasetId}/profiles`)
  });
}

export function usePiiCoverage(datasetId: number | null) {
  return useQuery({
    queryKey: keys.datascope.piiCoverage(datasetId),
    enabled: !!datasetId,
    queryFn: () => apiFetch<PiiCoverage>(`/api/datasets/${datasetId}/pii-coverage`)
  });
}

export function useDrift(datasetId: number | null) {
  return useQuery({
    queryKey: keys.datascope.drift(datasetId),
    enabled: !!datasetId,
    queryFn: () => apiFetch<DriftReport>(`/api/datasets/${datasetId}/drift`)
  });
}

export function useOverrides(datasetId: number | null) {
  return useQuery({
    queryKey: keys.datascope.overrides(datasetId),
    enabled: !!datasetId,
    queryFn: () => apiFetch<ColumnOverride[]>(`/api/datasets/${datasetId}/overrides`)
  });
}

export function useRelationships(datasetId: number | null) {
  return useQuery({
    queryKey: keys.datascope.relationships(datasetId),
    enabled: !!datasetId,
    queryFn: () => apiFetch<RelationshipInfo[]>(`/api/datasets/${datasetId}/relationships`)
  });
}

export function useUserRels(datasetId: number | null) {
  return useQuery({
    queryKey: keys.datascope.userRels(datasetId),
    enabled: !!datasetId,
    queryFn: () => apiFetch<UserDefinedRelationship[]>(`/api/datasets/${datasetId}/user-rels`)
  });
}

export function useCustomPks(datasetId: number | null) {
  return useQuery({
    queryKey: keys.datascope.customPks(datasetId),
    enabled: !!datasetId,
    queryFn: () => apiFetch<CustomPk[]>(`/api/datasets/${datasetId}/custom-pks`)
  });
}

export function useVersions(datasetId: number | null) {
  return useQuery({
    queryKey: keys.datascope.versions(datasetId),
    enabled: !!datasetId,
    queryFn: () => apiFetch<DataScopeVersion[]>(`/api/datasets/${datasetId}/versions`)
  });
}
