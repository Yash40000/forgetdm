export type AgentCondition = {
  field: string;
  operator: string;
  value: string;
  negative: boolean;
};

export type AgentIntent = {
  objective: string;
  capabilities: string[];
  businessEntities: string[];
  sourceHints: string[];
  targetHint?: string | null;
  conditions: AgentCondition[];
  requestedRows?: number | null;
  requestedEntities?: number | null;
  privacyMode: string;
  includeNegativeCases: boolean;
  validations: string[];
  reservationRequired: boolean;
  reservationHours?: number | null;
  deliveryMode: string;
  outputFormat: string;
  assumptions: string[];
  questions: string[];
};

export type AgentEvidence = {
  documentId: number;
  citation: string;
  type: string;
  title: string;
  score: number;
  reason: string;
  metadata?: Record<string, unknown>;
};

export type AgentIssue = {
  severity: 'BLOCKER' | 'WARNING' | 'INFO' | string;
  code: string;
  message: string;
  remediation: string;
};

export type AgentStep = {
  id: number;
  ord: number;
  code: string;
  title: string;
  detail?: string;
  operation: string;
  status: string;
  changesData: boolean;
  requiresApproval: boolean;
  actionName?: string | null;
  actionArgs?: unknown;
  actionSummary?: string | null;
  evidence?: string[];
  result?: unknown;
};

export type AgentRun = {
  id: number;
  goal: string;
  status: string;
  summary?: string;
  provider?: string | null;
  model?: string | null;
  intent: AgentIntent;
  validation: AgentIssue[];
  questions: string[];
  evidence: AgentEvidence[];
  confidence: number;
  riskLevel: string;
  fingerprint: string;
  modelAssisted: boolean;
  createdBy: string;
  approvedBy?: string | null;
  approvedAt?: string | null;
  createdAt: string;
  updatedAt: string;
  canApprovePlan: boolean;
  approvalMessage?: string | null;
  canExecute: boolean;
  steps: AgentStep[];
};

export type AiProvider = {
  id: string;
  label: string;
  model: string;
  configuredModel?: string;
  local: boolean;
  reachable?: boolean;
  autoSelected?: boolean;
  availableModels?: string[];
  detail?: string;
};
export type AiStatus = { enabled: boolean; providers: AiProvider[]; default?: string | null };

export type DataStoreStatus = {
  documents: number;
  types: Array<{ type: string; count: number }>;
  lastSyncedAt?: string | null;
  stale: boolean;
  canManage: boolean;
  privacyBoundary: string;
  latestSync?: {
    id: number;
    status: string;
    documentsWritten: number;
    sourceCounts: Record<string, number>;
    message?: string;
    triggeredBy: string;
    startedAt: string;
    finishedAt?: string;
  } | null;
  warnings?: string[];
};

export type DataStoreDocument = {
  id: number;
  citation: string;
  type: string;
  origin: 'SYSTEM' | 'USER' | string;
  title: string;
  summary?: string | null;
  score: number;
  metadata?: Record<string, unknown>;
  sensitivity: string;
  updatedAt?: string | null;
};

export type AgentEvent = {
  id: number;
  eventType: string;
  actor: string;
  message?: string;
  detail?: string;
  createdAt: string;
};
