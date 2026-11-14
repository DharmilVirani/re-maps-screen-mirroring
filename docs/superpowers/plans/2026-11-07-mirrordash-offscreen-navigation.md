# MirrorDash Off-Screen Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use the native Codex or subagent-driven execution approach Dharmil approves after plan review. Implement task by task; steps use checkbox (`- [ ]`) syntax for tracking. Keep the renderer/protocol feasibility gate ahead of the complete navigation UI.

**Goal:** Replace the Google Maps screen-capture MVP with a MirrorDash-owned GPX map that streams to the Himalayan Tripper while the phone display is off or locked.

**Architecture:** A user-started foreground service owns the local route, GPS updates, MapLibre renderer, H.264 encoder, Tripper UDP session, joystick actions, notification, and session-scoped power/network locks. The renderer draws MirrorDash UI on a private Android `VirtualDisplay` whose output surface feeds the existing encoder; GPX and offline map regions remain on-device.

**Tech Stack:** Kotlin, Android 14+ (`minSdk 34`, current `targetSdk 36`), Jetpack Compose, MapLibre Native Android (13.7.1 at plan time), Android `Presentation`/`VirtualDisplay`, `MediaCodec` H.264, RTP/UDP, `WifiNetworkSpecifier`, `LocationManager`, and Android's XML pull parser.

**Spec:** [docs/design/offscreen-navigation-spec.md](../../design/offscreen-navigation-spec.md)

## Global Constraints

- Render a MirrorDash-owned map; do not capture Google Maps or another app window.
- Import GPX routes and keep route geometry on the phone.
- Download a route-area offline map pack before the ride; a complete local route and map pack must be usable without mobile data.
- Show current location, route line/progress, remaining distance, and a speed-based ETA estimate. Do not promise live traffic or offline rerouting.
- Keep the existing Tripper H.264/RTP video transport and K1G control path.
- Receive and acknowledge joystick events. Make zoom direction reversible; enable recenter only after its event is confirmed on the owner's bike.
- Keep connection values editable: SSID, optional passphrase, TFT IP, control transmit/receive UDP ports, video UDP port, dimensions, frame rate, and bitrate.
- Start the foreground session from the visible app; support normal screen-off and lock after start, subject to target-device validation.
- Keep a persistent session notification with Stop. Hold wake/Wi-Fi locks only during the active stream and release them on every stop/failure path.
- Defaults from the owner report are editable: `RE_LT09_260125`, `192.168.1.1`, UDP `2000/2002/5000`, `526×300`, `4 fps`, `204800 bit/s`.
- Default to `526×300`; treat larger output modes as experimental until a parked TFT test confirms them.
- Preserve current profile guardrails (width `144..1280`, height `144..720`, frame rate `1..30`, bitrate `64,000..5,000,000`) as app input limits only; they are not claims of TFT decoder support.
- Keep route and live location on-device. Never log coordinates, GPX points, passphrases, keys, encoded frames, or complete protocol packets.
- Disclose the map provider and requested area; show required map attribution in the TFT view.
- Test all motorcycle behavior while parked. Do not claim FOTA 11.63 or screen-off compatibility before observing it on the owner's phone and bike.

## Review Focus

- Malformed, empty, or ambiguous GPX: show a specific import error and do not start a ride with a partial route. Valid multi-segment tracks must retain segment boundaries and must not add artificial distance between segments.
- Missing or incomplete offline map pack: show the exact readiness state and prevent the normal Ride start action.
- GPS denied, stale, inaccurate, or off-route: show the stale/off-route state and avoid presenting an ungrounded ETA.
- Wrong Wi-Fi password, SSID, TFT address, or a lost local network: show the failed stage, keep reconnect bounded, and release resources after Stop/final failure.
- Unknown/repeated joystick codes or conflicting community mappings: acknowledge only according to the confirmed protocol behavior and do not trigger an unverified recenter action.

---

## Task 1: Reconcile the project contract with the approved spec

