# RC relay (signaling only)

Desktop and Agent dial out here to exchange connection info. Once the relay has
confirmed the phone is online, screen video and input go **peer-to-peer over
WebRTC data channels**. This process does not forward media.

- **TCP 9000** — register, pairing, SDP, ICE candidates
- **STUN** — `stun:stun.l.google.com:19302` by default (sent in `pair_ok`)
- **TURN** — optional, `webrtc.cfg` next to the exe (`turn`, `turn_user`, `turn_pass`)

Public address: `167.114.172.44` (open **TCP 9000**, and **UDP 3478** if TURN is enabled).

```
phone ──TCP──► relay ◄──TCP── desktop     connection info only
phone ◄════ WebRTC P2P (STUN / TURN) ════► desktop
```

## Build

```bat
cd Relay
build.bat
```

## Run

```bat
rc-relay.exe --port 9000
rc-relay.exe install
```

Stdout logs only connect events:

`2026-08-27 17:32:01 connect 1.2.3.4`

## Desktop / Agent

- Desktop and Agent always dial out to this relay for signaling.
- After `pair_ok`, video (`V`) and control (`C`) move to WebRTC data channels `video` and `ctrl`.
- Copy `webrtc.cfg.example` to `webrtc.cfg` to point both peers at your own STUN/TURN.
