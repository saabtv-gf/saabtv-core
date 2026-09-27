import { AUTH, DATA, encode, encrypt, fetchJson } from '../relay.js';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
if (process.env.SAAB_PAIRING_LIVE !== '1') throw new Error('Explicit relay test opt-in required');
const token = (await fetchJson(`${AUTH}/token/anonymous`)).token;
const id = crypto.randomUUID(), reader = encode(crypto.getRandomValues(new Uint8Array(32))), writer = encode(crypto.getRandomValues(new Uint8Array(32)));
const key = await crypto.subtle.generateKey({name:'AES-GCM',length:256},false,['encrypt','decrypt']);
const hash = text=>createHash('sha256').update(text).digest('hex');
const rpc = (name,body)=>fetchJson(`${DATA}/rpc/saabtv_pair_${name}`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify(body)});
await rpc('create',{session_id:id,reader_hash:hash(reader),writer_hash:hash(writer),tool:'search',encrypted_manifest:await encrypt(key,{mode:'search',version:1},`${id}|manifest`)});
console.log(`Disposable expiry session ${id} created; checking real five-minute TTL.`);
try {
  const active = await rpc('phone',{session_id:id,capability:writer});
  await new Promise(resolve=>setTimeout(resolve,Math.max(0,new Date(active.expires).getTime()-Date.now()+1500)));
  await assert.rejects(rpc('phone',{session_id:id,capability:writer}));
  await assert.rejects(rpc('read',{session_id:id,capability:reader,ack:0}));
  console.log('Real five-minute expiry denies phone and TV access PASS');
} finally { await rpc('close',{session_id:id,capability:reader}); console.log('Expiry QA session closed'); }
