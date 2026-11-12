// react and react-dom/client are loaded dynamically AFTER bootstrapping window/document
// so React's module-level DOM feature detection (isInputEventSupported) succeeds.

class MiniNode {
  constructor(nodeType, nodeName, ownerDocument) {
    this.nodeType = nodeType;
    this.nodeName = nodeName;
    this.ownerDocument = ownerDocument;
    this.parentNode = null;
    this.childNodes = [];
    this._listeners = new Map();
  }

  get firstChild() {
    return this.childNodes[0] || null;
  }

  get lastChild() {
    return this.childNodes[this.childNodes.length - 1] || null;
  }

  get nextSibling() {
    if (!this.parentNode) return null;
    const idx = this.parentNode.childNodes.indexOf(this);
    return idx >= 0 && idx + 1 < this.parentNode.childNodes.length
      ? this.parentNode.childNodes[idx + 1]
      : null;
  }

  get previousSibling() {
    if (!this.parentNode) return null;
    const idx = this.parentNode.childNodes.indexOf(this);
    return idx > 0 ? this.parentNode.childNodes[idx - 1] : null;
  }

  appendChild(child) {
    if (!child) return child;
    if (child.nodeType === 11) {
      const items = [...child.childNodes];
      items.forEach((item) => this.appendChild(item));
      return child;
    }
    if (child.parentNode) {
      child.parentNode.removeChild(child);
    }
    child.parentNode = this;
    this.childNodes.push(child);
    return child;
  }

  insertBefore(newChild, refChild) {
    if (!refChild) {
      return this.appendChild(newChild);
    }
    if (newChild.nodeType === 11) {
      const items = [...newChild.childNodes];
      items.forEach((item) => this.insertBefore(item, refChild));
      return newChild;
    }
    if (newChild.parentNode) {
      newChild.parentNode.removeChild(newChild);
    }
    const idx = this.childNodes.indexOf(refChild);
    if (idx === -1) {
      return this.appendChild(newChild);
    }
    newChild.parentNode = this;
    this.childNodes.splice(idx, 0, newChild);
    return newChild;
  }

  removeChild(child) {
    const idx = this.childNodes.indexOf(child);
    if (idx !== -1) {
      this.childNodes.splice(idx, 1);
      child.parentNode = null;
    }
    return child;
  }

  contains(target) {
    let cursor = target;
    while (cursor) {
      if (cursor === this) return true;
      cursor = cursor.parentNode;
    }
    return false;
  }

  addEventListener(type, listener) {
    if (typeof listener !== 'function') return;
    if (!this._listeners.has(type)) {
      this._listeners.set(type, new Set());
    }
    this._listeners.get(type).add(listener);
  }

  removeEventListener(type, listener) {
    const set = this._listeners.get(type);
    if (set) {
      set.delete(listener);
    }
  }

  dispatchEvent(event) {
    event.target = event.target || this;
    const path = [];
    let cursor = this;
    while (cursor) {
      path.push(cursor);
      cursor = cursor.parentNode;
    }
    if (this.ownerDocument) {
      path.push(this.ownerDocument);
      if (this.ownerDocument.defaultView) {
        path.push(this.ownerDocument.defaultView);
      }
    }

    for (const node of path) {
      if (event._stopped) break;
      event.currentTarget = node;
      const listeners = node._listeners?.get(event.type);
      if (listeners) {
        for (const fn of [...listeners]) {
          fn.call(node, event);
        }
      }
      if (event.bubbles === false) break;
    }
    return !event.defaultPrevented;
  }
}

class MiniText extends MiniNode {
  constructor(data, ownerDocument) {
    super(3, '#text', ownerDocument);
    this._data = String(data ?? '');
  }
  get data() {
    return this._data;
  }
  set data(v) {
    this._data = String(v ?? '');
  }
  get nodeValue() {
    return this._data;
  }
  set nodeValue(v) {
    this._data = String(v ?? '');
  }
  get textContent() {
    return this._data;
  }
  set textContent(v) {
    this._data = String(v ?? '');
  }
}

class MiniComment extends MiniNode {
  constructor(data, ownerDocument) {
    super(8, '#comment', ownerDocument);
    this.data = String(data ?? '');
  }
  get nodeValue() {
    return this.data;
  }
  set nodeValue(v) {
    this.data = String(v ?? '');
  }
}

class MiniDocumentFragment extends MiniNode {
  constructor(ownerDocument) {
    super(11, '#document-fragment', ownerDocument);
  }
}

class MiniElement extends MiniNode {
  constructor(tagName, ownerDocument, namespaceURI = 'http://www.w3.org/1999/xhtml') {
    const upper = String(tagName).toUpperCase();
    super(1, upper, ownerDocument);
    this.tagName = upper;
    this.localName = String(tagName).toLowerCase();
    this.namespaceURI = namespaceURI;
    this.attributes = {};
    this.style = {};
    this._value = '';
    this.checked = false;
    this.defaultChecked = false;
    this._selected = false;
    this.defaultSelected = false;
    this.disabled = false;
    this.className = '';
    this.oninput = null;
    this.onchange = null;
    this.onclick = null;
    this.onsubmit = null;
  }

