# spin.build — Claude Context

## Codebase Overview

**spin** is a script-free Java 25 build system that infers what to build by inspecting project structure via pluggable Extensions (discovered via JPMS `ServiceLoader`), then executes a dependency-ordered graph of `Task`s. Extensions auto-detect applicability, declare task dependencies via annotations (`@From`, `@After`, `@Before`, `@PreProcess`, `@PostProcess`), and are composed via a DI framework (`build.codemodel.dependency.injection`, Jakarta Inject compatible). spin is self-hosting: spin₁ builds spin₂ builds spin₃ during the Maven `prepare-package` phase.

**Stack:** Java 25, Maven multi-module, hand-rolled `PomResolver` (artifact resolution), JUnit 6.

**Structure:**
- `spin-api/` — all interfaces, annotations, and option types (the public contract)
- `spin-common/` — `DefaultProgram` (program inference + execution), `DefaultInvocable`, DI resolvers, utilities
- `spin-engine/` — `DefaultEngine` (ServiceLoader discovery, workspace/project tree), `HeapBasedCache`
- `spin/` — `Spin.main()` CLI entry point; jlink packaging
- `spin-modules/` — pluggable extension modules:
  - `spin-java-module` — Java compile/link/javadoc, multi-version JARs, jlink runtime images
  - `spin-module-system-module` — `ModuleGraphClassifier` (split-package resolution), `ModuleCatalog`, artifact versioning
  - `spin-maven-module` — `PomResolver` artifact resolution, Maven packaging (POM/sources/javadoc)
  - `spin-junit-module` — JUnit test runner (Java 8 and Java 25 variants)
  - `spin-configuration-module` — `.spin/` hierarchical config, `.spinignore` workspace detection
  - `spin-checkstyle-module`, `spin-clean-module`, `spin-git-module`, `spin-gpg-module`, `spin-reporting-module`
  - `spin-engine-tests`, `spin-java-module-tests` — fixture-based tests for engine/program behavior
- `spin-testing/` — `WorkspaceDiscovery` JUnit extension for integration tests
- `spin-integration-tests/` — end-to-end tests that build real workspaces with the self-hosted jlink image

For detailed architecture, see [docs/CODEBASE_MAP.md](docs/CODEBASE_MAP.md). For the concept
vocabulary (Project, Task, Invocable, Instruction, Program, and how they relate), see
[docs/CONCEPTS.md](docs/CONCEPTS.md).

## Two Things Called "spin"

spin is both a framework and a binary, and keeping this distinction in mind matters for architectural decisions:

**spin-the-framework** (`spin-api`, `spin-engine`, `spin-common`) — a language-agnostic orchestration engine. It defines the contracts for Extensions, Plugins, Tasks, and the dependency graph. In principle, someone could write Extensions to build Go, TypeScript, or anything else. The CLI layer at this level deals in opaque task-name strings resolved at runtime.

**spin-the-binary** (`spin/` + all `spin-modules/`) — an opinionated Java build tool shipped as a jlink image. It bundles a fixed set of certified Extensions (compile, test, package, checkstyle, clean, javadoc, jlink) that cover the full Java/Maven project lifecycle. The task set here is finite and known at build time.

Most day-to-day work is on the binary. When evaluating CLI design, help text, task discoverability, or per-task options — reason about the binary, not the framework.

## Code Conventions (from coding-conventions.md)

- Google Java Style; enforced by Checkstyle
- No nulls — use `Optional`/`Stream`
- No logging framework — use `TelemetryRecorder`
- No static state except constants
- Getters: `age()` not `getAge()`; `Stream` returns have no `get` prefix
- Interface names: no `I` prefix; implementation classes: no `Impl` suffix; use `Default` prefix
- Constructors: private or package-private; use Builder or static factory
- DI via `build.codemodel.dependency.injection` everywhere

## Key Gotchas

- Tasks must be `public static` non-abstract inner classes of their Plugin to be discovered via reflection
- `version.properties` is not yet used by the engine (marked "coming soon")
- `@Before`/`@After` are ordering-only and never force a task into a Program — only `@From` (and a programmatic `Task#dependencies()` override) does
- `DefaultProgram` executes concurrently (`StructuredTaskScope` + a fair execution-slot semaphore); the semaphore is released body-scoped, not method-scoped, or wide graphs deadlock
- Directory detection never matches `build`/`target` by name (both are package segments here) — it checks for an adjacent `pom.xml` / `build.gradle`
- `ModuleGraphClassifier` lives in `build.percolate.core`, not `spin-module-system-module`
