package com.game.admin.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

import java.util.HashMap;
import java.util.Map;

/**
 * Generates and caches tile textures as WritableImage.
 * Drop a real PNG at resources/textures/<name>.png (spaces → underscores) to override any texture.
 */
public class ProceduralTextures {

    private static final int S = 256;
    private static final Map<String, Image> CACHE = new HashMap<>();

    public static Image get(String name) {
        return CACHE.computeIfAbsent(name, ProceduralTextures::generate);
    }

    private static Image generate(String name) {
        String base = "/textures/" + name.toLowerCase().replace(" ", "_");
        for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
            var url = ProceduralTextures.class.getResource(base + ext);
            if (url != null) return new Image(url.toExternalForm());
        }
        return switch (name) {
            case "Stone"       -> stone();
            case "Brick"       -> brick();
            case "Wood"        -> wood();
            case "Ice"         -> ice();
            case "Cave"        -> cave();
            case "Marble"      -> marble();
            case "Wood Planks" -> woodPlanks();
            case "Dirt"        -> dirt();
            case "Grass"          -> grass();
            case "Ancient Tunnel" -> ancientTunnel();
            case "Castle"        -> brick();
            case "Dungeon Wall"  -> cave();
            default              -> stone();
        };
    }

    // ── Texture generators ────────────────────────────────────────────────────

    private static WritableImage stone() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float n = fbm(x * 0.04f, y * 0.04f, 12345);
                int v = clamp(108 + (int)(n * 42));
                // Subtle crack lines using a linear hash pattern
                int crack = Math.abs(((x * 3 + y * 7) % 97) - 48);
                if (crack < 1) v = clamp(v - 38);
                pw.setArgb(x, y, rgb(v, v, v));
            }
        }
        return img;
    }

    private static WritableImage brick() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        int bH = 20, bW = 52, m = 3;
        for (int y = 0; y < S; y++) {
            int row   = y / bH;
            int offX  = (row % 2 == 0) ? 0 : bW / 2;
            boolean mY = (y % bH) < m || (y % bH) >= bH - m;
            for (int x = 0; x < S; x++) {
                int lx = (x + offX) % bW;
                boolean mX = lx < m || lx >= bW - m;
                if (mX || mY) {
                    pw.setArgb(x, y, rgb(128, 118, 108));
                } else {
                    int id = (x + offX) / bW + row * 100;
                    int vr = hash(id, 1) % 22;
                    float gr = smoothNoise((x + offX) * 0.28f, y * 0.45f, 0) * 14;
                    pw.setArgb(x, y, rgb(clamp(158 + vr + (int)gr),
                                         clamp(68  + vr/2),
                                         clamp(52  + vr/3)));
                }
            }
        }
        return img;
    }

    private static WritableImage wood() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float grain = smoothNoise(y * 0.07f, x * 0.009f, 99) * 32;
                float knot  = (float)(Math.sin(y * 0.55f + smoothNoise(x * 0.09f, y * 0.09f, 77) * 3.0)) * 14;
                int v = (int)(grain + knot);
                pw.setArgb(x, y, rgb(clamp(128 + v), clamp(73 + v/2), clamp(28 + v/4)));
            }
        }
        return img;
    }

    private static WritableImage ice() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float n = fbm(x * 0.055f, y * 0.055f, 555);
                int base = clamp(192 + (int)(n * 32));
                int facet = Math.abs(((x * 5 + y * 3) % 61) - 30);
                int fv = facet < 1 ? -22 : 0;
                pw.setArgb(x, y, rgb(clamp(base + fv - 12),
                                     clamp(base + fv + 2),
                                     clamp(base + fv + 22)));
            }
        }
        return img;
    }

    private static WritableImage cave() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                // Base rock with strata banding
                float n      = fbm(x * 0.045f, y * 0.045f, 777);
                float strata = smoothNoise(x * 0.018f, y * 0.085f, 911) * 20;
                int v = clamp(44 + (int)(n * 28) + (int)strata);

                // Coal seams — dark diagonal veins
                float coal = smoothNoise(x * 0.032f + y * 0.014f, y * 0.048f, 1337);
                if (coal > 0.70f) v = clamp(v - 30);

                // Pick/chisel marks — two crossing scratch families
                int s1 = Math.abs(((x * 7 + y * 3) % 53) - 26);
                int s2 = Math.abs(((x * 3 - y * 8) % 47) - 23);
                if (s1 < 1 || s2 < 1) v = clamp(v - 18);

                // Iron ore — reddish-brown flecks
                if (hash(x * 17 + y * 83, 55) % 88 == 0) {
                    pw.setArgb(x, y, rgb(clamp(v + 58), clamp(v + 18), clamp(v - 8)));
                    continue;
                }
                // Gold ore — rare golden flecks
                if (hash(x * 31 + y * 53, 99) % 210 == 0) {
                    pw.setArgb(x, y, rgb(clamp(v + 82), clamp(v + 66), clamp(v - 12)));
                    continue;
                }
                // Quartz — occasional bright white crystal fleck
                if (hash(x * 13 + y * 97, 42) % 80 == 0) v = clamp(v + 50);

                pw.setArgb(x, y, rgb(clamp(v - 2), clamp(v - 5), clamp(v - 1)));
            }
        }
        return img;
    }

    private static WritableImage marble() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float turb = fbm(x * 0.028f, y * 0.028f, 333) * 85;
                float vein = (float) Math.sin((x * 0.065f + turb) * Math.PI);
                int base = 198;
                int v = clamp(base + (int)(vein * 26));
                int dark = (int)(Math.abs(vein) * 42);
                pw.setArgb(x, y, rgb(clamp(v - dark/3),
                                     clamp(v - dark/3),
                                     clamp(v - dark/4)));
            }
        }
        return img;
    }

    private static WritableImage woodPlanks() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        int pW = 38, seam = 2;
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int lx = x % pW;
                if (lx < seam) {
                    pw.setArgb(x, y, rgb(32, 18, 6));
                } else {
                    int id = x / pW;
                    float grain = smoothNoise(y * 0.065f, (x + id * 200) * 0.012f, 11) * 26;
                    int vr = hash(id, 7) % 16;
                    pw.setArgb(x, y, rgb(clamp(152 + vr + (int)grain),
                                         clamp(97  + vr/2 + (int)grain/2),
                                         clamp(42  + (int)grain/4)));
                }
            }
        }
        return img;
    }

    private static WritableImage dirt() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float n = fbm(x * 0.058f, y * 0.058f, 222);
                int base = clamp(102 + (int)(n * 38));
                boolean pebble = hash(x * 17 + y * 31, 13) % 48 == 0;
                int pv = pebble ? 20 : 0;
                pw.setArgb(x, y, rgb(clamp(base + 22 + pv),
                                     clamp(base + pv),
                                     clamp(base - 26 + pv)));
            }
        }
        return img;
    }

    private static WritableImage grass() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                float n     = fbm(x * 0.048f, y * 0.048f, 888);
                float blade = smoothNoise(x * 0.75f, y * 0.045f, 44) * 22;
                pw.setArgb(x, y, rgb(clamp(42  + (int)(n * 18)),
                                     clamp(112 + (int)(n * 32) + (int)blade),
                                     clamp(28  + (int)(n * 10))));
            }
        }
        return img;
    }

    private static WritableImage ancientTunnel() {
        WritableImage img = img();
        PixelWriter pw = img.getPixelWriter();
        // Stone block grid — large irregular blocks
        int[] blockW = {48, 52, 44, 56, 50};
        int[] blockH = {36, 40, 34, 38, 42};
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                // Determine block cell using staggered rows
                int rowEst = y / 38;
                int bH     = blockH[rowEst % blockH.length];
                int row    = y / bH;
                int offX   = (row % 2 == 0) ? 0 : 26;
                int bW     = blockW[(row + x / 50) % blockW.length];
                int lx     = (x + offX) % bW;
                int ly     = y % bH;
                int mortar = 2;
                boolean isMortar = lx < mortar || lx >= bW - mortar || ly < mortar || ly >= bH - mortar;

                // Base stone color — aged limestone, warm grey-tan
                int blockId = (x + offX) / bW * 7 + row * 31;
                float age   = fbm(x * 0.022f, y * 0.022f, 4321) * 38;
                int base    = clamp(88 + hash(blockId, 3) % 18 + (int) age);
                int r = clamp(base + 12), g = clamp(base + 6), b = clamp(base - 4);

                if (isMortar) {
                    // Mortar: darker, slightly cooler
                    int mv = clamp(52 + (int)(fbm(x * 0.08f, y * 0.08f, 999) * 14));
                    pw.setArgb(x, y, rgb(mv - 2, mv, mv + 3));
                    continue;
                }

                // Age staining — dark blotches from centuries of moisture
                float stain = fbm(x * 0.038f, y * 0.038f, 7777);
                if (stain > 0.68f) { r = clamp(r - 28); g = clamp(g - 22); b = clamp(b - 18); }

                // Moss patches — greenish, clusters near mortar lines
                float moss = fbm(x * 0.055f, y * 0.055f, 2222);
                boolean nearMortar = lx < 5 || lx >= bW - 5 || ly < 5 || ly >= bH - 5;
                if (moss > 0.64f && nearMortar) {
                    float m = (moss - 0.64f) * 2.8f;
                    r = clamp((int)(r * (1 - m) + 42  * m));
                    g = clamp((int)(g * (1 - m) + 88  * m));
                    b = clamp((int)(b * (1 - m) + 34  * m));
                }

                // Water stain streaks — vertical dark drips
                float drip = smoothNoise(x * 0.18f, y * 0.028f, 5555);
                if (drip > 0.72f) { r = clamp(r - 18); g = clamp(g - 14); b = clamp(b - 8); }

                // Surface cracks — irregular fracture lines across blocks
                float crack = fbm(x * 0.12f + (float)Math.sin(y * 0.07f) * 3, y * 0.09f, 3333);
                if (crack > 0.80f) { r = clamp(r - 35); g = clamp(g - 30); b = clamp(b - 25); }

                // Chisel marks — diagonal tool lines on block faces
                int chisel = Math.abs(((x * 5 - y * 2) % 41) - 20);
                if (chisel < 1 && !nearMortar) { r = clamp(r - 12); g = clamp(g - 10); b = clamp(b - 8); }

                // Rare carved glyph hint — very faint cross-hatch in a few blocks
                if (hash(blockId, 17) % 12 == 0) {
                    int gx = lx - bW / 2, gy = ly - bH / 2;
                    if (Math.abs(gx) < 8 && Math.abs(gy) < 8 && (Math.abs(gx) < 1 || Math.abs(gy) < 1)) {
                        r = clamp(r - 20); g = clamp(g - 16); b = clamp(b - 12);
                    }
                }

                pw.setArgb(x, y, rgb(r, g, b));
            }
        }
        return img;
    }

    // ── Noise ─────────────────────────────────────────────────────────────────

    private static float smoothNoise(float x, float y, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float a = lattice(ix,   iy,   seed),  b = lattice(ix+1, iy,   seed);
        float c = lattice(ix,   iy+1, seed),  d = lattice(ix+1, iy+1, seed);
        float s = fx*fx*(3-2*fx), t = fy*fy*(3-2*fy);
        float lo = a + s*(b-a), hi = c + s*(d-c);
        return lo + t*(hi-lo);
    }

    private static float fbm(float x, float y, int seed) {
        float v = 0, amp = 1f, freq = 1f, sum = 0;
        for (int i = 0; i < 4; i++) {
            v   += smoothNoise(x*freq, y*freq, seed + i*97) * amp;
            sum += amp;  freq *= 2;  amp *= 0.5f;
        }
        return v / sum;
    }

    private static float lattice(int ix, int iy, int seed) {
        int n = ix * 1619 + iy * 31337 + seed * 1013;
        n = (n ^ (n >> 8)) * 1540483477;
        return (n & 0xFFFF) / 65535.0f;
    }

    private static int hash(int v, int seed) {
        v ^= seed;
        v = ((v >> 16) ^ v) * 0x45d9f3b;
        return Math.abs(v);
    }

    private static int clamp(int v) { return Math.max(0, Math.min(255, v)); }
    private static int rgb(int r, int g, int b) { return 0xFF000000 | (r<<16) | (g<<8) | b; }
    private static WritableImage img() { return new WritableImage(S, S); }
}
