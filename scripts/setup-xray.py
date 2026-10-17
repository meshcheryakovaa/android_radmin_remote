#!/usr/bin/env python3
"""Fetch official libXray Android release; verify archive AND AAR before use."""
from pathlib import Path
import argparse
import hashlib
import io
import os
import subprocess
import tempfile
import zipfile

VERSION = 'v26.10.10'
URL = f'https://github.com/XTLS/libXray/releases/download/{VERSION}/libxray-android.zip'
ARCHIVE_SHA256 = 'aad01c82eb82e98702838f7b4ac3f33dba1ae5ff4102538e5ca7f05bbab57437'
AAR_SHA256 = 'e2ca36b011f4c3fe65c23d3a49d0298591bb7aaf34e0a8e91e946e5e5bdbc3f5'
ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'app/libs/libXray.aar'

def digest(data):
    return hashlib.sha256(data).hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, help='Use an already downloaded official ZIP')
    args = parser.parse_args()
    if DEST.is_file() and digest(DEST.read_bytes()) == AAR_SHA256:
        print(f'libXray {VERSION}: verified')
        return
    with tempfile.TemporaryDirectory(prefix='openrad-xray-') as tmp:
        archive = args.archive or Path(tmp) / 'libxray-android.zip'
        if args.archive is None:
            subprocess.run(['curl', '--fail', '--location', '--retry', '3', '--connect-timeout', '20',
                            '--max-time', '240', URL, '--output', str(archive)], check=True)
        data = archive.read_bytes()
        if digest(data) != ARCHIVE_SHA256:
            raise SystemExit('libXray archive checksum mismatch')
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            aar = z.read('libxray-android/libXray.aar')
        if digest(aar) != AAR_SHA256:
            raise SystemExit('libXray AAR checksum mismatch')
        DEST.parent.mkdir(parents=True, exist_ok=True)
        temp_dest = DEST.with_suffix('.aar.tmp')
        temp_dest.write_bytes(aar)
        os.replace(temp_dest, DEST)
        print(f'libXray {VERSION}: downloaded and verified')

if __name__ == '__main__':
    main()
