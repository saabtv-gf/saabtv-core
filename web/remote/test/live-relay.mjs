// Approved relay-only QA. Never signs up, signs in or writes account snapshots.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { AUTH, DATA, encode, encrypt, decrypt, fetchJson } from '../relay.js';
if (process.env.SAAB_PAIRING_LIVE !== '1') throw new Error('Explicit live relay test opt-in required');
const token = (await fetchJson(`${AUTH}/token/anonymous`)).token;
const hash = value => createHash('sha256').update(value).digest('hex');
const sessions = [];
async function rpc(name, body, expectSuccess = true) {
  const response = await fetch(`${DATA}/rpc/saabtv_pair_${name}`, {
    method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`,Origin:'https://saabtv-gf.github.io'},body:JSON.stringify(body)
  });
  assert.equal(response.ok,expectSuccess,`${name} returned HTTP ${response.status}`);
  return response.ok ? response.json() : null;
}
try {
  for (const mode of ['signin','signup','paste','search','avatar','hub']) {
    const id = crypto.randomUUID();
    const reader = encode(crypto.getRandomValues(new Uint8Array(32))), writer = encode(crypto.getRandomValues(new Uint8Array(32)));
    const key = await crypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
    const session = {id,reader,writer,key}; sessions.push(session);
    const manifest = await encrypt(key,{mode,version:1},`${id}|manifest`);
    await rpc('create',{session_id:id,reader_hash:hash(reader),writer_hash:hash(writer),tool:mode,encrypted_manifest:manifest});
    const state = await rpc('phone',{session_id:id,capability:writer});
    assert.equal((await decrypt(key,state.manifest,`${id}|manifest`)).mode,mode);
    assert.ok(new Date(state.expires).getTime()-Date.now() < 301000);
    await rpc('read',{session_id:id,capability:writer,ack:0},false);
    await rpc('phone',{session_id:id,capability:reader},false);
    const messageId = crypto.randomUUID();
    const payload = {mode,value:'Disposable relay QA',...(mode==='avatar'||mode==='hub'?{image:encode(new Uint8Array(524288).fill(17))}:{})};
    const cipher = await encrypt(key,payload,`${id}|message|${messageId}`);
    const request = {session_id:id,capability:writer,message_id:messageId,encrypted_message:cipher};
    assert.equal(await rpc('send',request),1);
    assert.equal(await rpc('send',request),1);
    await rpc('send',{...request,message_id:crypto.randomUUID()},false);
    await rpc('read',{session_id:id,capability:reader,ack:2},false);
    const received = await rpc('read',{session_id:id,capability:reader,ack:0});
    assert.equal((await decrypt(key,received.ciphertext,`${id}|message|${received.message_id}`)).value,payload.value);
    await rpc('read',{session_id:id,capability:reader,ack:1});
    assert.equal(await rpc('send',request),1,'retry remains idempotent after acknowledgement');
    const clean = await rpc('read',{session_id:id,capability:reader,ack:1});
    assert.equal(clean.ciphertext,undefined);
    if (sessions.length > 1) await rpc('phone',{session_id:id,capability:sessions[0].writer},false);
    await rpc('send',{...request,message_id:crypto.randomUUID(),encrypted_message:'x'.repeat(1048577)},false);
    await rpc('close',{session_id:id,capability:writer}); // wrong role must not close
    assert.equal((await rpc('phone',{session_id:id,capability:writer})).closed,false);
    await rpc('close',{session_id:id,capability:reader});
    const closed = await rpc('phone',{session_id:id,capability:writer});
    assert.equal(closed.closed,true); assert.equal(closed.manifest,''); assert.equal(closed.acknowledged,1);
    await rpc('read',{session_id:id,capability:reader,ack:1},false);
    console.log(`${mode}: encrypted round trip, role isolation, retry, bounds, acknowledgement and close PASS`);
  }
  const denied = await fetch(`${DATA}/saabtv_account_state?select=user_id`,{headers:{Authorization:`Bearer ${token}`}});
  assert.equal(denied.ok,false,'anonymous relay token must not expose account snapshots');
  console.log('Anonymous account snapshot access denied PASS');
} finally {
  for (const session of sessions) {
    await rpc('close',{session_id:session.id,capability:session.reader});
    console.log(`Closed disposable relay session ${session.id}`);
  }
}
