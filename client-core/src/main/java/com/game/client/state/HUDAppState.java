package com.game.client.state;

import com.game.client.SessionStore;
import com.game.client.dungeon.DungeonMap;
import com.game.client.entity.ActionSlot;
import com.game.client.entity.CharacterEntity;
import com.game.client.entity.WeaponType;
import com.game.shared.Packet;
import com.game.shared.PacketType;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Quad;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HUD overlay: HP/MP stats (top-left), minimap (top-right),
 * movement buttons (bottom-left), action buttons (bottom-right).
 */
public class HUDAppState extends BaseAppState {

    private static final Logger log = LoggerFactory.getLogger(HUDAppState.class);

    // Minimap
    private static final int TILE_PX    = 10;
    private static final int MAP_MARGIN = 12;

    // Movement buttons
    private static final int BTN_SIZE   = 60;
    private static final int BTN_GAP    = 4;
    private static final int BTN_MARGIN = 12;

    // Minimap colors
    private static final ColorRGBA COLOR_WALL   = new ColorRGBA(0.13f, 0.11f, 0.09f, 1f);
    private static final ColorRGBA COLOR_FLOOR  = new ColorRGBA(0.52f, 0.47f, 0.42f, 1f);
    private static final ColorRGBA COLOR_PLAYER = new ColorRGBA(1.00f, 0.88f, 0.08f, 1f);
    private static final ColorRGBA COLOR_MAP_BG = new ColorRGBA(0.04f, 0.04f, 0.04f, 1f);

    // RPG UI palette
    private static final ColorRGBA COLOR_GOLD      = new ColorRGBA(0.92f, 0.76f, 0.18f, 1.00f);
    private static final ColorRGBA COLOR_GOLD_DIM  = new ColorRGBA(0.58f, 0.42f, 0.06f, 0.95f);
    private static final ColorRGBA COLOR_BTN_BG    = new ColorRGBA(0.12f, 0.08f, 0.05f, 0.92f);
    private static final ColorRGBA COLOR_PANEL_BG  = new ColorRGBA(0.07f, 0.04f, 0.02f, 0.90f);
    private static final ColorRGBA COLOR_HP        = new ColorRGBA(0.90f, 0.20f, 0.10f, 1.00f);
    private static final ColorRGBA COLOR_MP        = new ColorRGBA(0.20f, 0.46f, 0.95f, 1.00f);

    private final NetworkAppState        network;
    private final DungeonMap             map;
    private final CharacterEntity        player;
    private final GridMovementController movement;

    private Node         guiNode;
    private Container    statsPanel;
    private Node         minimapNode;
    private Node         buttonsNode;
    private Node         actionNode;
    private AssetManager am;

    private Geometry[][] tileGeos; // [z][x]
    private int lastPx = -1, lastPz = -1;
    private WeaponType lastWeapon = null;

    private Label   hpLabel;
    private Label   manaLabel;
    private Button[] actionBtns; // indexed by ActionSlot.ordinal()

    public HUDAppState(NetworkAppState network, DungeonMap map,
                       CharacterEntity player, GridMovementController movement) {
        this.network  = network;
        this.map      = map;
        this.player   = player;
        this.movement = movement;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application app) {
        guiNode = ((SimpleApplication) app).getGuiNode();
        am = app.getAssetManager();
        int screenW = app.getCamera().getWidth();
        int screenH = app.getCamera().getHeight();

        buildStats(screenH);
        buildMinimap(am, screenW, screenH);
        buildButtons(screenH);
        buildActionButtons(screenW, screenH);

        network.addListener(this::onPacket);
    }

    @Override
    protected void cleanup(Application app) {
        network.removeListener(this::onPacket);
        detachAll();
    }

    @Override
    protected void onEnable() {
        guiNode.attachChild(statsPanel);
        guiNode.attachChild(minimapNode);
        guiNode.attachChild(buttonsNode);
        guiNode.attachChild(actionNode);
    }

    @Override
    protected void onDisable() {
        detachAll();
    }

    private void detachAll() {
        if (statsPanel  != null) statsPanel.removeFromParent();
        if (minimapNode != null) minimapNode.removeFromParent();
        if (buttonsNode != null) buttonsNode.removeFromParent();
        if (actionNode  != null) actionNode.removeFromParent();
    }

    @Override
    public void update(float tpf) {
        if (player == null) return;

        // Minimap player dot
        if (tileGeos != null) {
            int px = player.gridX, pz = player.gridZ;
            if (px != lastPx || pz != lastPz) {
                if (lastPx >= 0) setTileColor(lastPz, lastPx, baseTileColor(lastPx, lastPz));
                setTileColor(pz, px, COLOR_PLAYER);
                lastPx = px;
                lastPz = pz;
            }
        }

        // Action button enable state when weapon changes
        if (player.weaponType != lastWeapon) {
            refreshActionButtons(player.weaponType);
            lastWeapon = player.weaponType;
        }
    }

