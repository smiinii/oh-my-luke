"""One shared policy for public and sealed task delivery, outside sealed answers."""
import hashlib
from pathlib import Path

NAME = "EXPERIMENT-RULES.md"
RULES = Path(__file__).resolve().parents[1] / "fixtures/common" / NAME


def rule_hash():
    return hashlib.sha256(RULES.read_bytes()).hexdigest()


def attach_rules(workspace):
    target = Path(workspace) / NAME
    if target.exists() or target.is_symlink():
        if target.is_symlink() or target.read_bytes() != RULES.read_bytes():
            raise ValueError("Conflicting experiment rules")
    else:
        with target.open("xb") as stream:
            stream.write(RULES.read_bytes())


def prompt(workspace):
    attach_rules(workspace)
    return RULES.read_text() + "\n## 이번 작업\n\n" + (Path(workspace) / "TASK.md").read_text()
