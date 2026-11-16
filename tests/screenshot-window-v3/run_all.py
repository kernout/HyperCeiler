#!/usr/bin/env python3
"""Run portable screenshot v3, callback and recorder suites against this checkout."""
from pathlib import Path
import os, subprocess, sys, tempfile
suite = Path(__file__).resolve().parent
root = suite.parents[1]
out = Path(os.environ['HC_TEST_OUTPUT_DIR']) if 'HC_TEST_OUTPUT_DIR' in os.environ else Path(tempfile.mkdtemp(prefix='hc-screenshot-reports-'))
out.mkdir(parents=True, exist_ok=True)
env = dict(os.environ, HC_TEST_OUTPUT_DIR=str(out), PYTHONDONTWRITEBYTECODE='1')
for script in [suite/'run_island.py', suite/'run_tests.py', root/'tests/screenshot-recorder-privacy/run_tests.py', root/'tests/screenshot-window/run_tests.py']:
    print('RUN', script.relative_to(root), flush=True)
    subprocess.run([sys.executable, '-B', str(script)], cwd=root, env=env, check=True)
print('PASS all screenshot suites; reports:', out)
