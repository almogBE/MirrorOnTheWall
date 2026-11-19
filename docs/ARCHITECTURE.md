# MirrorOnTheWall: Architecture

This system mirrors an Android phone's screen (and later its system audio) to an Android TV over the local Wi-Fi network, using WebRTC. No cloud services are involved.

Status: **Phase 0** (research, plan, empty multi-module project). Sections that describe later phases describe the plan, not working code. §10 says what exists today.

---

## 1. Goals and priorities

These are fixed. Changing their order needs the project owner's approval.

| # | Goal | Target | What it means for design decisions |
|---|------|--------|-------------------------------------|
| 1 | **Smooth playback** | No freezes, no frame bursts | Keep WebRTC's adaptive jitter buffer and render smoothing. Keep the frame rate stable and drop resolution or quality first. |
| 2 | **Low latency** | 80–150 ms glass-to-glass | Use LAN-only host candidates, hardware codecs, CBR encoding, a minimal TV post-processing mode, and a low-latency Wi-Fi lock. Never trade away #1 to get here. |
| 3 | **Image quality and graceful degradation** | Sharp text on a stable network | Briefly lower resolution or quality instead of freezing. |

---

## 2. Toolchain and dependency versions (verified 2026-11-18)

| Item | Version | How it was verified |
|------|---------|---------------------|
| Gradle (wrapper) | **9.9.1** | `services.gradle.org/versions/all`: latest stable. The download was tested from this environment. |
| Android Gradle Plugin | **9.5.1** | Official AGP release notes (developer.android.com/build/releases/gradle-plugin). AGP 9.5 needs Gradle ≥ 9.6.0, Build Tools 36.0.0 and JDK 17+, and supports up to API 37. |
| Kotlin (KGP, Compose compiler, serialization plugin) | **2.4.21** | Maven Central metadata. 2.5.0 is still RC. `compose-compiler-gradle-plugin` and `kotlin-serialization` 2.4.21 exist. |
| kotlinx-coroutines | **1.11.0** | Maven Central. |
| kotlinx-serialization | **1.13.0** | Maven Central. Built against kotlin-stdlib 2.4.10, so it is compatible with 2.4.21. |
| Java-WebSocket | **1.6.0** | Maven Central (signaling server and client, from Phase 1). |
| AndroidX core / activity / lifecycle | **1.19.1 / 1.13.0 / 2.11.0** | AndroidX versions table (developer.android.com/jetpack/androidx/versions), latest stable. |
| Compose UI-foundation / Material3 | **1.12.1 / 1.4.0** | Same table. Explicit versions are used because the BOM mapping page could not be fully read. |
| WebRTC | **io.github.webrtc-sdk:android 150.7871.04** | See §3. |
| compileSdk / targetSdk | **37 / 36** | AGP 9.5 supports API 37 (Android 17). targetSdk stays at 36 until the Android 17 behavior changes are reviewed. The Android 14+ MediaProjection rules apply in either case. |
| minSdk | sender **29**, receiver **26** | As specified. |
| JDK | 17 bytecode (built with JDK 17+; this environment has 21) | AGP 9.5 minimum is 17. |

AGP 9 notes:
- AGP 9 has **built-in Kotlin**, so the Android modules do *not* apply `org.jetbrains.kotlin.android`. The root build declares `org.jetbrains.kotlin.jvm` (used by `:common`), which also pins the KGP version AGP uses.
- Google Maven was blocked at first, so the AGP and AndroidX versions were picked from Google's release-notes pages. Once `dl.google.com` was allowed, `./gradlew assembleDebug` passed with every pinned version unchanged (2026-11-19), and `lintDebug` reported no errors.
- The SDK package for API 37 is named **`platforms;android-37.0`**. There is no `android-37`. `compileSdk = 37` resolves to it.
- Without an NDK installed, AGP logs "Unable to strip … libjingle_peerconnection_so.so" and packages the native library unstripped. The debug sender APK is then about 63 MB. Release builds should install the NDK so the library gets stripped.
- No `distributionSha256Sum` is pinned in the wrapper because the checksum host was blocked from the build environment. Add it from https://gradle.org/release-checksums/.

