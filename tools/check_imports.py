#!/usr/bin/env python3
"""
Missing-import check for Java sources.

Catches the class of error that costs a full CI cycle to discover: a file that uses
`UUID`, `List`, `Instant` and so on without importing them. The Java compiler finds
these instantly, but there is no compiler in this sandbox, so the feedback loop is
otherwise thirty minutes of GitHub Actions per mistake.

It is a heuristic, not a type checker. It only reports identifiers from a curated
list of JDK types that always need an explicit import, and it ignores occurrences
that are qualified (`java.util.UUID`), preceded by a dot, or inside a comment,
string literal or import statement. Anything it reports is worth a human look; it
does not claim to be complete.
"""
import pathlib
import re
import sys

BACKEND = pathlib.Path(__file__).resolve().parent.parent / 'backend'

# Types that require an explicit import, by simple name.
NEEDS_IMPORT = {
    'UUID': 'java.util.UUID',
    'List': 'java.util.List',
    'Map': 'java.util.Map',
    'Set': 'java.util.Set',
    'ArrayList': 'java.util.ArrayList',
    'LinkedHashMap': 'java.util.LinkedHashMap',
    'HashMap': 'java.util.HashMap',
    'HashSet': 'java.util.HashSet',
    'Optional': 'java.util.Optional',
    'Objects': 'java.util.Objects',
    'Locale': 'java.util.Locale',
    'Base64': 'java.util.Base64',
    'Iterator': 'java.util.Iterator',
    'Instant': 'java.time.Instant',
    'Duration': 'java.time.Duration',
    'OffsetDateTime': 'java.time.OffsetDateTime',
    'InputStream': 'java.io.InputStream',
    'IOException': 'java.io.IOException',
    'StandardCharsets': 'java.nio.charset.StandardCharsets',
    'Collectors': 'java.util.stream.Collectors',
    'BigDecimal': 'java.math.BigDecimal',
}

# Never reported: used only as a type argument or in a cast that is already covered
# by another import, or shadowed by a nested type in this codebase.
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')
LINE_COMMENT = re.compile(r'//.*')
BLOCK_COMMENT = re.compile(r'/\*.*?\*/', re.DOTALL)
CHAR_LITERAL = re.compile(r"'(?:\\.|[^'\\])'")


def strip_noise(text):
    """Blanks out comments, string and char literals so their words are not scanned."""
    def blank(match):
        return re.sub(r'\S', ' ', match.group(0))

    text = BLOCK_COMMENT.sub(blank, text)
    text = STRING_LITERAL.sub(blank, text)
    text = CHAR_LITERAL.sub(blank, text)
    text = LINE_COMMENT.sub(blank, text)
    return text


def declared_types(text):
    """Types this file declares itself, which therefore need no import."""
    return set(re.findall(r'\b(?:class|interface|enum|record)\s+(\w+)', text))


def imports(text):
    explicit = set(re.findall(r'^import\s+(?:static\s+)?([\w.]+);', text, re.MULTILINE))
    simple = {name.rsplit('.', 1)[-1] for name in explicit}
    star = {name.rsplit('.', 1)[0] for name in explicit if name.endswith('.*')}
    return simple, star


def check(path):
    raw = path.read_text()
    text = strip_noise(raw)

    imported_simple, imported_packages = imports(raw)
    local = declared_types(text)

    problems = []
    for simple_name, fqn in NEEDS_IMPORT.items():
        if simple_name in imported_simple or simple_name in local:
            continue
        if fqn.rsplit('.', 1)[0] in imported_packages:
            continue

        # An occurrence that is qualified (java.util.UUID) or member-accessed
        # (something.UUID) does not need an import.
        pattern = re.compile(r'(?<![\w.$])' + simple_name + r'(?![\w])')
        hits = []
        for match in pattern.finditer(text):
            line_number = text[:match.start()].count('\n') + 1
            hits.append(line_number)
        if hits:
            problems.append((simple_name, fqn, hits[0], len(hits)))
    return problems


def main():
    files = sorted(BACKEND.rglob('*.java'))
    total = 0
    for path in files:
        for simple_name, fqn, line, count in check(path):
            total += 1
            print(f'{path.relative_to(BACKEND)}:{line}: uses {simple_name} ({count}x) '
                  f'without importing {fqn}')
    print()
    print(f'scanned {len(files)} java files, {total} suspect import(s)')
    return 1 if total else 0


if __name__ == '__main__':
    sys.exit(main())
