#!/usr/bin/env python3
"""Decode the one private Actions secret into ephemeral signing files, never git."""
import base64
import io
import json
import os
from pathlib import Path
import re
import tempfile
import zipfile


def prepare():
    output = Path(os.environ['GITHUB_OUTPUT'])
    encoded = os.environ.get('ANDROID_RELEASE_SIGNING_B64', '')
    if not encoded:
        with output.open('a') as f:
            f.write('ready=false\n')
        print('Release signing is not configured; signed release publication skipped.')
        return
    try:
        if len(encoded) > 60000:
            raise ValueError()
        data = base64.b64decode(encoded.strip(), validate=True)
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if set(archive.namelist()) != {'release.jks', 'signing.json'}:
                raise ValueError()
            if any(i.file_size > 32768 for i in archive.infolist()):
                raise ValueError()
            key = archive.read('release.jks')
            config = json.loads(archive.read('signing.json'))
        for field in ('keyAlias', 'storePassword', 'keyPassword'):
            value = config[field]
            if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{6,128}', value):
                raise ValueError()
        if not key:
            raise ValueError()
    except Exception:
        raise SystemExit('Invalid release signing secret (contents withheld).') from None
    if os.environ.get('GITHUB_ACTIONS') == 'true':
        for field in ('storePassword', 'keyPassword'):
            print('::add-mask::' + config[field])
    folder = Path(tempfile.mkdtemp(prefix='radmin-signing-', dir=os.environ['RUNNER_TEMP']))
    keystore = folder / 'release.jks'
    properties = folder / 'signing.properties'
    keystore.write_bytes(key)
    properties.write_text('storeFile=' + keystore.as_posix() + '\n' + ''.join(
        field + '=' + config[field] + '\n' for field in ('keyAlias', 'storePassword', 'keyPassword')))
    keystore.chmod(0o600)
    properties.chmod(0o600)
    with output.open('a') as f:
        f.write('ready=true\npropertiesFile=' + str(properties) + '\n')
    print('Release signing prepared.')


if __name__ == '__main__':
    prepare()
