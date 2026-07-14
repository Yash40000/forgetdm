'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ActionIcon, Badge, Button, Group, Loader, Paper, ScrollArea, Select, Stack, Tabs, Text, TextInput, Tooltip } from '@mantine/core';
import { IconArrowsExchange, IconMaximize, IconMinimize, IconRefresh, IconRoute, IconTrash } from '@tabler/icons-react';
import {
  Background,
  BackgroundVariant,
  ConnectionMode,
  ConnectionLineType,
  Controls,
  Handle,
  MarkerType,
  MiniMap,
  NodeResizer,
  Panel,
  Position,
  ReactFlow,
  type ReactFlowInstance,
  useEdgesState,
  useNodesState,
  type Connection,
  type Edge,
  type Node,
  type NodeProps
} from '@xyflow/react';

import type { MappingCanvas, MappingColumn, MappingSpec, MappingTransform } from '../types';
import type { MappingAsset } from '../types';
import type { DataSource } from '@/lib/types';
import { apiFetch } from '@/lib/api';
import { dialectFor, functionsFor, newTransform, TRANSFORM_CATALOG, transformName } from '../transform-library';

const TARGET_NODE = 'mapping-target';

type Port = { name: string; qualified: string; action?: string; dataType?: string; input?: boolean; output?: boolean; joinSelected?: boolean };
type MappingNodeData = {
  kind: 'SOURCE' | 'TARGET' | 'TRANSFORM' | 'STAGING';
  title: string;
  subtitle: string;
  ports: Port[];
  transformType?: string;
  onEdit?: () => void;
  onAddColumn?: () => void;
  onRename?: (value: string) => void;
  onDelete?: () => void;
  onRenamePort?: (port: Port, value: string) => void;
  onRetypePort?: (port: Port, value: string) => void;
  onDeletePort?: (port: Port) => void;
  onResize?: (size: { width: number; height: number }) => void;
  onJoinPort?: (qualified: string) => void;
};
type MappingFlowNode = Node<MappingNodeData, 'mappingNode'>;

const nodeTypes = { mappingNode: MappingObjectNode };

export function VisualMapCanvas({
  spec,
  sourceColumns,
  onChange,
  onAutoMap,
  onConfigure,
  onEditTransforms,
  dataSources,
  assets,
  columnTypes
}: {
  spec: MappingSpec;
  sourceColumns: string[];
  onChange: (next: MappingSpec | ((current: MappingSpec) => MappingSpec)) => void;
  onAutoMap: () => void;
  onConfigure: () => void;
  onEditTransforms: () => void;
  dataSources: DataSource[];
  assets: MappingAsset[];
  columnTypes: Record<string, string>;
}) {
  return (
    <VisualMapSession
      spec={spec}
      sourceColumns={sourceColumns}
      onChange={onChange}
      onAutoMap={onAutoMap}
      onConfigure={onConfigure}
      onEditTransforms={onEditTransforms}
      dataSources={dataSources}
      assets={assets}
      columnTypes={columnTypes}
    />
  );
}

