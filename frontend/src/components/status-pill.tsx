'use client';

import { Badge, type BadgeProps } from '@mantine/core';

const colors: Record<string, BadgeProps['color']> = {
  ACTIVE: 'green',
  READY: 'green',
  COMPLETED: 'green',
  APPROVED: 'green',
  RUNNING: 'blue',
  PENDING: 'gray',
  DRAFT: 'gray',
  WARN: 'yellow',
  STALE: 'yellow',
  FAILED: 'red',
  REJECTED: 'red'
};

export function StatusPill({ value }: { value?: string | null }) {
  const label = value || 'UNKNOWN';
  return (
    <Badge size="sm" variant="light" color={colors[label.toUpperCase()] || 'blue'}>
      {label}
    </Badge>
  );
}
