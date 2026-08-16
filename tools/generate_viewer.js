#!/usr/bin/env node
// generate_viewer.js <sdb_scan_dir>
// Reads scan.csv + heights.csv from the given directory and writes a
// self-contained viewer.html (biome map + hover tooltip with all values).
// Usage:  bun generate_viewer.js "C:/path/to/sdb_scan"
const fs = require('fs');
const path = require('path');

const dir = (process.argv[2] || 'run/server/sdb_scan').replace(/[\\/]$/, '') + '/';

// ---------- parse scan.csv ----------
const scanRaw = fs.readFileSync(dir + 'scan.csv', 'utf8');
const lines = scanRaw.split('\n');
const cells = new Map();
let minX = Infinity, maxX = -Infinity, minZ = Infinity, maxZ = -Infinity;
const xs = new Set();
for (let i = 1; i < lines.length; i++) {
  const c = lines[i].split(',');
  if (c.length < 9) continue;
  const x = +c[0], z = +c[1];
  cells.set(x + ',' + z, { x, z, temp: +c[2], hum: +c[3], cont: +c[4], erosion: +c[5], depth: +c[6], weird: +c[7], biome: c[8] });
  xs.add(x);
  if (x < minX) minX = x; if (x > maxX) maxX = x;
  if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
}
const sortedX = [...xs].sort((a, b) => a - b);
const step = sortedX[1] - sortedX[0];
const fullW = Math.round((maxX - minX) / step) + 1;
const fullH = Math.round((maxZ - minZ) / step) + 1;
console.log('scan:', fullW + 'x' + fullH, 'step', step, 'cells', cells.size);

// ---------- parse heights.csv ----------
const hmap = new Map();
const hRaw = fs.readFileSync(dir + 'heights.csv', 'utf8');
for (const l of hRaw.split('\n').slice(1)) {
  const c = l.split(',');
  if (c.length < 3) continue;
  hmap.set(c[0] + ',' + c[1], +c[2]);
}
console.log('height samples:', hmap.size);

// ---------- palette ----------
const biomeNames = [...new Set([...cells.values()].map(c => c.biome))].sort();
const colorFor = (name) => {
  const s = name.split(':')[1] || name;
  const pal = {
    ocean: 0x000070, deep_ocean: 0x000030, warm_ocean: 0x0000ac, lukewarm_ocean: 0x000090,
    cold_ocean: 0x202070, frozen_ocean: 0x7070b0, deep_frozen_ocean: 0x7070b0,
    river: 0x3030ff, frozen_river: 0xa0a0ff, beach: 0xede0d4, snowy_beach: 0xfaf0ec, stony_shore: 0x8a8a90,
    plains: 0x8ab86a, sunflower_plains: 0x9ac86a, meadow: 0x83bb6d,
    forest: 0x056621, flower_forest: 0x2d8a49, birch_forest: 0x5f9a5f, old_growth_birch_forest: 0x5f9a5f, dark_forest: 0x1c4a1c,
    swamp: 0x4a5d43, mangrove_swamp: 0x4a3d43, desert: 0xe6d68a,
    savanna: 0xbfae3f, savanna_plateau: 0xbfae3f, badlands: 0xd08a5a, eroded_badlands: 0xd08a5a, wooded_badlands: 0xd08a5a,
    taiga: 0x2b6652, snowy_taiga: 0x2b6652, old_growth_pine_taiga: 0x2b6652, old_growth_spruce_taiga: 0x2b6652,
    snowy_plains: 0xffffff, ice_spikes: 0x9ad8ff, grove: 0x8a9b68,
    jagged_peaks: 0x9aa0a8, frozen_peaks: 0x9aa0a8, stony_peaks: 0x9aa0a8, snowy_slopes: 0xd0d8e0,
    windswept_hills: 0x7a8a7a, windswept_forest: 0x7a8a7a, windswept_gravelly_hills: 0x7a8a7a,
    jungle: 0x14791a, sparse_jungle: 0x14791a, bamboo_jungle: 0x14791a, mushroom_fields: 0xc05a8a,
    dripstone_caves: 0x7a6a4a, lush_caves: 0x5a9a4a, deep_dark: 0x1a1a2a, cherry_grove: 0xe8a0b0
  };
  if (pal[s]) return pal[s];
  let h = 7;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) | 0;
  return (60 + Math.abs(h) % 176) << 16 | (60 + Math.abs(h >> 8) % 176) << 8 | (60 + Math.abs(h >> 16) % 176);
};
const palette = biomeNames.map(n => colorFor(n));
const idxByName = new Map(biomeNames.map((n, i) => [n, i]));

