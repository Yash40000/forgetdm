'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost, apiPut } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { GroupRequest, SecuritySummary, UserRequest } from './types';

export function useSecuritySummary() {
  return useQuery({
    queryKey: keys.security.summary,
    queryFn: () => apiFetch<SecuritySummary>('/api/security/summary')
  });
}

export function useSecurityMutations() {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: keys.security.summary });

  const createUser = useMutation({
    mutationFn: (req: UserRequest) => apiPost('/api/security/users', req),
    onSuccess: invalidate
  });
  const updateUser = useMutation({
    mutationFn: ({ id, req }: { id: number; req: UserRequest }) => apiPut(`/api/security/users/${id}`, req),
    onSuccess: invalidate
  });
  const deleteUser = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/security/users/${id}`, { method: 'DELETE' }),
    onSuccess: invalidate
  });
  const createGroup = useMutation({
    mutationFn: (req: GroupRequest) => apiPost('/api/security/groups', req),
    onSuccess: invalidate
  });
  const updateGroup = useMutation({
    mutationFn: ({ id, req }: { id: number; req: GroupRequest }) => apiPut(`/api/security/groups/${id}`, req),
    onSuccess: invalidate
  });
  const deleteGroup = useMutation({
    mutationFn: (id: number) => apiFetch(`/api/security/groups/${id}`, { method: 'DELETE' }),
    onSuccess: invalidate
  });

  return { createUser, updateUser, deleteUser, createGroup, updateGroup, deleteGroup };
}
