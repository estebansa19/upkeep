'use strict';
(() => {
  const TOKEN = document.querySelector('meta[name="ck-token"]').content;
  const $ = (s) => document.querySelector(s);
  const $$ = (s) => Array.from(document.querySelectorAll(s));

  // reason bits, same as RegionData on the server
  const R = { BUILD: 1, STORAGE: 2, MOBS: 4, PORTAL: 8, PLAYER: 16, END: 32, STRUCTURE: 64 };
  const CATS = [
    { bit: R.BUILD, label: 'Builds', desc: 'player-placed blocks outside natural structures' },
    { bit: R.STORAGE, label: 'Storage & workstations', desc: 'containers with items, beacons, signs with text…' },
    { bit: R.MOBS, label: 'Pets, villagers & named mobs', desc: 'tamed, named, leashed, traded with; item frames, armor stands' },
    { bit: R.PORTAL, label: 'Portals', desc: 'nether portals, activated End portals' },
    { bit: R.PLAYER, label: 'Players & spawn', desc: 'player positions, respawn points, world spawn' },
    { bit: R.END, label: 'End island & gateways', desc: 'main End island and the gateways you used' },
    { bit: R.STRUCTURE, label: 'Structures your farms use', desc: 'fortress, monument, witch hut or outpost you built in' },
  ];
  const STATE_NAMES = ['not generated', 'unfinished (edge)', 'generated', 'unreadable'];

  // ------------------------------------------------------------------ state
  const S = {
    session: null, dims: new Map(), dimKey: null,
    tool: 'keep', brush: 1, space: false,
    ov: { sugg: true, preview: false, heat: false, grid: true },
    cats: new Set(CATS.map((c) => c.bit)), visited: true, visitedMin: 60, ring: 1,
    undo: [], redo: [],
    cam: { x: 0, z: 0, s: 0.5 },
    hover: null, drag: null, needDraw: false,
    dimOn: new Map(), dry: null, dryStale: true,
    poll: null, infoQueue: [], infoActive: 0,
  };
  window.chunkKeeper = S; // handy for debugging from the browser console

  // ------------------------------------------------------------------ helpers
  async function api(path, opts = {}) {
    const r = await fetch('/api/' + path, { ...opts, headers: { 'X-CK-Token': TOKEN, ...(opts.headers || {}) } });
    const ct = r.headers.get('content-type') || '';
    const body = ct.includes('json') ? await r.json() : await r.text();
    if (!r.ok) throw new Error((body && body.error) || body || r.statusText);
    return body;
  }
  const post = (path, obj) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(obj) });
  const fmt = (n) => Number(n).toLocaleString();
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const rkey = (rx, rz) => rx + ',' + rz;
  const cidx = (cx, cz) => (cx & 31) | ((cz & 31) << 5);
  function duration(sec) {
    if (sec < 60) return sec + ' s';
    if (sec < 3600) return Math.round(sec / 60) + ' min';
    return Math.floor(sec / 3600) + ' h ' + Math.round((sec % 3600) / 60) + ' min';
  }
  let toastTimer = null;
  function toast(msg, err) {
    const t = $('#toast');
    t.textContent = msg;
    t.className = 'toast' + (err ? ' err' : '');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.add('hidden'), err ? 6000 : 2600);
  }
  const cur = () => S.dims.get(S.dimKey);
  const redraw = () => { if (!S.needDraw) { S.needDraw = true; requestAnimationFrame(draw); } };

  // ------------------------------------------------------------------ start screen
  async function showStart() {
    stopPolling();
    $('#screenMain').classList.add('hidden');
    $('#screenStart').classList.remove('hidden');
    $('#btnChangeWorld').classList.add('hidden');
    $('#worldTitle').innerHTML = '';
    $('#scanStatus').textContent = '';
    $('#lockBadge').classList.add('hidden');
    try {
      const r = await api('worlds');
      S.worlds = r.worlds;
      renderWorlds();
    } catch (e) {
      $('#worldList').innerHTML = '<div class="empty">Could not list worlds: ' + esc(e.message) + '</div>';
    }
  }

  function renderWorlds() {
    const f = $('#worldFilter').value.trim().toLowerCase();
    const list = (S.worlds || []).filter((w) => !f || (w.name + ' ' + w.folder + ' ' + (w.instance || '')).toLowerCase().includes(f));
    const el = $('#worldList');
    if (!list.length) { el.innerHTML = '<div class="empty">' + (S.worlds && S.worlds.length ? 'No world matches.' : 'No worlds found in the usual launcher folders. Paste a folder path below.') + '</div>'; return; }
    el.innerHTML = '';
    for (const w of list) {
      const b = document.createElement('button');
      b.className = 'world';
      const when = w.lastPlayed ? new Date(w.lastPlayed).toLocaleDateString() : '';
      b.innerHTML = '<span class="name">' + esc(w.name) + '</span><span class="ver">' + esc(w.version || '') + (when ? '<br>' + esc(when) : '') + '</span>'
        + '<span class="meta">' + esc(w.launcher) + (w.instance ? ' · ' + esc(w.instance) : '') + ' · ' + esc(w.folder) + '</span>';
      b.title = w.path;
      b.onclick = () => openWorld(w.path);
      el.appendChild(b);
    }
  }

  async function openWorld(path) {
    $('#startError').classList.add('hidden');
    try {
      document.body.style.cursor = 'progress';
      const info = await post('open', { path });
      enterMain(info);
    } catch (e) {
      $('#startError').textContent = e.message;
      $('#startError').classList.remove('hidden');
    } finally {
      document.body.style.cursor = '';
    }
  }

  // ------------------------------------------------------------------ main screen
  function enterMain(info) {
    S.session = info;
    S.dims = new Map();
    S.undo = []; S.redo = []; S.dry = null; S.dryStale = true; S.dimOn = new Map(); S.userMoved = false;
    S.infoQueue = [];
    for (const d of info.dims) {
      S.dims.set(d.key, {
        key: d.key, label: d.label, id: d.id, regions: d.regions,
        regionSet: new Set(d.regions.map((r) => rkey(r[0], r[1]))),
        info: new Map(), tiles: new Map(), keep: new Map(), sugg: new Map(), overlay: new Map(),
        global: null, notes: [], saveTimer: null, saving: false, counts: { existing: 0, kept: 0 }, suggCounts: null,
      });
    }
    $('#screenStart').classList.add('hidden');
    $('#screenMain').classList.remove('hidden');
    $('#btnChangeWorld').classList.remove('hidden');
    $('#worldTitle').innerHTML = '<b>' + esc(info.name) + '</b> · ' + esc(info.version) + (info.seed ? ' · seed ' + esc(info.seed) : '');
    $('#worldTitle').title = info.path + '\nMap colors: ' + info.colors + '\nSelections: ' + info.projectDir;
    $('#lockBadge').classList.toggle('hidden', !info.locked);
    $('#outPath').value = '';
    $('#outName').value = '';
    $('#applyResult').classList.add('hidden');
    $('#applyProgress').classList.add('hidden');
    $('#dryResult').classList.add('hidden');
    renderDimTabs();
    const first = info.dims.find((d) => d.regions.length) || info.dims[0];
    resizeCanvas();
    if (first) selectDim(first.key, true);
    for (const ds of S.dims.values()) loadSelection(ds);
    queueInfos();
    startPolling();
    selectTab('select');
  }

  function renderDimTabs() {
    const el = $('#dimTabs');
    el.innerHTML = '';
    for (const ds of S.dims.values()) {
      const b = document.createElement('button');
      b.dataset.dim = ds.key;
      b.innerHTML = esc(ds.label) + '<small>' + (ds.counts.existing ? fmt(ds.counts.existing) : ds.regions.length ? '…' : '0') + '</small>';
      b.classList.toggle('active', ds.key === S.dimKey);
      b.onclick = () => selectDim(ds.key);
      el.appendChild(b);
    }
  }

  function selectDim(key, fit) {
    S.dimKey = key;
    const ds = cur();
    $$('#dimTabs button').forEach((b) => b.classList.toggle('active', b.dataset.dim === key));
    $('#mapEmpty').classList.toggle('hidden', !!(ds && ds.regions.length));
    if (fit !== false && ds && !ds.viewed) fitView();
    if (ds) ds.viewed = true;
    renderCategories();
    updateCounts();
    renderNotes();
    queueInfos();
    redraw();
  }

  function fitView() {
    const ds = cur();
    const cv = $('#map');
    if (!ds || !ds.regions.length) { S.cam = { x: 0, z: 0, s: 0.5 }; return; }
    let x0 = Infinity, z0 = Infinity, x1 = -Infinity, z1 = -Infinity;
    for (const [rx, rz] of ds.regions) { x0 = Math.min(x0, rx * 512); z0 = Math.min(z0, rz * 512); x1 = Math.max(x1, rx * 512 + 512); z1 = Math.max(z1, rz * 512 + 512); }
    const w = cv.clientWidth || 800, h = cv.clientHeight || 600;
    S.cam.x = (x0 + x1) / 2;
    S.cam.z = (z0 + z1) / 2;
    S.cam.s = Math.max(1 / 64, Math.min(4, Math.min((w - 120) / (x1 - x0), (h - 120) / (z1 - z0))));
    redraw();
  }

  // ------------------------------------------------------------------ data loading
  function queueInfos() {
    const order = [];
    const curKey = S.dimKey;
    for (const ds of S.dims.values())
      for (const [rx, rz] of ds.regions)
        if (!ds.info.has(rkey(rx, rz))) order.push({ ds, rx, rz, pri: (ds.key === curKey ? 0 : 1e12) + (rx * 512 + 256 - S.cam.x) ** 2 + (rz * 512 + 256 - S.cam.z) ** 2 });
    order.sort((a, b) => a.pri - b.pri);
    S.infoQueue = order;
    pumpInfos();
  }

  function pumpInfos() {
    while (S.infoActive < 4 && S.infoQueue.length) {
      const job = S.infoQueue.shift();
      if (job.ds.info.has(rkey(job.rx, job.rz)) || !S.dims.has(job.ds.key) || S.dims.get(job.ds.key) !== job.ds) continue;
      S.infoActive++;
      api('region?dim=' + job.ds.key + '&rx=' + job.rx + '&rz=' + job.rz)
        .then((r) => {
          r.state = Uint8Array.from(r.state);
          job.ds.info.set(rkey(job.rx, job.rz), r);
          markOverlay(job.ds, rkey(job.rx, job.rz));
          scheduleSugg(job.ds);
        })
        .catch(() => job.ds.info.set(rkey(job.rx, job.rz), null))
        .finally(() => { S.infoActive--; pumpInfos(); });
    }
  }

  async function loadSelection(ds) {
    try {
      const text = await api('selection?dim=' + ds.key);
      ds.keep = new Map();
      parseSelection(ds, text);
      for (const k of ds.regionSet) markOverlay(ds, k);
      updateCounts();
      redraw();
    } catch (e) {
      toast('Could not load the saved selection: ' + e.message, true);
    }
  }

  function parseSelection(ds, text) {
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line || line.startsWith('#') || line === 'inverted') continue;
      const p = line.split(';').map(Number);
      if (p.length === 2) {
        const arr = keepArr(ds, rkey(p[0], p[1]));
        arr.fill(1);
      } else if (p.length === 4) {
        keepArr(ds, rkey(p[2] >> 5, p[3] >> 5))[cidx(p[2], p[3])] = 1;
      }
    }
  }

  function keepArr(ds, rk) {
    let a = ds.keep.get(rk);
    if (!a) { a = new Uint8Array(1024); ds.keep.set(rk, a); }
    return a;
  }

  function selectionText(ds) {
    const lines = [];
    for (const [rk, arr] of ds.keep) {
      const [rx, rz] = rk.split(',').map(Number);
      let n = 0;
      for (let i = 0; i < 1024; i++) n += arr[i];
      if (!n) continue;
      if (n === 1024) { lines.push(rx + ';' + rz); continue; }
      for (let i = 0; i < 1024; i++) if (arr[i]) lines.push(rx + ';' + rz + ';' + (rx * 32 + (i & 31)) + ';' + (rz * 32 + (i >> 5)));
    }
    return lines.join('\n') + (lines.length ? '\n' : '');
  }

  function scheduleSave(ds) {
    $('#saveState').textContent = 'saving…';
    clearTimeout(ds.saveTimer);
    ds.saveTimer = setTimeout(() => saveNow(ds), 700);
  }

  // saves run one after another, each sending the selection as it is when it starts
  async function saveNow(ds) {
    clearTimeout(ds.saveTimer);
    ds.saveTimer = null;
    const p = (ds.savePromise || Promise.resolve()).catch(() => {})
      .then(() => api('selection?dim=' + ds.key, { method: 'PUT', body: selectionText(ds) }));
    ds.savePromise = p;
    try {
      await p;
      if (ds.savePromise === p && !ds.saveTimer) $('#saveState').textContent = 'saved';
    } catch (e) {
      $('#saveState').textContent = 'not saved!';
      toast('Saving the selection failed: ' + e.message, true);
      throw e;
    }
  }

  async function flushSaves() {
    for (const ds of S.dims.values()) {
      if (ds.saveTimer) await saveNow(ds);
      else if (ds.savePromise) await ds.savePromise;
    }
  }

  // ------------------------------------------------------------------ polling
  function startPolling() {
    stopPolling();
    const tick = async () => {
      if (!S.session) return;
      try {
        const st = await api('status');
        $('#lockBadge').classList.toggle('hidden', !st.locked);
        S.locked = st.locked;
        let busy = null;
        for (const [key, p] of Object.entries(st.dims)) {
          const ds = S.dims.get(key);
          if (!ds) continue;
          if (!p.finished && !busy) busy = ds.label + ' ' + p.done + '/' + p.total + ' regions';
          if (p.finished && !ds.global) {
            const g = await api('global?dim=' + key);
            if (!g.pending) {
              ds.global = new Map(g.reasons.map((r) => [r[0] + ',' + r[1], r[2]]));
              ds.notes = g.notes || [];
              computeSugg(ds);
              if (ds.key === S.dimKey) renderNotes();
            }
          }
        }
        $('#scanStatus').textContent = busy ? 'Reading ' + busy : '';
        updateApplyGate();
      } catch (e) {
        $('#scanStatus').textContent = 'Lost connection to Chunk Keeper';
      }
    };
    tick();
    S.poll = setInterval(tick, 1500);
  }

  function stopPolling() { if (S.poll) clearInterval(S.poll); S.poll = null; }

  // ------------------------------------------------------------------ suggestions
  function scheduleSugg(ds) {
    clearTimeout(ds.suggTimer);
    ds.suggTimer = setTimeout(() => computeSugg(ds), 250);
  }

  function chunkExists(ds, cx, cz) {
    const rk = rkey(cx >> 5, cz >> 5);
    const info = ds.info.get(rk);
    if (info === undefined) return ds.regionSet.has(rk);
    return !!(info && info.state[cidx(cx, cz)]);
  }

  function chunkBits(ds, info, i) {
    const g = ds.global ? ds.global.get((info.rx * 32 + (i & 31)) + ',' + (info.rz * 32 + (i >> 5))) || 0 : 0;
    return info.reasons[i] | g;
  }

  function computeSugg(ds) {
    let mask = 0;
    for (const b of S.cats) mask |= b;
    const minSec = S.visited ? S.visitedMin * 60 : Infinity;
    const base = [];
    const catCount = {};
    let visitedCount = 0, existing = 0;
    for (const [rk, info] of ds.info) {
      if (!info) continue;
      for (let i = 0; i < 1024; i++) {
        if (!info.state[i]) continue;
        existing++;
        const bits = chunkBits(ds, info, i);
        for (const c of CATS) if (bits & c.bit) catCount[c.bit] = (catCount[c.bit] || 0) + 1;
        const vis = info.inh[i] >= S.visitedMin * 60;
        if (vis) visitedCount++;
        if ((bits & mask) || info.inh[i] >= minSec) base.push([info.rx * 32 + (i & 31), info.rz * 32 + (i >> 5)]);
      }
    }
    const out = new Map();
    const ring = S.ring;
    let total = 0;
    for (const [cx, cz] of base)
      for (let dx = -ring; dx <= ring; dx++)
        for (let dz = -ring; dz <= ring; dz++) {
          const x = cx + dx, z = cz + dz;
          if (!chunkExists(ds, x, z)) continue;
          const rk = rkey(x >> 5, z >> 5);
          let a = out.get(rk);
          if (!a) { a = new Uint8Array(1024); out.set(rk, a); }
          const i = cidx(x, z);
          if (!a[i]) { a[i] = 1; total++; }
        }
    ds.sugg = out;
    ds.suggCounts = { catCount, visitedCount, total };
    ds.counts.existing = existing;
    for (const k of ds.regionSet) markOverlay(ds, k);
    if (ds.key === S.dimKey) { renderCategories(); updateCounts(); }
    renderDimTabs();
    redraw();
  }

  function renderCategories() {
    const ds = cur();
    const el = $('#categories');
    el.innerHTML = '';
    const cc = ds && ds.suggCounts ? ds.suggCounts.catCount : {};
    for (const c of CATS) {
      const n = cc[c.bit] || 0;
      const lab = document.createElement('label');
      lab.className = 'row';
      lab.innerHTML = '<input type="checkbox"' + (S.cats.has(c.bit) ? ' checked' : '') + '><span>' + esc(c.label)
        + '<span class="desc">' + esc(c.desc) + '</span></span><span class="count">' + (ds && ds.suggCounts ? fmt(n) : '…') + '</span>';
      lab.querySelector('input').onchange = (e) => {
        if (e.target.checked) S.cats.add(c.bit); else S.cats.delete(c.bit);
        for (const d of S.dims.values()) computeSugg(d);
      };
      el.appendChild(lab);
    }
    const t = ds && ds.suggCounts ? ds.suggCounts.total : null;
    $('#suggCount').textContent = t == null ? (ds && ds.regions.length ? 'reading…' : '0') : fmt(t) + ' chunks';
    const notKept = countSuggNotKept(ds);
    $('#btnAcceptSugg').textContent = notKept ? 'Keep ' + fmt(notKept) + ' suggested chunks' : 'All suggestions are kept';
    $('#btnAcceptSugg').disabled = !notKept;
  }

  function countSuggNotKept(ds) {
    if (!ds) return 0;
    let n = 0;
    for (const [rk, a] of ds.sugg) {
      const k = ds.keep.get(rk);
      for (let i = 0; i < 1024; i++) if (a[i] && !(k && k[i])) n++;
    }
    return n;
  }

  function renderNotes() {
    const ds = cur();
    const el = $('#globalNotes');
    el.innerHTML = '';
    if (!ds) return;
    for (const n of ds.notes.slice(0, 12)) { const li = document.createElement('li'); li.textContent = n; el.appendChild(li); }
  }

  // ------------------------------------------------------------------ editing
  function beginOp() { return { dim: S.dimKey, changes: [] }; }

  function setKeep(ds, cx, cz, v, op) {
    if (!chunkExists(ds, cx, cz)) return;
    const rk = rkey(cx >> 5, cz >> 5);
    const a = keepArr(ds, rk);
    const i = cidx(cx, cz);
    if (a[i] === v) return;
    op.changes.push([rk, i, a[i], v]);
    a[i] = v;
    markOverlay(ds, rk);
  }

  function commitOp(op) {
    if (!op.changes.length) return;
    S.undo.push(op);
    if (S.undo.length > 200) S.undo.shift();
    S.redo = [];
    afterEdit(S.dims.get(op.dim));
  }

  function afterEdit(ds) {
    S.dryStale = true;
    scheduleSave(ds);
    updateCounts();
    renderCategories();
    updateApplyGate();
    redraw();
  }

  function undoRedo(from, to, useNew) {
    const op = from.pop();
    if (!op) return;
    const ds = S.dims.get(op.dim);
    if (!ds) return;
    for (let k = op.changes.length - 1; k >= 0; k--) {
      const [rk, i, oldV, newV] = op.changes[k];
      keepArr(ds, rk)[i] = useNew ? newV : oldV;
      markOverlay(ds, rk);
    }
    to.push(op);
    if (op.dim !== S.dimKey) selectDim(op.dim, false);
    afterEdit(ds);
  }

  function brushAt(cx, cz, v, op) {
    const ds = cur();
    const h = (S.brush - 1) >> 1;
    for (let dx = -h; dx <= h; dx++) for (let dz = -h; dz <= h; dz++) setKeep(ds, cx + dx, cz + dz, v, op);
  }

  function brushLine(a, b, v, op) {
    const n = Math.max(Math.abs(b.cx - a.cx), Math.abs(b.cz - a.cz));
    for (let k = 1; k <= n; k++) brushAt(Math.round(a.cx + (b.cx - a.cx) * k / n), Math.round(a.cz + (b.cz - a.cz) * k / n), v, op);
  }

  function boxApply(a, b, v) {
    const ds = cur();
    const op = beginOp();
    for (let cx = Math.min(a.cx, b.cx); cx <= Math.max(a.cx, b.cx); cx++)
      for (let cz = Math.min(a.cz, b.cz); cz <= Math.max(a.cz, b.cz); cz++) setKeep(ds, cx, cz, v, op);
    commitOp(op);
  }

  function updateCounts() {
    const ds = cur();
    if (!ds) return;
    let kept = 0, existing = 0;
    for (const [rk, info] of ds.info) {
      if (!info) continue;
      const k = ds.keep.get(rk);
      for (let i = 0; i < 1024; i++) if (info.state[i]) { existing++; if (k && k[i]) kept++; }
    }
    ds.counts = { existing, kept };
    $('#cntKeep').textContent = fmt(kept);
    $('#cntRegen').textContent = fmt(existing - kept);
  }

  // ------------------------------------------------------------------ drawing
  function markOverlay(ds, rk) { const o = ds.overlay.get(rk); if (o) o.dirty = true; }
  function markAllOverlays() { for (const ds of S.dims.values()) for (const o of ds.overlay.values()) o.dirty = true; redraw(); }

  function heatColor(sec) {
    const t = Math.min(1, Math.log10(sec / 60 + 1) / Math.log10(601));
    const stops = [[59, 130, 246], [250, 204, 21], [239, 68, 68]];
    const u = t < 0.5 ? t * 2 : (t - 0.5) * 2, a = t < 0.5 ? stops[0] : stops[1], b = t < 0.5 ? stops[1] : stops[2];
    return [a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u];
  }

  function overlayFor(ds, rk) {
    let o = ds.overlay.get(rk);
    if (o && !o.dirty) return o.canvas;
    if (!o) {
      const c = document.createElement('canvas');
      c.width = 32; c.height = 32;
      o = { canvas: c, dirty: true };
      ds.overlay.set(rk, o);
    }
    const ctx = o.canvas.getContext('2d');
    const img = ctx.createImageData(32, 32);
    const d = img.data;
    const info = ds.info.get(rk), keep = ds.keep.get(rk), sugg = ds.sugg.get(rk);
    for (let i = 0; i < 1024; i++) {
      const p = i * 4;
      const st = info ? info.state[i] : 0;
      let c = null, a = 0;
      if (keep && keep[i] && (!info || st)) { c = [52, 211, 153]; a = 100; }
      else if (S.ov.heat && st && info.inh[i] > 0) { c = heatColor(info.inh[i]); a = 160; }
      else if (S.ov.sugg && sugg && sugg[i]) { c = [245, 158, 11]; a = 95; }
      else if (S.ov.preview && st) { c = [239, 68, 68]; a = 100; }
      if (!c && st === 1) { c = [10, 11, 14]; a = 120; }
      if (!c && st === 3) { c = [217, 70, 239]; a = 170; }
      if (c) { d[p] = c[0]; d[p + 1] = c[1]; d[p + 2] = c[2]; d[p + 3] = a; }
    }
    ctx.putImageData(img, 0, 0);
    o.dirty = false;
    return o.canvas;
  }

  function tileFor(ds, rx, rz, lod) {
    const rk = rkey(rx, rz);
    for (const l of [lod, ...[0, 1, 2].filter((x) => x !== lod)]) {
      const t = ds.tiles.get(rk + '/' + l);
      if (t && t.ok) return t.img;
      if (l === lod && !t) {
        const img = new Image();
        const rec = { img, ok: false };
        ds.tiles.set(rk + '/' + l, rec);
        img.onload = () => { rec.ok = true; redraw(); };
        img.onerror = () => { rec.failed = true; };
        img.src = '/api/tile?dim=' + ds.key + '&rx=' + rx + '&rz=' + rz + '&lod=' + l + '&t=' + TOKEN;
      }
    }
    return null;
  }

  function resizeCanvas() {
    const cv = $('#map');
    const dpr = window.devicePixelRatio || 1;
    cv.width = Math.max(1, Math.round(cv.clientWidth * dpr));
    cv.height = Math.max(1, Math.round(cv.clientHeight * dpr));
    if (!S.userMoved && cur()) fitView(); // keep the whole world in view until the user moves the map
    redraw();
  }

  function draw() {
    S.needDraw = false;
    const cv = $('#map');
    const ctx = cv.getContext('2d');
    const W = cv.width, H = cv.height, dpr = window.devicePixelRatio || 1;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#0b0c0f';
    ctx.fillRect(0, 0, W, H);
    const ds = cur();
    if (!ds) return;
    const s = S.cam.s * dpr;
    const ox = W / 2 - S.cam.x * s, oz = H / 2 - S.cam.z * s;
    const x0 = -ox / s, x1 = (W - ox) / s, z0 = -oz / s, z1 = (H - oz) / s;
    const lod = s >= 0.75 ? 0 : s >= 0.19 ? 1 : 2;

    for (const [rx, rz] of ds.regions) {
      const bx = rx * 512, bz = rz * 512;
      if (bx + 512 < x0 || bx > x1 || bz + 512 < z0 || bz > z1) continue;
      const sx = Math.round(ox + bx * s), sz = Math.round(oz + bz * s), size = Math.round(ox + (bx + 512) * s) - sx;
      ctx.fillStyle = '#15171c';
      ctx.fillRect(sx, sz, size, size);
      const tile = tileFor(ds, rx, rz, lod);
      ctx.imageSmoothingEnabled = s < 1;
      if (tile) ctx.drawImage(tile, sx, sz, size, size);
      ctx.imageSmoothingEnabled = false;
      ctx.drawImage(overlayFor(ds, rkey(rx, rz)), sx, sz, size, size);
    }

    const chunkPx = 16 * s;
    if (S.ov.sugg && chunkPx >= 10) {
      ctx.strokeStyle = 'rgba(245,158,11,.95)';
      ctx.lineWidth = Math.max(1, dpr);
      ctx.beginPath();
      for (let cx = Math.floor(x0 / 16); cx <= Math.floor(x1 / 16); cx++)
        for (let cz = Math.floor(z0 / 16); cz <= Math.floor(z1 / 16); cz++) {
          const rk = rkey(cx >> 5, cz >> 5), a = ds.sugg.get(rk);
          if (!a || !a[cidx(cx, cz)]) continue;
          const k = ds.keep.get(rk);
          if (k && k[cidx(cx, cz)]) continue;
          ctx.rect(Math.round(ox + cx * 16 * s) + 1, Math.round(oz + cz * 16 * s) + 1, Math.round(chunkPx) - 2, Math.round(chunkPx) - 2);
        }
      ctx.stroke();
    }

    if (S.ov.grid) {
      if (chunkPx >= 8) gridLines(ctx, 16, s, ox, oz, x0, x1, z0, z1, W, H, 'rgba(255,255,255,.07)', 1);
      if (512 * s >= 24) gridLines(ctx, 512, s, ox, oz, x0, x1, z0, z1, W, H, 'rgba(255,255,255,.22)', Math.max(1, dpr));
    }

    if (S.session && S.session.spawn && ds.key === 'overworld') {
      const px = ox + (S.session.spawn[0] + 0.5) * s, pz = oz + (S.session.spawn[2] + 0.5) * s, r = 6 * dpr;
      ctx.fillStyle = '#fde68a'; ctx.strokeStyle = '#111'; ctx.lineWidth = dpr;
      ctx.beginPath(); ctx.moveTo(px, pz - r); ctx.lineTo(px + r, pz); ctx.lineTo(px, pz + r); ctx.lineTo(px - r, pz); ctx.closePath(); ctx.fill(); ctx.stroke();
    }

    if (S.drag && S.drag.type === 'box' && S.drag.end) {
      const a = S.drag.start, b = S.drag.end;
      const cx0 = Math.min(a.cx, b.cx), cz0 = Math.min(a.cz, b.cz), cx1 = Math.max(a.cx, b.cx) + 1, cz1 = Math.max(a.cz, b.cz) + 1;
      ctx.fillStyle = S.drag.v ? 'rgba(52,211,153,.25)' : 'rgba(239,68,68,.25)';
      ctx.strokeStyle = S.drag.v ? '#34d399' : '#ef4444';
      ctx.lineWidth = 2 * dpr;
      const rx0 = ox + cx0 * 16 * s, rz0 = oz + cz0 * 16 * s;
      ctx.fillRect(rx0, rz0, (cx1 - cx0) * 16 * s, (cz1 - cz0) * 16 * s);
      ctx.strokeRect(rx0, rz0, (cx1 - cx0) * 16 * s, (cz1 - cz0) * 16 * s);
    } else if (S.hover && !S.drag) {
      const brush = S.tool === 'keep' || S.tool === 'erase';
      const h = brush ? (S.brush - 1) >> 1 : 0;
      ctx.strokeStyle = S.tool === 'erase' || S.tool === 'boxerase' ? '#fca5a5' : '#ffffff';
      ctx.lineWidth = 1.5 * dpr;
      ctx.strokeRect(ox + (S.hover.cx - h) * 16 * s, oz + (S.hover.cz - h) * 16 * s, (2 * h + 1) * 16 * s, (2 * h + 1) * 16 * s);
    }
    if (S.flash && S.flash.until > performance.now()) {
      ctx.strokeStyle = '#60a5fa'; ctx.lineWidth = 3 * dpr;
      ctx.strokeRect(ox + S.flash.cx * 16 * s - 4 * dpr, oz + S.flash.cz * 16 * s - 4 * dpr, 16 * s + 8 * dpr, 16 * s + 8 * dpr);
      redrawLater();
    }
  }

  let laterTimer = null;
  function redrawLater() { if (!laterTimer) laterTimer = setTimeout(() => { laterTimer = null; redraw(); }, 120); }

  function gridLines(ctx, step, s, ox, oz, x0, x1, z0, z1, W, H, color, width) {
    ctx.strokeStyle = color;
    ctx.lineWidth = width;
    ctx.beginPath();
    for (let x = Math.ceil(x0 / step) * step; x <= x1; x += step) { const px = Math.round(ox + x * s) + 0.5; ctx.moveTo(px, 0); ctx.lineTo(px, H); }
    for (let z = Math.ceil(z0 / step) * step; z <= z1; z += step) { const pz = Math.round(oz + z * s) + 0.5; ctx.moveTo(0, pz); ctx.lineTo(W, pz); }
    ctx.stroke();
  }

  // ------------------------------------------------------------------ map input
  function worldAt(ev) {
    const r = $('#map').getBoundingClientRect();
    const x = S.cam.x + (ev.clientX - r.left - r.width / 2) / S.cam.s;
    const z = S.cam.z + (ev.clientY - r.top - r.height / 2) / S.cam.s;
    return { x, z, cx: Math.floor(x / 16), cz: Math.floor(z / 16) };
  }

  function setupMap() {
    const cv = $('#map');
    cv.addEventListener('contextmenu', (e) => e.preventDefault());
    cv.addEventListener('pointerdown', (e) => {
      cv.focus();
      const w = worldAt(e);
      cv.setPointerCapture(e.pointerId);
      if (e.button === 1 || e.button === 2 || S.tool === 'pan' || S.space) {
        S.drag = { type: 'pan', mx: e.clientX, mz: e.clientY, x: S.cam.x, z: S.cam.z };
        cv.classList.add('panning');
        return;
      }
      if (e.button !== 0) return;
      if (S.tool === 'keep' || S.tool === 'erase') {
        const op = beginOp();
        S.drag = { type: 'brush', v: S.tool === 'keep' ? 1 : 0, last: w, op };
        brushAt(w.cx, w.cz, S.drag.v, op);
        redraw();
      } else if (S.tool === 'boxkeep' || S.tool === 'boxerase') {
        S.drag = { type: 'box', v: S.tool === 'boxkeep' ? 1 : 0, start: w, end: w };
        redraw();
      } else if (S.tool === 'inspect') {
        inspect(w.cx, w.cz);
      }
    });
    cv.addEventListener('pointermove', (e) => {
      const w = worldAt(e);
      S.hover = w;
      status(w);
      if (!S.drag) { redraw(); return; }
      if (S.drag.type === 'pan') {
        S.userMoved = true;
        S.cam.x = S.drag.x - (e.clientX - S.drag.mx) / S.cam.s;
        S.cam.z = S.drag.z - (e.clientY - S.drag.mz) / S.cam.s;
      } else if (S.drag.type === 'brush') {
        if (w.cx !== S.drag.last.cx || w.cz !== S.drag.last.cz) { brushLine(S.drag.last, w, S.drag.v, S.drag.op); S.drag.last = w; }
      } else if (S.drag.type === 'box') {
        S.drag.end = w;
      }
      redraw();
    });
    const end = () => {
      if (!S.drag) return;
      if (S.drag.type === 'brush') commitOp(S.drag.op);
      if (S.drag.type === 'box') boxApply(S.drag.start, S.drag.end, S.drag.v);
      if (S.drag.type === 'pan') queueInfos();
      S.drag = null;
      cv.classList.remove('panning');
      redraw();
    };
    cv.addEventListener('pointerup', end);
    cv.addEventListener('pointercancel', end);
    cv.addEventListener('pointerleave', () => { if (!S.drag) { S.hover = null; redraw(); } });
    cv.addEventListener('wheel', (e) => {
      e.preventDefault();
      S.userMoved = true;
      const before = worldAt(e);
      const f = Math.pow(1.0018, -e.deltaY * (e.deltaMode === 1 ? 33 : 1));
      S.cam.s = Math.max(1 / 64, Math.min(16, S.cam.s * f));
      const r = cv.getBoundingClientRect();
      S.cam.x = before.x - (e.clientX - r.left - r.width / 2) / S.cam.s;
      S.cam.z = before.z - (e.clientY - r.top - r.height / 2) / S.cam.s;
      redraw();
    }, { passive: false });
    new ResizeObserver(resizeCanvas).observe(cv);
  }

  function status(w) {
    const ds = cur();
    if (!ds) return;
    const rx = w.cx >> 5, rz = w.cz >> 5, i = cidx(w.cx, w.cz);
    const info = ds.info.get(rkey(rx, rz));
    let s = 'X ' + Math.floor(w.x) + '  Z ' + Math.floor(w.z) + '   chunk ' + w.cx + ', ' + w.cz + '   region ' + rx + ', ' + rz;
    if (info) {
      const st = info.state[i];
      s += '   · ' + STATE_NAMES[st];
      if (st) {
        const keep = ds.keep.get(rkey(rx, rz));
        s += keep && keep[i] ? ' · KEEP' : ' · will regenerate';
        if (info.inh[i]) s += ' · ' + duration(info.inh[i]) + ' spent nearby';
        const bits = chunkBits(ds, info, i);
        const names = CATS.filter((c) => bits & c.bit).map((c) => c.label);
        if (names.length) s += ' · ' + names.join(', ');
      }
    } else if (ds.regionSet.has(rkey(rx, rz))) s += '   · reading…';
    else s += '   · not generated';
    $('#statusbar').textContent = s;
  }

  async function inspect(cx, cz) {
    const card = $('#detailsCard');
    card.classList.remove('hidden');
    $('#detailsTitle').textContent = 'Chunk ' + cx + ', ' + cz;
    $('#details').innerHTML = '<span class="muted">Loading…</span>';
    selectTab('select');
    try {
      const d = await api('chunk?dim=' + S.dimKey + '&cx=' + cx + '&cz=' + cz);
      if (!d.exists) { $('#details').innerHTML = '<p class="muted">Not generated yet.</p>'; return; }
      const ds = cur();
      const keep = ds.keep.get(rkey(cx >> 5, cz >> 5));
      let h = '<dl>'
        + '<dt>Blocks</dt><dd>X ' + cx * 16 + '…' + (cx * 16 + 15) + ', Z ' + cz * 16 + '…' + (cz * 16 + 15) + '</dd>'
        + '<dt>Status</dt><dd>' + esc(d.status) + (keep && keep[cidx(cx, cz)] ? ' · <b class="t-keep">kept</b>' : ' · will regenerate') + '</dd>'
        + '<dt>Time nearby</dt><dd>' + duration(d.inhabitedSeconds) + '</dd>'
        + (d.biome ? '<dt>Biome</dt><dd>' + esc(d.biome.replace('minecraft:', '')) + '</dd>' : '')
        + '<dt>Data version</dt><dd>' + d.dataVersion + (d.blendingTag ? ' · marked old (blending)' : '') + '</dd></dl>';
      if (d.playerLikeBlocks.length) h += '<b>Player-type blocks</b><div>' + d.playerLikeBlocks.map((b) => '<span class="tag">' + esc(b.id) + ' ×' + b.count + '</span>').join('') + '</div>';
      if (d.blockEntities.length) h += '<b>Block entities</b><ul>' + d.blockEntities.map((b) => '<li>' + esc(b.id) + ' at ' + b.pos.join(' ') + (b.items ? ' · has items' : '') + (b.loot ? ' · unopened loot' : '') + '</li>').join('') + '</ul>';
      if (d.entities.length) h += '<b>Notable entities</b><ul>' + d.entities.map((e) => '<li>' + esc(e.id) + (e.name ? ' "' + esc(e.name) + '"' : '') + (e.tamed ? ' · tamed' : '') + (e.pos ? ' at ' + e.pos.join(' ') : '') + '</li>').join('') + '</ul>';
      const others = Object.entries(d.otherEntities || {});
      if (others.length) h += '<b>Other entities</b><div>' + others.map(([k, v]) => '<span class="tag">' + esc(k) + ' ×' + v + '</span>').join('') + '</div>';
      if (d.structureStarts.length) h += '<b>Structure starts here</b><div>' + d.structureStarts.map((s) => '<span class="tag">' + esc(s.replace('minecraft:', '')) + '</span>').join('') + '</div>';
      if (d.structureParts.length) h += '<b>Parts of structures</b><ul>' + d.structureParts.map((s) => '<li>' + esc(s) + '</li>').join('') + '</ul>';
      $('#details').innerHTML = h;
    } catch (e) {
      $('#details').innerHTML = '<p class="error">' + esc(e.message) + '</p>';
    }
  }

  function goTo(dimKey, cx, cz) {
    if (dimKey !== S.dimKey) selectDim(dimKey, false);
    S.userMoved = true;
    S.cam.x = cx * 16 + 8;
    S.cam.z = cz * 16 + 8;
    S.cam.s = Math.max(S.cam.s, 2);
    S.flash = { cx, cz, until: performance.now() + 2500 };
    queueInfos();
    redraw();
  }

  // ------------------------------------------------------------------ tools and keys
  function setTool(t) {
    S.tool = t;
    $$('#toolbar [data-tool]').forEach((b) => b.classList.toggle('active', b.dataset.tool === t));
    $('#map').classList.toggle('pan', t === 'pan');
    redraw();
  }

  function setBrush(n) {
    S.brush = n;
    $$('#toolbar [data-brush]').forEach((b) => b.classList.toggle('active', Number(b.dataset.brush) === n));
    redraw();
  }

  function toggleOverlay(name, value) {
    S.ov[name] = value === undefined ? !S.ov[name] : value;
    const box = { sugg: '#ovSugg', preview: '#ovPreview', heat: '#ovHeat', grid: '#ovGrid' }[name];
    $(box).checked = S.ov[name];
    markAllOverlays();
  }

  function setupControls() {
    $$('#toolbar [data-tool]').forEach((b) => (b.onclick = () => setTool(b.dataset.tool)));
    $$('#toolbar [data-brush]').forEach((b) => (b.onclick = () => setBrush(Number(b.dataset.brush))));
    $('#btnUndo').onclick = () => undoRedo(S.undo, S.redo, false);
    $('#btnRedo').onclick = () => undoRedo(S.redo, S.undo, true);
    $('#ovSugg').onchange = (e) => toggleOverlay('sugg', e.target.checked);
    $('#ovPreview').onchange = (e) => toggleOverlay('preview', e.target.checked);
    $('#ovHeat').onchange = (e) => toggleOverlay('heat', e.target.checked);
    $('#ovGrid').onchange = (e) => toggleOverlay('grid', e.target.checked);
    $('#btnFit').onclick = fitView;
    $('#jumpForm').onsubmit = (e) => {
      e.preventDefault();
      const n = $('#jumpInput').value.trim().split(/[\s,;]+/).map(Number).filter((v) => !Number.isNaN(v));
      if (n.length < 2) { toast('Type two numbers: X and Z (block coordinates)', true); return; }
      const x = n[0], z = n.length >= 3 ? n[2] : n[1];
      goTo(S.dimKey, Math.floor(x / 16), Math.floor(z / 16));
    };
    $('#catVisited').onchange = (e) => { S.visited = e.target.checked; for (const d of S.dims.values()) computeSugg(d); };
    $('#visitedMinutes').onchange = (e) => { S.visitedMin = Number(e.target.value); for (const d of S.dims.values()) computeSugg(d); };
    $('#ringSize').onchange = (e) => { S.ring = Number(e.target.value); for (const d of S.dims.values()) computeSugg(d); };
    $('#btnAcceptSugg').onclick = () => {
      const ds = cur();
      const op = beginOp();
      for (const [rk, a] of ds.sugg) {
        const [rx, rz] = rk.split(',').map(Number);
        for (let i = 0; i < 1024; i++) if (a[i]) setKeep(ds, rx * 32 + (i & 31), rz * 32 + (i >> 5), 1, op);
      }
      commitOp(op);
      toast('Added ' + fmt(op.changes.length) + ' chunks to keep');
    };
    $('#btnKeepAll').onclick = () => {
      const ds = cur();
      if (!confirm('Keep every chunk of ' + ds.label + '? Nothing in it would be regenerated.')) return;
      const op = beginOp();
      for (const [rk, info] of ds.info) {
        if (!info) continue;
        for (let i = 0; i < 1024; i++) if (info.state[i]) setKeep(ds, info.rx * 32 + (i & 31), info.rz * 32 + (i >> 5), 1, op);
      }
      commitOp(op);
    };
    $('#btnClear').onclick = () => {
      const ds = cur();
      if (!confirm('Clear the whole selection of ' + ds.label + '? (You can undo this.)')) return;
      const op = beginOp();
      for (const [rk, a] of ds.keep) {
        const [rx, rz] = rk.split(',').map(Number);
        for (let i = 0; i < 1024; i++) if (a[i]) setKeep(ds, rx * 32 + (i & 31), rz * 32 + (i >> 5), 0, op);
      }
      commitOp(op);
    };
    $('#btnExport').onclick = async () => {
      const ds = cur();
      await flushSaves();
      const blob = new Blob([selectionText(ds)], { type: 'text/csv' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = (S.session.name + '-' + ds.key + '.csv').replace(/[^\w.-]+/g, '_');
      a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    };
    $('#btnImport').onclick = () => $('#importFile').click();
    $('#importFile').onchange = async (e) => {
      const f = e.target.files[0];
      e.target.value = '';
      if (!f) return;
      const ds = cur();
      try {
        await flushSaves();
        const before = new Map([...ds.keep].map(([k, a]) => [k, a.slice()]));
        await api('selection?dim=' + ds.key, { method: 'PUT', body: await f.text() });
        const text = await api('selection?dim=' + ds.key);
        const op = beginOp();
        const fresh = { keep: new Map() };
        parseSelection(fresh, text);
        for (const [rk, a] of before) for (let i = 0; i < 1024; i++) if (a[i] && !(fresh.keep.get(rk) || [])[i]) op.changes.push([rk, i, 1, 0]);
        for (const [rk, a] of fresh.keep) for (let i = 0; i < 1024; i++) if (a[i] && !(before.get(rk) || [])[i]) op.changes.push([rk, i, 0, 1]);
        ds.keep = fresh.keep;
        for (const k of ds.regionSet) markOverlay(ds, k);
        commitOp(op);
        toast('Imported ' + f.name);
      } catch (err) {
        toast('Import failed: ' + err.message, true);
      }
    };
    $('#btnCloseDetails').onclick = () => $('#detailsCard').classList.add('hidden');
    $$('.tabs button').forEach((b) => (b.onclick = () => selectTab(b.dataset.tab)));
    $('#btnDryRun').onclick = dryRun;
    $('#btnApply').onclick = apply;
    $('#ackWarnings').onchange = updateApplyGate;
    $('#btnHelp').onclick = () => $('#helpDialog').showModal();
    $('#btnChangeWorld').onclick = async () => { await flushSaves(); S.session = null; showStart(); };
    $('#worldFilter').oninput = renderWorlds;
    $('#btnOpenPath').onclick = () => { const p = $('#worldPath').value.trim(); if (p) openWorld(p); };
    $('#worldPath').onkeydown = (e) => { if (e.key === 'Enter') $('#btnOpenPath').click(); };

    window.addEventListener('keydown', (e) => {
      if (!S.session || $('#screenMain').classList.contains('hidden')) return;
      const tag = (e.target.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'select' || tag === 'textarea' || $('#helpDialog').open) return;
      const k = e.key.toLowerCase();
      if ((e.ctrlKey || e.metaKey) && k === 'z') { e.preventDefault(); if (e.shiftKey) undoRedo(S.redo, S.undo, true); else undoRedo(S.undo, S.redo, false); return; }
      if ((e.ctrlKey || e.metaKey) && k === 'y') { e.preventDefault(); undoRedo(S.redo, S.undo, true); return; }
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      const tools = { h: 'pan', b: 'keep', e: 'erase', k: 'boxkeep', x: 'boxerase', i: 'inspect' };
      if (tools[k]) { setTool(tools[k]); return; }
      if (k === ' ') { S.space = true; $('#map').classList.add('pan'); e.preventDefault(); return; }
      if (k === '[' || k === ']') { const sizes = [1, 3, 5, 9]; const i = sizes.indexOf(S.brush); setBrush(sizes[Math.max(0, Math.min(3, i + (k === ']' ? 1 : -1)))]); return; }
      if (k === 'f') fitView();
      if (k === 'g') toggleOverlay('grid');
      if (k === 's') toggleOverlay('sugg');
      if (k === 'p') toggleOverlay('preview');
      if (k === 't') toggleOverlay('heat');
    });
    window.addEventListener('keyup', (e) => { if (e.key === ' ') { S.space = false; $('#map').classList.toggle('pan', S.tool === 'pan'); } });
    window.addEventListener('beforeunload', () => { for (const ds of S.dims.values()) if (ds.saveTimer) saveNow(ds); });
  }

  // ------------------------------------------------------------------ review
  function selectTab(name) {
    $$('.tabs button').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
    for (const t of ['select', 'review', 'apply']) $('#tab-' + t).classList.toggle('hidden', t !== name);
    if (name === 'review') renderDimToggles();
    if (name === 'apply') prepareApply();
  }

  function renderDimToggles() {
    const el = $('#dimToggles');
    el.innerHTML = '';
    for (const ds of S.dims.values()) {
      if (!S.dimOn.has(ds.key)) S.dimOn.set(ds.key, ds.key === S.dimKey && ds.counts.kept > 0);
      const on = S.dimOn.get(ds.key);
      const lab = document.createElement('label');
      lab.className = 'dim-toggle';
      const nothing = on && ds.counts.kept === 0;
      lab.innerHTML = '<span class="switch"><input type="checkbox"' + (on ? ' checked' : '') + '><i></i></span><b>' + esc(ds.label) + '</b>'
        + '<span class="sub' + (nothing ? ' bad' : '') + '">' + (on
          ? (nothing ? 'Nothing kept: every chunk here would be regenerated!' : 'keep ' + fmt(ds.counts.kept) + ' · regenerate ' + fmt(ds.counts.existing - ds.counts.kept))
          : 'copied unchanged (' + fmt(ds.counts.existing) + ' chunks)') + '</span>';
      lab.querySelector('input').onchange = (e) => { S.dimOn.set(ds.key, e.target.checked); S.dryStale = true; renderDimToggles(); updateApplyGate(); };
      el.appendChild(lab);
    }
  }

  function dimsRequest() {
    const o = {};
    for (const [k, v] of S.dimOn) o[k] = !!v;
    return o;
  }

  async function dryRun() {
    const btn = $('#btnDryRun');
    if (![...S.dimOn.values()].some(Boolean)) { toast('Switch on at least one dimension to regenerate', true); return; }
    btn.disabled = true;
    btn.textContent = 'Checking…';
    try {
      await flushSaves();
      const r = await post('dryrun', { dims: dimsRequest() });
      S.dry = r;
      S.dryStale = false;
      renderDry(r);
      updateApplyGate();
    } catch (e) {
      toast('Dry run failed: ' + e.message, true);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Run dry run';
    }
  }

  function renderDry(r) {
    $('#dryResult').classList.remove('hidden');
    let h = '<table class="sumtable"><tr><th>Dimension</th><th>Keep</th><th>Regenerate</th><th>of which unfinished</th></tr>';
    for (const d of r.dims) {
      if (!d.enabled) { h += '<tr><td>' + esc(d.label) + '</td><td colspan="3" class="muted">copied unchanged</td></tr>'; continue; }
      h += '<tr><td>' + esc(d.label) + '</td><td>' + fmt(d.keep) + '</td><td>' + fmt(d.regenerate) + '</td><td>' + fmt(d.regenerateProto) + '</td></tr>';
    }
    h += '</table>';
    for (const d of r.dims.filter((x) => x.enabled && x.keep > 0)) {
      h += '<p class="small muted">' + esc(d.label) + ': kept chunks were saved with data version ' + d.keptMinDataVersion
        + (d.keptMaxDataVersion !== d.keptMinDataVersion ? '–' + d.keptMaxDataVersion : '') + '. A newer Minecraft version that changed world'
        + ' generation marks them as old when it loads them, so the old biome continues about 5 chunks into the regenerated land.'
        + (d.keptWithBlendingTag > 0 ? ' ' + fmt(d.keptWithBlendingTag) + ' of them already carry that mark ("Remove old-chunk marks" on the Apply tab clears it).' : '')
        + '</p>';
    }
    $('#drySummary').innerHTML = h;
    $('#dryCounts').textContent = r.dangerCount + ' at risk · ' + r.warnCount + ' warnings · ' + r.infoCount + ' notes';
    const el = $('#dryWarnings');
    el.innerHTML = '';
    if (!r.dangerCount && !r.warnCount) el.insertAdjacentHTML('beforeend', '<div class="ok-box">Nothing at risk found. Everything that looks like your work is kept.</div>');
    const label = { danger: 'at risk', warn: 'check', info: 'note' };
    for (const w of r.warnings.slice(0, 300)) {
      const ds = S.dims.get(w.dim);
      const div = document.createElement('div');
      div.className = 'warning ' + w.level;
      div.innerHTML = '<span class="lvl">' + label[w.level] + '</span><span>' + esc(w.text) + '</span>'
        + '<span class="where">' + esc(ds ? ds.label : w.dim) + ' · chunk ' + w.cx + ', ' + w.cz + ' · X ' + (w.cx * 16 + 8) + ' Z ' + (w.cz * 16 + 8)
        + ' <button data-a="show">Show</button>' + (w.level !== 'info' ? '<button data-a="keep">Keep it</button>' : '') + '</span>';
      div.querySelector('[data-a=show]').onclick = () => goTo(w.dim, w.cx, w.cz);
      const kb = div.querySelector('[data-a=keep]');
      if (kb) kb.onclick = () => {
        const d = S.dims.get(w.dim);
        const op = { dim: w.dim, changes: [] };
        const h2 = S.ring;
        for (let dx = -h2; dx <= h2; dx++) for (let dz = -h2; dz <= h2; dz++) setKeep(d, w.cx + dx, w.cz + dz, 1, op);
        commitOp(op);
        kb.textContent = 'Kept';
        kb.disabled = true;
      };
      el.appendChild(div);
    }
    if (r.warnings.length > 300) el.insertAdjacentHTML('beforeend', '<p class="small muted">…and ' + fmt(r.warnings.length - 300) + ' more.</p>');
  }

  // ------------------------------------------------------------------ apply
  async function prepareApply() {
    if (!$('#outPath').value) {
      try {
        const d = await api('defaults');
        $('#outPath').value = d.output;
        $('#outName').value = d.name;
      } catch (e) { /* the fields stay editable */ }
    }
    updateApplyGate();
  }

  function updateApplyGate() {
    const btn = $('#btnApply');
    const anyOn = [...S.dimOn.values()].some(Boolean);
    const needAck = S.dry && !S.dryStale && S.dry.dangerCount > 0;
    $('#applyGate').classList.toggle('hidden', !needAck);
    $('#applyLocked').classList.toggle('hidden', !S.locked);
    let reason = '';
    if (!anyOn) reason = 'Choose what to regenerate in Review first';
    else if (!S.dry || S.dryStale) reason = 'Run the dry run in Review first';
    else if (needAck && !$('#ackWarnings').checked) reason = 'Confirm that you checked the warnings';
    else if (S.locked) reason = 'Close Minecraft first';
    if (S.applying) reason = 'Working…';
    btn.disabled = !!reason;
    btn.textContent = reason || 'Create regenerated copy';
  }

  async function apply() {
    S.applying = true;
    updateApplyGate();
    $('#applyResult').classList.add('hidden');
    $('#applyProgress').classList.remove('hidden');
    try {
      await flushSaves();
      await post('apply', { dims: dimsRequest(), output: $('#outPath').value, newName: $('#outName').value, stripBlending: $('#stripBlending').checked });
      for (;;) {
        await new Promise((res) => setTimeout(res, 400));
        const p = await api('apply');
        $('#applyPhase').textContent = p.phase + (p.total ? ' · ' + fmt(p.done) + ' / ' + fmt(p.total) + ' region files' : '');
        $('#applyBar').style.width = (p.total ? Math.round(100 * p.done / p.total) : 5) + '%';
        if (p.error) throw new Error(p.error);
        if (p.result) { showResult(p.result); break; }
      }
    } catch (e) {
      $('#applyResult').classList.remove('hidden');
      $('#applyResult').innerHTML = '<p class="error">' + esc(e.message) + '</p>';
    } finally {
      S.applying = false;
      $('#applyProgress').classList.add('hidden');
      updateApplyGate();
    }
  }

  function showResult(r) {
    const el = $('#applyResult');
    el.classList.remove('hidden');
    let h = r.verified ? '<div class="ok-box"><b>Done and verified.</b> Every kept chunk was copied exactly; nothing else was left behind.</div>'
      : '<p class="error"><b>Written, but verification found problems:</b></p><ul class="small">' + r.problems.slice(0, 20).map((p) => '<li>' + esc(p) + '</li>').join('') + '</ul>';
    h += '<table class="sumtable"><tr><th>Dimension</th><th>Kept</th><th>Removed</th></tr>';
    for (const d of r.dims) h += '<tr><td>' + esc((S.dims.get(d.key) || { label: d.key }).label) + '</td><td>' + fmt(d.keptChunks) + '</td><td>' + fmt(d.removedChunks) + '</td></tr>';
    h += '</table><p class="small">New world: <code>' + esc(r.output) + '</code></p>'
      + '<p class="small muted">Open it in Minecraft (it is next to your original world). Removed chunks are generated again as you explore.</p>'
      + '<div class="btn-row"><button id="btnReveal" class="secondary">Open folder</button></div>';
    el.innerHTML = h;
    $('#btnReveal').onclick = () => post('reveal', { path: r.output }).catch((e) => toast(e.message, true));
  }

  // ------------------------------------------------------------------ boot
  async function boot() {
    setupMap();
    setupControls();
    setTool('keep');
    try {
      const s = await api('session');
      if (s.open) enterMain(s); else showStart();
    } catch (e) {
      showStart();
    }
  }
  boot();
})();
