# MacOS/pam — PixelSync phone-unlock PAM module

> ⚠️ **Advanced / system-level.** This installs a PAM module used by the macOS lock
> screen. It is **not** plug-and-play: you need admin rights and should understand the
> change. The password path is preserved and everything is reversible.

## What it does

The macOS lock screen authenticates through **PAM** (`/etc/pam.d/screensaver`), not through
Authorization Services. This module is added as `auth sufficient` **before** the password
module. If a valid, unexpired grant exists (written by the PixelSync app after a phone
fingerprint signature), it consumes the grant and returns `PAM_SUCCESS` → the screen unlocks
**without a password**. Otherwise it returns `PAM_IGNORE` and the normal password path runs.

It never reads or stores a password and never simulates keyboard input.

## Build

Requires the Xcode Command Line Tools.

```sh
./build.sh          # builds a universal (x86_64 + arm64) pam_pixelsync.so
```

`build.sh` signs the module with a local “Apple Development” identity if available, otherwise
ad-hoc. Ad-hoc works but macOS may log an AMFI warning.

## Install (needs sudo)

```sh
sudo ./install.sh   # copies the module to /usr/local/lib/pam and edits /etc/pam.d/screensaver
```

It only **adds** this line (keeping the password module):

```
auth       sufficient     /usr/local/lib/pam/pam_pixelsync.so
```

## Uninstall

```sh
sudo ./uninstall.sh
```

## Grant file

The app writes `/Users/Shared/PixelSync/unlock_grant` (0600, owned by the user; directory
0700). The module requires the grant to be **owned by the authenticating user** and bound to
that **username**, and consumes it once (unlink) — no replay.

## Compatibility

Works where the lock screen uses the `screensaver` PAM service (verified on macOS 12.7
Monterey, Intel). If a future macOS changes the PAM service name, `install.sh` fails safely
(it checks `/etc/pam.d/screensaver`) — adjust the service name as needed.

## Rollback / safety

`uninstall.sh` restores the original `/etc/pam.d/screensaver` (backup is taken on install).
The password login is never removed, so a misconfiguration does not lock you out.