function VisualMapSession({ spec, sourceColumns, onChange, onAutoMap, onConfigure, onEditTransforms, dataSources, assets, columnTypes }: {
  spec: MappingSpec;
  sourceColumns: string[];
  onChange: (next: MappingSpec | ((current: MappingSpec) => MappingSpec)) => void;
  onAutoMap: () => void;
  onConfigure: () => void;
  onEditTransforms: () => void;
  dataSources: DataSource[];
  assets: MappingAsset[];
  columnTypes: Record<string, string>;
}) {
  const aliasToId = useMemo(() => new Map(spec.sources.map((source) => [source.alias.toLowerCase(), source.id])), [spec.sources]);
  const panelRef = useRef<HTMLDivElement>(null);
  const revealNodeRef = useRef<string | null>(null);
  const [fullScreen, setFullScreen] = useState(false);
  const [flow, setFlow] = useState<ReactFlowInstance<MappingFlowNode, Edge> | null>(null);
  const [selectedEdges, setSelectedEdges] = useState<Edge[]>([]);
  const [joinType, setJoinType] = useState<'INNER' | 'LEFT' | 'RIGHT' | 'FULL'>('INNER');
  const [pendingJoin, setPendingJoin] = useState<{ nodeId: string; qualified: string } | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState<MappingFlowNode>(buildNodes(spec, sourceColumns, columnTypes, onChange, onEditTransforms));
  const [edges, setEdges, onEdgesChange] = useEdgesState(buildEdges(spec, aliasToId));
  const activeSources = spec.sources.filter(isConfiguredSource);
  const functionGroups = useMemo(() => functionsFor(dialectFor(spec, dataSources)), [dataSources, spec]);

  useEffect(() => {
    const changed = () => setFullScreen(document.fullscreenElement === panelRef.current);
    document.addEventListener('fullscreenchange', changed);
    return () => document.removeEventListener('fullscreenchange', changed);
  }, []);

  const selectJoinPort = useCallback((nodeId: string, qualified: string) => {
    if (!pendingJoin) { setPendingJoin({ nodeId, qualified }); return; }
    if (pendingJoin.nodeId === nodeId) { setPendingJoin(null); return; }
    const duplicate = spec.joins.some((join) => (join.left === pendingJoin.qualified && join.right === qualified) || (join.left === qualified && join.right === pendingJoin.qualified));
    if (!duplicate) {
      const join = { id: crypto.randomUUID(), type: joinType, left: pendingJoin.qualified, right: qualified } as const;
      const edge = joinEdge(join, aliasToId);
      if (edge) setEdges((current) => [...current, edge]);
      onChange((current) => ({ ...current, joins: [...current.joins, join] }));
    }
    setPendingJoin(null);
  }, [aliasToId, joinType, onChange, pendingJoin, setEdges, spec.joins]);

  useEffect(() => {
    setNodes(buildNodes(spec, sourceColumns, columnTypes, onChange, onEditTransforms, selectJoinPort, pendingJoin?.qualified));
    setEdges(buildEdges(spec, aliasToId));
  }, [aliasToId, columnTypes, onChange, onEditTransforms, pendingJoin?.qualified, selectJoinPort, setEdges, setNodes, sourceColumns, spec]);

  useEffect(() => {
    const id = revealNodeRef.current;
    if (!flow || !id) return;
    const node = nodes.find((candidate) => candidate.id === id);
    if (!node) return;
    revealNodeRef.current = null;
    const width = Number(node.style?.width || node.measured?.width || 160);
    const height = Number(node.style?.height || node.measured?.height || 100);
    void flow.setCenter(node.position.x + width / 2, node.position.y + height / 2, { zoom: Math.min(1, Math.max(0.55, flow.getZoom())), duration: 260 });
  }, [flow, nodes]);

  const persistPositions = (nextNodes: MappingFlowNode[]) => {
    const positions = Object.fromEntries(nextNodes.map((node) => [node.id, node.position]));
    onChange((current) => ({ ...current, canvas: { ...(current.canvas || {}), positions } }));
  };

  const autoLayout = () => {
    const sources = nodes.filter((node) => node.data.kind === 'SOURCE');
    const staging = nodes.filter((node) => node.data.kind === 'STAGING');
    const transforms = nodes.filter((node) => node.data.kind === 'TRANSFORM');
    const sourceWidth = Math.max(160, ...sources.map((node) => Number(node.style?.width || node.measured?.width || 160)));
    const sourceStride = Math.max(105, Math.min(155, Math.max(...sources.map((node) => Number(node.style?.height || node.measured?.height || 105)), 105) + 18));
    const centerY = 40 + Math.max(0, sources.length - 1) * sourceStride / 2;
    const transformStart = 40 + sourceWidth + 70;
    const stagingStart = transformStart + transforms.length * 215;
    const targetX = stagingStart + (staging.length ? 220 : 0);
    const next = nodes.map((node) => {
      if (node.data.kind === 'SOURCE') {
        const index = sources.findIndex((item) => item.id === node.id);
        return { ...node, position: { x: 40, y: 40 + index * sourceStride } };
      }
      if (node.data.kind === 'TRANSFORM') {
        const index = transforms.findIndex((item) => item.id === node.id);
        return { ...node, position: { x: transformStart + index * 215, y: centerY } };
      }
      if (node.data.kind === 'STAGING') {
        const index = staging.findIndex((item) => item.id === node.id);
        return { ...node, position: { x: stagingStart, y: centerY + index * 125 } };
      }
      return { ...node, position: { x: targetX, y: centerY } };
    });
    setNodes(next);
    persistPositions(next);
    window.setTimeout(() => void flow?.fitView({ padding: 0.32, maxZoom: 1, duration: 280 }), 0);
  };

  const compactNodes = () => {
    const sizes = Object.fromEntries(nodes.map((node) => [node.id, {
      width: node.data.kind === 'TRANSFORM' ? 185 : 160,
      height: Math.min(220, Math.max(70, 39 + Math.max(1, node.data.ports.length) * 20))
    }]));
    setNodes((current) => current.map((node) => ({ ...node, style: { ...node.style, ...sizes[node.id] } })));
    onChange((current) => ({ ...current, canvas: { ...(current.canvas || {}), sizes: { ...(current.canvas?.sizes || {}), ...sizes } } }));
  };

  const connect = (connection: Connection) => {
    if (!connection.source || !connection.target || !connection.sourceHandle || !connection.targetHandle) return;
    const left = decodeHandle(connection.sourceHandle);
    if (!left) return;

    const sourceKind = nodes.find((node) => node.id === connection.source)?.data.kind;
    const targetKind = nodes.find((node) => node.id === connection.target)?.data.kind;
    if (targetKind === 'STAGING' && connection.targetHandle === 'pipeline-in') {
      const incomingType = columnTypes[left] || nodes.find((node) => node.id === connection.source)?.data.ports.find((port) => port.qualified === left)?.dataType || '';
      onChange((current) => {
        const staging = (current.stagingTables || []).find((table) => table.id === connection.target);
        if (!staging) return current;
        const column = uniqueColumnName(staging.columns, left.split('.').pop() || 'column');
        const target = `${staging.name}.${column}`;
        return {
          ...current,
          stagingTables: (current.stagingTables || []).map((table) => table.id === staging.id ? {
            ...table,
            columns: [...table.columns, column],
            columnTypes: incomingType ? { ...(table.columnTypes || {}), [column]: incomingType } : table.columnTypes
          } : table),
          canvas: { ...(current.canvas || {}), links: [...(current.canvas?.links || []), { id: crypto.randomUUID(), source: left, target }] }
        };
      });
      return;
    }
    const right = decodeHandle(connection.targetHandle);
    if (!right) return;
    if (sourceKind === 'SOURCE' && targetKind === 'SOURCE' && connection.source !== connection.target) {
      const duplicate = spec.joins.some((join) => (join.left === left && join.right === right) || (join.left === right && join.right === left));
      if (!duplicate) {
        const join = { id: crypto.randomUUID(), type: joinType, left, right } as const;
        setEdges((current) => [...current, joinEdge(join, aliasToId)].filter((edge): edge is Edge => !!edge));
        onChange((current) => ({ ...current, joins: [...current.joins, join] }));
      }
      setPendingJoin(null);
      return;
    }
    if (connection.target === TARGET_NODE) {
      if (!connection.sourceHandle.startsWith('out:') || !connection.targetHandle.startsWith('in:')) return;
      const resolved = sourceKind === 'STAGING' ? resolveStagingSource(spec, left) : left;
      onChange((current) => ({ ...current, columns: upsertColumn(current.columns, resolved, right) }));
      return;
    }
    if (targetKind === 'STAGING' || targetKind === 'TRANSFORM') {
      if (!connection.sourceHandle.startsWith('out:') || !connection.targetHandle.startsWith('in:')) return;
      const duplicate = (spec.canvas?.links || []).some((link) => link.source === left && link.target === right);
      if (!duplicate) onChange((current) => ({ ...current, canvas: { ...(current.canvas || {}), links: [...(current.canvas?.links || []), { id: crypto.randomUUID(), source: left, target: right }] } }));
      return;
    }
    if (connection.source === TARGET_NODE || connection.source === connection.target) return;
  };

  const deleteEdges = (removed: Edge[]) => {
    const columnIds = new Set(removed.filter((edge) => edge.id.startsWith('column:')).map((edge) => edge.id.slice(7)));
    const joinIds = new Set(removed.filter((edge) => edge.id.startsWith('join:')).map((edge) => edge.id.slice(5)));
    const wireIds = new Set(removed.filter((edge) => edge.id.startsWith('wire:')).map((edge) => edge.id.slice(5)));
    if (!columnIds.size && !joinIds.size && !wireIds.size) return;
    onChange((current) => ({
      ...current,
      columns: current.columns.filter((column) => !columnIds.has(column.id)),
      joins: current.joins.filter((join) => !joinIds.has(join.id)),
      canvas: { ...(current.canvas || {}), links: (current.canvas?.links || []).filter((link) => !wireIds.has(link.id)) }
    }));
  };

  const deleteNodes = (removed: MappingFlowNode[]) => {
    const ids = new Set(removed.map((node) => node.id));
    const aliases = new Set(spec.sources.filter((source) => ids.has(source.id)).map((source) => source.alias.toLowerCase()));
    const stagingNames = new Set((spec.stagingTables || []).filter((table) => ids.has(table.id)).map((table) => table.name.toLowerCase()));
    onChange((current) => ({
      ...current,
      sources: current.sources.filter((source) => !ids.has(source.id)),
      stagingTables: (current.stagingTables || []).filter((table) => !ids.has(table.id)),
      joins: current.joins.filter((join) => !aliases.has(join.left.split('.')[0].toLowerCase()) && !aliases.has(join.right.split('.')[0].toLowerCase())),
      columns: current.columns.filter((column) => !aliases.has(column.source.split('.')[0]?.toLowerCase()) && !stagingNames.has(column.source.split('.')[0]?.toLowerCase())),
      canvas: { ...withoutCanvasNodes(current.canvas, ids), links: (current.canvas?.links || []).filter((link) => !aliases.has(link.source.split('.')[0].toLowerCase()) && !aliases.has(link.target.split('.')[0].toLowerCase()) && !stagingNames.has(link.source.split('.')[0].toLowerCase()) && !stagingNames.has(link.target.split('.')[0].toLowerCase())) }
    }));
  };

  const addDatabaseObject = async (role: 'SOURCE' | 'TARGET', source: DataSource, schema: string, table: string, position?: { x: number; y: number }) => {
    let columns: Array<{ name: string; type?: string }> = [];
    try {
      const rows = await apiFetch<Array<Record<string, unknown>>>(`/api/datasources/${source.id}/tables/${encodeURIComponent(table)}/columns?schema=${encodeURIComponent(schema)}`);
      columns = rows.map((row) => ({ name: String(row.name || row.column || row.columnName || ''), type: String(row.type || row.dataType || row.typeName || '') })).filter((column) => column.name);
    } catch {
      // Keep the table on the canvas. Validation will surface an inaccessible object.
    }
    if (role === 'TARGET') {
      if (!position) revealNodeRef.current = TARGET_NODE;
      onChange((current) => ({ ...current, target: { ...current.target, type: 'DATABASE', dataSourceId: source.id, schema, table, columns }, canvas: { ...(current.canvas || {}), positions: { ...(current.canvas?.positions || {}), [TARGET_NODE]: position || findOpenCanvasPosition(current, 'TARGET') } } }));
      return;
    }
    const id = crypto.randomUUID();
    if (!position) revealNodeRef.current = id;
    onChange((current) => {
      const base = safeAlias(table); const used = new Set(current.sources.map((item) => item.alias));
      let alias = base; let suffix = 2; while (used.has(alias)) alias = `${base}_${suffix++}`;
      return { ...current, sources: [...current.sources.filter((item) => item.dataSourceId || item.assetId || item.table), { id, type: 'DATABASE', alias, dataSourceId: source.id, schema, table, filter: '', columns }], canvas: { ...(current.canvas || {}), positions: { ...(current.canvas?.positions || {}), [id]: position || findOpenCanvasPosition(current, 'SOURCE') } } };
    });
  };

  const addFileObject = (asset: MappingAsset) => {
    const id = crypto.randomUUID();
    revealNodeRef.current = id;
    onChange((current) => ({ ...current, sources: [...current.sources.filter((item) => item.dataSourceId || item.assetId || item.table), { id, type: 'FILE', alias: uniqueAlias(current, safeAlias(asset.name)), assetId: asset.id }], canvas: { ...(current.canvas || {}), positions: { ...(current.canvas?.positions || {}), [id]: findOpenCanvasPosition(current, 'SOURCE') } } }));
  };

  const addTransformObject = (type: string, position?: { x: number; y: number }) => {
    const transform = newTransform(type); const id = String(transform.id || crypto.randomUUID()); transform.id = id;
    if (!position) revealNodeRef.current = id;
    onChange((current) => ({ ...current, transforms: [...(current.transforms || []), transform], canvas: { ...(current.canvas || {}), positions: { ...(current.canvas?.positions || {}), [id]: position || findOpenCanvasPosition(current, 'TRANSFORM') } } }));
  };

  const addFunctionObject = (expression: string, category = 'Expression', position?: { x: number; y: number }) => {
    const isAggregate = category.toUpperCase() === 'AGGREGATE';
    const baseTransform = newTransform(isAggregate ? 'AGGREGATOR' : 'EXPRESSION');
    const id = String(baseTransform.id || crypto.randomUUID());
    if (!position) revealNodeRef.current = id;
    onChange((current) => {
      const transform: MappingTransform = { ...baseTransform, id };
      const ordinal = (current.transforms || []).filter((item) => item.type === transform.type).length + 1;
      if (isAggregate) transform.aggregates = [{ name: functionOutputName(expression, ordinal), expr: expression }];
      else transform.columns = [{ name: functionOutputName(expression, ordinal), expr: expression }];
      transform.functionCategory = category;
      transform.functionTemplate = expression;
      transform.requiresConfiguration = true;
      return { ...current, transforms: [...(current.transforms || []), transform], canvas: { ...(current.canvas || {}), positions: { ...(current.canvas?.positions || {}), [id]: position || findOpenCanvasPosition(current, 'TRANSFORM') } } };
    });
  };

  const addStagingObject = () => {
    const id = crypto.randomUUID();
    revealNodeRef.current = id;
    onChange((current) => ({ ...current, stagingTables: [...(current.stagingTables || []), { id, name: uniqueStagingName(current, `staging_${(current.stagingTables || []).length + 1}`, id), columns: [] }], canvas: { ...(current.canvas || {}), positions: { ...(current.canvas?.positions || {}), [id]: findOpenCanvasPosition(current, 'STAGING') } } }));
  };

  const toggleFullScreen = async () => {
    if (document.fullscreenElement === panelRef.current) await document.exitFullscreen();
    else await panelRef.current?.requestFullscreen();
  };

  return (
    <Paper ref={panelRef} className={`mapx-panel mapx-visual-panel ${fullScreen ? 'is-fullscreen' : ''}`} p={0}>
      <div className="mapx-visual-heading">
        <div>
          <Group gap="xs">
            <Text fw={800}>Visual source-to-target map</Text>
            <Badge variant="light">{spec.columns.filter((column) => column.action !== 'UNUSED').length} mapped</Badge>
            <Badge color="orange" variant="light">{spec.joins.length} joins</Badge>
            {pendingJoin ? <Badge color="yellow" variant="filled">Choose second input</Badge> : null}
          </Group>
          <Text size="sm" c="dimmed">Wire a source column to a target column. Wire columns between sources to create a join.</Text>
        </div>
        <Button size="xs" leftSection={fullScreen ? <IconMinimize size={15} /> : <IconMaximize size={15} />} onClick={() => void toggleFullScreen()}>{fullScreen ? 'Exit full screen' : 'Full screen'}</Button>
      </div>
      <div className="mapx-visual-toolbar">
        <Text size="xs" fw={800} c="dimmed">JOIN</Text>
        <Select size="xs" data={[{ value: 'INNER', label: 'Inner (Normal)' }, { value: 'LEFT', label: 'Left Outer (Detail)' }, { value: 'RIGHT', label: 'Right Outer (Master)' }, { value: 'FULL', label: 'Full Outer' }]} value={joinType} onChange={(value) => setJoinType((value || 'INNER') as typeof joinType)} w={165} />
        <Button size="xs" variant="default" disabled={!selectedEdges.length} onClick={() => { deleteEdges(selectedEdges); const ids = new Set(selectedEdges.map((edge) => edge.id)); setEdges((current) => current.filter((edge) => !ids.has(edge.id))); setSelectedEdges([]); }}>Delete link</Button>
        <Button size="xs" variant="subtle" onClick={onConfigure}>Source/target rules</Button>
        <Select size="xs" placeholder="+ Transformation..." data={TRANSFORM_CATALOG.map(([value, label]) => ({ value, label }))} value={null} onChange={(type) => type && addTransformObject(type)} w={190} />
        <Button size="xs" variant="default" leftSection={<IconArrowsExchange size={14} />} onClick={addStagingObject}>Staging table</Button>
        <Button size="xs" variant="default" onClick={() => void flow?.fitView({ padding: 0.18, duration: 250 })}>Overview</Button>
        <Select size="xs" aria-label="Canvas zoom" data={[{ value: '1.25', label: '125%' }, { value: '1', label: '100%' }, { value: '0.8', label: '80%' }, { value: '0.6', label: '60%' }, { value: '0.45', label: '45%' }, { value: '0.3', label: '30%' }]} defaultValue="1" onChange={(value) => value && void flow?.zoomTo(Number(value), { duration: 180 })} w={90} />
        <Button size="xs" variant="default" onClick={compactNodes}>Compact nodes</Button>
        <Button size="xs" variant="default" leftSection={<IconRoute size={14} />} onClick={autoLayout}>Auto-layout</Button>
        <Button size="xs" variant="default" leftSection={<IconRefresh size={14} />} onClick={onAutoMap}>Auto-map</Button>
        <Button size="xs" color="red" leftSection={<IconTrash size={14} />} onClick={() => onChange((current) => ({ ...current, sources: [], joins: [], columns: [], transforms: [], stagingTables: [], canvas: { positions: {}, links: [] }, target: { type: 'PREVIEW', preAction: 'NONE', format: 'CSV' } }))}>Clear</Button>
      </div>
      <div className="mapx-pipeline-strip"><Text size="xs" fw={850}>PIPELINE</Text>{activeSources.map((source) => <span className="mapx-pipeline-chip is-source" key={source.id}>{source.alias}</span>)}{activeSources.length > 1 ? <span className="mapx-pipeline-arrow">JOIN</span> : null}{(spec.transforms || []).map((transform, index) => <span key={String(transform.id || index)} className="mapx-pipeline-chip is-transform">{transformName(transform.type)}</span>)}{(spec.stagingTables || []).map((table) => <span key={table.id} className="mapx-pipeline-chip is-staging">{table.name}</span>)}{isConfiguredTarget(spec) ? <><span className="mapx-pipeline-arrow">TO</span><span className="mapx-pipeline-chip is-target">{spec.target.type === 'DATABASE' ? spec.target.table : spec.target.type === 'FILE' ? `${spec.target.format || 'CSV'} output` : 'Preview'}</span></> : <span className="mapx-pipeline-empty">Drop a target table or choose an output</span>}</div>
      <div className="mapx-visual-body">
        <DesignerObjectBrowser dataSources={dataSources} assets={assets} functionGroups={functionGroups} onDatabase={addDatabaseObject} onFile={addFileObject} onTransform={addTransformObject} onFunction={addFunctionObject} />
      <div className="mapx-flow-canvas" role="application" aria-label="Visual source to target mapping canvas"
        onDragOver={(event) => { event.preventDefault(); event.dataTransfer.dropEffect = 'copy'; }}
        onDrop={(event) => {
          event.preventDefault();
          if (!flow) return;
          try {
            const item = JSON.parse(event.dataTransfer.getData('application/forgetdm-mapping-object')) as { kind?: 'DATABASE' | 'TRANSFORM' | 'FUNCTION'; role?: 'SOURCE' | 'TARGET'; sourceId?: number; schema?: string; table?: string; type?: string; expression?: string; category?: string };
            const position = flow.screenToFlowPosition({ x: event.clientX, y: event.clientY });
            if (item.kind === 'TRANSFORM' && item.type) { addTransformObject(item.type, position); return; }
            if (item.kind === 'FUNCTION' && item.expression) { addFunctionObject(item.expression, item.category || 'Expression', position); return; }
            const source = dataSources.find((candidate) => candidate.id === item.sourceId);
            if (!source || !item.role || !item.table) return;
            void addDatabaseObject(item.role, source, item.schema || '', item.table, position);
          } catch {
            // Ignore unrelated browser drag payloads.
          }
        }}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onNodeDragStop={(_, node) => persistPositions(nodes.map((item) => item.id === node.id ? { ...item, position: node.position } : item))}
          onConnect={connect}
          onEdgesDelete={deleteEdges}
          onNodesDelete={deleteNodes}
          onEdgeClick={(event, edge) => {
            event.stopPropagation();
            setSelectedEdges([edge]);
            setEdges((current) => current.map((item) => ({ ...item, selected: item.id === edge.id })));
          }}
          onPaneClick={() => {
            setSelectedEdges([]);
            setEdges((current) => current.map((edge) => edge.selected ? { ...edge, selected: false } : edge));
          }}
          onInit={setFlow}
          onSelectionChange={({ edges: selected }) => setSelectedEdges(selected)}
          connectionLineType={ConnectionLineType.SmoothStep}
          connectionMode={ConnectionMode.Loose}
          defaultEdgeOptions={{ type: 'smoothstep', markerEnd: { type: MarkerType.ArrowClosed }, interactionWidth: 24 }}
          deleteKeyCode={['Backspace', 'Delete']}
          fitView
          fitViewOptions={{ padding: 0.18 }}
          minZoom={0.25}
          maxZoom={1.6}
          colorMode="system"
        >
          <Background variant={BackgroundVariant.Dots} gap={18} size={1.2} />
          <Controls showInteractive={false} />
          <MiniMap pannable zoomable nodeColor={(node) => node.data?.kind === 'TARGET' ? '#16a34a' : node.data?.kind === 'TRANSFORM' ? '#7c3aed' : '#228be6'} />
          <Panel position="bottom-center" className="mapx-flow-help">
            JOIN: drag input to input, or click two source input ports. DATA: source output to transformation, staging, or target input. Select a line and press Delete to remove it.
          </Panel>
        </ReactFlow>
      </div>
      </div>
    </Paper>
  );
}