**Files:**
- Modify: `docs/project/project-definition.json`
- Modify: `docs/architecture/artifact-packet.json`
- Modify: `docs/architecture/capability-dag.json`
- Modify: `docs/architecture/provenance.md`
- Modify: `docs/research/evidence-ledger.json`
- Modify: `README.md`

**Interfaces:**
- Consumes: approved design at `docs/design/offscreen-navigation-spec.md` and sources at `docs/research/offscreen-navigation-evidence-ledger.json`.
- Produces: project records whose requirements, architecture, permissions, risks, validation cases, and source IDs describe app-owned rendering, GPX, offline maps, the location/connected-device foreground service, and the known unencrypted Tripper transport.

- [x] Update the PDC outcome, requirements, non-goals, Android permission model, assumptions, and validation references to remove the prior MediaProjection/Shizuku workflow.
- [x] Update architecture artifacts and provenance so the renderer, route/map data, service, and Tripper trust boundary are represented consistently.
- [x] Rewrite README flow for GPX/map readiness, Ride start, screen-off/lock, joystick, notification Stop, and the unverified-bike warning.
- [x] Inspect the resulting docs for stale directions such as “choose Google Maps in the screen-sharing picker,” “install Shizuku,” and “keep phone unlocked.”

## Task 2: Build the parked-device off-screen renderer spike

**Files:**
- Create: `app/src/main/java/com/dharmil/mirrordash/navigation/GeoPoint.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/render/TftRenderState.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/render/TripperMapRenderer.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/ride/RideSessionService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/dharmil/mirrordash/MainActivity.kt`

**Interfaces:**
- Consumes: `H264Encoder.start(): Surface`, `TripperTransport.open(...)`, `TripperSession.authenticate()`, and `TripperSession.beginProjection()` from the current implementation.
- Produces: `data class GeoPoint(val latitude: Double, val longitude: Double)`, `data class TftRenderState(val center: GeoPoint?, val routeSegments: List<List<GeoPoint>>, val rider: GeoPoint?, val zoom: Double, val nextCueText: String?, val remainingMeters: Double?, val etaMinutes: Int?, val status: String)`, and `class TripperMapRenderer(context: Context, widthPx: Int, heightPx: Int) : AutoCloseable` with `start(encoderSurface: Surface)`, `update(state: TftRenderState)`, `zoomBy(delta: Double)`, `recenter()`, and `close()`.

- [x] Implement a small app-owned test view in a `Presentation` attached to a private `VirtualDisplay` using `VIRTUAL_DISPLAY_FLAG_PRESENTATION | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`; do not set `AUTO_MIRROR`.
- [x] Draw the test screen locally with a simple Android `View` so the first hardware gate does not depend on internet tiles or an offline map pack.
- [x] Make the renderer own its display and Presentation lifecycle on the main thread. It must not retain an Activity after the Ride screen backgrounds.
- [x] Create `RideSessionService` as an explicit, non-exported user-started service. Start foreground mode before network/codec work and use the existing configurable profile, Tripper transport, H.264 encoder, and RTP session.
- [x] Declare the initial renderer-spike service with the `connectedDevice` foreground-service type and its permission; add `location` when Task 6 wires in GPS.
- [x] Keep the existing MediaProjection service available behind the old UI until the new service can render a test view; add only a debug-only entry point for the temporary test view.
- [x] Preserve the current wire handshake and route-card metadata for the first protocol trial; never claim Google Maps is rendering.
- [ ] Use a parked TFT check to decide whether its displayed source label can safely change from the current Google Maps compatibility label to MirrorDash/Navigation.
- [x] Tear down in reverse dependency order: stop map updates; dismiss/destroy the Presentation and release its `VirtualDisplay`; send Tripper stop packets while transport is open; close encoder and its input `Surface`; close sockets; release locks and unregister callbacks.
- [x] Run `./gradlew :app:assembleDebug`.
- [x] Install the debug APK on the connected iQOO I2405 and launch it.
- [x] While parked, start the new session and confirm the `526×300` renderer test pattern on the actual TFT (owner-reported; see `source:renderer-spike-phone-run`).
- [ ] With the TFT stream active, turn the phone display off and confirm the pattern continues; separately confirm behavior while locked. The workstation currently has no ADB connection, so the owner is checking this on-device.
- [ ] Stop from the MirrorDash notification and confirm the TFT stream ends; verify session resources are released when device access is available.

