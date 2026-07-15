'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  ActionIcon, Badge, Button, Drawer, FileInput, Group, NumberFormatter, Paper, Progress, ScrollArea, Select,
  SimpleGrid, Stack, Switch, Table, Tabs, Text, TextInput, Textarea, ThemeIcon, Title, Tooltip
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconAlertTriangle, IconCircleCheck, IconCloudDownload, IconFile, IconFileAnalytics, IconFileTextShield,
  IconHistory, IconInfoCircle, IconMaximize, IconMinimize, IconPlus, IconPlayerPlay, IconShieldLock, IconSquare, IconTrash
} from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import { apiFetch, apiFormPost, apiPost } from '@/lib/api';
import { keys } from '@/lib/keys';
import { useUnstructuredCapabilities, useUnstructuredJobs, useUnstructuredProfiles } from './hooks';
import type { UnstructuredJob, UnstructuredProfile, UnstructuredRule } from './types';

const EMPTY_PROFILE: UnstructuredProfile = { name: '', description: '', rulesJson: '[]', status: 'DRAFT', versionNo: 1 };

export function UnstructuredMaskingPage() {
  const pageRef = useRef<HTMLElement | null>(null);
  const queryClient = useQueryClient();
  const profilesQuery = useUnstructuredProfiles();
  const jobsQuery = useUnstructuredJobs();
  const capabilitiesQuery = useUnstructuredCapabilities();
  const profiles = useMemo(() => profilesQuery.data || [], [profilesQuery.data]);
  const jobs = useMemo(() => jobsQuery.data || [], [jobsQuery.data]);
  const [profileId, setProfileId] = useState<number | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [seed, setSeed] = useState('');
  const [previewText, setPreviewText] = useState('Customer Jane Doe: jane.doe@example.com, SSN 123-45-6789, card 4111 1111 1111 1111.');
  const [previewResult, setPreviewResult] = useState<{ masked: string; findingsCount: number; findings: Record<string, number> } | null>(null);
  const [selectedJobId, setSelectedJobId] = useState<number | null>(null);
  const [profileDraft, setProfileDraft] = useState<UnstructuredProfile>(EMPTY_PROFILE);
  const [rules, setRules] = useState<UnstructuredRule[]>([]);
  const [securityOpened, setSecurityOpened] = useState(false);
  const [fullScreen, setFullScreen] = useState(false);

  useEffect(() => {
    const changed = () => setFullScreen(document.fullscreenElement === pageRef.current);
    document.addEventListener('fullscreenchange', changed);
    return () => document.removeEventListener('fullscreenchange', changed);
  }, []);

  const toggleFullScreen = async () => {
    try {
      if (document.fullscreenElement === pageRef.current) await document.exitFullscreen();
      else await pageRef.current?.requestFullscreen();
    } catch (error) {
      notifyError('Full screen is unavailable', error);
    }
  };

  const effectiveProfileId = profileId || profiles.find((profile) => profile.status === 'ACTIVE')?.id || profiles[0]?.id || null;
  const selectedJob = jobs.find((job) => job.id === selectedJobId) || jobs[0] || null;

  const startMutation = useMutation({
    mutationFn: async () => { if (!file || !effectiveProfileId) throw new Error('Choose an active profile and file'); const form = new FormData(); form.append('file', file); form.append('profileId', String(effectiveProfileId)); if (seed) form.append('seed', seed); return apiFormPost<UnstructuredJob>('/api/unstructured/jobs', form); },
    onSuccess: (job) => { setSelectedJobId(job.id); setFile(null); void queryClient.invalidateQueries({ queryKey: keys.unstructured.jobs }); notifications.show({ color: 'green', title: 'Masking job queued', message: 'The encrypted upload is now tracked in the backend.' }); },
    onError: (error) => notifyError('Could not start masking', error)
  });

  const saveProfileMutation = useMutation({
    mutationFn: () => apiPost<UnstructuredProfile>('/api/unstructured/profiles', { ...profileDraft, rulesJson: JSON.stringify(rules) }),
    onSuccess: (saved) => { setProfileDraft(saved); setRules(parseRules(saved.rulesJson)); setProfileId(saved.id || null); void queryClient.invalidateQueries({ queryKey: keys.unstructured.profiles }); notifications.show({ color: 'green', title: 'Profile saved', message: `${saved.name} version ${saved.versionNo} is available.` }); },
    onError: (error) => notifyError('Could not save profile', error)
  });

  const openProfile = (profile: UnstructuredProfile) => { setProfileDraft(profile); setRules(parseRules(profile.rulesJson)); };
  const runPreview = async () => { if (!effectiveProfileId) return; try { setPreviewResult(await apiPost('/api/unstructured/preview', { profileId: effectiveProfileId, text: previewText, seed })); } catch (error) { notifyError('Preview failed', error); } };
  const cancel = async (job: UnstructuredJob) => { try { await apiPost(`/api/unstructured/jobs/${job.id}/cancel`, {}); void queryClient.invalidateQueries({ queryKey: keys.unstructured.jobs }); } catch (error) { notifyError('Cancel failed', error); } };

  return <main ref={pageRef} className="forge-page unx-page">
    <Drawer opened={securityOpened} onClose={() => setSecurityOpened(false)} position="right" size={430} title="Security model" classNames={{ body: 'unx-security-drawer-body' }}>
      <SafetyRail />
    </Drawer>
    <header className="forge-page-header"><div><Text className="forge-eyebrow">File privacy</Text><Title order={1}>Unstructured Masking</Title><Text c="dimmed">Find and mask sensitive content across documents, data files, and free-flow text.</Text></div><Group gap="xs" className="unx-header-actions"><Badge size="lg" variant="light" leftSection={<IconShieldLock size={14} />}>Fail closed</Badge><Button variant="subtle" leftSection={<IconInfoCircle size={16} />} onClick={() => setSecurityOpened(true)}>About security</Button><Tooltip label={fullScreen ? 'Exit full screen' : 'Open full-screen workspace'}><ActionIcon size="lg" variant="light" aria-label={fullScreen ? 'Exit full screen' : 'Open full-screen workspace'} onClick={() => void toggleFullScreen()}>{fullScreen ? <IconMinimize size={18} /> : <IconMaximize size={18} />}</ActionIcon></Tooltip></Group></header>
    <Tabs defaultValue="run" className="forge-feature-tabs">
      <Tabs.List><Tabs.Tab value="run" leftSection={<IconFileTextShield size={16} />}>Mask a file</Tabs.Tab><Tabs.Tab value="profiles" leftSection={<IconShieldLock size={16} />}>Profiles</Tabs.Tab><Tabs.Tab value="history" leftSection={<IconHistory size={16} />}>Run history</Tabs.Tab><Tabs.Tab value="formats" leftSection={<IconFileAnalytics size={16} />}>Format coverage</Tabs.Tab></Tabs.List>
      <Tabs.Panel value="run" pt="md">
        <Stack gap="md" className="unx-run-workspace">
          <Paper className="unx-panel" p="md"><Group justify="space-between"><div><Text fw={800}>Upload and mask</Text><Text size="sm" c="dimmed">The source is encrypted immediately and destroyed after completion or failure.</Text></div><Badge variant="light">100 MB default limit</Badge></Group><SimpleGrid cols={{ base: 1, sm: 2 }} mt="md"><Select label="Active profile" searchable data={profiles.filter((profile) => profile.status === 'ACTIVE').map((profile) => ({ value: String(profile.id), label: `${profile.name} v${profile.versionNo}` }))} value={effectiveProfileId ? String(effectiveProfileId) : null} onChange={(value) => setProfileId(value ? Number(value) : null)} /><TextInput label="Deterministic seed" value={seed} onChange={(event) => setSeed(event.currentTarget?.value || '')} placeholder="Optional" spellCheck={false} /><FileInput label="Document or data file" value={file} onChange={setFile} leftSection={<IconFile size={15} />} placeholder="PDF, DOCX, CSV, JSON, XML, HTML, logs..." clearable /><Button mt={24} size="md" leftSection={<IconPlayerPlay size={16} />} disabled={!file || !effectiveProfileId} loading={startMutation.isPending} onClick={() => startMutation.mutate()}>Start governed masking</Button></SimpleGrid></Paper>
          <Paper className="unx-panel" p="md"><Group justify="space-between"><div><Text fw={800}>Rule preview</Text><Text size="sm" c="dimmed">Test the selected profile without uploading a file.</Text></div><Button variant="default" onClick={() => void runPreview()}>Preview masking</Button></Group><Textarea mt="sm" minRows={4} value={previewText} onChange={(event) => setPreviewText(event.currentTarget?.value || '')} />{previewResult ? <SimpleGrid cols={{ base: 1, sm: 2 }} mt="md"><Paper p="sm" withBorder><Text size="xs" fw={800} c="dimmed" tt="uppercase">Masked result</Text><Text className="unx-preview-text">{previewResult.masked}</Text></Paper><Paper p="sm" withBorder><Text size="xs" fw={800} c="dimmed" tt="uppercase">Detections</Text><Group mt="xs">{Object.entries(previewResult.findings).map(([type, count]) => <Badge key={type} variant="light">{type}: {count}</Badge>)}</Group><Text mt="sm" fw={800}>{previewResult.findingsCount} replacement(s)</Text></Paper></SimpleGrid> : null}</Paper>
          {selectedJob ? <LiveJob job={selectedJob} onCancel={() => void cancel(selectedJob)} /> : null}
        </Stack>
      </Tabs.Panel>
      <Tabs.Panel value="profiles" pt="md"><SimpleGrid cols={{ base: 1, lg: 4 }} spacing="md"><Paper className="unx-panel" p="md"><Group justify="space-between"><Text fw={800}>Profile library</Text><Button size="xs" variant="subtle" leftSection={<IconPlus size={14} />} onClick={() => { setProfileDraft(EMPTY_PROFILE); setRules([]); }}>New</Button></Group><Stack gap={6} mt="sm">{profiles.map((profile) => <button className={`unx-profile-row ${profileDraft.id === profile.id ? 'is-active' : ''}`} type="button" key={profile.id} onClick={() => openProfile(profile)}><span><b>{profile.name}</b><small>Version {profile.versionNo}</small></span><Badge size="xs" color={profile.status === 'ACTIVE' ? 'green' : 'gray'}>{profile.status}</Badge></button>)}</Stack></Paper><Stack gap="md" style={{ gridColumn: 'span 3' }}><Paper className="unx-panel" p="md"><SimpleGrid cols={{ base: 1, sm: 3 }}><TextInput label="Profile name" value={profileDraft.name} onChange={(event) => setProfileDraft((current) => ({ ...current, name: event.currentTarget?.value || '' }))} /><TextInput label="Description" value={profileDraft.description || ''} onChange={(event) => setProfileDraft((current) => ({ ...current, description: event.currentTarget?.value || '' }))} /><Select label="Lifecycle" data={['DRAFT', 'ACTIVE', 'RETIRED']} value={profileDraft.status} onChange={(value) => setProfileDraft((current) => ({ ...current, status: (value || 'DRAFT') as UnstructuredProfile['status'] }))} /></SimpleGrid></Paper><RuleEditor rules={rules} onChange={setRules} /><Group justify="flex-end"><Button loading={saveProfileMutation.isPending} disabled={!profileDraft.name.trim() || !rules.length} onClick={() => saveProfileMutation.mutate()}>Save profile version</Button></Group></Stack></SimpleGrid></Tabs.Panel>
      <Tabs.Panel value="history" pt="md"><JobHistory jobs={jobs} onOpen={setSelectedJobId} onDelete={async (job) => { try { await apiFetch(`/api/unstructured/jobs/${job.id}`, { method: 'DELETE' }); void queryClient.invalidateQueries({ queryKey: keys.unstructured.jobs }); } catch (error) { notifyError('Delete failed', error); } }} /></Tabs.Panel>
      <Tabs.Panel value="formats" pt="md"><FormatCoverage capabilities={capabilitiesQuery.data} /></Tabs.Panel>
    </Tabs>
  </main>;
}

