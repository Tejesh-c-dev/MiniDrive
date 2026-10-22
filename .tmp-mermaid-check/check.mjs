import fs from 'node:fs';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!DOCTYPE html><html><body></body></html>', { url: 'http://localhost/' });
const define = (key, value) => {
  try { Object.defineProperty(globalThis, key, { value, configurable: true, writable: true }); }
  catch (e) { /* leave native global in place */ }
};
define('window', dom.window);
define('document', dom.window.document);
define('DOMParser', dom.window.DOMParser);
define('Node', dom.window.Node);
define('Element', dom.window.Element);
define('HTMLElement', dom.window.HTMLElement);
define('SVGElement', dom.window.SVGElement);
define('getComputedStyle', dom.window.getComputedStyle.bind(dom.window));
define('MutationObserver', dom.window.MutationObserver);
define('requestAnimationFrame', (cb) => setTimeout(cb, 0));

const md = fs.readFileSync(process.argv[2], 'utf8');
const blocks = [...md.matchAll(/```mermaid\n([\s\S]*?)```/g)].map((m) => m[1]);
console.log(`found ${blocks.length} mermaid block(s)`);

const mermaid = (await import('mermaid')).default;
mermaid.initialize({ startOnLoad: false, securityLevel: 'loose' });

let failed = 0;
for (let i = 0; i < blocks.length; i++) {
  try {
    await mermaid.parse(blocks[i]);
    console.log(`block ${i + 1}: PARSE OK (${blocks[i].split('\n').length} lines)`);
  } catch (e) {
    failed++;
    console.log(`block ${i + 1}: PARSE FAILED -> ${e.message || e}`);
  }
}
process.exit(failed ? 1 : 0);
