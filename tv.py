#!/usr/bin/env python3
"""Tiny ADB client for the TV (macOS Local Network privacy can block the stock adb).

Set TV_IP to the TV's address. The first connection makes a key in ~/.cleanyourtv_adbkey and the TV asks
"Allow USB debugging?": tick "Always allow" and press OK.

Usage:
  tv.py sh  '<shell command>'        run one shell command
  tv.py shot <out.png>               screenshot from the TV
  tv.py key <KEYCODE> [KEYCODE ...]  inject remote key presses
  tv.py push <local> <remote>        push a file (base64 chunks, md5-verified)
  tv.py pull <remote> <local>        pull a file (base64, md5-verified)
"""
import base64, hashlib, os, sys, time
from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HOST = os.environ.get("TV_IP", "")
if not HOST:
    sys.exit("Set TV_IP to your TV's address first, e.g.  export TV_IP=192.168.1.50")
KEY = os.path.expanduser("~/.cleanyourtv_adbkey")


def connect():
    if not os.path.exists(KEY):
        keygen(KEY)
    with open(KEY) as f:
        priv = f.read()
    with open(KEY + ".pub") as f:
        pub = f.read()
    dev = AdbDeviceTcp(HOST, 5555, default_transport_timeout_s=30.0)
    # Long auth timeout: first connect waits for "Allow USB debugging?" on the TV.
    dev.connect(rsa_keys=[PythonRSASigner(pub, priv)], auth_timeout_s=120)
    return dev


def sh(dev, cmd, timeout=120):
    return dev.shell(cmd, timeout_s=timeout, read_timeout_s=timeout)


def push(dev, local, remote, chunk=48000):
    data = open(local, "rb").read()
    b64 = base64.b64encode(data).decode()
    tmp = remote + ".b64"
    sh(dev, f"rm -f '{tmp}'")
    for i in range(0, len(b64), chunk):
        sh(dev, f"echo -n '{b64[i:i+chunk]}' >> '{tmp}'")
    sh(dev, f"base64 -d '{tmp}' > '{remote}' && rm -f '{tmp}'")
    want = hashlib.md5(data).hexdigest()
    got = sh(dev, f"md5sum '{remote}'").split()[0]
    if want != got:
        sys.exit(f"md5 mismatch: {want} != {got}")
    print(f"pushed {len(data)} bytes, md5 {got} OK")


def pull(dev, remote, local):
    want = sh(dev, f"md5sum '{remote}'").split()[0]
    b64 = sh(dev, f"base64 '{remote}'", timeout=300)
    data = base64.b64decode("".join(b64.split()))
    if hashlib.md5(data).hexdigest() != want:
        sys.exit("md5 mismatch on pull")
    open(local, "wb").write(data)
    return len(data)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    op, args = sys.argv[1], sys.argv[2:]
    dev = connect()
    try:
        if op == "sh":
            print(sh(dev, args[0]), end="")
        elif op == "shot":
            sh(dev, "screencap -p /data/local/tmp/shot.png")
            n = pull(dev, "/data/local/tmp/shot.png", args[0])
            print(f"saved {args[0]} ({n} bytes)")
        elif op == "key":
            for k in args:
                if k.startswith("sleep"):
                    time.sleep(float(k[5:] or 1))
                    continue
                sh(dev, f"input keyevent {k}")
                time.sleep(0.35)
        elif op == "push":
            push(dev, args[0], args[1])
        elif op == "pull":
            print(pull(dev, args[0], args[1]), "bytes")
        else:
            sys.exit(__doc__)
    finally:
        dev.close()


if __name__ == "__main__":
    main()
