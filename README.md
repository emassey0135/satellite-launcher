# The Satellite Launcher

The home screen of [Satellite](https://github.com/emassey0135/satellite-setup), for the Orbit Speak and Orbit Player.
It works like Orbit's own home screen, with the same keys, speaking through Orbit's screen reader, but shows every app
on the device, in folders you can arrange yourself. It also sets Satellite up on the device, and keeps it up to date.

To set Satellite up, use [Satellite Setup](https://github.com/emassey0135/satellite-setup), which explains the whole
project.

## The home screen

- **Every app.** Orbit's own apps are grouped as on Orbit's home screen, and apps that speak for themselves through
  Orbit's screen reader are beside them. Other Android apps, which Backtalk reads, are in the Android Apps folder.
  Newly installed apps appear by themselves.
- **Your own arrangement.** With the context menu (Space with M on the Speak, MENU on the Player), you can move items
  up and down or into other folders, make, rename and delete folders, and hide apps.
- **Built-in items:** the time and date, the battery, recent apps, notifications, Orbit's screen reader settings and
  user guide, the Plus license, formatting the SD card, Satellite Updates, and the power options.
- **Plus:** on the Orbit Speak, Orbit's Plus apps show only with an active Plus license, as on Orbit's home screen.

### Keys

| | Orbit Speak | Orbit Player |
| --- | --- | --- |
| Move | Up and Down, or Space with dot 1 and Space with dot 4 | Up and Down |
| First and last item | Hold Up and Down, or Space with L and Space with dots 4, 5 and 6 | Hold Up and Down |
| Open | Right, OK, Select, or dot 8 | Right or OK |
| Back | Left, dot 7, or Space with E | Left or BACK |
| Context menu | Space with M | MENU |
| Top of the home screen | Space with Z, or Space with all six dots | HOME |
| Recent apps | Space with R | |
| Notifications | Space with N | |

**Finding an item by typing:** on the Speak, type the first letter of an item to go to the next item that starts with
it, or type several letters quickly to find the item that starts with them. On the Player, type an item's number to
open it (0 alone is the tenth), or hold star to type letters with T9 instead.

The power button opens the power options over any app, with the same keys.

## Setup and updates

Satellite Setup installs the launcher and gives it root, then starts its setup (`SetupService`), which does the rest on
the device: it repairs Magisk if its own files are missing, turns on Zygisk, installs LSPosed, the Satellite hook and
Backtalk, makes Backtalk use the Orbit's keys, and makes the launcher the home app. Every step checks first and changes
only what isn't already as it should be, so running setup again repairs a device. It restarts the device when it needs
to, and carries on by itself afterwards. Before it changes anything, it saves how the device was as a script,
`/data/adb/satellite/restore.sh`, which puts it back; and a small Magisk module turns LSPosed off if the device can't
finish starting.

Satellite Updates checks the latest releases of the launcher and the hook on GitHub, and installs newer ones through
setup.

## Building

The launcher builds with Gradle and JDK 21: `./gradlew assembleDebug`, and `./gradlew testDebugUnitTest` for the
tests. GitHub Actions builds each push, signs it with Satellite's release key, and publishes it as the "dev"
pre-release; each version tag is published as a release, which setup and Satellite Updates install. A build signed
with another key can't update one signed with the release key, or be updated by it.

## License

The Satellite Launcher is under the [Apache License 2.0](LICENSE).
