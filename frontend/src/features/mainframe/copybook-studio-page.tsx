'use client';

import { useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Card,
  Checkbox,
  FileInput,
  Group,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Textarea
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconFileSearch, IconPlayerPlay, IconRefresh, IconShieldCheck, IconUpload } from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';

import { apiFormPost, apiPost } from '@/lib/api';
import { QueryErrorBanner } from '@/components/query-error-banner';
import type { MaskingScript } from '@/lib/types';
import { useMaskingFunctions, useMaskingScripts } from '@/features/masking/hooks';
import {
  EmptyState,
  FieldTable,
  MainframeHeader,
  MainframeMetric,
  ParamInput,
  maskFunctionOptions,
  maskPayloadFromDrafts
} from './components';
import type {
  CopybookDecodeResult,
  CopybookFileDecodeResult,
  CopybookMaskPreview,
  CopybookParseResult,
  DecodedField,
  MaskDraft
} from './types';
import {
  COPYBOOK_STUDIO_SAMPLE,
  COPYBOOK_STUDIO_SAMPLE_HEX,
  ensureMaskDrafts,
  errorMessage,
  safeInputValue,
  technicalInputProps
} from './utils';

export function CopybookStudioPage() {
  const functionsQuery = useMaskingFunctions();
  const scriptsQuery = useMaskingScripts();
  const functions = functionsQuery.data || [];
  const scripts = scriptsQuery.data || [];

  const [copybook, setCopybook] = useState(COPYBOOK_STUDIO_SAMPLE);
  const [codePage, setCodePage] = useState('Cp037');
  const [hex, setHex] = useState(COPYBOOK_STUDIO_SAMPLE_HEX);
  const [file, setFile] = useState<File | null>(null);
  const [maxRecords, setMaxRecords] = useState('50');
  const [parseResult, setParseResult] = useState<CopybookParseResult | null>(null);
  const [decodeResult, setDecodeResult] = useState<CopybookDecodeResult | null>(null);
  const [fileResult, setFileResult] = useState<CopybookFileDecodeResult | null>(null);
  const [fileRecordIndex, setFileRecordIndex] = useState<string | null>('0');
  const [currentHex, setCurrentHex] = useState('');
  const [maskDrafts, setMaskDrafts] = useState<Record<string, MaskDraft>>({});
  const [maskPreview, setMaskPreview] = useState<CopybookMaskPreview | null>(null);

  const activeFields = useMemo(() => decodeResult?.fields || [], [decodeResult]);
  const selectedMaskCount = Object.values(maskDrafts).filter((draft) => draft.enabled).length;

  const parseMutation = useMutation({
    mutationFn: () => apiPost<CopybookParseResult>('/api/copybook/parse', { copybook, codePage }),
    onSuccess: (result) => {
      setParseResult(result);
      notifications.show({ color: 'green', title: 'Copybook parsed', message: `${result.record || 'Record'} resolved to ${result.recordLength || 0} bytes.` });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Parse failed', message: errorMessage(error) })
  });

  const decodeMutation = useMutation({
    mutationFn: () => apiPost<CopybookDecodeResult>('/api/copybook/decode', { copybook, codePage, hex }),
    onSuccess: (result) => {
      setDecodeResult(result);
      setCurrentHex(hex);
      setMaskPreview(null);
      setMaskDrafts((current) => ensureMaskDrafts(result.fields || [], current, false));
      notifications.show({ color: 'green', title: 'Record decoded', message: `${result.fields?.length || 0} fields are ready for preview.` });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Decode failed', message: errorMessage(error) })
  });

  const fileDecodeMutation = useMutation({
    mutationFn: () => decodeFile(copybook, codePage, file, maxRecords),
    onSuccess: (result) => {
      setFileResult(result);
      const first = result.records?.[0] || null;
      setFileRecordIndex(first ? String(first.index) : '0');
      if (first) applyFileRecord(first.hex, first.fields || []);
      notifications.show({ color: 'green', title: 'File decoded', message: `${result.recordCount || 0} records found; showing ${result.shown || 0}.` });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'File decode failed', message: errorMessage(error) })
  });

  const maskMutation = useMutation({
    mutationFn: () =>
      apiPost<CopybookMaskPreview>('/api/copybook/mask-preview', {
        copybook,
        codePage,
        hex: currentHex,
        masks: maskPayloadFromDrafts(maskDrafts, true)
      }),
    onSuccess: (result) => {
      setMaskPreview(result);
      notifications.show({ color: 'green', title: 'Mask preview complete', message: `${result.bytesChanged || 0} byte(s) changed.` });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Mask preview failed', message: errorMessage(error) })
  });

  const loadSample = () => {
    setCopybook(COPYBOOK_STUDIO_SAMPLE);
    setHex(COPYBOOK_STUDIO_SAMPLE_HEX);
    setCodePage('Cp037');
    setParseResult(null);
    setDecodeResult(null);
    setMaskPreview(null);
    setCurrentHex('');
    setMaskDrafts({});
    setFile(null);
    setFileResult(null);
  };

  const updateCopybookSource = (value: string) => {
    setCopybook(value);
    setParseResult(null);
    setDecodeResult(null);
    setFileResult(null);
    setMaskPreview(null);
    setCurrentHex('');
    setMaskDrafts({});
  };

  const applyFileRecord = (recordHex: string, fields: DecodedField[]) => {
    setCurrentHex(recordHex);
    setHex(recordHex);
    setDecodeResult({ recordLength: recordHex.length / 2, byteLength: recordHex.length / 2, fields });
    setMaskPreview(null);
    setMaskDrafts((current) => ensureMaskDrafts(fields, current, false));
  };

  const selectFileRecord = (value: string | null) => {
    setFileRecordIndex(value || '0');
    const record = fileResult?.records?.find((item) => String(item.index) === value) || fileResult?.records?.[0];
    if (record) applyFileRecord(record.hex, record.fields || []);
  };

  const updateMask = (path: string, patch: Partial<MaskDraft>) => {
    setMaskDrafts((current) => {
      const base = current[path] || {
        enabled: false,
        function: 'FORMAT_PRESERVE',
        param1: '',
        param2: ''
      };
      return {
        ...current,
        [path]: {
          ...base,
          ...patch
        }
      };
    });
  };

  const checkAll = (enabled: boolean) => {
    setMaskDrafts((current) =>
      Object.fromEntries(
        activeFields.map((field) => [
          field.path,
          {
            enabled,
            function: current[field.path]?.function || 'FORMAT_PRESERVE',
            param1: current[field.path]?.param1 || '',
            param2: current[field.path]?.param2 || ''
          }
        ])
      )
    );
  };

  return (
    <main className="forge-page mf-page copybook-studio-page">
      <MainframeHeader
        eyebrow="Mainframe"
        title="Copybook Studio"
        description="Parse COBOL copybooks, decode EBCDIC/COMP-3 records, inspect byte positions, and preview deterministic masking before touching a mainframe file."
      />

      <QueryErrorBanner
        errors={[functionsQuery.error, scriptsQuery.error]}
        onRetry={() => Promise.all([functionsQuery.refetch(), scriptsQuery.refetch()])}
        title="Copybook Studio could not load masking catalogs"
      />

      <SimpleGrid cols={{ base: 1, md: 3 }} spacing="sm">
        <MainframeMetric label="Record" value={parseResult?.record || '-'} detail="Primary level-01 record" />
        <MainframeMetric label="Length" value={parseResult?.recordLength || '-'} detail="Resolved fixed length bytes" />
        <MainframeMetric label="Mask scope" value={selectedMaskCount} detail="Checked fields in preview grid" />
      </SimpleGrid>

      <section className="mf-workspace">
        <Paper className="forge-card mf-panel mf-editor-panel" p="md">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={820}>Copybook source</Text>
              <Text size="sm" c="dimmed">
                Paste COBOL data description entries. The parser computes the flattened byte layout.
              </Text>
            </div>
            <Badge variant="light">{codePage}</Badge>
          </Group>
          <TextInput
            {...technicalInputProps}
            mt="md"
            label="EBCDIC code page"
            value={codePage}
            onChange={(event) => {
              setCodePage(safeInputValue(event));
              setDecodeResult(null);
              setMaskPreview(null);
              setCurrentHex('');
            }}
          />
          <Textarea
            {...technicalInputProps}
            mt="sm"
            className="mf-code-editor"
            minRows={18}
            value={copybook}
            onChange={(event) => updateCopybookSource(safeInputValue(event))}
          />
          <Group justify="flex-end" gap="xs" mt="sm">
            <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={loadSample}>
              Load sample
            </Button>
            <Button leftSection={<IconFileSearch size={16} />} loading={parseMutation.isPending} onClick={() => parseMutation.mutate()}>
              Parse copybook
            </Button>
          </Group>
        </Paper>

        <Paper className="forge-card mf-panel" p="md">
          <Group justify="space-between">
            <div>
              <Text fw={820}>Resolved layout</Text>
              <Text size="sm" c="dimmed">
                Offsets are zero-based byte positions in the record.
              </Text>
            </div>
            <Badge variant="light">{parseResult?.fields?.length || 0} fields</Badge>
          </Group>
          <div className="mf-layout-box">
            <FieldTable fields={parseResult?.fields || []} />
          </div>
        </Paper>
      </section>

      <Card className="forge-card mf-panel" p="md">
        <Tabs defaultValue="hex" classNames={{ list: 'forge-tabs-list' }}>
          <Tabs.List>
            <Tabs.Tab value="hex" leftSection={<IconPlayerPlay size={15} />}>
              Decode hex
            </Tabs.Tab>
            <Tabs.Tab value="file" leftSection={<IconUpload size={15} />}>
              Decode binary file
            </Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="hex" pt="md">
            <Stack gap="sm">
              <Textarea
                {...technicalInputProps}
                label="Record bytes as hex"
                minRows={4}
                value={hex}
                onChange={(event) => setHex(safeInputValue(event))}
                placeholder="F1F2F3..."
              />
              <Button leftSection={<IconPlayerPlay size={16} />} loading={decodeMutation.isPending} onClick={() => decodeMutation.mutate()}>
                Decode record
              </Button>
            </Stack>
          </Tabs.Panel>
          <Tabs.Panel value="file" pt="md">
            <SimpleGrid cols={{ base: 1, md: 3 }} spacing="sm" className="mf-file-decode-grid">
              <FileInput label="Binary EBCDIC file" value={file} onChange={setFile} placeholder="Choose .dat or unload file" />
              <TextInput {...technicalInputProps} label="Max records to show" value={maxRecords} onChange={(event) => setMaxRecords(safeInputValue(event))} />
              <Button mt={24} leftSection={<IconUpload size={16} />} loading={fileDecodeMutation.isPending} onClick={() => fileDecodeMutation.mutate()}>
                Decode file
              </Button>
            </SimpleGrid>
            {fileResult?.records?.length ? (
              <Group mt="sm" justify="space-between">
                <Text size="sm" c="dimmed">
                  {fileResult.recordCount} records, {fileResult.fileBytes} bytes, showing {fileResult.shown}
                  {fileResult.remainderBytes ? `, ${fileResult.remainderBytes} trailing bytes` : ''}
                </Text>
                <Select
                  label="Record"
                  data={(fileResult.records || []).map((record) => ({ value: String(record.index), label: `Record ${record.index + 1}` }))}
                  value={fileRecordIndex}
                  onChange={selectFileRecord}
                />
              </Group>
            ) : null}
          </Tabs.Panel>
        </Tabs>
      </Card>

      <section className="mf-two-column-wide">
        <Paper className="forge-card mf-panel" p="md">
          <Group justify="space-between">
            <div>
              <Text fw={820}>Decoded record</Text>
              <Text size="sm" c="dimmed">
                Logical values decoded from the selected record bytes.
              </Text>
            </div>
            <Badge variant="light">{activeFields.length} fields</Badge>
          </Group>
          <DecodedFieldsTable fields={activeFields} />
        </Paper>

        <Paper className="forge-card mf-panel" p="md">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={820}>Mask preview</Text>
              <Text size="sm" c="dimmed">
                Check fields to mask. Preview validates each masked value fits the original COBOL field.
              </Text>
            </div>
            <Group gap="xs">
              <Button variant="subtle" size="xs" onClick={() => checkAll(true)}>
                Select all
              </Button>
              <Button variant="subtle" size="xs" onClick={() => checkAll(false)}>
                Clear
              </Button>
              <Button
                size="xs"
                leftSection={<IconShieldCheck size={15} />}
                loading={maskMutation.isPending}
                disabled={!currentHex || !selectedMaskCount}
                onClick={() => maskMutation.mutate()}
              >
                Preview
              </Button>
            </Group>
          </Group>
          <MaskGrid fields={activeFields} drafts={maskDrafts} functions={functions} scripts={scripts} updateMask={updateMask} />
          <MaskPreviewResult result={maskPreview} />
        </Paper>
      </section>
    </main>
  );
}

function DecodedFieldsTable({ fields }: { fields: DecodedField[] }) {
  if (!fields.length) return <EmptyState title="No decoded record" detail="Decode a hex record or upload a binary file to inspect field values." />;
  return (
    <div className="mf-table-wrap">
      <Table highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Field</Table.Th>
            <Table.Th>Off</Table.Th>
            <Table.Th>Len</Table.Th>
            <Table.Th>Type</Table.Th>
            <Table.Th>Value</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {fields.map((field) => (
            <Table.Tr key={`${field.path}-${field.offset}`}>
              <Table.Td>
                <Text fw={760} className="mf-mono-line">
                  {field.path}
                </Text>
              </Table.Td>
              <Table.Td>{field.offset}</Table.Td>
              <Table.Td>{field.length}</Table.Td>
              <Table.Td>
                <code>{field.type}</code>
              </Table.Td>
              <Table.Td>
                <code>{field.value || ''}</code>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function MaskGrid({
  fields,
  drafts,
  functions,
  scripts,
  updateMask
}: {
  fields: DecodedField[];
  drafts: Record<string, MaskDraft>;
  functions: string[];
  scripts: MaskingScript[];
  updateMask: (path: string, patch: Partial<MaskDraft>) => void;
}) {
  if (!fields.length) return <EmptyState title="No preview fields" detail="Decode a record first, then choose field-level masking rules." />;
  return (
    <div className="mf-table-wrap mf-mask-grid">
      <Table highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th />
            <Table.Th>Field</Table.Th>
            <Table.Th>Value</Table.Th>
            <Table.Th>Function</Table.Th>
            <Table.Th>Param 1</Table.Th>
            <Table.Th>Param 2</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {fields.map((field) => {
            const draft = drafts[field.path] || { enabled: false, function: 'FORMAT_PRESERVE', param1: '', param2: '' };
            return (
              <Table.Tr key={field.path}>
                <Table.Td>
                  <Checkbox checked={!!draft.enabled} onChange={(event) => updateMask(field.path, { enabled: event.currentTarget.checked })} />
                </Table.Td>
                <Table.Td>
                  <Text fw={760} className="mf-mono-line">
                    {field.path}
                  </Text>
                  <Text size="xs" c="dimmed">
                    {field.type}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <code>{field.value || ''}</code>
                </Table.Td>
                <Table.Td>
                  <Select
                    size="xs"
                    data={maskFunctionOptions(functions)}
                    value={draft.function}
                    onChange={(value) => updateMask(field.path, { function: value || 'FORMAT_PRESERVE', param1: '', param2: '' })}
                  />
                </Table.Td>
                <Table.Td>
                  <ParamInput fn={draft.function} index={1} value={draft.param1} scripts={scripts} onChange={(value) => updateMask(field.path, { param1: value })} />
                </Table.Td>
                <Table.Td>
                  <ParamInput fn={draft.function} index={2} value={draft.param2} scripts={scripts} onChange={(value) => updateMask(field.path, { param2: value })} />
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function MaskPreviewResult({ result }: { result: CopybookMaskPreview | null }) {
  if (!result) return null;
  return (
    <Stack gap="sm" mt="md">
      <Alert color="blue" variant="light">
        {result.bytesChanged || 0} byte(s) changed across {result.fields?.length || 0} selected field(s).
      </Alert>
      <div className="mf-table-wrap">
        <Table highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Field</Table.Th>
              <Table.Th>Before</Table.Th>
              <Table.Th>After / Error</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {(result.fields || []).map((field) => (
              <Table.Tr key={field.path || ''}>
                <Table.Td>{field.path}</Table.Td>
                <Table.Td>
                  <code>{field.before || ''}</code>
                </Table.Td>
                <Table.Td>{field.error ? <Badge color="red">{field.error}</Badge> : <code>{field.after || ''}</code>}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </div>
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="sm">
        <div className="mf-hex-box">
          <span>Before bytes</span>
          <code>{result.beforeHex}</code>
        </div>
        <div className="mf-hex-box">
          <span>After bytes</span>
          <code>{result.afterHex}</code>
        </div>
      </SimpleGrid>
    </Stack>
  );
}

async function decodeFile(copybook: string, codePage: string, file: File | null, maxRecords: string) {
  if (!file) throw new Error('Choose a binary file first.');
  const form = new FormData();
  form.append('file', file);
  form.append('copybook', copybook);
  form.append('codePage', codePage);
  form.append('maxRecords', maxRecords || '50');
  return apiFormPost<CopybookFileDecodeResult>('/api/copybook/decode-file', form);
}
