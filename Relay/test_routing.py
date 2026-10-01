"""Two phones must not see each other's video or input. SDP routing is covered
by test_signal.py. Video and input do cross the relay, but only to the desktop
or phone that is paired with that agent.

Each phone stamps a distinct width into its video prefix, so a desktop that saw a
foreign packet is detectable.
"""

import json
import socket
import struct
import threading
import time

HOST = "127.0.0.1"
PORT = 9098

FLAG_CONTROL = 0x43
FLAG_VIDEO = 0x56
CHUNK = 1450


def pack_control(obj):
    body = json.dumps(obj).encode()
    return bytes([FLAG_CONTROL]) + struct.pack(">I", len(body)) + body


def pack_video(frame_id, jpeg, width, height=1280):
    total = len(jpeg)
    count = (total + CHUNK - 1) // CHUNK
    out = b""
    for i in range(count):
        slice_ = jpeg[i * CHUNK:(i + 1) * CHUNK]
        prefix = struct.pack(">IIHHII", width, height, i, count, total, frame_id)
        out += bytes([FLAG_VIDEO]) + struct.pack(">I", len(prefix) + len(slice_))
        out += prefix + slice_
    return out


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


class Phone(threading.Thread):
    def __init__(self, agent_id, width):
        super().__init__(daemon=True)
        self.agent_id = agent_id
        self.width = width
        self.sock = socket.create_connection((HOST, PORT), timeout=5)
        self.sock.sendall(pack_control({
            "type": "register", "id": agent_id, "device": "test",
            "model": "TestModel", "os": "12",
        }))
        self.streaming = False
        self.inputs = []
        self.lock = threading.Lock()
        self.stop_flag = False

    def run(self):
        parser = Parser()
        self.sock.settimeout(0.05)
        jpeg = b"\xff\xd8" + bytes(2000)
        fid = 0
        last = 0.0
        while not self.stop_flag:
            try:
                data = self.sock.recv(65536)
                if not data:
                    break
                for flag, payload in parser.feed(data):
                    if flag != FLAG_CONTROL:
                        continue
                    try:
                        obj = json.loads(payload)
                    except ValueError:
                        continue
                    action = obj.get("action", "")
                    if action == "screen_monitor_start":
                        self.streaming = True
                    elif action == "screen_monitor_stop":
                        self.streaming = False
                    elif action == "input_touch":
                        with self.lock:
                            self.inputs.append(obj.get("payload", {}).get("from", "?"))
            except socket.timeout:
                pass
            except OSError:
                break
            now = time.time()
            if self.streaming and now - last > 0.05:
                last = now
                fid += 1
                try:
                    self.sock.sendall(pack_video(fid, jpeg, self.width))
                except OSError:
                    break

    def seen_inputs(self):
        with self.lock:
            return list(self.inputs)


class Desktop(threading.Thread):
    def __init__(self, desktop_id, agent_id):
        super().__init__(daemon=True)
        self.desktop_id = desktop_id
        self.attach = socket.create_connection((HOST, PORT), timeout=5)
        self.attach.sendall(pack_control({
            "type": "hello", "role": "attach",
            "agentId": agent_id, "desktopId": desktop_id,
        }))
        self.widths = set()
        self.frames = 0
        self.lock = threading.Lock()
        self.stop_flag = False

    def watch(self):
        self.attach.sendall(pack_control({
            "type": "command", "action": "screen_monitor_start", "payload": {},
        }))

    def tap(self):
        self.attach.sendall(pack_control({
            "type": "command", "action": "input_touch",
            "payload": {"a": "down", "x": 0.5, "y": 0.5, "from": self.desktop_id},
        }))

    def run(self):
        parser = Parser()
        self.attach.settimeout(0.05)
        pending = {}
        while not self.stop_flag:
            try:
                data = self.attach.recv(65536)
                if not data:
                    break
            except socket.timeout:
                continue
            except OSError:
                break
            for flag, payload in parser.feed(data):
                if flag == FLAG_VIDEO and len(payload) >= 20:
                    w, _, idx, cnt, _, fid = struct.unpack(">IIHHII", payload[:20])
                    with self.lock:
                        self.widths.add(w)
                    got = pending.get(fid, 0)
                    if idx == got:
                        got += 1
                        if got == cnt:
                            pending.pop(fid, None)
                            with self.lock:
                                self.frames += 1
                        else:
                            pending[fid] = got

    def snapshot(self):
        with self.lock:
            return self.frames, set(self.widths)


def main():
    failures = []

    def check(name, ok, detail=""):
        print(("PASS  " if ok else "FAIL  ") + name + ("  " + detail if detail else ""))
        if not ok:
            failures.append(name)

    p1 = Phone("PHONE-ONE", 720)
    p2 = Phone("PHONE-TWO", 1080)
    p1.start()
    p2.start()
    time.sleep(0.5)

    d1 = Desktop("desktop-1", "PHONE-ONE")
    d2 = Desktop("desktop-2", "PHONE-TWO")
    d1.start()
    d2.start()
    time.sleep(0.4)

    d1.watch()
    d2.watch()
    time.sleep(2.0)

    f1, w1 = d1.snapshot()
    f2, w2 = d2.snapshot()
    check("desktop-1 receives only its phone",
          f1 > 0 and w1 == {720}, f"{f1} frames widths={sorted(w1)}")
    check("desktop-2 receives only its phone",
          f2 > 0 and w2 == {1080}, f"{f2} frames widths={sorted(w2)}")

    d1.tap()
    d2.tap()
    time.sleep(0.6)
    check("desktop-1 input reached only its phone",
          p1.seen_inputs() == ["desktop-1"], f"{p1.seen_inputs()}")
    check("desktop-2 input reached only its phone",
          p2.seen_inputs() == ["desktop-2"], f"{p2.seen_inputs()}")

    d1.stop_flag = d2.stop_flag = p1.stop_flag = p2.stop_flag = True
    time.sleep(0.3)
    for s in (d1.attach, d2.attach, p1.sock, p2.sock):
        try:
            s.close()
        except OSError:
            pass

    print()
    if failures:
        print(f"{len(failures)} FAILED: " + ", ".join(failures))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
