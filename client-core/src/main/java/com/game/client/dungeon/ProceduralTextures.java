package com.game.client.dungeon;

import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Generates and caches tile textures as jME3 Texture2D objects.
 * Algorithms are identical to the admin ProceduralTextures so client and admin look the same.
 */
public class ProceduralTextures {

    private static final int S = 256;
    private static final Map<String, Texture2D> CACHE = new HashMap<>();

    public static Texture2D get(String name) {
        return CACHE.computeIfAbsent(name, ProceduralTextures::generate);
    }

    private static Texture2D generate(String name) {
        String base = "/textures/" + name.toLowerCase().replace(" ", "_");
        for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
            Texture2D loaded = loadImage(base + ext);
            if (loaded != null) return loaded;
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

    private static Texture2D stone() {
        Pixels p = new Pixels();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            float n = fbm(x * 0.04f, y * 0.04f, 12345);
            int v = clamp(108 + (int)(n * 42));
            int crack = Math.abs(((x * 3 + y * 7) % 97) - 48);
            if (crack < 1) v = clamp(v - 38);
            p.set(x, y, v, v, v);
        }
        return p.toTexture();
    }

    private static Texture2D brick() {
        Pixels p = new Pixels();
        int bH = 20, bW = 52, m = 3;
        for (int y = 0; y < S; y++) {
            int row  = y / bH, offX = (row % 2 == 0) ? 0 : bW / 2;
            boolean mY = (y % bH) < m || (y % bH) >= bH - m;
            for (int x = 0; x < S; x++) {
                int lx = (x + offX) % bW;
                boolean mX = lx < m || lx >= bW - m;
                if (mX || mY) { p.set(x, y, 128, 118, 108); }
                else {
                    int id = (x + offX) / bW + row * 100;
                    int vr = hash(id, 1) % 22;
                    float gr = smoothNoise((x + offX) * 0.28f, y * 0.45f, 0) * 14;
                    p.set(x, y, clamp(158 + vr + (int)gr), clamp(68 + vr/2), clamp(52 + vr/3));
                }
            }
        }
        return p.toTexture();
    }

