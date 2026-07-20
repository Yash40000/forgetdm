'use client';

import { useMemo, useState } from 'react';
import { Badge, Group, Loader, Paper, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';

import { QueryErrorBanner } from '@/components/query-error-banner';
import { usePermissions } from '@/lib/use-permissions';
import { ReportDetail, ReportsList, RunLauncher, Scorecard } from './components';
import { useValidationDataSources, useValidationMutations, useValidationPolicies, useValidationReports } from './hooks';
import type { RunValidationRequest, ValidationDiagnosis, ValidationRemedy, ValidationReport } from './types';
import { parseFindings } from './utils';

export function ValidationPage() {
  const { can } = usePermissions();
  const canRun = can('validation.run');
  const reportsQuery = useValidationReports();
  const dataSourcesQuery = useValidationDataSources();
  const policiesQuery = useValidationPolicies();
  const mutations = useValidationMutations();

  const reports = useMemo(() => reportsQuery.data || [], [reportsQuery.data]);
  const dataSources = useMemo(() => dataSourcesQuery.data || [], [dataSourcesQuery.data]);
  const policies = useMemo(() => policiesQuery.data || [], [policiesQuery.data]);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [diagnosis, setDiagnosis] = useState<ValidationDiagnosis | null>(null);
  const [applyingKey, setApplyingKey] = useState<string | null>(null);

  const dsName = useMemo(() => {
    const map = new Map(dataSources.map((source) => [source.id, source.name]));
    return (id?: number | null) => (id == null ? 'Unknown target' : map.get(id) || `Target #${id}`);
  }, [dataSources]);
  const policyName = useMemo(() => {
    const map = new Map(policies.map((policy) => [policy.id, policy.name]));
    return (id?: number | null) => (id == null ? 'Target default policy' : map.get(id) || `Policy #${id}`);
  }, [policies]);

  const selected = useMemo(
    () => reports.find((report) => report.id === selectedId) || reports[0] || null,
    [reports, selectedId]
  );
  const findings = useMemo(() => parseFindings(selected), [selected]);

  const selectReport = (report: ValidationReport) => {
    setSelectedId(report.id);
    setDiagnosis(null);
  };

  const runValidation = (req: RunValidationRequest) => {
    if (!canRun) return;
    mutations.run.mutate(req, {
      onSuccess: (report) => {
        notifications.show({
          color: report.result === 'FAIL' ? 'red' : report.result === 'WARN' ? 'yellow' : 'green',
          title: `Validation ${report.result}`,
          message: dsName(report.dataSourceId)
        });
        setSelectedId(report.id);
        setDiagnosis(null);
      },
      onError: (error) =>
        notifications.show({ color: 'red', title: 'Validation failed to run', message: error instanceof Error ? error.message : 'Run failed' })
    });
  };

  const diagnose = () => {
    if (!canRun || !selected) return;
    mutations.diagnose.mutate(selected.id, {
      onSuccess: (result) => setDiagnosis(result),
      onError: (error) =>
        notifications.show({ color: 'red', title: 'Diagnosis failed', message: error instanceof Error ? error.message : 'Could not diagnose' })
    });
  };

  const applyFix = (remedy: ValidationRemedy) => {
    if (!canRun || !selected?.policyId || !remedy.table || !remedy.column || !remedy.suggestedFunction) return;
    const key = `${remedy.table}.${remedy.column}`;
    setApplyingKey(key);
    mutations.applyFix.mutate(
      {
        policyId: selected.policyId,
        table: remedy.table,
        column: remedy.column,
        function: remedy.suggestedFunction,
        param1: remedy.suggestedParam1,
        param2: remedy.suggestedParam2
      },
      {
        onSuccess: (res) => {
          notifications.show({ color: 'green', title: 'Fix applied', message: `${key} → ${res.function}. Re-run validation to confirm.` });
          setApplyingKey(null);
        },
        onError: (error) => {
          notifications.show({ color: 'red', title: 'Could not apply fix', message: error instanceof Error ? error.message : 'Apply failed' });
          setApplyingKey(null);
        }
      }
    );
  };

  const loading = reportsQuery.isLoading || dataSourcesQuery.isLoading;

  return (
    <main className="forge-page">
      <Stack gap="lg">
        <div>
          <Badge variant="light" color="blue" mb={8}>
            Quality gate
          </Badge>
          <Title order={1} size="h2">
            Masking Validation
          </Title>
          <Text c="dimmed" size="sm" maw={780}>
            Prove that masked data is actually safe. Each run samples the target and checks for leaked source values, broken
            format contracts, referential-integrity breaks, and deliverable email domains — then AI explains any failure and
            proposes a one-click fix.
          </Text>
        </div>

        <QueryErrorBanner errors={[reportsQuery.error, dataSourcesQuery.error]} onRetry={() => reportsQuery.refetch()} title="Validation could not be loaded" />

        {canRun ? (
          <RunLauncher dataSources={dataSources} policies={policies} onRun={runValidation} running={mutations.run.isPending} />
        ) : null}

        {loading ? (
          <Paper className="forge-card" p="xl">
            <Group justify="center">
              <Loader />
              <Text c="dimmed">Loading validation…</Text>
            </Group>
          </Paper>
        ) : selected ? (
          <>
            <Scorecard report={selected} findings={findings} />
            <div className="validation-workspace">
              <div className="validation-history">
                <Text size="xs" fw={800} tt="uppercase" c="dimmed" mb="xs">
                  Run history
                </Text>
                <ReportsList reports={reports} selectedId={selected.id} onSelect={selectReport} dsName={dsName} policyName={policyName} />
              </div>
              <ReportDetail
                report={selected}
                findings={findings}
                diagnosis={diagnosis}
                onDiagnose={diagnose}
                diagnosing={mutations.diagnose.isPending}
                onApplyFix={applyFix}
                applyingKey={applyingKey}
                canRun={canRun}
                dsName={dsName}
                policyName={policyName}
              />
            </div>
          </>
        ) : (
          <Paper className="forge-card" p="xl">
            <Text c="dimmed" ta="center">
              No validation runs yet — pick a masked target above and run your first check.
            </Text>
          </Paper>
        )}
      </Stack>
    </main>
  );
}
