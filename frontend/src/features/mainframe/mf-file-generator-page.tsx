'use client';

import { useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDownload, IconRefresh, IconRocket, IconSend, IconTableOptions } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiPost } from '@/lib/api';
import { QueryErrorBanner } from '@/components/query-error-banner';
import { NameInput } from '@/components/name-input';
import { keys } from '@/lib/keys';
import { usePermissions } from '@/lib/use-permissions';
import {
  EmptyState,
  MainframeHeader,
  MainframeMetric
} from './components';
import { useCopybookFields, useMainframeConnections, useMainframeCopybooks } from './hooks';
import type { CopybookDef, CopybookField, GeneratorDraft, GeneratorSpec, MfGeneratedFile } from './types';
import {
  FALLBACK_GENERATORS,
  MF_GENERATOR_COPYBOOK_SAMPLE,
  downloadBase64,
  downloadText,
  errorMessage,
  numberOrNull,
  safeInputValue,
  suggestGenerator,
  technicalInputProps
} from './utils';

type CopybookDraft = {
  name: string;
  codePage: string;
  source: string;
};

const emptyCopybookDraft: CopybookDraft = {
  name: 'customer-record',
  codePage: 'Cp037',
  source: MF_GENERATOR_COPYBOOK_SAMPLE
};

export function MfFileGeneratorPage() {
  const queryClient = useQueryClient();
  const { can } = usePermissions();
  const canManage = can('mainframe.manage');
  const copybooksQuery = useMainframeCopybooks();
  const connectionsQuery = useMainframeConnections();
  const generatorsQuery = useQuery({
    queryKey: keys.synthetic.generators,
    queryFn: () => apiFetch<GeneratorSpec[]>('/api/synthetic/generators')
  });

  const copybooks = useMemo(() => copybooksQuery.data || [], [copybooksQuery.data]);
  const connections = useMemo(() => connectionsQuery.data || [], [connectionsQuery.data]);
  const generatorNames = useMemo(() => {
    const names = new Set(FALLBACK_GENERATORS);
    for (const spec of generatorsQuery.data || []) {
      const name = String(spec.name || spec.generator || '').trim().toUpperCase();
      if (name) names.add(name);
    }
    return [...names].sort();
  }, [generatorsQuery.data]);

  const [copybookDraft, setCopybookDraft] = useState<CopybookDraft>(emptyCopybookDraft);
  const [copybookId, setCopybookId] = useState<number | null>(null);
  const [rowCount, setRowCount] = useState('100');
  const [seed, setSeed] = useState('42');
  const [recfm, setRecfm] = useState<'FB' | 'VB'>('FB');
  const [codePage, setCodePage] = useState('');
  const [output, setOutput] = useState<'DOWNLOAD' | 'TARGET'>('DOWNLOAD');
  const [targetConnectionId, setTargetConnectionId] = useState<string | null>(null);
  const [targetName, setTargetName] = useState('');
  const [generatorDrafts, setGeneratorDrafts] = useState<Record<string, GeneratorDraft>>({});
  const [result, setResult] = useState<MfGeneratedFile | null>(null);

  const effectiveCopybookId = copybookId ?? copybooks[0]?.id ?? null;
  const fieldsQuery = useCopybookFields(effectiveCopybookId);
  const selectedCopybook = copybooks.find((copybook) => copybook.id === effectiveCopybookId) || null;
  const effectiveCodePage = codePage || selectedCopybook?.codePage || 'Cp037';
  const fields = useMemo(() => fieldsQuery.data || [], [fieldsQuery.data]);
  const effectiveGeneratorDrafts = useMemo(
    () => Object.fromEntries(fields.map((field) => [field.path, generatorDrafts[field.path] || suggestGenerator(field)])),
    [fields, generatorDrafts]
  );
  const canGenerate = canManage && Boolean(effectiveCopybookId && fields.length && (output !== 'TARGET' || targetConnectionId));

  const saveCopybookMutation = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Mainframe management permission is required');
      return apiPost<CopybookDef>('/api/mainframe/copybooks', copybookDraft);
    },
    onSuccess: (saved) => {
      notifications.show({ color: 'green', title: 'Copybook saved', message: `${saved.name} is ready for file generation.` });
      setCopybookId(saved.id);
      setCodePage(saved.codePage || 'Cp037');
      void queryClient.invalidateQueries({ queryKey: keys.mainframe.copybooks });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Copybook save failed', message: errorMessage(error) })
  });

  const generateMutation = useMutation({
    mutationFn: () => {
      if (!canManage) throw new Error('Mainframe management permission is required');
      const parsedRows = numberOrNull(rowCount);
      if (parsedRows == null || parsedRows < 1 || parsedRows > 200_000) {
        throw new Error('Rows must be between 1 and 200,000 for an interactive mainframe file generation run.');
      }
      if (output === 'TARGET' && !numberOrNull(targetConnectionId)) {
        throw new Error('Select a target LPAR before delivery.');
      }
      return apiPost<MfGeneratedFile>('/api/mainframe/generate-file', {
        copybookId: effectiveCopybookId,
        codePage: effectiveCodePage,
        recfm,
        seed: numberOrNull(seed) ?? 42,
        rowCount: parsedRows,
        columns: Object.values(effectiveGeneratorDrafts).map((draft) => ({
          field: draft.field,
          generator: draft.generator,
          param1: draft.param1 || null,
          param2: draft.param2 || null
        })),
        output,
        targetConnectionId: output === 'TARGET' ? numberOrNull(targetConnectionId) : null,
        targetName: targetName || null
      });
    },
    onSuccess: (generated) => {
      setResult(generated);
      notifications.show({
        color: 'green',
        title: output === 'TARGET' ? 'File generated and delivered' : 'File generated',
        message: `${generated.rowCount || 0} records, ${generated.recordLength || 0} byte LRECL.`
      });
    },
    onError: (error) => notifications.show({ color: 'red', title: 'Generation failed', message: errorMessage(error) })
  });

  const updateGenerator = (field: string, patch: Partial<GeneratorDraft>) => {
    setGeneratorDrafts((current) => {
      const fieldInfo = fields.find((item) => item.path === field);
      const base = current[field] || (fieldInfo ? suggestGenerator(fieldInfo) : {
        field,
        generator: 'ALPHANUMERIC',
        param1: '',
        param2: ''
      });
      return {
        ...current,
        [field]: {
          ...base,
          ...patch
        }
      };
    });
  };

  return (
    <main className="forge-page mf-page mf-generator-page">
      <MainframeHeader
        eyebrow="Mainframe"
        title="MF File Generator"
        description="Generate synthetic rows to a COBOL copybook layout, encode them as EBCDIC FB/VB records, then download artifacts or deliver directly to an LPAR."
        action={
          <Group gap="xs">
            <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={() => void Promise.all([copybooksQuery.refetch(), connectionsQuery.refetch(), generatorsQuery.refetch()])}>
              Refresh
            </Button>
            <Button leftSection={<IconRocket size={16} />} loading={generateMutation.isPending} disabled={!canGenerate} onClick={() => generateMutation.mutate()}>
              Generate file
            </Button>
          </Group>
        }
      />

      <QueryErrorBanner
        errors={[copybooksQuery.error, connectionsQuery.error, generatorsQuery.error, fieldsQuery.error]}
        onRetry={() => Promise.all([copybooksQuery.refetch(), connectionsQuery.refetch(), generatorsQuery.refetch(), fieldsQuery.refetch()])}
        title="MF File Generator could not load all backend data"
      />

      <SimpleGrid cols={{ base: 1, md: 4 }} spacing="sm">
        <MainframeMetric label="Copybook" value={selectedCopybook?.name || '-'} detail="Selected layout" />
        <MainframeMetric label="Fields" value={fieldsQuery.data?.length || 0} detail="Mapped generator columns" />
        <MainframeMetric label="Rows" value={rowCount || '0'} detail="Requested synthetic records" />
        <MainframeMetric label="Output" value={output === 'TARGET' ? 'LPAR' : 'Download'} detail="Delivery mode" />
      </SimpleGrid>

      <section className="mf-two-column">
        <Paper className="forge-card mf-panel" p="md">
          <Group justify="space-between" align="flex-start">
            <div>
              <Text fw={820}>Copybook</Text>
              <Text size="sm" c="dimmed">
                Use a saved copybook or save a new layout for generation.
              </Text>
            </div>
            {selectedCopybook ? <Badge variant="light">{selectedCopybook.recordLength || '?'} bytes</Badge> : null}
          </Group>
          <Select
            mt="md"
            label="Use saved copybook"
            placeholder="Select copybook"
            data={copybooks.map((copybook) => ({ value: String(copybook.id), label: `${copybook.name}${copybook.recordLength ? ` - ${copybook.recordLength} bytes` : ''}` }))}
            value={effectiveCopybookId ? String(effectiveCopybookId) : null}
            onChange={(value) => {
              const nextId = numberOrNull(value);
              const nextCopybook = copybooks.find((item) => item.id === nextId);
              setCopybookId(nextId);
              setCodePage(nextCopybook?.codePage || '');
              setGeneratorDrafts({});
              setResult(null);
            }}
          />
          {selectedCopybook ? (
            <Text mt="xs" size="sm" c="dimmed">
              {selectedCopybook.recordName || '-'} - {selectedCopybook.recordLength || '?'} bytes - {selectedCopybook.codePage || 'Cp037'}
            </Text>
          ) : null}

          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm" mt="md">
            <NameInput label="New copybook name" value={copybookDraft.name} onChange={(value) => setCopybookDraft({ ...copybookDraft, name: value })} />
            <TextInput {...technicalInputProps} label="Code page" value={copybookDraft.codePage} onChange={(event) => setCopybookDraft({ ...copybookDraft, codePage: safeInputValue(event) })} />
          </SimpleGrid>
          <Textarea
            {...technicalInputProps}
            mt="sm"
            className="mf-code-editor"
            minRows={14}
            label="COBOL copybook source"
            value={copybookDraft.source}
            onChange={(event) => setCopybookDraft({ ...copybookDraft, source: safeInputValue(event) })}
          />
          <Group justify="flex-end" mt="md">
            <Button variant="default" onClick={() => setCopybookDraft(emptyCopybookDraft)}>
              Load sample
            </Button>
            <Button loading={saveCopybookMutation.isPending} disabled={!canManage} onClick={() => saveCopybookMutation.mutate()}>
              Save copybook
            </Button>
          </Group>
        </Paper>

        <Paper className="forge-card mf-panel" p="md">
          <Text fw={820}>Run settings</Text>
          <Text size="sm" c="dimmed">
            Generator output is encoded to the selected copybook field widths and storage formats.
          </Text>
          <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="sm" mt="md">
            <TextInput {...technicalInputProps} label="Rows" description="1 to 200,000 records per interactive file" value={rowCount} onChange={(event) => setRowCount(safeInputValue(event))} />
            <TextInput {...technicalInputProps} label="Seed" value={seed} onChange={(event) => setSeed(safeInputValue(event))} />
            <Select label="RECFM" data={['FB', 'VB']} value={recfm} onChange={(value) => setRecfm(value === 'VB' ? 'VB' : 'FB')} />
            <TextInput {...technicalInputProps} label="Code page" value={effectiveCodePage} onChange={(event) => setCodePage(safeInputValue(event))} />
            <Select label="Output" data={[{ value: 'DOWNLOAD', label: 'Download files' }, { value: 'TARGET', label: 'Deliver to LPAR' }]} value={output} onChange={(value) => setOutput(value === 'TARGET' ? 'TARGET' : 'DOWNLOAD')} />
            {output === 'TARGET' ? (
              <Select
                label="Target LPAR"
                data={connections.map((connection) => ({ value: String(connection.id), label: `${connection.name} (${connection.type})` }))}
                value={targetConnectionId}
                onChange={setTargetConnectionId}
              />
            ) : null}
          </SimpleGrid>
          {output === 'TARGET' ? (
            <TextInput {...technicalInputProps} mt="sm" label="Target dataset / name" value={targetName} onChange={(event) => setTargetName(safeInputValue(event))} placeholder="(copybook).dat" />
          ) : null}
          <Alert color="blue" variant="light" mt="md" icon={<IconTableOptions size={16} />}>
            Numeric fields use width-aware defaults so packed/zoned decimal values fit the target field.
          </Alert>
        </Paper>
      </section>

      <Paper className="forge-card mf-panel" p="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={820}>Field generators</Text>
            <Text size="sm" c="dimmed">
              Each elementary copybook field maps to one synthetic generator. Change params where field-specific business shape matters.
            </Text>
          </div>
          <Badge variant="light">{Object.keys(effectiveGeneratorDrafts).length} mapped</Badge>
        </Group>
        <GeneratorMapTable fields={fields} drafts={effectiveGeneratorDrafts} generatorNames={generatorNames} update={updateGenerator} />
      </Paper>

      <Paper className="forge-card mf-panel" p="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={820}>Output</Text>
            <Text size="sm" c="dimmed">
              Download copybook, pre-conversion CSV, and post-conversion EBCDIC binary, or confirm delivery details.
            </Text>
          </div>
          <Button leftSection={output === 'TARGET' ? <IconSend size={16} /> : <IconDownload size={16} />} loading={generateMutation.isPending} disabled={!canGenerate} onClick={() => generateMutation.mutate()}>
            Generate
          </Button>
        </Group>
        <GeneratedResult result={result} />
      </Paper>
    </main>
  );
}

