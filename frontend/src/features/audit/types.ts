export type AuditEvent = {
  id: number;
  seq?: number | null;
  actor: string;
  action: string;
  category?: string | null;
  resourceType?: string | null;
  resourceId?: string | null;
  resourceName?: string | null;
  outcome?: string | null;
  severity?: string | null;
  ipAddress?: string | null;
  userAgent?: string | null;
  detail?: string | null;
  metadata?: string | null;
  prevHash?: string | null;
  hash?: string | null;
  createdAt?: string | null;
};

export type AuditSearchResult = {
  events: AuditEvent[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
};

export type AuditFacets = {
  actions: string[];
  categories: string[];
  actors: string[];
  outcomes: string[];
};

export type AuditStats = {
  total: number;
  failures: number;
  categories: number;
  actors: number;
};

export type AuditVerify = {
  valid: boolean;
  total: number;
  hashedCount: number;
  legacyCount: number;
  verifiedThroughSeq: number;
  brokenAtSeq?: number;
};

export type AuditFilters = {
  q?: string;
  category?: string | null;
  action?: string | null;
  actor?: string | null;
  outcome?: string | null;
  from?: string;
  page?: number;
  size?: number;
};
