<div align="center">

<img src="docs/readme/header.svg" width="100%" alt="Survival Cinema — your survival world, turned into a movie, by itself">

<img src="docs/readme/hotbar.svg" width="100%" alt="Minecraft 1.21.11 · Fabric 0.18+ · Fabric API · Flashback · Java 21 · MIT">

<a href="#how-it-works"><img src="docs/readme/button-how.svg" width="200" alt="How it works"></a>
<a href="#gallery"><img src="docs/readme/button-gallery.svg" width="200" alt="Gallery"></a>
<a href="#install"><img src="docs/readme/button-install.svg" width="200" alt="Install"></a>
<a href="#build"><img src="docs/readme/button-build.svg" width="200" alt="Build"></a>

<br><br>

<img src="publish/gallery/05-film-elytra.png" width="100%" alt="A frame from a film: the first elytra flight">

</div>

**Play. It makes the movie.** Survival Cinema records your survival in the background and turns the best moments into
a real film: your first diamonds, the night you nearly died, the moment everyone started yelling on voice chat, the
dragon. Cameras, chapters, music, a timelapse of your base and end credits — edited by itself and played in a little
cinema right inside Minecraft. It's a normal MP4 you can share.

<img src="docs/readme/divider.svg" width="100%" alt="">

<a id="how-it-works"></a>
<img src="docs/readme/title-how.svg" width="100%" alt="How it works">

1. **Play** the way you always do. A small ● REC sits in the corner.
2. **Kill the dragon** — or press the clapperboard in the pause menu any time.
3. **Pick 5, 10, 15 or 20 minutes** and press *Start shooting*.
4. **Watch.** The film plays by itself when it's ready.

Friends on Simple Voice Chat or a Discord call? Their voices go into the film, and the loudest moments get the spotlight.

<a id="gallery"></a>
<img src="docs/readme/title-gallery.svg" width="100%" alt="Gallery">

<table>
  <tr>
    <td><img src="publish/gallery/01-film-offer.png" alt="The film offer after the Ender Dragon"></td>
    <td><img src="publish/gallery/04-film-intro.png" alt="The film's title"></td>
  </tr>
  <tr>
    <td><img src="publish/gallery/06-film-chapter-nether.png" alt="A chapter card"></td>
    <td><img src="publish/gallery/07-film-base-timelapse.png" alt="The base timelapse"></td>
  </tr>
</table>

<a id="install"></a>
<img src="docs/readme/title-install.svg" width="100%" alt="Install">

1. Minecraft **1.21.11** with **Fabric Loader** and **Fabric API**.
2. **[Flashback](https://modrinth.com/mod/flashback)** — the replay mod Survival Cinema films with.
3. Drop the Survival Cinema jar into `mods`.
4. Optional: [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat), [Mod Menu](https://modrinth.com/mod/modmenu).

<p align="center"><a href="https://modrinth.com/mod/flashback"><img src="docs/readme/button-download.svg" width="400" alt="Download Flashback first"></a></p>

Client-side only, works on any server. Everything stays on your computer.

<a id="build"></a>
<img src="docs/readme/title-build.svg" width="100%" alt="Build from source">

Requires JDK 21. The jar lands in `build/libs/`.

```bash
./gradlew build
```

`./gradlew runPlayTest` lets a bot play a whole survival and make the film by itself.

<img src="docs/readme/footer.svg" width="100%" alt="Addon for Flashback by Moulberry. Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.">

<p align="center"><sub>Released under the <a href="LICENSE">MIT license</a>.</sub></p>
