import { defineConfig, devices } from '@playwright/test';

const externalBaseUrl = process.env.AUTH003_BASE_URL?.trim();
const baseURL = externalBaseUrl || 'http://127.0.0.1:3103';

export default defineConfig({
  testDir: './e2e/auth-003',
  outputDir: './test-results/auth-003/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/auth-003/results.json' }],
    ['junit', { outputFile: 'test-results/auth-003/junit.xml' }]
  ],
  use: {
    ...devices['Desktop Edge'],
    baseURL,
    channel: 'msedge',
    headless: true,
    trace: 'on',
    screenshot: 'on',
    video: 'retain-on-failure'
  },
  webServer: externalBaseUrl
    ? undefined
    : {
        command: 'npm.cmd run dev -- --webpack --hostname 127.0.0.1 --port 3103',
        url: baseURL,
        reuseExistingServer: false,
        timeout: 180_000,
        stdout: 'pipe',
        stderr: 'pipe'
      }
});
