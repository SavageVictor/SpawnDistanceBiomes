package com.savagelich.spawndistancebiomes.scan;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Generates a self-contained viewer.html (biome map + hover tooltip) from the
 * scan.csv / heights.csv produced by {@link WorldgenScanner}. Called at the end
 * of every scan so the map always reflects the latest data (overwrites the
 * previous viewer.html).
 */
public final class ViewerGenerator {

    private static final int TARGET = 400;

    private ViewerGenerator() {}

    public static void generate(Path dir) throws IOException {
        Path scanPath = dir.resolve("scan.csv");
        Path heightPath = dir.resolve("heights.csv");

        // ---- pass 1: bounds, step, biome names ----
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        TreeSet<Integer> xs = new TreeSet<>();
        List<String> biomeNames = new ArrayList<>();
        Map<String, Integer> biomeIndex = new LinkedHashMap<>();

        // scan.csv columns: x,z,temp,hum,cont,erosion,depth,weird,biome
        // We re-read the file for the downsampling pass; first we only need
        // bounds + the unique biome list, so do a light pass.
        try (BufferedReader br = Files.newBufferedReader(scanPath, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                int idx = line.lastIndexOf(',');
                if (idx < 0) continue;
                String biome = line.substring(idx + 1);
                if (biomeIndex.putIfAbsent(biome, biomeIndex.size()) == null) {
                    biomeNames.add(biome);
                }
                // parse x,z
                int c1 = line.indexOf(',');
                int c2 = line.indexOf(',', c1 + 1);
                if (c1 < 0 || c2 < 0) continue;
                int x = Integer.parseInt(line.substring(0, c1));
                int z = Integer.parseInt(line.substring(c1 + 1, c2));
                xs.add(x);
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (z < minZ) minZ = z;
                if (z > maxZ) maxZ = z;
            }
        }

        int step = xs.higher(xs.first()) - xs.first();
        int fullW = (maxX - minX) / step + 1;
        int fullH = (maxZ - minZ) / step + 1;

        int f = Math.max(1, fullW / TARGET);
        int W = fullW / f;
        int H = fullH / f;

        // ---- heights ----
        Map<String, Integer> hmap = new HashMap<>();
        try (BufferedReader br = Files.newBufferedReader(heightPath, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                String[] c = line.split(",");
                if (c.length < 3) continue;
                hmap.put(c[0] + ',' + c[1], Integer.parseInt(c[2]));
            }
        }

        // ---- downsampled arrays ----
        byte[] biomes = new byte[W * H];
        float[] vals = new float[W * H * 6];
        short[] heights = new short[W * H];

        // Second pass over scan.csv, filling only downsampled cells.
        // Map "x,z" -> packed index in the downsampled grid.
        Map<String, Integer> targetIdx = new HashMap<>();
        for (int j = 0; j < H; j++) {
            int z = minZ + j * f * step;
            for (int i = 0; i < W; i++) {
                int x = minX + i * f * step;
                targetIdx.put(x + "," + z, j * W + i);
            }
        }

        try (BufferedReader br = Files.newBufferedReader(scanPath, StandardCharsets.UTF_8)) {
            br.readLine(); // header
            String line;
            while ((line = br.readLine()) != null) {
                String[] c = line.split(",");
                if (c.length < 9) continue;
                Integer ti = targetIdx.get(c[0] + ',' + c[1]);
                if (ti == null) continue;
                int x = Integer.parseInt(c[0]);
                int z = Integer.parseInt(c[1]);
                biomes[ti] = (byte) biomeIndex.get(c[8]).intValue();
                int b = ti * 6;
                vals[b] = Float.parseFloat(c[2]);
                vals[b + 1] = Float.parseFloat(c[3]);
                vals[b + 2] = Float.parseFloat(c[4]);
                vals[b + 3] = Float.parseFloat(c[5]);
                vals[b + 4] = Float.parseFloat(c[6]);
                vals[b + 5] = Float.parseFloat(c[7]);
                heights[ti] = (short) heightAt(hmap, x, z);
            }
        }

        // ---- colors ----
        int[] palette = new int[biomeNames.size()];
        for (int i = 0; i < biomeNames.size(); i++) palette[i] = colorFor(biomeNames.get(i));

        // ---- base64 encode typed arrays (little-endian for floats/shorts) ----
        String b64Biomes = Base64.getEncoder().encodeToString(biomes);
        String b64Vals = base64Floats(vals);
        String b64Heights = base64Shorts(heights);

        // ---- write HTML ----
        String html = buildHtml(W, H, minX, minZ, step * f, biomeNames, palette, b64Biomes, b64Vals, b64Heights);
        Files.writeString(dir.resolve("viewer.html"), html, StandardCharsets.UTF_8);
    }

    private static int heightAt(Map<String, Integer> hmap, int x, int z) {
        Integer h = hmap.get(x + "," + z);
        if (h != null) return h;
        int hx = Math.round(x / 64f) * 64;
        int hz = Math.round(z / 64f) * 64;
        h = hmap.get(hx + "," + hz);
        return h != null ? h : 64;
    }