**Gate:** Do not continue to the full route UI if the Presentation/VirtualDisplay → H.264 → TFT path fails on the target hardware. Record the failing boundary and revise the renderer or provider decision first.

## Task 3: Add GPX import and route-progress domain data

**Files:**
- Create: `app/src/main/java/com/dharmil/mirrordash/navigation/RoutePlan.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/navigation/RouteProgress.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/navigation/GpxImporter.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/navigation/RouteProgressTracker.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/data/GpxRouteRepository.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/MainActivity.kt`

**Interfaces:**
- Consumes: `GeoPoint` and Android's `ContentResolver`/`Location`.
- Produces: `data class RouteCue(val pointIndex: Int, val text: String)`, `data class RoutePlan(val id: String, val name: String, val segments: List<List<GeoPoint>>, val cues: List<RouteCue>, val totalDistanceMeters: Double)`, `data class RouteProgress(val snappedPosition: GeoPoint, val distanceAlongRouteMeters: Double, val remainingMeters: Double, val nextCueText: String?, val isOffRoute: Boolean, val etaMinutes: Int?)`, and `sealed interface GpxImportResult { data class Success(val route: RoutePlan); data class Failure(val message: String) }`.
- Produces: `GpxImporter.parse(input: InputStream, sourceName: String): GpxImportResult`, `GpxRouteRepository.import(uri: Uri): GpxImportResult`, and `RouteProgressTracker(route: RoutePlan, offRouteThresholdMeters: Double = 50.0).update(location: Location): RouteProgress`, retaining the last matched segment index so loops do not jump arbitrarily backward.

- [ ] Parse GPX incrementally with Android's XML pull parser. Support route points (`rtept`) and track segments (`trkseg/trkpt`); preserve separate segments so distance does not include artificial jumps between them.
- [ ] Reject external entities/DTD, invalid latitude/longitude, missing route points, malformed XML, and ambiguous multiple route candidates with a visible error; do not log or upload imported file contents.
- [ ] Store the selected route privately in app storage and persist only the route ID/name/metadata needed to reopen it.
- [ ] Add a temporary GPX picker/route-summary panel to the existing screen so the parser can be exercised before the final Ride screen is built; show route name, distance, and segment count.
- [ ] Compute segment distances and cumulative route distance using geodesic distance; compute nearest-segment progress, remaining distance, and off-route state. Use a 50-meter off-route threshold with two consecutive fixes to avoid a single GPS jump changing the state.
- [ ] Use named GPX route-point text as optional cues only; do not infer turn directions from geometry. Keep automatic rerouting out of scope.
- [ ] Estimate ETA from moving GPS speed averaged over the previous 60 seconds; return no ETA when the latest GPS fix is older than 10 seconds, there are fewer than three valid fixes, or average speed is below 1 m/s.
- [ ] Run `./gradlew :app:assembleDebug`; import GPX files through Android's file picker and inspect route name, segment count, total distance, cues, and invalid-file messages in the temporary route preview.

## Task 4: Add MapLibre rendering and compliant offline map packs

