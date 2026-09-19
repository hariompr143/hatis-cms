#!/usr/bin/env python3
"""
Apply the Flyway migrations to a real PostgreSQL and report the first failure.

The migrations are the product's schema. Nothing else in this repository proves they
run: the entity check only compares names, and the integration test that would catch
this needs Docker. This applies them in filename order, each in its own transaction,
and stops at the first error so later failures are not mistaken for independent
defects - they usually cascade.

It found that V1_003 referenced a CTE from a second statement, which fails with
"relation system_roles does not exist" and would have broken every Flyway run.

Requires a local PostgreSQL. If `pgserver` is installed it is used and no server
configuration is needed:

    pip install pgserver
    python3 tools/check_migrations.py

Two substitutions are made to a throwaway copy of the SQL, never to the migrations
themselves, because a from-source PostgreSQL build ships no contrib modules:
`create extension pgcrypto` is dropped (gen_random_uuid() has been core since
PostgreSQL 13) and `citext` is provided as a plain text domain. Everything else -
tables, constraints, triggers, row level security, roles, seed data - runs verbatim.
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MIGRATIONS = os.path.join(
    REPO, 'backend/hatis-api/src/main/resources/db/migration')


def start_server(data_dir):
    import pgserver
    pgserver.get_server(data_dir, cleanup_mode=None)
    binary = glob.glob(os.path.join(
        os.path.dirname(pgserver.__file__), 'pginstall/bin/psql'))[0]
    return binary, {'PGHOST': data_dir, 'PGUSER': 'postgres', 'PGDATABASE': 'postgres'}


def run(psql, sql, env):
    return subprocess.run([psql, '-q', '-c', sql], env=env,
                          capture_output=True, text=True)


def shim(work_dir):
    """Copy the migrations with the two contrib statements neutralised."""
    for source in sorted(glob.glob(os.path.join(MIGRATIONS, '*.sql'))):
        text = open(source).read()
        text = re.sub(r'(?i)create extension if not exists (?:pgcrypto|citext)\s*;',
                      '-- substituted by tools/check_migrations.py: no contrib here',
                      text)
        open(os.path.join(work_dir, os.path.basename(source)), 'w').write(text)


def main():
    data_dir = os.environ.get('HATIS_PGDATA', '/tmp/hatis-migrations-pg')
    psql, admin = start_server(data_dir)

    for statement in ('drop database if exists hatis_migration_check',
                      'drop role if exists hatis_app',
                      'drop role if exists hatis_migrator',
                      'create database hatis_migration_check'):
        run(psql, statement, admin)
    env = dict(admin, PGDATABASE='hatis_migration_check')
    run(psql, 'create domain citext as text', env)

    work_dir = tempfile.mkdtemp(prefix='hatis-migrations-')
    shim(work_dir)

    files = sorted(glob.glob(os.path.join(work_dir, '*.sql')))
    applied = 0
    for path in files:
        result = subprocess.run(
            [psql, '--single-transaction', '-v', 'ON_ERROR_STOP=1', '-q', '-f', path],
            env=env, capture_output=True, text=True)
        if result.returncode != 0:
            print(f'FAIL {os.path.basename(path)}')
            for line in result.stderr.splitlines():
                if any(key in line for key in ('ERROR', 'LINE', 'DETAIL', 'CONTEXT')):
                    print('     ' + line.strip())
            print(f'\n{applied} of {len(files)} migrations applied')
            return 1
        applied += 1

    tables = run(psql, "select count(*) from pg_tables where schemaname = 'public'", env)
    rls = run(psql, "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace "
                    "where n.nspname = 'public' and c.relrowsecurity", env)
    roles = run(psql, "select string_agg(rolname, ', ' order by rolname) "
                      "from pg_roles where rolname like 'hatis%'", admin)
    print(f'{applied} of {len(files)} migrations applied cleanly')
    print(f'  tables:            {tables.stdout.strip()}')
    print(f'  tables with RLS:   {rls.stdout.strip()}')
    print(f'  roles created:     {roles.stdout.strip()}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
