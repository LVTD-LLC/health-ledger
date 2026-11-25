const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../assets/health.js'), 'utf8');

function makeEl(extra = {}) {
  const attrs = new Map();
  const listeners = new Map();
  const children = [];
  return {
    hidden: false,
    dataset: {},
    style: {},
    textContent: '',
    children,
    setAttribute(k, v) { attrs.set(k, String(v)); },
    getAttribute(k) { return attrs.get(k) ?? null; },
    removeAttribute(k) { attrs.delete(k); },
    addEventListener(type, fn) { listeners.set(type, fn); },
    trigger(type, event = {}) { const fn = listeners.get(type); if (fn) fn(event); },
    appendChild(child) { children.push(child); return child; },
    ...extra,
  };
}

test('dashboard tabs map #trend to overview and respect data-default-tab', () => {
  const sections = ['overview', 'timeline', 'entry'].map(id => makeEl({ id }));
  const links = ['#overview', '#timeline', '#entry'].map(hash => makeEl({ hash }));
  const ledger = makeEl({
    dataset: { defaultTab: 'overview' },
    querySelectorAll(sel) { return sel === ':scope > section[id]' ? sections : []; },
  });
  const nav = makeEl({
    querySelectorAll(sel) { return sel === 'a[href^="#"]' ? links : []; },
  });
  const windowObj = makeEl();
  const location = { hash: '#trend' };
  const document = {
    querySelector(sel) {
      if (sel === '.ledger') return ledger;
      if (sel === '.ledger-nav') return nav;
      return null;
    },
    querySelectorAll() { return []; },
    createElement(tag) { return makeEl({ tag }); },
  };

  vm.runInNewContext(source, { document, window: windowObj, location, Date, Number, Math });
  assert.equal(sections[0].hidden, false, '#trend should activate overview');
  assert.equal(sections[1].hidden, true);
  assert.equal(links[0].getAttribute('aria-current'), 'page');

  location.hash = '#timeline';
  windowObj.trigger('hashchange');
  assert.equal(sections[0].hidden, true);
  assert.equal(sections[1].hidden, false);
  assert.equal(links[1].getAttribute('aria-current'), 'page');
});

test('chart pointer interaction snaps crosshair and populates tooltip safely', () => {
  const crosshair = makeEl();
  const focusDot = makeEl();
  const tooltip = makeEl({ hidden: true });
  const pts = [
    makeEl({ dataset: { x: '60', y: '200', val: '80.35', unit: 'kg', date: '1 Aug 2026', delta: '' } }),
    makeEl({ dataset: { x: '738', y: '80', val: '84.55', unit: 'kg', date: '21 Nov 2026', delta: '+4.2' } }),
  ];
  const svg = makeEl({
    getBoundingClientRect: () => ({ left: 0, width: 760 }),
    querySelectorAll: sel => (sel === '[data-chart-point]' ? pts : []),
  });
  svg.setAttribute('viewBox', '0 0 760 276');

  const stage = makeEl({
    querySelector(sel) {
      if (sel === 'svg') return svg;
      if (sel === '[data-chart-crosshair]') return crosshair;
      if (sel === '[data-chart-focus]') return focusDot;
      if (sel === '[data-chart-tooltip]') return tooltip;
      return null;
    },
  });

  const document = {
    querySelector() { return null; },
    querySelectorAll(sel) { return sel === '[data-chart-stage]' ? [stage] : []; },
    createElement(tag) { return makeEl({ tag }); },
  };

  vm.runInNewContext(source, { document, window: makeEl(), location: { hash: '' }, Date, Number, Math });
  svg.trigger('pointermove', { clientX: 720 });
  assert.equal(crosshair.getAttribute('x1'), '738.0');
  assert.equal(focusDot.getAttribute('cx'), '738.0');
  assert.equal(focusDot.getAttribute('cy'), '80.0');
  assert.equal(tooltip.hidden, false);
  assert.equal(tooltip.children[0].textContent, '84.55 kg');
  assert.equal(tooltip.children[1].textContent, '21 Nov 2026');
  assert.equal(tooltip.children[2].textContent, '+4.2 vs prev');

  svg.trigger('pointerleave');
  assert.equal(tooltip.hidden, true);
  assert.equal(crosshair.getAttribute('hidden'), '');
});