**Files:**
- Create: `app/src/main/java/com/dharmil/mirrordash/MirrorDashApplication.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/maps/MapStyleConfig.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/maps/RouteBoundsBuilder.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/maps/MapPackStatus.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/maps/MapPackRepository.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/MainActivity.kt`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/dharmil/mirrordash/render/TripperMapRenderer.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/render/TftRenderState.kt`

**Interfaces:**
- Consumes: `RoutePlan`, `GeoPoint`, `TftRenderState`, and the service-owned renderer surface from Tasks 2–3.
- Produces: `sealed interface MapPackStatus` with `NotDownloaded`, `Downloading(completedTiles: Long, requiredTiles: Long?, completedBytes: Long)`, `Ready(regionId: Long)`, and `Failed(message: String)`; `MapPackRepository.status(routeId: String): StateFlow<MapPackStatus>`, `download(route: RoutePlan)`, `cancel(routeId: String)`, and `isReady(routeId: String): Boolean`.
- Map renderer preserves `start(Surface)`, `update(TftRenderState)`, `zoomBy(Double)`, `recenter()`, and `close()` while replacing the test view with MapLibre `MapView` and TFT overlays.

- [ ] Add the current MapLibre Native Android artifact `org.maplibre.gl:android-sdk:13.7.1`; recheck the latest version before implementation if the plan is run later.
- [ ] Initialize MapLibre once from an application context and use the proposed OpenFreeMap Liberty style URL `https://tiles.openfreemap.org/styles/liberty` without a MapTiler API key.
- [ ] Configure `HttpRequestUtil.setOkHttpClient(...)` before the first map view. Add an app-identifying User-Agent such as `MirrorDash/0.1.0 (Android; com.dharmil.mirrordash)` and restrict parallel map requests to at most four.
- [ ] Replace the test drawing in `TripperMapRenderer` with a MapLibre map in the existing Presentation. Draw route, rider marker, lower distance/ETA panel, loading/offline/error state, and visible OSM/OpenMapTiles attribution in the target frame.
- [ ] Compute the route bounds plus a 750-meter map margin with `RouteBoundsBuilder.build(route: RoutePlan, marginMeters: Double = 750.0): LatLngBounds`. Create a tile-pyramid offline region at zoom levels 9–15 and persist its MapLibre region ID against the route ID. Allow the user to lower maximum zoom before download when needed to fit the provider's pack limits.
- [ ] Add a temporary route-pack Download/Cancel/status control to the route preview so the offline region can be exercised before the final Ride screen is built.
- [ ] Enforce the provider's current offline limits for each download: no more than 3,000 tiles or 50 MB, no more than four parallel requests, and a unique User-Agent. Cancel/delete an incomplete over-limit pack and show a clear route-area/zoom choice; do not split downloads to evade the provider cap.
- [ ] Make normal Ride start unavailable until the selected route's offline region is complete. Recheck OpenFreeMap terms before release.
- [ ] Run `./gradlew :app:assembleDebug`; while parked, download an eligible route-area pack, stop internet access, render the complete map locally, and confirm attribution/status on the TFT.

**Provider gate:** MapLibre does not provide an offline tile source or guarantee provider rights. Its offline status exposes completed resource size but not a total required-byte estimate. If implementation cannot stop before a single pack crosses the provider's byte ceiling, pause this provider integration and return with compliant alternatives instead of attempting a larger download.

## Task 5: Add GPS progress, ETA, joystick zoom, and recenter

**Files:**
- Create: `app/src/main/java/com/dharmil/mirrordash/navigation/PhoneLocationSource.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/tripper/TripperInputEvent.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/tripper/JoystickActionMapper.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/tripper/TripperProtocol.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/tripper/TripperSession.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/ride/RideSessionService.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/render/TripperMapRenderer.kt`

**Interfaces:**
- Consumes: `RouteProgressTracker.update(Location)`, `TripperProtocol.parse(...)`, and existing `TripperProtocol.echoAck(...)`.
- Produces: `class PhoneLocationSource(context: Context)` with `start(onLocation: (Location) -> Unit)` and `stop()`; use `LocationManager.GPS_PROVIDER` with a 1-second requested update interval while a ride is active.
- Produces: `sealed interface TripperInputEvent { data class Button(val code: Int): TripperInputEvent }`, `enum class JoystickAction { ZoomIn, ZoomOut, Recenter }`, and `JoystickActionMapper.map(buttonCode: Int, invertZoom: Boolean): JoystickAction?`.
- Produces: `TripperSession.handleIncoming(packet: ReceivedControlPacket): List<TripperInputEvent>`; the service maps verified button codes into `JoystickAction` on the main thread.

