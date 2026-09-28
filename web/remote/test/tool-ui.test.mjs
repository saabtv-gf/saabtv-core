import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const source = (await readFile(new URL('../app.js', import.meta.url), 'utf8')).replace(/^import .*\n/, '');
for (const scenario of [
  ...['signin', 'signup', 'paste', 'search', 'avatar', 'hub'].map(mode => ({ mode })),
  { mode: 'paste', inputKind: 'secret' }
]) {
  const { mode, inputKind } = scenario;
  test(`QR ${mode}${inputKind ? ` ${inputKind}` : ''} displays only its own inputs and no tool tabs`, async () => {
    const nodes = new Map();
    class Element {
      constructor(tag) { this.tag = tag; this.children = []; this.dataset = {}; this.attributes = {}; }
      set id(value) { this._id = value; nodes.set(`#${value}`, this); }
      get id() { return this._id; }
      append(...items) { items.forEach(item => { item.parentElement = this; this.children.push(item); }); }
      replaceChildren() { this.children = []; }
      setAttribute(key, value) { this.attributes[key] = value; }
      addEventListener() {}
      querySelector(selector) {
        if (selector.startsWith('#')) return nodes.get(selector);
        for (const child of this.children) { if (child.tag === selector) return child; const found = child.querySelector(selector); if (found) return found; }
      }
      reset() {}
    }
    for (const id of ['fields', 'form', 'title', 'description', 'status-title', 'status']) { const element = new Element('div'); element.id = id; }
    nodes.set('.primary', new Element('button'));
    nodes.set('nav', new Element('nav')); nodes.set('footer', new Element('footer')); nodes.set('section', new Element('section'));
    const tabs = ['signin', 'signup', 'paste', 'search', 'avatar', 'hub'].map(value => {
      const element = new Element('button'); element.dataset.mode = value; return element;
    });
    const context = {
      document: { querySelector: selector => nodes.get(selector), querySelectorAll: () => tabs, createElement: tag => new Element(tag) },
      URLSearchParams, location: { pathname: '/', hash: `#id=12345678-1234-1234-1234-123456789abc&cap=${'a'.repeat(43)}&key=test` },
      history: { replaceState() {} }, window: { addEventListener() {} },
      setTimeout() { return 1; }, clearTimeout() {},
      decode: () => new Uint8Array(32), crypto: { subtle: { async importKey() { return {}; } } },
      AUTH: 'test-auth', DATA: 'test-data', async fetchJson() { return { token: 'fake' }; },
      Relay: class { expires = Date.now() + 300000; async manifest() { return { version: 1, mode, inputKind, items: [{id:'one', title:'Artwork'}] }; } }
    };
    vm.runInNewContext(source, context);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(nodes.get('#status-title').textContent, 'TV Connected');
    assert.equal(nodes.get('nav').hidden, true);
    assert.ok(tabs.every(tab => tab.hidden));
    const ids = [];
    function visit(element) { if (element.id && ['input','select'].includes(element.tag)) ids.push(element.id); element.children.forEach(visit); }
    visit(nodes.get('#fields'));
    const expected = mode === 'signin' ? ['username','password'] : mode === 'signup' ? ['username','password','confirm'] : ['paste','search'].includes(mode) ? ['value'] : mode === 'avatar' ? ['image','zoom','cropx','cropy'] : ['item','image','zoom','cropx','cropy'];
    assert.deepEqual(ids, expected);
    if (mode === 'signup') assert.equal(nodes.get('#username').placeholder, 'Username');
    if (inputKind === 'secret') {
      assert.equal(nodes.get('#value').type, 'password');
      assert.equal(nodes.get('#value').maxLength, 256);
      assert.equal(nodes.get('#title').textContent, 'Send TorBox API Key');
    }
  });
}