function DesignerObjectBrowser({ dataSources, assets, functionGroups, onDatabase, onFile, onTransform, onFunction }: {
  dataSources: DataSource[];
  assets: MappingAsset[];
  functionGroups: Record<string, string[]>;
  onDatabase: (role: 'SOURCE' | 'TARGET', source: DataSource, schema: string, table: string, position?: { x: number; y: number }) => void | Promise<void>;
  onFile: (asset: MappingAsset) => void;
  onTransform: (type: string, position?: { x: number; y: number }) => void;
  onFunction: (expression: string, category?: string, position?: { x: number; y: number }) => void;
}) {
  const [search, setSearch] = useState('');
  const dragObject = (event: React.DragEvent, payload: Record<string, unknown>) => {
    event.dataTransfer.effectAllowed = 'copy';
    event.dataTransfer.setData('application/forgetdm-mapping-object', JSON.stringify(payload));
  };
  const query = search.trim().toLowerCase();
  return (
    <aside className="mapx-connection-palette mapx-object-browser">
      <div className="mapx-palette-title"><Text fw={800} size="sm">Object browser</Text><Text size="xs" c="dimmed">Drag or double-click to add</Text></div>
      <Tabs defaultValue="sources" className="mapx-browser-tabs">
        <Tabs.List grow><Tabs.Tab value="sources">Sources</Tabs.Tab><Tabs.Tab value="transforms">Transforms</Tabs.Tab><Tabs.Tab value="functions">Functions</Tabs.Tab></Tabs.List>
        <Tabs.Panel value="sources" className="mapx-browser-panel mapx-browser-sources">
          <ConnectionPalette dataSources={dataSources} assets={assets} onDatabase={onDatabase} onFile={onFile} />
        </Tabs.Panel>
        <Tabs.Panel value="transforms" className="mapx-browser-panel">
          <TextInput size="xs" placeholder="Find transformation" value={search} onChange={(event) => setSearch(event.currentTarget.value)} m="xs" />
          <ScrollArea className="mapx-browser-scroll"><Stack gap={4} p="xs" pt={0}>{TRANSFORM_CATALOG.filter(([, label, description]) => !query || `${label} ${description}`.toLowerCase().includes(query)).map(([type, label, description]) => <button type="button" draggable className="mapx-browser-object is-transform" key={type} title={`${description}. Drag or double-click to add.`} onDragStart={(event) => dragObject(event, { kind: 'TRANSFORM', type })} onDoubleClick={() => onTransform(type)}><span><b>{label}</b><small>{description}</small></span><i>+</i></button>)}</Stack></ScrollArea>
        </Tabs.Panel>
        <Tabs.Panel value="functions" className="mapx-browser-panel">
          <TextInput size="xs" placeholder="Find function" value={search} onChange={(event) => setSearch(event.currentTarget.value)} m="xs" />
          <ScrollArea className="mapx-browser-scroll"><Stack gap="xs" p="xs" pt={0}>{Object.entries(functionGroups).map(([group, functions]) => { const visible = functions.filter((item) => !query || `${group} ${item}`.toLowerCase().includes(query)); if (!visible.length) return null; return <div key={group}><Text size="xs" fw={850} c="dimmed" mb={3}>{group.toUpperCase()}</Text>{visible.map((expression) => <button type="button" draggable className="mapx-browser-object is-function" key={expression} title={`${expression}. ${group === 'Aggregate' ? 'Adds an Aggregator object' : 'Adds an Expression object'} for strict configuration.`} onDragStart={(event) => dragObject(event, { kind: 'FUNCTION', expression, category: group })} onDoubleClick={() => onFunction(expression, group)}><code>{expression}</code><i>+</i></button>)}</div>; })}</Stack></ScrollArea>
        </Tabs.Panel>
      </Tabs>
    </aside>
  );
}

