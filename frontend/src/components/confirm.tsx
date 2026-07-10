'use client';

import { useCallback, useRef, useState } from 'react';
import { Button, Group, Modal, Text } from '@mantine/core';

export type ConfirmOptions = {
  title?: string;
  message: string;
  okText?: string;
  danger?: boolean;
};

/**
 * Promise-based confirm dialog (replaces window.confirm). Usage:
 *   const { confirm, confirmElement } = useConfirm();
 *   ... if (!(await confirm({ message: '...' }))) return; ...
 *   return <>{confirmElement}...</>
 */
export function useConfirm() {
  const [opts, setOpts] = useState<ConfirmOptions | null>(null);
  const resolver = useRef<((value: boolean) => void) | null>(null);

  const confirm = useCallback(
    (options: ConfirmOptions) =>
      new Promise<boolean>((resolve) => {
        resolver.current = resolve;
        setOpts(options);
      }),
    []
  );

  const close = (value: boolean) => {
    resolver.current?.(value);
    resolver.current = null;
    setOpts(null);
  };

  const confirmElement = (
    <Modal opened={!!opts} onClose={() => close(false)} title={opts?.title || 'Please confirm'} zIndex={400}>
      <Text size="sm" style={{ whiteSpace: 'pre-wrap' }}>
        {opts?.message}
      </Text>
      <Group justify="flex-end" mt="md">
        <Button variant="light" onClick={() => close(false)}>
          Cancel
        </Button>
        <Button color={opts?.danger ? 'red' : 'blue'} onClick={() => close(true)}>
          {opts?.okText || 'Continue'}
        </Button>
      </Group>
    </Modal>
  );

  return { confirm, confirmElement };
}