---

## 3. WebRTC library choice

Google no longer publishes a prebuilt WebRTC AAR. Three maintained prebuilt artifacts are on Maven Central. I downloaded all three AARs and inspected the bytecode (`javap`) and the native libraries (`strings` and `readelf`), so the comparison below comes from the artifacts themselves, not from READMEs.

| | `io.getstream:stream-webrtc-android` 1.3.10 | **`io.github.webrtc-sdk:android` 150.7871.04** | `org.jitsi:webrtc` 155.0.1 |
|---|---|---|---|
| Maintainer | Stream | LiveKit (webrtc-sdk org) | Jitsi |
| Published | 2025-09-11 | **2026-10-23** | 2026-10-22 |
| Native source stamp (`WebRTC source stamp` in .so) | **2024-04-15** (≈M125, 2.5 years old) | **2026-06-01** (M150) | 2026-09-14 |
| License | Apache-2.0 (+ BSD WebRTC) | BSD-3 | Apache-2.0 / MIT (+ BSD WebRTC) |
| ABIs | arm64, armv7, x86, x86_64 | arm64, armv7, x86, x86_64 | arm64, armv7, x86, x86_64 |
| 16 KB page alignment (arm64 `LOAD` align) | 0x4000 ✔ | 0x4000 ✔ | 0x4000 ✔ |
| Custom audio input hook | `AudioRecordDataCallback` (observe/modify mic data only; the mic must stay open) | **`AudioBufferCallback` plus `WebRtcAudioRecord.setUseAudioRecord(false)`** (see §8) | none (stock upstream) |
| `ScreenCapturerAndroid` safe on Android 14+ | yes | **yes** (verified, see §5.1) | yes |
| Packaging | normal AAR | normal AAR | classes in a nested `libs/libwebrtc.jar` |

**Decision: `io.github.webrtc-sdk:android:150.7871.04`.**
- It is recent and actively released, much newer than Stream's build. Stream's artifact was republished in 2025 but its native code dates from April 2024.
- It is the only build with a supported way to **feed our own PCM into WebRTC's audio device module without opening the microphone**. Phase 4 (system audio) depends on this (§8). Stock upstream, and therefore Jitsi's build, would need our own native ADM.
- It is the base of LiveKit's production Android SDK, which uses these exact hooks for screen-share audio, so the code path is exercised in the field.
- Risk: it is a fork. If it stops being maintained, the fallback is Jitsi's upstream build plus a small custom native ADM for Phase 4. Only the audio hook is fork-specific; everything else uses the standard `org.webrtc` API.

There is also an `android-prefixed` variant that renames the packages to avoid clashes with other WebRTC copies. We don't need it.

### 3.1 Verified API surface (from `javap` on 150.7871.04)

- `ScreenCapturerAndroid(Intent, MediaProjection.Callback)`, `changeCaptureFormat(w,h,fps)`, `getMediaProjection()`.
- `HardwareVideoEncoderFactory(EglBase.Context, enableIntelVp8, enableH264HighProfile[, Predicate<MediaCodecInfo>])`. The encoder sets `bitrate-mode = 2` (**CBR**), which suits low latency.
- `RtpParameters.degradationPreference` ∈ {`MAINTAIN_FRAMERATE`, `MAINTAIN_RESOLUTION`, `BALANCED`, `MAINTAIN_FRAMERATE_AND_RESOLUTION`, `DISABLED`}. Each `Encoding` has `minBitrateBps`, `maxBitrateBps`, `maxFramerate`, `scaleResolutionDownBy`, `scalabilityMode`, `networkPriority`.
- `PeerConnection.setBitrate(min, current, max)` (BWE start bitrate), `restartIce()`, `getStats(RTCStatsCollectorCallback)` (also per sender and per receiver).
- `RtpTransceiver.setCodecPreferences(List<CodecCapability>)` to put H.264 first.
- `VideoSource.adaptOutputFormat(...)` (downscale without touching the capturer), `setIsScreencast(boolean)`.
- `PeerConnectionFactory.InitializationOptions.Builder.setFieldTrials(String)`.

