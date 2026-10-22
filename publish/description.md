# Survival Cinema

**Your survival world, turned into a movie — by itself.**

Play the way you always do. Survival Cinema records in the background, notices what matters (your first diamonds, the night you nearly died, the creeper that blew up your house, the moment everyone started yelling on voice chat) and, when you are ready, shoots and edits a real film of it: cinematic camera moves, chapter cards, music, a timelapse of your base growing, and end credits with your world's stats. It plays right inside Minecraft, in a little cinema, and it is a normal MP4 you can share.

![The film offer after the Ender Dragon](https://cdn.modrinth.com/REPLACE/offer.png)

## How it works

1. **Join a world.** Survival Cinema asks once whether to record it (and whether to record voices). "Always in this world" and you never think about it again.
2. **Play.** A small ● REC sits in the corner. If you go AFK, recording pauses by itself.
3. **Make the film.** After the Ender Dragon it offers to make the film. Any time before that: pause menu → the clapperboard button. Pick 5, 10, 15 or 20 minutes and press *Start shooting*.
4. **Watch.** Shooting runs under a calm "Shooting the film…" screen (the replay editor never shows). Then the film starts playing by itself.

## What goes into the film

- **The best moments, in order.** Advancements, first trips to the Nether and the End, boss fights, deaths and near-deaths, kill streaks, rare finds, building sprees, elytra flights, big falls, sleeping through the first night…
- **Your voices first.** A moment where something big happens *and* people are shouting beats everything else. Quiet conversations between friends get their own two-shot scene, and a talk after the dragon closes the film.
- **Cinematic cameras.** Orbits, side shots, pull-backs, two-shots, slow motion on the big moments — with your friends' "WE FINALLY KILLED IT!" still at full speed over the slowed picture. The camera follows your recorded path, stays out of walls and leaves, and never jumps ahead of you.
- **Chapters.** "Day 1", "Days 4–6", "The Nether", "The End" — title cards in Minecraft's own font.
- **Your base, growing.** The place you kept coming back to and building is found automatically, and a timelapse shows it go up from bare ground, day by day, shot from its front door at noon.
- **Music.** Minecraft's own soundtrack from your game files, or your own tracks from `survivalcinema/music`. Music never plays over talking and ducks under loud moments.
- **Credits.** Days survived, hours played, blocks placed, mobs defeated, diamonds found, deaths — and everyone who played with you.

## Voices

- **Simple Voice Chat** (optional): your and your friends' voices are recorded with the game (through Flashback).
- **Discord, Telegram, TeamSpeak, Zoom, WhatsApp, Skype** (Windows 10 20348+ / Windows 11): if your friends are in a call instead, the call is recorded too, plus your microphone. Each app can be switched off in *Voice settings*, where live meters show what is heard.
- **Your own commentary**: playing alone? Turn on *My commentary* and talk over your game.

Voices are only recorded when you tick "Record voices" for that world. Your friends see no recording sign in Discord — tell them.

## Privacy

Everything stays on your computer. Survival Cinema uploads nothing and has no servers.

- It asks before recording each world, and remembers your answer (change it any time in the settings).
- Recordings are Flashback replays (`.minecraft/flashback/replays`) plus small files of what happened (`.minecraft/survivalcinema/sessions`); call voices and a copy of the voice chat (for slow motion) are saved there as Opus audio.
- *Disk space* (in *Make a film*) shows what your recordings and films take and deletes them; *Delete recordings after the film* frees the disk automatically once a film is made.

## Requirements

- Minecraft **1.21.11**, Fabric Loader 0.18+, Fabric API
- **[Flashback](https://modrinth.com/mod/flashback) 0.39.10 or newer 0.39.x** — the replay engine Survival Cinema films with (required)
- [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) — optional, for voice chat
- [Mod Menu](https://modrinth.com/mod/modmenu) — optional, for the settings button

Works with Sodium, Iris, Lithium and the rest of Fabulously Optimized. Client-side only: works on any server, vanilla or modded.

## Good to know

- **Shooting takes a while.** Every clip is rendered from the replays: roughly as long as the film in *Draft* (720p) and several times longer in *Cinema* (1080p 60 fps). You can stop at any time (Esc) and continue later — nothing is lost, even after a crash.
- **On a server**, shooting opens the replays, so you leave the server while the film is made and are taken back afterwards.
- **Music and YouTube**: Minecraft's soundtrack can get a Content ID claim. Use your own tracks or *No music* if that matters.
- Call recording is Windows-only, and all friends in a call share one track.
- Survival Cinema checks at startup that the installed Flashback has everything it needs. If a Flashback update changes something, recording turns itself off and tells you, instead of breaking your game.

## FAQ

**Do I need to set anything up?** No. Install it with Flashback and Fabric API and play.

**Can I make a film of a world I played before installing it?** No — it needs its own recordings of the moments.

**Will it record my friends without them knowing?** In voice chat, Simple Voice Chat shows everyone the recording. In Discord and other apps there is no such sign: Survival Cinema reminds you to tell them every time it starts recording a call.

**Where is my film?** `.minecraft/survivalcinema/films/<world>/<date>/<world>.mp4` — or press *Open folder* when it is done.

---

Survival Cinema is an addon for [Flashback](https://modrinth.com/mod/flashback) by Moulberry, which does the recording and rendering. It is not made by or affiliated with the Flashback author or Mojang. MIT licensed.
