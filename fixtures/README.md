# spin.build test fixtures

Small, orthogonal build projects used to verify that spin handles real-world configurations correctly. Each project isolates one feature so failures are diagnosable.

## Orthogonality rule

One feature per project. If a project needs to test an interaction between two features, name it `combo-<a>-<b>` so it is clearly a deliberate interaction test, not an accidental coupling.

## Naming convention

| Prefix | Meaning |
|---|---|
| `feature-<what>` | Language or compiler features |
| `plugin-<which>` | Specific plugin configurations |
| `ap-<which>` | Annotation processor cases |
| `layout-<shape>` | Project layout variations |
| `deps-<what>` | Dependency resolution cases |
| `module-<what>` | JPMS module cases |
| `combo-<a>-<b>` | Deliberate interaction tests |

## Running the fixtures

After a full build and install:

```
./mvnw clean install
./install.sh --local
./test-fixtures.sh
```

To run a single fixture:

```
./test-fixtures.sh feature-preview
```

Each fixture is run in two phases:
1. `./mvnw clean verify` — ground truth; confirms the fixture itself is valid
2. `spin clean build` — the actual test; if Maven passes but spin fails, spin is the bug

## Adding a new fixture

1. Create `fixtures/maven/<name>/` (or `gradle/` / `spin/` when populated)
2. Add a minimal `pom.xml`, source, and at least one test
3. Add a `FIXTURE.md` describing: what feature is tested, why it is interesting, expected behavior, known quirks
4. Add a `.gitignore` appropriate for the build tool
5. Run `./test-fixtures.sh <name>` locally to confirm both Maven and spin pass

## Directory layout

```
fixtures/
├── maven/     — Maven-based fixture projects
├── gradle/    — Gradle-based (not yet populated)
└── spin/      — spin-native (not yet populated)
```
