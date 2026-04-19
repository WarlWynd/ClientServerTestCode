package com.game.client.entity;

import com.game.client.dungeon.DungeonMap;
import com.jme3.anim.AnimComposer;
import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetManager;
import com.jme3.asset.AssetNotFoundException;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Manages 3D spatials for all characters.
 *
 * Attempts to load a GLTF/GLB model from Models/characters/<class>.glb for each
 * CharacterClass. Falls back to a box-man placeholder when the asset is missing.
 *
 * Animation clips in the GLTF file must be named: idle, walk, attack, cast, hit, death
 *
 * Mixamo exports typically need MODEL_SCALE ≈ 0.02. Custom rigs at 1-2m height use 1.0.
 */
public class CharacterManager {

    private static final Logger log = LoggerFactory.getLogger(CharacterManager.class);

    private static final String MODEL_BASE = "Models/characters/";

    // Scale applied to loaded GLTF models — tune per asset pipeline
    private static final float MODEL_SCALE = 1.0f;

    // Expected animation clip names in the GLTF file
    private static final Map<CharacterState, String> ANIM_CLIP = Map.of(
            CharacterState.IDLE,   "idle",
            CharacterState.WALK,   "walk",
            CharacterState.ATTACK, "attack",
            CharacterState.CAST,   "cast",
            CharacterState.HIT,    "hit",
            CharacterState.DEAD,   "death"
    );

    // ── Per-character visual state ─────────────────────────────────────────────

    private static final class Visual {
        final Node root;
        final AnimComposer anim;    // null for box-man
        CharacterState lastState = CharacterState.IDLE;

        Visual(Node root, AnimComposer anim) {
            this.root = root;
            this.anim = anim;
        }
    }

    // ── State ──────────────────────────────────────────────────────────────────

    private final Node sceneRoot;
    private final AssetManager assets;

    private final Map<CharacterClass, Material> classMats = new HashMap<>();
    private final Material hitMat;
    private final Material castMat;
    private final Material skinMat;

    private final Map<String, CharacterEntity> entities = new HashMap<>();
    private final Map<String, Visual>          visuals  = new HashMap<>();

    // ── Construction ───────────────────────────────────────────────────────────

