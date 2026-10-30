#!/usr/bin/env python3
"""Assemble the Cloudstream builds branch. Requires a successful Gradle build."""
import argparse
import json
from pathlib import Path
import re
import shutil
import zipfile


def prepare(repository: str, output: Path, root: Path) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Repository als OWNER/REPO angeben.")
    archive = root / "ParamountPlus/build/ParamountPlus.cs3"
    with zipfile.ZipFile(archive) as package:
        if not {"classes.dex", "manifest.json"}.issubset(package.namelist()):
            raise ValueError("Ungültiges Cloudstream-Paket.")
    plugins = json.loads((root / "build/plugins.json").read_text())
    if len(plugins) != 1 or plugins[0]["internalName"] != "ParamountPlus":
        raise ValueError("Unerwartete Plugin-Liste.")
    base = f"https://raw.githubusercontent.com/{repository}/builds"
    plugins[0]["url"] = f"{base}/{archive.name}"
    plugins[0]["repositoryUrl"] = f"https://github.com/{repository}"
    manifest = json.loads((root / "repo.json").read_text())
    manifest["pluginLists"] = [f"{base}/plugins.json"]
    output.mkdir(parents=True, exist_ok=True)
    shutil.copy2(archive, output / archive.name)
    for filename, data in [("repo.json", manifest), ("plugins.json", plugins)]:
        (output / filename).write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True, help="OWNER/REPO")
    parser.add_argument("--output", type=Path, default=Path("dist"))
    args = parser.parse_args()
    prepare(args.repository, args.output, Path(__file__).resolve().parents[1])
