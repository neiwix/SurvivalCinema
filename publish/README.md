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
- **Summary** (одна строка): *Play survival — it turns your best moments into a movie by itself. Addon for Flashback.*
- **Project type:** Mod. **Categories:** Utility, Social. **Environment:** Client — required, Server — unsupported.
- **License:** MIT.
- **Icon:** `icon-512.png`. **Description:** содержимое `description.md` как есть.
- **Gallery:** все 12 файлов из `publish/gallery` по порядку номеров; `05-film-elytra.png` или `07-film-base-timelapse.png` отметь Featured (самые красивые). Кадры фильма сняты ботом из автотеста, экраны — английская версия игры.
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

1. **Проверка в игре.** Один раз пройти путь самому: немного игры с другом в голосовом чате или Discord, затем собрать фильм.
2. **Своя сборка.** Хотя бы один короткий фильм в Fabulously Optimized (Sodium, Iris): мод должен с ними работать.
3. **Исходники.** Репозиторий публичный: в поля Source и Issues на обоих сайтах — https://github.com/neiwix/SurvivalCinema и …/issues.
4. **Flashback.** Напиши Moulberry в Discord Flashback (discord.gg/mAYDZ9c7rD), что выходит аддон. Это вежливо, и он будет знать, что его внутренности кто-то использует.
5. **Jar.** `gradlew build`, загружать `build/libs/survival-cinema-1.0.0-beta.1+1.21.11.jar`.

## Обновление мода потом

1. Поднять `mod_version` в `gradle.properties` (например, `1.0.0-beta.2`).
2. Дописать раздел в `CHANGELOG.md`.
3. `gradlew build` → загрузить новый jar как новую версию на обоих сайтах.
