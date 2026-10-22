# Как выложить Survival Cinema

Всё для страницы мода лежит в этой папке. Загружать будешь ты, по шагам ниже.

## Что загружать

| Что | Где лежит |
|---|---|
| Сам мод | `build/libs/survival-cinema-1.0.0-beta.1+1.21.11.jar` (без `-sources` и без `-gametest`) |
| Иконка 512×512 | `publish/icon-512.png` |
| Описание (англ.) | `publish/description.md` (копировать как есть, Modrinth и CurseForge понимают Markdown) |
| Список изменений | `CHANGELOG.md`, раздел 1.0.0-beta.1 |
| Скриншоты для галереи | `publish/gallery/*.png` |

Перед загрузкой собери свежий jar: `gradlew build`.

## Modrinth (modrinth.com → Create a project)

- **Name:** Survival Cinema
- **URL / slug:** `survival-cinema` (на 20.10.2026 свободен)
- **Summary** (одна строка): *Turns your survival world into a movie by itself: the best moments, cinematic cameras, your voices, music and credits. Addon for Flashback.*
- **Project type:** Mod. **Categories:** Utility, Social. **Environment:** Client — required, Server — unsupported.
- **License:** MIT.
- **Icon:** `icon-512.png`. **Description:** содержимое `description.md`. В нём есть ссылка на картинку `https://cdn.modrinth.com/REPLACE/offer.png`: после загрузки скриншотов в галерею замени её на адрес картинки из галереи или просто удали эту строку.
- **Gallery:** все 12 файлов из `publish/gallery` по порядку номеров; `05-film-elytra.png` или `07-film-base-timelapse.png` отметь Featured (самые красивые). Кадры фильма сняты ботом из автотеста (ник Player853), экраны — английская версия игры.
- **Version:**
  - Version number: `1.0.0-beta.1+1.21.11`, Version name: `1.0.0-beta.1`, channel **Beta**
  - Loaders: Fabric. Game versions: 1.21.11
  - Dependencies: **Flashback — required**, **Fabric API — required**, Simple Voice Chat — optional, Mod Menu — optional
  - Changelog: текст раздела 1.0.0-beta.1 из `CHANGELOG.md`

## CurseForge (authors.curseforge.com → Create project)

- Тот же Name, Summary, описание, иконка, лицензия MIT.
- Categories: *Utility & QoL*, *Cosmetic* (или *Map and Information*).
- Файл: тот же jar, Release type **Beta**, Game version 1.21.11, Modloader Fabric, Environment Client.
- Relations: **Flashback — Required Dependency**, **Fabric API — Required Dependency**, Simple Voice Chat — Optional, Mod Menu — Optional.

## Перед публикацией

1. **Автор.** В `src/main/resources/fabric.mod.json` поле `"authors": []` пустое. Скажи ник, я впишу (его видно в Mod Menu).
2. **Ссылки.** Если заведёшь GitHub для исходников или Discord, их можно добавить в `fabric.mod.json` (`contact`) и на страницу.
3. **Flashback.** Напиши Moulberry в Discord Flashback (discord.gg/mAYDZ9c7rD), что выходит аддон. Это вежливо, и он будет знать, что его внутренности кто-то использует.
4. **Проверка в игре.** Перед бетой стоит один раз пройти путь самому: записать немного игры с другом в голосовом чате или Discord и собрать фильм.

## Обновление мода потом

1. Поднять `mod_version` в `gradle.properties` (например, `1.0.0-beta.2`).
2. Дописать раздел в `CHANGELOG.md`.
3. `gradlew build` → загрузить новый jar как новую версию на обоих сайтах.
