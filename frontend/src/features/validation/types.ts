export type ValidationReport = {
  id: number;
  jobId?: number | null;
  dataSourceId?: number | null;
  policyId?: number | null;
  result: string; // PASS | WARN | FAIL
  findingsJson: string;
  createdAt?: string | null;
};

export type ValidationFinding = {
  severity: string; // FAIL | WARN | INFO
  check: string; // LEAK | FORMAT | RI | DOMAIN | ALL
  table?: string | null;
  column?: string | null;
  detail?: string | null;
};

export type ValidationRemedy = {
  check?: string;
  table?: string;
  column?: string;
  severity?: string;
  cause?: string;
  fix?: string;
  suggestedFunction?: string;
  suggestedParam1?: string;
  suggestedParam2?: string;
};

export type ValidationDiagnosis = {
  reportId: number;
  policyId?: number | null;
  result?: string;
  remedies: ValidationRemedy[];
};

export type RunValidationRequest = {
  targetId: number;
  policyId?: number | null;
  sourceId?: number | null;
  jobId?: number | null;
};