function LiveJob({ job, onCancel }: { job: UnstructuredJob; onCancel: () => void }) { const terminal = ['COMPLETED', 'FAILED', 'CANCELED'].includes(job.status); const findings = safeObject(job.findingsJson); return <Paper className={`unx-live is-${job.status.toLowerCase()}`} p="md"><Group justify="space-between"><div><Group gap="xs"><Text fw={850}>{job.originalFilename}</Text><Badge color={statusColor(job.status)}>{human(job.status)}</Badge></Group><Text size="sm" c="dimmed">{job.message || job.stage}</Text></div><Text fw={900} size="xl">{job.progress}%</Text></Group><Progress value={job.progress} color={statusColor(job.status)} size="lg" mt="md" animated={!terminal} /><SimpleGrid cols={{ base: 2, sm: 4 }} mt="md"><Metric label="Stage" value={human(job.stage)} /><Metric label="Format" value={job.detectedFormat || 'Detecting'} /><Metric label="Findings" value={job.findingsCount.toLocaleString()} /><Metric label="Strategy" value={human(job.outputStrategy || 'Pending')} /></SimpleGrid>{Object.keys(findings).length ? <Group mt="sm">{Object.entries(findings).map(([type, count]) => <Badge key={type} variant="light">{type}: {String(count)}</Badge>)}</Group> : null}{job.errorMessage ? <Paper p="sm" mt="sm" withBorder><Text c="red" size="sm">{job.errorMessage}</Text></Paper> : null}<Group justify="flex-end" mt="md">{job.outputName ? <Button component="a" href={`/api/unstructured/jobs/${job.id}/download`} variant="default" leftSection={<IconCloudDownload size={16} />}>Download masked output</Button> : null}{!terminal ? <Button color="red" variant="light" leftSection={<IconSquare size={15} />} onClick={onCancel}>Cancel safely</Button> : null}</Group></Paper>; }

