"""Locate the Joern script in an editable checkout or installed distribution."""

from pathlib import Path
import sys


def scala_script_path() -> Path:
    checkout_script = Path(__file__).resolve().parents[1] / "scala" / "extract_dynamic_variables.sc"
    if checkout_script.is_file():
        return checkout_script
    return Path(sys.prefix) / "share" / "sajaniemi-variable-extractor" / "scala" / "extract_dynamic_variables.sc"
