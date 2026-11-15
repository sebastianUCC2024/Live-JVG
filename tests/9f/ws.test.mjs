import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseFrames, verifySnapshot } from '../../scripts/phase9f-ws.mjs'

test('parses STOMP recovery frames and heartbeat prefixes without a socket', () => {
  const frames = parseFrames('\nCONNECTED\nversion:1.2\n\n\0MESSAGE\nsubscription:recovery\n\n{"available":true}\0')
  assert.equal(frames.length, 2)
  assert.equal(frames[1].headers.subscription, 'recovery')
  assert.equal(JSON.parse(frames[1].body).available, true)
})
test('rejects foreign match/epoch and lower revision while retaining opaque epoch identity', () => {
  const expected = { stream_id: 'durable-epoch-9f_012345abcdef_match', revision: 10 }
  const value = { ...expected, match_id: '9f_012345abcdef_match' }
  verifySnapshot(value, '012345abcdef', expected)
  for (const override of [{ match_id: 'other' }, { stream_id: 'durable-other' }, { revision: 9 }, { revision: 0 }]) {
    assert.throws(() => verifySnapshot({ ...value, ...override }, '012345abcdef', expected))
  }
})