- [ ] Feed GPS updates into `RouteProgressTracker`, then post a new `TftRenderState` to the renderer on the main thread. Display stale-GPS, no-GPS, and off-route states instead of carrying a stale ETA forward.
- [ ] Derive recent moving speed over the previous 60 seconds; show an estimated arrival only when the tracker has valid moving-speed history.
- [ ] Parse joystick packets separately from frame-decoded acknowledgments. Preserve confirmed ACK behavior and never log full packet bytes.
- [ ] Keep zoom direction reversible in the bike profile. Map codes only after the target bike is checked while parked; keep recenter disabled until its code/ACK behavior is confirmed.
- [ ] While parked, verify each observed button code, ACK, zoom direction, inversion, recenter, and behavior after a short network interruption.

## Task 6: Replace the old service with the Ride foreground session

**Files:**
- Create: `app/src/main/java/com/dharmil/mirrordash/ride/RideSessionState.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/ride/RideSessionService.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/dharmil/mirrordash/MainActivity.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/tripper/TripperTransport.kt`

**Interfaces:**
- Consumes: `TripperProfile.validate()`, selected route ID, `MapPackRepository.isReady(routeId)`, `PhoneLocationSource`, `TripperSession`, `H264Encoder`, and `TripperMapRenderer`.
- Produces: `enum class RidePhase { IDLE, PREPARING, CONNECTING_WIFI, AUTHENTICATING, WAITING_FOR_GPS, STREAMING, RECONNECTING, ERROR }`, `data class RideSessionState(val phase: RidePhase, val message: String)`, `object RideSessionStateStore { val state: StateFlow<RideSessionState>; fun update(value: RideSessionState) }`, and explicit `RideSessionService.start(context, profile, routeId)` / `RideSessionService.stop(context)` commands.

- [ ] Declare the service as non-exported with `location|connectedDevice` types and the required current Android foreground-service permissions.
- [ ] Request precise/coarse location, Nearby Wi-Fi, and notification permissions from the visible Ride screen before service start. Start location only after the user starts the ride; do not request background location for this flow.
- [ ] Start the service only from the visible Ride screen after location, Nearby Wi-Fi, and notification permission checks; call `startForeground` before network, location, map, or encoder work.
- [ ] Bind all Tripper UDP sockets to the Android `Network` returned by the selected `WifiNetworkSpecifier`; leave internet map-pack preparation outside the bike-network session.
- [ ] Own the selected route, GPS source, renderer, H.264 encoder, Tripper session, network callback, notification, partial wake lock, and Wi-Fi lock from the service.
- [ ] Show `Streaming`, `Reconnecting`, and `Stopped/Error` in the ongoing notification, with a Stop action. Do not stop the session merely because the Activity backgrounds or its task is swiped from Recents.
- [ ] Use bounded network reconnect attempts, then stop cleanly and release all locks/sockets/renderer/codec resources. Do not auto-restart a dead process; report the stopped state when the app reopens.
- [ ] Do not treat a wake lock as a guarantee against Doze or OEM network suspension; keep recovery visible and avoid an unrestricted-battery exemption unless target evidence later requires it.
- [ ] While parked, background and lock the phone, verify location and frames continue, disconnect/reconnect bike Wi-Fi, use notification Stop, and confirm all app-owned resources are released.

## Task 7: Build the one-screen Ride workflow and profile persistence

**Files:**
- Create: `app/src/main/java/com/dharmil/mirrordash/ui/RideScreen.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/ui/RideViewModel.kt`
- Create: `app/src/main/java/com/dharmil/mirrordash/data/BikeProfileStore.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/MainActivity.kt`
- Modify: `app/src/main/java/com/dharmil/mirrordash/tripper/TripperProfile.kt`

