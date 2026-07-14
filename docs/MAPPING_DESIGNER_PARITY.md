# Mapping Designer Functional Contract

This checklist is the regression contract for the React Mapping Designer. A UI migration is incomplete if any checked capability stops working, even when the replacement screen looks cleaner.

## Canvas and object model

- [x] Expand connection, schema, and table trees without leaving the designer.
- [x] Drag database tables onto the canvas at the drop position.
- [x] Add a table with source/target quick actions for keyboard and precise operation.
- [x] Load column names, data types, lengths reported by the connector immediately when an object is added.
- [x] Add the same source table more than once with unique aliases for self-joins.
- [x] Support one governed target and multiple database or managed-file sources.
- [x] Persist node positions, links, joins, staging objects, transformations, and zoom-safe layout in the mapping version.
- [x] Compact-all, collapse, move, resize, remove, auto-layout, fit, zoom, and clear canvas objects; positions and dimensions persist.
- [x] Button and double-click additions use collision-aware source, transformation, staging, and target lanes; deliberate drops preserve the user's exact position.
- [x] Auto-layout keeps the pipeline together from sources through transformations and staging to target instead of spreading objects across canvas corners.
- [x] Use the browser Fullscreen API so the canvas occupies the complete screen; Escape exits full screen.

## Port rules

- [x] Source input to source input creates a typed INNER, LEFT, RIGHT, or FULL join.
- [x] Input joins support drag-to-connect and click-first/click-second interaction with visible pending-port feedback.
- [x] Source, transformation, or staging output to transformation, staging, or target input creates a data mapping.
- [x] Invalid port combinations and self-links are rejected.
- [x] Links can be selected and removed with the toolbar, Backspace, or Delete.
- [x] Transformation nodes expose pass-through and derived output ports.
- [x] Target ports are loaded before mappings exist, so users can wire directly.

## Transformation and staging behavior

- [x] Filter, Expression, Aggregator, Sorter, Distinct, Limit, Router, Union, Lookup, Rank, Sequence, and Pivot stages.
- [x] Add, configure, reorder, and remove transformations; open configuration directly from a canvas node.
- [x] Multiple staging objects with editable names and columns.
- [x] Dropping an output on a staging object automatically creates a uniquely named column, carries its detected data type, and leaves both name and type editable inline.
- [x] Staging columns support inherited types or explicit type/length overrides.
- [x] Staging links compile into projected SQL, including guarded casts for valid SQL type declarations.
- [x] Router groups compile to transactional multi-target load statements.
- [x] Lookup, join, filter, expression, aggregate, sort, rank, sequence, pivot, union, distinct, and dialect row-limit compilation.
- [x] Searchable Informatica-style transformation and dialect function browser; aggregate functions create Aggregator objects while scalar, date, conversion, conditional, and window functions create Expression objects.
- [x] Catalog function templates are marked unconfigured until reviewed, then receive structural validation and a one-row database compilation preflight before execution.

## Build, run, and governance

- [x] Dialect-aware function library for PostgreSQL/H2, Oracle, SQL Server, DB2, and MySQL.
- [x] Test an expression against one source row.
- [x] Automatic column mapping and editable COPY, MASK, LITERAL, and UNUSED actions.
- [x] Generated SQL editor, validation, bounded preview, and exportable column lineage.
- [x] Preview, managed-file output, database load, pre-action, cancellation, status, row counts, and rejection evidence.
- [x] Immutable mapping versions and restore-as-new-version behavior.
- [x] Mapping deletion requires explicit confirmation.
- [x] Approved mapping execution can be launched from Auto Provision and Self-Service.
- [x] Legacy saved mappings retain transforms, canvas data, SQL, load statements, joins, and target settings when opened.

## Required regression checks

1. Drag two sources and one target, create a composite join, and wire at least three target columns.
2. Insert an Expression and a Filter between source and target, then verify generated SQL and preview.
3. Add a staging object, rename/retype columns, save, reopen, and verify links and positions survive.
4. Enter full screen, move nodes, exit with Escape, save, and reopen.
5. Execute a bounded preview and a disposable target load; verify lineage, counts, cancellation, and audit evidence.
6. Open one legacy designer mapping and verify no transformation, link, staging table, or load target is lost.
