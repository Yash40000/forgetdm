'use client';

import { useState } from 'react';
import { ScrollArea, Text, TextInput } from '@mantine/core';
import {
  flexRender,
  getCoreRowModel,
  getFilteredRowModel,
  getSortedRowModel,
  useReactTable,
  type ColumnDef,
  type SortingState
} from '@tanstack/react-table';

type DataTableProps<T> = {
  data: T[];
  columns: ColumnDef<T>[];
  /** Show the global search box above the table. */
  searchable?: boolean;
  searchPlaceholder?: string;
  /** Scroll container height; omit for natural height. */
  maxHeight?: number;
  emptyMessage?: string;
  initialSorting?: SortingState;
  /** Extra class on the <table>, e.g. 'ds-picker-table'. */
  tableClassName?: string;
  onRowClick?: (row: T) => void;
  rowClassName?: (row: T) => string;
};

/**
 * Shared TanStack Table wrapper: global filter, click-to-sort headers, optional
 * row click. All read-mostly lists (blueprints, catalog picker, saved jobs) use
 * this instead of hand-rolled tables; heavily-editable grids stay bespoke.
 */
export function DataTable<T>({
  data,
  columns,
  searchable = true,
  searchPlaceholder = 'Search...',
  maxHeight,
  emptyMessage = 'Nothing to show yet.',
  initialSorting = [],
  tableClassName = '',
  onRowClick,
  rowClassName
}: DataTableProps<T>) {
  const [globalFilter, setGlobalFilter] = useState('');
  const [sorting, setSorting] = useState<SortingState>(initialSorting);
  // TanStack Table intentionally returns table helpers from this hook.
  // eslint-disable-next-line react-hooks/incompatible-library
  const table = useReactTable({
    data,
    columns,
    state: { globalFilter, sorting },
    onGlobalFilterChange: setGlobalFilter,
    onSortingChange: setSorting,
    globalFilterFn: 'includesString',
    getCoreRowModel: getCoreRowModel(),
    getFilteredRowModel: getFilteredRowModel(),
    getSortedRowModel: getSortedRowModel()
  });

  const rows = table.getRowModel().rows;
  const body = (
    <div className="forge-grid-panel">
      <table className={`forge-table ${tableClassName}`.trim()}>
        <thead>
          {table.getHeaderGroups().map((headerGroup) => (
            <tr key={headerGroup.id}>
              {headerGroup.headers.map((header) => {
                const canSort = header.column.getCanSort();
                const sorted = header.column.getIsSorted();
                return (
                  <th
                    key={header.id}
                    className={canSort ? 'is-sortable' : ''}
                    onClick={canSort ? header.column.getToggleSortingHandler() : undefined}
                    style={canSort ? { cursor: 'pointer', userSelect: 'none' } : undefined}
                  >
                    {flexRender(header.column.columnDef.header, header.getContext())}
                    {sorted === 'asc' ? ' asc' : sorted === 'desc' ? ' desc' : ''}
                  </th>
                );
              })}
            </tr>
          ))}
        </thead>
        <tbody>
          {rows.length ? (
            rows.map((row) => (
              <tr
                key={row.id}
                className={`${onRowClick ? 'is-clickable' : ''} ${rowClassName ? rowClassName(row.original) : ''}`.trim()}
                onClick={onRowClick ? () => onRowClick(row.original) : undefined}
              >
                {row.getVisibleCells().map((cell) => (
                  <td key={cell.id}>{flexRender(cell.column.columnDef.cell, cell.getContext())}</td>
                ))}
              </tr>
            ))
          ) : (
            <tr>
              <td colSpan={columns.length}>
                <Text c="dimmed" size="sm">
                  {emptyMessage}
                </Text>
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );

  return (
    <>
      {searchable ? (
        <TextInput
          placeholder={searchPlaceholder}
          value={globalFilter}
          onChange={(event) => setGlobalFilter(event.currentTarget.value)}
          mb="sm"
        />
      ) : null}
      {maxHeight ? <ScrollArea.Autosize mah={maxHeight}>{body}</ScrollArea.Autosize> : body}
    </>
  );
}
