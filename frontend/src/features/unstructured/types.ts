export type UnstructuredRule = {
  name: string;
  piiType: string;
  pattern: string;
  function: string;
  param1: string;
  param2: string;
  selector: string;
  enabled: boolean;
};

export type UnstructuredProfile = {
  id?: number;
  name: string;
  description?: string | null;
  rulesJson: string;
  status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  versionNo: number;
  createdBy?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type UnstructuredJob = {
  id: number;
  profileId: number;
  profileVersion: number;
  originalFilename: string;
  detectedFormat?: string | null;
  outputStrategy?: string | null;
  status: string;
  stage: string;
  progress: number;
  message?: string | null;
  bytesRead: number;
  charsProcessed: number;
  findingsCount: number;
  findingsJson?: string | null;
  sourceSha256?: string | null;
  outputSha256?: string | null;
  outputName?: string | null;
  errorMessage?: string | null;
  cancelRequested: boolean;
  createdBy: string;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
};

export type UnstructuredCapabilities = {
  nativePreserving: string[];
  safeTextRebuild: string[];
  blockedWithoutExtractor: string[];
  guarantee: string;
};
