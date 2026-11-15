package com.iossweet.sweet16

import java.util.Base64

/**
 * "Notes" for iOS: a web app served by ShareServer at /notes. In iOS Safari:
 * Share -> Add to Home Screen gives a Notes icon that opens full screen.
 * Notes live in the page's localStorage on iOS and are also saved on Android
 * (POST /api/notes -> Store.notesFile), so they survive a new iOS disk.
 */
object NotesPage {
    val ICON: ByteArray = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAALQAAAC0CAIAAACyr5FlAAABh0lEQVR42u3asQkAIRBFQfuvxzLMTS6+NrSAUxbBwJN5/ApkAoNNrSSz4TyBwWFwGBwGh8FhcBgcBofBYXCYwWFwGBwGh8FhcBgcBofBYXCYwWFwGBwGh8FhcBgcBofBYXCYwWFwGBwGh8FhcBgcBofBYXAYHGZw2AqON5uN5wkMDoPDduJo0iQ4BIfgEByCQ3AIDsEhOASH4JDgEByCQ3AIDsEhOASH4BAcEhyCQ3AIDsEhOASH4BAc32p97MDBYXDYT3HIn0NwSHAIDsEhOASH4BAcgkNwCA4JDsEhOASH4BAcuhaHa003pHDAAQcc/hyCQ3AIDsEhOASH4BAcgkOCQ3AIDsEhOASH4BAcgkNwxLnWdEMKBxxwwOHPITgEh+AQHIJDcAgOwSE4JDgEh+AQHIJDcAgOwSE4BEeca003pHDAAQcc/hyCQ3AIDsEhOASH4BAcgkOCQ3AIDsEhOASH4BAcgkNwxLnWdEMKBxxwwOHPITgEh+AQHIJDcAgOwSE4JDg0rQNbldIcIVxbVAAAAABJRU5ErkJggg==")

    const val HTML = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-title" content="Notes">
<meta name="apple-mobile-web-app-status-bar-style" content="default">
<link rel="apple-touch-icon" href="/notes-icon.png">
<title>Notes</title>
<style>
:root{--y:#d9a000;--bg:#f2f2f7;--fg:#000;--sub:#8e8e93;--card:#fff;--sep:#d1d1d6}
@media (prefers-color-scheme: dark){:root{--bg:#000;--fg:#fff;--card:#1c1c1e;--sep:#38383a}}
body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:var(--bg);color:var(--fg);-webkit-user-select:none;-webkit-tap-highlight-color:transparent}
header{position:sticky;top:0;padding:calc(env(safe-area-inset-top) + 6px) 12px 6px 16px;background:var(--bg);display:flex;align-items:center;justify-content:space-between;z-index:2}
h1{font-size:34px;margin:4px 0}
button{font:inherit;color:var(--y);background:none;border:0;font-size:17px;padding:8px}
.list{margin:8px 16px;background:var(--card);border-radius:10px;overflow:hidden}
.item{padding:11px 16px;border-bottom:1px solid var(--sep)}
.item:last-child{border-bottom:0}
.t{font-weight:600;font-size:17px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.s{color:var(--sub);font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin-top:2px}
#editor{display:none;position:fixed;top:0;left:0;right:0;bottom:0;background:var(--card);flex-direction:column}
#editor header{background:var(--card)}
#text{flex:1;border:0;padding:4px 16px calc(env(safe-area-inset-bottom) + 16px);font:17px/1.45 -apple-system,sans-serif;background:var(--card);color:var(--fg);outline:none;-webkit-user-select:text;user-select:text;resize:none}
.empty{color:var(--sub);text-align:center;padding:28px 16px}
footer{color:var(--sub);text-align:center;font-size:13px;margin:14px 16px 40px}
</style></head><body>
<div id="home"><header><h1>Notes</h1><button id="new">New Note</button></header>
<div id="list" class="list"></div><footer id="foot"></footer></div>
<div id="editor"><header><button id="back">&#8249; Notes</button><button id="del">Delete</button></header>
<textarea id="text" placeholder="Start typing"></textarea></div>
<script>
var notes = [], cur = null, timer = null, online = false;
function el(id) { return document.getElementById(id); }
function visible() { return notes.filter(function (n) { return !n.deleted; }).sort(function (a, b) { return b.updated - a.updated; }); }
function storeLocal() { try { localStorage.setItem('sweet16-notes', JSON.stringify(notes)); } catch (e) {} }
function push() {
  clearTimeout(timer);
  timer = setTimeout(function () {
    fetch('/api/notes', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(notes) })
      .then(function () { online = true; foot(); }).catch(function () { online = false; foot(); });
  }, 600);
}
function merge(list) {
  var m = {};
  notes.concat(list || []).forEach(function (n) { if (n && n.id && (!m[n.id] || m[n.id].updated < n.updated)) m[n.id] = n; });
  notes = Object.keys(m).map(function (k) { return m[k]; });
}
function lines(n) { return (n.text || '').split('\n').map(function (s) { return s.trim(); }).filter(function (s) { return s; }); }
function when(t) { var d = new Date(t), now = new Date(); return d.toDateString() == now.toDateString() ? d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : d.toLocaleDateString(); }
function foot() {
  var c = visible().length;
  el('foot').textContent = c + (c == 1 ? ' note' : ' notes') + (online ? ' · also saved in Sweet16 on Android' : ' · saved on this iPhone');
}
function render() {
  var list = el('list'), v = visible();
  list.innerHTML = '';
  if (!v.length) { var e = document.createElement('div'); e.className = 'empty'; e.textContent = 'No notes yet. Tap New Note.'; list.appendChild(e); }
  v.forEach(function (n) {
    var l = lines(n), d = document.createElement('div');
    d.className = 'item';
    var t = document.createElement('div'); t.className = 't'; t.textContent = l[0] || 'New Note';
    var s = document.createElement('div'); s.className = 's'; s.textContent = when(n.updated) + '  ' + (l[1] || 'No additional text');
    d.appendChild(t); d.appendChild(s);
    d.onclick = function () { open(n); };
    list.appendChild(d);
  });
  foot();
}
function open(n) { cur = n; el('text').value = n.text || ''; el('home').style.display = 'none'; el('editor').style.display = 'flex'; setTimeout(function () { el('text').focus(); }, 50); }
function close() {
  if (cur && !(cur.text || '').trim()) { cur.deleted = true; cur.updated = Date.now(); storeLocal(); push(); }
  cur = null; el('editor').style.display = 'none'; el('home').style.display = 'block'; el('text').blur(); render();
}
el('new').onclick = function () { var n = { id: Date.now().toString(36) + Math.random().toString(36).slice(2, 7), text: '', updated: Date.now() }; notes.push(n); open(n); };
el('back').onclick = close;
el('del').onclick = function () { if (cur && confirm('Delete this note?')) { cur.deleted = true; cur.updated = Date.now(); cur.text = ''; storeLocal(); push(); close(); } };
el('text').oninput = function () { if (!cur) return; cur.text = el('text').value; cur.updated = Date.now(); storeLocal(); push(); };
try { notes = JSON.parse(localStorage.getItem('sweet16-notes') || '[]'); } catch (e) { notes = []; }
render();
fetch('/api/notes').then(function (r) { return r.json(); }).then(function (srv) { online = true; merge(srv); storeLocal(); render(); push(); }).catch(function () { online = false; foot(); });
</script></body></html>"""
}
