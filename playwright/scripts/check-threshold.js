const fs = require('fs');
const path = require('path');

function setOutput(name, value) {
    if (process.env.GITHUB_OUTPUT) {
        fs.appendFileSync(process.env.GITHUB_OUTPUT, `${name}=${value}\n`);
    }
}

const THRESHOLD = parseFloat(
    process.argv.find(a => a.startsWith('--threshold='))?.split('=')[1] || '80'
);

const PLAYWRIGHT_DIR = path.join(__dirname, '..');
const RESULTS_DIR = path.join(PLAYWRIGHT_DIR, 'test-results');

function readResults(filePath) {
    if (!fs.existsSync(filePath)) return null;
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

const initialResults =
    readResults(path.join(PLAYWRIGHT_DIR, 'results-initial.json')) ||
    readResults(path.join(RESULTS_DIR, 'results.json'));
const retryResults = readResults(path.join(PLAYWRIGHT_DIR, 'results-retry.json'));

if (!initialResults) {
    console.error('No test results found. Run tests first.');
    process.exit(1);
}

const { expected = 0, unexpected = 0, flaky = 0 } = initialResults.stats;
const total = expected + unexpected + flaky;
const passedInitial = expected + flaky;

let finalPassed = passedInitial;

if (retryResults) {
    const savedByRetry = retryResults.stats.expected + retryResults.stats.flaky;
    finalPassed += savedByRetry;
}

const savedByRetry = finalPassed - passedInitial;
const passRate = (finalPassed / total) * 100;

console.log('\n Quality Threshold Report');
console.log('─────────────────────────────────');
console.log(`Total tests:      ${total}`);
console.log(`Passed (initial): ${passedInitial}`);
console.log(`Saved by retry:   ${savedByRetry}`);
console.log(`Final passed:     ${finalPassed}`);
console.log(`Pass rate:        ${passRate.toFixed(1)}%`);
console.log(`Threshold:        ${THRESHOLD}%`);
console.log('─────────────────────────────────');

const thresholdMet = passRate >= THRESHOLD;
const allPassed = finalPassed === total;

setOutput('pass_rate', passRate.toFixed(1));
setOutput('threshold_met', thresholdMet);
setOutput('all_passed', allPassed);

if (thresholdMet) {
    console.log(`PASSED - Quality threshold met (${passRate.toFixed(1)}% >= ${THRESHOLD}%)\n`);
    process.exit(0);
} else {
    console.log(`FAILED - Quality threshold NOT met (${passRate.toFixed(1)}% < ${THRESHOLD}%)\n`);
    process.exit(1);
}