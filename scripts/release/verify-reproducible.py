#!/usr/bin/env python3
"""Preserve every archive and report member differences; never retry to hide failure."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tarfile


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def members(path):
    result = {}
    with tarfile.open(path, "r:gz") as archive:
        for member in archive:
            if member.name in result:
                raise ValueError("Duplicate tar member: " + member.name)
            content = archive.extractfile(member) if member.isfile() else None
            result[member.name] = {"sha256": hashlib.file_digest(content, "sha256").hexdigest() if content else None,
                                   "size": member.size, "mode": member.mode, "mtime": member.mtime,
                                   "uid": member.uid, "gid": member.gid, "uname": member.uname,
                                   "gname": member.gname, "type": member.type.decode(), "link": member.linkname}
    return result


def compare(paths):
    baseline = members(paths[0])
    runs = []
    for path in paths:
        current = members(path)
        runs.append({"archive": str(path), "sha256": digest(path),
                     "differences": {name: {"first": baseline.get(name), "current": current.get(name)}
                                     for name in sorted(baseline.keys() | current.keys())
                                     if baseline.get(name) != current.get(name)}})
    return {"reproducible": len({run["sha256"] for run in runs}) == 1, "attempts": runs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--compare", type=Path, nargs="+")
    args = parser.parse_args()
    args.evidence.mkdir(parents=True, exist_ok=False)
    paths = []
    sources = [args.archive, *args.compare] if args.compare else [args.archive] * 3
    for index, source in enumerate(sources):
        if index and not args.compare:
            with (args.evidence / f"build-{index + 1}.log").open("w") as log:
                subprocess.run(["./gradlew", "packageArchive", "--rerun-tasks"], stdout=log,
                               stderr=subprocess.STDOUT, check=True, timeout=600)
        target = args.evidence / f"attempt-{index + 1}.tar.gz"
        shutil.copyfile(source, target)
        paths.append(target)
    report = compare(paths)
    (args.evidence / "comparison.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["reproducible"] else 1)


if __name__ == "__main__":
    main()