function ConnectionPalette({ dataSources, assets, onDatabase, onFile }: { dataSources: DataSource[]; assets: MappingAsset[]; onDatabase: (role: 'SOURCE' | 'TARGET', source: DataSource, schema: string, table: string, position?: { x: number; y: number }) => void | Promise<void>; onFile: (asset: MappingAsset) => void }) {
  const [schemas, setSchemas] = useState<Record<number, string[]>>({});
  const [tables, setTables] = useState<Record<string, string[]>>({});
  const [openConnection, setOpenConnection] = useState<number | null>(null);
  const [openSchema, setOpenSchema] = useState('');
  const [loading, setLoading] = useState('');
  const openSource = async (source: DataSource) => {
    if (openConnection === source.id) { setOpenConnection(null); return; }
    setOpenConnection(source.id);
    if (schemas[source.id]) return;
    setLoading(`ds:${source.id}`);
    try { const rows = await apiFetch<Array<Record<string, unknown>>>(`/api/datasources/${source.id}/schemas`); setSchemas((current) => ({ ...current, [source.id]: rows.map((row) => String(row.schema || row.name || '')).filter(Boolean) })); }
    finally { setLoading(''); }
  };
  const openSchemaNode = async (source: DataSource, schema: string) => {
    const key = `${source.id}:${schema}`;
    if (openSchema === key) { setOpenSchema(''); return; }
    setOpenSchema(key);
    if (tables[key]) return;
    setLoading(`schema:${key}`);
    try { const rows = await apiFetch<Array<Record<string, unknown>>>(`/api/datasources/${source.id}/tables?schema=${encodeURIComponent(schema)}`); setTables((current) => ({ ...current, [key]: rows.map((row) => String(row.name || row.table || '')).filter(Boolean) })); }
    finally { setLoading(''); }
  };
  const beginDrag = (event: React.DragEvent, role: 'SOURCE' | 'TARGET', source: DataSource, schema: string, table: string) => {
    event.dataTransfer.effectAllowed = 'copy';
    event.dataTransfer.setData('application/forgetdm-mapping-object', JSON.stringify({ role, sourceId: source.id, schema, table }));
  };
  return <aside className="mapx-connection-palette"><div className="mapx-palette-title"><Text fw={800} size="sm">Connections</Text><Text size="xs" c="dimmed">Drag a table to the canvas</Text></div><ScrollArea h="100%"><Stack gap={3} p="xs">{dataSources.map((source) => <div key={source.id}><button type="button" className="mapx-tree-row is-connection" onClick={() => void openSource(source)}><span>{openConnection === source.id ? '-' : '+'}</span><b>{source.name}</b><small>{source.role}</small></button>{openConnection === source.id ? <div className="mapx-tree-children">{loading === `ds:${source.id}` ? <Loader size="xs" m="xs" /> : (schemas[source.id] || []).map((schema) => { const key = `${source.id}:${schema}`; return <div key={key}><button type="button" className="mapx-tree-row is-schema" onClick={() => void openSchemaNode(source, schema)}><span>{openSchema === key ? '-' : '+'}</span>{schema}</button>{openSchema === key ? <div className="mapx-tree-children">{loading === `schema:${key}` ? <Loader size="xs" m="xs" /> : (tables[key] || []).map((table) => { const dragRole = source.role === 'TARGET' ? 'TARGET' : 'SOURCE'; return <div className="mapx-tree-table" key={table} draggable onDragStart={(event) => beginDrag(event, dragRole, source, schema, table)}><span title={`${table} - drag to canvas`}>{table}</span><Group gap={2} wrap="nowrap">{source.role !== 'TARGET' ? <Tooltip label="Add as source"><ActionIcon size="xs" variant="subtle" onClick={() => void onDatabase('SOURCE', source, schema, table)}>S</ActionIcon></Tooltip> : null}{source.role !== 'SOURCE' ? <Tooltip label="Set as target"><ActionIcon size="xs" color="green" variant="subtle" onClick={() => void onDatabase('TARGET', source, schema, table)}>T</ActionIcon></Tooltip> : null}</Group></div>; })}</div> : null}</div>; })}</div> : null}</div>)}{assets.length ? <><Text size="xs" fw={800} c="dimmed" mt="sm" px={5}>MANAGED FILES</Text>{assets.map((asset) => <div className="mapx-tree-table" key={asset.id}><span title={asset.name}>{asset.name}</span><Tooltip label="Add file source"><ActionIcon size="xs" variant="subtle" onClick={() => onFile(asset)}>S</ActionIcon></Tooltip></div>)}</> : null}</Stack></ScrollArea></aside>;
}

