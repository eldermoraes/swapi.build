#!/usr/bin/env bash
# Guard (run by .github/workflows/ci.yml): every action a workflow uses from
# outside this repository is pinned by a full commit SHA, with the release it
# corresponds to in a comment on the same line:
#
#   uses: owner/repo@<40 hex> # vX.Y.Z
#
# A tag or a branch can be moved by whoever controls the action's repository,
# and the next job here then runs their code: in the deploy job that is code
# next to the deploy key and the Tailscale secret, in the Claude workflows
# code that receives the OAuth token (issue #65). A commit cannot be moved.
#
# Offline, so it checks the shape of a pin, never that the SHA is the commit
# of the version in the comment: that is checked by hand when a pin moves
# (docs/DEPLOY.md, "Moving the pin of an action").
#
# Read line by line, not as YAML: the version lives in a comment, which a YAML
# reader drops. Anything the rule does not know fails (a quoted value or key,
# a `docker://` reference, a `uses:` inside a flow mapping) instead of passing.
# It guards against a reference added by mistake or by habit, not against one
# hidden on purpose (a key written with YAML escapes or as a complex key is
# not seen): whoever can do that can also edit this file. Local actions
# (`./...`) pass and their own files are not read; there is none today.
#
# It first proves the rule on throwaway workflows, so that it cannot pass by
# being blind, then applies it to the real ones.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

python3 -I - "$ROOT/.github/workflows" <<'PYTHON'
import pathlib
import re
import sys
import tempfile

RUNBOOK = 'docs/DEPLOY.md, "Moving the pin of an action"'

MENTIONS_USES = re.compile(r'''(?:^|[\s{,"'-])uses["']?\s*:''')
USES_LINE = re.compile(r'^\s*(?:-\s+)?uses:\s+(?P<value>\S.*?)\s*$')
LOCAL = re.compile(r'^\./\S*(?:\s+#.*)?$')
PINNED = re.compile(
    r'^(?P<repo>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)(?:/[A-Za-z0-9_./-]+)?'
    r'@(?P<sha>[0-9a-f]{40})\s+#\s+(?P<version>v\d+\.\d+\.\d+)(?:\s.*)?$'
)


def problems_in(workflows_dir):
    """The reasons the workflows of a directory break the rule; empty when they keep it."""
    directory = pathlib.Path(workflows_dir)
    files = sorted(list(directory.glob('*.yml')) + list(directory.glob('*.yaml')))
    if not files:
        return [f'{directory}: no workflow file found, so nothing was checked']

    problems = []
    pins = {}
    for file in files:
        for number, line in enumerate(file.read_text(encoding='utf-8').splitlines(), start=1):
            if line.lstrip().startswith('#') or not MENTIONS_USES.search(line):
                continue
            where = f'{file.name}:{number}'
            uses = USES_LINE.match(line)
            if not uses:
                problems.append(f'{where}: cannot read this `uses:`, write it alone on its line: {line.strip()}')
                continue
            value = uses.group('value')
            if LOCAL.match(value):
                continue
            pinned = PINNED.match(value)
            if not pinned:
                problems.append(
                    f'{where}: not pinned as `owner/repo@<40 hex commit> # vX.Y.Z`: {value}')
                continue
            pin = (pinned.group('sha'), pinned.group('version'))
            pins.setdefault(pinned.group('repo').lower(), {}).setdefault(pin, []).append(where)

    for repo, by_pin in sorted(pins.items()):
        if len(by_pin) > 1:
            found = '; '.join(
                f'{sha} # {version} ({", ".join(places)})' for (sha, version), places in sorted(by_pin.items()))
            problems.append(f'{repo} has more than one pin, keep one: {found}')
    return problems


SHA_A = 'a' * 40
SHA_B = 'b' * 40