**Interfaces:**
- Consumes: `RideSessionState`, `RoutePlan`, `MapPackStatus`, `TripperProfile`, and the approved existing MirrorDash design system.
- Produces: one Compose Ride screen with a selected/imported GPX route, map-pack readiness/download progress, expandable editable bike/video settings, and session status/start/stop actions.
- Produces: `data class RideScreenState(val session: RideSessionState, val route: RoutePlan?, val mapPack: MapPackStatus, val profile: TripperProfile)` and `RideViewModel.state: StateFlow<RideScreenState>` with `importRoute(uri: Uri)`, `downloadMap()`, `startRide()`, `stopRide()`, and `saveProfile()` actions.
- Produces: `class BikeProfileStore(context: Context)` with `load(): TripperProfile` and `save(profile: TripperProfile)`, persisting only non-secret fields.

- [ ] Replace the old screen-capture setup form with the single Ride workflow: import/select GPX, prepare/resume offline map, edit bike profile, grant required access, Start, then use the system power button and notification Stop.
- [ ] Persist non-secret SSID/IP/port/video/zoom settings. Never persist the optional Wi-Fi passphrase; keep it session-only and keep it out of logs and analytics.
- [ ] Keep video dimensions/rate/bitrate editable. For the owner's current high-mode TFT experiment, seed `1280×720`, `30 fps`, and `5,000,000 bit/s` as the highest app-accepted profile and label it unverified for the TFT; use the previously observed `526×300`, `4 fps` profile as a fallback if the dash rejects or downscales the high mode.
- [ ] Show explicit `Route missing`, `Map not ready`, `GPS not ready/stale`, `Connecting`, `Authenticating`, `Streaming`, `Reconnecting`, `Stopped`, and error states. Disable Ride start until route and offline pack are ready.
- [ ] Keep the active TFT view glanceable; all touch setup is pre-ride. Keep the phone screen from being required once the service reaches Streaming.
- [ ] Run `./gradlew :app:assembleDebug`, install on the connected phone, and verify profile save/reopen, passphrase non-persistence, route import, map readiness, permissions, notification Stop, and screen-off service status.

## Task 8: Remove obsolete capture/Shizuku code and complete parked acceptance

