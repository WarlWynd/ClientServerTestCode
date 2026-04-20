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
        String res = "/textures/" + name.toLowerCase().replace(" ", "_") + ".png";
        var url = ProceduralTextures.class.getResource(res);
        if (url != null) return new Image(url.toExternalForm());
        return switch (name) {
            case "Stone"       -> stone();
            case "Brick"       -> brick();
            case "Wood"        -> wood();
            case "Ice"         -> ice();
            case "Cave"        -> cave();
            case "Marble"      -> marble();
            case "Wood Planks" -> woodPlanks();
            case "Dirt"        -> dirt();
            case "Grass"       -> grass();
            default            -> stone();
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
