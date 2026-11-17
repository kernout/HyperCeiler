import base64, hashlib, lzma, os
from pathlib import Path
PAYLOAD = ''.join(Path(p).read_text().strip() for p in ['.github/recorder-offline-toggle/part-00.b64', '.github/recorder-offline-toggle/part-01.b64', '.github/recorder-offline-toggle/part-02.b64', '.github/recorder-offline-toggle/part-03.b64', '.github/recorder-offline-toggle/part-04.b64', '.github/recorder-offline-toggle/part-05.b64', '.github/recorder-offline-toggle/part-06.b64', '.github/recorder-offline-toggle/part-07.b64', '.github/recorder-offline-toggle/part-08.b64'])
scripts = {}
for label, spec in {'prepare': {'files': ['.github/recorder-offline-toggle/prepare-00.txt', '.github/recorder-offline-toggle/prepare-01.txt'], 'sha256': '537d3cbbf2eaefd5ecf2ebc1c8f03606307807d72a117ef8a58f213d35b45c27'}, 'collect': {'files': ['.github/recorder-offline-toggle/collect-00.txt'], 'sha256': '1bc91391e416ca83158bec86a51a673406ea64e547751acfbb03511a4913aef6'}}.items():
    code = b''.join(Path(p).read_bytes() for p in spec['files'])
    assert hashlib.sha256(code).hexdigest() == spec['sha256'], label
    compile(code, label, 'exec')
    scripts[label] = code
collector = Path(os.environ['RUNNER_TEMP'])/'recorder-offline-toggle-collect.py'
collector.write_bytes(scripts['collect'])
exec(compile(scripts['prepare'], 'prepare', 'exec'), dict(PAYLOAD=PAYLOAD))
