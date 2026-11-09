"""Checks that the documentation still matches the repository.

A reader following the README or SETUP guide fails on the first link that points nowhere, the first
make target that does not exist, or the first environment variable nobody told them about. This
finds those before a reader does. It reads only tracked files, so a path that exists on one machine
but is ignored by git counts as missing.

Usage, from the project root: python3 scripts/audit-docs.py
Exit status is the number of problems found.
"""
import re
import subprocess
import sys
from pathlib import Path

tracked = set(subprocess.run(["git", "ls-files"], capture_output=True, text=True, check=True).stdout.split())
tracked_dirs = {str(Path(f).parent) for f in tracked} | {
    str(p) for f in tracked for p in Path(f).parents if str(p) != "."}
docs = sorted(f for f in tracked if f.endswith(".md") and not f.startswith(("docs/sprint/", "docs/submission/")))
makefile = Path("Makefile").read_text()
targets = set(re.findall(r"^([a-zA-Z0-9_-]+):", makefile, re.M))
problems = []


def exists(path):
    path = path.rstrip("/")
    return path in tracked or path in tracked_dirs


for doc in docs:
    text = Path(doc).read_text()
    base = Path(doc).parent
    for target in re.findall(r"\]\(([^)#\s]+)(?:#[^)]*)?\)", text):
        if re.match(r"^[a-z]+://|^mailto:", target):
            continue
        resolved = str((base / target).resolve().relative_to(Path.cwd().resolve())) if not target.startswith("/") else target[1:]
        if not exists(resolved):
            problems.append(f"{doc}: link to {target} does not resolve to a tracked file")
    for target in re.findall(r"`make ([a-z][a-z0-9-]*)", text):
        if target not in targets:
            problems.append(f"{doc}: make {target} is not a target in the Makefile")
    for script in set(re.findall(r"`?(scripts/[A-Za-z0-9_.-]+\.(?:sh|py))", text)):
        if script not in tracked:
            problems.append(f"{doc}: {script} is not in the repository")

env_example = Path(".env.example").read_text() if Path(".env.example").exists() else ""
config_vars = set()
for f in tracked:
    if f.startswith("src/main/resources/") and f.endswith((".yml", ".yaml", ".properties")):
        config_vars |= set(re.findall(r"\$\{([A-Z][A-Z0-9_]+)(?::[^}]*)?\}", Path(f).read_text()))
for var in sorted(config_vars):
    if var not in env_example:
        problems.append(f".env.example: {var} is read by the application config but not documented")

for line in problems:
    print(line)
print(f"{len(docs)} documents, {len(targets)} make targets, {len(config_vars)} configuration variables checked, "
      f"{len(problems)} problems")
sys.exit(min(len(problems), 125))
