import base64, hashlib, lzma, os
from pathlib import Path
PAYLOAD = ''.join(Path(p).read_text().strip() for p in ['.github/recorder-gesture-fix/part-00.b64', '.github/recorder-gesture-fix/part-01.b64', '.github/recorder-gesture-fix/part-02.b64', '.github/recorder-gesture-fix/part-03.b64', '.github/recorder-gesture-fix/part-04.b64'])
scripts = {}
for label, spec in {'prepare': {'files': ['.github/recorder-gesture-fix/prepare-00.txt', '.github/recorder-gesture-fix/prepare-01.txt'], 'sha256': 'bf9f184e8d3ad17cd0cfdac1ccb60dea0175175a31ae32d8e92a61f3c6b051c3'}, 'collect': {'files': ['.github/recorder-gesture-fix/collect-00.txt'], 'sha256': 'd07171095580599fe43ff924283f6c27f01ecd0a9c753b9c30da4fccbd16cd5b'}}.items():
    code = b''.join(Path(p).read_bytes() for p in spec['files'])
    assert hashlib.sha256(code).hexdigest() == spec['sha256'], label
    compile(code, label, 'exec')
    scripts[label] = code
collector = Path(os.environ['RUNNER_TEMP'])/'recorder-gesture-fix-collect.py'
collector.write_bytes(scripts['collect'])
exec(compile(scripts['prepare'], 'prepare', 'exec'), dict(PAYLOAD=PAYLOAD))
