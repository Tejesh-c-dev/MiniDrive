import fs from 'node:fs';

const logPath = process.argv[2] || 'stage.log';
const log = (msg) => fs.appendFileSync(logPath, `${Date.now()} ${msg}\n`);

log('start');
const { JSDOM } = await import('jsdom');
log('jsdom imported');

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/' });
const define = (key, value) => {
  try { Object.defineProperty(globalThis, key, { value, configurable: true, writable: true }); }
  catch (e) { log(`define failed: ${key}`); }
};
define('window', dom.window);
define('document', dom.window.document);
define('DOMParser', dom.window.DOMParser);
define('Node', dom.window.Node);
define('Element', dom.window.Element);
define('HTMLElement', dom.window.HTMLElement);
define('SVGElement', dom.window.SVGElement);
define('getComputedStyle', dom.window.getComputedStyle.bind(dom.window));
define('requestAnimationFrame', (cb) => setTimeout(cb, 0));
log('globals set');

const mod = await import('mermaid');
log('mermaid module imported: ' + Object.keys(mod).join(',').slice(0, 120));

const mermaid = mod.default;
log('initialize...');
mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });
log('initialized');

const md = fs.readFileSync(process.argv[3], 'utf8');
const blocks = [...md.matchAll(/```mermaid\n([\s\S]*?)```/g)].map((m) => m[1]);
log(`found ${blocks.length} block(s)`);

for (let i = 0; i < blocks.length; i++) {
  log(`parsing block ${i + 1}`);
  try {
    const res = await Promise.race([
      mermaid.parse(blocks[i]),
      new Promise((_, rej) => setTimeout(() => rej(new Error('TIMEOUT 30s')), 30000)),
    ]);
    log(`block ${i + 1} PARSE OK ${JSON.stringify(res)}`);
  } catch (e) {
    log(`block ${i + 1} PARSE FAILED: ${e && e.message ? e.message : e}`);
  }
}
log('done');
process.exit(0);
