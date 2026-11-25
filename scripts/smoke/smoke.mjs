#!/usr/bin/env node
// Smoke test of a running Compose stack, through nginx (the same origin as the SPA).
// documentations/08 §4E (PR 7, decision E1). It proves the stack starts and the main flow works;
// it is NOT evidence of financial correctness (documentations/06 §5): the integration tests are.
//
// Usage: node scripts/smoke/smoke.mjs [path/to/.env]
//   Staff accounts come from STAFF_ADMIN_EMAIL/PASSWORD and STAFF_COMPLIANCE_EMAIL/PASSWORD, read
//   from the given env file or the process environment. SMOKE_BASE_URL (default
//   http://127.0.0.1:5173) and SMOKE_MAIL_URL (default http://127.0.0.1:8025, Mailpit) override the
//   targets. Use 127.0.0.1, not localhost: localhost may resolve to ::1 and reach another server.
//
// It never prints a secret, token, password, OTP or wallet number. Auth calls are paced under the
// per-IP auth rate limit (10 per minute, documentations/02 §1.8) instead of changing the limit.
// Exit code 0 when every check passes, 1 otherwise.

import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';

const fileEnv = process.argv[2] ? readEnvFile(process.argv[2]) : {};
const env = (name) => process.env[name] ?? fileEnv[name] ?? '';
const BASE = env('SMOKE_BASE_URL') || 'http://127.0.0.1:5173';
const MAIL = env('SMOKE_MAIL_URL') || 'http://127.0.0.1:8025';
// The browser's origin for the cookie endpoints (Origin allow-list, documentations/06 MVP-ADR-14).
const ORIGIN = 'http://localhost:5173';
const STEP_TIMEOUT_MS = 30_000;

function readEnvFile(path) {
  return Object.fromEntries(
    readFileSync(path, 'utf8')
      .split(/\r?\n/)
      .filter((line) => /^[A-Z_][A-Z0-9_]*=/.test(line))
      .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
  );
}

