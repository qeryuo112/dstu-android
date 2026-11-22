"""Verify extracted Cargo sources against each locked registry archive without modifying them."""

import hashlib
import json
import pathlib
import sys
import tarfile
import tomllib


def verify(root: pathlib.Path, cargo_home: pathlib.Path) -> None:
    lock = tomllib.loads((root / "engines/fsrs/Cargo.lock").read_text())
    count = 0
    files = 0
    for package in lock["package"]:
        if not package.get("source", "").startswith("registry+"):
            continue
        name = f"{package['name']}-{package['version']}"
        archive = cargo_home / "registry/cache/index.crates.io-1949cf8c6b5b557f" / f"{name}.crate"
        source = cargo_home / "registry/src/index.crates.io-1949cf8c6b5b557f" / name
        if hashlib.sha256(archive.read_bytes()).hexdigest() != package["checksum"]:
            raise ValueError(f"Cargo archive checksum mismatch: {name}")
        expected = set()
        with tarfile.open(archive, "r:gz") as tar:
            for member in tar:
                path = pathlib.PurePosixPath(member.name)
                if not path.parts or path.parts[0] != name or ".." in path.parts:
                    raise ValueError(f"Invalid crate member: {name}")
                if member.isdir():
                    continue
                if not member.isfile():
                    raise ValueError(f"Unsupported crate member: {name}")
                relative = pathlib.Path(*path.parts[1:])
                target = source / relative
                if target.is_symlink() or not target.is_file():
                    raise ValueError(f"Missing or linked Cargo source: {name}/{relative}")
                if target.read_bytes() != tar.extractfile(member).read():
                    raise ValueError(f"Modified Cargo source: {name}/{relative}")
                expected.add(relative.as_posix())
                files += 1
        actual = {path.relative_to(source).as_posix() for path in source.rglob("*") if path.is_file()}
        actual.difference_update({".cargo-ok", ".cargo-checksum.json"})
        if actual != expected:
            raise ValueError(f"Unexpected Cargo source file set: {name}")
        count += 1
    print(json.dumps({"event": "fsrs.cargo.sources.verified", "packages": count, "files": files}))


if __name__ == "__main__":
    verify(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
