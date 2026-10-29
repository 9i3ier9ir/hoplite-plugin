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

Player ELO and coin balances are stored in `plugins/HoplitePlugin/elo.yml` and `plugins/HoplitePlugin/coins.yml`.

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

## Commands

| Command | Description |
| --- | --- |
| `/queue` or `/q` | Join an available singles lobby or the singles waiting queue. |
| `/queue duos` or `/q duos` | Join an available duo lobby or the duo waiting queue. |
| `/leaveq` | Leave a lobby or global waiting queue. |
| `/hoplitesetspawn` | Set the shared return spawn to your current position and facing. |
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
| `/k` | Open the kits menu. Left-click a kit to buy it; right-click to preview it. |

Party invites and memberships are held in memory and are cleared when the server restarts. Party games require at least two online members, and all members must leave existing queues or matches before the owner can start one. The game reserves an empty lobby for the party, starts after 10 seconds, and does not allow other players to join. Party games do not award coins or ELO.

## Match Behavior

When a game world is ready, the plugin selects a safe solid landing point for each player. Each player starts 130 blocks above and 50 blocks horizontally away from their target, riding a guided chicken. The chicken is removed on landing. Players cannot dismount during the drop and are protected from fall damage until they land.

In regular matches, duo teammates are announced before the game starts and shown with green nametags during the match. Friendly fire between teammates is disabled. Active matches have a five-minute grace period and a world border that begins at 1,000 blocks and shrinks over time. After 10 minutes, players receive a compass that tracks the nearest opponent.

Normal-match kills award 5 coins, and match winners receive the configured in-game awards handled by the plugin. These awards are disabled in private party matches.

## Permissions

| Permission | Purpose | Default |
| --- | --- | --- |
| `hoplite.broadcast` | Use `/broadcast`. | Operators |
| `hoplite.party.broadcast` | Use `/party broadcast`. | Operators |

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
