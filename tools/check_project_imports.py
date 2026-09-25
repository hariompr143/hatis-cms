#!/usr/bin/env python3
"""
Project-type import check.

`tools/check_imports.py` covers JDK types that need an explicit import. It cannot see the
other half: a type **this repository declares** being used without its import, which is an
ordinary `cannot find symbol` compile failure. Nothing local caught the last one —
`EmailChannelSender` implements `ChannelSender` without importing it, so `hatis-notification`
failed to compile and the only signal was a ten-minute CI cycle.

The rule is mechanical: a file may refer to a project type by its simple name only if

  * the type is in the file's own package (no import needed),
  * the type is declared in the file itself,
  * the file imports it, or
  * the file imports a package that could supply it.

Only top-level types are considered, and only simple names that the project declares exactly
once — a name declared in two packages is ambiguous without reproducing the compiler's
resolution order, and guessing would produce noise rather than findings. Comments, string
literals, text blocks and char literals are stripped by a small tokenizer first, because a
`{@link}` in a javadoc needs no import and a `"User-Agent"` header is not a type. The
tokenizer is a single pass on purpose: layered regexes get quote parity wrong on real code,
and a file with one `'"'` character literal then reports the contents of every later string.

A file with any wildcard import is skipped for this check: which simple names that wildcard
supplies is not knowable without the dependency's classpath.

It is a heuristic, not a resolver. Anything it reports is worth a human look.
"""
import pathlib
import re
import sys
from collections import defaultdict

BACKEND = pathlib.Path(__file__).resolve().parent.parent / 'backend'

PACKAGE = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.MULTILINE)
IMPORT = re.compile(r'^\s*import\s+(?:static\s+)?([\w.*]+)\s*;', re.MULTILINE)
TYPE_DECL = re.compile(r'\b(?:class|interface|record|enum|@interface)\s+([A-Z]\w*)')
# A bare type reference: not preceded by a dot (a fully qualified use needs no import) and not
# part of a longer identifier.
IDENTIFIER = re.compile(r'(?<![\w.])([A-Z]\w*)\b')


def strip_non_code(text):
    """Remove comments, string literals, text blocks and char literals."""
    out = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        if text.startswith('//', index):
            newline = text.find('\n', index)
            if newline < 0:
                break
            index = newline
            continue
        if text.startswith('/*', index):
            end = text.find('*/', index + 2)
            index = length if end < 0 else end + 2
            continue
        if text.startswith('"""', index):
            end = text.find('"""', index + 3)
            index = length if end < 0 else end + 3
            continue
        if char in ('"', "'"):
            cursor = index + 1
            while cursor < length:
                current = text[cursor]
                if current == '\\':
                    cursor += 2
                    continue
                if current == char:
                    cursor += 1
                    break
                if current == '\n':
                    # An unterminated literal: stop here rather than swallow the file.
                    break
                cursor += 1
            index = cursor
            continue
        out.append(char)
        index += 1
    return ''.join(out)


def java_files():
    return sorted(BACKEND.rglob('src/*/java/**/*.java'))


def main():
    packages_by_name = defaultdict(set)
    for path in java_files():
        match = PACKAGE.search(path.read_text(encoding='utf-8'))
        if match:
            packages_by_name[path.stem].add(match.group(1))

    problems = []
    checked = 0

    for path in java_files():
        raw = path.read_text(encoding='utf-8')
        package_match = PACKAGE.search(raw)
        if not package_match:
            continue
        package = package_match.group(1)
        imports = IMPORT.findall(raw)
        if any(name.endswith('.*') for name in imports):
            continue
        imported_types = {name.rsplit('.', 1)[-1] for name in imports}

        code = IMPORT.sub('', strip_non_code(raw))
        declared_here = set(TYPE_DECL.findall(code))
        relative = path.relative_to(BACKEND)

        for simple_name in sorted(set(IDENTIFIER.findall(code))):
            if simple_name == path.stem or simple_name in declared_here:
                continue
            if simple_name in imported_types:
                continue
            packages = packages_by_name.get(simple_name)
            if not packages:
                # Not a type this repository declares: a JDK, Spring or library type, which
                # check_imports.py covers.
                continue
            if len(packages) > 1 or package in packages:
                continue
            checked += 1
            problems.append(f'{relative}: uses {simple_name} '
                            f'(declared in {next(iter(packages))}) without importing it')

    print(f'{checked} project-type reference(s) that appear to be missing an import')
    if problems:
        print()
        for problem in problems:
            print(f'  {problem}')
        return 1
    print('every project type used by simple name is imported, same-package or declared locally')
    return 0


if __name__ == '__main__':
    sys.exit(main())
