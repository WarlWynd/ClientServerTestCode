# Multiplayer Game — Java UDP Client/Server

A multiplayer game skeleton built with:

| Layer | Technology |
|---|---|
| Protocol | UDP (custom JSON packet protocol) |
| Server runtime | Java 23 virtual threads |
| Client UI | JavaFX 23 |
| Authentication | BCrypt passwords + UUID session tokens |
| Persistence | MySQL / MariaDB |
| Build | Gradle 8 (multi-project) |

---

## Project Structure

```
multiplayer-game/
├── shared/          Packet protocol, PacketType enum, serializer
├── server/          UDP server, auth, game logic, DB access
└── client/          JavaFX app (Login, Register, Game screens)
```

---

## Prerequisites

| Requirement | Version |
|---|---|
| JDK | 23+ |
| Gradle | 8.x (or use the wrapper) |
| MySQL / MariaDB | 8.0+ / 10.6+ |

---

## Database Setup

```sql
-- Run once as a MySQL admin user
CREATE DATABASE game_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'gameuser'@'localhost' IDENTIFIED BY 'yourpassword';
GRANT ALL PRIVILEGES ON game_db.* TO 'gameuser'@'localhost';
FLUSH PRIVILEGES;
```

The server creates the `users` and `sessions` tables automatically on first start.

---

## Configuration

### Server — `server/src/main/resources/server.properties`

```properties
server.port=9876

db.host=localhost
db.port=3306
db.name=game_db
db.user=gameuser
db.password=yourpassword
```

### Client — `client/src/main/resources/client.properties`

```properties
server.host=localhost
server.port=9876
client.port=0          # 0 = OS-assigned ephemeral port
```

---

## Building & Running

### Build everything

```bash
./gradlew build
```

### Run the server

```bash
./gradlew :server:run
```

Or as a fat JAR:

```bash
./gradlew :server:jar
java --enable-preview -jar server/build/libs/game-server.jar
```

### Run the client

```bash
./gradlew :client:run
```

Launch multiple clients simultaneously to test multiplayer.

---

## How It Works

### Packet Protocol

Every UDP datagram is a JSON-serialized `Packet`:

```json
{
  "type": "PLAYER_UPDATE",
  "sessionToken": "550e8400-e29b-41d4-a716-446655440000",
  "sequence": 42,
  "timestamp": 1718000000000,
  "payload": { "x": 320.5, "y": 215.0, "score": 7 }
}
```

### Authentication Flow

```
Client                          Server
  │── LOGIN_REQUEST ──────────────→ │  validate credentials (BCrypt)
  │← LOGIN_RESPONSE ───────────────  │  create session in DB, return token
  │                                  │
  │── GAME_JOIN (token) ───────────→ │  validate token → register player
  │← GAME_STATE ────────────────── │  broadcast world snapshot to all
  │                                  │
  │── PLAYER_UPDATE (token) ──────→ │  update position, broadcast GAME_STATE
  │← GAME_STATE ────────────────── │
```

### Session Lifecycle

- Sessions are stored in MySQL with a 24-hour TTL.
- Expired sessions are purged every 5 minutes.
- Idle players (no PLAYER_UPDATE for 30 s) are evicted from the game world.
- Logout invalidates the session immediately.

### Game Loop

- Client: 60 FPS JavaFX `AnimationTimer` → process held keys → send PLAYER_UPDATE at most every 50 ms (20 Hz cap).
- Server: event-driven — every PLAYER_UPDATE triggers a broadcast GAME_STATE to all connected clients.

---

## Extending the Game

The scaffold is intentionally minimal. Good next steps:

- **Collectibles / scoring** — add collectible objects to `GameHandler`, increase `score` on collection.
- **Fixed-rate server tick** — replace event-driven broadcast with a `ScheduledExecutorService` game loop at 20 Hz.
- **Delta compression** — only send changed player state to reduce bandwidth.
- **Rooms / lobbies** — add a `rooms` table and a `GAME_ROOM` packet type.
- **Connection pool** — replace `DriverManager.getConnection()` with HikariCP.
- **TLS / encryption** — wrap packets with AES-GCM before sending.

---

## Packet Reference

| Type | Direction | Auth Required | Description |
|---|---|---|---|
| `LOGIN_REQUEST` | C→S | No | Username + password |
| `LOGIN_RESPONSE` | S→C | — | Success flag + session token |
| `REGISTER_REQUEST` | C→S | No | Username + password |
| `REGISTER_RESPONSE` | S→C | — | Success flag + message |
| `LOGOUT_REQUEST` | C→S | Yes | Invalidate session |
| `LOGOUT_RESPONSE` | S→C | — | Confirmation |
| `PING` | C→S | No | Connectivity check |
| `PONG` | S→C | — | Echo with timestamp |
| `ERROR` | S→C | — | Server-sent error message |
| `GAME_JOIN` | C→S | Yes | Enter the game world |
| `GAME_LEAVE` | C→S | Yes | Leave the game world |
| `PLAYER_UPDATE` | C→S | Yes | Position / state delta |
| `GAME_STATE` | S→C | — | Authoritative world snapshot |