function MappingObjectNode({ data, selected }: NodeProps<MappingFlowNode>) {
  const [collapsed, setCollapsed] = useState(false);
  const isTarget = data.kind === 'TARGET';
  const isTransform = data.kind === 'TRANSFORM';
  const isStaging = data.kind === 'STAGING';
  return (
    <div className={`mapx-flow-node is-${data.kind.toLowerCase()} ${selected ? 'is-selected' : ''}`}>
      <NodeResizer isVisible={selected} minWidth={data.kind === 'TRANSFORM' ? 170 : 120} minHeight={62} onResizeEnd={(_, size) => data.onResize?.({ width: Math.round(size.width), height: Math.round(size.height) })} />
      {!isTarget ? <Handle type="target" position={Position.Left} id="pipeline-in" className={`mapx-pipeline-handle ${isStaging ? 'is-staging-drop' : ''}`} title={isStaging ? 'Drop an output here to create a staging column' : undefined} /> : null}
      <div className="mapx-flow-node-head">
        <button type="button" className="mapx-node-collapse" onClick={() => setCollapsed((value) => !value)} aria-label={collapsed ? 'Expand object' : 'Collapse object'}>{collapsed ? '+' : '-'}</button>
        <div className="mapx-flow-node-icon"><IconArrowsExchange size={15} /></div>
        <div>{data.kind === 'STAGING' ? <input className="mapx-node-name-input" defaultValue={data.title} onBlur={(event) => data.onRename?.(event.currentTarget.value)} aria-label="Staging table name" /> : <b title={data.title}>{data.title}</b>}<small title={data.subtitle}>{data.subtitle}</small></div>
        <Badge size="xs" variant="light" color={isTarget ? 'green' : isTransform || isStaging ? 'violet' : 'blue'}>{data.kind}</Badge>
        {isTransform ? <button type="button" className="mapx-node-configure" onClick={data.onEdit}>Configure</button> : null}
        <button type="button" className="mapx-node-delete" onClick={data.onDelete} aria-label={`Remove ${data.title}`}>x</button>
      </div>
      {!collapsed ? <Stack gap={0} className="mapx-flow-ports">
        {data.ports.map((port) => (
          <div className={`mapx-flow-port ${port.joinSelected ? 'is-join-selected' : ''}`} key={port.qualified}>
            {!isTarget && port.input !== false ? <Handle type={data.kind === 'SOURCE' ? 'source' : 'target'} position={Position.Left} id={`in:${port.qualified}`} className="mapx-column-handle" onClick={(event) => { if (data.kind !== 'SOURCE') return; event.stopPropagation(); data.onJoinPort?.(port.qualified); }} /> : null}
            {isStaging ? <input className="mapx-port-name-input" defaultValue={port.name} aria-label="Staging column name" onBlur={(event) => data.onRenamePort?.(port, event.currentTarget.value)} /> : <span title={port.qualified}>{port.name}</span>}
            {isStaging ? <span className="mapx-port-type-editor"><input defaultValue={port.dataType || ''} placeholder="inherit type" aria-label={`${port.name} data type`} onBlur={(event) => data.onRetypePort?.(port, event.currentTarget.value)} /><button type="button" onClick={() => data.onDeletePort?.(port)} aria-label={`Remove ${port.name}`}>x</button></span> : port.action || port.dataType ? <small title={port.action || port.dataType}>{port.action || port.dataType}</small> : null}
            {!isTarget && port.output !== false ? <Handle type="source" position={Position.Right} id={`out:${port.qualified}`} className="mapx-column-handle" /> : null}
            {isTarget ? <Handle type="target" position={Position.Left} id={`in:${port.qualified}`} className="mapx-column-handle" /> : null}
          </div>
        ))}
        {!data.ports.length ? <Text size="xs" c="dimmed" p="sm">Discover columns to expose mapping ports.</Text> : null}
        {data.kind === 'STAGING' ? <Button size="compact-xs" variant="subtle" m="xs" onClick={data.onAddColumn}>+ column</Button> : null}
      </Stack> : null}
      {!isTarget ? <Handle type="source" position={Position.Right} id="pipeline-out" className="mapx-pipeline-handle" /> : null}
    </div>
  );
}

