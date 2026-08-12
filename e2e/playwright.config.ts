import { defineConfig, devices } from '@playwright/test';
import { existsSync } from 'node:fs';

const PORT = Number(process.env.SERVICE_ATLAS_PORT ?? 18099);
const BASE_URL = `http://127.0.0.1:${PORT}`;
const JAR = '../backend/build/libs/service-atlas.jar';

/**
 * Some environments ship a pre-installed Chromium that does not match Playwright's expected build.
 * `PLAYWRIGHT_CHROMIUM_PATH` points at it; otherwise Playwright's own download is used.
 */
const executablePath = process.env.PLAYWRIGHT_CHROMIUM_PATH;

export default defineConfig({
  testDir: './tests',
  timeout: 120_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',

  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    ...(executablePath ? { launchOptions: { executablePath } } : {}),
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],

  /**
   * Runs the packaged jar — the same artifact a user runs — against a throwaway data directory, so
   * the test never touches a real workspace database.
   */
  webServer: existsSync(JAR)
    ? {
        command:
          `java -jar ${JAR} --server.port=${PORT} ` +
          `--service-atlas.data-dir=./.e2e-data --spring.datasource.url=jdbc:h2:mem:e2e;DB_CLOSE_DELAY=-1`,
        url: `${BASE_URL}/api/v1/workspaces`,
        timeout: 120_000,
        reuseExistingServer: !process.env.CI,
        stdout: 'ignore',
        stderr: 'pipe',
      }
    : undefined,
});
