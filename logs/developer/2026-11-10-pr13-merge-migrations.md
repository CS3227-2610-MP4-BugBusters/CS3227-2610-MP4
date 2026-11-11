# Reporter PR 13 migration integration — 10 November 2026

The merged shared migration order is Reporter timestamps V4, Reporter discussion comments V5, Developer private notes V6 and Developer notifications V7. A duplicate V5 prevented Flyway startup before the change.

A disposable database test covers a fresh schema and an upgrade from the canonical Reporter V5 history containing a seeded report and comment; it verifies both survive and Developer tables are created. This does not establish upgrades for earlier Developer databases that already recorded private notes or notifications at different versions. Preserve those databases and design a separate data-preserving migration; do not repair or rewrite Flyway history blindly. No real databases were modified.

The initial test after renumbering still found stale generated migration files in the Gradle build output. `./gradlew clean --no-daemon` removed those generated copies; the targeted test then passed. Developers switching between the old and new migration numbering should clean build outputs before testing. Test evidence and exact results are reported after the broader verification run.

Verification: `python3 src/backend/auth/test/run_setup.py` passed the full backend suite on disposable PostgreSQL. The focused `--tests mp4.auth.SharedMigrationVersionTest` passed again after restoring the V4 assertion. Developer frontend `npm run verify` passed 62 tests, Maintainer passed 115, and Reporter passed 10; all three passed typecheck and build. Reporter verification required installing the shared Markdown package's dependencies as documented. Strict OpenSpec validation passed all 13 items. The Reporter standalone Docker test and a deployed/browser journey were not run.
