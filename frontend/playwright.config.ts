import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  testIgnore: [],

  globalTimeout: 1_800_000,
  timeout: 60_000,
  expect: {
    timeout: 20_000,
  },

  fullyParallel: false,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env['CI'],
  /* Retry on CI only */
  retries: process.env['CI'] ? 1 : 0,
  workers: 1,
  /* Reporter to use. See https://playwright.dev/docs/test-reporters */
  reporter: [
    process.env['CI'] ? ['dot'] : ['line'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }]
  ],
  /* Shared settings for all the projects below. See https://playwright.dev/docs/api/class-testoptions. */
  use: {
    /* Base URL to use in actions like `await page.goto('/')`. */
    baseURL: process.env['BASE_URL'] || 'http://localhost:8080/',
    headless: true,
    screenshot: 'only-on-failure',
    /* Collect trace when retrying the failed test. See https://playwright.dev/docs/trace-viewer */
    trace: 'on-first-retry',
    ignoreHTTPSErrors: true
  },

  /* Configure projects for major browsers */
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
      testIgnore: [
        '**/fallback-killswitch.spec.ts',
        '**/fallback-insecure.spec.ts',
        '**/share-link-killswitch.spec.ts',
        '**/share-link-insecure.spec.ts',
      ],
    },

    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
      testIgnore: [
        '**/fallback-killswitch.spec.ts',
        '**/fallback-insecure.spec.ts',
        '**/share-link-killswitch.spec.ts',
        '**/share-link-insecure.spec.ts',
      ],
      testMatch: ['**/share-link-firefox.spec.ts'],
    },

    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
      testIgnore: [
        '**/fallback-killswitch.spec.ts',
        '**/fallback-insecure.spec.ts',
        '**/share-link-killswitch.spec.ts',
        '**/share-link-insecure.spec.ts',
      ],
      testMatch: ['**/share-link-webkit.spec.ts'],
    },

    {
      name: 'killswitch',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: process.env['BASE_URL'] || 'http://localhost:8080/',
      },
      testMatch: [
        '**/fallback-killswitch.spec.ts',
        '**/share-link-killswitch.spec.ts',
      ],
    },

    {
      name: 'insecure',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: process.env['BASE_URL_INSECURE'] || 'http://localhost:8081/',
      },
      testMatch: [
        '**/fallback-insecure.spec.ts',
        '**/share-link-insecure.spec.ts',
      ],
    },
  ],
});