---

## 4. Modules

```
:common   Kotlin/JVM library (no Android dependencies)
          signaling messages (kotlinx.serialization JSON), data models, constants,
          stats row model + CSV schema. Unit-tested on the JVM.
:sender   phone app, Compose UI, minSdk 29
          discovery client, signaling client, MediaProjection foreground service,
          WebRTC send pipeline, profiles, thermal manager, latency-test screen
:receiver TV app, Leanback launcher entry, View-based UI, minSdk 26
          NSD advertiser, signaling server, WebRTC receive pipeline,
          SurfaceViewRenderer, stats overlay
```

The receiver uses plain Views. The UI is a status screen plus a full-screen `SurfaceViewRenderer` (itself a View), so Compose would add size and startup time without benefit. Every API-level-specific call is gated by `Build.VERSION.SDK_INT` checks.

Threading model (both apps): one `Session` object owns one PeerConnection. All PeerConnection calls run on a single-threaded coroutine dispatcher. WebRTC observer callbacks post events into a `Channel`, which a state machine consumes, so session state is only touched on that one thread.

---

## 5. Discovery, pairing and signaling (Phase 1)

### 5.0 Discovery
- The receiver registers `_lowmirror._tcp` with `NsdManager.registerService`. TXT attributes: `v` (protocol version), `id` (random receiver UUID, persisted), `name` (device name).
- The sender calls `NsdManager.discoverServices`. To resolve:
  - **API 34+**: `registerServiceInfoCallback`, which also reports IP or port changes.
  - **API 29–33**: `resolveService`, which is deprecated in 34. Calls are serialized because older platforms allow only one resolve at a time.
- NSD runs in the system service, so the app does **not** need a `MulticastLock`.
- Fallback: manual `IP:port` entry. The TV home screen always shows its current IP, port and pairing code.

