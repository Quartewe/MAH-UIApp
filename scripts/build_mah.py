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
    parser.add_argument("--uiapp-version",
                        help="Internal UIApp release version used by the updater")
    parser.add_argument("--version", "--project-version", dest="project_version", required=True, help="Version of the embedded MAH checkout")
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--skip-runtime", action="store_true", help="Reuse previously prepared matching runtimes")
    args = parser.parse_args()
    if not args.uiapp_version:
        # The last tag reachable from this checkout is the internal UIApp release.
        result = subprocess.run(["git", "describe", "--tags", "--abbrev=0", "--match", "v*"],
                                cwd=APP, capture_output=True, text=True, check=False)
        args.uiapp_version = result.stdout.strip() if result.returncode == 0 else "0.0.0"
    mah = args.mah.resolve()
    run(sys.executable, mah / "tools/build_android.py", "--resources", args.resources.resolve(), "--version", args.project_version)
    if not args.skip_runtime:
        run(sys.executable, APP / "scripts/setup_maa_framework.py", "--tag", FRAMEWORK, "--abi", args.abi)
        abis = ["arm64-v8a", "x86_64"] if args.abi == "all" else [args.abi]
        run(sys.executable, APP / "scripts/build_agent_bundle.py", "--core-tag", AGENT_CORE,
            "--requirements", mah / "agent/requirements.txt", "--out", mah / "build/android/agent",
            *(item for abi in abis for item in ("--abi", abi)))
    env = os.environ.copy()
    env["PI_PROFILE"] = str(mah / "android/pi-profile.yaml")
    env["BUILD_VERSION_NAME"] = args.project_version
    env["BUILD_UIAPP_VERSION"] = args.uiapp_version
    kind = "Release" if args.release else "Debug"
    wrapper = APP / ("gradlew.bat" if os.name == "nt" else "gradlew")
    abi_list = "arm64-v8a,x86_64" if args.abi == "all" else args.abi
    run(wrapper, f":app:assemble{kind}", f"-Pbuild.{kind.lower()}Abi={abi_list}",
        f"-Ppi.profile={mah / 'android/pi-profile.yaml'}", f"-Pbuild.versionName={args.project_version}",
        f"-Pbuild.projectVersion={args.project_version}",
        f"-Pbuild.uiappVersion={args.uiapp_version}",
        # Ship the committed profile; publishing must not launch device instrumentation.
        "-Pandroid.baselineProfile.automaticGenerationDuringBuild=false",
        "--console=plain", env=env)


if __name__ == "__main__":
    main()
