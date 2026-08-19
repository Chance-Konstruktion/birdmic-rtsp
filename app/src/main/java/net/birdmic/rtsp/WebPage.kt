package net.birdmic.rtsp

/**
 * Die einzige Seite des Webservers, als Konstante einkompiliert.
 *
 * Alles steckt in dieser einen Datei: kein Nachladen von Schriften, Bibliotheken
 * oder Symbolen. Das ist nicht nur schlank (unter 6 kB), sondern die
 * Voraussetzung dafuer, dass die Seite in einem Netz ohne Internetzugang
 * vollstaendig funktioniert -- und genau dort steht das Telefon.
 */
object WebPage {

    val HTML: String = """
<!DOCTYPE html>
<html lang="de">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BirdMic</title>
<style>
:root{color-scheme:dark}
body{margin:0;padding:12px;background:#000;color:#e8e8e8;
     font:14px/1.45 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
h1{font-size:17px;margin:0 0 2px}
h2{font-size:13px;margin:18px 0 6px;color:#8ab4f8;text-transform:uppercase;letter-spacing:.06em}
a{color:#8ab4f8}
.card{background:#0d0d0d;border:1px solid #222;border-radius:8px;padding:10px 12px;margin-bottom:10px}
.big{font-size:16px;font-weight:600}
.dim{color:#909090}
.warn{color:#ffb74d}
.err{color:#ef5350}
code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;word-break:break-all}
table{border-collapse:collapse;width:100%}
td{padding:3px 0;vertical-align:top}
td:first-child{color:#909090;width:42%;padding-right:8px}
#bar{height:10px;background:#1a1a1a;border-radius:5px;overflow:hidden;margin:8px 0 4px}
#bar div{height:100%;width:0;background:linear-gradient(90deg,#4caf50,#cddc39,#ff7043);transition:width .1s}
canvas{width:100%;height:180px;display:block;background:#000;border-radius:6px;image-rendering:pixelated}
ul{margin:0;padding-left:18px}
#log{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:12px;white-space:pre-wrap;margin:0}
footer{color:#666;font-size:12px;margin-top:18px}
</style>
</head>
<body>

<h1>BirdMic</h1>
<div class="dim" id="device">–</div>

<div class="card" style="margin-top:10px">
  <div class="big" id="status">Verbinde …</div>
  <div style="margin-top:4px"><code id="url"></code></div>
  <div id="bar"><div id="barfill"></div></div>
  <div class="dim" id="levels">–</div>
</div>

<h2>Spektrogramm</h2>
<canvas id="spec" width="480" height="180"></canvas>

<h2>Zustand</h2>
<div class="card"><table id="facts"></table></div>

<h2>Empfänger</h2>
<div class="card" id="clients">–</div>

<h2>Logbuch</h2>
<div class="card"><p id="log">–</p></div>
<p><a href="/log.txt" download>Logbuch als Textdatei laden</a></p>

<footer>
  Nur-Lese-Ansicht. Einstellungen ändert man in der App.<br>
  Seite aktualisiert sich selbst; im Hintergrund pausiert sie, um Akku zu sparen.
</footer>

<script>
var spec = document.getElementById('spec');
var ctx = spec.getContext('2d');
ctx.fillStyle = '#000';
ctx.fillRect(0, 0, spec.width, spec.height);

// Farbverlauf wie in der App: dunkel -> blau -> gruen -> gelb -> weiss.
function colour(v) {
  v = Math.max(0, Math.min(1, v));
  if (v < 0.25) return 'rgb(0,0,' + Math.round(40 + v * 4 * 160) + ')';
  if (v < 0.5)  return 'rgb(0,' + Math.round((v - 0.25) * 4 * 200) + ',200)';
  if (v < 0.75) return 'rgb(' + Math.round((v - 0.5) * 4 * 255) + ',200,' + Math.round(200 - (v - 0.5) * 4 * 200) + ')';
  return 'rgb(255,' + Math.round(200 + (v - 0.75) * 4 * 55) + ',' + Math.round((v - 0.75) * 4 * 255) + ')';
}

function drawColumn(bands) {
  // Bild um eine Spalte nach links schieben, rechts die neue zeichnen.
  ctx.drawImage(spec, -1, 0);
  var h = spec.height, n = bands.length, x = spec.width - 1;
  ctx.fillStyle = '#000';
  ctx.fillRect(x, 0, 1, h);
  for (var i = 0; i < n; i++) {
    var y0 = Math.floor(h - (i + 1) * h / n);
    var y1 = Math.floor(h - i * h / n);
    ctx.fillStyle = colour(bands[i]);
    ctx.fillRect(x, y0, 1, Math.max(1, y1 - y0));
  }
}

function get(path, done) {
  var r = new XMLHttpRequest();
  r.open('GET', path + '?t=' + Date.now(), true);
  r.timeout = 4000;
  r.onload = function () {
    if (r.status === 200) { try { done(JSON.parse(r.responseText)); } catch (e) {} }
  };
  r.send();
}

var rows = [
  ['source', 'Aufnahmequelle'], ['mic', 'Aktives Mikrofon'], ['format', 'Format'],
  ['uptime', 'Laufzeit'], ['bytes', 'Gesendet'], ['restarts', 'Neustarts der Aufnahme'],
  ['clipped', 'Übersteuerte Samples'], ['battery', 'Akku'], ['mdns', 'mDNS-Name']
];

function tick() {
  get('/api/status', function (s) {
    document.getElementById('device').textContent = s.device + ' · Android ' + s.android + ' · BirdMic ' + s.app;
    var st = document.getElementById('status');
    st.textContent = s.status;
    st.className = 'big' + (s.error ? ' err' : (s.hot || s.restarts > 0 ? ' warn' : ''));
    document.getElementById('url').textContent = s.url;
    document.getElementById('levels').textContent =
      'Pegel ' + Math.round(s.level * 100) + ' %  ·  Spitze ' + Math.round(s.peak * 100) + ' %';
    document.getElementById('barfill').style.width = Math.round(Math.sqrt(s.level) * 100) + '%';

    var html = '';
    for (var i = 0; i < rows.length; i++) {
      var v = s[rows[i][0]];
      if (v === null || v === undefined || v === '') continue;
      html += '<tr><td>' + rows[i][1] + '</td><td>' + v + '</td></tr>';
    }
    document.getElementById('facts').innerHTML = html;

    var c = document.getElementById('clients');
    if (!s.clientList || !s.clientList.length) {
      c.innerHTML = '<span class="dim">Niemand verbunden</span>';
    } else {
      var li = '';
      for (var j = 0; j < s.clientList.length; j++) {
        li += '<li><code>' + s.clientList[j] + '</code></li>';
      }
      c.innerHTML = '<ul>' + li + '</ul>';
    }
  });
}

function tickLog() {
  get('/api/log', function (d) {
    var lines = [];
    for (var i = 0; i < d.entries.length; i++) {
      var e = d.entries[i];
      lines.push(e.t + '  ' + e.i + ' ' + e.m);
    }
    document.getElementById('log').textContent = lines.length ? lines.join('\n') : 'Noch keine Einträge.';
  });
}

function tickSpectrum() {
  if (document.hidden) return;
  get('/api/spectrum', function (d) { if (d.bands) drawColumn(d.bands); });
}

tick(); tickLog(); tickSpectrum();
setInterval(tick, 1000);
setInterval(tickLog, 5000);
setInterval(tickSpectrum, 250);
</script>
</body>
</html>
""".trimIndent()
}
