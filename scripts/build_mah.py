"""Build the MAH Android app from a sibling MAH checkout (no game/device actions)."""
from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

APP = Path(__file__).resolve().parents[1]
FRAMEWORK = "v5.14.2"
AGENT_CORE = "3.13.15-maafw5.14.2"


def run(*args, env=None):
    subprocess.run([str(arg) for arg in args], cwd=APP, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mah", type=Path, default=APP.parent / "MAH")
    parser.add_argument("--resources", type=Path, default=APP.parent / "mah_res")
    parser.add_argument("--abi", choices=("arm64-v8a", "x86_64", "all"), default="all")
    parser.add_argument("--version", default="v1.0.1-android.1")
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--skip-runtime", action="store_true", help="Reuse previously prepared matching runtimes")
    args = parser.parse_args()
    mah = args.mah.resolve()
    run(sys.executable, mah / "tools/build_android.py", "--resources", args.resources.resolve(), "--version", args.version)
    if not args.skip_runtime:
        run(sys.executable, APP / "scripts/setup_maa_framework.py", "--tag", FRAMEWORK, "--abi", args.abi)
        abis = ["arm64-v8a", "x86_64"] if args.abi == "all" else [args.abi]
        run(sys.executable, APP / "scripts/build_agent_bundle.py", "--core-tag", AGENT_CORE,
            "--requirements", mah / "agent/requirements.txt", "--out", mah / "build/android/agent",
            *(item for abi in abis for item in ("--abi", abi)))
    env = os.environ.copy()
    env["PI_PROFILE"] = str(mah / "android/pi-profile.yaml")
    env["BUILD_VERSION_NAME"] = args.version
    kind = "Release" if args.release else "Debug"
    wrapper = APP / ("gradlew.bat" if os.name == "nt" else "gradlew")
    abi_list = "arm64-v8a,x86_64" if args.abi == "all" else args.abi
    run(wrapper, f":app:assemble{kind}", f"-Pbuild.{kind.lower()}Abi={abi_list}",
        f"-Ppi.profile={mah / 'android/pi-profile.yaml'}", f"-Pbuild.versionName={args.version}",
        "--console=plain", env=env)


if __name__ == "__main__":
    main()
