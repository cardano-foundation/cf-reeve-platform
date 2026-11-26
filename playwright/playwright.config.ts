import {defineConfig} from '@playwright/test';
import {Status} from "allure-js-commons";
import {defineBddConfig} from "playwright-bdd";

import "dotenv/config";
import * as os from "node:os";


const testDir = defineBddConfig({
  features: './tests/e2e',
  steps: [
      './tests/steps',
      './utils/playwright-bdd-fixtures.ts'
  ]
});
export default defineConfig({
  /* Indicates where the test steps definition are */
  testDir,

  reporter: [
    ["line"],
    ["json", { outputFile: "test-results/results.json" }],
    [
      "allure-playwright",
      {
        resultsDir: "allure-results",
        detail: true,
        suiteTitle: true,
        links: {
          issue: {
            nameTemplate: "Issue #%s",
            urlTemplate: "https://issues.example.com/%s",
          },
          tms: {
            nameTemplate: "TMS #%s",
            urlTemplate: "https://tms.example.com/%s",
          },
          jira: {
            urlTemplate: (v) => `https://jira.example.com/browse/${v}`,
          },
        },
        categories: [
          {
            name: "foo",
            messageRegex: "bar",
            traceRegex: "baz",
            matchedStatuses: [Status.FAILED, Status.BROKEN],
          },
        ],
        environmentInfo: {
          os_platform: os.platform(),
          os_release: os.release(),
          os_version: os.version(),
          node_version: process.version,
        },
      },
    ],
  ],

  timeout: 120_000,

  /* Run tests in files in parallel */
  fullyParallel: true,
  /* Fail the build on CI if you accidentally left test.only in the source code. */
  forbidOnly: !!process.env.CI,
  /* Retry on CI only */
  retries: process.env.CI ? 1 : 0,
  /* Opt out of parallel tests on CI. */
  workers: process.env.CI ? 2 : undefined,
  projects: [
    {
      name: "api-tests",
      testDir,
    }
  ],
});
