'use client';

import { Component } from 'react';
import type { ErrorInfo, ReactNode } from 'react';
import { Alert, Button, Group, Paper, Stack, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';

type Props = { children: ReactNode; title?: string };
type State = { error: Error | null };

/** Catches render-time crashes so one broken panel never blanks the whole page. */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('ErrorBoundary caught', error, info.componentStack);
  }

  render() {
    if (!this.state.error) return this.props.children;
    return (
      <Paper className="forge-card" p="xl">
        <Stack gap="sm">
          <Alert color="red" icon={<IconAlertTriangle size={16} />} title={this.props.title || 'Something went wrong'}>
            <Text size="sm">{this.state.error.message}</Text>
          </Alert>
          <Group>
            <Button variant="light" onClick={() => this.setState({ error: null })}>
              Try again
            </Button>
            <Button variant="subtle" onClick={() => window.location.reload()}>
              Reload page
            </Button>
          </Group>
        </Stack>
      </Paper>
    );
  }
}
