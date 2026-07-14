'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource, MaskingPolicy } from '@/lib/types';
import type { RunValidationRequest, ValidationDiagnosis, ValidationReport } from './types';

export function useValidationReports() {
  return useQuery({
    queryKey: keys.validation.reports,
    queryFn: () => apiFetch<ValidationReport[]>('/api/validation/reports')
  });
}

export function useValidationDataSources() {
  return useQuery({ queryKey: keys.dataSources.all, queryFn: () => apiFetch<DataSource[]>('/api/datasources') });
}

export function useValidationPolicies() {
  return useQuery({ queryKey: keys.policies.all, queryFn: () => apiFetch<MaskingPolicy[]>('/api/policies') });
}

export function useValidationMutations() {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: keys.validation.reports });

  const run = useMutation({
    mutationFn: (req: RunValidationRequest) => apiPost<ValidationReport>('/api/validation/run', req),
    onSuccess: invalidate
  });
  const diagnose = useMutation({
    mutationFn: (reportId: number) => apiPost<ValidationDiagnosis>(`/api/validation/reports/${reportId}/diagnose`, {})
  });
  const applyFix = useMutation({
    mutationFn: (body: { policyId: number; table: string; column: string; function: string; param1?: string; param2?: string }) =>
      apiPost<{ ok: boolean; ruleId: number; function: string }>('/api/validation/apply-fix', body)
  });

  return { run, diagnose, applyFix };
}
