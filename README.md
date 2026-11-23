# Hoplite Plugin

Hoplite is a Paper/Spigot minigame plugin for queued survival matches, duo matches, and private party games. It creates a temporary game world for each match using Multiverse and TerraformGenerator, tracks player ELO and coins, and provides PlaceholderAPI leaderboard placeholders.

## Requirements

- A Paper or Spigot server compatible with Minecraft 1.21 and Java 21 or newer.
- Multiverse-Core, used to create and remove game worlds.
- TerraformGenerator, used as the game-world generator.
- PlaceholderAPI is optional. For local builds, place its jar at `lib/PlaceholderAPI.jar`.

Each configured `lobbyWorld` must already exist and be loaded when the plugin starts. The configured `gameWorld` is created for a match and removed after the match ends. Make sure your Multiverse and TerraformGenerator versions support the server version you run.

## Build and Install

Build the plugin with Maven:

```bash
mvn clean package
```

Copy `target/hoplite-plugin-1.21.0-SNAPSHOT.jar` into the server's `plugins/` directory, then start or restart the server. On first startup, the plugin creates its data folder and copies the default `config.yml` there. Edit `plugins/HoplitePlugin/config.yml` and restart the server to apply lobby changes.

Set `activation-code` in `config.yml` to the four-digit code bundled in `HoplitePlugin.jar` before enabling the plugin. If the value is missing, malformed, or incorrect, Hoplite logs `Put the correct code in config.yml` and disables itself. The code is recoverable by anyone who can inspect the plugin jar, and four digits are low entropy, so this is a startup gate rather than strong authentication.

By default, player data is stored in `plugins/HoplitePlugin/elo.yml`, `coins.yml`, and `kits.yml`. To share player data across servers, configure the same MySQL/MariaDB database on each server as described below.

## Shared MySQL/MariaDB Data

Create the database on your MySQL or MariaDB server, then set the same `database` values in every server's `config.yml` and enable it:

```yaml
database:
  enabled: true
  host: db.example.net
  port: 3306
  name: hoplite
  username: hoplite
  password: replace-this
  table-prefix: hoplite_
```

The plugin creates its tables automatically. Coins, ELO, kit ownership, kit definitions, and kit prices are read from and written to the shared database. Existing YAML data is imported for records not already present in the database. Keep database credentials private and use the same table prefix on all servers.

When the database is disabled, the plugin continues to use its local YAML files. If the database cannot be reached during startup, the plugin logs an error and falls back to local YAML data for that server.

## Lobby Configuration

The `lobbies` list in `config.yml` defines waiting areas and the temporary world used for each match:

```yaml
lobbies:
  - lobbyWorld: qwait1
    gameWorld: game1
    maxPlayers: 10
    minPlayers: 5
  - lobbyWorld: qwait2
    gameWorld: game2
    maxPlayers: 20
    minPlayers: 10
    mode: duos
```

- `lobbyWorld`: Existing world where players wait for the match.
- `gameWorld`: World name created for the match and removed after cleanup.
- `maxPlayers`: Maximum players in that lobby.
- `minPlayers`: Number of players required to start its regular countdown.
- `mode`: Set to `duos` to make the lobby available to `/queue duos`. Omit it for singles.

The default configuration contains ten lobbies, including two duo lobbies. Add or remove list entries as needed; ensure each waiting world exists and each game-world name is unique. Regular lobbies start a 60-second countdown at their minimum player count, with warnings at 30 and 10 seconds. A full lobby starts in 10 seconds. Players who cannot enter a lobby are added to the relevant global waiting queue; use `/leaveq` to leave it.

The plugin also saves a shared return spawn when an administrator uses `/hoplitesetspawn`. Queue leavers and match participants return to that location. If no spawn has been set, match participants return to their lobby world's spawn.

Lobby centers are saved separately under `lobby-centers` in `config.yml`, so running `/lobbycenter <id>` does not alter the `lobbies` list or its player limits. `maxPlayers` and `minPlayers` remain independently editable on each lobby entry.

## Commands

