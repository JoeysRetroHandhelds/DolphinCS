# DolphinCS

DolphinCS is an unofficial fork of [Dolphin](https://github.com/dolphin-emu/dolphin), the
GameCube/Wii emulator. It is **not affiliated with the Dolphin Emulator project**.

The only thing this fork changes is **where Dolphin stores its user data on Android**. Everything
else is identical to upstream Dolphin.

## What's different

A settings toggle lets you choose where Dolphin's user data (settings, saves, game paths, etc.)
lives:

- **Scoped Storage** (default, same as official Dolphin)
- **Internal Storage** (`/sdcard/dolphin-emu`)
- **SD Card**, if a removable card is detected

Switching locations offers to migrate your existing data to the new location automatically.

Nothing else is added, removed, or changed.

## Releases

Releases here are built automatically whenever Dolphin publishes a new stable release. This fork
tracks upstream Dolphin's release tags, not individual commits or dev builds. See the
[Releases page](https://github.com/JoeysRetroHandhelds/DolphinCS/releases) for builds.

## License

DolphinCS is licensed under the GNU GPL version 2 (or any later version), same as upstream
Dolphin. See [COPYING](COPYING) for the full license text.

## Official Dolphin

For the official, upstream Dolphin (all platforms), see [dolphin-emu.org](https://dolphin-emu.org/)
or the [dolphin-emu/dolphin](https://github.com/dolphin-emu/dolphin) repository.
