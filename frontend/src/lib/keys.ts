/**
 * Central query-key factory. Every useQuery/invalidateQueries call goes through
 * these helpers so key shapes can never drift apart between read and invalidation sites.
 */
export const keys = {
  auth: {
    me: ['auth', 'me'] as const
  },
  dataSources: {
    all: ['datasources'] as const,
    schemas: (dataSourceId: number | null | undefined) => ['datasources', dataSourceId, 'schemas'] as const,
    tables: (dataSourceId: number | null | undefined, schema?: string | null) =>
      ['datasources', dataSourceId, 'tables', schema || ''] as const,
    columns: (dataSourceId: number | null | undefined, table: string, schema?: string | null) =>
      ['datascope', 'columns', dataSourceId, schema || '', table] as const
  },
  policies: {
    all: ['policies'] as const,
    rules: (policyId: number | null | undefined) => ['policy-rules', policyId] as const
  },
  datascope: {
    blueprints: ['datascope', 'blueprints'] as const,
    savedJobs: ['datascope', 'saved-jobs'] as const,
    blueprint: (id: number) => ['datascope', id] as const,
    profiles: (id: number | null | undefined) => ['datascope', id, 'profiles'] as const,
    piiCoverage: (id: number | null | undefined) => ['datascope', id, 'pii-coverage'] as const,
    drift: (id: number | null | undefined) => ['datascope', id, 'drift'] as const,
    overrides: (id: number | null | undefined) => ['datascope', id, 'overrides'] as const,
    relationships: (id: number | null | undefined) => ['datascope', id, 'relationships'] as const,
    userRels: (id: number | null | undefined) => ['datascope', id, 'user-rels'] as const,
    customPks: (id: number | null | undefined) => ['datascope', id, 'custom-pks'] as const,
    versions: (id: number | null | undefined) => ['datascope', id, 'versions'] as const
  },
  synthetic: {
    generators: ['synthetic', 'generators'] as const,
    jobs: ['synthetic', 'jobs'] as const,
    job: (id: string | null | undefined) => ['synthetic', 'jobs', id || ''] as const,
    savedJobs: ['synthetic', 'saved-jobs'] as const,
    savedJob: (id: string | null | undefined) => ['synthetic', 'saved-jobs', id || ''] as const,
    planSummary: (fingerprint: string) => ['synthetic', 'plan-summary', fingerprint] as const
  }
};
