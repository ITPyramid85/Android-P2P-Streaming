"""End-to-end check of the relay fan-out: one phone, two desktops.

Verifies the architecture the relay implements:
  - one receive thread reads the phone and queues packets
  - each desktop's send thread pulls only its own phone's packets
  - input from every desktop reaches the phone
  - screen_monitor_stop from one desktop does not pause the phone while
    another desktop is still watching; the last one does.
"""

import json
import socket
import struct
import threading
import time

HOST = "127.0.0.1"
PORT = 9099

FLAG_CONTROL = 0x43
FLAG_VIDEO = 0x56
CHUNK = 1450
AGENT_ID = "TESTPHONE-abc123"


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
    """Splits a byte stream into (flag, payload) packets."""

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
    """Streams frames only while the relay says a desktop is watching."""

    def __init__(self):
        super().__init__(daemon=True)
        self.sock = socket.create_connection((HOST, PORT), timeout=5)
        self.sock.sendall(pack_control({
            "type": "register", "id": AGENT_ID, "device": "test",
            "model": "TestModel", "os": "12",
        }))
        self.streaming = False
        self.commands = []
        self.lock = threading.Lock()
        self.stop_flag = False
        self.frame_id = 0

    def run(self):
        parser = Parser()
        self.sock.settimeout(0.05)
        jpeg = b"\xff\xd8" + bytes(3000)
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
                    with self.lock:
                        if action:
                            self.commands.append(action)
                    if action == "screen_monitor_start":
                        self.streaming = True
                    elif action == "screen_monitor_stop":
                        self.streaming = False
            except socket.timeout:
                pass
            except OSError:
                break
            now = time.time()
            if self.streaming and now - last > 0.05:
                last = now
                self.frame_id += 1
                try:
                    self.sock.sendall(pack_video(self.frame_id, jpeg))
                except OSError:
                    break

    def seen(self):
        with self.lock:
            return list(self.commands)


class Desktop(threading.Thread):
    """Control connection + attach connection, like RemoteController does."""

    def __init__(self, desktop_id):
        super().__init__(daemon=True)
        self.desktop_id = desktop_id
        self.control = socket.create_connection((HOST, PORT), timeout=5)
        self.control.sendall(pack_control({
            "type": "hello", "role": "desktop", "desktopId": desktop_id,
        }))
        self.attach = socket.create_connection((HOST, PORT), timeout=5)
        self.attach.sendall(pack_control({
            "type": "hello", "role": "attach",
            "agentId": AGENT_ID, "desktopId": desktop_id,
        }))
        self.frames = 0
        self.registered = False
        self.lock = threading.Lock()
        self.stop_flag = False

    def watch(self):
        self.attach.sendall(pack_control({
            "type": "command", "action": "screen_monitor_start", "payload": {},
        }))

    def unwatch(self):
        self.attach.sendall(pack_control({
            "type": "command", "action": "screen_monitor_stop", "payload": {},
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
                if flag == FLAG_CONTROL:
                    try:
                        obj = json.loads(payload)
                    except ValueError:
                        continue
                    if obj.get("type") == "register":
                        with self.lock:
                            self.registered = True
                elif flag == FLAG_VIDEO and len(payload) >= 20:
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


def main():
    failures = []

    def check(name, ok, detail=""):
        print(("PASS  " if ok else "FAIL  ") + name + ("  " + detail if detail else ""))
        if not ok:
            failures.append(name)

    phone = Phone()
    phone.start()
    time.sleep(0.5)

    a = Desktop("desktop-A")
    b = Desktop("desktop-B")
    a.start()
    b.start()
    time.sleep(0.5)

    a.watch()
    b.watch()
    time.sleep(0.8)
    check("relayed video reaches both desktops",
          a.count() > 0 and b.count() > 0, f"A={a.count()} B={b.count()}")

    a.tap()
    b.tap()
    time.sleep(0.4)
    taps = phone.seen().count("input_touch")
    check("input reaches the phone", taps == 2, f"{taps} taps")

    # Closing the socket (not a command on the signaling channel) is what ends
    # a session. One desktop leaving must not pause the phone for the other.
    a.stop_flag = True
    try:
        a.attach.close()
    except OSError:
        pass
    time.sleep(0.6)
    stops = phone.seen().count("screen_monitor_stop")
    check("one desktop leaving does not pause the phone", stops == 0,
          f"{stops} stops seen")

    b.stop_flag = True
    try:
        b.attach.close()
    except OSError:
        pass
    time.sleep(0.8)
    stops = phone.seen().count("screen_monitor_stop")
    check("the last desktop leaving pauses the phone", stops == 1,
          f"{stops} stops seen")

    a.stop_flag = b.stop_flag = phone.stop_flag = True
    time.sleep(0.3)
    for s in (a.control, a.attach, b.control, b.attach, phone.sock):
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
