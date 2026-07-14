/**
 * Central query-key factory. Every useQuery/invalidateQueries call goes through
 * these helpers so key shapes can never drift apart between read and invalidation sites.
 */
export const keys = {
  ai: {
    status: ['ai', 'status'] as const,
    runs: ['ai', 'runs'] as const,
    events: (runId: number | null | undefined) => ['ai', 'runs', runId || '', 'events'] as const,
    dataStoreStatus: ['ai', 'data-store', 'status'] as const,
    documents: (query: string, type: string) => ['ai', 'data-store', 'documents', query, type] as const
  },
  auth: {
    me: ['auth', 'me'] as const
  },
  selfService: {
    catalog: ['self-service', 'catalog'] as const,
    requests: ['self-service', 'requests'] as const,
    enterpriseCatalog: ['self-service', 'v2', 'catalog'] as const,
    enterpriseOrders: ['self-service', 'v2', 'orders'] as const,
    enterpriseMetrics: ['self-service', 'v2', 'metrics'] as const,
    enterpriseProducts: ['self-service', 'v2', 'products'] as const,
    enterpriseCandidates: ['self-service', 'v2', 'candidates'] as const
  },
  automation: {
    tokens: ['automation', 'tokens'] as const,
    integrations: ['automation', 'integrations'] as const,
    deliveries: ['automation', 'deliveries'] as const
  },
  security: {
    summary: ['security', 'summary'] as const,
    users: ['security', 'users'] as const,
    groups: ['security', 'groups'] as const,
    roles: ['security', 'roles'] as const
  },
  audit: {
    search: (params: Record<string, unknown>) => ['audit', 'search', params] as const,
    facets: ['audit', 'facets'] as const,
    stats: ['audit', 'stats'] as const,
    verify: ['audit', 'verify'] as const
  },
  validation: {
    reports: ['validation', 'reports'] as const
  },
  dataSources: {
    all: ['datasources'] as const,
    nativeLoaders: ['datasources', 'native-loaders'] as const,
    schemas: (dataSourceId: number | null | undefined) => ['datasources', dataSourceId, 'schemas'] as const,
    tables: (dataSourceId: number | null | undefined, schema?: string | null) =>
      ['datasources', dataSourceId, 'tables', schema || ''] as const,
    columns: (dataSourceId: number | null | undefined, table: string, schema?: string | null) =>
      ['datascope', 'columns', dataSourceId, schema || '', table] as const
  },
  policies: {
    all: ['policies'] as const,
    rules: (policyId: number | null | undefined) => ['policy-rules', policyId] as const,
    functions: ['policies', 'functions'] as const,
    scripts: ['policies', 'scripts'] as const,
    lookupReferences: ['policies', 'lookup-references'] as const,
    discoveryFindings: (dataSourceId: number | null | undefined, schema?: string | null) =>
      ['policies', 'discovery-findings', dataSourceId || '', schema || ''] as const
  },
  mappings: {
    all: ['mappings'] as const,
    assets: ['mappings', 'assets'] as const,
    runs: ['mappings', 'runs'] as const,
    versions: (id: number | null | undefined) => ['mappings', id, 'versions'] as const,
    plan: (id: number | null | undefined) => ['mappings', id, 'plan'] as const
  },
  unstructured: {
    profiles: ['unstructured', 'profiles'] as const,
    jobs: ['unstructured', 'jobs'] as const,
    capabilities: ['unstructured', 'capabilities'] as const
  },
  datascope: {
    blueprints: ['datascope', 'blueprints'] as const,
    savedJobs: ['datascope', 'saved-jobs'] as const,
    jobs: ['datascope', 'jobs'] as const,
    jobRetention: ['datascope', 'jobs', 'retention'] as const,
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
    valueLists: ['synthetic', 'value-lists'] as const,
    jobs: ['synthetic', 'jobs'] as const,
    job: (id: string | null | undefined) => ['synthetic', 'jobs', id || ''] as const,
    savedJobs: ['synthetic', 'saved-jobs'] as const,
    savedJob: (id: string | null | undefined) => ['synthetic', 'saved-jobs', id || ''] as const,
    planSummary: (fingerprint: string) => ['synthetic', 'plan-summary', fingerprint] as const
  },
  discovery: {
    piiTypes: ['discovery', 'pii-types'] as const,
    functions: ['discovery', 'functions'] as const,
    jobs: (dataSourceId?: number | null, schema?: string | null) => ['discovery', 'jobs', dataSourceId || '', schema || ''] as const,
    findings: (dataSourceId?: number | null, schema?: string | null, types?: string) =>
      ['discovery', 'findings', dataSourceId || '', schema || '', types || ''] as const,
    tableColumns: (dataSourceId?: number | null, schema?: string | null, table?: string | null, types?: string) =>
      ['discovery', 'table-columns', dataSourceId || '', schema || '', table || '', types || ''] as const,
    graph: (dataSourceId?: number | null, schema?: string | null, types?: string) =>
      ['discovery', 'graph', dataSourceId || '', schema || '', types || ''] as const,
    patterns: ['discovery', 'patterns'] as const,
    groups: ['discovery', 'pattern-groups'] as const
  },
  businessEntity: {
    all: ['business-entities'] as const,
    detail: (id: number | null | undefined) => ['business-entities', id] as const,
    snapshots: (id: number | null | undefined) => ['business-entities', id, 'snapshots'] as const,
    reservations: (id: number | null | undefined) => ['business-entities', id, 'reservations'] as const,
    capsules: (id: number | null | undefined) => ['business-entities', id, 'capsules'] as const,
    capsuleDetail: (instanceId: number | null | undefined) => ['business-entities', 'capsules', instanceId] as const,
    identities: (id: number | null | undefined) => ['business-entities', id, 'identities'] as const,
    syncPolicies: (id: number | null | undefined) => ['business-entities', id, 'sync-policies'] as const,
    enterprise: (id: number | null | undefined) => ['business-entities', id, 'enterprise'] as const,
    flows: (id: number | null | undefined) => ['business-entities', id, 'flows'] as const,
    flowDebugRuns: (flowId: number | null | undefined) => ['business-entities', 'flows', flowId, 'debug-runs'] as const
  },
  virtualization: {
    snapshots: ['virtualization', 'snapshots'] as const,
    vdbs: ['virtualization', 'vdbs'] as const,
    timeflows: ['virtualization', 'timeflows'] as const,
    environments: ['virtualization', 'environments'] as const,
    operations: ['virtualization', 'operations'] as const,
    pool: ['virtualization', 'pool'] as const,
    zfs: ['virtualization', 'zfs'] as const,
    docker: ['virtualization', 'docker'] as const
  },
  mainframe: {
    connections: ['mainframe', 'connections'] as const,
    connectionFiles: (id: number | null | undefined) => ['mainframe', 'connections', id || '', 'files'] as const,
    copybooks: ['mainframe', 'copybooks'] as const,
    copybook: (id: number | null | undefined) => ['mainframe', 'copybooks', id || ''] as const,
    copybookFields: (id: number | null | undefined) => ['mainframe', 'copybooks', id || '', 'fields'] as const,
    copybookMasks: (id: number | null | undefined) => ['mainframe', 'copybooks', id || '', 'masks'] as const,
    jobs: ['mainframe', 'jobs'] as const,
    job: (id: number | null | undefined) => ['mainframe', 'jobs', id || ''] as const
  }
};
