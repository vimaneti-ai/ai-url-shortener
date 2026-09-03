import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const profile = (__ENV.PROFILE || 'smoke').toLowerCase();
const baseUrl = (__ENV.BASE_URL || 'http://host.docker.internal:4200').replace(/\/$/, '');
const shortCode = __ENV.SHORT_CODE;
const recordAnalytics = (__ENV.RECORD_ANALYTICS || 'true').toLowerCase() === 'true';

if (!shortCode) {
  fail('SHORT_CODE is required. Create a link first, then pass its short code to k6.');
}

if (!['smoke', 'load'].includes(profile)) {
  fail(`Unknown PROFILE=${profile}. Use smoke or load.`);
}

if (profile === 'load'
    && baseUrl === 'https://short.vinodmaneti.com'
    && (__ENV.ALLOW_PRODUCTION || '').toLowerCase() !== 'true') {
  fail('Refusing a 1,000-user run against production. Set ALLOW_PRODUCTION=true only after approval.');
}

const redirectFailures = new Rate('redirect_failures');
const analyticsFailures = new Rate('analytics_failures');
const redirectDuration = new Trend('redirect_duration', true);
const analyticsDuration = new Trend('analytics_duration', true);

const loadStages = (maximumUsers) => [
  { duration: '1m', target: Math.round(maximumUsers * 0.1) },
  { duration: '2m', target: Math.round(maximumUsers * 0.5) },
  { duration: '2m', target: maximumUsers },
  { duration: '5m', target: maximumUsers },
  { duration: '1m', target: 0 },
];

const smokeScenarios = {
  redirects: {
    executor: 'constant-vus',
    exec: 'redirectTraffic',
    vus: 1,
    duration: '10s',
    gracefulStop: '5s',
  },
  analytics: {
    executor: 'constant-vus',
    exec: 'analyticsTraffic',
    vus: 1,
    duration: '10s',
    gracefulStop: '5s',
  },
};

const loadScenarios = {
  redirects: {
    executor: 'ramping-vus',
    exec: 'redirectTraffic',
    startVUs: 0,
    stages: loadStages(900),
    gracefulRampDown: '30s',
  },
  analytics: {
    executor: 'ramping-vus',
    exec: 'analyticsTraffic',
    startVUs: 0,
    stages: loadStages(100),
    gracefulRampDown: '30s',
  },
};

export const options = {
  scenarios: profile === 'load' ? loadScenarios : smokeScenarios,
  thresholds: {
    checks: ['rate>0.99'],
    redirect_failures: ['rate<0.01'],
    analytics_failures: ['rate<0.01'],
    redirect_duration: ['p(95)<500', 'p(99)<1000'],
    analytics_duration: ['p(95)<750', 'p(99)<1500'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  tags: { test_profile: profile },
};

export function redirectTraffic() {
  const headers = recordAnalytics ? {} : { Purpose: 'prefetch' };
  const response = http.get(`${baseUrl}/${encodeURIComponent(shortCode)}`, {
    redirects: 0,
    headers,
    tags: { endpoint: 'redirect' },
  });

  const passed = check(response, {
    'redirect returns 302': (result) => result.status === 302,
    'redirect has a location header': (result) => Boolean(result.headers.Location),
  });
  redirectFailures.add(!passed);
  redirectDuration.add(response.timings.duration);
  sleep(0.5 + Math.random());
}

export function analyticsTraffic() {
  const response = http.get(`${baseUrl}/api/v1/analytics/${encodeURIComponent(shortCode)}`, {
    tags: { endpoint: 'analytics' },
  });

  const passed = check(response, {
    'analytics returns 200': (result) => result.status === 200,
    'analytics contains clicks': (result) => {
      try {
        return typeof result.json('clicks') === 'number';
      } catch (_) {
        return false;
      }
    },
  });
  analyticsFailures.add(!passed);
  analyticsDuration.add(response.timings.duration);
  sleep(0.5 + Math.random());
}

export function handleSummary(data) {
  const metric = (name, value) => data.metrics[name]?.values?.[value];
  const percentage = (value) => `${((value || 0) * 100).toFixed(2)}%`;
  const milliseconds = (value) => `${(value || 0).toFixed(2)} ms`;
  const summary = [
    '',
    `Profile: ${profile}`,
    `Iterations: ${metric('iterations', 'count') || 0}`,
    `Checks passed: ${percentage(metric('checks', 'rate'))}`,
    `Redirect failures: ${percentage(metric('redirect_failures', 'rate'))}`,
    `Redirect latency: p95=${milliseconds(metric('redirect_duration', 'p(95)'))}, p99=${milliseconds(metric('redirect_duration', 'p(99)'))}`,
    `Analytics failures: ${percentage(metric('analytics_failures', 'rate'))}`,
    `Analytics latency: p95=${milliseconds(metric('analytics_duration', 'p(95)'))}, p99=${milliseconds(metric('analytics_duration', 'p(99)'))}`,
    'Machine-readable summary: performance/results/summary.json',
    '',
  ].join('\n');

  return {
    stdout: summary,
    'results/summary.json': JSON.stringify(data, null, 2),
  };
}
