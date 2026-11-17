import test from 'node:test';
import assert from 'node:assert/strict';
import { generateClientRequestId } from './requestIdUtils.js';

test('generateClientRequestId generates non-empty unique string within 64 chars', () => {
  const id1 = generateClientRequestId();
  const id2 = generateClientRequestId();

  assert.ok(id1 && typeof id1 === 'string');
  assert.ok(id2 && typeof id2 === 'string');
  assert.ok(id1.startsWith('req-'));
  assert.ok(id2.startsWith('req-'));
  assert.notEqual(id1, id2);
  assert.ok(id1.length >= 8);
  assert.ok(id1.length <= 64);
  assert.ok(id2.length >= 8);
  assert.ok(id2.length <= 64);
});

test('generateClientRequestId generates unique IDs over 100 consecutive calls', () => {
  const ids = new Set();
  for (let i = 0; i < 100; i += 1) {
    const id = generateClientRequestId();
    assert.ok(id.length >= 8 && id.length <= 64);
    assert.ok(!ids.has(id), `Duplicate ID generated: ${id}`);
    ids.add(id);
  }
  assert.equal(ids.size, 100);
});
