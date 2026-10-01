"""Live diagnosis of the phone currently attached to the production relay.

Connects like RemoteController does (desktop control + attach), discovers the
online agent, sends screen_monitor_start, and reports for a few seconds:
  - whether video frames arrive (is the phone actually capturing?)
  - every control/response message the phone sends back (status / error /
    need_permission for screen_monitor_start), which is the state we normally
    only see in the phone's log.

Observation only: no input is injected into the device.
"""

import json
import socket
import struct
import time

HOST = "167.114.172.44"
PORT = 9000
FLAG_CONTROL = 0x43
FLAG_VIDEO = 0x56


def pack(obj):
    body = json.dumps(obj).encode()
    return bytes([FLAG_CONTROL]) + struct.pack(">I", len(body)) + body


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


def discover_agent(control, timeout=6.0):
    """Read peer_online from the desktop control socket to learn the agent id."""
    control.settimeout(timeout)
    parser = Parser()
    end = time.time() + timeout
    agents = []
    while time.time() < end:
        try:
            data = control.recv(65536)
        except socket.timeout:
            break
        if not data:
            break
        for flag, payload in parser.feed(data):
            if flag != FLAG_CONTROL:
                continue
            try:
                obj = json.loads(payload)
            except ValueError:
                continue
            if obj.get("type") == "peer_online":
                info = {k: obj.get(k) for k in ("id", "model", "os", "ip")}
                print(f"  peer_online: {info}")
                agents.append(obj.get("id"))
        if agents:
            break
    return agents


def main():
    print(f"connecting to relay {HOST}:{PORT} (desktop) ...")
    control = socket.create_connection((HOST, PORT), timeout=6)
    control.sendall(pack({"type": "hello", "role": "desktop", "desktopId": "diag-probe"}))
    agents = discover_agent(control)
    if not agents:
        print("  no agent online — the phone is not registered right now")
        control.close()
        return
    agent_id = agents[0]
    print(f"\nattaching to agent: {agent_id}")

    attach = socket.create_connection((HOST, PORT), timeout=6)
    attach.sendall(pack({"type": "hello", "role": "attach",
                         "agentId": agent_id, "desktopId": "diag-probe"}))
    attach.settimeout(0.2)

    # Ask the phone to start streaming.
    attach.sendall(pack({"type": "command", "action": "screen_monitor_start", "payload": {}}))
    print("sent screen_monitor_start; observing 10s ...\n")

    parser = Parser()
    frames = 0
    pending = {}
    first_frame_at = None
    dims = None
    start = time.time()
    while time.time() - start < 10.0:
        try:
            data = attach.recv(65536)
        except socket.timeout:
            continue
        except OSError as e:
            print(f"  attach socket error: {e}")
            break
        if not data:
            print("  attach socket closed by relay")
            break
        for flag, payload in parser.feed(data):
            if flag == FLAG_CONTROL:
                try:
                    obj = json.loads(payload)
                except ValueError:
                    continue
                t = obj.get("type")
                if t == "register":
                    continue
                # Print everything else: ack, response, error, status.
                print(f"  [ctrl] {json.dumps(obj, ensure_ascii=False)[:300]}")
            elif flag == FLAG_VIDEO and len(payload) >= 20:
                w, h, idx, cnt, total, fid = struct.unpack(">IIHHII", payload[:20])
                dims = (w, h)
                got = pending.get(fid, 0)
                if idx == got:
                    got += 1
                    if got == cnt:
                        pending.pop(fid, None)
                        frames += 1
                        if first_frame_at is None:
                            first_frame_at = time.time() - start
                    else:
                        pending[fid] = got

    print("\n===== RESULT =====")
    print(f"  video frames received : {frames}")
    print(f"  first frame latency   : "
          f"{('%.2fs' % first_frame_at) if first_frame_at is not None else 'NONE'}")
    print(f"  frame size            : {dims}")
    if frames == 0:
        print("  => phone is NOT producing video (capture/projection not active)")
    else:
        print("  => capture works; video path is healthy")

    attach.close()
    control.close()


if __name__ == "__main__":
    main()
