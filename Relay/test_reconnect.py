"""Teardown check: a phone dropping and coming back must not wedge the relay.

Exercises the path where the single receive thread notices the phone is gone and
the connection thread tears the agent down while a desktop is still attached.
"""

import json
import socket
import struct
import threading
import time

HOST = "127.0.0.1"
PORT = 9097

FLAG_CONTROL = 0x43
FLAG_VIDEO = 0x56
CHUNK = 1450
AGENT_ID = "PHONE-RECONNECT"


def pack_control(obj):
    body = json.dumps(obj).encode()
    return bytes([FLAG_CONTROL]) + struct.pack(">I", len(body)) + body


def pack_video(frame_id, jpeg, width=720, height=1280):
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
    def __init__(self):
        super().__init__(daemon=True)
        self.sock = socket.create_connection((HOST, PORT), timeout=5)
        self.sock.sendall(pack_control({
            "type": "register", "id": AGENT_ID, "device": "test",
            "model": "TestModel", "os": "12",
        }))
        self.streaming = False
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
                    if obj.get("action") == "screen_monitor_start":
                        self.streaming = True
                    elif obj.get("action") == "screen_monitor_stop":
                        self.streaming = False
            except socket.timeout:
                pass
            except OSError:
                break
            now = time.time()
            if self.streaming and now - last > 0.05:
                last = now
                fid += 1
                try:
                    self.sock.sendall(pack_video(fid, jpeg))
                except OSError:
                    break

    def kill(self):
        self.stop_flag = True
        try:
            self.sock.close()
        except OSError:
            pass


class ControlLink(threading.Thread):
    """Desktop control connection: tracks peer_online / peer_offline."""

    def __init__(self):
        super().__init__(daemon=True)
        self.sock = socket.create_connection((HOST, PORT), timeout=5)
        self.sock.sendall(pack_control({
            "type": "hello", "role": "desktop", "desktopId": "desktop-R",
        }))
        self.events = []
        self.lock = threading.Lock()
        self.stop_flag = False

    def run(self):
        parser = Parser()
        self.sock.settimeout(0.05)
        while not self.stop_flag:
            try:
                data = self.sock.recv(65536)
                if not data:
                    break
            except socket.timeout:
                continue
            except OSError:
                break
            for flag, payload in parser.feed(data):
                if flag != FLAG_CONTROL:
                    continue
                try:
                    obj = json.loads(payload)
                except ValueError:
                    continue
                if obj.get("type") in ("peer_online", "peer_offline"):
                    with self.lock:
                        self.events.append(obj["type"])

    def seen(self):
        with self.lock:
            return list(self.events)


class Attach(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.attach = socket.create_connection((HOST, PORT), timeout=5)
        self.attach.sendall(pack_control({
            "type": "hello", "role": "attach",
            "agentId": AGENT_ID, "desktopId": "desktop-R",
        }))
        self.frames = 0
        self.closed = False
        self.lock = threading.Lock()
        self.stop_flag = False

    def watch(self):
        self.attach.sendall(pack_control({
            "type": "command", "action": "screen_monitor_start", "payload": {},
        }))

    def run(self):
        parser = Parser()
        self.attach.settimeout(0.05)
        pending = {}
        while not self.stop_flag:
            try:
                data = self.attach.recv(65536)
                if not data:
                    with self.lock:
                        self.closed = True
                    break
            except socket.timeout:
                continue
            except OSError:
                with self.lock:
                    self.closed = True
                break
            for flag, payload in parser.feed(data):
                if flag == FLAG_VIDEO and len(payload) >= 20:
                    _, _, idx, cnt, _, fid = struct.unpack(">IIHHII", payload[:20])
                    got = pending.get(fid, 0)
                    if idx == got:
                        got += 1
                        if got == cnt:
                            pending.pop(fid, None)
                            with self.lock:
                                self.frames += 1
                        else:
                            pending[fid] = got

    def count(self):
        with self.lock:
            return self.frames

    def is_closed(self):
        with self.lock:
            return self.closed


def main():
    failures = []

    def check(name, ok, detail=""):
        print(("PASS  " if ok else "FAIL  ") + name + ("  " + detail if detail else ""))
        if not ok:
            failures.append(name)

    control = ControlLink()
    control.start()
    time.sleep(0.3)

    phone = Phone()
    phone.start()
    time.sleep(0.5)
    check("desktop was told the phone is online",
          control.seen() == ["peer_online"], f"{control.seen()}")

    view = Attach()
    view.start()
    time.sleep(0.3)
    view.watch()
    time.sleep(1.2)
    check("video is relayed before the drop", view.count() > 0, f"{view.count()} frames")

    # Phone disappears while a desktop is attached and watching.
    phone.kill()
    time.sleep(1.5)
    check("desktop was told the phone went offline",
          control.seen() == ["peer_online", "peer_offline"], f"{control.seen()}")
    check("the attach connection was dropped with the phone", view.is_closed())

    # Same phone id comes back; a fresh attach must stream again.
    phone2 = Phone()
    phone2.start()
    time.sleep(0.8)
    check("desktop was told the phone is back",
          control.seen() == ["peer_online", "peer_offline", "peer_online"],
          f"{control.seen()}")

    view2 = Attach()
    view2.start()
    time.sleep(0.3)
    view2.watch()
    time.sleep(1.2)
    check("video is relayed after reconnect", view2.count() > 0,
          f"{view2.count()} frames")

    control.stop_flag = view.stop_flag = view2.stop_flag = True
    phone2.kill()
    time.sleep(0.3)
    for s in (control.sock, view.attach, view2.attach):
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
