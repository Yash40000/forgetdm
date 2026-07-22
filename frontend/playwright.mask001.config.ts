import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e/mask-001',
  outputDir: './test-results/mask-001/artifacts',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  forbidOnly: true,
  timeout: 90_000,
  expect: { timeout: 20_000 },
  reporter: 'list',
  use: {
    ...devices['Desktop Edge'],
    baseURL: process.env.MASK001_UI_BASE_URL?.trim() || 'http://127.0.0.1:3000',
    channel: 'msedge',
    headless: true,
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure'
  }
});
