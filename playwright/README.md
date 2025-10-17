# 🧪 Playwright BDD Testing Framework

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