// ---------- downsample ----------
const TARGET = 400;
const f = Math.max(1, Math.floor(fullW / TARGET));
const W = Math.floor(fullW / f), H = Math.floor(fullH / f);
console.log('grid:', W + 'x' + H, 'factor', f);

const hAt = (x, z) => {
  if (hmap.has(x + ',' + z)) return hmap.get(x + ',' + z);
  const hx = Math.round(x / 64) * 64, hz = Math.round(z / 64) * 64;
  return hmap.get(hx + ',' + hz) ?? hmap.get('0,0') ?? 64;
};

const biomes = new Uint8Array(W * H);
const vals = new Float32Array(W * H * 6);
const heights = new Int16Array(W * H);
let idx = 0;
for (let j = 0; j < H; j++) {
  const z = minZ + j * f * step;
  for (let i = 0; i < W; i++) {
    const x = minX + i * f * step;
    const c = cells.get(x + ',' + z);
    if (c) {
      biomes[idx] = idxByName.get(c.biome);
      vals[idx * 6] = c.temp; vals[idx * 6 + 1] = c.hum; vals[idx * 6 + 2] = c.cont;
      vals[idx * 6 + 3] = c.erosion; vals[idx * 6 + 4] = c.depth; vals[idx * 6 + 5] = c.weird;
      heights[idx] = hAt(x, z);
    }
    idx++;
  }
}

const b64 = (arr) => Buffer.from(arr.buffer, arr.byteOffset, arr.byteLength).toString('base64');

const loader =
`const W=${W},H=${H};
const dec=(b64,T)=>{const bin=atob(b64);const arr=new T(bin.length/T.BYTES_PER_ELEMENT);const dv=new DataView(arr.buffer);for(let i=0;i<bin.length;i++)dv.setUint8(i,bin.charCodeAt(i));return arr;};
const BIOMES=dec("${b64(biomes)}",Uint8Array);
const VAL=dec("${b64(vals)}",Float32Array);
const HGT=dec("${b64(heights)}",Int16Array);
const NAMES=${JSON.stringify(biomeNames)};
const PCOL=${JSON.stringify(palette)};
const MINX=${minX}, MINZ=${minZ}, STEP=${step * f};`;

