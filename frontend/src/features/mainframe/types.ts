export type MainframeConnection = {
  id: number;
  name: string;
  type: 'LOCAL' | 'ZOWE' | string;
  host?: string | null;
  port?: number | null;
  basePath?: string | null;
  username?: string | null;
  baseDir?: string | null;
  codePage?: string | null;
  trustAllCerts?: boolean;
  createdAt?: string | null;
};

export type CopybookSummary = {
  id: number;
  name: string;
  codePage?: string | null;
  recordName?: string | null;
  recordLength?: number | null;
};

export type CopybookDef = CopybookSummary & {
  source?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
};

export type CopybookField = {
  path: string;
  level?: number | null;
  offset: number;
  length: number;
  type: string;
  picture?: string | null;
  numeric?: boolean;
};

export type DecodedField = CopybookField & {
  value?: string | null;
};

export type CopybookParseResult = {
  record?: string | null;
  recordLength?: number | null;
  fields?: CopybookField[];
};

export type CopybookDecodeResult = {
  recordLength?: number | null;
  byteLength?: number | null;
  fields?: DecodedField[];
};

export type CopybookFileRecord = {
  index: number;
  hex: string;
  fields?: DecodedField[];
};

export type CopybookFileDecodeResult = {
  recordLength?: number | null;
  fileBytes?: number | null;
  recordCount?: number | null;
  shown?: number | null;
  remainderBytes?: number | null;
  records?: CopybookFileRecord[];
};

export type CopybookMask = {
  id?: number | null;
  copybookId?: number | null;
  fieldPath: string;
  function: string;
  param1?: string | null;
  param2?: string | null;
};

export type CopybookMaskPreview = {
  beforeHex?: string | null;
  afterHex?: string | null;
  bytesChanged?: number | null;
  fields?: Array<{
    path?: string | null;
    before?: string | null;
    after?: string | null;
    error?: string | null;
  }>;
};

export type MainframeJob = {
  id: number;
  name: string;
  status?: string | null;
  sourceConnectionId?: number | null;
  targetConnectionId?: number | null;
  maskingSeed?: string | null;
  message?: string | null;
  filesTotal?: number | null;
  filesDone?: number | null;
  recordsProcessed?: number | null;
  createdAt?: string | null;
  startedAt?: string | null;
  finishedAt?: string | null;
};

export type MainframeJobFile = {
  id?: number | null;
  jobId?: number | null;
  sourceName: string;
  copybookId?: number | null;
  recfm?: string | null;
  lrecl?: number | null;
  codePage?: string | null;
  targetConnectionId?: number | null;
  targetName?: string | null;
  status?: string | null;
  recordCount?: number | null;
  message?: string | null;
  ordinal?: number | null;
};

export type MainframeJobDetail = {
  job?: MainframeJob;
  files?: MainframeJobFile[];
};

export type MainframeFileInfo = {
  name: string;
  recfm?: string | null;
  lrecl?: number | null;
  sizeBytes?: number | null;
};

export type GeneratorSpec = {
  name?: string | null;
  generator?: string | null;
  category?: string | null;
  description?: string | null;
  param1?: string | null;
  param2?: string | null;
  example?: string | null;
};

export type MfGeneratedFile = {
  recordLength?: number | null;
  rowCount?: number | null;
  recfm?: string | null;
  codePage?: string | null;
  copybookName?: string | null;
  copybook?: string | null;
  preName?: string | null;
  preContent?: string | null;
  postName?: string | null;
  postBase64?: string | null;
  delivered?: {
    connection?: string | null;
    name?: string | null;
    bytes?: number | null;
  } | null;
};

export type MaskDraft = {
  enabled?: boolean;
  function: string;
  param1: string;
  param2: string;
};

export type GeneratorDraft = {
  field: string;
  generator: string;
  param1: string;
  param2: string;
};
