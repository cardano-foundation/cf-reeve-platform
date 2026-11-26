# 🧪 Reeve API Automation Framework

This project uses [Playwright](https://playwright.dev/) with BDD-style tests using [playwright-bdd](https://github.com/folke/playwright-bdd).

## 📦 Installation

To install all dependencies, including Playwright and playwright-bdd:

- Install Playwright and Playwright-BDD
```
npm i -D @playwright/test playwright-bdd 
```
```
npx playwright install
```
- Install only Playwright-BDD
```
npm i -D playwright-bdd 
```
## Env file to run in local

1. Create a `.env` file at the root of the playwright folder.
2. Use next structure as example.
3. Ask a team member for the required environment variables & corresponding values for the API, KEYCLOAK and extra application necessary variables.

```
API_URL= <API env url> 
LOGIN_URL= <KEYCLOAK url>
MANAGER_USER= <Manager user name>
MANAGER_PASSWORD= <Manager user password>
API_LOG_REQUEST= <Boolean flag to show in logger the request body and params>
ORGANIZATION_ID= <Organization ID>
```

## ⚙️ Test run in local:

### Run all tests in feature files
    npm test
### You can run a specific .feature file by passing it as an argument:
    npm test "your-feature-file.feature"

## 📂 Test Structure

The test suite follows a BDD-style structure using [Gherkin](https://cucumber.io/docs/gherkin/) 
feature files and corresponding step definitions.

### 🔸 Folder layout
- `tests/`
    - `e2e/`: Contains `.feature` files written in Gherkin syntax. Each file describes user scenarios using Given, When, and Then steps.
        - `test-scenarios.feature`
        - `other-tests.feature`
    - `steps/`: Contains the step definitions — the TypeScript code that implements the behavior described in the feature files.
        - `test-scenarios.steps.ts`

