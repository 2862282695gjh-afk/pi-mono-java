from __future__ import annotations

import argparse
import sys
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from _common import configure_stdio_utf8  # noqa: E402
from fixture_store import consolidate_rule_fixtures_to_devices, fixtures_dir, list_rule_fixture_paths  # noqa: E402

configure_stdio_utf8()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="Merge rule-keyed fixtures into devices/<deviceId>.json")
    p.add_argument("--clean", action="store_true", help="Remove legacy rule-keyed fixture files after merge")
    p.add_argument(
        "--source-dir",
        default="",
        help="Read legacy rule fixtures from this directory (default: fixtures root)",
    )
    args = p.parse_args(argv)

    source = Path(args.source_dir).expanduser().resolve() if args.source_dir else None
    before = len(list_rule_fixture_paths(fixtures_root=source or fixtures_dir()))
    written = consolidate_rule_fixtures_to_devices(source_dir=source)
    print(f"merged {before} rule fixture(s) -> {len(written)} device fixture(s) under {fixtures_dir() / 'devices'}")

    if args.clean and source is None:
        removed = 0
        for path in list_rule_fixture_paths(fixtures_root=fixtures_dir()):
            path.unlink()
            removed += 1
        print(f"removed {removed} legacy rule fixture file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
