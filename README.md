# Android P2P Streaming

Android + desktop remote screen control. Phones register on a TCP/UDP relay, then stream video and input over P2P when possible (UDP hole punch; WebRTC data channels as fallback).

## Components

| Part | Path | Role |
|------|------|------|
| **Agent** | `Client/` | Android app: MediaProjection capture, JPEG over UDP, remote input |
| **Desktop** | `Server/` | Qt 6 `RemoteController`: device list, live preview, touch/keys |
| **Relay** | `Relay/` | Signaling on TCP 9000; UDP video/control when P2P is not ready |
| **Admin** | `AdminPanel/` | Operator accounts (`Shared/` SQLite auth) |

## Session flow

1. Phone and desktop connect outbound to the relay.
2. After pairing, video and input try **direct P2P UDP**; otherwise they stay on the relay.
3. Opening a preview sends `screen_monitor_start`. The phone captures the screen; the desktop paints the newest frame.
4. The operator drives the phone from the preview (touch, Back/Home/Recents, keys).
5. Closing the window, Stop, or the **1-hour timer** sends `screen_monitor_stop`. A client with an open preview is hidden from the table until that window closes.

## Build

**Android** (JDK 17):

```bat
cd Client
gradlew.bat assembleDebug
```

APK: `Client\build-app\outputs\apk\debug\app-debug.apk`

**Desktop** (CMake + Ninja + Qt 6 MinGW):

```bat
cd Server
build.bat
```

**Relay:**

```bat
cd Relay
build.bat
rc-relay.exe --port 9000
```

Not in git: Gradle/CMake build trees, APKs, `local.properties`, `webrtc.cfg`, installer output, and unused FFmpeg tools.
