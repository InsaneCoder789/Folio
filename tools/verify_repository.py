#!/usr/bin/env python3
"""Reject local reference assets and generated outputs in the Git index."""
import subprocess
from pathlib import PurePosixPath


def prohibited(path):
    parts=PurePosixPath(path)
    ml_binary_suffixes={'.safetensors','.pt','.pth','.ckpt','.onnx','.pdiparams','.pdmodel',
                        '.bin','.pkl','.pickle','.npz','.npy','.zip','.tgz','.gz',
                        '.png','.jpg','.jpeg','.webp','.pdf','.pyc'}
    return (parts.parts[0] in {"design", "output"}
            or (len(parts.parts)>1 and parts.parts[0]=="ml" and parts.parts[1] in {"data","artifacts","checkpoints",".venv"})
            or (parts.parts[0]=='ml' and parts.suffix.lower() in ml_binary_suffixes)
            or parts.suffix.lower() == ".html"
            or path in {"Start Scanner Design.command", "tools/serve_design.py"})


def main():
    paths=subprocess.check_output(["git", "ls-files", "-z"],text=True).split("\0")
    blocked=[p for p in paths if p and prohibited(p)]
    expected_docs={"docs/"+name+".md" for name in ("Project","Architecture","Frontend","Fundamentals","Backend","Testing")}
    actual_docs={p for p in paths if p and (p.startswith("docs/") or p.lower().endswith(".md"))}
    if actual_docs-{"README.md"}!=expected_docs:
        print("Commit blocked: documentation must remain within the six canonical files.")
        print("Unexpected: "+", ".join(sorted(actual_docs-expected_docs-{"README.md"})))
        print("Missing: "+", ".join(sorted(expected_docs-actual_docs)))
        return 1
    if blocked:
        print("Commit blocked: local references or generated outputs are staged:")
        print("\n".join(blocked))
        return 1
    print("Repository contents check passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