    // ── Stats (top-left) ──────────────────────────────────────────────────────

    private void buildStats(int screenH) {
        statsPanel = new Container();
        statsPanel.setBackground(new QuadBackgroundComponent(COLOR_PANEL_BG));

        Label nameLabel = new Label(SessionStore.getUsername());
        nameLabel.setColor(COLOR_GOLD);
        nameLabel.setFontSize(15f);
        statsPanel.addChild(nameLabel);

        hpLabel = new Label("HP: —");
        hpLabel.setColor(COLOR_HP);
        statsPanel.addChild(hpLabel);

        manaLabel = new Label("MP: —");
        manaLabel.setColor(COLOR_MP);
        statsPanel.addChild(manaLabel);

        statsPanel.setLocalTranslation(10, screenH - 10, 1);
    }

    // ── Minimap (top-right) ───────────────────────────────────────────────────

    private void buildMinimap(AssetManager am, int screenW, int screenH) {
        int mmW = map.width * TILE_PX;
        int mmH = map.depth * TILE_PX;

        minimapNode = new Node("minimap");

        // Dark background behind tiles (2px padding each side)
        Geometry bg = quad(am, mmW + 4, mmH + 4, COLOR_MAP_BG);
        bg.setLocalTranslation(-2, -2, 0);
        minimapNode.attachChild(bg);

        tileGeos = new Geometry[map.depth][map.width];
        for (int z = 0; z < map.depth; z++) {
            for (int x = 0; x < map.width; x++) {
                Geometry g = quad(am, TILE_PX - 1, TILE_PX - 1, baseTileColor(x, z));
                // z=0 (north) drawn at top; flip vertical
                g.setLocalTranslation(x * TILE_PX, (map.depth - 1 - z) * TILE_PX, 1);
                minimapNode.attachChild(g);
                tileGeos[z][x] = g;
            }
        }

        // "N" label above the north (top) edge of the map
        com.jme3.font.BitmapFont font = am.loadFont("Interface/Fonts/Default.fnt");
        com.jme3.font.BitmapText northLabel = new com.jme3.font.BitmapText(font, false);
        northLabel.setSize(font.getCharSet().getRenderedSize() * 0.85f);
        northLabel.setColor(new ColorRGBA(0.75f, 0.85f, 1.0f, 1f));
        northLabel.setText("N");
        // Centre above the top tile row; BitmapText origin is bottom-left of text
        float textW = northLabel.getLineWidth();
        northLabel.setLocalTranslation(mmW / 2f - textW / 2f, mmH + 14, 2);
        minimapNode.attachChild(northLabel);

        // Position: bottom-left of content grid at screen (right-edge - mmW - margin, top-edge - mmH - margin)
        minimapNode.setLocalTranslation(
            screenW - MAP_MARGIN - mmW - 2,
            screenH - MAP_MARGIN - mmH - 16,   // shift down to make room for "N" label
            2);
    }

    private Geometry rpgQuad(float w, float h, ColorRGBA color) {
        Geometry g = new Geometry("rq", new Quad(w, h));
        Material m = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", color.clone());
        if (color.a < 1f) m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        g.setMaterial(m);
        return g;
    }

    private Geometry quad(AssetManager am, int w, int h, ColorRGBA color) {
        Geometry g = new Geometry("q", new Quad(w, h));
        Material m = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", color.clone());
        g.setMaterial(m);
        return g;
    }

    private ColorRGBA baseTileColor(int x, int z) {
        return map.isPassable(x, z) ? COLOR_FLOOR : COLOR_WALL;
    }

    private void setTileColor(int z, int x, ColorRGBA color) {
        tileGeos[z][x].getMaterial().setColor("Color", color.clone());
    }

    // ── Movement buttons (bottom-left) ────────────────────────────────────────

    private void buildButtons(int screenH) {
        buttonsNode = new Node("buttons");

        // 3-column × 2-row layout:
        //   col 0        col 1      col 2
        //   row 1 (top): turnLeft   forward    turnRight
        //   row 0 (bot): strafeLeft backward   strafeRight
        addArrowBtn(ArrowMesh.Dir.TURN_LEFT,  0, 1, () -> movement.turnLeft());
        addArrowBtn(ArrowMesh.Dir.UP,         1, 1, () -> movement.forward());
        addArrowBtn(ArrowMesh.Dir.TURN_RIGHT, 2, 1, () -> movement.turnRight());
        addArrowBtn(ArrowMesh.Dir.LEFT,       0, 0, () -> movement.strafeLeft());
        addArrowBtn(ArrowMesh.Dir.DOWN,       1, 0, () -> movement.backward());
        addArrowBtn(ArrowMesh.Dir.RIGHT,      2, 0, () -> movement.strafeRight());

        buttonsNode.setLocalTranslation(0, 0, 2);
    }

