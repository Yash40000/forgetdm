'use client';

import type { ComponentType, ReactNode } from 'react';
import { ActionIcon, Group, Paper, Text } from '@mantine/core';

export function MetricCard({
  label,
  value,
  hint,
  icon: Icon,
  tone
}: {
  label: string;
  value: string | number;
  hint: string;
  icon: ComponentType<{ size?: number }>;
  tone?: 'good' | 'warn';
}) {
  const color = tone === 'good' ? 'green' : tone === 'warn' ? 'yellow' : 'blue';
  return (
    <Paper className="forge-card" p="md">
      <Group justify="space-between" align="flex-start">
        <div>
          <Text size="xs" tt="uppercase" fw={800} c="dimmed">
            {label}
          </Text>
          <Text size="xl" fw={850}>
            {value}
          </Text>
          <Text size="xs" c="dimmed" className="forge-truncate">
            {hint}
          </Text>
        </div>
        <ActionIcon variant="light" color={color} size="lg" radius={8}>
          <Icon size={18} />
        </ActionIcon>
      </Group>
    </Paper>
  );
}

export function InfoRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <Group justify="space-between" gap="sm" wrap="nowrap">
      <Text size="sm" c="dimmed">
        {label}
      </Text>
      <Text size="sm" fw={700} ta="right" className="forge-truncate">
        {value}
      </Text>
    </Group>
  );
}

export function MiniStat({ label, value }: { label: string; value: string | number }) {
  return (
    <div>
      <Text size="xl" fw={850}>
        {value}
      </Text>
      <Text size="xs" c="dimmed" tt="uppercase" fw={800}>
        {label}
      </Text>
    </div>
  );
}
