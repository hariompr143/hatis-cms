#!/usr/bin/env python3
"""
Entity <-> schema consistency check.

Hibernate validates mappings at startup, so an entity that references a table or
column the migrations do not create is a guaranteed startup failure. This script
catches that class of bug without a JVM, by parsing @Entity/@Table/@Column
annotations out of the sources and comparing them against the CREATE TABLE
statements in the Flyway migrations.

It is not a type checker. It answers one narrow question, and answers it exactly:
does every persisted field have somewhere to live?
"""
import pathlib
import re
import sys
from collections import defaultdict

# Repository layout: <repo>/backend/<module>. The script lives in <repo>/tools.
BACKEND = pathlib.Path(__file__).resolve().parent.parent / 'backend'
MIGRATIONS = BACKEND / 'hatis-api/src/main/resources/db/migration'

# Fields contributed by the mapped superclasses, and the columns they own.
INHERITED = {
    'BaseEntity': ['id', 'created_at', 'updated_at', 'version'],
    'TenantScopedEntity': ['id', 'created_at', 'updated_at', 'version', 'organization_id'],
}


def parse_migrations():
    """table name -> set of column names.

    Reads both `create table` and the `alter table ... add column` form. The
    latter is how columns arrive once a table has shipped - V1_014 added the
    bookkeeping columns to int_webhook_deliveries that way, and an entity
    mapping one of them would otherwise be reported as a mapping error (or, with
    the inherited-field list, quietly accepted without ever being checked).
    """
    tables = defaultdict(set)
    for path in sorted(MIGRATIONS.glob('*.sql')):
        sql = path.read_text()
        for match in re.finditer(
                r'alter table ([a-z_]+)\s*(.*?);', sql, re.DOTALL):
            table, body = match.group(1), match.group(2)
            for column in re.finditer(
                    r'add column (?:if not exists )?([a-z_][a-z0-9_]*)', body):
                tables[table].add(column.group(1))
        for match in re.finditer(r'create table (?:if not exists )?([a-z_]+)\s*\((.*?)\n\);',
                                 sql, re.DOTALL):
            name, body = match.group(1), match.group(2)
            for line in body.split('\n'):
                stripped = line.strip()
                if not stripped or stripped.startswith('--'):
                    continue
                keyword = stripped.split()[0].lower()
                if keyword in ('constraint', 'primary', 'unique', 'foreign', 'check', 'exclude'):
                    continue
                column = re.match(r'([a-z_][a-z0-9_]*)\s', stripped)
                if column:
                    tables[name].add(column.group(1))
    return tables


def find_duplicate_columns():
    """(file, table, [columns]) for every table that declares a column name twice.

    parse_migrations collects columns into a set, so a duplicated column disappears
    from its output and the mapping check sees nothing wrong. PostgreSQL, however,
    refuses to create the table at all, which fails every Flyway run - dep_releases
    once declared both the release's semantic version and BaseEntity's optimistic
    lock as `version`. Worth checking on its own terms.
    """
    duplicates = []
    for path in sorted(MIGRATIONS.glob('*.sql')):
        sql = path.read_text()
        for match in re.finditer(r'create table (?:if not exists )?([a-z_]+)\s*\((.*?)\n\);',
                                 sql, re.DOTALL):
            name, body = match.group(1), match.group(2)
            seen = defaultdict(int)
            for line in body.split('\n'):
                stripped = line.strip()
                if not stripped or stripped.startswith('--'):
                    continue
                if stripped.split()[0].lower() in (
                        'constraint', 'primary', 'unique', 'foreign', 'check', 'exclude'):
                    continue
                column = re.match(r'([a-z_][a-z0-9_]*)\s', stripped)
                if column:
                    seen[column.group(1)] += 1
            repeated = sorted(c for c, count in seen.items() if count > 1)
            if repeated:
                duplicates.append((path.name, name, repeated))
    return duplicates


def parse_entities():
    """(file, table, superclass, [(field, column, line)]) for every @Entity."""
    entities = []
    for path in sorted(BACKEND.rglob('*.java')):
        text = path.read_text()
        if '@Entity' not in text:
            continue
        for match in re.finditer(
                r'@Entity\b(?P<annos>.*?)public\s+(?:static\s+)?class\s+(?P<cls>\w+)'
                r'(?:\s+extends\s+(?P<sup>\w+))?',
                text, re.DOTALL):
            annotations = match.group('annos')
            table_match = re.search(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"', annotations)
            if not table_match:
                print(f'  !! {path.name}: @Entity {match.group("cls")} has no @Table(name=...)')
                continue
            table = table_match.group(1)

            # The entity body runs from the class declaration to its own closing
            # brace. Depth starts at 0 so the first '{' opens the class body and the
            # brace that returns it to 0 is the end of *this* class - starting at 1
            # would swallow the nested entity classes that follow in the same file.
            start = match.end()
            depth, index = 0, start
            while index < len(text):
                if text[index] == '{':
                    depth += 1
                elif text[index] == '}':
                    depth -= 1
                    if depth == 0:
                        index += 1
                        break
                index += 1
            body = text[start:index]

            fields = []
            for column_match in re.finditer(
                    r'@Column\s*\((?P<args>[^)]*)\)(?P<between>.*?)'
                    r'(?:private|protected)\s+[\w.<>\[\]]+\s+(?P<field>\w+)\s*(?:=[^;]*)?;',
                    body, re.DOTALL):
                args = column_match.group('args')
                name_match = re.search(r'name\s*=\s*"([^"]+)"', args)
                if not name_match:
                    # No explicit name means Hibernate applies its naming strategy;
                    # every field in this codebase names its column, so this is a
                    # real finding rather than a false positive to skip.
                    print(f'  !! {path.name}: field {column_match.group("field")} has no @Column name')
                    continue
                line = text[:start + column_match.start()].count('\n') + 1
                fields.append((column_match.group('field'), name_match.group(1), line))
            entities.append((path, table, match.group('sup') or '', fields))
    return entities


def main():
    tables = parse_migrations()
    print(f'migrations define {len(tables)} tables')

    entities = parse_entities()
    print(f'sources define {len(entities)} @Entity classes\n')

    problems = 0
    for source, table, repeated in find_duplicate_columns():
        print(f'DUPLICATE COLUMN {source}: table {table} declares {repeated} more than once')
        problems += 1

    for path, table, superclass, fields in entities:
        if table not in tables:
            print(f'MISSING TABLE  {path.relative_to(BACKEND)}: @Table("{table}") is not created by any migration')
            problems += 1
            continue

        columns = set(tables[table])
        for name in INHERITED.get(superclass, []):
            columns.add(name)

        for field, column, line in fields:
            if column not in columns:
                print(f'MISSING COLUMN {path.relative_to(BACKEND)}:{line}: '
                      f'{field} -> {table}.{column} does not exist')
                problems += 1

    print()
    if problems:
        print(f'{problems} mapping problem(s)')
        return 1
    print('every persisted field maps to a column that the migrations create')
    return 0


if __name__ == '__main__':
    sys.exit(main())