const html = `<!doctype html><html><head><meta charset="utf-8"><title>SDB Viewer</title><style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#111;color:#ddd;font-family:system-ui,sans-serif;overflow:hidden;height:100vh;display:flex;flex-direction:column}
#bar{display:flex;gap:6px;padding:8px;background:#181818;flex-wrap:wrap;align-items:center}
#bar button{background:#222;color:#ccc;border:1px solid #333;padding:6px 12px;cursor:pointer;border-radius:4px;font-size:13px}
#bar button.on{background:#3a6;color:#fff;border-color:#3a6}
#wrap{flex:1;position:relative;display:flex;align-items:center;justify-content:center;overflow:hidden}
canvas{image-rendering:pixelated;max-width:100%;max-height:100%}
#tip{position:absolute;pointer-events:none;background:rgba(0,0,0,.9);border:1px solid #444;border-radius:6px;padding:8px 10px;font-size:12px;line-height:1.55;display:none;z-index:10;max-width:300px;font-family:ui-monospace,monospace}
#tip b{color:#fff}
#tip .k{color:#9ab}
</style></head><body>
<div id="bar">
<button data-mode="biome" class="on">Biome</button>
<button data-mode="cont">Continentalness</button>
<button data-mode="temp">Temperature</button>
<button data-mode="hum">Humidity</button>
<button data-mode="erosion">Erosion</button>
<button data-mode="depth">Depth</button>
<button data-mode="weird">Weirdness</button>
<button data-mode="height">Height</button>
</div>
<div id="wrap"><canvas id="map"></canvas><div id="tip"></div></div>
<script>
${loader}
const canvas=document.getElementById('map'), ctx=canvas.getContext('2d'), tip=document.getElementById('tip');
canvas.width=W; canvas.height=H;
const img=ctx.createImageData(W,H);
let mode='biome';
function gray(v){const g=Math.max(0,Math.min(255,Math.round((v+1)*127)));return[g,g,g];}
function colorAt(i){
  if(mode==='biome'){const c=PCOL[BIOMES[i]];return[c>>16&255,c>>8&255,c&255];}
  if(mode==='cont'){const v=VAL[i*6+2];return v<0?[0,40,Math.min(255,Math.round(-v*255))]:v<0.1?[192,176,96]:v<0.3?[64,160,64]:v<0.5?[48,128,48]:[128,96,64];}
  if(mode==='temp'){const v=VAL[i*6+0];return[Math.min(255,Math.round((v+1)*127)),0,Math.min(255,Math.round((1-v)*127))];}
  if(mode==='hum'){const v=VAL[i*6+1];return[0,Math.min(255,Math.round((v+1)*127)),Math.min(255,Math.round((1-v)*127))];}
  if(mode==='erosion')return gray(VAL[i*6+3]);
  if(mode==='depth')return gray(VAL[i*6+4]);
  if(mode==='weird')return gray(VAL[i*6+5]);
  if(mode==='height'){const v=HGT[i];if(v<40)return[0,0,32];if(v<62)return[0,0,128];if(v<68)return[200,192,96];if(v<85)return[64,160,64];if(v<110)return[48,128,48];if(v<150)return[128,96,64];return[208,208,208];}
  return[255,0,255];
}
function render(){for(let i=0;i<W*H;i++){const c=colorAt(i);img.data[i*4]=c[0];img.data[i*4+1]=c[1];img.data[i*4+2]=c[2];img.data[i*4+3]=255;}ctx.putImageData(img,0,0);}
render();
document.querySelectorAll('#bar button').forEach(b=>b.onclick=()=>{document.querySelectorAll('#bar button').forEach(x=>x.classList.remove('on'));b.classList.add('on');mode=b.dataset.mode;render();});
canvas.addEventListener('mousemove', e=>{
  const r=canvas.getBoundingClientRect();
  const px=(e.clientX-r.left)/r.width*W, py=(e.clientY-r.top)/r.height*H;
  const i=Math.floor(py)*W+Math.floor(px);
  if(i<0||i>=W*H)return;
  const j=i*6;
  const sx=Math.round(MINX+(i%W)*STEP), sz=Math.round(MINZ+Math.floor(i/W)*STEP);
  tip.innerHTML='<b>'+NAMES[BIOMES[i]]+'</b><br>'+
    '<span class="k">x,z</span> '+sx+', '+sz+'<br>'+
    '<span class="k">temp</span> '+VAL[j].toFixed(3)+'<br>'+
    '<span class="k">hum</span> '+VAL[j+1].toFixed(3)+'<br>'+
    '<span class="k">cont</span> '+VAL[j+2].toFixed(3)+'<br>'+
    '<span class="k">erosion</span> '+VAL[j+3].toFixed(3)+'<br>'+
    '<span class="k">depth</span> '+VAL[j+4].toFixed(3)+'<br>'+
    '<span class="k">weird</span> '+VAL[j+5].toFixed(3)+'<br>'+
    '<span class="k">height</span> '+HGT[i];
  tip.style.display='block';
  let tx=e.clientX+14, ty=e.clientY+14;
  if(tx+280>innerWidth)tx=e.clientX-290;
  if(ty+220>innerHeight)ty=e.clientY-230;
  tip.style.left=tx+'px';tip.style.top=ty+'px';
});
canvas.addEventListener('mouseleave', ()=>tip.style.display='none');
</script></body></html>`;

fs.writeFileSync(dir + 'viewer.html', html);
console.log('wrote', dir + 'viewer.html', (html.length / 1024 / 1024).toFixed(1) + ' MB');