# name, workflow files, number of problems expected, text the first problem must contain
CASES = [
    ('a commit with its version passes',
     {'a.yml': f'      - uses: actions/checkout@{SHA_A} # v4.4.0\n'}, 0, None),
    ('a named step, a sub-path and a longer comment pass',
     {'a.yml': f'      - name: x\n        uses: owner/repo/path/to@{SHA_A} # v1.2.3 (2026-01-01)\n'}, 0, None),
    ('a local action and a local reusable workflow pass',
     {'a.yml': '      - uses: ./.github/actions/x\n    uses: ./.github/workflows/y.yml # ours\n'}, 0, None),
    ('the same pin in two files passes',
     {'a.yml': f'      - uses: o/r@{SHA_A} # v1.0.0\n', 'b.yaml': f'      - uses: o/r@{SHA_A} # v1.0.0\n'}, 0, None),
    ('a commented-out line is ignored',
     {'a.yml': '      # - uses: actions/checkout@v4\n'}, 0, None),
    ('a major tag fails',
     {'a.yml': '      - uses: actions/checkout@v4\n'}, 1, 'a.yml:1: not pinned'),
    ('an exact tag fails',
     {'a.yml': '      - uses: actions/checkout@v4.4.0 # v4.4.0\n'}, 1, 'not pinned'),
    ('a branch fails',
     {'a.yml': '\n        uses: owner/repo@main\n'}, 1, 'a.yml:2: not pinned'),
    ('a short SHA fails',
     {'a.yml': '      - uses: owner/repo@aaaaaaa # v1.0.0\n'}, 1, 'not pinned'),
    ('an uppercase SHA fails',
     {'a.yml': f'      - uses: owner/repo@{"A" * 40} # v1.0.0\n'}, 1, 'not pinned'),
    ('a commit without its version fails',
     {'a.yml': f'      - uses: owner/repo@{SHA_A}\n'}, 1, 'not pinned'),
    ('a commit with only the major in the comment fails',
     {'a.yml': f'      - uses: owner/repo@{SHA_A} # v4\n'}, 1, 'not pinned'),
    ('a quoted value fails',
     {'a.yml': f'      - uses: "owner/repo@{SHA_A}" # v1.0.0\n'}, 1, 'not pinned'),
    ('a container reference fails',
     {'a.yml': '      - uses: docker://alpine:3.20\n'}, 1, 'not pinned'),
    ('a reusable workflow of another repository by tag fails',
     {'a.yml': '    uses: owner/repo/.github/workflows/w.yml@v1\n'}, 1, 'not pinned'),
    ('a `uses:` inside a flow mapping fails',
     {'a.yml': '      - { uses: actions/checkout@v4 }\n'}, 1, 'cannot read'),
    ('a quoted `uses` key fails',
     {'a.yml': '      - "uses": actions/checkout@v4\n      - \'uses\': actions/checkout@v4\n'}, 2, 'cannot read'),
    ('a quoted `uses` key inside a flow mapping fails',
     {'a.yml': '      - {"uses": actions/checkout@v4}\n'}, 1, 'cannot read'),
    ('a version comment without a space after the # fails',
     {'a.yml': f'      - uses: owner/repo@{SHA_A} #v1.0.0\n'}, 1, 'not pinned'),
    ('a .yaml file is read too',
     {'a.yaml': '      - uses: actions/checkout@v4\n'}, 1, 'a.yaml:1'),
    ('every unpinned line is reported',
     {'a.yml': '      - uses: o/r@v1\n      - uses: o/s@v2\n', 'b.yml': '      - uses: o/t@v3\n'}, 3, 'a.yml:1'),
    ('two pins for one action fail',
     {'a.yml': f'      - uses: o/r@{SHA_A} # v1.0.0\n', 'b.yml': f'      - uses: O/R/sub@{SHA_B} # v1.0.1\n'},
     1, 'more than one pin'),
    ('one commit under two versions fails',
     {'a.yml': f'      - uses: o/r@{SHA_A} # v1.0.0\n      - uses: o/r@{SHA_A} # v1.0.1\n'},
     1, 'more than one pin'),
    ('a directory without workflows fails',
     {'notes.txt': 'uses: nothing\n'}, 1, 'no workflow file'),
]

failed = 0


def report(passed, name, detail=''):
    global failed
    print(f'{"PASS" if passed else "FAIL"}  {name}')
    if not passed:
        failed += 1
        if detail:
            print(detail)


for name, files, expected_count, expected_text in CASES:
    with tempfile.TemporaryDirectory() as directory:
        for file_name, content in files.items():
            pathlib.Path(directory, file_name).write_text(content, encoding='utf-8')
        found = problems_in(directory)
    as_expected = len(found) == expected_count and (expected_text is None or expected_text in found[0])
    report(as_expected, f'rule: {name}',
           f'      expected {expected_count} problem(s) containing {expected_text!r}, got {found}')

real = problems_in(sys.argv[1])
report(not real, 'every action of .github/workflows is pinned by commit, with its version in a comment',
       ''.join(f'      {problem}\n' for problem in real) + f'      How to pin: {RUNBOOK}')

sys.exit(1 if failed else 0)
PYTHON
