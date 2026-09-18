#!/usr/bin/env python3
"""
Cross-package visibility check for the HATIS backend.

Reports uses of the form `SomeType.member` where `member` is declared in `SomeType`
without `public`, and the use happens from a different package. javac rejects these;
there is no compiler in this sandbox, so each one otherwise costs a build cycle to
find. It caught Asset.normalizeContentType being called from the assets application
package while still package-private in the domain package.

Scope and limits, stated plainly:

  * Only `Type.member` accesses are considered. Resolving an instance expression to
    its declared type needs the inference a compiler has.
  * Members are attributed to the file, not to the enclosing class, so a
    package-private member of one class in a file can shadow a public one of the
    same name in another. A name declared public anywhere in the file is treated as
    public, which keeps local variable declarations from being reported.

Members are found by scanning lines, not by regex over the whole file: the
whole-file pattern backtracks badly enough that it does not finish.
"""
import pathlib
import re
import sys
from collections import defaultdict

BACKEND = pathlib.Path(__file__).resolve().parent.parent / 'backend'

TYPE_DECL = re.compile(
    r'^\s*(?:public|private|protected|static|final|abstract|\s)*'
    r'\b(?:class|interface|enum|record|@interface)\s+(?P<name>\w+)', re.M)
MODIFIERS = re.compile(r'^(?:(?:public|private|protected|static|final|abstract|synchronized'
                       r'|default|native)\s+)+')
DECLARATION = re.compile(r'^(?:<[^>]*>\s*)?[A-Za-z_][\w.<>\[\],?]*(?:\s*\[\s*\])*\s+'
                         r'(?P<name>\w+)\s*[(=;]')
ACCESS = re.compile(r'(?<![\w.$])(?P<type>[A-Z]\w*)\.(?P<member>\w+)(?![\w])')

KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'return', 'new', 'else', 'do',
            'try', 'throw', 'case', 'instanceof', 'package', 'import', 'super', 'this'}


def strip_comments(text):
    text = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'\S', ' ', m.group(0)), text, flags=re.DOTALL)
    return re.sub(r'//.*', '', text)


def analyse(path):
    text = strip_comments(path.read_text())
    match = re.search(r'^package\s+([\w.]+);', text, re.M)
    package = match.group(1) if match else ''
    types = {m.group('name') for m in TYPE_DECL.finditer(text)}

    public, hidden = set(), set()
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('@'):
            continue
        mods = MODIFIERS.match(stripped)
        rest = stripped[mods.end():] if mods else stripped
        decl = DECLARATION.match(rest)
        if not decl:
            continue
        name = decl.group('name')
        if name in KEYWORDS or name in types:
            continue
        (public if mods and 'public' in mods.group(0) else hidden).add(name)
    return package, types, hidden - public


def main():
    files = sorted(BACKEND.rglob('*.java'))
    results = {path: analyse(path) for path in files}

    index = defaultdict(list)
    for path, (package, types, hidden) in results.items():
        for type_name in types:
            index[type_name].append((package, path, hidden))

    problems = 0
    for path in files:
        package = results[path][0]
        text = strip_comments(path.read_text())
        for access in ACCESS.finditer(text):
            for owner_package, owner_path, hidden in index.get(access.group('type'), ()):
                if owner_package == package or access.group('member') not in hidden:
                    continue
                line = text[:access.start()].count('\n') + 1
                print(f'{path.relative_to(BACKEND)}:{line}: uses '
                      f'{access.group("type")}.{access.group("member")} from {package}, but it '
                      f'is not public in {owner_package} ({owner_path.name})')
                problems += 1
    print()
    print(f'scanned {len(files)} java files, {problems} cross-package visibility problem(s)')
    return 1 if problems else 0


if __name__ == '__main__':
    sys.exit(main())
