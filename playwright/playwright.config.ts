import { defineConfig, devices } from '@playwright/test';
import {defineBddConfig} from "playwright-bdd";

import "dotenv/config";


const testDir = defineBddConfig({
  features: './tests/e2e',
  steps: './tests/steps'
});
export default defineConfig({
  /* Indicates where the test steps definition are */
  testDir,


  timeout: 120_000,

  /* Run tests in files in parallel */
  fullyParallel: true,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: process.env.CI ? 2 : 0,
  /* Opt out of parallel tests on CI. */
  workers: process.env.CI ? 1 : undefined,
  /* Reporter to use. See https://playwright.dev/docs/test-reporters */
  reporter: 'html',
  /* Configure projects for major browsers */
  projects: [
    {
      name: "api-tests",
      testDir,
    }
  ],
});
