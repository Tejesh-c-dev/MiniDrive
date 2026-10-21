import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const PROFILE = (__ENV.PROFILE || 'smoke').toLowerCase();
const EMAIL = __ENV.K6_EMAIL;
const PASSWORD = __ENV.K6_PASSWORD;
const bytes = Number(__ENV.FILE_SIZE_BYTES || 65536);
const smokeIterations = Number(__ENV.SMOKE_ITERATIONS || 1);
const duration = __ENV.DURATION || '60s';
const vus = Number(__ENV.VUS || 2);
const maxFailureRate = Number(__ENV.MAX_HTTP_FAILURE_RATE || 0.05);

if (BASE_URL !== 'http://127.0.0.1:8080') {
  throw new Error('BASE_URL is restricted to http://127.0.0.1:8080');
}
if (!['smoke', 'baseline', 'ramp', 'ramp10'].includes(PROFILE)) {
  throw new Error('PROFILE must be smoke, baseline, ramp, or ramp10');
}
if (!Number.isInteger(bytes) || bytes < 1 || bytes > 1048576) {
  throw new Error('FILE_SIZE_BYTES must be an integer from 1 to 1048576');
}
if (!Number.isInteger(smokeIterations) || smokeIterations < 1 || smokeIterations > 10) {
  throw new Error('SMOKE_ITERATIONS must be an integer from 1 to 10');
}
if (!Number.isInteger(vus) || vus < 1 || vus > 5) {
  throw new Error('VUS must be an integer from 1 to 5');
}
if (!Number.isFinite(maxFailureRate) || maxFailureRate <= 0 || maxFailureRate > 0.25) {
  throw new Error('MAX_HTTP_FAILURE_RATE must be greater than 0 and at most 0.25');
}

const uploadDuration = new Trend('minidrive_upload_duration', true);
const downloadDuration = new Trend('minidrive_download_duration', true);
const responseStatuses = new Counter('minidrive_response_status_total');
const rampTargets = PROFILE === 'ramp10' ? [2, 4, 6, 8, 10] : [2, 3, 5];
const plateauTarget = PROFILE === 'ramp10' ? 10 : 5;

const scenario = PROFILE === 'smoke'
  ? { executor: 'per-vu-iterations', vus: 1, iterations: smokeIterations, maxDuration: '2m' }
  : PROFILE === 'baseline'
    ? { executor: 'constant-vus', vus, duration }
    : {
        executor: 'ramping-vus',
        startVUs: 1,
        stages: [
          ...rampTargets.map((target) => ({ duration: __ENV.RAMP_STAGE_DURATION || '20s', target })),
          { duration: __ENV.RAMP_HOLD_DURATION || '60s', target: plateauTarget },
        ],
        gracefulRampDown: '5s',
      };

export const options = {
  scenarios: { [PROFILE]: scenario },
  thresholds: {
    http_req_failed: [{ threshold: `rate<${maxFailureRate}`, abortOnFail: true, delayAbortEval: '10s' }],
    checks: [{ threshold: 'rate>0.95', abortOnFail: true, delayAbortEval: '10s' }],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  userAgent: 'MiniDrive-k6-local-load-test/1.0',
};

function sanitizeSummary(value) {
  if (Array.isArray(value)) return value.map(sanitizeSummary);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value)
      .filter(([key]) => !/(token|password|secret|credential|authorization)/i.test(key))
      .map(([key, entry]) => [key, sanitizeSummary(entry)]));
  }
  return value;
}

export function handleSummary(data) {
  const summary = sanitizeSummary(data);
  // Setup data contains the bearer token used by VUs. Never persist it in a summary.
  delete summary.setup_data;
  const summaryPath = __ENV.SUMMARY_PATH;
  return summaryPath ? { [summaryPath]: JSON.stringify(summary, null, 2) } : {};
}