    private static Texture2D wood() {
        Pixels p = new Pixels();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            float grain = smoothNoise(y * 0.07f, x * 0.009f, 99) * 32;
            float knot  = (float)(Math.sin(y * 0.55f + smoothNoise(x * 0.09f, y * 0.09f, 77) * 3.0)) * 14;
            int v = (int)(grain + knot);
            p.set(x, y, clamp(128 + v), clamp(73 + v/2), clamp(28 + v/4));
        }
        return p.toTexture();
    }

    private static Texture2D ice() {
        Pixels p = new Pixels();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            float n = fbm(x * 0.055f, y * 0.055f, 555);
            int base = clamp(192 + (int)(n * 32));
            int facet = Math.abs(((x * 5 + y * 3) % 61) - 30);
            int fv = facet < 1 ? -22 : 0;
            p.set(x, y, clamp(base + fv - 12), clamp(base + fv + 2), clamp(base + fv + 22));
        }
        return p.toTexture();
    }

    private static Texture2D cave() {
        Pixels p = new Pixels();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
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
                p.set(x, y, clamp(v + 58), clamp(v + 18), clamp(v - 8));
                continue;
            }
            // Gold ore — rare golden flecks
            if (hash(x * 31 + y * 53, 99) % 210 == 0) {
                p.set(x, y, clamp(v + 82), clamp(v + 66), clamp(v - 12));
                continue;
            }
            // Quartz — occasional bright white crystal fleck
            if (hash(x * 13 + y * 97, 42) % 80 == 0) v = clamp(v + 50);

            p.set(x, y, clamp(v - 2), clamp(v - 5), clamp(v - 1));
        }
        return p.toTexture();
    }

    private static Texture2D marble() {
        Pixels p = new Pixels();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            float turb = fbm(x * 0.028f, y * 0.028f, 333) * 85;
            float vein = (float) Math.sin((x * 0.065f + turb) * Math.PI);
            int base = 198, v = clamp(base + (int)(vein * 26));
            int dark = (int)(Math.abs(vein) * 42);
            p.set(x, y, clamp(v - dark/3), clamp(v - dark/3), clamp(v - dark/4));
        }
        return p.toTexture();
    }

    private static Texture2D woodPlanks() {
        Pixels p = new Pixels();
        int pW = 38, seam = 2;
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            int lx = x % pW;
            if (lx < seam) { p.set(x, y, 32, 18, 6); }
            else {
                int id = x / pW;
                float grain = smoothNoise(y * 0.065f, (x + id * 200) * 0.012f, 11) * 26;
                int vr = hash(id, 7) % 16;
                p.set(x, y, clamp(152 + vr + (int)grain), clamp(97 + vr/2 + (int)grain/2), clamp(42 + (int)grain/4));
            }
        }
        return p.toTexture();
    }

    private static Texture2D dirt() {
        Pixels p = new Pixels();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            float n = fbm(x * 0.058f, y * 0.058f, 222);
            int base = clamp(102 + (int)(n * 38));
            boolean pebble = hash(x * 17 + y * 31, 13) % 48 == 0;
            int pv = pebble ? 20 : 0;
            p.set(x, y, clamp(base + 22 + pv), clamp(base + pv), clamp(base - 26 + pv));
        }
        return p.toTexture();
    }

    private static Texture2D grass() {
        Pixels p = new Pixels();
        for (int y = 0; y < S; y++) for (int x = 0; x < S; x++) {
            float n     = fbm(x * 0.048f, y * 0.048f, 888);
            float blade = smoothNoise(x * 0.75f, y * 0.045f, 44) * 22;
            p.set(x, y, clamp(42 + (int)(n * 18)), clamp(112 + (int)(n * 32) + (int)blade), clamp(28 + (int)(n * 10)));
        }
        return p.toTexture();
    }

    private static Texture2D ancientTunnel() {
        Pixels p = new Pixels();
        int[] blockW = {48, 52, 44, 56, 50};
        int[] blockH = {36, 40, 34, 38, 42};
        for (int y = 0; y < S; y++) {
            for (int x = 0; x < S; x++) {
                int rowEst = y / 38;
                int bH     = blockH[rowEst % blockH.length];
                int row    = y / bH;
                int offX   = (row % 2 == 0) ? 0 : 26;
                int bW     = blockW[(row + x / 50) % blockW.length];
                int lx     = (x + offX) % bW;
                int ly     = y % bH;
                int mortar = 2;
                boolean isMortar   = lx < mortar || lx >= bW - mortar || ly < mortar || ly >= bH - mortar;
                boolean nearMortar = lx < 5 || lx >= bW - 5 || ly < 5 || ly >= bH - 5;

                int blockId = (x + offX) / bW * 7 + row * 31;
                float age   = fbm(x * 0.022f, y * 0.022f, 4321) * 38;
                int base    = clamp(88 + hash(blockId, 3) % 18 + (int) age);
                int r = clamp(base + 12), g = clamp(base + 6), b = clamp(base - 4);

                if (isMortar) {
                    int mv = clamp(52 + (int)(fbm(x * 0.08f, y * 0.08f, 999) * 14));
                    p.set(x, y, mv - 2, mv, mv + 3);
                    continue;
                }

                float stain = fbm(x * 0.038f, y * 0.038f, 7777);
                if (stain > 0.68f) { r = clamp(r - 28); g = clamp(g - 22); b = clamp(b - 18); }

                float moss = fbm(x * 0.055f, y * 0.055f, 2222);
                if (moss > 0.64f && nearMortar) {
                    float m = (moss - 0.64f) * 2.8f;
                    r = clamp((int)(r * (1 - m) + 42  * m));
                    g = clamp((int)(g * (1 - m) + 88  * m));
                    b = clamp((int)(b * (1 - m) + 34  * m));
                }

                float drip = smoothNoise(x * 0.18f, y * 0.028f, 5555);
                if (drip > 0.72f) { r = clamp(r - 18); g = clamp(g - 14); b = clamp(b - 8); }

                float crack = fbm(x * 0.12f + (float)Math.sin(y * 0.07f) * 3, y * 0.09f, 3333);
                if (crack > 0.80f) { r = clamp(r - 35); g = clamp(g - 30); b = clamp(b - 25); }

                int chisel = Math.abs(((x * 5 - y * 2) % 41) - 20);
                if (chisel < 1 && !nearMortar) { r = clamp(r - 12); g = clamp(g - 10); b = clamp(b - 8); }

                if (hash(blockId, 17) % 12 == 0) {
                    int gx = lx - bW / 2, gy = ly - bH / 2;
                    if (Math.abs(gx) < 8 && Math.abs(gy) < 8 && (Math.abs(gx) < 1 || Math.abs(gy) < 1)) {
                        r = clamp(r - 20); g = clamp(g - 16); b = clamp(b - 12);
                    }
                }

                p.set(x, y, r, g, b);
            }
        }
        return p.toTexture();
    }

    private static Texture2D loadImage(String resource) {
        try (InputStream is = ProceduralTextures.class.getResourceAsStream(resource)) {
            if (is == null) return null;
            BufferedImage bi = ImageIO.read(is);
            if (bi == null) return null;
            int w = bi.getWidth(), h = bi.getHeight();
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int argb = bi.getRGB(x, y);
                buf.put((byte)((argb >> 16) & 0xFF));
                buf.put((byte)((argb >>  8) & 0xFF));
                buf.put((byte)( argb        & 0xFF));
                buf.put((byte)((argb >> 24) & 0xFF));
            }
            buf.flip();
            Image img = new Image(Image.Format.RGBA8, w, h, buf, ColorSpace.sRGB);
            Texture2D tex = new Texture2D(img);
            tex.setWrap(com.jme3.texture.Texture.WrapMode.Repeat);
            return tex;
        } catch (Exception e) {
            return null;
        }
    }

    // ── Noise ─────────────────────────────────────────────────────────────────

    private static float smoothNoise(float x, float y, int seed) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        float fx = x - ix, fy = y - iy;
        float a = lattice(ix, iy, seed),    b = lattice(ix+1, iy, seed);
        float c = lattice(ix, iy+1, seed),  d = lattice(ix+1, iy+1, seed);
        float s = fx*fx*(3-2*fx), t = fy*fy*(3-2*fy);
        return (a + s*(b-a)) + t*((c + s*(d-c)) - (a + s*(b-a)));
    }

    private static float fbm(float x, float y, int seed) {
        float v = 0, amp = 1f, freq = 1f, sum = 0;
        for (int i = 0; i < 4; i++) {
            v += smoothNoise(x*freq, y*freq, seed + i*97) * amp;
            sum += amp; freq *= 2; amp *= 0.5f;
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

    // ── ByteBuffer helper ────────────────────────────────────────────────────

    private static class Pixels {
        final ByteBuffer buf = BufferUtils.createByteBuffer(S * S * 4);
        void set(int x, int y, int r, int g, int b) {
            int idx = (y * S + x) * 4;
            buf.put(idx,   (byte) r);
            buf.put(idx+1, (byte) g);
            buf.put(idx+2, (byte) b);
            buf.put(idx+3, (byte) 255);
        }
        Texture2D toTexture() {
            Image img = new Image(Image.Format.RGBA8, S, S, buf, ColorSpace.sRGB);
            Texture2D tex = new Texture2D(img);
            tex.setWrap(com.jme3.texture.Texture.WrapMode.Repeat);
            return tex;
        }
    }
}