    private static String base64Floats(float[] arr) {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : arr) buf.putFloat(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static String base64Shorts(short[] arr) {
        ByteBuffer buf = ByteBuffer.allocate(arr.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : arr) buf.putShort(v);
        return Base64.getEncoder().encodeToString(buf.array());
    }

    private static int colorFor(String name) {
        String s = name.substring(name.indexOf(':') + 1);
        Integer pal = switch (s) {
            case "ocean" -> 0x000070;
            case "deep_ocean" -> 0x000030;
            case "warm_ocean" -> 0x0000ac;
            case "lukewarm_ocean" -> 0x000090;
            case "cold_ocean" -> 0x202070;
            case "frozen_ocean", "deep_frozen_ocean" -> 0x7070b0;
            case "river" -> 0x3030ff;
            case "frozen_river" -> 0xa0a0ff;
            case "beach" -> 0xede0d4;
            case "snowy_beach" -> 0xfaf0ec;
            case "stony_shore" -> 0x8a8a90;
            case "plains" -> 0x8ab86a;
            case "sunflower_plains" -> 0x9ac86a;
            case "meadow" -> 0x83bb6d;
            case "forest" -> 0x056621;
            case "flower_forest" -> 0x2d8a49;
            case "birch_forest", "old_growth_birch_forest" -> 0x5f9a5f;
            case "dark_forest" -> 0x1c4a1c;
            case "swamp" -> 0x4a5d43;
            case "mangrove_swamp" -> 0x4a3d43;
            case "desert" -> 0xe6d68a;
            case "savanna", "savanna_plateau" -> 0xbfae3f;
            case "badlands", "eroded_badlands", "wooded_badlands" -> 0xd08a5a;
            case "taiga", "snowy_taiga", "old_growth_pine_taiga", "old_growth_spruce_taiga" -> 0x2b6652;
            case "snowy_plains" -> 0xffffff;
            case "ice_spikes" -> 0x9ad8ff;
            case "grove" -> 0x8a9b68;
            case "jagged_peaks", "frozen_peaks", "stony_peaks" -> 0x9aa0a8;
            case "snowy_slopes" -> 0xd0d8e0;
            case "windswept_hills", "windswept_forest", "windswept_gravelly_hills" -> 0x7a8a7a;
            case "jungle", "sparse_jungle", "bamboo_jungle" -> 0x14791a;
            case "mushroom_fields" -> 0xc05a8a;
            case "dripstone_caves" -> 0x7a6a4a;
            case "lush_caves" -> 0x5a9a4a;
            case "deep_dark" -> 0x1a1a2a;
            case "cherry_grove" -> 0xe8a0b0;
            default -> null;
        };
        if (pal != null) return pal;
        int h = 7;
        for (int i = 0; i < name.length(); i++) h = h * 31 + name.charAt(i);
        int r = 60 + Math.abs(h) % 176;
        int g = 60 + Math.abs(h >> 8) % 176;
        int b = 60 + Math.abs(h >> 16) % 176;
        return (r << 16) | (g << 8) | b;
    }

    private static String buildHtml(int W, int H, int minX, int minZ, int step,
                                    List<String> names, int[] palette,
                                    String b64Biomes, String b64Vals, String b64Heights) {
        StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>SDB Viewer</title><style>")
          .append("*{box-sizing:border-box;margin:0;padding:0}body{background:#111;color:#ddd;font-family:system-ui,sans-serif;overflow:hidden;height:100vh;display:flex;flex-direction:column}")
          .append("#bar{display:flex;gap:6px;padding:8px;background:#181818;flex-wrap:wrap;align-items:center}")
          .append("#bar button{background:#222;color:#ccc;border:1px solid #333;padding:6px 12px;cursor:pointer;border-radius:4px;font-size:13px}")
          .append("#bar button.on{background:#3a6;color:#fff;border-color:#3a6}")
          .append("#wrap{flex:1;position:relative;display:flex;align-items:center;justify-content:center;overflow:hidden}")
          .append("canvas{image-rendering:pixelated;max-width:100%;max-height:100%}")
          .append("#tip{position:absolute;pointer-events:none;background:rgba(0,0,0,.9);border:1px solid #444;border-radius:6px;padding:8px 10px;font-size:12px;line-height:1.55;display:none;z-index:10;max-width:300px;font-family:ui-monospace,monospace}")
          .append("#tip b{color:#fff}#tip .k{color:#9ab}</style></head><body>")
          .append("<div id=\"bar\"><button data-mode=\"biome\" class=\"on\">Biome</button>")
          .append("<button data-mode=\"cont\">Continentalness</button><button data-mode=\"temp\">Temperature</button>")
          .append("<button data-mode=\"hum\">Humidity</button><button data-mode=\"erosion\">Erosion</button>")
          .append("<button data-mode=\"depth\">Depth</button><button data-mode=\"weird\">Weirdness</button>")
          .append("<button data-mode=\"height\">Height</button></div>")
          .append("<div id=\"wrap\"><canvas id=\"map\"></canvas><div id=\"tip\"></div></div><script>");

        sb.append("const W=").append(W).append(",H=").append(H).append(";")
          .append("const dec=(b64,T)=>{const bin=atob(b64);const arr=new T(bin.length/T.BYTES_PER_ELEMENT);const dv=new DataView(arr.buffer);for(let i=0;i<bin.length;i++)dv.setUint8(i,bin.charCodeAt(i));return arr;};")
          .append("const BIOMES=dec(\"").append(b64Biomes).append("\",Uint8Array);")
          .append("const VAL=dec(\"").append(b64Vals).append("\",Float32Array);")
          .append("const HGT=dec(\"").append(b64Heights).append("\",Int16Array);")
          .append("const NAMES=").append(json(names)).append(";")
          .append("const PCOL=").append(jsonInts(palette)).append(";")
          .append("const MINX=").append(minX).append(",MINZ=").append(minZ).append(",STEP=").append(step).append(";");

        sb.append("const canvas=document.getElementById('map'),ctx=canvas.getContext('2d'),tip=document.getElementById('tip');")
          .append("canvas.width=W;canvas.height=H;const img=ctx.createImageData(W,H);let mode='biome';")
          .append("function gray(v){const g=Math.max(0,Math.min(255,Math.round((v+1)*127)));return[g,g,g];}")
          .append("function colorAt(i){")
          .append("if(mode==='biome'){const c=PCOL[BIOMES[i]];return[c>>16&255,c>>8&255,c&255];}")
          .append("if(mode==='cont'){const v=VAL[i*6+2];return v<0?[0,40,Math.min(255,Math.round(-v*255))]:v<0.1?[192,176,96]:v<0.3?[64,160,64]:v<0.5?[48,128,48]:[128,96,64];}")
          .append("if(mode==='temp'){const v=VAL[i*6+0];return[Math.min(255,Math.round((v+1)*127)),0,Math.min(255,Math.round((1-v)*127))];}")
          .append("if(mode==='hum'){const v=VAL[i*6+1];return[0,Math.min(255,Math.round((v+1)*127)),Math.min(255,Math.round((1-v)*127))];}")
          .append("if(mode==='erosion')return gray(VAL[i*6+3]);if(mode==='depth')return gray(VAL[i*6+4]);if(mode==='weird')return gray(VAL[i*6+5]);")
          .append("if(mode==='height'){const v=HGT[i];if(v<40)return[0,0,32];if(v<62)return[0,0,128];if(v<68)return[200,192,96];if(v<85)return[64,160,64];if(v<110)return[48,128,48];if(v<150)return[128,96,64];return[208,208,208];}")
          .append("return[255,0,255];}")
          .append("function render(){for(let i=0;i<W*H;i++){const c=colorAt(i);img.data[i*4]=c[0];img.data[i*4+1]=c[1];img.data[i*4+2]=c[2];img.data[i*4+3]=255;}ctx.putImageData(img,0,0);}render();")
          .append("document.querySelectorAll('#bar button').forEach(b=>b.onclick=()=>{document.querySelectorAll('#bar button').forEach(x=>x.classList.remove('on'));b.classList.add('on');mode=b.dataset.mode;render();});")
          .append("canvas.addEventListener('mousemove',e=>{const r=canvas.getBoundingClientRect();const px=(e.clientX-r.left)/r.width*W,py=(e.clientY-r.top)/r.height*H;const i=Math.floor(py)*W+Math.floor(px);if(i<0||i>=W*H)return;const j=i*6;const sx=Math.round(MINX+(i%W)*STEP),sz=Math.round(MINZ+Math.floor(i/W)*STEP);")
          .append("tip.innerHTML='<b>'+NAMES[BIOMES[i]]+'</b><br><span class=\"k\">x,z</span> '+sx+', '+sz+'<br><span class=\"k\">temp</span> '+VAL[j].toFixed(3)+'<br><span class=\"k\">hum</span> '+VAL[j+1].toFixed(3)+'<br><span class=\"k\">cont</span> '+VAL[j+2].toFixed(3)+'<br><span class=\"k\">erosion</span> '+VAL[j+3].toFixed(3)+'<br><span class=\"k\">depth</span> '+VAL[j+4].toFixed(3)+'<br><span class=\"k\">weird</span> '+VAL[j+5].toFixed(3)+'<br><span class=\"k\">height</span> '+HGT[i];")
          .append("tip.style.display='block';let tx=e.clientX+14,ty=e.clientY+14;if(tx+280>innerWidth)tx=e.clientX-290;if(ty+220>innerHeight)ty=e.clientY-230;tip.style.left=tx+'px';tip.style.top=ty+'px';});")
          .append("canvas.addEventListener('mouseleave',()=>tip.style.display='none');")
          .append("</script></body></html>");
        return sb.toString();
    }

    private static String json(List<String> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJs(list.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    private static String jsonInts(int[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(arr[i]);
        }
        return sb.append(']').toString();
    }

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
