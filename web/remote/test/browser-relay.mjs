// TV-side simulator for approved browser pairing QA. No authentication is performed.
import { AUTH, DATA, encode, encrypt, decrypt, fetchJson } from '../relay.js';
import { createHash } from 'node:crypto';
if (process.env.SAAB_PAIRING_LIVE !== '1') throw new Error('Explicit relay test opt-in required');
const mode = process.env.SAAB_PAIRING_MODE || 'search';
if (!['search','signin','avatar','hub'].includes(mode)) throw new Error('Unsupported QA mode');
const token = (await fetchJson(`${AUTH}/token/anonymous`)).token;
const id = crypto.randomUUID(), reader = encode(crypto.getRandomValues(new Uint8Array(32))), writer = encode(crypto.getRandomValues(new Uint8Array(32)));
const raw = crypto.getRandomValues(new Uint8Array(32));
const key = await crypto.subtle.importKey('raw',raw,'AES-GCM',false,['encrypt','decrypt']);
const hash = text=>createHash('sha256').update(text).digest('hex');
const rpc = (name, body)=>fetchJson(`${DATA}/rpc/saabtv_pair_${name}`,{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify(body)});
const manifest = {mode,version:1,shape:'HORIZONTAL',items:[{id:'qa-artwork',title:'QA Artwork',hasImage:false}],canDelete:true};
await rpc('create',{session_id:id,reader_hash:hash(reader),writer_hash:hash(writer),tool:mode,encrypted_manifest:await encrypt(key,manifest,`${id}|manifest`)});
console.log(`http://127.0.0.1:8765/#id=${id}&cap=${writer}&key=${encode(raw)}`);
try {
  const deadline = Date.now()+290000;
  while (Date.now()<deadline) {
    const received = await rpc('read',{session_id:id,capability:reader,ack:0});
    if (received.ciphertext) {
      const payload = await decrypt(key,received.ciphertext,`${id}|message|${received.message_id}`);
      if (payload.mode!==mode) throw new Error('Mode mismatch');
      if (mode==='search' && payload.value!=='Interstellar') throw new Error('Search mismatch');
      if (mode==='signin' && (payload.username!=='relay_demo' || payload.password!=='NotAnAccount1!')) throw new Error('Fake credential mismatch');
      if (mode==='avatar' && !payload.image) throw new Error('Image missing');
      await rpc('read',{session_id:id,capability:reader,ack:1});
      console.log(`${mode}: browser-to-Neon-to-TV-simulator encrypted delivery PASS`);
      break;
    }
    await new Promise(resolve=>setTimeout(resolve,2500));
  }
} finally { await rpc('close',{session_id:id,capability:reader}); raw.fill(0); console.log('QA relay closed'); }
