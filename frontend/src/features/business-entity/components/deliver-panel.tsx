'use client';

import { useState } from 'react';
import { Badge, Button, Group, NumberInput, Select, SimpleGrid, Stack, Tabs, Text, TextInput } from '@mantine/core';
import { NameInput } from '@/components/name-input';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import type { DataSource } from '@/lib/types';
import type { LooseMap } from '../hooks';
import type { BusinessEntityDetail, CapsuleInstance } from '../types';
import { listOfMaps, num, statusDot, str, technicalInputProps } from '../utils';
import { FlowStudio } from './flow-studio';
import { OperationalPackageList } from './operational-packages';

/** Deliver: turn the entity into test data — issue packages, look-alikes, flows, execution plans, packages. */
export function DeliverPanel({
  detail,
  enterprise,
  flows,
  capsules,
  dataSources,
  onDirtyChange
}: {
  detail: BusinessEntityDetail;
  enterprise: LooseMap;
  flows: LooseMap[];
  capsules: CapsuleInstance[];
  dataSources: DataSource[];
  onDirtyChange?: (dirty: boolean) => void;
}) {
  const queryClient = useQueryClient();
  const entityId = detail.entity.id!;
  const issuePackages = listOfMaps(enterprise, 'issuePackages');
  const lookalikes = listOfMaps(enterprise, 'lookalikeProfiles');
  const executionPlans = listOfMaps(enterprise, 'executionPlans');
  const operationalPackages = listOfMaps(enterprise, 'operationalPackages');

  const [issueForm, setIssueForm] = useState({ issueKey: '', title: '', severity: 'MEDIUM', mode: 'MASKED_SUBSET', target: '' });
  const [lookForm, setLookForm] = useState({ name: '', rows: '1000', privacy: 'NO_RAW_VALUES', objective: '' });
  const [planForm, setPlanForm] = useState({ name: '', operation: 'SUBSET_MASK', mode: 'PLAN_ONLY', source: '', target: '', capsuleId: '' });
  const [launchForm, setLaunchForm] = useState({ planId: '', targetId: '', schema: '', seed: '', loadAction: 'REPLACE', prep: 'DELETE' });
  const [packageForm, setPackageForm] = useState({ planId: '', name: '', targetEnvironment: 'QA' });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: keys.businessEntity.enterprise(entityId) });

  const createIssue = useMutation({
    mutationFn: () => {
      if (!issueForm.issueKey.trim()) throw new Error('Enter the issue key, e.g. INC-12345.');
      return apiPost<LooseMap>(`/api/business-entities/${entityId}/issue-packages`, {
        issueKey: issueForm.issueKey.trim(),
        title: issueForm.title.trim() || null,
        severity: issueForm.severity,
        recreationMode: issueForm.mode,
        targetEnvironment: issueForm.target.trim() || null
      });
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Issue package created', message: issueForm.issueKey.trim() });
      setIssueForm({ issueKey: '', title: '', severity: issueForm.severity, mode: issueForm.mode, target: issueForm.target });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create issue package', message: error.message })
  });

  const createLookalike = useMutation({
    mutationFn: () =>
      apiPost<LooseMap>(`/api/business-entities/${entityId}/lookalike-profiles`, {
        name: lookForm.name.trim() || `${detail.entity.name} look-alike`,
        objective: lookForm.objective.trim() || null,
        privacyMode: lookForm.privacy,
        rowGoal: num(lookForm.rows) || 1000
      }),
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Look-alike plan created', message: 'Metadata-only — no raw values stored.' });
      setLookForm({ name: '', rows: lookForm.rows, privacy: lookForm.privacy, objective: '' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create look-alike plan', message: error.message })
  });

  const createPlan = useMutation({
    mutationFn: () =>
      apiPost<LooseMap>(`/api/business-entities/${entityId}/execution-plans`, {
        name: planForm.name.trim() || `${planForm.operation} ${detail.entity.name}`,
        operationType: planForm.operation,
        mode: planForm.mode,
        sourceEnvironment: planForm.source.trim() || null,
        targetEnvironment: planForm.target.trim() || null,
        capsuleInstanceId: planForm.capsuleId ? Number(planForm.capsuleId) : null
      }),
    onSuccess: async (plan) => {
      notifications.show({
        color: str(plan.status) === 'APPROVED' ? 'green' : 'yellow',
        title: 'Execution plan created',
        message: str(plan.status) === 'APPROVED' ? 'Pre-approved — ready to launch.' : 'Awaiting governance approval before launch.'
      });
      setPlanForm({ ...planForm, name: '' });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create plan', message: error.message })
  });

  const launchPlan = useMutation({
    mutationFn: () => {
      if (!launchForm.planId) throw new Error('Pick the execution plan to launch.');
      return apiPost<LooseMap>(`/api/business-entities/execution-plans/${launchForm.planId}/launch`, {
        targetDataSourceId: launchForm.targetId ? Number(launchForm.targetId) : null,
        targetSchema: launchForm.schema.trim() || null,
        maskingSeed: launchForm.seed.trim() || null,
        loadAction: launchForm.loadAction,
        targetPrep: launchForm.prep,
        executionMode: 'SINGLE'
      });
    },
    onSuccess: async (result) => {
      notifications.show({ color: 'green', title: 'Plan launched', message: `Engine ${str(result.engine, '-')} · run ${str(result.runId ?? result.engineRunId, '-')}` });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Launch failed', message: error.message })
  });

  const createPackage = useMutation({
    mutationFn: () => {
      if (!packageForm.planId) throw new Error('Pick the execution plan to package.');
      return apiPost<LooseMap>(`/api/business-entities/${entityId}/operational-packages`, {
        name: packageForm.name.trim() || 'Scheduler package',
        executionPlanId: Number(packageForm.planId),
        packageType: 'SCHEDULER_RUNNER',
        targetEnvironment: packageForm.targetEnvironment.trim() || null
      });
    },
    onSuccess: async () => {
      notifications.show({ color: 'green', title: 'Operational package created', message: packageForm.name.trim() || 'Scheduler package' });
      setPackageForm({ planId: '', name: '', targetEnvironment: packageForm.targetEnvironment });
      await invalidate();
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Could not create package', message: error.message })
  });

  const planOptions = executionPlans.map((plan) => ({ value: String(plan.id), label: `${str(plan.name)} (${str(plan.status)})` }));

  return (
    <Tabs defaultValue="build" variant="pills" radius="md">
      <Tabs.List mb="sm">
        <Tabs.Tab value="build">Build data</Tabs.Tab>
        <Tabs.Tab value="flow">Flow studio</Tabs.Tab>
        <Tabs.Tab value="run">Run &amp; packages</Tabs.Tab>
      </Tabs.List>

      <Tabs.Panel value="build">
        <Stack gap="lg">
          <div>
            <Text fw={650} size="sm">
              Production issue recreation
            </Text>
            <Text size="xs" c="dimmed" mb="xs">
              Capture the defect context as a replayable, privacy-safe package.
            </Text>
            <SimpleGrid cols={{ base: 1, sm: 3, lg: 6 }} mb="xs">
              <TextInput {...technicalInputProps} size="xs" label="Issue key" placeholder="INC-12345" value={issueForm.issueKey} onChange={(e) => setIssueForm({ ...issueForm, issueKey: e.currentTarget.value })} />
              <TextInput size="xs" label="Title" placeholder="Payment fails for active customer" value={issueForm.title} onChange={(e) => setIssueForm({ ...issueForm, title: e.currentTarget.value })} />
              <Select size="xs" label="Severity" data={['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']} value={issueForm.severity} onChange={(value) => setIssueForm({ ...issueForm, severity: value || 'MEDIUM' })} />
              <Select
                size="xs"
                label="Mode"
                data={[
                  { value: 'MASKED_SUBSET', label: 'Masked subset' },
                  { value: 'SYNTHETIC_REPLAY', label: 'Synthetic replay' },
                  { value: 'HYBRID', label: 'Hybrid' }
                ]}
                value={issueForm.mode}
                onChange={(value) => setIssueForm({ ...issueForm, mode: value || 'MASKED_SUBSET' })}
              />
              <TextInput size="xs" label="Target env" placeholder="UAT" value={issueForm.target} onChange={(e) => setIssueForm({ ...issueForm, target: e.currentTarget.value })} />
              <Button size="xs" mt={22} loading={createIssue.isPending} onClick={() => createIssue.mutate()}>
                Create package
              </Button>
            </SimpleGrid>
            {issuePackages.map((pkg) => (
              <LineRow key={str(pkg.id)} status={str(pkg.status)} title={`${str(pkg.issueKey)} — ${str(pkg.title, 'issue package')}`} meta={`${str(pkg.recreationMode)} · ${str(pkg.privacyAction)} · ${str(pkg.approvalStatus)}`} />
            ))}
          </div>

          <div>
            <Text fw={650} size="sm">
              AI-assisted look-alike planning
            </Text>
            <Text size="xs" c="dimmed" mb="xs">
              Metadata-only synthetic plans with the production shape and zero raw values.
            </Text>
            <SimpleGrid cols={{ base: 1, sm: 3, lg: 5 }} mb="xs">
              <NameInput size="xs" label="Name" placeholder={`${detail.entity.name} UAT look-alike`} value={lookForm.name} onChange={(value) => setLookForm({ ...lookForm, name: value })} />
              <NumberInput
                size="xs"
                label="Rows"
                min={1}
                value={lookForm.rows === '' ? '' : Number(lookForm.rows)}
                onChange={(value) => setLookForm({ ...lookForm, rows: value === '' || value === null ? '' : String(value) })}
              />
              <Select
                size="xs"
                label="Privacy"
                data={[
                  { value: 'NO_RAW_VALUES', label: 'No raw values' },
                  { value: 'BANKING_SAFE_PROFILE', label: 'Banking safe profile' }
                ]}
                value={lookForm.privacy}
                onChange={(value) => setLookForm({ ...lookForm, privacy: value || 'NO_RAW_VALUES' })}
              />
              <TextInput size="xs" label="Objective" placeholder="Maintain account/customer shape" value={lookForm.objective} onChange={(e) => setLookForm({ ...lookForm, objective: e.currentTarget.value })} />
              <Button size="xs" mt={22} loading={createLookalike.isPending} onClick={() => createLookalike.mutate()}>
                Create plan
              </Button>
            </SimpleGrid>
            {lookalikes.map((profile) => (
              <LineRow key={str(profile.id)} status={str(profile.status)} title={str(profile.name)} meta={`${str(profile.privacyMode)} · ${str(profile.rowGoal, '0')} rows`} />
            ))}
          </div>
        </Stack>
      </Tabs.Panel>

      <Tabs.Panel value="flow">
        <FlowStudio entityId={entityId} flows={flows} executionPlans={executionPlans} onDirtyChange={onDirtyChange} />
      </Tabs.Panel>

      <Tabs.Panel value="run">
        <Stack gap="lg">
          <div>
            <Text fw={650} size="sm">
              Execution plans
            </Text>
            <Text size="xs" c="dimmed" mb="xs">
              Plan entity-level subset/mask or synthetic execution; launch fans out one run per application slice.
            </Text>
            <SimpleGrid cols={{ base: 1, sm: 3, lg: 7 }} mb="xs">
              <NameInput size="xs" label="Name" placeholder="Customer UAT release" value={planForm.name} onChange={(value) => setPlanForm({ ...planForm, name: value })} />
              <Select size="xs" label="Operation" data={['SUBSET_MASK', 'SYNTHETIC_LOOKALIKE', 'ISSUE_RECREATE']} value={planForm.operation} onChange={(value) => setPlanForm({ ...planForm, operation: value || 'SUBSET_MASK' })} />
              <Select size="xs" label="Mode" data={['PLAN_ONLY', 'APPROVED_RUN_READY']} value={planForm.mode} onChange={(value) => setPlanForm({ ...planForm, mode: value || 'PLAN_ONLY' })} />
              <TextInput size="xs" label="Source env" placeholder="PROD" value={planForm.source} onChange={(e) => setPlanForm({ ...planForm, source: e.currentTarget.value })} />
              <TextInput size="xs" label="Target env" placeholder="UAT" value={planForm.target} onChange={(e) => setPlanForm({ ...planForm, target: e.currentTarget.value })} />
              <Select
                size="xs"
                label="Attach capsule"
                placeholder="Optional"
                clearable
                data={capsules.map((capsule) => ({ value: String(capsule.id), label: capsule.canonicalKey }))}
                value={planForm.capsuleId}
                onChange={(value) => setPlanForm({ ...planForm, capsuleId: value || '' })}
              />
              <Button size="xs" mt={22} loading={createPlan.isPending} onClick={() => createPlan.mutate()}>
                Create plan
              </Button>
            </SimpleGrid>
            {executionPlans.map((plan) => (
              <LineRow
                key={str(plan.id)}
                status={str(plan.status)}
                title={str(plan.name)}
                meta={`${str(plan.operationType)} · ${str(plan.mode)} · target ${str(plan.targetEnvironment, '-')}`}
              />
            ))}
          </div>

          <div>
            <Text fw={650} size="sm">
              Launch
            </Text>
            <SimpleGrid cols={{ base: 1, sm: 3, lg: 7 }} mb="xs">
              <Select size="xs" label="Plan" placeholder="Pick a plan" data={planOptions} value={launchForm.planId} onChange={(value) => setLaunchForm({ ...launchForm, planId: value || '' })} />
              <Select
                size="xs"
                label="Target DB override"
                placeholder="Blueprint target(s)"
                clearable
                data={dataSources.filter((source) => ['TARGET', 'BOTH'].includes(String(source.role || '').toUpperCase())).map((source) => ({ value: String(source.id), label: source.name }))}
                value={launchForm.targetId}
                onChange={(value) => setLaunchForm({ ...launchForm, targetId: value || '' })}
              />
              <TextInput {...technicalInputProps} size="xs" label="Target schema" placeholder="optional" value={launchForm.schema} onChange={(e) => setLaunchForm({ ...launchForm, schema: e.currentTarget.value })} />
              <TextInput {...technicalInputProps} size="xs" label="Seed" placeholder="optional" value={launchForm.seed} onChange={(e) => setLaunchForm({ ...launchForm, seed: e.currentTarget.value })} />
              <Select size="xs" label="Load" data={['REPLACE', 'INSERT', 'INSERT_UPDATE', 'UPDATE', 'TRUNCATE_ONLY']} value={launchForm.loadAction} onChange={(value) => setLaunchForm({ ...launchForm, loadAction: value || 'REPLACE' })} />
              <Select size="xs" label="Prep" data={['DELETE', 'TRUNCATE', 'TRUNCATE_CASCADE', 'NONE']} value={launchForm.prep} onChange={(value) => setLaunchForm({ ...launchForm, prep: value || 'DELETE' })} />
              <Button size="xs" mt={22} loading={launchPlan.isPending} onClick={() => launchPlan.mutate()}>
                Launch
              </Button>
            </SimpleGrid>
          </div>

          <div>
            <Text fw={650} size="sm">
              Operational packages
            </Text>
            <Group gap="xs" align="flex-end" mb="xs">
              <Select size="xs" label="Execution plan" placeholder="Pick a plan" data={planOptions} value={packageForm.planId} onChange={(value) => setPackageForm({ ...packageForm, planId: value || '' })} w={240} />
              <NameInput size="xs" label="Package name" placeholder="Nightly scheduler package" value={packageForm.name} onChange={(value) => setPackageForm({ ...packageForm, name: value })} w={240} />
              <TextInput {...technicalInputProps} size="xs" label="Target environment" placeholder="QA" value={packageForm.targetEnvironment} onChange={(e) => setPackageForm({ ...packageForm, targetEnvironment: e.currentTarget.value })} w={170} />
              <Button size="xs" loading={createPackage.isPending} onClick={() => createPackage.mutate()}>
                Create package
              </Button>
            </Group>
            <OperationalPackageList entityId={entityId} packages={operationalPackages} />
          </div>
        </Stack>
      </Tabs.Panel>
    </Tabs>
  );
}

function LineRow({ status, title, meta }: { status: string; title: string; meta: string }) {
  return (
    <div className="be-line-row">
      <span className="be-dot" style={{ background: statusDot(status) }} aria-hidden />
      <div className="be-line-body">
        <Group gap={6} wrap="nowrap">
          <Text size="sm" fw={600}>
            {title}
          </Text>
          <Badge size="xs" variant="light">
            {status || 'PENDING'}
          </Badge>
        </Group>
        <Text size="xs" c="dimmed">
          {meta}
        </Text>
      </div>
    </div>
  );
}