function buildNodes(spec: MappingSpec, sourceColumns: string[], columnTypes: Record<string, string>, onChange: (next: MappingSpec | ((current: MappingSpec) => MappingSpec)) => void, onEditTransforms: () => void, onJoinPort?: (nodeId: string, qualified: string) => void, pendingJoin?: string): MappingFlowNode[] {
  const positions = spec.canvas?.positions || {};
  const sizes = spec.canvas?.sizes || {};
  const visibleSources = spec.sources.filter(isConfiguredSource);
  const nodes: MappingFlowNode[] = visibleSources.map((source, index) => {
    const prefix = `${source.alias}.`;
    const ports = sourceColumns
      .filter((column) => column.toLowerCase().startsWith(prefix.toLowerCase()))
      .map((column) => ({ name: column.slice(prefix.length), qualified: column, dataType: columnTypes[column] || source.columns?.find((item) => item.name.toLowerCase() === column.slice(prefix.length).toLowerCase())?.type || '', joinSelected: pendingJoin?.toLowerCase() === column.toLowerCase() }));
    return {
      id: source.id,
      type: 'mappingNode',
      position: positions[source.id] || { x: 28, y: 38 + index * 138 },
      data: {
        kind: 'SOURCE',
        title: source.alias,
        subtitle: source.type === 'FILE' ? `Managed file ${source.assetId || ''}` : [source.schema, source.table].filter(Boolean).join('.') || 'Choose a table',
        ports,
        onJoinPort: (qualified) => onJoinPort?.(source.id, qualified),
        onDelete: () => onChange((current) => ({ ...current, sources: current.sources.filter((item) => item.id !== source.id), joins: current.joins.filter((join) => !join.left.toLowerCase().startsWith(`${source.alias.toLowerCase()}.`) && !join.right.toLowerCase().startsWith(`${source.alias.toLowerCase()}.`)), columns: current.columns.filter((column) => !column.source.toLowerCase().startsWith(`${source.alias.toLowerCase()}.`)), canvas: { ...withoutCanvasNodes(current.canvas, new Set([source.id])), links: (current.canvas?.links || []).filter((link) => !link.source.toLowerCase().startsWith(`${source.alias.toLowerCase()}.`) && !link.target.toLowerCase().startsWith(`${source.alias.toLowerCase()}.`)) } }))
      }
    };
  });

  const transforms = spec.transforms || [];
  transforms.forEach((transform, index) => {
    const id = transform.id || `transform-${index}`;
    nodes.push({
      id,
      type: 'mappingNode',
      draggable: true,
      deletable: false,
      position: positions[id] || { x: 220 + index * 195, y: 55 },
      data: { kind: 'TRANSFORM', title: transformLabel(transform.type), subtitle: transformSummary(transform), ports: transformPorts(transform, id, sourceColumns, columnTypes), transformType: transform.type, onEdit: onEditTransforms, onDelete: () => onChange((current) => ({ ...current, transforms: (current.transforms || []).filter((item, position) => String(item.id || `transform-${position}`) !== id), canvas: { ...withoutCanvasNodes(current.canvas, new Set([id])), links: (current.canvas?.links || []).filter((link) => !link.source.startsWith(`${id}.`) && !link.target.startsWith(`${id}.`)) } })) }
    });
  });

  (spec.stagingTables || []).forEach((staging, index) => {
    nodes.push({
      id: staging.id, type: 'mappingNode', position: positions[staging.id] || { x: 220 + transforms.length * 195, y: 55 + index * 145 },
      data: {
        kind: 'STAGING', title: staging.name, subtitle: 'Virtual staging and column shaping', ports: staging.columns.map((column) => ({ name: column, qualified: `${staging.name}.${column}`, dataType: staging.columnTypes?.[column] || '', input: true, output: true })),
        onAddColumn: () => onChange((current) => ({ ...current, stagingTables: (current.stagingTables || []).map((table) => table.id === staging.id ? { ...table, columns: [...table.columns, uniqueColumnName(table.columns, `column_${table.columns.length + 1}`)] } : table) })),
        onRenamePort: (port, raw) => onChange((current) => renameStagingColumn(current, staging.id, port.name, raw)),
        onRetypePort: (port, raw) => onChange((current) => ({ ...current, stagingTables: (current.stagingTables || []).map((table) => table.id === staging.id ? { ...table, columnTypes: { ...(table.columnTypes || {}), [port.name]: raw.trim() } } : table) })),
        onDeletePort: (port) => onChange((current) => ({ ...current, stagingTables: (current.stagingTables || []).map((table) => table.id === staging.id ? { ...table, columns: table.columns.filter((column) => column !== port.name), columnTypes: Object.fromEntries(Object.entries(table.columnTypes || {}).filter(([column]) => column !== port.name)) } : table), columns: current.columns.filter((column) => column.source !== `${staging.name}.${port.name}`), canvas: { ...(current.canvas || {}), links: (current.canvas?.links || []).filter((link) => link.source !== `${staging.name}.${port.name}` && link.target !== `${staging.name}.${port.name}`) } })),
        onRename: (raw) => onChange((current) => { const nextName = uniqueStagingName(current, safeAlias(raw), staging.id); const oldPrefix = `${staging.name}.`; const nextPrefix = `${nextName}.`; return { ...current, stagingTables: (current.stagingTables || []).map((table) => table.id === staging.id ? { ...table, name: nextName } : table), columns: current.columns.map((column) => column.source.startsWith(oldPrefix) ? { ...column, source: nextPrefix + column.source.slice(oldPrefix.length) } : column), canvas: { ...(current.canvas || {}), links: (current.canvas?.links || []).map((link) => ({ ...link, source: link.source.startsWith(oldPrefix) ? nextPrefix + link.source.slice(oldPrefix.length) : link.source, target: link.target.startsWith(oldPrefix) ? nextPrefix + link.target.slice(oldPrefix.length) : link.target })) } }; }),
        onDelete: () => onChange((current) => ({ ...current, stagingTables: (current.stagingTables || []).filter((table) => table.id !== staging.id), canvas: { ...withoutCanvasNodes(current.canvas, new Set([staging.id])), links: (current.canvas?.links || []).filter((link) => !link.source.startsWith(`${staging.name}.`) && !link.target.startsWith(`${staging.name}.`)) } }))
      }
    });
  });

  const targetNames = [...new Set([...(spec.target.columns || []).map((column) => column.name), ...spec.columns.filter((column) => column.action !== 'UNUSED').map((column) => column.target).filter(Boolean)])];
  const targetPorts = targetNames.map((name) => {
    const column = spec.columns.find((candidate) => candidate.target.toLowerCase() === name.toLowerCase());
    return { name, qualified: name, action: column ? (column.action === 'MASK' ? `${column.action}: ${column.maskFunction || 'rule'}` : column.action) : '', dataType: columnTypes[`target.${name}`] || spec.target.columns?.find((candidate) => candidate.name.toLowerCase() === name.toLowerCase())?.type || '' };
  });
  if (isConfiguredTarget(spec)) {
    nodes.push({
      id: TARGET_NODE,
      type: 'mappingNode',
      deletable: false,
      position: positions[TARGET_NODE] || { x: transforms.length ? 415 + transforms.length * 195 : 415, y: Math.max(50, visibleSources.length * 65) },
      data: {
        kind: 'TARGET',
        title: spec.target.type === 'DATABASE' ? spec.target.table || 'Target table' : spec.target.type === 'FILE' ? `${spec.target.format || 'CSV'} output` : 'Preview output',
        subtitle: spec.target.type === 'DATABASE' ? [spec.target.schema, spec.target.table].filter(Boolean).join('.') : 'Mapped result',
        ports: targetPorts,
        onDelete: () => onChange((current) => ({ ...current, target: { type: 'PREVIEW', preAction: 'NONE', format: 'CSV' }, columns: [], canvas: withoutCanvasNodes(current.canvas, new Set([TARGET_NODE])) }))
      }
    });
  }
  return nodes.map((node) => ({
    ...node,
    style: { ...node.style, width: sizes[node.id]?.width || (node.data.kind === 'TRANSFORM' ? 185 : 160), ...(sizes[node.id]?.height ? { height: sizes[node.id].height } : {}) },
    data: {
      ...node.data,
      onResize: (size) => onChange((current) => ({ ...current, canvas: { ...(current.canvas || {}), sizes: { ...(current.canvas?.sizes || {}), [node.id]: size } } }))
    }
  }));
}

