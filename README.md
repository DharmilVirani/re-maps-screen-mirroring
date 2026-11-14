# MirrorDash

MirrorDash is a native Android app that captures a selected navigation app and streams it to a Royal Enfield Tripper Dash over local Wi-Fi. Connect & mirror now fits the captured screen into a semicircle-shaped TFT viewport. Keeping the bike connection alive while the phone is locked is a later stage; the map video may pause while locked and should resume after unlock.

## Implementation status

The Connect & mirror path now composites the selected Maps window inside a semicircle-shaped TFT viewport. The debug APK builds; the crop still needs verification on the actual TFT. The owner has authorized the Royal Enfield connection investigation and MirrorDash lock-persistence work. The phone is USB-authorized, but Wi-Fi is currently off, so a live bike route is not active yet.

The new design must first prove the Presentation/VirtualDisplay → H.264/RTP → actual TFT path on the target phone and parked bike. The owner-reported Tripper FOTA 11.63, SSID, endpoints, screen-off behavior, joystick semantics, and larger video modes remain unverified.

## Intended ride flow

1. While parked, start a route in the chosen map app and rotate the phone to landscape.
2. Review the editable Tripper Wi-Fi name, optional session-only passphrase, TFT address, UDP ports, and video profile.
3. Start Connect & mirror, accept Android's screen-sharing prompt, and select the map app window.
4. Accept the local Tripper Wi-Fi connection prompt and confirm the semicircle image on the TFT.
5. Use Stop in MirrorDash's ongoing notification to end the session. After the semicircle check, investigate Royal Enfield's locked connection behavior, then implement and test MirrorDash connection persistence.

The current TFT view uses a semicircle mask around the selected map app's captured pixels. Maps' own ETA and distance remain part of that image. The exact crop still needs an on-bike check. Joystick zoom/recenter and locked-session continuity remain unverified.

## Map and privacy

The current screen-mirror path does not parse, save, or upload the selected app's pixels. The Tripper H.264/RTP stream is not end-to-end encrypted and the TFT identity is not cryptographically verified. Connect only to a trusted Tripper Wi-Fi network.

## Current reported bike profile

- Firmware: FOTA 11.63 (owner-reported; not tested here)
- Wi-Fi SSID: `RE_LT09_260125`
- TFT IPv4 address: `192.168.1.1`
- Control/response UDP ports: `2000` / `2002`
- Video UDP port: `5000`
- Initial video profile: `526×300`, `4 fps`, `204800 bit/s`

The initial profile is editable. Larger output values are experimental until a parked TFT test confirms them.

## Display-off bubble for Royal Enfield navigation

MirrorDash can show a small floating power button above Royal Enfield navigation. Before starting a route, open MirrorDash, make sure Shizuku is running and has granted MirrorDash permission, then use **Show display-off bubble** and allow Android's **Display over other apps** and notification permissions. Return to Royal Enfield, connect the TFT and start navigation. Tap the bubble to run the fixed Shizuku display-off command without bringing MirrorDash to the foreground. Drag it to reposition it; use the ongoing notification's **Hide bubble** action to remove it.

The owner reports that a WhatsApp bubble stayed open while the TFT continued showing Royal Enfield navigation. That observation does not yet validate MirrorDash's custom bubble or Shizuku button path. Wake the phone with its power key; lock-screen navigation continuity is a separate test and is not provided by the bubble itself.

## Build

With Android SDK platform 37 and a compatible JDK installed:

```bash
JAVA_HOME=/opt/android-studio/jbr ./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The final APK must be installed and checked on the target phone; actual bike acceptance must be performed while parked.

See [the Project Definition Contract](docs/project/project-definition.json), [the implementation plan](docs/superpowers/plans/2026-11-07-mirrordash-offscreen-navigation.md), [the evidence ledger](docs/research/evidence-ledger.json), and [the approved navigation spec](docs/design/offscreen-navigation-spec.md).
