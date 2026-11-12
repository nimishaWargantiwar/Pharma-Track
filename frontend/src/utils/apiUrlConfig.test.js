import test from 'node:test';
import assert from 'node:assert/strict';
import {
  isLocalhostHostname,
  normalizeApiBaseUrl,
  validateProductionApiBaseUrl,
} from './apiUrlConfig.js';

test('normalizeApiBaseUrl trims whitespace and strips trailing slashes cleanly', () => {
  assert.equal(normalizeApiBaseUrl('  https://api.example.com/api///  '), 'https://api.example.com/api');
  assert.equal(normalizeApiBaseUrl('http://localhost:8080/api/'), 'http://localhost:8080/api');
  assert.equal(normalizeApiBaseUrl('   '), '');
  assert.equal(normalizeApiBaseUrl(null), '');
});

test('isLocalhostHostname detects localhost, loopback IPs, and .local hosts safely', () => {
  assert.equal(isLocalhostHostname('localhost'), true);
  assert.equal(isLocalhostHostname('127.0.0.1'), true);
  assert.equal(isLocalhostHostname('0.0.0.0'), true);
  assert.equal(isLocalhostHostname('::1'), true);
  assert.equal(isLocalhostHostname('[::1]'), true);
  assert.equal(isLocalhostHostname('dev.localhost'), true);
  assert.equal(isLocalhostHostname('backend.local'), true);
  assert.equal(isLocalhostHostname('pharmatrack-api.onrender.com'), false);
});

test('validateProductionApiBaseUrl accepts valid HTTPS /api URLs and normalizes trailing slash', () => {
  assert.equal(
    validateProductionApiBaseUrl('  https://pharmatrack-api.example.com/api/  '),
    'https://pharmatrack-api.example.com/api'
  );
  assert.equal(
    validateProductionApiBaseUrl('https://gateway.example.com/v1/api'),
    'https://gateway.example.com/v1/api'
  );
});

test('validateProductionApiBaseUrl rejects missing, HTTP, localhost, credentials, query, or non-/api URLs', () => {
  const invalidInputs = [
    '',
    '   ',
    '/api',
    'not-a-url',
    'http://api.example.com/api',
    'http://localhost:8080/api',
    'https://localhost:8443/api',
    'https://127.0.0.1:8080/api',
    'https://[::1]:8080/api',
    'https://api.example.local/api',
    'https://user:pass@api.example.com/api',
    'https://api.example.com',
    'https://api.example.com/api?debug=1',
    'https://api.example.com/api#frag',
  ];

  for (const input of invalidInputs) {
    assert.throws(
      () => validateProductionApiBaseUrl(input),
      /\[PharmaTrack Build Error\]/,
      `Expected production URL validation to reject "${input}"`
    );
  }
});