  get id() {
    return this.attributes.id || '';
  }
  set id(v) {
    this.attributes.id = String(v);
  }

  get type() {
    return this.attributes.type || '';
  }
  set type(v) {
    this.attributes.type = String(v);
  }

  get name() {
    return this.attributes.name || '';
  }
  set name(v) {
    this.attributes.name = String(v);
  }

  get value() {
    return this._value;
  }
  set value(v) {
    this._value = String(v ?? '');
    if (this.localName === 'select') {
      const target = this._value;
      for (const opt of this.options) {
        opt._selected = opt.value === target || opt.getAttribute('value') === target;
      }
    }
  }

  get selected() {
    return Boolean(this._selected);
  }
  set selected(v) {
    this._selected = Boolean(v);
  }

  get multiple() {
    return this.hasAttribute('multiple');
  }
  set multiple(v) {
    if (v) {
      this.setAttribute('multiple', '');
    } else {
      this.removeAttribute('multiple');
    }
  }

  get selectedIndex() {
    return this.options.findIndex((opt) => opt.selected);
  }
  set selectedIndex(idx) {
    const opts = this.options;
    opts.forEach((opt, i) => {
      opt._selected = i === idx;
    });
    if (idx >= 0 && opts[idx]) {
      this._value = opts[idx].value ?? opts[idx].getAttribute('value') ?? '';
    }
  }

  get options() {
    return this.querySelectorAll('option');
  }

  get selectedOptions() {
    return this.options.filter((opt) => opt.selected);
  }

  get textContent() {
    if (this.childNodes.length === 0) {
      return '';
    }
    return this.childNodes.map((c) => c.textContent ?? '').join('');
  }
  set textContent(v) {
    this.childNodes.forEach((c) => {
      c.parentNode = null;
    });
    this.childNodes = [];
    if (v !== null && v !== undefined && String(v) !== '') {
      this.appendChild(new MiniText(String(v), this.ownerDocument));
    }
  }

  setAttribute(name, value) {
    const key = String(name);
    const val = String(value);
    this.attributes[key] = val;
    if (key === 'class') this.className = val;
    if (key === 'value') this._value = val;
    if (key === 'disabled') this.disabled = val !== 'false';
  }

  getAttribute(name) {
    const key = String(name);
    return Object.prototype.hasOwnProperty.call(this.attributes, key)
      ? this.attributes[key]
      : null;
  }

  removeAttribute(name) {
    const key = String(name);
    delete this.attributes[key];
    if (key === 'disabled') this.disabled = false;
    if (key === 'class') this.className = '';
  }

  hasAttribute(name) {
    return Object.prototype.hasOwnProperty.call(this.attributes, String(name));
  }

  focus() {
    if (this.ownerDocument) {
      this.ownerDocument.activeElement = this;
    }
  }

  blur() {}

  querySelectorAll(selector) {
    const matches = [];
    const check = (el) => {
      if (matchesSelector(el, selector)) {
        matches.push(el);
      }
      el.childNodes.forEach((c) => {
        if (c.nodeType === 1) check(c);
      });
    };
    this.childNodes.forEach((c) => {
      if (c.nodeType === 1) check(c);
    });
    return matches;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
}

function matchesSelector(el, selector) {
  if (!el || el.nodeType !== 1) return false;
  if (selector.startsWith('#')) {
    return el.id === selector.slice(1);
  }
  const attrMatch = /^\[([^=\]]+)(?:="([^"]*)")?\]$/.exec(selector);
  if (attrMatch) {
    const [, attr, expected] = attrMatch;
    if (!el.hasAttribute(attr)) return false;
    return expected === undefined ? true : el.getAttribute(attr) === expected;
  }
  const tagAttrMatch = /^([a-zA-Z0-9_-]+)\[([^=\]]+)="([^"]*)"\]$/.exec(selector);
  if (tagAttrMatch) {
    const [, tag, attr, expected] = tagAttrMatch;
    return el.localName === tag.toLowerCase() && el.getAttribute(attr) === expected;
  }
  return el.localName === selector.toLowerCase();
}

export class MiniEvent {
  constructor(type, init = {}) {
    this.type = type;
    this.bubbles = init.bubbles !== false;
    this.cancelable = init.cancelable !== false;
    this.defaultPrevented = false;
    this._stopped = false;
    this.target = null;
    this.currentTarget = null;
  }
  preventDefault() {
    this.defaultPrevented = true;
  }
  stopPropagation() {
    this._stopped = true;
  }
}

