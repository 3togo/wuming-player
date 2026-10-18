"""CatVod plugin lab — offline research harness for the OpenDrive cloud pipeline.

See `tools/catvod-plugin-lab/README.md` for the pipeline, storage layout and the
evidence rules that govern how findings may be used.
"""

__version__ = "0.1.0"

# Repository root, derived from the package location:
# <repo>/tools/catvod-plugin-lab/catvod_lab/__init__.py
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]

DEFAULT_DEPOT = REPO_ROOT / "app" / "src" / "main" / "assets" / "depot.json"
DEFAULT_WORK = REPO_ROOT / ".cache" / "catvod-plugin-lab"
DEFAULT_MIRROR_FILE = REPO_ROOT / "github_proxies.json"

__all__ = ["__version__", "REPO_ROOT", "DEFAULT_DEPOT", "DEFAULT_WORK", "DEFAULT_MIRROR_FILE"]
