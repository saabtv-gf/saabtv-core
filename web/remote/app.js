import { AUTH, DATA, Relay, decode, encode, fetchJson } from './relay.js';
const modes = {
  signin: ['Sign In To Saab TV', 'Enter your details here, then confirm on your TV.'],
  signup: ['Create Your Saab TV Account', 'Choose a unique username and a strong password.'],
  paste: ['Send A Link To Your TV', 'Paste an addon or integration link.'],
  search: ['Search On Your TV', 'Find a movie or series using your phone keyboard.'],
  avatar: ['Choose A Profile Photo', 'Adjust the crop before sending your profile photo.'],
  hub: ['Manage TV Artwork', 'Select an item and adjust its artwork.']
};
let relay, manifest, token, busy = false, image, cropCanvas, expiryTimer, generation = 0;
const fields = document.querySelector('#fields');
const submit = document.querySelector('.primary');
const form = document.querySelector('#form');
function status(title, detail) {
  document.querySelector('#status-title').textContent = title;
  document.querySelector('#status').textContent = detail;
}
function field(labelText, type, id, help) {
  const wrapper = document.createElement('div'); wrapper.className = 'field';
  const label = document.createElement('label'); label.htmlFor = id; label.textContent = labelText;
  const input = document.createElement('input'); input.id = id; input.type = type;
  input.autocomplete = 'off'; input.spellcheck = false; input.disabled = !relay;
  if (id === 'username' || type === 'password') {
    label.className = 'visually-hidden'; input.placeholder = labelText;
  }
  wrapper.append(label);
  if (type === 'password') {
    input.maxLength = 128;
    const row = document.createElement('div'); row.className = 'password';
    const toggle = document.createElement('button'); toggle.type = 'button'; toggle.textContent = 'Show';
    toggle.setAttribute('aria-label', `Show ${labelText}`); toggle.setAttribute('aria-pressed', 'false');
    toggle.addEventListener('click', () => {
      const visible = input.type === 'password'; input.type = visible ? 'text' : 'password';
      toggle.textContent = visible ? 'Hide' : 'Show'; toggle.setAttribute('aria-pressed', String(visible));
      toggle.setAttribute('aria-label', `${visible ? 'Hide' : 'Show'} ${labelText}`);
    });
    row.append(input, toggle); wrapper.append(row);
  } else wrapper.append(input);
  if (help) {
    const hint = document.createElement('p'); hint.className = 'help'; hint.id = `${id}-help`; hint.textContent = help;
    input.setAttribute('aria-describedby', hint.id); wrapper.append(hint);
  }
  return wrapper;
}
function showMode(mode) {
  if (!Object.hasOwn(modes, mode)) return;
  const secretPaste = mode === 'paste' && manifest?.inputKind === 'secret';
  document.querySelector('#title').textContent = secretPaste ? 'Send TorBox API Key' : modes[mode][0];
  document.querySelector('#description').textContent = secretPaste ? 'Enter your key here, then save it in Integrations on your TV.' : modes[mode][1];
  document.querySelectorAll('[data-mode]').forEach(button => {
    button.setAttribute('aria-pressed', String(button.dataset.mode === mode));
    button.disabled = !!relay && button.dataset.mode !== manifest.mode;
    button.hidden = true;
  });
  fields.replaceChildren(); image?.close(); image = undefined; cropCanvas = undefined;
  if (mode === 'signin' || mode === 'signup') {
    fields.append(field('Username', 'text', 'username', '3–32 letters, numbers or underscores.'),
      field('Password', 'password', 'password', mode === 'signup' ? '8–128 characters, including uppercase, lowercase, number and symbol.' : null));
    document.querySelector('#username').maxLength = 32;
    if (mode === 'signup') {
      fields.append(field('Confirm Password', 'password', 'confirm'));
      const availability = document.createElement('p'); availability.className = 'help';
      availability.setAttribute('aria-live','polite'); fields.querySelector('#username').parentElement.append(availability);
      const usernameInput = document.querySelector('#username');
      let timer, revision = 0;
      usernameInput.addEventListener('input', () => {
        clearTimeout(timer); const current = ++revision;
        const username = usernameInput.value.trim().toLowerCase();
        if (!/^[a-z0-9_]{3,32}$/.test(username)) { availability.textContent = 'Use 3–32 letters, numbers or underscores.'; return; }
        availability.textContent = 'Checking username…';
        const activeGeneration = generation;
        timer = setTimeout(async () => {
          try {
            const available = await fetchJson(`${DATA}/rpc/saabtv_username_available`, {method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify({requested_username:username})});
            if (current === revision && activeGeneration === generation) availability.textContent = available ? 'Username available' : 'Username already taken';
          } catch (_) { if (current === revision && activeGeneration === generation) availability.textContent = 'Unable to check now. We will check again before sending.'; }
        }, 600);
      });
    }
  } else if (mode === 'paste' || mode === 'search') {
    fields.append(field(secretPaste ? 'TorBox API Key' : mode === 'paste' ? 'Link' : 'Movie Or Series',
      secretPaste ? 'password' : mode === 'paste' ? 'url' : 'search', 'value'));
    document.querySelector('#value').maxLength = secretPaste ? 256 : mode === 'paste' ? 2048 : 200;
  } else {
    if (mode === 'hub' && relay) {
      const label = document.createElement('label'); label.htmlFor = 'item'; label.textContent = 'Artwork Item';
      const select = document.createElement('select'); select.id = 'item';
      for (const item of manifest.items) {
        const option = document.createElement('option'); option.value = item.id; option.textContent = item.title; select.append(option);
      }
      fields.append(label, select);
      if (manifest.canDelete) {
        const remove = document.createElement('button'); remove.type = 'button'; remove.textContent = 'Remove Selected Artwork';
        remove.addEventListener('click', () => {
          if (!busy && confirm('Remove this item’s custom artwork from your TV?')) send({ mode, item: select.value, delete: true });
        }); fields.append(remove);
      }
    }
    fields.append(field('Image', 'file', 'image', 'JPEG, PNG or WebP, up to 5 MB. Only the cropped image is sent.'));
    const input = document.querySelector('#image'); input.accept = 'image/jpeg,image/png,image/webp';
    cropCanvas = document.createElement('canvas'); cropCanvas.width = mode === 'hub' && manifest?.shape === 'HORIZONTAL' ? 640 : 400;
    cropCanvas.height = mode === 'hub' && manifest?.shape === 'VERTICAL' ? 600 : mode === 'hub' && manifest?.shape === 'HORIZONTAL' ? 360 : 400;
    cropCanvas.setAttribute('aria-label', 'Image Crop Preview'); fields.append(cropCanvas);
    for (const [id, labelText, min, max, value] of [['zoom','Zoom',1,3,1],['cropx','Horizontal Position',0,100,50],['cropy','Vertical Position',0,100,50]]) {
      const wrapper = field(labelText, 'range', id); const slider = wrapper.querySelector('input');
      slider.min = min; slider.max = max; slider.step = .1; slider.value = value; slider.addEventListener('input', drawCrop); fields.append(wrapper);
    }
    input.addEventListener('change', async () => {
      const imageGeneration = generation;
      const canvas = cropCanvas;
      try {
        const file = input.files[0]; if (!file) return;
        if (file.size > 5 * 1024 * 1024 || !['image/jpeg','image/png','image/webp'].includes(file.type)) throw new Error('Choose a JPEG, PNG or WebP up to 5 MB.');
        const decoded = await createImageBitmap(file);
        if (imageGeneration !== generation || !relay || canvas !== cropCanvas) { decoded.close(); return; }
        image?.close(); image = decoded;
        if (image.width * image.height > 25000000) { image.close(); image = undefined; throw new Error('Choose an image smaller than 25 megapixels.'); }
        drawCrop();
      } catch (error) { if (imageGeneration === generation && relay) status('Image Unavailable', error.message); }
    });
  }
  submit.disabled = !relay; submit.textContent = relay ? 'Send To TV' : 'Pairing Required';
}
function drawCrop() {
  if (!image || !cropCanvas || !document.querySelector('#zoom')) return;
  const context = cropCanvas.getContext('2d');
  const scale = Math.max(cropCanvas.width / image.width, cropCanvas.height / image.height) * Number(document.querySelector('#zoom').value);
  const width = image.width * scale, height = image.height * scale;
  context.fillStyle = '#fff'; context.fillRect(0, 0, cropCanvas.width, cropCanvas.height);
  context.drawImage(image, (cropCanvas.width - width) * Number(document.querySelector('#cropx').value) / 100,
    (cropCanvas.height - height) * Number(document.querySelector('#cropy').value) / 100, width, height);
}
async function send(message) {
  if (!relay || busy) return;
  const activeRelay = relay;
  const activeGeneration = generation;
  const mode = manifest.mode;
  busy = true; submit.disabled = true;
  fields.querySelectorAll('input,button,select').forEach(input => input.disabled = true);
  status('Sending Securely…', 'Keep the QR dialog open on your TV.');
  try {
    await activeRelay.send(message);
    if (activeGeneration !== generation) return;
    form.reset();
    status('Received By Your TV', ['signin','signup'].includes(mode) ? 'Confirm on your TV to continue. Your password has been cleared from this form.' :
      mode === 'paste' && manifest.inputKind === 'secret' ? 'Save the key in Integrations on your TV. This form has been cleared.' : 'Your TV has confirmed receipt.');
    if (mode === 'hub') { showMode('hub'); }
    else { relay = undefined; fields.replaceChildren(); submit.textContent = 'Received By TV'; }
  } catch (error) {
    if (activeGeneration !== generation) return;
    // Clear credentials after any attempt, even if delivery acknowledgement failed.
    if (['signin','signup'].includes(mode) || (mode === 'paste' && manifest.inputKind === 'secret')) form.reset();
    status('Could Not Confirm Delivery', error.message);
    fields.querySelectorAll('input,button,select').forEach(input => input.disabled = false);
    submit.disabled = false;
  } finally { if (activeGeneration === generation) busy = false; }
}
form.addEventListener('submit', async event => {
  event.preventDefault(); if (!relay || busy) return;
  try {
    const mode = manifest.mode; const message = { mode };
    if (['signin','signup'].includes(mode)) {
      const username = document.querySelector('#username').value.trim().toLowerCase();
      const password = document.querySelector('#password').value;
      if (!/^[a-z0-9_]{3,32}$/.test(username)) throw new Error('Use 3–32 letters, numbers or underscores for your username.');
      if (!password || password.length > 128) throw new Error('Enter your password (maximum 128 characters).');
      if (mode === 'signup') {
        if (password.length < 8 || !/[A-Z]/.test(password) || !/[a-z]/.test(password) || !/[0-9]/.test(password) || !/[^\p{L}\p{N}\s]/u.test(password)) throw new Error('Use 8–128 characters with uppercase, lowercase, number and symbol.');
        if (password !== document.querySelector('#confirm').value) throw new Error('Passwords do not match.');
        const available = await fetchJson(`${DATA}/rpc/saabtv_username_available`, { method:'POST', headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`}, body:JSON.stringify({requested_username:username}) });
        if (!available) throw new Error('This username is already taken.');
      }
      Object.assign(message, { username, password });
    } else if (mode === 'paste' || mode === 'search') {
      message.value = document.querySelector('#value').value.trim();
      const secretPaste = mode === 'paste' && manifest.inputKind === 'secret';
      if (!message.value || message.value.length > (secretPaste ? 256 : mode === 'search' ? 200 : 2048)) throw new Error('Enter valid text.');
      if (secretPaste && /\s/.test(message.value)) throw new Error('The API key must not contain spaces.');
      if (mode === 'paste' && !secretPaste && !['http:', 'https:'].includes(new URL(message.value).protocol)) throw new Error('Use an HTTP or HTTPS link.');
    } else {
      if (!image) throw new Error('Choose an image first.');
      const blob = await new Promise(resolve => cropCanvas.toBlob(resolve, 'image/jpeg', .9));
      if (!blob || blob.size > 524288) throw new Error('The cropped image is too large. Choose another image.');
      message.image = encode(new Uint8Array(await blob.arrayBuffer()));
      if (mode === 'hub') message.item = document.querySelector('#item').value;
    }
    await send(message);
  } catch (error) { status('Check Your Details', error.message); }
});
document.querySelector('nav').hidden = true; // The authenticated QR manifest, not tabs, selects the only tool.
document.querySelector('footer').textContent = 'End-to-end encrypted to your TV. No analytics or browser storage. Keep the pairing QR private. Requires internet on both devices.';
document.querySelector('section').hidden = true;
async function connect() {
  const params = new URLSearchParams(location.hash.slice(1));
  const activeGeneration = ++generation;
  clearTimeout(expiryTimer);
  relay = undefined; token = undefined; busy = false; form.reset(); fields.replaceChildren(); submit.disabled = true;
  document.querySelector('section').hidden = true;
  history.replaceState(null, '', location.pathname); // Do not leave pairing secrets in browser history.
  if (!params.has('id')) { status('Scan Your TV’s QR Code', 'Open the required tool on your TV and scan its QR.'); return; }
  try {
    const id = params.get('id'), cap = params.get('cap'), rawKey = decode(params.get('key') || '');
    if (!/^[0-9a-f-]{36}$/.test(id) || !/^[A-Za-z0-9_-]{43}$/.test(cap) || rawKey.length !== 32) throw new Error('Invalid QR code. Scan again from your TV.');
    status('Connecting Securely…', 'Checking the temporary pairing session.');
    const key = await crypto.subtle.importKey('raw', rawKey, 'AES-GCM', false, ['encrypt','decrypt']); rawKey.fill(0);
    const authToken = (await fetchJson(`${AUTH}/token/anonymous`)).token;
    const candidate = new Relay(id, cap, key, authToken);
    const fetchedManifest = await candidate.manifest();
    if (activeGeneration !== generation) return;
    token = authToken; manifest = fetchedManifest;
    if (manifest.version !== 1 || !Object.hasOwn(modes, manifest.mode)) throw new Error('Unsupported pairing. Update your TV app.');
    relay = candidate; showMode(manifest.mode); document.querySelector('section').hidden = false; status('TV Connected', 'Pairing expires in five minutes. Keep the TV dialog open.');
    const ttl = Math.max(0, candidate.expires - Date.now());
    expiryTimer = setTimeout(() => {
      generation++; busy = false;
      relay = undefined; token = undefined; image?.close(); image = undefined; cropCanvas = undefined; fields.replaceChildren();
      submit.disabled = true; submit.textContent = 'Pairing Expired'; status('Pairing Expired', 'Reopen the QR code on your TV.');
    }, ttl);
  } catch (error) { if (activeGeneration === generation) { relay = undefined; status('Connection Unavailable', error.message); } }
}
connect();
window.addEventListener('hashchange', connect);
window.addEventListener('pagehide', () => {
  generation++; clearTimeout(expiryTimer); busy = false;
  form.reset(); fields.replaceChildren(); image?.close(); image = undefined;
  relay = undefined; token = undefined;
});
window.addEventListener('pageshow', event => {
  if (event.persisted) { submit.disabled = true; status('Pairing Closed', 'Scan a fresh QR code from your TV.'); }
});
