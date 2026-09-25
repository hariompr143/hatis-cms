#!/usr/bin/env python3
"""
Permission-code check.

Permission codes are data: the catalogue lives in the migrations, and
`AuthorizationService` grants access by matching the requested code against what a role was
given. A code that is not in that catalogue therefore denies *everybody* — including an owner
whose role holds `'*'`, because the wildcard is resolved by joining the catalogue too. That is
not a weaker check, it is a permanently closed door, and from the outside it does not look like
a bug: the endpoint answers 403 to every caller, which reads as a misconfigured tenant rather
than as a typo in the source.

This checker exists because that mistake shipped twice — once in the CMS module, which asked for
`content:read` where the catalogue defines `cms:content:read`, and once in the deployment module,
which asked for `application:*` and `release:*` before those codes existed.

Two rules:

  1. every `<NAME>_PERMISSION` (or `*_PERMISSION_*`) constant declared in main code must hold a
     code the migrations seed. Constants are how the platform is meant to name a capability,
     and a constant that is not in the catalogue is the door being closed once, for all callers;
  2. every string literal shaped like a permission code in main code must be one the migrations
     seed. The shape is narrow — lowercase, colon-separated, at most two colons, no slashes or
     dots — so event types, MIME types, JDBC settings and quota wire values do not match it, and
     a literal that does is a permission check far more often than it is anything else.

`frontend/` is out of scope: a console that hides a button the API would refuse is a
convenience, not a security control, and the API is what this checks.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKEND = ROOT / 'backend'
MIGRATIONS = BACKEND / 'hatis-api' / 'src' / 'main' / 'resources' / 'db' / 'migration'

# Lowercase, colon-separated, one or two colons: `project:read`, `cms:content:publish`.
CODE_SHAPE = re.compile(r'"([a-z][a-z0-9_]*(?::[a-z0-9_]+){1,2})"')
CONSTANT = re.compile(r'String\s+([A-Z][A-Z0-9_]*PERMISSION[A-Z0-9_]*)\s*=\s*"([^"]+)"')

# Literals that look like a permission code but are not one. Each entry needs a reason; if this
# list grows, the shape rule above is wrong and should be tightened instead. It is empty today:
# every literal in main code with the code's shape is a permission.
NOT_PERMISSIONS = set()


def seeded_codes():
    """Every code the migrations insert into auth_permissions."""
    codes = set()
    for migration in sorted(MIGRATIONS.glob('*.sql')):
        text = migration.read_text(encoding='utf-8')
        if 'auth_permissions' not in text:
            continue
        for match in re.finditer(r"gen_random_uuid\(\)\s*,\s*'([^']+)'", text):
            codes.add(match.group(1))
    return codes


def main():
    catalogue = seeded_codes()
    problems = []
    constants_checked = 0
    literals_checked = 0

    for source in sorted(BACKEND.rglob('src/main/java/**/*.java')):
        text = source.read_text(encoding='utf-8')
        relative = source.relative_to(BACKEND)

        for match in CONSTANT.finditer(text):
            constants_checked += 1
            if match.group(2) not in catalogue:
                problems.append(f'{relative}: constant {match.group(1)} = "{match.group(2)}" '
                                f'is not in the permission catalogue')

        for line_number, line in enumerate(text.splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith('*') or stripped.startswith('//'):
                continue
            for match in CODE_SHAPE.finditer(line):
                code = match.group(1)
                if code in NOT_PERMISSIONS:
                    continue
                literals_checked += 1
                if code not in catalogue:
                    problems.append(f'{relative}:{line_number}: "{code}" '
                                    f'is not in the permission catalogue')

    print(f'{constants_checked} permission constant(s), {literals_checked} code-shaped literal(s), '
          f'{len(catalogue)} catalogue code(s)')
    if problems:
        print()
        for problem in problems:
            print(f'  {problem}')
        return 1
    print('every permission code the code names is one the migrations seed')
    return 0


if __name__ == '__main__':
    sys.exit(main())
