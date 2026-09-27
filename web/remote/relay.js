export const AUTH = 'https://ep-gentle-voice-azd9if2p.neonauth.c-3.ap-southeast-1.aws.neon.tech/neondb/auth';
export const DATA = 'https://ep-gentle-voice-azd9if2p.apirest.c-3.ap-southeast-1.aws.neon.tech/neondb/rest/v1';
const utf8 = new TextEncoder();
export function encode(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}
export function decode(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error('Invalid pairing data.');
  return Uint8Array.from(atob(value.replaceAll('-', '+').replaceAll('_', '/')), c => c.charCodeAt(0));
}
export async function encrypt(key, value, aad) {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const data = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, additionalData: utf8.encode(aad) }, key, utf8.encode(JSON.stringify(value)));
  return JSON.stringify({ iv: encode(iv), data: encode(new Uint8Array(data)) });
}
export async function decrypt(key, envelope, aad) {
  const parsed = JSON.parse(envelope);
  const iv = decode(parsed.iv);
  if (iv.length !== 12) throw new Error('Invalid encrypted message.');
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv, additionalData: utf8.encode(aad) }, key, decode(parsed.data));
  return JSON.parse(new TextDecoder().decode(plain));
}
export async function fetchJson(url, options = {}) {
  const response = await fetch(url, { ...options, cache: 'no-store', credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(20000) });
  if (!response.ok) throw new Error(`Secure connection failed (${response.status}). Reopen the QR on your TV and try again.`);
  return response.json();
}
export class Relay {
  constructor(id, cap, key, token) { Object.assign(this, { id, cap, key, token }); }
  rpc(method, body) {
    return fetchJson(`${DATA}/rpc/saabtv_pair_${method}`, {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${this.token}` },
      body: JSON.stringify({ session_id: this.id, capability: this.cap, ...body })
    });
  }
  async manifest() {
    const state = await this.rpc('phone', {});
    if (state.closed) throw new Error('Your TV closed this pairing. Reopen the QR code.');
    this.expires = new Date(state.expires).getTime();
    return decrypt(this.key, state.manifest, `${this.id}|manifest`);
  }
  async send(message) {
    if (Date.now() >= this.expires) throw new Error('Pairing expired. Reopen the QR on your TV.');
    const id = crypto.randomUUID();
    const envelope = await encrypt(this.key, message, `${this.id}|message|${id}`);
    // One immutable ID per operation, reused on a transient request failure.
    const body = { message_id: id, encrypted_message: envelope };
    let sequence;
    try { sequence = await this.rpc('send', body); }
    catch (_) { sequence = await this.rpc('send', body); }
    const deadline = Math.min(this.expires, Date.now() + 45000);
    while (Date.now() < deadline) {
      const status = await this.rpc('phone', {});
      if (status.acknowledged >= sequence) return;
      if (status.closed) throw new Error('The TV closed pairing before confirming receipt.');
      await new Promise(resolve => setTimeout(resolve, 2500));
    }
    throw new Error('The TV has not confirmed receipt. Check your TV before retrying.');
  }
}