let failures = 0;
let checks = 0;
function check(name, ok, detail = '') {
  checks++;
  if (!ok) failures++;
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${!ok && detail ? `  (${detail})` : ''}`);
  return ok;
}
function must(name, ok, detail) {
  if (!check(name, ok, detail)) {
    throw new Error(`stopping: "${name}" failed and later steps depend on it`);
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Waits until `probe` returns a truthy value, or fails after STEP_TIMEOUT_MS. */
async function waitFor(what, probe) {
  const deadline = Date.now() + STEP_TIMEOUT_MS;
  for (;;) {
    const value = await probe().catch(() => null);
    if (value) return value;
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await sleep(1000);
  }
}

// At most 9 auth calls in any 61 seconds (the limit is 10 per minute per IP).
const authCalls = [];
async function paceAuth() {
  for (;;) {
    const now = Date.now();
    while (authCalls.length && now - authCalls[0] > 61_000) authCalls.shift();
    if (authCalls.length < 9) {
      authCalls.push(now);
      return;
    }
    await sleep(61_000 - (now - authCalls[0]));
  }
}

class Session {
  constructor(label) {
    this.label = label;
    this.token = null;
    this.cookie = null;
  }

  async call(method, path, { body, raw, idempotencyKey, cookieEndpoint = false, bearer = true } = {}) {
    if (path.startsWith('/api/v1/auth/')) await paceAuth();
    const headers = { 'X-Correlation-Id': randomUUID() };
    if (body !== undefined || raw !== undefined) headers['Content-Type'] = 'application/json';
    if (bearer && this.token) headers.Authorization = `Bearer ${this.token}`;
    if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;
    if (cookieEndpoint) {
      headers['X-Requested-With'] = 'XMLHttpRequest';
      headers.Origin = ORIGIN;
      if (this.cookie) headers.Cookie = this.cookie;
    }
    const response = await fetch(BASE + path, {
      method,
      headers,
      body: raw ?? (body === undefined ? undefined : JSON.stringify(body)),
      signal: AbortSignal.timeout(STEP_TIMEOUT_MS),
    });
    for (const header of response.headers.getSetCookie()) {
      if (header.startsWith('refresh_token=')) {
        const pair = header.split(';')[0];
        this.cookie = pair === 'refresh_token=' ? null : pair;
      }
    }
    const text = await response.text();
    let json = null;
    try {
      json = text ? JSON.parse(text) : null;
    } catch {
      json = null;
    }
    return { status: response.status, json };
  }
}

async function latestOtp(email) {
  return waitFor(`the code email to ${email.replace(/@.*/, '@…')}`, async () => {
    const query = encodeURIComponent(`to:${email}`);
    const list = await (await fetch(`${MAIL}/api/v1/search?query=${query}`)).json();
    const message = list.messages?.[0];
    if (!message) return null;
    const full = await (await fetch(`${MAIL}/api/v1/message/${message.ID}`)).json();
    return /\b(\d{6})\b/.exec(full.Text ?? '')?.[1] ?? null;
  });
}

const PASSWORD = `Smoke-${randomUUID()}`;
const RUN = Date.now().toString(36);

async function signUp(label, firstName, lastName) {
  const session = new Session(label);
  const email = `smoke-${label}-${RUN}@example.test`;
  const phoneNumber = `+6680${String(Math.floor(Math.random() * 1e7)).padStart(7, '0')}`;
  let r = await session.call('POST', '/api/v1/auth/register', {
    body: { email, phoneNumber, password: PASSWORD },
    bearer: false,
  });
  must(`${label}: register 201`, r.status === 201, String(r.status));
  const otp = await latestOtp(email);
  r = await session.call('POST', '/api/v1/auth/verify-otp', {
    body: { otp, purpose: 'REGISTRATION', identifier: email, password: PASSWORD },
    bearer: false,
    cookieEndpoint: true,
  });
  must(`${label}: verify the emailed code 200`, r.status === 200, String(r.status));
  r = await session.call('POST', '/api/v1/auth/login', {
    body: { email, password: PASSWORD },
    bearer: false,
    cookieEndpoint: true,
  });
  must(`${label}: login 200 with a refresh cookie`, r.status === 200 && !!r.json?.accessToken && !!session.cookie, String(r.status));
  session.token = r.json.accessToken;
  session.firstName = firstName;
  session.lastName = lastName;
  return session;
}

async function staffSignIn(label, email, password) {
  must(`${label}: staff credentials set`, !!email && !!password, 'STAFF_* variables missing');
  const session = new Session(label);
  const r = await session.call('POST', '/api/v1/auth/login', {
    body: { email, password },
    bearer: false,
    cookieEndpoint: true,
  });
  must(`${label}: login 200`, r.status === 200 && !!r.json?.accessToken, String(r.status));
  session.token = r.json.accessToken;
  return session;
}

async function approvedWallet(customer, compliance) {
  let r = await customer.call('GET', '/api/v1/wallet');
  check(`${customer.label}: no wallet before KYC (404 WALLET_NOT_FOUND)`, r.status === 404 && r.json?.errorCode === 'WALLET_NOT_FOUND', String(r.status));
  r = await customer.call('PUT', '/api/v1/customers/me', { body: { dateOfBirth: '1990-01-15', countryCode: 'TH' } });
  must(`${customer.label}: profile saved`, r.status === 200, String(r.status));
  r = await customer.call('POST', '/api/v1/kyc/applications', {
    body: {
      verificationLevel: 'BASIC',
      documentType: 'ID_CARD',
      documentReference: `SMOKE${randomUUID().replace(/-/g, '').slice(0, 10).toUpperCase()}`,
      firstName: customer.firstName,
      lastName: customer.lastName,
    },
  });
  must(`${customer.label}: KYC draft 201`, r.status === 201 && !!r.json?.id, String(r.status));
  const applicationId = r.json.id;
  r = await customer.call('POST', `/api/v1/kyc/applications/${applicationId}/submit`);
  must(`${customer.label}: KYC submitted`, r.status === 200, String(r.status));
  r = await compliance.call('POST', `/api/v1/admin/kyc/applications/${applicationId}/approve`);
  must(`${customer.label}: compliance approves`, r.status === 200 && r.json?.status === 'APPROVED', String(r.status));
  customer.customerId = r.json.customerId;
  r = await customer.call('GET', '/api/v1/wallet');
  must(`${customer.label}: wallet ACTIVE`, r.status === 200 && r.json?.status === 'ACTIVE', String(r.status));
  customer.walletNumber = r.json.walletNumber;
}

async function main() {
  // The stack is up and the SPA is served with its security headers.
  const health = await waitFor('the backend health through nginx', async () => {
    const response = await fetch(`${BASE}/actuator/health`);
    return response.ok ? response.json() : null;
  });
  check('backend health UP through nginx', health?.status === 'UP', health?.status);
  for (const path of ['/', '/transfers', '/admin/kyc']) {
    const response = await fetch(BASE + path);
    const csp = response.headers.get('content-security-policy') ?? '';
    check(`SPA ${path} served with the CSP`, response.status === 200 && csp.includes("script-src 'self'"), String(response.status));
  }

  // The OpenAPI document is served only by the backend in the local profile, never through nginx
  // (documentations/06 §4, MVP-ADR-15): nginx answers this path with the SPA shell.
  const docs = await fetch(`${BASE}/v3/api-docs`);
  const docsBody = await docs.text();
  check(
    'API document not reachable through nginx',
    !(docs.headers.get('content-type') ?? '').includes('json') && !docsBody.includes('"openapi"'),
    `${docs.status} ${docs.headers.get('content-type')}`,
  );

  const compliance = await staffSignIn('compliance', env('STAFF_COMPLIANCE_EMAIL'), env('STAFF_COMPLIANCE_PASSWORD'));
  const admin = await staffSignIn('admin', env('STAFF_ADMIN_EMAIL'), env('STAFF_ADMIN_PASSWORD'));
  const alice = await signUp('alice', 'Alice', 'Smoke');
  const bob = await signUp('bob', 'Bob', 'Smoke');
  await approvedWallet(alice, compliance);
  await approvedWallet(bob, compliance);

  // Money: a deposit, a lookup and a transfer.
  const depositKey = randomUUID();
  let r = await alice.call('POST', '/api/v1/wallet/deposits', { raw: '{"amount":1000.00,"currency":"THB"}', idempotencyKey: depositKey });
  must('alice: deposit 201 COMPLETED POSTED', r.status === 201 && r.json?.status === 'COMPLETED' && r.json?.ledgerStatus === 'POSTED', String(r.status));
  const depositReference = r.json.reference;
  r = await alice.call('POST', '/api/v1/wallet/deposits', { raw: '{"amount":1000.00,"currency":"THB"}', idempotencyKey: depositKey });
  check('alice: the same Idempotency-Key replays the deposit', r.status === 201 && r.json?.reference === depositReference, String(r.status));
  r = await alice.call('GET', `/api/v1/wallets/lookup?walletNumber=${bob.walletNumber}`);
  check('alice: lookup shows the masked name', r.status === 200 && r.json?.maskedName === 'Bob S.', String(r.status));
  r = await alice.call('POST', '/api/v1/transfers', {
    raw: `{"amount":150.50,"receiverWalletNumber":"${bob.walletNumber}","currency":"THB"}`,
    idempotencyKey: randomUUID(),
  });
  must('alice: transfer to bob 201 COMPLETED', r.status === 201 && r.json?.status === 'COMPLETED' && r.json?.ledgerStatus === 'POSTED', `${r.status} ${r.json?.status ?? r.json?.errorCode}`);
  r = await alice.call('GET', '/api/v1/wallet/balance');
  check('alice: balance 849.50', r.status === 200 && Number(r.json?.availableBalance) === 849.5, String(r.json?.availableBalance));
  r = await alice.call('GET', '/api/v1/transactions?page=0&size=20');
  const aliceTypes = (r.json?.items ?? []).map((item) => item.type);
  check('alice: history has the deposit and the transfer out', aliceTypes.includes('DEPOSIT') && aliceTypes.includes('TRANSFER_OUT'), aliceTypes.join(','));
  r = await bob.call('GET', '/api/v1/transactions');
  check('bob: history has the transfer in', (r.json?.items ?? []).some((item) => item.type === 'TRANSFER_IN'));

  // Role separation is enforced by the backend.
  r = await admin.call('GET', '/api/v1/admin/kyc/applications');
  check('admin refused on KYC reviews (403)', r.status === 403, String(r.status));
  r = await alice.call('GET', '/api/v1/admin/transfer-reviews');
  check('customer refused on transfer reviews (403)', r.status === 403, String(r.status));
  r = await compliance.call('GET', '/api/v1/wallet');
  check('staff refused on the customer wallet (403)', r.status === 403, String(r.status));

  // Staff freeze and unfreeze.
  r = await admin.call('POST', `/api/v1/admin/customers/${bob.customerId}/freeze`, { body: { reason: 'Smoke test freeze' } });
  check('admin: freeze bob 200 FROZEN', r.status === 200 && r.json?.status === 'FROZEN', String(r.status));
  r = await bob.call('POST', '/api/v1/wallet/deposits', { raw: '{"amount":100.00,"currency":"THB"}', idempotencyKey: randomUUID() });
  check('bob: frozen wallet refuses a deposit (409 WALLET_FROZEN)', r.status === 409 && r.json?.errorCode === 'WALLET_FROZEN', String(r.status));
  r = await admin.call('POST', `/api/v1/admin/customers/${bob.customerId}/unfreeze`, { body: { reason: 'Smoke test unfreeze' } });
  check('admin: unfreeze bob 200 ACTIVE', r.status === 200 && r.json?.status === 'ACTIVE', String(r.status));

  // Session: refresh rotates the cookie, logout ends the session.
  const before = alice.cookie;
  r = await alice.call('POST', '/api/v1/auth/refresh', { bearer: false, cookieEndpoint: true });
  check('alice: refresh 200 rotates the cookie', r.status === 200 && !!r.json?.accessToken && alice.cookie !== before, String(r.status));
  if (r.json?.accessToken) alice.token = r.json.accessToken;
  r = await alice.call('POST', '/api/v1/auth/logout', { cookieEndpoint: true });
  check('alice: logout 204 clears the cookie', r.status === 204 && alice.cookie === null, String(r.status));
  r = await alice.call('GET', '/api/v1/wallet');
  check('alice: the access token is refused after logout (401)', r.status === 401, String(r.status));
}

const started = Date.now();
try {
  await main();
} catch (error) {
  failures++;
  console.log(`FAIL  ${error instanceof Error ? error.message : 'unexpected error'}`);
}
const seconds = Math.round((Date.now() - started) / 1000);
console.log(`\n${checks} checks, ${failures} failed, ${seconds} s`);
process.exit(failures === 0 ? 0 : 1);
