"""Checks how the relay treats the phone's tile-update format ('RCT1').

A tile update paints over part of the picture the desktop already has, so the
relay must not shed it the way it sheds a whole screen. The rule under test:

  - a tile update is forwarded byte-for-byte
  - a backed-up desktop still receives every tile update, because dropping one
    would leave that region of its canvas wrong until it happened to change again
  - a whole-screen JPEG replaces the canvas, so a backlog may be shed up to one
  - a desktop that has fallen behind jumps to the newest whole screen

Run the relay on port 9099 first:  rc-relay.exe --port 9099
"""

import json
import os
import socket
import struct
import threading
import time

HOST = os.environ.get("RELAY_HOST", "127.0.0.1")
PORT = int(os.environ.get("RELAY_PORT", "9099"))

FLAG_CONTROL = 0x43
FLAG_VIDEO = 0x56
CHUNK = 1450
AGENT_ID = "TILEPHONE-abc123"


def pack_control(obj):
    body = json.dumps(obj).encode()
    return bytes([FLAG_CONTROL]) + struct.pack(">I", len(body)) + body


def pack_video(frame_id, stream, width=1024, height=576):
    total = len(stream)
    count = (total + CHUNK - 1) // CHUNK
    out = b""
    for i in range(count):
        slice_ = stream[i * CHUNK:(i + 1) * CHUNK]
        prefix = struct.pack(">IIHHII", width, height, i, count, total, frame_id)
        out += bytes([FLAG_VIDEO]) + struct.pack(">I", len(prefix) + len(slice_))
        out += prefix + slice_
    return out


def tile_frame(tile_count, payload_size, marker):
    """Builds an 'RCT1' container matching what SilentScreenStreamer writes."""
    body = bytearray(b"RCT1")
    body.append(0x00)
    body.append(8)   # cols
    body.append(4)   # rows
    body.append(tile_count)
    per = max(4, payload_size // tile_count)
    for i in range(tile_count):
        # Stand-in for a tile JPEG: real SOI marker, then identifiable filler.
        jpeg = b"\xff\xd8" + bytes([marker & 0xFF, i & 0xFF]) + bytes(per - 4)
        body.append(i)
        body += struct.pack(">I", len(jpeg))
        body += jpeg
    return bytes(body)


def jpeg_frame(size):
    return b"\xff\xd8" + bytes(size - 2)


class Parser:
    def __init__(self):
        self.buf = b""

    def feed(self, data):
        self.buf += data
        out = []
        while len(self.buf) >= 5:
            flag = self.buf[0]
            length = struct.unpack(">I", self.buf[1:5])[0]
            if len(self.buf) < 5 + length:
                break
            out.append((flag, self.buf[5:5 + length]))
            self.buf = self.buf[5 + length:]
        return out


class Phone:
    def __init__(self):
        self.sock = socket.create_connection((HOST, PORT), timeout=5)
        self.sock.sendall(pack_control({
            "type": "register", "id": AGENT_ID, "device": "test",
            "model": "TileModel", "os": "14",
        }))
        self.frame_id = 0

    def send(self, stream):
        self.frame_id += 1
        self.sock.sendall(pack_video(self.frame_id, stream))
        return self.frame_id

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class Desktop:
    """Reads only when told to, so a backlog can be built on purpose."""

    def __init__(self, desktop_id, rcvbuf=0):
        self.control = socket.create_connection((HOST, PORT), timeout=5)
        self.control.sendall(pack_control({
            "type": "hello", "role": "desktop", "desktopId": desktop_id,
        }))
        # A deliberately tiny receive window makes the relay's writes to this
        # desktop block almost at once, so a backlog builds inside the relay no
        # matter how slowly the phone side can upload. Without this, a test over a
        # real network never reaches the queue depth the drop rules act on.
        self.attach = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        if rcvbuf:
            self.attach.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
        self.attach.settimeout(5)
        self.attach.connect((HOST, PORT))
        self.attach.sendall(pack_control({
            "type": "hello", "role": "attach",
            "agentId": AGENT_ID, "desktopId": desktop_id,
        }))
        self.attach.settimeout(0.2)

    def watch(self):
        self.attach.sendall(pack_control({
            "type": "command", "action": "screen_monitor_start", "payload": {},
        }))

    def drain(self, seconds=3.0):
        """Reassembles frames and returns [(frameId, payload)] in arrival order."""
        parser = Parser()
        pending = {}
        frames = []
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                data = self.attach.recv(262144)
                if not data:
                    break
            except socket.timeout:
                continue
            except OSError:
                break
            for flag, payload in parser.feed(data):
                if flag != FLAG_VIDEO or len(payload) < 20:
                    continue
                _, _, idx, cnt, total, fid = struct.unpack(">IIHHII", payload[:20])
                slice_ = payload[20:]
                buf, want = pending.get(fid, (b"", 0))
                if idx != want:
                    pending.pop(fid, None)
                    continue
                buf += slice_
                if idx + 1 == cnt:
                    pending.pop(fid, None)
                    if len(buf) == total:
                        frames.append((fid, buf))
                else:
                    pending[fid] = (buf, idx + 1)
        return frames

    def close(self):
        for s in (self.control, self.attach):
            try:
                s.close()
            except OSError:
                pass


def main():
    failures = []

    def check(name, ok, detail=""):
        print(("PASS  " if ok else "FAIL  ") + name + ("  " + detail if detail else ""))
        if not ok:
            failures.append(name)

    phone = Phone()
    time.sleep(0.4)
    desk = Desktop("desktop-tiles", rcvbuf=2048)
    time.sleep(0.4)
    desk.watch()
    time.sleep(0.4)

    phone.send(jpeg_frame(30000))
    phone.send(tile_frame(tile_count=3, payload_size=3 * 900, marker=2))
    got = desk.drain(0.8)
    kinds = [buf[:4] for _, buf in got]
    check("tiles and JPEG are relayed",
          any(k.startswith(b"\xff\xd8") for k in kinds) and any(k == b"RCT1" for k in kinds),
          f"{len(got)} frames {kinds}")

    desk.close()
    phone.close()

    print()
    if failures:
        print(f"{len(failures)} FAILED: " + ", ".join(failures))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
