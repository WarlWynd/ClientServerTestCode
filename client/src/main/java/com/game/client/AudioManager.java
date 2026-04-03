package com.game.client;

import javafx.scene.media.AudioClip;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Singleton audio manager.
 *
 * Handles:
 *   - Background music (looping MediaPlayer)
 *   - Sound effects (low-latency AudioClip cache)
 *   - Volume control for music and SFX independently
 *   - Loading from both classpath resources and absolute file paths
 *     (so the Audio Dev tab can preview files before they're bundled)
 */
public class AudioManager {

    private static final Logger log = LoggerFactory.getLogger(AudioManager.class);
    private static AudioManager instance;

    private MediaPlayer          musicPlayer;
    private double               musicVolume = 0.7;
    private double               sfxVolume   = 1.0;
    private boolean              musicMuted  = false;
    private boolean              sfxMuted    = false;

    private final Map<String, AudioClip> sfxCache = new HashMap<>();

    private AudioManager() {}

    public static AudioManager getInstance() {
        if (instance == null) instance = new AudioManager();
        return instance;
    }

    // ── Music ────────────────────────────────────────────────────────────────

    /** Play a music file from the classpath (e.g. "/audio/music/game_theme.mp3") */
    public void playMusic(String resourcePath) {
        stopMusic();
        try {
            URL url = getClass().getResource(resourcePath);
            if (url == null) { log.warn("Music resource not found: {}", resourcePath); return; }
            musicPlayer = new MediaPlayer(new Media(url.toExternalForm()));
            musicPlayer.setCycleCount(MediaPlayer.INDEFINITE);
            musicPlayer.setVolume(musicMuted ? 0 : musicVolume);
            musicPlayer.play();
            log.info("Playing music: {}", resourcePath);
        } catch (Exception e) {
            log.error("Failed to play music '{}': {}", resourcePath, e.getMessage());
        }
    }

    /** Play a music file from an absolute file path (used by Audio Dev tab) */
    public void playMusicFromFile(File file) {
        stopMusic();
        try {
            musicPlayer = new MediaPlayer(new Media(file.toURI().toString()));
            musicPlayer.setCycleCount(MediaPlayer.INDEFINITE);
            musicPlayer.setVolume(musicMuted ? 0 : musicVolume);
            musicPlayer.play();
            log.info("Playing music from file: {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to play music file '{}': {}", file.getName(), e.getMessage());
        }
    }

    public void stopMusic() {
        if (musicPlayer != null) {
            musicPlayer.stop();
            musicPlayer.dispose();
            musicPlayer = null;
        }
    }

    public void pauseMusic() {
        if (musicPlayer != null) musicPlayer.pause();
    }

    public void resumeMusic() {
        if (musicPlayer != null) musicPlayer.play();
    }

    public void setMusicVolume(double volume) {
        this.musicVolume = Math.max(0, Math.min(1, volume));
        if (musicPlayer != null && !musicMuted) musicPlayer.setVolume(this.musicVolume);
    }

    public void setMusicMuted(boolean muted) {
        this.musicMuted = muted;
        if (musicPlayer != null) musicPlayer.setVolume(muted ? 0 : musicVolume);
    }

    public boolean isMusicPlaying() {
        return musicPlayer != null &&
               musicPlayer.getStatus() == MediaPlayer.Status.PLAYING;
    }

    public double getMusicVolume()  { return musicVolume; }
    public boolean isMusicMuted()   { return musicMuted; }

    // ── Sound Effects ────────────────────────────────────────────────────────

    /** Play a sound effect from classpath (cached after first load) */
    public void playSfx(String resourcePath) {
        if (sfxMuted) return;
        try {
            AudioClip clip = sfxCache.computeIfAbsent(resourcePath, path -> {
                URL url = getClass().getResource(path);
                if (url == null) { log.warn("SFX resource not found: {}", path); return null; }
                return new AudioClip(url.toExternalForm());
            });
            if (clip != null) {
                clip.setVolume(sfxVolume);
                clip.play();
            }
        } catch (Exception e) {
            log.error("Failed to play SFX '{}': {}", resourcePath, e.getMessage());
        }
    }

    /** Play a sound effect from an absolute file path (used by Audio Dev tab) */
    public void playSfxFromFile(File file) {
        if (sfxMuted) return;
        try {
            AudioClip clip = new AudioClip(file.toURI().toString());
            clip.setVolume(sfxVolume);
            clip.play();
        } catch (Exception e) {
            log.error("Failed to play SFX file '{}': {}", file.getName(), e.getMessage());
        }
    }

    public void setSfxVolume(double volume) {
        this.sfxVolume = Math.max(0, Math.min(1, volume));
    }

    public void setSfxMuted(boolean muted) { this.sfxMuted = muted; }
    public double getSfxVolume()           { return sfxVolume; }
    public boolean isSfxMuted()            { return sfxMuted; }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    public void shutdown() {
        stopMusic();
        sfxCache.clear();
    }

    /** Remove a cached SFX entry so it is reloaded from disk on next play. */
    public static void invalidate(String name) {
        if (instance != null) instance.sfxCache.remove(name);
    }
}
