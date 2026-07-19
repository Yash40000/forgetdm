'use client';

import { Suspense, useState } from 'react';
import type { FormEvent } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Alert, Button, Center, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconLock } from '@tabler/icons-react';

import { apiPost } from '@/lib/api';

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const nextPath = safeNextPath(searchParams.get('next'));

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!username.trim() || busy) return;
    setBusy(true);
    setError(null);
    try {
      await apiPost('/api/auth/login', { username: username.trim(), password });
      router.replace(nextPath);
    } catch (loginError) {
      setError(loginError instanceof Error ? loginError.message : 'Login failed');
      setBusy(false);
    }
  };

  return (
    <Center mih="100vh" p="md">
      <Paper className="forge-card" p="xl" w={380}>
        <form onSubmit={submit}>
          <Stack gap="md">
            <div>
              <div className="forge-brand-mark">FDM</div>
              <Title order={2} size="h3" mt="sm">
                Sign in to ForgeTDM
              </Title>
              <Text size="sm" c="dimmed">
                Use your ForgeTDM workspace credentials.
              </Text>
            </div>
            {error ? (
              <Alert color="red" icon={<IconLock size={16} />}>
                {error}
              </Alert>
            ) : null}
            <TextInput
              label="Username"
              autoComplete="username"
              autoFocus
              value={username}
              onChange={(event) => setUsername(event.currentTarget.value)}
            />
            <PasswordInput
              label="Password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.currentTarget.value)}
            />
            <Button type="submit" loading={busy} disabled={!username.trim()}>
              Sign in
            </Button>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}

/** Only allow same-origin path redirects — never absolute or off-origin URLs (open-redirect guard). */
function safeNextPath(raw: string | null) {
  if (!raw || !raw.startsWith('/')) return '/datascope';
  // Reject protocol-relative ("//host") and backslash-normalized ("/\host") bypasses.
  if (raw.length >= 2 && (raw[1] === '/' || raw[1] === '\\')) return '/datascope';
  // Defense in depth: the value must resolve to this exact origin.
  if (typeof window !== 'undefined') {
    try {
      if (new URL(raw, window.location.origin).origin !== window.location.origin) return '/datascope';
    } catch {
      return '/datascope';
    }
  }
  return raw;
}
