"""Signaling relay: connection info is checked, and media crosses TCP until P2P is up.

The relay must
  - refuse an attach when the phone is not registered
  - confirm a live phone with pair_ok (role + iceServers) to both peers
  - forward SDP and ICE only to the paired desktop
  - forward video and input while the data channel is not the path in use
  - tell the phone to stop capture when the last desktop disconnects
"""

import json
import socket
import struct
import threading
import time

HOST = "127.0.0.1"
PORT = 9102
FLAG_CONTROL = 0x43
FLAG_VIDEO = 0x56


def pack_control(obj):
    body = json.dumps(obj).encode()
    return bytes([FLAG_CONTROL]) + struct.pack(">I", len(body)) + body


def pack_video():
    prefix = struct.pack(">IIHHII", 720, 1280, 0, 1, 2, 1)
    payload = prefix + b"\xff\xd8"
    return bytes([FLAG_VIDEO]) + struct.pack(">I", len(payload)) + payload


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


class Peer(threading.Thread):
    def __init__(self, hello):
        super().__init__(daemon=True)
        self.sock = socket.create_connection((HOST, PORT), timeout=5)
        self.sock.sendall(pack_control(hello))
        self.messages = []
        self.video = 0
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
                with self.lock:
                    if flag == FLAG_VIDEO:
                        self.video += 1
                        continue
                    if flag != FLAG_CONTROL:
                        continue
                    try:
                        self.messages.append(json.loads(payload))
                    except ValueError:
                        continue

    def snapshot(self):
        with self.lock:
            return list(self.messages), self.video

    def close(self):
        self.stop_flag = True
        try:
            self.sock.close()
        except OSError:
            pass


def wait_until(predicate, timeout=2.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if predicate():
            return True
        time.sleep(0.05)
    return False


def main():
    failures = []

    def check(name, ok, detail=""):
        print(("PASS  " if ok else "FAIL  ") + name + ("  " + detail if detail else ""))
        if not ok:
            failures.append(name)

    missing = socket.create_connection((HOST, PORT), timeout=5)
    missing.sendall(pack_control({
        "type": "hello", "role": "attach",
        "agentId": "NO-SUCH-PHONE", "desktopId": "desk-x",
    }))
    missing.settimeout(2)
    err = Parser()
    try:
        data = missing.recv(4096)
    except socket.timeout:
        data = b""
    messages = []
    for flag, payload in err.feed(data):
        if flag == FLAG_CONTROL:
            messages.append(json.loads(payload))
    missing.close()
    check("unknown phone is rejected",
          any(m.get("type") == "error" for m in messages),
          str(messages))

    phone = Peer({
        "type": "register", "id": "PHONE-A", "device": "pixel",
        "model": "Test", "os": "14",
    })
    phone.start()
    desk1 = Peer({
        "type": "hello", "role": "attach",
        "agentId": "PHONE-A", "desktopId": "desk-1",
    })
    desk2 = Peer({
        "type": "hello", "role": "attach",
        "agentId": "PHONE-A", "desktopId": "desk-2",
    })
    time.sleep(0.2)
    desk1.start()
    desk2.start()

    def paired():
        phone_msgs, _ = phone.snapshot()
        d1, _ = desk1.snapshot()
        d2, _ = desk2.snapshot()
        roles = [m.get("role") for m in phone_msgs if m.get("type") == "pair_ok"]
        return roles.count("answerer") >= 2 and any(m.get("type") == "pair_ok" and m.get("role") == "offerer" for m in d1) and any(m.get("type") == "pair_ok" and m.get("role") == "offerer" for m in d2)

    check("both desktops are paired with ICE servers", wait_until(paired), "")
    phone_msgs, _ = phone.snapshot()
    pair = next(m for m in phone_msgs if m.get("type") == "pair_ok")
    urls = [s.get("urls") for s in pair.get("iceServers", [])]
    check("pair_ok carries a STUN server", any(str(u).startswith("stun:") for u in urls), str(urls))

    desk1.sock.sendall(pack_control({
        "type": "sdp", "desktopId": "desk-1", "sdpType": "offer", "sdp": "v=0",
    }))
    desk2.sock.sendall(pack_control({
        "type": "sdp", "desktopId": "desk-2", "sdpType": "offer", "sdp": "v=2",
    }))
    phone.sock.sendall(pack_video())
    phone.sock.sendall(pack_control({
        "type": "sdp", "desktopId": "desk-1", "sdpType": "answer", "sdp": "v=answer",
    }))
    desk1.sock.sendall(pack_control({
        "type": "command", "action": "input_touch",
        "payload": {"a": "down", "x": 0.1, "y": 0.2},
    }))

    def routed():
        phone_msgs, phone_video = phone.snapshot()
        d1, d1_video = desk1.snapshot()
        d2, d2_video = desk2.snapshot()
        phone_sdps = [m.get("sdp") for m in phone_msgs if m.get("type") == "sdp"]
        d1_sdps = [m.get("sdp") for m in d1 if m.get("type") == "sdp"]
        d2_sdps = [m.get("sdp") for m in d2 if m.get("type") == "sdp"]
        return (phone_sdps.count("v=0") >= 1 and phone_sdps.count("v=2") >= 1
                and "v=answer" in d1_sdps and "v=answer" not in d2_sdps
                and phone_video == 0 and d1_video >= 1 and d2_video >= 1
                and any(m.get("action") == "input_touch" for m in phone_msgs))

    check("SDP is routed per desktop and video/input cross the relay", wait_until(routed, 2.5))

    desk1.close()
    time.sleep(0.4)
    phone_msgs, _ = phone.snapshot()
    stops = [m for m in phone_msgs if m.get("action") == "screen_monitor_stop"]
    check("one desktop leaving does not stop capture", len(stops) == 0, f"stops={len(stops)}")

    desk2.close()
    def stopped():
        msgs, _ = phone.snapshot()
        return any(m.get("action") == "screen_monitor_stop" for m in msgs)

    check("the last desktop leaving stops capture", wait_until(stopped))

    phone.close()
    print()
    if failures:
        print(f"{len(failures)} FAILED: " + ", ".join(failures))
        return 1
    print("all checks passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