### 5.1 Transport
- The receiver runs a WebSocket server (Java-WebSocket) on `0.0.0.0:47913`, or on any free port if that one is taken, at path `/signal`. The sender connects as a client.
- Keep-alive uses WebSocket ping frames (Java-WebSocket's connection-lost timeout, set to about 3 s). Only one active session is allowed. A new `hello` from the same `senderId` replaces the old session (fast reconnect). A different sender gets `reject(BUSY)`.

### 5.2 Pairing
- The TV shows a 4-digit code, regenerated each time the app starts.
- The first connection from a sender needs the code, whether the TV was discovered or entered by IP. On success the TV issues a random `trustToken` that the sender stores per receiver `id`. Later connections send the token instead of the code. Token persistence and "forget device" are Phase 5. Phase 1 checks only the code.
- This is LAN-only access control, not strong security. The WebRTC media itself is always DTLS-SRTP encrypted.

### 5.3 Messages (JSON with a `type` discriminator, `:common`)
| type | direction | fields |
|------|-----------|--------|
| `hello` | S→R | `protocolVersion`, `senderId`, `senderName`, `pairingCode?`, `trustToken?` |
| `welcome` | R→S | `receiverId`, `receiverName`, `trustToken`, `display {width,height,refreshHz}`, `decoders {h264, vp8: max WxH@fps}` |
| `reject` | R→S | `reason` ∈ {`BAD_CODE`, `BUSY`, `VERSION_MISMATCH`} |
| `offer` / `answer` | S→R / R→S | `sdp` |
| `ice` | both | `sdpMid`, `sdpMLineIndex`, `candidate` (trickle ICE) |
| `iceRestartRequest` | R→S | (the receiver detected a failure; the sender owns renegotiation) |
| `bye` | both | `reason` |

Roles: the **sender is the offerer and the controlling ICE agent**. The receiver only answers. `welcome.display` and `decoders` let the sender choose its capture size and codec before creating the offer.

### 5.4 ICE / PeerConnection configuration
- `iceServers = []`, so only **host candidates**. No STUN or TURN.
- `bundlePolicy = MAXBUNDLE`, `rtcpMuxPolicy = REQUIRE`, `tcpCandidatePolicy = DISABLED`, `continualGatheringPolicy = GATHER_CONTINUALLY` (new local addresses after a network change are signaled without a full restart), and `keyType = ECDSA`.
- Unified Plan. The sender adds a `SEND_ONLY` video transceiver (and later audio), with both tracks in **one stream id** so that the receiver's A/V sync pairs them (§8).

---

## 6. Video pipeline

### 6.1 Sender capture: Android 14+ MediaProjection rules (Phase 2)
Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `POST_NOTIFICATIONS` (33+, requested at runtime), and a `<service android:foregroundServiceType="mediaProjection">`.

Order of operations:
1. The activity launches `MediaProjectionManager.createScreenCaptureIntent()`. On API 34+ it passes `MediaProjectionConfig.createConfigForDefaultDisplay()`, because a mirror should show the whole screen, not a single app.
2. On `RESULT_OK`, the activity starts the `MirrorService` with the result code and data intent.
3. The service calls `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)` (typed overload on API 29+) **before** anything obtains the projection.
4. Only then is `ScreenCapturerAndroid(data, callback)` created and started. **Verified in the bytecode**: its `startCapture` calls `getMediaProjection()`, then **`registerCallback()`, then `createVirtualDisplay()`**, in the order Android 14 requires.
5. `MediaProjection.Callback.onStop()` (user revoked, screen locked, another projection started) leads to a clean teardown, a `bye`, and a UI message. On API 34+, `onCapturedContentResize` and `onCapturedContentVisibilityChanged` are also handled.

Consequences of the Android 14 rules:
- A consent token can be used **once**, and a `MediaProjection` can create **one** VirtualDisplay. `ScreenCapturerAndroid.changeCaptureFormat` was checked in the bytecode: it calls `VirtualDisplay.resize()`, not release and recreate, so changing resolution (rotation, thermal, profile) is safe.
- **The capture outlives the PeerConnection.** Reconnects and ICE restarts rebuild only the PeerConnection. The `MediaProjection`, capturer and `VideoTrack` stay alive, so the user is never asked for consent again within a session.

Capture size: the VirtualDisplay is created at the **target encode size** (for example, long edge 1920 with the phone's aspect ratio), not at the panel's native size. Capturing smaller is the cheapest way to lower GPU and encoder load. Rotation is detected with a `DisplayListener` and handled with `changeCaptureFormat` (resize).

`FLAG_SECURE` windows appear black. This is expected and is not worked around. The UI explains it.

### 6.2 Encoding
- **H.264 hardware** first (`setCodecPreferences`), using `HardwareVideoEncoderFactory`. **VP8** is the fallback (hardware if available, otherwise libvpx software). HEVC is not used.
- H.264 High profile (`enableH264HighProfile = true`) is offered when both sides support it. Constrained Baseline remains in the offer for TVs whose decoder doesn't. WebRTC never uses B-frames, so High doesn't add reorder latency.
- The encoder runs in CBR mode (§3.1). The keyframe interval stays at WebRTC's default because PLI/FIR requests trigger keyframes on demand.
- Content type: the `VideoSource` is created with **`isScreencast = false`**. In WebRTC, screencast mode favors resolution over frame rate and tolerates frame drops, which works against priority #1. Phase 3 will A/B test it.

### 6.3 Rate control and degradation
Set through `RtpSender.setParameters()` on encoding[0], plus `PeerConnection.setBitrate()` so that bandwidth estimation (BWE) starts high on a LAN instead of at WebRTC's ~300 kbps default.

| Profile | Capture long edge | Max fps | Min / start / max bitrate | Degradation preference |
|---|---|---|---|---|
| **Smooth** (default) | 1920 | 60 | 2 / 8 / 14 Mbps | `MAINTAIN_FRAMERATE` |
| **Sharp** | 1920 (2560 if the encoder and TV decoder allow it) | 60 | 4 / 12 / 25 Mbps | `BALANCED` |
| **Low latency** | 1280 | 60 | 1.5 / 5 / 8 Mbps | `MAINTAIN_FRAMERATE` |

These are **starting values**, tuned in Phase 3 against the measurements in §9. `MAINTAIN_FRAMERATE` means that under pressure WebRTC reduces resolution or quality (QP) and keeps the frame rate, which is exactly priority #1 followed by #3.

### 6.4 Receiver rendering (Phase 2)
- A full-screen `SurfaceViewRenderer` shares one `EglBase` with `DefaultVideoDecoderFactory`. The factory uses the MediaCodec hardware decoder first, with libvpx software as the VP8 fallback. The Android WebRTC build has **no software H.264 decoder**, so H.264 needs the TV's hardware decoder. Almost all TVs have one, and `welcome.decoders` reports it.
- `FLAG_KEEP_SCREEN_ON`. `Window.setPreferMinimalPostProcessing(true)` on API 30+, plus `android:preferMinimalPostProcessing="true"` on the activity, to request ALLM / game mode.
- **Display refresh match** (smoothness): 60 fps content on a TV running at 50 Hz judders. On API 30+ we call `Surface.setFrameRate(60f, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)` (with `CHANGE_FRAME_RATE_ALWAYS` on API 31+). On API 26–29 we pick a matching `Display.Mode` through `preferredDisplayModeId`, if the TV offers one.
- The Wi-Fi lock and the rules in §7.1 apply to the receiver too.

### 6.5 Jitter buffer / playout delay: what this build exposes
Checked in both the Java API (`javap`) and the native library (`strings`):

| Control | Available? | Notes |
|---|---|---|
| `RtpReceiver.setJitterBufferMinimumDelay` | **No Java binding** | The native `RtpReceiverInterface::SetJitterBufferMinimumDelay` exists but is not exposed in Java in any of the three builds. It can only *raise* the minimum anyway. |
| `RTCConfiguration.audioJitterBufferMaxPackets`, `audioJitterBufferFastAccelerate` | Yes (audio only) | NetEq tuning for Phase 4. |
| Playout-delay RTP header extension (`http://www.webrtc.org/experiments/rtp-hdrext/playout-delay`) | Present in native | Negotiated in SDP. The sender can signal min/max playout delay. |
| Field trials `WebRTC-ForcePlayoutDelay`, `WebRTC-ForceSendPlayoutDelay`, `WebRTC-ZeroPlayoutDelay`, `WebRTC-JitterEstimatorConfig`, `WebRTC-FrameDropper` | Present in the native lib | Set through `InitializationOptions.setFieldTrials`. The exact parameter syntax will be checked in the M150 source before use in Phase 3. |

**Plan**: ship with WebRTC's **default adaptive jitter buffer**. It absorbs Wi-Fi jitter, which is what priority #1 asks for. Phase 3 will measure fixed min/max playout delays (for example 0–100 ms) through `WebRTC-ForcePlayoutDelay` on the receiver, and expose that only as an advanced option if it lowers latency without raising `freezeCount`. A max delay of 0 switches WebRTC to its "render ASAP" low-latency renderer, which removes smoothing. It will **not** be the default.

---

## 7. Platform tuning (Phase 3)

### 7.1 Wi-Fi and wake locks
- **API 29+**: `WifiManager.createWifiLock(WIFI_MODE_FULL_LOW_LATENCY, …)`. **Below 29**: `WIFI_MODE_FULL_HIGH_PERF` (receiver only, minSdk 26).
- Caveat from the platform documentation: the low-latency lock **only takes effect while the app is in the foreground and the screen is on**. On the phone, the user is usually in *another* app while mirroring, so our app is not in the foreground. The sender therefore holds **both** a LOW_LATENCY and a HIGH_PERF lock. The framework applies LOW_LATENCY when it can and HIGH_PERF otherwise. HIGH_PERF is deprecated in API 34, so its real effect on 34+ will be checked in the WifiLockManager source in Phase 3 and recorded here.
- Sender: a `PARTIAL_WAKE_LOCK` for the whole session. Both locks are released in every teardown path.

### 7.2 Thermal handling (sender)
- `PowerManager.addThermalStatusListener` (API 29+). On API 30+, `getThermalHeadroom(10s)` is polled every 5 s to act *before* the status changes.
- Steps, applied gradually with hysteresis (≥ 30 s before stepping back up):
  1. `LIGHT` or headroom ≥ 0.85: lower the bitrate ceiling by 25%.
  2. `MODERATE`: lower the capture size one step (1920 → 1600 → 1280) with `changeCaptureFormat`, and keep the frame rate.
  3. `SEVERE`: cap at 1280 and 30 fps. The frame rate drops only here, because thermal throttling would otherwise cause irregular stutter, which is worse.
  4. `CRITICAL` or above: show a warning and suggest stopping.

### 7.3 Resilience
- `ConnectivityManager.NetworkCallback` on both sides tracks Wi-Fi link and IP changes.
- ICE `DISCONNECTED` for 2 s, or `FAILED`, leads to an ICE restart (the sender calls `restartIce()` and sends a new offer). The receiver sends `iceRestartRequest` when it sees the failure first.
- If the signaling WebSocket drops, the sender reconnects with backoff (0.5, 1, 2, 4 s, up to 30 s), re-resolving NSD in case the TV's IP changed, and sends `hello` with the same `senderId`. The receiver then replaces the stale session. The capture keeps running throughout (§6.1).
- Teardown is idempotent on both sides, with this order: stop stats, close the PeerConnection, dispose tracks/sources/capturer (sender), release the renderer (receiver), release locks, close the socket, stop the foreground service.

---

## 8. System audio (Phase 4, researched now, implemented after video is stable)

Mechanism in the chosen build (verified in the bytecode of `WebRtcAudioRecord$AudioRecordThread.run`):
- `JavaAudioDeviceModule.Builder.setAudioBufferCallback(cb)`: on every 10 ms record cycle, WebRTC calls `long onBuffer(ByteBuffer buf, int audioFormat, int channels, int sampleRate, int bytesRead, long captureTimeNs)` **before** passing the buffer to the native encoder. The return value becomes the capture timestamp.
- `adm.audioInput.setUseAudioRecord(false)` (the field is public): WebRTC **does not create its own `AudioRecord`**. Its record thread fills the buffer with silence and calls our callback with no internal pacing. **Our callback therefore sets the pace**: a blocking read of exactly one 10 ms chunk paces the loop at real time.

Proposed implementation:
1. Request `RECORD_AUDIO` (needed by `AudioPlaybackCaptureConfiguration`). From the session's `MediaProjection`, build an `AudioRecord` with `AudioPlaybackCaptureConfiguration` (usages MEDIA, GAME, UNKNOWN), 48 kHz stereo PCM16.
2. Build the ADM with `setUseStereoInput(true)`, `setSampleRate(48000)`, `setAudioBufferCallback`, and disable the hardware AEC/NS. Call `setUseAudioRecord(false)` before recording starts, so the microphone is never opened.
3. In `onBuffer`, use `playbackRecord.read(buf, 0, bytes, READ_BLOCKING)` to copy into WebRTC's buffer. Return `AudioRecord.getTimestamp(…, TIMEBASE_MONOTONIC)` adjusted to the chunk's first frame, so audio and video capture timestamps share the `CLOCK_MONOTONIC` timebase that `SurfaceTexture` frame timestamps use.
4. Opus stereo at 128 kbps, with DTX **off** (constant cadence) and no FEC on a clean LAN (re-evaluated with loss stats).
5. **A/V sync**: audio and video tracks share one stream id, so the receiver's `StreamSynchronization` aligns them using RTCP sender reports. Residual offset will be measured with a beep plus flash in the latency-test screen (§9.3), then corrected on the sender by offsetting the returned `captureTimeNs` if needed.
6. Apps that opt out of capture (`ALLOW_CAPTURE_BY_NONE`) and DRM content produce silence. The UI mentions this. The phone still plays the audio locally. Phase 5 adds an option to mute the phone's media volume during a session.

To verify on devices in Phase 4: whether the privacy indicator stays off (no mic is opened), and whether `mediaProjection` FGS type alone is enough for playback capture while in the background (the documentation says yes; to be confirmed on API 34+).

---

## 9. Measurement (Phase 3)

### 9.1 Stats (every 1 s, from `getStats()`)
Receiver `inbound-rtp` (video): `frameWidth`, `frameHeight`, `framesPerSecond`, Δ`bytesReceived` → bitrate, `jitter`, `jitterBufferDelay / jitterBufferEmittedCount` → average jitter buffer delay, `jitterBufferTargetDelay`, `packetsLost`, `framesDropped`, `freezeCount`, `totalFreezesDuration`, `totalDecodeTime / framesDecoded` → decode ms/frame, `decoderImplementation`, `nackCount`, `pliCount`. The `candidate-pair` (selected) supplies `currentRoundTripTime`.
Sender `outbound-rtp`: `frameWidth`, `frameHeight`, `framesPerSecond`, `targetBitrate`, `totalEncodeTime / framesEncoded`, `qualityLimitationReason`, `qualityLimitationDurations`, `encoderImplementation`, `retransmittedPacketsSent`. `remote-inbound-rtp` supplies `roundTripTime`. Thermal status and the active profile are logged alongside.
The exact member names will be checked against a live stats dump in Phase 3 before the CSV schema is frozen.

### 9.2 Overlay and CSV
- TV overlay: toggled with a remote key (`KEYCODE_INFO`, with DPAD_CENTER long-press as a fallback). Monospace, updated every 1 s.
- CSV per session, one row per second with a fixed column order defined in `:common`. Stored in `getExternalFilesDir("stats")/session-<ISO8601>-<role>.csv`, which needs no storage permission, retrieved with `adb pull`.

### 9.3 Glass-to-glass latency test (sender)
A full-screen Compose screen shows a large millisecond counter, updated on every vsync with `withFrameNanos`, and a square that flashes for 1 frame every 500 ms. Film the phone and the TV together in slow motion (≥ 240 fps). Glass-to-glass latency = phone counter − TV counter in the same camera frame. The flashing square gives an unambiguous edge to compare. Phase 4 adds a beep synchronized with the flash, to measure A/V offset.

---

## 10. What exists today (Phase 0)

- Gradle multi-module project (`:common`, `:sender`, `:receiver`), version catalog, Gradle 9.9.1 wrapper.
- `:common`: `MirrorConstants` (service type, port, protocol version, TXT keys), with JVM unit tests.
- `:sender`: a Compose activity that shows a skeleton notice. **No discovery, capture or streaming.**
- `:receiver`: a TV activity with Leanback launcher entry and banner, showing a skeleton notice. **No server, advertising or rendering.**
- The WebRTC dependency is declared in both apps so that the first `assembleDebug` verifies resolution and packaging. No WebRTC code is written yet.

## 11. Open questions and risks

1. ~~Unverified toolchain~~. Resolved: `assembleDebug` passes (§2).
2. Whether `WIFI_MODE_FULL_HIGH_PERF` does anything on API 34+ (§7.1).
3. Field-trial syntax for playout delay in M150 (§6.5).
4. `isScreencast` true vs false, and its effect on smoothness (§6.2), to be measured.
5. Some TV H.264 decoders add latency unless a vendor low-latency key or `KEY_LOW_LATENCY` (API 30) is set. Phase 3 checks whether this WebRTC build's `AndroidVideoDecoder` sets it. If not, a decoder factory wrapper that sets it is an option.