**Files:**
- Delete: `app/src/main/java/com/dharmil/mirrordash/projection/ProjectionService.kt`
- Delete: `app/src/main/java/com/dharmil/mirrordash/projection/SessionUiState.kt`
- Delete: `app/src/main/java/com/dharmil/mirrordash/panel/DisplayPowerUserService.kt`
- Delete: `app/src/main/java/com/dharmil/mirrordash/panel/PanelPowerRecoveryStore.kt`
- Delete: `app/src/main/java/com/dharmil/mirrordash/panel/ShizukuPanelController.kt`
- Delete: `app/src/main/aidl/com/dharmil/mirrordash/panel/IDisplayPowerService.aidl`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/build.gradle.kts`
- Modify: `README.md`, PDC, architecture artifacts, and security review

**Interfaces:**
- Consumes: approved Ride service, UI, route, MapLibre, and Tripper modules from Tasks 2–7.
- Produces: an app with no MediaProjection consent flow, no Shizuku panel controls, no stale projection/Shizuku permission or provider declarations, and updated user-facing/privacy documentation.

- [ ] Remove the old MediaProjection/Shizuku entry points only after the new Ride flow is active; remove their manifest declarations, permission declarations, queries, AIDL, dependencies, and recovery state.
- [ ] Preserve the current H.264/RTP protocol implementation and security disclosure; do not claim app-level encryption or cryptographic TFT identity verification.
- [ ] Remove the temporary debug-only renderer entry point and ensure the normal Start action requires route/map readiness.
- [ ] Run `./gradlew :app:assembleDebug` and install the resulting debug APK on the connected iQOO I2405.
- [ ] Complete parked acceptance `TST-MD-01` through `TST-MD-09`: TFT layout and crop, default stream, offline route preview, screen-off and lock, joystick zoom/recenter, editable profile/passphrase handling, Stop/reconnect cleanup, provider/attribution/privacy disclosures, and resolution experiments.
- [ ] Record actual results separately for phone-only, TFT protocol, FOTA 11.63, and provider/offline checks; do not convert an untested result into a compatibility claim.

## Plan Notes

- The workspace has no Git metadata at plan time, so this plan omits commit steps. Initialize/version-control the workspace before relying on commit-based review or rollback.
- The repository currently has no automated test source tree. This plan uses Gradle build/package checks and the owner's approved parked-device acceptance scenarios; it does not add or run automated tests.
- **Progress (2026-11-07):** Task 1 is complete. Task 2 renderer/service code compiles and the debug APK is installed/launched on the iQOO I2405. The phone joined the owner-reported Tripper SSID, MirrorDash reports streaming, and the owner confirmed the TFT displays the renderer test pattern. Screen-off/lock continuity and notification Stop cleanup remain the Task 2 gate; do not proceed to the full Ride UI until those checks pass.
- **Owner direction (2026-11-08):** The immediate path is now to keep Connect & mirror, fit the selected Maps window into the TFT semicircle, and defer app-owned MapLibre/GPX work. After the owner reconnects the phone over USB, use REA on the Royal Enfield app to investigate how the Tripper connection survives phone lock. MirrorDash should later keep its bike connection alive while locked, allow the map video to pause, resume mirroring after unlock, and stop only on explicit disconnect. The screen-mirror layout is implemented and builds; TFT crop verification is pending.
- **Owner execution sequence (2026-11-08):** (1) Validate the semicircle output through MirrorDash Connect & mirror on the parked TFT. (2) Use REA on the installed Royal Enfield app to inspect service declarations and code where the configured provider permits; compare its live foreground services, Tripper Wi-Fi and traffic before lock, while locked, and after unlock. The owner authorizes this investigation without another approval prompt; ask for manual help only to start or restore navigation. (3) Keep MirrorDash's Tripper connection alive independently of screen capture, pausing video when Android ends MediaProjection and stopping the session only on explicit disconnect or terminal failure. (4) Test whether mirroring resumes after unlock within Android's capture-consent rules. (5) Add the Shizuku Display Off control after the connection path is stable, preserving the intended off-but-unlocked then physical-wake-to-lock flow. (6) Verify the full sequence while parked and record each observed result separately.
- **Android capture constraint:** On Android 15 QPR1 and later, locking the phone automatically stops MediaProjection. Android 14 and later require consent for each capture session and restrict each MediaProjection instance to one capture call. Therefore, keeping the Tripper link alive is a separate, achievable milestone; automatic selected-app video resumption without new consent must be verified and is not promised by this plan.
- **Device checkpoint (2026-11-08):** The iQOO I2405 running Android 16/API 36 is USB-authorized. The installed Royal Enfield app is `com.royalenfield.reprime` version `10.1.23(45)`. At the last read-only check, the phone was on the launcher with Wi-Fi disabled and no active Tripper route. Its APK manifest declares `REForeGroundService` with the `connectedDevice` foreground-service type and Google's `NavigationService` with the `location` type. These declarations are a static lead, not proof those services are running during navigation. The owner must start/restore a route for the live lock test.
- **MirrorDash install checkpoint (2026-11-08):** Debug APK `0.1.0` / version code `1` was reinstalled on the authorized iQOO; REA reports launch status `ok`. A later screen capture was black while Android reported `mWakefulness=Asleep` and the user locked; Wi-Fi was disabled. This is not a renderer failure. Semicircle/TFT acceptance remains pending an awake, unlocked phone, active Tripper Wi-Fi, and a live map source.
- **High-mode connection checkpoint (2026-11-08):** The owner had an active Maps route and reported the TFT ready. The first attempt showed `Android could not connect to the selected Tripper Wi-Fi.` Device inspection showed the phone already associated with `RE_LT09_260125` at `192.168.1.2/24`, with the TFT at `192.168.1.1` and Wi-Fi security type SAE. The MirrorDash passphrase field was blank while `ProjectionService` requested the SSID with a `WifiNetworkSpecifier` that only configured WPA2 when a passphrase was present. The next build removed app-side Wi-Fi joining from Connect & mirror and waits for the user-connected bike link.
- **High-mode build checkpoint (2026-11-08):** `TripperProfile` was temporarily set to the app's input ceiling of `1280×720`, `30 fps`, and `5,000,000 bit/s`; these are not verified TFT limits. `./gradlew :app:assembleDebug` succeeded and the APK was installed on the authorized iQOO.
- **Wi-Fi route-matching failure checkpoint (2026-11-08):** On the next user attempt the app showed `No matching Tripper Wi-Fi found` even though Android still reported the correct SSID, `192.168.1.2/24`, and a `192.168.1.0/24` connected route covering the TFT IP. The service had already stopped MediaProjection, so no video frames reached the TFT; this was a connection-selection failure, not evidence that the TFT rejected 720p/30 fps. The SSID-only matcher was replaced with a Wi-Fi callback plus a specific local-route check for the configured TFT IP. The route-matcher build succeeded and was installed. Target-device verification of route adoption, authentication, and high-mode decoding remains pending the owner's next **Connect & mirror** action.
- **Owner TFT result (2026-11-08):** With the route-based matcher installed, MirrorDash reported `Maps is streaming inside the TFT semicircle`, while the TFT displayed `Timed out` at the high app settings. The owner then switched to the previously observed baseline `526×300`, `4 fps`, `204,800 bit/s`; the video became visible. This confirms the route and baseline video path. The simultaneous high-mode failure does not identify whether resolution, frame rate, or bitrate exceeded a TFT or transport limit. Next isolate resolution with `1260×720`, `4 fps`, `204,800 bit/s`; if visible, then raise bitrate and frame rate separately. Keep the bike parked and preserve the known-good profile as the fallback.
- **REA toolchain note (2026-11-08):** REA successfully pulled the installed APK. Its JADX and apktool providers reported that their caller-supplied JAR/launcher is not configured, so current static observations use the APK manifest and Android SDK `aapt`; do not present raw DEX strings or manifest declarations as observed runtime behavior.
- **Preflight (2026-11-07):** In a separate parked test using the Royal Enfield app, the owner confirmed the full-map route remained visible on the TFT for about one minute with the phone display off while Android stayed unlocked and the app remained foreground. The display-off command was issued over ADB shell using the same `cmd display power-off 0` operation as MirrorDash's Shizuku helper; the Shizuku binder path itself was not exercised, and this does not verify MirrorDash's own renderer. On this phone, `cmd display power-on 0` is unsupported; a power-key recovery turns the panel on and activates the lock screen. See `source:royal-enfield-screen-off-test`.
- **Owner overlay observation and implementation (2026-11-08):** The owner reports that a WhatsApp bubble stayed open above active Royal Enfield navigation while the TFT route remained visible. MirrorDash now has an explicit floating power button backed by the existing fixed Shizuku display-off helper, an ongoing notification with Hide bubble, and a foreground service. The debug build succeeded and was installed. The custom overlay, Shizuku binder call, and TFT continuity after tapping it still need a parked owner test; lock continuity remains separate. See `source:whatsapp-overlay-navigation-test`.
- OpenFreeMap terms are current to the research date and can change. The implementation gate in Task 4 is mandatory because MapLibre does not expose a total required-byte estimate before a region download completes.
- The current project's prior renderer in `re-connector` is a reference only. Do not import it wholesale; preserve attribution/license notices if code is copied.

## Source checkpoints

- [Android DisplayManager API](https://developer.android.com/reference/android/hardware/display/DisplayManager) and [Presentation API](https://developer.android.com/reference/android/app/Presentation)
- [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types), [location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions), and [Doze behavior](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [MapLibre Android artifacts](https://maplibre.org/maplibre-native/android/examples/artifacts/) and [MapLibre HTTP client API](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.module.http/-http-request-util/index.html)
- [MapLibre offline-region API](https://maplibre.org/maplibre-native/android/api/-map-libre%20-native%20-android/org.maplibre.android.offline/index.html) and [OpenFreeMap terms](https://openfreemap.org/tos/)
- [OpenFreeMap style quick start](https://openfreemap.org/quick_start/) and [OpenStreetMap attribution guidelines](https://osmfoundation.org/wiki/Attribution_Guidelines)

