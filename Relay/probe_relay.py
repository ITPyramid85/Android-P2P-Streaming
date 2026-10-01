"""Probe the production relay to see whether it is reachable and whether it is
the NEW build (which keeps a `log` connection open) or an OLD build (which drops
any unknown hello type immediately)."""

import json
import socket
import struct
import time

HOST = "167.114.172.44"
PORT = 9000
FLAG_CONTROL = 0x43


def pack(obj):
    body = json.dumps(obj).encode()
    return bytes([FLAG_CONTROL]) + struct.pack(">I", len(body)) + body


def try_connect(label, hello, expect_reply):
    print(f"\n[{label}] connecting to {HOST}:{PORT} ...")
    try:
        s = socket.create_connection((HOST, PORT), timeout=6)
    except Exception as e:
        print(f"  UNREACHABLE: {e}")
        return
    print("  TCP connected")
    try:
        s.sendall(pack(hello))
        s.settimeout(2.5)
        if expect_reply:
            try:
                data = s.recv(512)
                print(f"  reply ({len(data)} bytes): {data[:120]!r}")
            except socket.timeout:
                print("  no reply within 2.5s")
        # Probe whether the socket stays open (new relay holds `log`/`desktop`).
        time.sleep(1.0)
        try:
            s.settimeout(1.0)
            extra = s.recv(1)
            if extra == b"":
                print("  socket CLOSED by relay (likely OLD build for this type)")
            else:
                print(f"  socket still open, extra byte {extra!r}")
        except socket.timeout:
            print("  socket still OPEN after 1s (relay accepted this type)")
    except Exception as e:
        print(f"  error: {e}")
    finally:
        s.close()


def main():
    # A desktop hello: any live relay (old or new) should ack.
    try_connect("desktop", {"type": "hello", "role": "desktop", "desktopId": "probe"}, True)
    # A log hello: only the NEW build keeps this open; old build drops it.
    try_connect("log", {"type": "log", "role": "log", "id": "probe",
                        "session": "probe-sess", "resume": False}, False)


if __name__ == "__main__":
    main()
