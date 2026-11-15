#!/usr/bin/env python3
"""Start, stop, and restart an isolated Forge development server; never touches an existing world."""

import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
run = root / "run" / "integration"
run.mkdir(parents=True, exist_ok=True)
if (run / "world").exists():
    sys.exit("Refusing to reuse a world: move run/integration aside before running this test again.")
(run / "eula.txt").write_text("eula=true\n")
(run / "server.properties").write_text(
    "server-ip=127.0.0.1\nserver-port=25599\nonline-mode=false\n"
    "level-name=world\nlevel-type=FLAT\nspawn-protection=0\nview-distance=3\n"
    "generate-structures=false\nmax-tick-time=-1\n"
)

for phase in ("seed", "restore"):
    log = run / (phase + ".log")
    with log.open("w") as output:
        process = subprocess.Popen(
            [str(root / "gradlew"), "--no-daemon", "--no-configuration-cache", "runServer", "-PintegrationPhase=" + phase],
            cwd=root,
            stdout=output,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            code = process.wait(timeout=600)
        except subprocess.TimeoutExpired:
            import signal
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            code = -1
    text = log.read_text(errors="replace")
    if code or "AMCL_INTEGRATION_PASS " + phase not in text or "AMCL_INTEGRATION_FAIL" in text:
        print(text[-16000:])
        sys.exit("Integration phase failed: " + phase)
    print("PASS: " + phase, flush=True)
