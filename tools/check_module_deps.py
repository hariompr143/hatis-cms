#!/usr/bin/env python3
"""
Cross-module reference check for the HATIS backend.

Finds two kinds of mistake that otherwise cost a full GitHub Actions cycle each:

  1. an import of a com.hatis.platform type that no module declares, and
  2. an import of a type that exists, but in a module the importing module does not
     depend on (directly or transitively).

Both are ordinary javac errors. There is no compiler in this sandbox, so this is the
only way to see them before pushing.

Module dependencies are read from each module's pom.xml, restricted to the
com.hatis.platform group, and closed transitively.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

BACKEND = pathlib.Path(__file__).resolve().parent.parent / 'backend'
GROUP = 'com.hatis.platform'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def modules():
    return [p.parent for p in sorted(BACKEND.glob('*/pom.xml'))]


def hatis_dependencies(module_dir):
    root = ET.parse(module_dir / 'pom.xml').getroot()
    deps = []
    for dep in root.findall('.//m:dependencies/m:dependency', NS):
        group = dep.find('m:groupId', NS)
        artifact = dep.find('m:artifactId', NS)
        if group is None or artifact is None:
            continue
        if group.text == GROUP:
            deps.append(artifact.text)
    return deps


def closure(module, graph):
    seen = set()
    stack = list(graph.get(module, []))
    while stack:
        current = stack.pop()
        if current in seen:
            continue
        seen.add(current)
        stack.extend(graph.get(current, []))
    return seen


def main():
    dirs = modules()
    names = {d.name for d in dirs}
    graph = {d.name: [a for a in hatis_dependencies(d) if a in names] for d in dirs}
    reachable = {m: closure(m, graph) for m in graph}

    # Every type the backend declares, keyed by fully qualified name. Production types are
    # importable wherever the module is, so they are recorded once; test types are recorded
    # separately and are only importable from the module's own tests, which is what javac
    # allows - a test fixture is not on another module's test classpath.
    declared = {}
    test_declared = {}
    for d in dirs:
        for path in (d / 'src' / 'main' / 'java').rglob('*.java'):
            package = path.parent.relative_to(d / 'src' / 'main' / 'java').as_posix().replace('/', '.')
            declared[f'{package}.{path.stem}'] = d.name
        test_root = d / 'src' / 'test' / 'java'
        for path in test_root.rglob('*.java'):
            package = path.parent.relative_to(test_root).as_posix().replace('/', '.')
            test_declared[f'{package}.{path.stem}'] = d.name

    unresolved = []
    missing_dep = []
    for d in dirs:
        for path in sorted(d.rglob('*.java')):
            for line_number, line in enumerate(path.read_text().splitlines(), start=1):
                match = re.match(r'\s*import\s+(?:static\s+)?(' + GROUP.replace('.', r'\.')
                                 + r'\.[\w.]+)\s*;', line)
                if not match:
                    continue
                target = match.group(1)
                parts = target.split('.')
                owner = declared.get(target)
                # Nested type: walk back to the outermost declared type.
                candidate = list(parts)
                while owner is None and len(candidate) > 3:
                    candidate = candidate[:-1]
                    owner = declared.get('.'.join(candidate))
                if owner is None:
                    test_owner = test_declared.get(target)
                    if test_owner == d.name:
                        # A test importing its own module's test fixture: ordinary, and not
                        # a production dependency.
                        continue
                    if test_owner is not None:
                        missing_dep.append(f'{d.name}/{path.relative_to(d)}:{line_number}: '
                                           f'imports {target}, a test type from {test_owner}')
                        continue
                if owner is None:
                    unresolved.append(f'{d.name}/{path.relative_to(d)}:{line_number}: '
                                      f'imports {target}, which no module declares')
                elif owner != d.name and owner not in reachable[d.name]:
                    missing_dep.append(f'{d.name}/{path.relative_to(d)}:{line_number}: imports '
                                       f'{target} from {owner}, which is not a dependency')

    for problem in unresolved + missing_dep:
        print(problem)
    print()
    print(f'{len(dirs)} modules, {len(declared)} declared types, '
          f'{len(unresolved)} unresolved import(s), {len(missing_dep)} missing module dep(s)')
    return 1 if (unresolved or missing_dep) else 0


if __name__ == '__main__':
    sys.exit(main())