    public CharacterManager(SimpleApplication app) {
        this.sceneRoot = app.getRootNode();
        this.assets    = app.getAssetManager();

        for (CharacterClass cc : CharacterClass.values()) {
            classMats.put(cc, unshaded(cc.bodyColor()));
        }
        hitMat  = unshaded(ColorRGBA.Red.clone());
        castMat = unshaded(new ColorRGBA(0.4f, 0.9f, 1f, 1f));
        skinMat = unshaded(new ColorRGBA(0.85f, 0.72f, 0.60f, 1f));
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void addEntity(CharacterEntity e) {
        entities.put(e.id, e);
        Visual v = buildVisual(e);
        visuals.put(e.id, v);
        sceneRoot.attachChild(v.root);
        syncTransform(e, v.root);
        applyState(e, v);
    }

    public void removeEntity(String id) {
        entities.remove(id);
        Visual v = visuals.remove(id);
        if (v != null) v.root.removeFromParent();
    }

    public void updateEntity(CharacterEntity e) {
        entities.put(e.id, e);
        Visual v = visuals.get(e.id);
        if (v == null) return;
        syncTransform(e, v.root);
        applyState(e, v);
    }

    // ── Visual construction ────────────────────────────────────────────────────

    private Visual buildVisual(CharacterEntity e) {
        String path = MODEL_BASE + e.charClass.name().toLowerCase() + ".glb";
        try {
            Spatial model = assets.loadModel(path);
            model.setLocalScale(MODEL_SCALE);

            // GLTF models are usually authored facing +Z; jME's default facing is -Z.
            // Rotate 180° around Y so the model faces the same direction as the entity.
            Quaternion flip = new Quaternion();
            flip.fromAngleAxis(FastMath.PI, Vector3f.UNIT_Y);
            model.setLocalRotation(flip);

            Node root = new Node("char_" + e.id);
            root.attachChild(model);

            AnimComposer anim = findAnimComposer(model);
            if (anim == null) {
                log.warn("Model {} has no AnimComposer — animations unavailable", path);
            } else {
                log.info("Loaded model {} with animations: {}", path, anim.getAnimClipsNames());
            }
            return new Visual(root, anim);

        } catch (AssetNotFoundException ex) {
            log.info("No model at {} — using box-man placeholder", path);
            return new Visual(buildBoxMan(e), null);
        }
    }

    private Node buildBoxMan(CharacterEntity e) {
        Node node = new Node("char_" + e.id);

        Geometry body = new Geometry("body", new Box(0.40f, 0.75f, 0.30f));
        body.setLocalTranslation(0, 0.75f, 0);
        body.setMaterial(classMats.get(e.charClass));
        node.attachChild(body);

        Geometry head = new Geometry("head", new Box(0.22f, 0.22f, 0.22f));
        head.setLocalTranslation(0, 1.72f, 0);
        head.setMaterial(skinMat);
        node.attachChild(head);

        // Small yellow box on the chest shows which way the entity faces
        Geometry indicator = new Geometry("indicator", new Box(0.08f, 0.08f, 0.12f));
        indicator.setLocalTranslation(0, 0.85f, -0.40f);
        indicator.setMaterial(unshaded(ColorRGBA.Yellow.clone()));
        node.attachChild(indicator);

        return node;
    }

    // ── Per-frame state sync ───────────────────────────────────────────────────

    private void syncTransform(CharacterEntity e, Node root) {
        root.setLocalTranslation(
                DungeonMap.TILE_SIZE * e.gridX,
                0f,
                DungeonMap.TILE_SIZE * e.gridZ);

        Quaternion rot = new Quaternion();
        rot.fromAngleAxis(e.facing.toYaw(), Vector3f.UNIT_Y);
        root.setLocalRotation(rot);
    }

    private void applyState(CharacterEntity e, Visual v) {
        if (e.state == v.lastState) return;
        v.lastState = e.state;

        if (v.anim != null) {
            applyAnimState(e.state, v.anim);
        } else {
            applyBoxManState(e.state, v.root, classMats.get(e.charClass));
        }
    }

    private void applyAnimState(CharacterState state, AnimComposer anim) {
        String clip = ANIM_CLIP.getOrDefault(state, "idle");
        if (anim.getAnimClipsNames().contains(clip)) {
            anim.setCurrentAction(clip);
        } else {
            log.debug("Animation clip '{}' not found — falling back to idle", clip);
            if (anim.getAnimClipsNames().contains("idle")) {
                anim.setCurrentAction("idle");
            }
        }
    }

    private void applyBoxManState(CharacterState state, Node root, Material classMat) {
        Geometry body = (Geometry) root.getChild("body");
        if (body == null) return;

        switch (state) {
            case DEAD -> {
                body.setMaterial(classMat);
                Quaternion fallen = new Quaternion();
                fallen.fromAngleAxis(FastMath.HALF_PI, Vector3f.UNIT_Z);
                body.setLocalRotation(fallen);
                body.setLocalTranslation(0, 0.30f, 0);
            }
            case HIT -> {
                body.setMaterial(hitMat);
                body.setLocalRotation(Quaternion.IDENTITY);
                body.setLocalTranslation(0, 0.75f, 0);
            }
            case CAST -> {
                body.setMaterial(castMat);
                body.setLocalRotation(Quaternion.IDENTITY);
                body.setLocalTranslation(0, 0.75f, 0);
            }
            default -> {
                body.setMaterial(classMat);
                body.setLocalRotation(Quaternion.IDENTITY);
                body.setLocalTranslation(0, 0.75f, 0);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static AnimComposer findAnimComposer(Spatial s) {
        AnimComposer ac = s.getControl(AnimComposer.class);
        if (ac != null) return ac;
        if (s instanceof Node n) {
            for (Spatial child : n.getChildren()) {
                ac = findAnimComposer(child);
                if (ac != null) return ac;
            }
        }
        return null;
    }

    private Material unshaded(ColorRGBA color) {
        Material m = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", color);
        return m;
    }
}
