package com.game.client.state;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.client.SessionStore;
import com.game.shared.Packet;
import com.game.shared.PacketSerializer;
import com.game.shared.PacketType;
import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.scene.Node;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.TextField;
import com.simsilica.lemur.component.SpringGridLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lemur-based login screen rendered in 3D space.
 *
 * On successful login → detaches itself and attaches WorldAppState.
 */
public class LoginAppState extends BaseAppState {

    private static final Logger log = LoggerFactory.getLogger(LoginAppState.class);

    private final NetworkAppState network;
    private Node  guiNode;
    private Container loginPanel;

    public LoginAppState(NetworkAppState network) {
        this.network = network;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void initialize(Application app) {
        guiNode = ((SimpleApplication) app).getGuiNode();
        buildLoginForm(app);

        network.addListener(this::onPacket);
    }

    @Override
    protected void cleanup(Application app) {
        network.removeListener(this::onPacket);
        if (loginPanel != null) loginPanel.removeFromParent();
    }

    @Override protected void onEnable()  { if (loginPanel != null) guiNode.attachChild(loginPanel); }
    @Override protected void onDisable() { if (loginPanel != null) loginPanel.removeFromParent(); }

    // ── UI ────────────────────────────────────────────────────────────────────

    private TextField emailField;
    private TextField passwordField;  // TODO: mask with PasswordField from lemur-proto
    private Label statusLabel;

    private void buildLoginForm(Application app) {
        int screenW = app.getCamera().getWidth();
        int screenH = app.getCamera().getHeight();

        loginPanel = new Container();
        loginPanel.setLayout(new SpringGridLayout());
        loginPanel.addChild(new Label("Adventure Friends"));

        Container form = loginPanel.addChild(new Container());
        form.addChild(new Label("Email:"));
        emailField = form.addChild(new TextField(""));

        form.addChild(new Label("Password:"));
        passwordField = form.addChild(new TextField(""));

        statusLabel = loginPanel.addChild(new Label(""));

        Button loginBtn = loginPanel.addChild(new Button("Login"));
        loginBtn.addClickCommands(src -> doLogin());

        // Centre on screen
        loginPanel.setLocalTranslation(
                screenW / 2f - 150,
                screenH / 2f + 100,
                0);

        guiNode.attachChild(loginPanel);
    }

    private void doLogin() {
        String email    = emailField.getText().trim();
        String password = passwordField.getText().trim();
        if (email.isEmpty() || password.isEmpty()) {
            statusLabel.setText("Enter email and password.");
            return;
        }
        statusLabel.setText("Connecting…");

        ObjectNode payload = PacketSerializer.mapper().createObjectNode();
        payload.put("email",    email);
        payload.put("password", password);
        network.send(new Packet(PacketType.LOGIN_REQUEST, null, payload));
    }

    // ── Network ───────────────────────────────────────────────────────────────

    private void onPacket(Packet packet) {
        if (packet.type != PacketType.LOGIN_RESPONSE) return;
        boolean ok = packet.payload.path("success").asBoolean(false);
        if (ok) {
            String token    = packet.payload.path("token").asText("");
            String username = packet.payload.path("username").asText("");
            boolean isAdmin = packet.payload.path("admin").asBoolean(false);
            SessionStore.set(token, username, isAdmin, false, false, false);
            getApplication().enqueue(() -> onLoginSuccess());
        } else {
            String msg = packet.payload.path("message").asText("Login failed.");
            getApplication().enqueue(() -> statusLabel.setText(msg));
        }
    }

    private void onLoginSuccess() {
        log.info("Login successful — entering dungeon world");
        getStateManager().attach(new DungeonWorldAppState(network));
        getStateManager().detach(this);
    }
}
