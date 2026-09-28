# SuperiorStory

First-join intro for Superior (Forge 1.20.1 / 47.4.20).

- Text lives in `src/main/resources/assets/superiorstory/lang/en_us.json` (can also be overridden by a resource pack, no rebuild needed).
- Sounds: `assets/superiorstory/sounds/*.ogg`.
- Commands (op): `/superiorstory replay [players]` plays it again now; `/superiorstory reset [players]` plays it on next join.
- Hands off to Puffish Skills by passing the real `key.puffish_skills.open` key press through; no compile dependency on Puffish.
- Build: standard ForgeGradle 6 (`gradlew build`), output in `build/libs`.
