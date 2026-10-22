<div align="center">

<img src="publish/icon-512.png" width="128" alt="Survival Cinema">

# Survival Cinema

**Your survival world, turned into a movie — by itself.**

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.11-62B47A?style=for-the-badge&logo=minecraft&logoColor=white)](https://minecraft.net)
[![Fabric](https://img.shields.io/badge/Fabric-0.18%2B-DBD0B4?style=for-the-badge)](https://fabricmc.net)
[![Flashback](https://img.shields.io/badge/Flashback-0.39.10%2B-8A5CF5?style=for-the-badge)](https://modrinth.com/mod/flashback)
[![Java](https://img.shields.io/badge/Java-21-E76F00?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue?style=for-the-badge)](LICENSE)

[What it does](#-what-it-does) · [Gallery](#-gallery) · [Install](#-install) · [How it works](#-how-it-works) · [Build](#-build-from-source) · [Русский](#-по-русски)

<img src="publish/gallery/05-film-elytra.png" width="860" alt="A frame from a film: the first elytra flight">

</div>

Play the way you always do. Survival Cinema records in the background, notices what matters — your first diamonds,
the night you nearly died, the moment everyone started yelling on voice chat — and when you are ready it shoots and
edits a real film of it: cinematic camera moves, chapter cards, music, a timelapse of your base growing and end credits
with your world's stats. It plays right inside Minecraft, in a little cinema, and it is a normal MP4 you can share.

## ✨ What it does

|  |  |
|---|---|
| 🎬 **Finds the best moments** | Advancements, first trips to the Nether and the End, boss fights and the Ender Dragon, deaths and near-deaths, kill streaks, rare finds, building sprees, elytra flights, big falls, the first night. |
| 🎙️ **Voices first** | A big moment *and* people shouting beats everything else. Quiet talks get a two-shot; a talk after the dragon closes the film. Voices stay at normal speed over slow motion. |
| 🎥 **Cinematic cameras** | Orbits, side shots, pull-backs, over-the-shoulder, two-shots and slow motion — planned along your recorded path, kept out of walls and leaves, following you down a mine. |
| 📖 **Chapters** | "Day 1", "Days 4–6", "The Nether", "The End" — title cards in Minecraft's own font. |
| 🏠 **Your base, growing** | The place you kept coming back to is found automatically and shown going up, day by day, as a timelapse. |
| 🎵 **Music** | Minecraft's soundtrack from your game files or your own tracks, ducked under voices and loud moments. |
| 🏆 **Credits** | Days survived, blocks placed, mobs defeated, diamonds found, deaths — and everyone who played with you. |
| 🍿 **In-game cinema** | The film plays the moment it is ready: pause, seek, volume. Stop shooting with Esc and continue later — even after a crash. |

## 🖼️ Gallery

<table>
  <tr>
    <td><img src="publish/gallery/01-film-offer.png" alt="The film offer after the Ender Dragon"></td>
    <td><img src="publish/gallery/04-film-intro.png" alt="The film's title"></td>
  </tr>
  <tr>
    <td align="center"><sub>After the Ender Dragon, the film is offered</sub></td>
    <td align="center"><sub>The opening title</sub></td>
  </tr>
  <tr>
    <td><img src="publish/gallery/06-film-chapter-nether.png" alt="A chapter card"></td>
    <td><img src="publish/gallery/07-film-base-timelapse.png" alt="The base timelapse"></td>
  </tr>
  <tr>
    <td align="center"><sub>Chapter cards in Minecraft's font</sub></td>
    <td align="center"><sub>Your base, growing day by day</sub></td>
  </tr>
  <tr>
    <td><img src="publish/gallery/03-shooting-screen.png" alt="The shooting screen"></td>
    <td><img src="publish/gallery/08-film-credits.png" alt="End credits"></td>
  </tr>
  <tr>
    <td align="center"><sub>One calm screen while the film is shot</sub></td>
    <td align="center"><sub>End credits with your world's stats</sub></td>
  </tr>
</table>

## 📦 Install

1. Minecraft **1.21.11** with **Fabric Loader 0.18+** and **Fabric API**.
2. **[Flashback](https://modrinth.com/mod/flashback) 0.39.10** or a newer 0.39.x — the replay engine Survival Cinema films with.
3. Drop the Survival Cinema jar into `mods`.
4. Optional: [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) for voices, [Mod Menu](https://modrinth.com/mod/modmenu) for the settings button.

Client-side only — works on any server, vanilla or modded, and with Sodium, Iris, Lithium and the rest of Fabulously Optimized.

> [!NOTE]
> On Windows 11 (and Windows 10 build 20348+) calls in **Discord, Telegram, TeamSpeak, Zoom, WhatsApp and Skype** can be
> recorded too, plus your microphone. Voices are only recorded in worlds where you tick *Record voices* — and your friends
> see no sign in Discord, so tell them.

## 🧭 How it works

```mermaid
flowchart LR
    A["🎮 You play"] --> B["● REC<br/>Flashback replay<br/>+ events + camera path<br/>+ voices"]
    B --> C["🧠 Director<br/>picks the moments,<br/>plans the story"]
    C --> D["🎥 Camera<br/>plans every shot<br/>around the blocks"]
    D --> E["🎞️ Flashback renders<br/>each clip from<br/>the replay"]
    E --> F["✂️ Editor<br/>cards, music, voices,<br/>timelapse, credits"]
    F --> G["🍿 MP4 in the<br/>in-game cinema"]
```

| Module | What lives there |
|---|---|
| [`src/main`](src/main/java/com/survivalcinema) | The director, without Minecraft: event scoring, the story planner, the edit decision list, camera paths and the occlusion solver, the base finder and timelapse planner. Pure Java, fully unit-tested. |
| [`src/client`](src/client/java/com/survivalcinema/client) | Everything in the game: recording, watchers for events and voices, the render pipeline that drives Flashback, the film editor (FFmpeg via JavaCV), the cinema and all the screens. |
| [`src/test`](src/test/java/com/survivalcinema) | Unit tests, including camera tests on real terrain saved from films. |
| [`src/gametest`](src/gametest/java/com/survivalcinema/gametest) | End-to-end tests in a real client — among them a **bot that plays a whole survival** (house, mine, Nether, elytra, the dragon) with real keys and makes the film. |

## 🛠️ Build from source

Requires JDK 21.

```bash
./gradlew build
```

The jar lands in `build/libs/`. Other useful tasks:

| Command | What it does |
|---|---|
| `./gradlew runClient` | A dev client with Flashback and Simple Voice Chat. |
| `./gradlew test` | The unit tests. |
| `./gradlew runPlayTest` | The bot plays two sessions, kills the dragon and makes a film (~15 min, opens a game window). |
| `./gradlew runFilmTest` | Shoots a short film from the recordings in `run/`. Add `-Preshoot=<world>/<film> -Pclips=3,5` to shoot chosen clips of a film again. |

## 🔒 Privacy

Everything stays on your computer. Survival Cinema uploads nothing and has no servers. It asks before recording each
world, and *Disk space* shows and deletes your recordings and films.

## 🇷🇺 По-русски

**Survival Cinema** сам превращает твоё выживание в фильм. Играешь как обычно, мод записывает в фоне и замечает главное:
первые алмазы, ночь, когда ты чуть не умер, момент, когда все заорали в голосовом. Когда захочешь, он снимает и монтирует
настоящий фильм на 5, 10, 15 или 20 минут: кинематографичная камера, главы, музыка, таймлапс базы, титры со статистикой
мира. Фильм играет прямо в игре, в маленьком кинотеатре, и сохраняется обычным MP4. Нужны Fabric, Fabric API и
[Flashback](https://modrinth.com/mod/flashback). Интерфейс на английском и русском.

---

<div align="center">
<sub>
Survival Cinema is an addon for <a href="https://modrinth.com/mod/flashback">Flashback</a> by Moulberry, which does the recording and rendering.<br>
Not made by or affiliated with the Flashback author or Mojang. Released under the <a href="LICENSE">MIT license</a>.
</sub>
</div>