function SafetyRail() { return <Paper className="unx-safety" p="md"><ThemeIcon size={38} color="green" variant="light"><IconShieldLock size={20} /></ThemeIcon><Text fw={850} mt="sm">Secure by construction</Text><Stack gap="sm" mt="md">{[['Encrypted at rest', 'AES-GCM for uploads and outputs; random key salt and IV per file.'], ['No unchanged fallback', 'Unsupported or unreadable content fails. The original is never mislabeled as masked.'], ['Structure-aware', 'JSON keys, CSV columns, XML nodes, and HTML text are processed without flattening.'], ['Safe document rebuild', 'PDF and Office content is extracted and rebuilt as masked text when native preservation is not guaranteed.'], ['Evidence without PII', 'Audit records keep counts, digests, actors, and outcomes, never matched clear values.']].map(([title, detail]) => <div key={title} className="unx-safety-item"><IconCircleCheck size={16} /><div><Text size="sm" fw={750}>{title}</Text><Text size="xs" c="dimmed">{detail}</Text></div></div>)}</Stack></Paper>; }

function RuleEditor({ rules, onChange }: { rules: UnstructuredRule[]; onChange: (rules: UnstructuredRule[]) => void }) { const patch = (index: number, update: Partial<UnstructuredRule>) => onChange(rules.map((rule, i) => i === index ? { ...rule, ...update } : rule)); return <Paper className="unx-panel" p="md"><Group justify="space-between"><div><Text fw={800}>Detection and masking rules</Text><Text size="sm" c="dimmed">Selector optionally limits a rule to a CSV column, JSON/XML path, or HTML element.</Text></div><Button size="xs" variant="light" leftSection={<IconPlus size={14} />} onClick={() => onChange([...rules, { name: 'Custom rule', piiType: 'CUSTOM', pattern: '', function: 'REDACT', param1: '', param2: '', selector: '', enabled: true }])}>Add rule</Button></Group><ScrollArea mt="md"><Table withTableBorder verticalSpacing="xs" className="unx-rule-table"><Table.Thead><Table.Tr><Table.Th>On</Table.Th><Table.Th>Rule</Table.Th><Table.Th>PII type</Table.Th><Table.Th>Safe regular expression</Table.Th><Table.Th>Selector</Table.Th><Table.Th>Mask function</Table.Th><Table.Th /></Table.Tr></Table.Thead><Table.Tbody>{rules.map((rule, index) => <Table.Tr key={`${rule.name}-${index}`}><Table.Td><Switch checked={rule.enabled} onChange={(event) => patch(index, { enabled: event.currentTarget.checked })} /></Table.Td><Table.Td><TextInput value={rule.name} onChange={(event) => patch(index, { name: event.currentTarget?.value || '' })} /></Table.Td><Table.Td><TextInput value={rule.piiType} onChange={(event) => patch(index, { piiType: event.currentTarget?.value || '' })} spellCheck={false} /></Table.Td><Table.Td><TextInput className="unx-regex" value={rule.pattern} onChange={(event) => patch(index, { pattern: event.currentTarget?.value || '' })} spellCheck={false} /></Table.Td><Table.Td><TextInput value={rule.selector} onChange={(event) => patch(index, { selector: event.currentTarget?.value || '' })} placeholder="All content" /></Table.Td><Table.Td><Select searchable data={['REDACT', 'REDACT_KEEP_LAST4', 'FORMAT_PRESERVE', 'CHARACTER_MAP', 'TOKENIZE', 'EMAIL', 'SSN', 'CREDIT_CARD', 'PHONE', 'IBAN', 'BANK_ACCOUNT', 'IP_ADDRESS', 'MAC_ADDRESS', 'DATE_SHIFT', 'NULLIFY']} value={rule.function} onChange={(value) => patch(index, { function: value || 'REDACT' })} /></Table.Td><Table.Td><Tooltip label="Remove"><ActionIcon color="red" variant="subtle" onClick={() => onChange(rules.filter((_, i) => i !== index))}><IconTrash size={15} /></ActionIcon></Tooltip></Table.Td></Table.Tr>)}</Table.Tbody></Table></ScrollArea></Paper>; }

