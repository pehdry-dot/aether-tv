# Signing and updates

Android installs an update only when it is signed with the same key as the installed app. When the key matches, the
installed data (profiles, favorites, wallpapers, settings) is kept. Settings ▸ Updates relies on this rule. It refuses
any release whose key differs from the installed one, before downloading it and again before installing.

## What is installed now (evidence, 2026-11-23)

| Build | Signing certificate | SHA-256 of the certificate |
|---|---|---|
| v1.1.0, the GitHub release asset `AetherTV-1.1.0.apk` (versionCode 2) | `C=US, O=Android, CN=Android Debug` | `1c:e2:ca:82:ff:95:fb:a2:3e:f6:2d:97:a3:d2:ba:95:74:6c:ed:3a:27:8d:c5:5f:5c:a7:49:66:c0:ce:68:3e` |
| v1.2.0 and later (release key) | `CN=Aether TV, O=pehdry-dot, C=US` | `5e:b0:39:e7:1b:29:62:1b:60:4d:5a:9e:17:4d:e3:a1:9b:77:17:c5:18:c6:a6:9b:cd:1f:6b:65:3e:c1:19:91` |

1.1.0 was signed with the debug keystore of the machine that built it (`~/.android/debug.keystore`). That keystore is
not in Git, and it is not in the build environment that produced 1.2.0, so 1.2.0 cannot be signed with it.

**Unknown: whether that machine still has that keystore.** On the computer that built 1.1.0, run:
```bash
keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA256
```
If it prints `1C:E2:CA:82:…:68:3E`, you still have the 1.1.0 key. Two choices then:
- **Option 1: keep the debug key (no reinstall).** Build 1.2.0 with that keystore and install it over 1.1.0:
  `./gradlew :app:assembleRelease -Paether.debugSignedRelease=true`. Every later release must use the same debug key,
  and the release workflow would need that keystore as its secret.
- **Option 2: switch to the release key (recommended).** Migrate once, below.

To check which key the TV has, run `adb shell dumpsys package com.aether.tv | grep -A1 signatures`. You can also
read it in Aether 1.2.0 under Settings ▸ Updates ▸ Installed.

## One-time migration to the release key

**Why a reinstall is needed:** Android has no way to move an installed app to a new key without uninstalling it, and
uninstalling deletes the app's data. The steps below take your data off the TV first and put it back afterwards.
Run them from a computer with adb on the same network as the TV.

The backup in step 1 is the 1.1.0 kind. It keeps profiles, settings, favorites, devices, games, categories, hidden
apps and renames. Pictures stored in the TV folder (`Android/data/com.aether.tv/files/…`) come back through step 2.
Pictures that 1.1.0 imported into its private storage, from a web address or the file picker, can't be copied off
the TV without root. Choose those again after the restore; Aether lists them as missing.

1. **On the TV, in Aether 1.1.0:** Settings ▸ Advanced ▸ **Back up to TV storage**.
2. **Copy Aether's TV folder to the computer:**
   ```bash
   adb connect <TV-IP>:5555
   ```
   ```bash
   adb pull /sdcard/Android/data/com.aether.tv/files aether-files
   ```
3. **Aether-only mode only (Amazon's launcher disabled):** turn Amazon's launcher back on first, so the TV still has
   a Home screen while Aether is briefly uninstalled:
   ```bash
   adb shell pm enable com.amazon.tv.launcher
   ```
4. **Replace the app.** The `uninstall` command deletes Aether's data on the TV. Your copy from steps 1 and 2 is on
   the computer.
   ```bash
   adb uninstall com.aether.tv
   ```
   ```bash
   adb install AetherTV-1.2.0.apk
   ```
5. **Open Aether once** (from Your Apps), then put the folder back:
   ```bash
   adb push aether-files/. /sdcard/Android/data/com.aether.tv/files/
   ```
6. **On the TV:** Settings ▸ Advanced ▸ **Restore a backup** ▸ choose the newest `aether-backup-…`.
7. **Grant again** what Android dropped with the uninstall. Only the lines you used before are needed:
   ```bash
   adb shell cmd notification allow_listener com.aether.tv/com.aether.tv.platform.AetherNotificationListener
   ```
   ```bash
   adb shell pm grant com.aether.tv android.permission.READ_TV_LISTINGS
   ```
   ```bash
   adb shell appops set com.aether.tv REQUEST_INSTALL_PACKAGES allow
   ```
   The last line lets Settings ▸ Updates install future updates without the "Install unknown apps" detour.
8. **Aether-only mode only:** make Aether the Home app again and switch Amazon's launcher off:
   ```bash
   adb shell cmd package set-home-activity com.aether.tv/com.aether.tv.MainActivity
   ```
   ```bash
   adb shell pm disable-user --user 0 com.amazon.tv.launcher
   ```
   If you used the Home button service instead, turn it on again with the commands under Settings ▸ Behavior.

If anything goes wrong in steps 4–8, `adb shell pm enable com.amazon.tv.launcher` restores Amazon's Home screen.
Your data is still in `aether-files` on the computer.

After this, every release built by the workflow is signed with the same key, and **Settings ▸ Updates installs it from
the couch** with your data kept.

## Configuring the key

The key is **never committed**. `.gitignore` excludes `*.jks`, `*.p12`, `*.keystore` and `keystore.properties`.

**Local builds:** create `keystore.properties` in the project root:
```properties
storeFile=/absolute/path/to/aether-release.p12
storePassword=…
keyAlias=aether
keyPassword=…
```
Then:
```bash
./gradlew :app:assembleRelease
```
A release build with no key configured fails with an explanation. It never falls back to a debug key unless you ask
for one with `-Paether.debugSignedRelease=true`.

**GitHub Actions** (`.github/workflows/release.yml`):
- **Secrets:** `AETHER_KEYSTORE_B64` (base64 of the `.p12`), `AETHER_KEYSTORE_PASSWORD`, `AETHER_KEY_ALIAS` and
  `AETHER_KEY_PASSWORD`.
- **Variable:** `AETHER_SIGNER_SHA256`, set to the certificate SHA-256 above, colons optional.

The workflow fails if the APK is signed with any other key.

**Back up the keystore and its password**, for example in a password manager and on a USB stick. If both are lost,
the next release needs another one-time migration.

## Releasing
1. Raise `versionCode` (by one or more) and `versionName` in `app/build.gradle.kts`, and add a `## x.y.z` section to
   CHANGELOG.md.
2. Tag and push: `git tag v1.2.1 && git push origin v1.2.1`.
3. The workflow tests, builds, signs and checks the key. It then attaches `AetherTV-x.y.z.apk`, `aether-update.json`
   and `SHA256SUMS` to a **draft** release.
4. Review the draft on GitHub and publish it. Aether on the TV offers it within a day, or at once with Settings ▸
   Updates ▸ Check now.