    private void addArrowBtn(ArrowMesh.Dir dir, int col, int row, Runnable action) {
        float bx = BTN_MARGIN + col * (BTN_SIZE + BTN_GAP);
        float by = BTN_MARGIN + row * (BTN_SIZE + BTN_GAP) + BTN_SIZE;

        // Lemur button top-left is at (bx, by), extends DOWN by BTN_SIZE.
        // Quad origin is bottom-left, extends UP — so we offset by -BTN_SIZE in Y.
        Geometry border = rpgQuad(BTN_SIZE + 6, BTN_SIZE + 6, COLOR_GOLD_DIM);
        border.setLocalTranslation(bx - 3, by - BTN_SIZE - 3, -0.5f);
        buttonsNode.attachChild(border);

        Geometry fill = rpgQuad(BTN_SIZE, BTN_SIZE, COLOR_BTN_BG);
        fill.setLocalTranslation(bx, by - BTN_SIZE, 0f);
        buttonsNode.attachChild(fill);

        // Lemur button — transparent bg, used only for click detection
        Button btn = new Button("");
        btn.setBackground(new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0)));
        btn.setPreferredSize(new Vector3f(BTN_SIZE, BTN_SIZE, 0));
        btn.setLocalTranslation(bx, by, 0.5f);
        btn.addClickCommands(src -> action.run());
        buttonsNode.attachChild(btn);

        // Gold arrow symbol
        float cx = bx + BTN_SIZE * 0.5f;
        float cy = by - BTN_SIZE * 0.5f;
        Geometry arrow = new Geometry("arrow_" + dir, ArrowMesh.build(dir, BTN_SIZE * 0.56f));
        Material mat = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", COLOR_GOLD);
        mat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        arrow.setMaterial(mat);
        arrow.setLocalTranslation(cx, cy, 1f);
        buttonsNode.attachChild(arrow);
    }

    // ── Action buttons (bottom-right) ────────────────────────────────────────

    private void buildActionButtons(int screenW, int screenH) {
        actionNode = new Node("actions");
        ActionSlot[] slots = ActionSlot.values();
        actionBtns = new Button[slots.length];

        int cols = 2;
        for (int i = 0; i < slots.length; i++) {
            int col = i % cols;
            int row = i / cols;
            float x = screenW - BTN_MARGIN - (cols - col) * (BTN_SIZE + BTN_GAP) + BTN_GAP;
            float y = BTN_MARGIN + row * (BTN_SIZE + BTN_GAP) + BTN_SIZE;

            // Same Quad-vs-Lemur origin correction: offset down by BTN_SIZE.
            Geometry border = rpgQuad(BTN_SIZE + 6, BTN_SIZE + 6, COLOR_GOLD_DIM);
            border.setLocalTranslation(x - 3, y - BTN_SIZE - 3, -0.5f);
            actionNode.attachChild(border);

            Geometry fill = rpgQuad(BTN_SIZE, BTN_SIZE, COLOR_BTN_BG);
            fill.setLocalTranslation(x, y - BTN_SIZE, 0f);
            actionNode.attachChild(fill);

            Button btn = new Button(slots[i].label);
            btn.setPreferredSize(new Vector3f(BTN_SIZE, BTN_SIZE, 0));
            btn.setLocalTranslation(x, y, 0.5f);
            btn.setBackground(new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0)));
            btn.setColor(COLOR_GOLD);
            btn.setFontSize(12f);
            // TODO: wire to combat actions when combat system is ready
            actionBtns[i] = btn;
            actionNode.attachChild(btn);
        }

        actionNode.setLocalTranslation(0, 0, 2);
        refreshActionButtons(player != null ? player.weaponType : WeaponType.UNARMED);
    }

    private void refreshActionButtons(WeaponType weapon) {
        if (actionBtns == null) return;
        ActionSlot[] slots = ActionSlot.values();
        for (int i = 0; i < slots.length; i++) {
            boolean enabled = slots[i].enabledFor(weapon);
            actionBtns[i].setEnabled(enabled);
        }
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void onPacket(Packet packet) {
        if (packet.type != PacketType.GAME_STATE) return;
        int hp   = packet.payload.path("hp").asInt(-1);
        int mana = packet.payload.path("mana").asInt(-1);
        if (hp < 0 && mana < 0) return;
        getApplication().enqueue(() -> {
            if (hp   >= 0) hpLabel.setText("HP: " + hp);
            if (mana >= 0) manaLabel.setText("MP: " + mana);
        });
    }
}