| Command | Description |
| --- | --- |
| `/queue` or `/q` | Join an available singles lobby or the singles waiting queue. |
| `/queue duos` or `/q duos` | Join an available duo lobby or the duo waiting queue. |
| `/leaveq` | Leave a lobby or global waiting queue. |
| `/hoplitesetspawn` | Set the shared return spawn to your current position and facing. |
| `/lobbycenter <id>` | Save the current location as a queue lobby center. Requires `hoplite.lobbycenter`. |
| `/party create` | Create a party. |
| `/party invite <player>` | Invite an online player. Only the party owner can invite. |
| `/party join <owner>` | Accept an outstanding invite from an online party owner. |
| `/party leave` | Leave a party as a non-owner. |
| `/party disband` | Disband your party as its owner. |
| `/party game` | Have the party owner start a private game for the online party members. |
| `/party broadcast` | Broadcast the party announcement; requires permission. |
| `/broadcast <link> <youtube\|twitch>` | Broadcast that you are live; requires permission. |
| `/teamchat ingame` | Toggle team-only chat during a duo match. Run it again to return to global chat. |
| `/elo` | Show your ELO rating. |
| `/eloleader` | Show the top 10 ELO leaderboard. |
| `/cbal` | Show your coin balance. |
| `/cleader` | Show the top 10 coin leaderboard. |
| `/coingive <player> <amount>` | Give coins to an online player. |
| `/k` | Open the kits menu. Left-click to buy/load a kit; right-click to preview it. |
| `/k create <name> <coins>` | Create a permanent kit from your current inventory. Requires `hoplite.kit.admin`. |
| `/k edit <name> <new-price>` | Change a kit's price. Requires `hoplite.kit.admin`. |

Party invites and memberships are held in memory and are cleared when the server restarts. Party games require at least two online members, and all members must leave existing queues or matches before the owner can start one. The game reserves an empty lobby for the party, starts after 10 seconds, and does not allow other players to join. Party games do not award coins or ELO.

## Match Behavior

When a game world is ready, each player or duo team rides a visible hot-air balloon carrier for a 100-block route. Players can sneak to depart early and receive Slow Falling until they land or enter water.

In regular matches, duo teammates are announced before the game starts and shown with green nametags during the match. Friendly fire between teammates is disabled. Active matches have a five-minute grace period and a world border that begins at 1,000 blocks and shrinks over time. After 10 minutes, players receive a compass that tracks the nearest opponent.

Normal-match kills award 5 coins, and match winners receive the configured in-game awards handled by the plugin. These awards are disabled in private party matches.

## Permissions

| Permission | Purpose | Default |
| --- | --- | --- |
| `hoplite.broadcast` | Use `/broadcast`. | Operators |
| `hoplite.party.broadcast` | Use `/party broadcast`. | Operators |
| `hoplite.lobbycenter` | Use `/lobbycenter <id>`. | Operators |
| `hoplite.kit.admin` | Use `/k create` and `/k edit`. | Operators |

Grant these nodes through your permissions plugin, for example LuckPerms. The party broadcast permission can be granted independently from the live broadcast permission.

## Broadcast Message Configuration

Edit the `messages` section in `plugins/HoplitePlugin/config.yml`. Ampersand color codes are supported.

```yaml
messages:
  broadcast: "&6[Live] &e%player% IS LIVE ON %platform%: &b%link%"
  party-broadcast: "&d[Party] &e%owner%'s party members: &f%members%"
```

Supported template placeholders:

- `broadcast`: `%player%`, `%platform%`, `%link%`
- `party-broadcast`: `%owner%`, `%members%`

`/broadcast` accepts HTTP or HTTPS links hosted on YouTube (`youtube.com` or `youtu.be`) or Twitch (`twitch.tv`), and the platform argument must match the link host.

## PlaceholderAPI

When PlaceholderAPI is installed, Hoplite registers the following placeholders:

| Placeholder | Result |
| --- | --- |
| `%hoplite_elo%` | The viewing player's ELO. |
| `%hoplite_coins%` | The viewing player's coin balance. |
| `%hoplite_inqueue%` | `true` if the player is in either global waiting queue. |
| `%hoplite_queue_status%` | `singles`, `duos`, or `none`. |
| `%hoplite_ingame%` | `true` if the player is in an active match. |
| `%eloleader_1%` through `%eloleader_10%` | Ranked ELO entry as `player - rating`. |
| `%cleader_1%` through `%cleader_10%` | Ranked coin entry as `player - amount coins`. |

Leaderboard placeholders return an empty value when that rank has no entry. PlaceholderAPI is optional at runtime; without it, the plugin's commands and games continue to work, but these placeholders are unavailable.
