# MirrorDash visual system

## Fit

MirrorDash is a one-screen Android setup utility used while the motorcycle is
parked. The app should guide a short configuration and permission flow, then
leave Maps visible while the session is controlled from the system notification.
The Mobile/Compose UI Pro Max search returned a useful native layout guideline
(keep the layout shallow) after a retry; its generic web landing-page pattern
was not applicable. The Digital Signage/Kiosk palette result fits the need for
legible, high-contrast controls, but its red accent is reserved for errors here.

## Tokens

| Role | Color | Use |
|---|---|---|
| Background | `#101419` | Main screen |
| Surface | `#1B2229` | Form grouping |
| Primary | `#80CBC4` | Main action and active state |
| On primary | `#003733` | Primary button text |
| Text | `#F2F5F6` | Main labels |
| Muted text | `#BBC6CB` | Helper text |
| Warning | `#F0B86E` | Parked-test and unlocked-phone notices |
| Error | `#FFB4AB` | Errors and stop failures |
| Border | `#52616A` | Input and section boundaries |

## Layout and behavior

- Sentence case, left aligned, Android system sans-serif, 16sp body text.
- Keep the connection state first, editable bike details second and start/stop
  controls last. Use a single scrolling column with at least 16dp gaps.
- Use Material 3 text fields with visible labels; numeric keyboards for ports,
  dimensions and bitrate. Keep optional Wi-Fi password masked and transient.
- Keep primary actions at least 48dp high; do not rely on color alone for
  state. Announce state changes and errors through accessible Compose text.
- No animation is required. The Android capture picker and Wi-Fi prompt own their
  system UI and consent behavior.
- Do not copy Royal Enfield product identity or assume the app is safe to operate
  while riding.

## References

- UI Pro Max `jetpack-compose` search: use Compose state and avoid deeply nested
  layout trees; verified 2026-08-13 in the installed catalog.
- UI Pro Max mobile UX search: clear loading/success/error feedback, recoverable
  errors, 8dp spacing and 48dp Android touch target guidance.
- Android MediaProjection documentation supplies the system capture picker and
  user-consent flow. Material 3 components provide the native control language.

The palette is an implementation direction, not a contrast validation report.
Rendered contrast and TalkBack behavior remain device review items.