function buildEdges(spec: MappingSpec, aliasToId: Map<string, string>): Edge[] {
  const edges: Edge[] = [];
  for (const column of spec.columns) {
    if (!column.source || !column.target || column.action === 'UNUSED' || column.action === 'LITERAL') continue;
    const alias = column.source.split('.')[0]?.toLowerCase();
    const source = aliasToId.get(alias) || nodeForQualified(spec, column.source);
    if (!source) continue;
    const masked = column.action === 'MASK';
    edges.push({
      id: `column:${column.id}`,
      source,
      target: TARGET_NODE,
      sourceHandle: `out:${column.source}`,
      targetHandle: `in:${column.target}`,
      type: 'smoothstep',
      label: masked ? `MASK ${column.maskFunction || ''}`.trim() : 'COPY',
      animated: masked,
      style: { stroke: masked ? '#7c3aed' : '#228be6', strokeWidth: 2 },
      labelStyle: { fill: masked ? '#7c3aed' : '#1971c2', fontWeight: 700, fontSize: 10 },
      markerEnd: { type: MarkerType.ArrowClosed, color: masked ? '#7c3aed' : '#228be6' }
    });
  }
  for (const join of spec.joins) {
    const edge = joinEdge(join, aliasToId);
    if (edge) edges.push(edge);
  }
  for (const link of spec.canvas?.links || []) {
    const sourceNode = nodeForQualified(spec, link.source);
    const targetNode = nodeForQualified(spec, link.target);
    if (!sourceNode || !targetNode) continue;
    edges.push({ id: `wire:${link.id}`, source: sourceNode, target: targetNode, sourceHandle: `out:${link.source}`, targetHandle: `in:${link.target}`, type: 'smoothstep', label: 'DATA', style: { stroke: '#0ca678', strokeWidth: 2 }, markerEnd: { type: MarkerType.ArrowClosed, color: '#0ca678' } });
  }
  return edges;
}

function joinEdge(join: MappingSpec['joins'][number], aliasToId: Map<string, string>): Edge | null {
  const source = aliasToId.get(join.left.split('.')[0]?.toLowerCase());
  const target = aliasToId.get(join.right.split('.')[0]?.toLowerCase());
  if (!source || !target) return null;
  return {
    id: `join:${join.id}`,
    source,
    target,
    sourceHandle: `out:${join.left}`,
    targetHandle: `in:${join.right}`,
    type: 'smoothstep',
    label: `${join.type} JOIN`,
    style: { stroke: '#f59f00', strokeWidth: 2, strokeDasharray: '6 4' },
    labelStyle: { fill: '#b26a00', fontWeight: 700, fontSize: 10 }
  };
}

function upsertColumn(columns: MappingColumn[], source: string, target: string): MappingColumn[] {
  const existing = columns.find((column) => column.target.toLowerCase() === target.toLowerCase());
  if (existing) return columns.map((column) => column.id === existing.id ? { ...column, source, action: 'COPY' } : column);
  return [...columns, { id: crypto.randomUUID(), source, target, action: 'COPY' }];
}

function decodeHandle(handle: string) {
  if (handle.startsWith('out:')) return handle.slice(4);
  if (handle.startsWith('in:')) return handle.slice(3);
  return '';
}

function nodeForQualified(spec: MappingSpec, qualified: string) {
  const alias = qualified.split('.')[0]?.toLowerCase();
  return spec.sources.find((source) => source.alias.toLowerCase() === alias)?.id
    || (spec.stagingTables || []).find((table) => table.name.toLowerCase() === alias)?.id
    || (spec.transforms || []).find((transform, index) => String(transform.id || `transform-${index}`).toLowerCase() === alias)?.id;
}

function transformPorts(transform: Record<string, unknown>, id: string, sourceColumns: string[], columnTypes: Record<string, string>): Port[] {
  const base = [...new Map(sourceColumns.map((qualified) => {
    const name = qualified.split('.').pop() || qualified;
    return [name.toLowerCase(), { name, qualified: `${id}.${name}`, dataType: columnTypes[qualified] || '', input: true, output: true } as Port];
  })).values()];
  const outputs: Array<{ name: string; input?: boolean }> = [];
  const type = String(transform.type || '').toUpperCase();
  if (type === 'EXPRESSION') arrayRecords(transform.columns).forEach((column) => { if (textValue(column.name)) outputs.push({ name: textValue(column.name), input: false }); });
  if (type === 'AGGREGATOR') {
    listValues(transform.groupBy).forEach((name) => outputs.push({ name: name.split('.').pop() || name }));
    arrayRecords(transform.aggregates).forEach((aggregate) => { if (textValue(aggregate.name)) outputs.push({ name: textValue(aggregate.name), input: false }); });
  }
  if (type === 'SEQUENCE') outputs.push({ name: textValue(transform.name) || 'seq_no', input: false });
  if (type === 'RANK') outputs.push({ name: textValue(transform.name) || 'rank_in_group', input: false });
  if (type === 'PIVOT') listValues(transform.values).forEach((name) => outputs.push({ name: safeAlias(name), input: false }));
  for (const output of outputs) {
    const existing = base.find((port) => port.name.toLowerCase() === output.name.toLowerCase());
    if (existing) existing.output = true;
    else base.push({ name: output.name, qualified: `${id}.${output.name}`, input: output.input !== false, output: true });
  }
  return base;
}

