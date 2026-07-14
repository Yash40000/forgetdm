'use client';

import { Alert, Button, Group, Text } from '@mantine/core';
import { IconAlertTriangle, IconRefresh } from '@tabler/icons-react';

type QueryErrorBannerProps = {
  errors: Array<unknown>;
  onRetry?: () => void | Promise<unknown>;
  title?: string;
};

export function QueryErrorBanner({ errors, onRetry, title = 'Some data could not be loaded' }: QueryErrorBannerProps) {
  const messages = [...new Set(errors.filter(Boolean).map(errorText))];
  if (!messages.length) return null;

  return (
    <Alert color="red" variant="light" icon={<IconAlertTriangle size={18} />} title={title}>
      <Group justify="space-between" align="flex-start" wrap="wrap">
        <div>
          {messages.slice(0, 2).map((message) => (
            <Text size="sm" key={message}>
              {message}
            </Text>
          ))}
          {messages.length > 2 ? (
            <Text size="xs" c="dimmed" mt={3}>
              {messages.length - 2} additional request{messages.length - 2 === 1 ? '' : 's'} failed.
            </Text>
          ) : null}
        </div>
        {onRetry ? (
          <Button size="xs" variant="default" leftSection={<IconRefresh size={14} />} onClick={() => void onRetry()}>
            Retry
          </Button>
        ) : null}
      </Group>
    </Alert>
  );
}

function errorText(error: unknown) {
  if (error instanceof Error && error.message.trim()) return error.message;
  return String(error || 'The backend request failed.');
}