function JobHistory({ jobs, onOpen, onDelete }: { jobs: UnstructuredJob[]; onOpen: (id: number) => void; onDelete: (job: UnstructuredJob) => void }) { return <Paper className="unx-panel" p="md"><Group justify="space-between"><div><Text fw={800}>Unstructured masking history</Text><Text size="sm" c="dimmed">Source copies are destroyed; masked outputs expire under the configured retention policy.</Text></div><Badge>{jobs.length} recent</Badge></Group><Stack gap="xs" mt="md">{jobs.map((job) => <div key={job.id} className="unx-job-row"><button type="button" onClick={() => onOpen(job.id)}><span><b>{job.originalFilename}</b><small>#{job.id} - {new Date(job.createdAt).toLocaleString()} - {job.createdBy}</small></span><span><Badge color={statusColor(job.status)}>{human(job.status)}</Badge><small><NumberFormatter value={job.findingsCount} thousandSeparator /> findings</small></span></button><Group gap={4}>{job.outputName ? <ActionIcon component="a" href={`/api/unstructured/jobs/${job.id}/download`} variant="subtle"><IconCloudDownload size={16} /></ActionIcon> : null}{!['RUNNING', 'QUEUED'].includes(job.status) ? <ActionIcon color="red" variant="subtle" onClick={() => onDelete(job)}><IconTrash size={16} /></ActionIcon> : null}</Group></div>)}{!jobs.length ? <Text c="dimmed" ta="center" py="xl">No unstructured masking jobs yet.</Text> : null}</Stack></Paper>; }

function FormatCoverage({ capabilities }: { capabilities?: { nativePreserving: string[]; safeTextRebuild: string[]; blockedWithoutExtractor: string[]; guarantee: string } }) {
  if (!capabilities) return <Paper p="xl"><Text>Loading format capabilities...</Text></Paper>;
  const supported = capabilities.nativePreserving.length + capabilities.safeTextRebuild.length;
  return <Stack gap="md" className="unx-format-workspace">
    <Paper className="unx-format-overview" p="md">
      <Group justify="space-between" align="center">
        <div><Text fw={850}>Protection path by format</Text><Text size="sm" c="dimmed">Every upload follows one explicit handling path. Unsupported content is stopped before output.</Text></div>
        <Badge size="lg" variant="light" color="blue">{supported} supported format groups</Badge>
      </Group>
      <div className="unx-format-flow">
        <div className="is-native"><b>1</b><span><strong>Preserve</strong><small>Mask in native structure</small></span></div>
        <i aria-hidden="true" />
        <div className="is-rebuild"><b>2</b><span><strong>Rebuild safely</strong><small>Extract, mask, emit text</small></span></div>
        <i aria-hidden="true" />
        <div className="is-blocked"><b>3</b><span><strong>Stop safely</strong><small>No unchanged fallback</small></span></div>
      </div>
    </Paper>
    <SimpleGrid cols={{ base: 1, lg: 3 }}>
      <CoverageCard tone="native" color="green" icon={<IconCircleCheck size={19} />} title="Native structure preserved" rows={capabilities.nativePreserving} detail="The original logical structure remains usable after masking." />
      <CoverageCard tone="rebuild" color="blue" icon={<IconFileAnalytics size={19} />} title="Safe text rebuild" rows={capabilities.safeTextRebuild} detail="Text is extracted and masked; the original binary is never copied to output." />
      <CoverageCard tone="blocked" color="yellow" icon={<IconAlertTriangle size={19} />} title="Approved extractor required" rows={capabilities.blockedWithoutExtractor} detail={capabilities.guarantee} />
    </SimpleGrid>
  </Stack>;
}
function CoverageCard({ tone, color, icon, title, rows, detail }: { tone: string; color: string; icon: React.ReactNode; title: string; rows: string[]; detail: string }) { return <Paper className={`unx-panel unx-coverage-card is-${tone}`} p="md"><Group justify="space-between" align="flex-start"><ThemeIcon color={color} variant="light">{icon}</ThemeIcon><Badge size="sm" variant="light" color={color}>{rows.length} groups</Badge></Group><Text fw={850} mt="sm">{title}</Text><Text size="sm" c="dimmed" className="unx-coverage-detail">{detail}</Text><div className="unx-format-tags">{rows.map((row) => <Badge key={row} variant="outline" color={color}>{row}</Badge>)}</div></Paper>; }
function Metric({ label, value }: { label: string; value: string }) { return <div className="unx-metric"><Text size="xs" c="dimmed" fw={800} tt="uppercase">{label}</Text><Text fw={800}>{value}</Text></div>; }
function parseRules(value: string) { try { return JSON.parse(value || '[]') as UnstructuredRule[]; } catch { return []; } }
function safeObject(value?: string | null) { try { return JSON.parse(value || '{}') as Record<string, unknown>; } catch { return {}; } }
function human(value: string) { return value.replaceAll('_', ' ').toLowerCase().replace(/^./, (letter) => letter.toUpperCase()); }
function statusColor(status: string) { if (status === 'COMPLETED') return 'green'; if (status === 'FAILED') return 'red'; if (status === 'CANCELED') return 'gray'; return 'blue'; }
function notifyError(title: string, error: unknown) { notifications.show({ color: 'red', title, message: error instanceof Error ? error.message : String(error) }); }
