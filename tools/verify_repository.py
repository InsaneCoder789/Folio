#!/usr/bin/env python3
"""Reject local reference assets and generated outputs in the Git index."""
import subprocess
from pathlib import PurePosixPath


def prohibited(path):
    parts=PurePosixPath(path)
    return (parts.parts[0] in {"design", "output"}
            or parts.suffix.lower() == ".html"
            or path in {"Start Scanner Design.command", "tools/serve_design.py"})


def main():
    paths=subprocess.check_output(["git", "ls-files", "-z"],text=True).split("\0")
    blocked=[p for p in paths if p and prohibited(p)]
    if blocked:
        print("Commit blocked: local references or generated outputs are staged:")
        print("\n".join(blocked))
        return 1
    print("Repository contents check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
