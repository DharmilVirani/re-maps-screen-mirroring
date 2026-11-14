# MirrorDash independent security review

## Scope

Code review covered the Android manifest and permission flow, MediaProjection
service lifecycle, Shizuku UserService and Binder contract, local UDP transport,
logging and persistence. The review did not include a connected phone, motorcycle,
packet capture, penetration test or cryptographic protocol analysis.

## Findings and disposition

| Finding | Severity | Disposition |
| --- | --- | --- |
| The Tripper peer is selected by configured source IP, but its identity is not cryptographically pinned. H.264/RTP video is sent without end-to-end encryption. A device on an untrusted local network could observe or spoof traffic. | P2 | Residual risk accepted for this local-only prototype. The setup screen and README now say the video is unencrypted, the peer is not cryptographically verified, and only a trusted Tripper Wi-Fi network should be used. |
| A standalone Panel On action could leave a non-foreground ProjectionService instance alive after its one command. | P2 | Fixed. One-shot panel actions stop their service after the Shizuku callback. |
| Panel-off recovery existed only in process memory, so process death could lose the app's knowledge that the screen might still be off. | P2 | Fixed in part. A single non-secret SharedPreferences boolean is committed before panel-off and cleared after panel-on succeeds. App startup shows a restore reminder. The user may still need the hardware power button and Shizuku permission if Android kills the app or the command is unavailable. |
| Stop during authentication could not close a local transport until the handshake returned. | P3 | Fixed. The connecting transport is registered under a resource lock and stop closes it; ownership transfer into the active session uses the same lock. |

## Controls reviewed

- The Shizuku UserService accepts one Boolean display-power request and builds
  fixed argument arrays. SSID, IP, ports and other user inputs do not reach a
  shell command.
- Android requests screen-capture consent for each session. Tripper UDP sockets
  are bound to the user-selected Wi-Fi `Network`; the app does not change global
  routing or add a remote relay.
- The app does not record, upload or send screen content to analytics. The Wi-Fi
  passphrase and screen frames are transient. The only persisted session state
  is the panel-recovery Boolean.
- Inbound control packets are filtered by the configured TFT source IP. UDP
  source-port filtering is not used because the protocol reference does not
  establish the TFT's source port.

## Remaining validation

The app has been built, rendered on an Android emulator, and installed and
launched on an iQOO I2405 running Android 16. No capture session or bike
connection has been tested. A parked-device check must still confirm Shizuku
behavior, that panel-off leaves the phone unlocked, that MediaProjection and
Google Maps keep rendering, and that the Tripper accepts this implementation's
handshake and video packets. No FOTA 11.63 compatibility or secure transport
claim is made.
