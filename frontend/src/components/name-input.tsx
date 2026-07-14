'use client';

import { forwardRef } from 'react';
import { TextInput, type TextInputProps } from '@mantine/core';

/**
 * Input for user-defined names (policy name, job name, dataset name, etc.).
 * Forces the value to UPPER CASE regardless of how the user types it, and
 * renders with the shared "user-defined" treatment (accent tint + monospace)
 * so these fields are visually identifiable across the app.
 *
 * Do NOT use this for technical identifiers that map to real database objects
 * (schema, table, column names) or for login usernames — those stay as typed.
 */
export type NameInputProps = Omit<TextInputProps, 'value' | 'onChange'> & {
  value: string;
  /** Called with the already-uppercased value. */
  onChange: (value: string) => void;
};

export const NameInput = forwardRef<HTMLInputElement, NameInputProps>(function NameInput(
  { value, onChange, className, ...rest },
  ref
) {
  return (
    <TextInput
      ref={ref}
      value={value}
      onChange={(event) => onChange(event.currentTarget.value.toUpperCase())}
      autoCapitalize="characters"
      autoCorrect="off"
      spellCheck={false}
      className={className ? `forge-name-input ${className}` : 'forge-name-input'}
      {...rest}
    />
  );
});