function request(method, path, body, params, expectedStatus, label) {
  const response = http.request(method, `${BASE_URL}${path}`, body, {
    timeout: '30s',
    ...params,
    tags: { name: label },
  });
  responseStatuses.add(1, { status: String(response.status), request: label });
  const ok = check(response, {
    [`${label} status is ${expectedStatus}`]: (r) => r.status === expectedStatus,
  });
  if (!ok) fail(`${label} returned HTTP ${response.status}`);
  return response;
}

export function setup() {
  if (!EMAIL || !PASSWORD) fail('Set K6_EMAIL and K6_PASSWORD in the environment');

  const health = http.get(`${BASE_URL}/actuator/health`, { timeout: '5s', tags: { name: 'health' } });
  responseStatuses.add(1, { status: String(health.status), request: 'health' });
  if (!check(health, { 'backend health is UP': (r) => r.status === 200 && r.json('status') === 'UP' })) {
    fail(`Backend health check failed with HTTP ${health.status}`);
  }

  const metrics = http.get(`${BASE_URL}/actuator/prometheus`, { timeout: '5s', tags: { name: 'prometheus' } });
  responseStatuses.add(1, { status: String(metrics.status), request: 'prometheus' });
  if (!check(metrics, { 'Prometheus endpoint is available': (r) => r.status === 200 && r.body.includes('http_server_requests_seconds_count') })) {
    fail(`Prometheus endpoint check failed with HTTP ${metrics.status}`);
  }

  const login = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }), {
    headers: { 'Content-Type': 'application/json' }, timeout: '10s', tags: { name: 'login' },
  });
  responseStatuses.add(1, { status: String(login.status), request: 'login' });
  if (!check(login, { 'login succeeds and returns a bearer token': (r) => r.status === 200 && !!r.json('token') })) {
    fail(`Login failed with HTTP ${login.status}; response body suppressed`);
  }
  return { token: login.json('token') };
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` };
  request('GET', '/api/auth/me', null, { headers }, 200, 'auth_me');
  request('GET', '/api/files', null, { headers }, 200, 'file_list');

  const unique = `k6-${Date.now()}-${__VU}-${__ITER}-${Math.random().toString(16).slice(2)}`;
  const filename = `${unique}.bin`;
  const payload = 'x'.repeat(bytes);
  let fileId;
  try {
    const form = { file: http.file(payload, filename, 'application/octet-stream') };
    const uploaded = http.post(`${BASE_URL}/api/files`, form, {
      headers, timeout: '30s', tags: { name: 'file_upload' },
    });
    responseStatuses.add(1, { status: String(uploaded.status), request: 'file_upload' });
    try { fileId = uploaded.json('id'); } catch (_) { /* No returned id means there is no owned resource to delete. */ }
    if (fileId) console.log(`[fixture] created id=${fileId} name=${filename}`);
    uploadDuration.add(uploaded.timings.duration, { outcome: uploaded.status === 201 ? 'success' : 'failure' });
    if (!check(uploaded, { 'file_upload status is 201': (r) => r.status === 201 })) {
      fail(`file_upload returned HTTP ${uploaded.status}`);
    }
    if (!check(uploaded, {
      'upload response has a file id, unique name, and requested size': (r) =>
        !!r.json('id') && r.json('name') === filename && Number(r.json('sizeBytes')) === bytes,
    })) fail('Upload response content validation failed');

    const downloaded = request('GET', `/api/files/${fileId}/download`, null, { headers, responseType: 'binary' }, 200, 'file_download');
    downloadDuration.add(downloaded.timings.duration, { outcome: 'success' });
    if (!check(downloaded, {
      'download bytes match uploaded fixture': (r) => r.body && r.body.byteLength === bytes,
    })) fail('Download response size did not match the uploaded fixture');
  } finally {
    if (fileId) {
      const deleted = request('DELETE', `/api/files/${fileId}`, null, { headers }, 204, 'file_delete');
      console.log(`[fixture] cleanup id=${fileId} status=${deleted.status}`);
    }
  }
}
