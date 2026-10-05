"""Discover source repositories and infer their language from file extensions."""

from __future__ import annotations

import warnings
from dataclasses import dataclass
from pathlib import Path


C_EXTENSIONS = {".c"}
CPP_EXTENSIONS = {".cc", ".cpp", ".cxx", ".c++", ".C", ".H", ".hh", ".hpp", ".hxx", ".h++", ".ipp"}
AMBIGUOUS_HEADERS = {".h"}
OTHER_EXTENSIONS = {
    ".js": "JavaScript", ".jsx": "JavaScript", ".mjs": "JavaScript", ".cjs": "JavaScript",
    ".py": "Python", ".rb": "Ruby",
}
SUPPORTED_EXTENSIONS = C_EXTENSIONS | CPP_EXTENSIONS | AMBIGUOUS_HEADERS | set(OTHER_EXTENSIONS)
UNSUPPORTED_SOURCE_EXTENSIONS = {".cs", ".java", ".ts", ".tsx", ".go", ".rs", ".kt", ".kts", ".swift", ".php"}


@dataclass(frozen=True)
class SourceJob:
    source: Path
    language: str
    output_parts: tuple[str, ...] = ()  # Repository path relative to the input root.


def _visible_dirs(path: Path) -> list[Path]:
    return sorted(child for child in path.iterdir() if child.is_dir() and not child.name.startswith("."))


def _extension(path: Path) -> str:
    # Uppercase .C is a C++ suffix; other suffixes are matched case-insensitively.
    return path.suffix if path.suffix in {".C", ".H"} else path.suffix.lower()


def _source_extensions(path: Path, *, recursive: bool = True) -> set[str]:
    files = path.rglob("*") if recursive else path.iterdir()
    return {_extension(child) for child in files
            if child.is_file() and not any(part.startswith(".") for part in child.relative_to(path).parts)
            and _extension(child) in SUPPORTED_EXTENSIONS}


def detect_language(path: Path, *, hint: str | None = None) -> str:
    extensions = _source_extensions(path)
    unsupported = {child.suffix.lower() for child in path.rglob("*") if child.is_file()
                   and not any(part.startswith(".") for part in child.relative_to(path).parts)
                   and child.suffix.lower() in UNSUPPORTED_SOURCE_EXTENSIONS}
    if unsupported:
        raise ValueError(f"Unsupported source extensions in {path}: {', '.join(sorted(unsupported))}")
    if not extensions:
        raise ValueError(f"No supported source files in {path}")
    languages = {OTHER_EXTENSIONS[ext] for ext in extensions if ext in OTHER_EXTENSIONS}
    if extensions & CPP_EXTENSIONS:
        languages.add("C++")
    elif extensions & C_EXTENSIONS:
        languages.add("C")
    elif extensions & AMBIGUOUS_HEADERS:
        if languages:
            raise ValueError(f"Ambiguous .h headers alongside another language in {path}")
        if hint in {"C", "C++"}:
            languages.add(hint)
        else:
            raise ValueError(f"Only ambiguous .h headers in {path}; specify --language C or C++")
    if len(languages) != 1:
        raise ValueError(f"Several languages in {path}: {', '.join(sorted(languages))}")
    language = next(iter(languages))
    if hint is not None and language != hint:
        raise ValueError(f"--language {hint} disagrees with detected {language} in {path}")
    return language


def discover_sources(root: Path, layout: str = "auto", language_hint: str | None = None) -> list[SourceJob]:
    """Return jobs for a tree, repository collection, or named split collections."""
    if not root.is_dir():
        raise ValueError(f"Source directory does not exist: {root}")
    if layout == "single":
        return [SourceJob(root, detect_language(root, hint=language_hint))]
    if layout == "repos":
        return _collection_jobs(root, ())
    if layout == "splits":
        return _split_jobs(root)
    if layout != "auto":
        raise ValueError(f"Unknown source layout: {layout}")

    children = _visible_dirs(root)
    if _source_extensions(root, recursive=False) or not children:
        return [SourceJob(root, detect_language(root, hint=language_hint))]

    populated = [child for child in children if _source_extensions(child)]
    # A split contains repositories at the next level. At least two children
    # distinguish it from a conventional src/ directory in a single project.
    split_like = [child for child in populated
                  if not _source_extensions(child, recursive=False)
                  and sum(bool(_source_extensions(nested)) for nested in _visible_dirs(child)) >= 2]
    if split_like:
        return _split_jobs(root)

    if len(populated) >= 2:
        # Same-language src/ and tests/ are one project. Multiple repositories
        # of that language can be selected explicitly with --layout repos.
        try:
            language = detect_language(root, hint=language_hint)
        except ValueError:
            return _collection_jobs(root, ())
        return [SourceJob(root, language)]

    return [SourceJob(root, detect_language(root, hint=language_hint))]


def _split_jobs(root: Path) -> list[SourceJob]:
    jobs: list[SourceJob] = []
    for split in _visible_dirs(root):
        try:
            jobs.extend(_collection_jobs(split, (split.name,)))
        except ValueError as error:
            warnings.warn(f"Skipping invalid split {split}: {error}", stacklevel=2)
    if not jobs:
        raise ValueError(f"No valid source directories in {root}")
    return jobs


def _collection_jobs(root: Path, prefix: tuple[str, ...]) -> list[SourceJob]:
    jobs = []
    for child in _visible_dirs(root):
        try:
            language = detect_language(child)
        except ValueError as error:
            warnings.warn(f"Skipping invalid source directory {child}: {error}", stacklevel=2)
            continue
        jobs.append(SourceJob(child, language, (*prefix, child.name)))
    if not jobs:
        raise ValueError(f"No valid source directories in {root}")
    return jobs
