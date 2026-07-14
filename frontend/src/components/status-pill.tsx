'use client';

import { Badge, type BadgeProps } from '@mantine/core';

const colors: Record<string, BadgeProps['color']> = {
  ACTIVE: 'green',
  READY: 'green',
  COMPLETED: 'green',
  APPROVED: 'green',
  RUNNING: 'blue',
  CANCEL_REQUESTED: 'yellow',
  AWAITING_APPROVAL: 'yellow',
  PENDING_APPROVAL: 'yellow',
  PENDING: 'gray',
  DRAFT: 'gray',
  WARN: 'yellow',
  STALE: 'yellow',
  FAILED: 'red',
  REJECTED: 'red',
  CANCELED: 'gray',
  COMPLETED_WITH_ERRORS: 'yellow'
};

export function StatusPill({ value }: { value?: string | null }) {
  const label = value || 'UNKNOWN';
  return (
    <Badge size="sm" variant="light" color={colors[label.toUpperCase()] || 'blue'}>
      {label}
    </Badge>
  );
}