function GeneratorMapTable({
  fields,
  drafts,
  generatorNames,
  update
}: {
  fields: CopybookField[];
  drafts: Record<string, GeneratorDraft>;
  generatorNames: string[];
  update: (field: string, patch: Partial<GeneratorDraft>) => void;
}) {
  if (!fields.length) return <EmptyState title="No fields loaded" detail="Select or save a copybook to map generators." />;
  const options = generatorNames.map((name) => ({ value: name, label: name }));
  return (
    <div className="mf-table-wrap mf-generator-map">
      <Table highlightOnHover>
        <Table.Thead>
          <Table.Tr>
            <Table.Th>Field</Table.Th>
            <Table.Th>Type</Table.Th>
            <Table.Th>Off</Table.Th>
            <Table.Th>Len</Table.Th>
            <Table.Th>Generator</Table.Th>
            <Table.Th>Param 1</Table.Th>
            <Table.Th>Param 2</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {fields.map((field) => {
            const draft = drafts[field.path] || suggestGenerator(field);
            return (
              <Table.Tr key={field.path}>
                <Table.Td>
                  <Text fw={760} className="mf-mono-line">
                    {field.path}
                  </Text>
                  {field.numeric ? (
                    <Badge size="xs" color="gray" variant="light">
                      numeric
                    </Badge>
                  ) : null}
                </Table.Td>
                <Table.Td>
                  <code>{field.type}</code>
                </Table.Td>
                <Table.Td>{field.offset}</Table.Td>
                <Table.Td>{field.length}</Table.Td>
                <Table.Td>
                  <Select size="xs" data={options} value={draft.generator} searchable onChange={(value) => update(field.path, { generator: value || 'ALPHANUMERIC' })} />
                </Table.Td>
                <Table.Td>
                  <TextInput {...technicalInputProps} size="xs" value={draft.param1} onChange={(event) => update(field.path, { param1: safeInputValue(event) })} />
                </Table.Td>
                <Table.Td>
                  <TextInput {...technicalInputProps} size="xs" value={draft.param2} onChange={(event) => update(field.path, { param2: safeInputValue(event) })} />
                </Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
    </div>
  );
}

function GeneratedResult({ result }: { result: MfGeneratedFile | null }) {
  if (!result) return <EmptyState title="No output yet" detail="Generate a file to download artifacts or see LPAR delivery details." />;
  return (
    <Stack gap="sm" mt="md">
      <Alert color="green" variant="light">
        Generated {result.rowCount || 0} records - RECFM {result.recfm || 'FB'} - LRECL {result.recordLength || 0} - {result.codePage || 'Cp037'}.
      </Alert>
      {result.delivered ? (
        <Alert color="blue" variant="light">
          Delivered to {result.delivered.connection} as <code>{result.delivered.name}</code> ({result.delivered.bytes || 0} bytes).
        </Alert>
      ) : null}
      <Group gap="xs">
        <Button variant="default" size="xs" onClick={() => downloadText(result.copybookName || 'copybook.cpy', result.copybook || '')}>
          Download copybook
        </Button>
        <Button variant="default" size="xs" onClick={() => downloadText(result.preName || 'pre-ebcdic.csv', result.preContent || '')}>
          Download pre-EBCDIC CSV
        </Button>
        <Button size="xs" onClick={() => downloadBase64(result.postName || 'mainframe.dat', result.postBase64 || '')}>
          Download EBCDIC .dat
        </Button>
      </Group>
    </Stack>
  );
}