export async function createDomEnvironment(initialUrl = 'http://localhost:5173/') {
  const storageMap = new Map();
  const sessionStorage = {
    getItem: (k) => (storageMap.has(String(k)) ? storageMap.get(String(k)) : null),
    setItem: (k, v) => storageMap.set(String(k), String(v)),
    removeItem: (k) => storageMap.delete(String(k)),
    clear: () => storageMap.clear(),
  };

  const url = new URL(initialUrl);
  const location = {
    get href() {
      return url.href;
    },
    get pathname() {
      return url.pathname;
    },
    set pathname(v) {
      url.pathname = v;
    },
    get search() {
      return url.search;
    },
    get hash() {
      return url.hash;
    },
    set hash(v) {
      url.hash = v.startsWith('#') ? v : `#${v}`;
    },
  };

  const doc = {
    nodeType: 9,
    nodeName: '#document',
    _listeners: new Map(),
    body: null,
    activeElement: null,
    oninput: null,
    onchange: null,
    onclick: null,
    createElement(tag) {
      return new MiniElement(tag, doc);
    },
    createElementNS(ns, tag) {
      return new MiniElement(tag, doc, ns);
    },
    createTextNode(text) {
      return new MiniText(text, doc);
    },
    createComment(text) {
      return new MiniComment(text, doc);
    },
    createDocumentFragment() {
      return new MiniDocumentFragment(doc);
    },
    addEventListener(type, fn) {
      if (!doc._listeners.has(type)) doc._listeners.set(type, new Set());
      doc._listeners.get(type).add(fn);
    },
    removeEventListener(type, fn) {
      doc._listeners.get(type)?.delete(fn);
    },
  };
  doc.body = new MiniElement('body', doc);

  const win = {
    _listeners: new Map(),
    document: doc,
    location,
    sessionStorage,
    history: {
      pushState(_state, _title, nextUrl) {
        const parsed = new URL(nextUrl, url.href);
        url.pathname = parsed.pathname;
        url.search = parsed.search;
        url.hash = parsed.hash;
      },
    },
    Event: MiniEvent,
    CustomEvent: MiniEvent,
    HTMLElement: MiniElement,
    HTMLInputElement: MiniElement,
    HTMLTextAreaElement: MiniElement,
    HTMLSelectElement: MiniElement,
    HTMLButtonElement: MiniElement,
    HTMLFormElement: MiniElement,
    HTMLIFrameElement: class HTMLIFrameElement {},
    Element: MiniElement,
    Node: MiniNode,
    addEventListener(type, fn) {
      if (!win._listeners.has(type)) win._listeners.set(type, new Set());
      win._listeners.get(type).add(fn);
    },
    removeEventListener(type, fn) {
      win._listeners.get(type)?.delete(fn);
    },
    dispatchEvent(evt) {
      const list = win._listeners.get(evt.type);
      if (list) {
        for (const fn of [...list]) fn(evt);
      }
      return true;
    },
    setHash(nextHash) {
      url.hash = nextHash.startsWith('#') ? nextHash : `#${nextHash}`;
      win.dispatchEvent(new MiniEvent('hashchange', { bubbles: false }));
    },
  };
  doc.defaultView = win;

  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  globalThis.window = win;
  globalThis.document = doc;
  globalThis.Event = MiniEvent;
  globalThis.HTMLElement = MiniElement;
  globalThis.HTMLInputElement = MiniElement;
  globalThis.HTMLTextAreaElement = MiniElement;
  globalThis.HTMLSelectElement = MiniElement;
  globalThis.Element = MiniElement;
  globalThis.Node = MiniNode;
  if (typeof globalThis.navigator === 'undefined') {
    globalThis.navigator = { userAgent: 'Node' };
  }

  const { act } = await import('react');
  const { createRoot } = await import('react-dom/client');

  const container = doc.createElement('div');
  doc.body.appendChild(container);

  let root = null;

  async function render(element) {
    if (!root) {
      root = createRoot(container);
    }
    await act(async () => {
      root.render(element);
    });
  }

  async function flush() {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  }

  async function unmount() {
    if (root) {
      await act(async () => {
        root.unmount();
      });
      root = null;
    }
    delete globalThis.window;
    delete globalThis.document;
  }

  async function changeInput(el, value) {
    const valueDescriptor = Object.getOwnPropertyDescriptor(MiniElement.prototype, 'value');
    if (valueDescriptor && valueDescriptor.set) {
      valueDescriptor.set.call(el, String(value ?? ''));
    } else {
      el.value = value;
    }
    await act(async () => {
      el.dispatchEvent(new MiniEvent('input', { bubbles: true }));
      el.dispatchEvent(new MiniEvent('change', { bubbles: true }));
    });
  }

  async function click(el) {
    await act(async () => {
      el.dispatchEvent(new MiniEvent('click', { bubbles: true }));
    });
  }

  async function submit(formEl) {
    await act(async () => {
      formEl.dispatchEvent(new MiniEvent('submit', { bubbles: true }));
    });
  }

  return {
    window: win,
    document: doc,
    container,
    sessionStorage,
    act,
    render,
    flush,
    unmount,
    changeInput,
    click,
    submit,
  };
}