function arrayRecords(value: unknown): Array<Record<string, unknown>> { return Array.isArray(value) ? value.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object' && !Array.isArray(item)) : []; }
function listValues(value: unknown): string[] { return Array.isArray(value) ? value.map(textValue).filter(Boolean) : textValue(value).split(',').map((item) => item.trim()).filter(Boolean); }
function textValue(value: unknown): string { return value == null ? '' : String(value).trim(); }

function resolveStagingSource(spec: MappingSpec, stagingColumn: string) {
  const visited = new Set<string>(); let current = stagingColumn;
  while (!visited.has(current)) {
    visited.add(current);
    const link = (spec.canvas?.links || []).find((item) => item.target === current);
    if (!link) break;
    current = link.source;
  }
  return current;
}

function transformLabel(type: string) {
  return type.replace(/_/g, ' ').toLowerCase().replace(/(^|\s)\S/g, (letter) => letter.toUpperCase());
}

function transformSummary(transform: Record<string, unknown>) {
  const type = String(transform.type || '').toUpperCase();
  if (type === 'FILTER') return String(transform.condition || 'Condition not configured');
  if (type === 'LIMIT') return `${transform.rows || 'No'} row limit`;
  if (type === 'SORTER') return 'Ordered pipeline stage';
  if (type === 'LOOKUP') return `Lookup ${transform.table || 'table not configured'}`;
  return 'Preserved visual transformation';
}

type PlacementKind = 'SOURCE' | 'TRANSFORM' | 'STAGING' | 'TARGET';

function findOpenCanvasPosition(spec: MappingSpec, kind: PlacementKind) {
  const positions = spec.canvas?.positions || {};
  const sizes = spec.canvas?.sizes || {};
  const sources = spec.sources.filter(isConfiguredSource);
  const transforms = spec.transforms || [];
  const staging = spec.stagingTables || [];
  const objects: Array<{ id: string; kind: PlacementKind; x: number; y: number; width: number; height: number }> = [];
  const add = (id: string, objectKind: PlacementKind, fallback: { x: number; y: number }, portCount = 1) => {
    if (kind === 'TARGET' && id === TARGET_NODE) return;
    const position = positions[id] || fallback;
    const size = sizes[id];
    objects.push({
      id,
      kind: objectKind,
      x: position.x,
      y: position.y,
      width: size?.width || (objectKind === 'TRANSFORM' ? 185 : 160),
      height: size?.height || Math.min(220, Math.max(70, 39 + Math.max(1, portCount) * 20))
    });
  };
  sources.forEach((source, index) => add(source.id, 'SOURCE', { x: 28, y: 38 + index * 138 }, source.columns?.length || 1));
  transforms.forEach((transform, index) => add(String(transform.id || `transform-${index}`), 'TRANSFORM', { x: 220 + index * 195, y: 55 }, transformPorts(transform, String(transform.id || `transform-${index}`), [], {}).length));
  staging.forEach((table, index) => add(table.id, 'STAGING', { x: 220 + transforms.length * 195, y: 55 + index * 145 }, table.columns.length));
  if (isConfiguredTarget(spec)) add(TARGET_NODE, 'TARGET', { x: transforms.length ? 415 + transforms.length * 195 : 415, y: Math.max(50, sources.length * 65) }, spec.target.columns?.length || spec.columns.length || 1);

  const lane = (objectKind: PlacementKind) => objects.filter((object) => object.kind === objectKind);
  const right = (items: typeof objects, fallback: number) => items.length ? Math.max(...items.map((item) => item.x + item.width)) : fallback;
  const centeredY = (items: typeof objects) => items.length ? Math.max(38, Math.round(items.reduce((sum, item) => sum + item.y, 0) / items.length)) : 55;
  let desired = { x: 28, y: 38 };
  if (kind === 'SOURCE') {
    const peers = lane('SOURCE');
    desired = peers.length
      ? { x: Math.min(...peers.map((item) => item.x)), y: Math.max(...peers.map((item) => item.y + item.height)) + 24 }
      : { x: 28, y: 38 };
  } else if (kind === 'TRANSFORM') {
    const peers = lane('TRANSFORM');
    desired = peers.length
      ? { x: right(peers, 220) + 48, y: centeredY(peers) }
      : { x: right(lane('SOURCE'), 150) + 70, y: centeredY(lane('SOURCE')) };
  } else if (kind === 'STAGING') {
    const peers = lane('STAGING');
    desired = peers.length
      ? { x: Math.min(...peers.map((item) => item.x)), y: Math.max(...peers.map((item) => item.y + item.height)) + 24 }
      : { x: right([...lane('SOURCE'), ...lane('TRANSFORM')], 220) + 55, y: centeredY(lane('TRANSFORM').length ? lane('TRANSFORM') : lane('SOURCE')) };
  } else {
    const pipeline = objects.filter((object) => object.kind !== 'TARGET');
    desired = { x: right(pipeline, 360) + 70, y: centeredY(lane('STAGING').length ? lane('STAGING') : lane('TRANSFORM').length ? lane('TRANSFORM') : lane('SOURCE')) };
  }

  const width = kind === 'TRANSFORM' ? 185 : 160;
  const height = 100;
  const collides = (candidate: { x: number; y: number }) => objects.some((object) =>
    candidate.x < object.x + object.width + 22
    && candidate.x + width + 22 > object.x
    && candidate.y < object.y + object.height + 18
    && candidate.y + height + 18 > object.y
  );
  const yOffsets = [0, 125, -125, 250, -250, 375, -375, 500];
  for (let column = 0; column < 6; column++) {
    for (const yOffset of yOffsets) {
      const candidate = { x: Math.max(18, desired.x + column * (width + 48)), y: Math.max(24, desired.y + yOffset) };
      if (!collides(candidate)) return candidate;
    }
  }
  return { x: right(objects, 28) + 70, y: 38 };
}

function functionOutputName(expression: string, ordinal: number) {
  const functionName = expression.match(/^\s*([A-Za-z_][A-Za-z0-9_]*)/)?.[1] || 'expression';
  return `${safeAlias(functionName)}_${ordinal}`;
}

function safeAlias(value: string) { const clean = value.toLowerCase().replace(/[^a-z0-9_]/g, '_').replace(/^_+/, ''); return /^[a-z_]/.test(clean) ? clean || 'source' : `source_${clean || '1'}`; }
function uniqueAlias(spec: MappingSpec, base: string) { const used = new Set(spec.sources.map((source) => source.alias)); let value = base; let suffix = 2; while (used.has(value)) value = `${base}_${suffix++}`; return value; }
function uniqueStagingName(spec: MappingSpec, base: string, id: string) { const used = new Set((spec.stagingTables || []).filter((table) => table.id !== id).map((table) => table.name)); let value = base; let suffix = 2; while (used.has(value)) value = `${base}_${suffix++}`; return value; }
function withoutCanvasNodes(canvas: MappingCanvas | undefined, ids: Set<string>): MappingCanvas { return { ...(canvas || {}), positions: Object.fromEntries(Object.entries(canvas?.positions || {}).filter(([id]) => !ids.has(id))), sizes: Object.fromEntries(Object.entries(canvas?.sizes || {}).filter(([id]) => !ids.has(id))) }; }
function isConfiguredSource(source: MappingSpec['sources'][number]) { return source.type === 'FILE' ? !!source.assetId : !!source.dataSourceId && !!source.table; }
function isConfiguredTarget(spec: MappingSpec) { return spec.target.type === 'DATABASE' ? !!spec.target.dataSourceId && !!spec.target.table : spec.target.type === 'FILE' || spec.columns.length > 0; }
function uniqueColumnName(columns: string[], raw: string) { const base = safeAlias(raw) || 'column'; const used = new Set(columns.map((column) => column.toLowerCase())); let value = base; let suffix = 2; while (used.has(value.toLowerCase())) value = `${base}_${suffix++}`; return value; }
function renameStagingColumn(spec: MappingSpec, stagingId: string, oldName: string, raw: string): MappingSpec {
  const staging = (spec.stagingTables || []).find((table) => table.id === stagingId);
  if (!staging) return spec;
  const nextName = uniqueColumnName(staging.columns.filter((column) => column !== oldName), raw);
  if (nextName === oldName) return spec;
  const oldQualified = `${staging.name}.${oldName}`; const nextQualified = `${staging.name}.${nextName}`;
  const oldType = staging.columnTypes?.[oldName] || '';
  return {
    ...spec,
    stagingTables: (spec.stagingTables || []).map((table) => table.id === stagingId ? { ...table, columns: table.columns.map((column) => column === oldName ? nextName : column), columnTypes: { ...Object.fromEntries(Object.entries(table.columnTypes || {}).filter(([column]) => column !== oldName)), ...(oldType ? { [nextName]: oldType } : {}) } } : table),
    columns: spec.columns.map((column) => column.source === oldQualified ? { ...column, source: nextQualified } : column),
    canvas: { ...(spec.canvas || {}), links: (spec.canvas?.links || []).map((link) => ({ ...link, source: link.source === oldQualified ? nextQualified : link.source, target: link.target === oldQualified ? nextQualified : link.target })) }
  };
}
