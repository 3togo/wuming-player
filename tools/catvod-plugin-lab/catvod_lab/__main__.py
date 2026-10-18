"""Allow `python3 -m catvod_lab` from the subproject directory (or PYTHONPATH)."""

from .cli import main

if __name__ == "__main__":
    raise SystemExit(main())
