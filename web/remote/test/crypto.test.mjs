import { test } from 'node:test';
import assert from 'node:assert/strict';
import { encrypt, decrypt, encode, decode } from '../relay.js';
test('authenticated round trip, fresh IVs, session and direction isolation', async () => {
  const key = await crypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
  const value = {value:'English తెలుగు हिन्दी',mode:'search'};
  const a = await encrypt(key, value, 'session|message|uuid');
  const b = await encrypt(key, value, 'session|message|uuid');
  assert.notEqual(a,b);
  assert.deepEqual(await decrypt(key,a,'session|message|uuid'), value);
  await assert.rejects(decrypt(key,a,'other|message|uuid'));
  await assert.rejects(decrypt(key,a,'session|manifest'));
  const wrong = await crypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
  await assert.rejects(decrypt(wrong,a,'session|message|uuid'));
});
test('512KB image encoding round trip', () => {
  const image = Uint8Array.from({length:524288},(_,i)=>i%256);
  assert.deepEqual(decode(encode(image)),image);
});
