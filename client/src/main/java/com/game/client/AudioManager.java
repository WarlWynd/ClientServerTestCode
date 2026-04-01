package com.game.client;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Loads and plays SFX from the classpath at /audio/sfx/.
 *
 * Clips are loaded once and cached as raw bytes so the same file can play
 * concurrently without re-reading the resource each time.
 * Volume is derived from AppSettings.getSoundMode().
 */
public final class AudioManager {

    private static final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    private AudioManager() {}

    /** Play a file from /audio/sfx/ by filename, e.g. "login.wav". No-op if missing or sound is off. */
    public static void play(String sfxName) {
        if (MobilePlatform.isMobile()) return; // javax.sound.sampled not available on Android
        double vol = AppSettings.getSoundMode().volume;
        if (vol == 0.0) return;

        byte[] data = cache.computeIfAbsent(sfxName, AudioManager::loadBytes);
        if (data.length == 0) return;

        Thread.ofPlatform().daemon(true).start(() -> {
            try {
                AudioInputStream ais = AudioSystem.getAudioInputStream(
                        new ByteArrayInputStream(data));
                Clip clip = AudioSystem.getClip();
                clip.open(ais);
                applyVolume(clip, vol);
                clip.start();
                clip.addLineListener(e -> {
                    if (e.getType() == LineEvent.Type.STOP) clip.close();
                });
            } catch (Exception ignored) {}
        });
    }

    /** Invalidate cached bytes for a file (e.g. after regenerating a stub). */
    public static void invalidate(String sfxName) {
        cache.remove(sfxName);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private static byte[] loadBytes(String name) {
        // 1. Local cache — downloaded from server
        Path cached = ClientSyncClient.BASE_DIR.resolve("sounds").resolve(name);
        if (Files.exists(cached)) {
            try { return Files.readAllBytes(cached); } catch (Exception ignored) {}
        }
        // 2. Classpath fallback — bundled stubs
        try (InputStream in = AudioManager.class.getResourceAsStream("/audio/sfx/" + name)) {
            if (in == null) return new byte[0];
            return in.readAllBytes();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static void applyVolume(Clip clip, double vol) {
        try {
            FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
            float dB = (vol >= 1.0) ? 0f : (float)(20.0 * Math.log10(vol));
            gain.setValue(Math.max(gain.getMinimum(), dB));
        } catch (Exception ignored) {}
    }
}
