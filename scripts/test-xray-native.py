#!/usr/bin/env python3
"""Optional native smoke test on Linux using the SAME libXray release as Android.
Only synthetic generated configs and loopback sockets; no VLESS server connection.
Run JVM tests first to generate app/build/test-artifacts/xray-profile.json.
"""
import argparse
import ctypes
import json
from pathlib import Path
import socket
import hashlib
import io
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('library', type=Path, nargs='?')
    parser.add_argument('--download', action='store_true', help='Fetch verified official Linux release for CI')
    args = parser.parse_args()
    library = args.library
    if args.download:
        cache = ROOT / 'build/native-smoke'
        cache.mkdir(parents=True, exist_ok=True)
        archive = cache / 'libxray-linux.zip'
        expected = 'aeeffb921d5e2e9309d44d3f3b9cff90104c09ffe2531a6d66b2fedeac212f8d'
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--connect-timeout', '20', '--max-time', '180',
                'https://github.com/XTLS/libXray/releases/download/v26.10.10/libxray-linux-x64.zip', '-o', str(archive)], check=True)
        data = archive.read_bytes()
        assert hashlib.sha256(data).hexdigest() == expected, 'native smoke artifact checksum mismatch'
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            library = cache / 'libXray.so'
            library.write_bytes(z.read('libxray-linux-x64/libXray.so'))
    if library is None:
        parser.error('pass a Linux library path or --download')
    lib = ctypes.CDLL(str(library.resolve()))
    lib.CGoInvoke.argtypes = [ctypes.c_char_p]
    lib.CGoInvoke.restype = ctypes.c_void_p
    lib.CGoFree.argtypes = [ctypes.c_void_p]
    lib.CGoFree.restype = None

    def invoke(method, payload=None):
        raw = json.dumps({'apiVersion': 3, 'method': method, 'payload': payload or {}}).encode()
        ptr = lib.CGoInvoke(raw)
        if not ptr:
            raise AssertionError('native call returned null')
        try:
            response = json.loads(ctypes.string_at(ptr).decode())
        finally:
            lib.CGoFree(ptr)
        assert response['success'], response.get('error')
        return response.get('data') or {}

    fixture = ROOT / 'app/build/test-artifacts/xray-profile.json'
    if not fixture.exists():
        fixture = ROOT / 'build/test-artifacts/xray-profile.json'
    config = json.loads(fixture.read_text())
    assert config['outbounds'][0]['settings']['vnext'][0]['address'] == '127.0.0.1', 'synthetic fixture required'
    version = invoke('xrayVersion')['version']
    for _ in range(2):
        with socket.socket() as reserve:
            reserve.bind(('127.0.0.1', 0))
            port = reserve.getsockname()[1]
        config['inbounds'][0]['port'] = port
        invoke('testXray', {'xrayJson': json.dumps(config)})
        try:
            invoke('runXray', {'xrayJson': json.dumps(config)})
            assert invoke('getXrayState')['running']
            with socket.create_connection(('127.0.0.1', port), 2) as client:
                client.settimeout(2)
                client.sendall(bytes([5, 1, 2]))
                assert client.recv(2) == bytes([5, 2])
                user, password = b'test-user', b'synthetic-secret'
                client.sendall(bytes([1, len(user)]) + user + bytes([len(password)]) + password)
                assert client.recv(2) == bytes([1, 0]), 'SOCKS authentication rejected'
            with socket.create_connection(('127.0.0.1', port), 2) as client:
                client.settimeout(2)
                client.sendall(bytes([5, 1, 0]))
                assert client.recv(2) == bytes([5, 255]), 'unauthenticated SOCKS unexpectedly accepted'
        finally:
            invoke('stopXray')
        assert not invoke('getXrayState')['running']
        try:
            client = socket.create_connection(('127.0.0.1', port), .5)
        except OSError:
            pass
        else:
            client.close()
            raise AssertionError('SOCKS listener survived stopXray')
    print(f'PASS: libXray {version}, generated Reality/TCP config, authenticated loopback SOCKS, anonymous rejection, 2 start/stop cycles. No remote connections.')

if __name__ == '__main__':
    main()
