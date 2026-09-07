# spin Concepts: A Plain-English Guide

This document exists because spin reuses ordinary English words — `Project`, `Task`, `Resource`,
`Instruction`, `Program` — for very specific, narrow meanings, and the meanings only make sense
together. Reading one interface in isolation doesn't help much. This document builds the vocabulary
up in dependency order: each section only uses terms already introduced above it.

If you just want "what do I annotate to make X happen," skip to
[Configuration & Extension Points](#12-configuration--extension-points). If you want the mental
model, read straight through — it's short enough, and the order matters.

## 1. The world spin looks at: Workspace and Project

Before spin can build anything, it has to make sense of a directory tree. It does this by walking the
filesystem and deciding which directories matter.

- A **`Project`** is a directory that spin has decided is meaningful — because something was
  *detected* there (more on detection below). A `Project` knows its path, its parent `Project` (if
  any), its child `Project`s, and everything that was detected in it.
- A **`Workspace`** is just the root `Project` — the one with no parent. It's the top of the tree for
  a given spin invocation. (`Workspace extends Project`, plus it's `AutoCloseable`.)

So "the project tree" and "the workspace" are the same tree; "workspace" just means "the whole tree,
starting from the top."

Projects nest. A single spin invocation might have a `Workspace` at the repo root and a `Project` for
each Maven-style module underneath it, because each module directory has its own `Plugin`s detected
in it (e.g. each has `src/main/java`).

## 2. How spin decides what's interesting: Extension

An **`Extension`** is spin's umbrella term for "a pluggable capability." Everything pluggable in spin
— every `Plugin`, `Resource`, `Service`, `Daemon`, `Server` you'll read about below — *is* an
`Extension`. spin finds all of them once, at startup, via JPMS `ServiceLoader` (each module's
`module-info.java` has a line like `provides build.spin.Extension$MetaClass with MyThing.MetaClass`).

The reason `Extension` exists as a shared concept at all is that every kind of extension needs the
same two things: a way to be *found* (a `MetaClass` with detection logic) and a way to contribute
command-line options. What differs between the five kinds below is only **scope** (workspace-wide vs.
per-project) and **lifecycle** (one-shot vs. long-running background process). That's the entire
axis of variation:

| | Workspace-wide (detected once, available everywhere) | Per-project (detected per directory, cascades to children) |
|---|---|---|
| **One-shot / passive** | `Service` | `Resource` |
| **Long-running background process** | `Server` | `Daemon` |
| **Declares `Task`s** | — | `Plugin` |

- **`Service`** — detected once against the whole environment (e.g. `MavenRepository` is detected
  when `~/.m2` exists), then injectable into *any* `Plugin` or `Task` in *any* `Project`, regardless
  of where it was detected.
- **`Resource`** — detected per `Project` directory. Unlike a `Service`, a `Resource` is only
  injectable into that `Project` and its descendants — not the whole workspace. `Resource`s can also
  mark a path as ignored (`isIgnored`) or mark a directory as a workspace root (`isWorkspace`) —
  that's literally how `.spinignore` and `.git` get recognized as workspace-root markers
  (`ConfigurationResource`, `GitResource`).
- **`Server`** — a `Service` that also runs a long-lived background process (an LSP server, a
  workspace-inspection endpoint). Only relevant in `spin --server` mode. The contract exists; no
  `Server` implementation currently ships in this repository.
- **`Daemon`** — the `Resource` equivalent of a `Server`: per-project, background process. Likewise
  no implementation ships today.
- **`Plugin`** — a per-project `Extension` whose entire purpose is to declare `Task`s (see below). This
  is the one you'll interact with most as an extension author.

**Detection** is the mechanism by which spin decides an `Extension` applies somewhere. Every
`Extension.MetaClass` has an `isDetectedIn(...)` method (checking a `Path`, or a `Project`, depending
on the kind) that returns true/false. `JavaCompilerPlugin` detects `src/main/java`; `CleanPlugin`
detects an existing `.build/` directory (or is just always applicable, depending on which overload).
Detection is what makes spin "script-free" — you don't declare "this is a Java project" anywhere, spin
notices the directory shape.

## 3. The unit of work: Task

A **`Task<T>`** is one unit of work that produces a result of type `T` — compiling sources, copying
resources, running a linter. Concretely, it's a `public static` inner class of a `Plugin`, with a
public non-static method whose return type matches `T` (spin finds this method by reflection; there's
no `execute()` you implement by hand for the common case — you write a method like
`public String compile(...)` and spin calls it).

**A `Task` must be a `public static` inner class of a `Plugin` or it is silently invisible.** Not
abstract, not non-static, not package-private. This trips people up because there's no compile error
— the class just never gets discovered.

`Plugin` and `Task` relate like a class and its members: `JavaCompilerPlugin` (a `Plugin`) declares
`DetectSourcePaths`, `DetectSourceFiles`, `Compile`, `JavaDoc` (each a `Task`). The `Plugin` is "the
Java compilation capability, for this project"; each `Task` is one step within that capability.

A `Task` can declare that it needs another `Task`'s output — see
[§6](#6-instructions-and-the-three-ways-tasks-relate-to-each-other) for exactly how.

## 4. The recipe card: Invocable

An **`Invocable<T>`** is metadata about a specific `Task` class *in a specific `Project`* — before
anything runs. Given a `Task` class and the `Project`/`Plugin` it belongs to, an `Invocable` can tell
you: its name (from `@Named`, or the class's simple name), its categories (`@Category`), whether it's
a codependency, whether it matches a given `Task.Pattern`, and — critically — its `dependencies()`:
the `Reference`s (see next) of the other `Task`s it needs, computed by reflecting over its execution
method's `@From`-annotated parameters.

Think of an `Invocable` as a recipe card: it describes what a dish needs and how it's identified, but
it isn't "the dish being cooked right now" — that's an `Instruction`
([§6](#6-instructions-and-the-three-ways-tasks-relate-to-each-other)). `DefaultInvocable` is the
concrete implementation used everywhere. An `Invocable`'s `dependencies()` are *only* the `@From`
edges; a task that needs to compute dependencies programmatically does so by overriding
`Task#dependencies()` instead (see [§11](#11-taskdependencies-the-programmatic-cross-project-hook)).

## 5. The address: Reference

A **`Reference`** is an immutable value identifying "this `Task` class, in this `Project`" —
essentially a `(Project, Task class)` pair, plus (derived from the `Task` class) a `Plugin` class.
`Reference`s are what dependency lists are made of, and what `AssetCache`
([§8](#8-the-results-asset-and-assetcache)) uses as its keys. Two `Reference`s to the "same" task in
the same project are `.equals()` even if they wrap different object instances, which is what lets the
same task be looked up consistently across the graph.

## 6. Instructions, and the three ways tasks relate to each other

Before we can talk about *how* a task's declared relationships to other tasks actually behave, we need
one more piece of vocabulary: an **`Instruction<T>`**. It's what an `Invocable` becomes once spin has
decided this task is actually going to run this time, as part of a specific batch of work (that batch
is called a `Program` — the next section covers it in full; all you need here is "the set of tasks
spin is about to run"). Where an `Invocable` can only tell you "here's what I need" in isolation, an
`Instruction` is built *knowing every other task also going to run in the same batch*, so it can
resolve real edges to them — who this task waits on, who's bundled with it, who waits on this task in
turn.

Each `Instruction` sorts its relationships to other tasks into three buckets, and each bucket is
produced by a different annotation. Conflating these three is the single most common source of
confusion in this codebase, so here they are side by side:

- **`@From(OtherTask.class)`** on a parameter — a *real, data-carrying* dependency. `OtherTask` is
  guaranteed to run first, and its result is injected into that parameter. Landing in this bucket
  (`Instruction#requiredDependencies()`) is what actually causes spin to schedule `OtherTask` at all —
  see §7 for exactly how that plays out. This is the *only* one of the three annotations below that
  can do that.
- **`@Before(OtherTask.class)` / `@After(OtherTask.class)`** on the `Task` class (the annotation
  also permits a method target, but the class is the normal place) — *ordering only, no
  data*. "If both of us end up running anyway, run in this relative order." This lands in
  `Instruction#dependencies()` (the ordering graph) but **not** in `requiredDependencies()` — so
  declaring `@Before`/`@After` never, by itself, causes the named task to be scheduled. It only
  constrains order *if* both tasks are already going to run for some other reason. It is a common
  mistake to expect `@Before`/`@After` to pull a task in; only `@From` does that.
- **`@PreProcess(OtherTask.class)` / `@PostProcess(OtherTask.class)`** on the `Task` class — a
  **codependency**. This lands in `Instruction#codependencies()`, a third bucket entirely. Unlike
  `@Before`/`@After`, a codependency is *always* executed alongside the task it names — there's no
  "only if independently required" escape hatch. But also unlike `@From`, a codependency never becomes
  a first-class, independently-schedulable task: it's run *inline*, as a synchronous step immediately
  before (`@PreProcess`) or after (`@PostProcess`) the named task's own body, within that task's own
  execution. Its result is private to that relationship — nothing else can `@From` a codependency's
  output. Codependencies are resolved transitively: one that itself declares `@PreProcess`/
  `@PostProcess` has its own nested codependencies discovered too.

If you remember one thing: **`@From` = "I need your output, and that means you must exist."**
**`@Before`/`@After` = "if we're both here anyway, this is the order."**
**`@PreProcess`/`@PostProcess` = "I always come bundled with you, invisibly, as a wrapper step."**

To summarize what an `Instruction` actually exposes, now that you know where each bucket comes from:

- `dependencies()` — everything this task is ordered after: both `@From` *and* `@Before`/`@After`
  edges together. Used to build the execution graph.
- `requiredDependencies()` — the strict subset of the above that's *forcing*: the `@From` edges (by
  way of `Invocable#dependencies()`) plus any `Task#dependencies()` a task declares programmatically
  (§11), plus the same for every codependency bundled into this instruction. This is what decides
  whether a referenced task gets scheduled at all.
- `codependencies()` — the `@PreProcess`/`@PostProcess` tasks bundled with this one.
- `dependents()` — the reverse edges: who depends on *this* task.

One `Instruction` exists per `Task` that ends up running. It's the graph node.

## 7. The plan itself: Program

A **`Program`** is the fully-resolved, ready-to-run batch of `Instruction`s for a requested
`Task.Pattern` (e.g. "compile", or a `@Category` name like "test") — the concrete "batch of work" that
§6 kept referring to. `DefaultProgram` builds it in two phases:

**Inference** (`DefaultProgram`'s constructor): start from every `Invocable` in the project tree that
matches the requested pattern (by exact name, or by `@Category`), and push it onto a work stack. Pop
each one, build its `Instruction` (which resolves its `requiredDependencies()`, per §6), and push any
newly-discovered required dependency that isn't already known. Repeat until the stack is empty — this
is a depth-first expansion outward from the requested tasks to everything they transitively require
(and only *require* — `@Before`/`@After` edges never trigger this expansion, by design). Then add any
`@Automatic` tasks not already pulled in (these run in every `Program`, unconditionally). Finally,
build an explicit dependency graph (`Reference` → `Reference` edges, "A depends on B") from every
`Instruction`'s `dependencies()`, including a codependency's own `@From` dependencies, so those
participate in graph edges and cycle detection too. If the graph has a cycle, that's recorded now and
thrown at execution time.

**Execution** (`Program#execute(AssetCache)`): tasks with zero pending dependencies are dispatched
first, concurrently, via `StructuredTaskScope`. For each task: run any `@PreProcess` codependencies
inline, run the task's own body, run any `@PostProcess` codependencies inline, then store the result
(an `Asset`, or a `VoidAsset` sentinel for `Task<Void>`) in the cache. Completing a task decrements
the pending-dependency count of everything that depends on it; anything that hits zero gets dispatched
next. If a task fails, no *new* tasks are dispatched, but anything already in flight is left to finish.

Dispatch onto virtual threads is unbounded, but a fair `Semaphore` — sized by the `ExecutionSlots`
option (§12) — bounds how many task *bodies* run at once. The slot is held only for the body, not
while a completed task forks and waits on its dependents, so a wide readiness front can't starve its
own children of slots.

## 8. The results: Asset and AssetCache

An **`Asset<T>`** is the immutable, wrapped result of running one `Task` — keyed by that task's
`Reference`. An **`AssetCache`** is where these live, keyed by `Reference`, so that a task with
`@From(OtherTask.class)` can look its dependency's result up once `OtherTask` has completed.
`NearAssetCache` layers two caches — a fresh one for *this* `Program`'s own results (the "front") over
a possibly-populated one from a *previous* `Program` execution in the same process (the "back") — which
is how spin avoids recomputing a task that already ran earlier in the same CLI invocation (e.g. when
chaining multiple requested task names).

## 9. The engine and the CLI

The **`Engine`** is the top-level facility that ties all of the above together: it does `Extension`
discovery (once, at startup), builds the `Workspace`/`Project` tree by walking the filesystem and
running detection, and creates `Program`s on request. `Spin.main()` creates one `DefaultEngine`,
builds the workspace for the current directory, and then either enters server mode (would start every
`Server`/`Daemon`'s background process and wait — but no `Server`/`Daemon` currently ships, so this
path does nothing useful yet) or one-shot mode (builds and executes a `Program` per requested task
name, sharing an `AssetCache` across them so later tasks can reuse earlier results).

## 10. How spin reports what it's doing: Telemetry

spin has no logging framework — there is no `Logger`, no SLF4J, no `System.out.println` in task code.
Everything a task, `Program`, or the `Engine` wants to say — an informational line, a warning, a
compile error, progress through a long operation — is published as a typed **`Telemetry`** value and
delivered to subscribers. This is the *only* observable-output mechanism, and every convention doc in
the codebase points at it ("no logging framework — use `TelemetryRecorder`").

**Recorders.** Every `Engine`, `Program`, and `Task` gets its own `TelemetryRecorder`, identified by
a `SpinURI` that encodes what it is (`program://…`, a per-task URI, etc.). Code records an event by
calling a method on its recorder:

- `info(...)` — an informational line for the user.
- `warn(...)` — something unexpected but non-fatal (optionally carrying a `Throwable`).
- `error(...)` — a recoverable error; the build may continue.
- `fatal(...)` — an unrecoverable error.
- `diagnostic(...)` — verbose detail, suppressed unless `Verbose` is on.
- `notify(...)` — a `Notification` aimed at tooling subscribers rather than the user.
- `advice(...)` — a suggestion or recommendation.
- `commence(...)` — opens a tracked activity (see below).

**Events.** Each call produces a concrete `Telemetry` subtype — `Information`, `Warning`, `Error`,
`Fatal`, `Diagnostic`, `Notification`, `Advice`, `Commenced`, `Completed`, `Progress` — each carrying
its source URI and a timestamp, so a subscriber can reconstruct a structured trace of what happened,
when, and in which component. Most of the recorder methods also have overloads that attach one or
more source `Location`s to the event (a file, a line/column range) for tooling to resolve.

**Activities and meters.** `commence(String)` returns an `Activity` handle for an in-progress unit of
work; the caller closes it with `complete()` on success or `completeExceptionally(Throwable)` on
failure, producing a `Completed` or `Error` event. `commence(int, ...)` returns a `Meter`, which
additionally emits `Progress` events as work advances — that's what drives progress bars and
estimated-time-remaining output.

**Routing.** `DefaultEngine` holds a `SubscriberRegistry<Telemetry>` (`observers`); every recorder's
events flow into it via `engine.publish(...)`, and `engine.subscribe(subscriber)` registers a
consumer. Today there is exactly one: `Spin.main()` hands the engine (at construction) a callback
that subscribes a renderer printing each event — timestamp plus the `Telemetry`'s own `toString()` —
to stderr. The registry is the intended integration point for other consumers (an IDE, a server-mode
UI), but nothing else in this repository subscribes.

## 11. `Task#dependencies()` — the programmatic cross-project hook

`@From` covers dependencies you can name at compile time. It can't express "depend on the `Compile`
task of whichever sibling projects my module descriptor happens to `requires`" — that set isn't known
until the workspace has been walked. `Task#dependencies()` is the escape hatch for exactly this: a
default method on `Task` (returns `Stream.empty()`) that a task class can override to compute
`Reference`s to other tasks — in this or any other project — from whatever runtime information it has.
Whatever it returns is folded into that task's `requiredDependencies()` (§6), right alongside its
`@From` edges, so it's genuinely forcing: it can pull a task into the `Program` that nothing else
asked for.

In the Java module this is how cross-project compile ordering gets derived from the module graph. Two
tasks in the compile pipeline need it — `AbstractDetectResolution` (dependency resolution, which
needs each required sibling's output directory to already exist on disk when it runs) and
`AbstractCompile` itself. Both override `dependencies()`, and both delegate to one shared helper,
`AbstractDetectResolution.siblingCompileDependencies(...)`, which walks the module descriptor's
`requires` clauses and returns a `Reference` to the `JavaCompilerPlugin.Compile` task of every
workspace sibling that provides one — skipping any sibling already compiled on disk, so an
already-built dependency isn't forced to rebuild. Keeping the logic in one helper is deliberate: the
two tasks sit at different points in the same pipeline but must agree on which siblings to wait for.

## 12. Configuration & Extension Points

### Annotations reference

| Annotation | Goes on | What it actually does |
|---|---|---|
| `@From(Task.class)` | a `Task` execution-method parameter | Real dependency + data injection. See §6. Abstract/interface targets require the parameter to be `Stream<T>` (every matching implementor becomes a dependency), unless the target is `@Merge`-annotated, in which case a plain `T` parameter is allowed and results are combined via a `merge(Stream<T>)` method you provide. |
| `@Before(Task.class)` / `@After(Task.class)` | a `Task` class or method (class is normal) | Ordering only, never forces inclusion. See §6. |
| `@PreProcess(Task.class)` / `@PostProcess(Task.class)` | a `Task` class or method (class is normal) | Codependency: always runs, inline, never independently schedulable. See §6. |
| `@Automatic` | a `Task` class | This task is added to *every* `Program`, whether or not anything requested or depends on it. |
| `@Category("name")` | a `Task` class (repeatable) | An additional name a `Task.Pattern` can match on, besides the task's own `@Named` name. This is how e.g. `spin test` runs every task categorized `"test"` across every project, not just one specifically-named task. |
| `@Named("name")` | a `Task` class | The name used to request this task by pattern and to display it. Defaults to the simple class name if omitted. |
| `@Description("text")` | a `Task` class | Human-readable text shown in CLI help output — purely cosmetic, no behavioral effect. |
| `@NoCache` | a `Task` class | Opts this task out of cross-`Program` result caching (`NearAssetCache`'s "back" layer, §8) — it always reruns. |
| `@Bootstrap` | a `Service.MetaClass` | This service is detected and instantiated *before* project-tree discovery starts, and before non-bootstrap services. Needed if other detection logic depends on it (e.g. reading `~/.spin/` config). |
| `@SpecifiedBy(Invocable.class)` | a `Task` class | Overrides which `Invocable` implementation class wraps this particular task, in case you need custom dependency-resolution logic beyond what `DefaultInvocable` gives you for free. |
| `@System` | a field/parameter/method | A Jakarta `@Qualifier` requesting the system-detected value of something rather than a user override — used sparingly, e.g. for JDK version detection. |

### Option types (`build.spin.option`)

These are CLI-configurable values, injected wherever a `Task`/`Plugin` asks for them:

| Option | Default | Meaning |
|---|---|---|
| `BuildDirectoryName` | `.build` | Where per-project build output goes. |
| `TargetDirectoryName` | `target` | Sub-directory name within a task's build output area (mirrors Maven's `target/` convention). |
| `EngineVersion` | auto-detected from spin's own JPMS module descriptor | spin's own version. |
| `ExecutionSlots` | `Runtime.availableProcessors()` (or the `spin.execution.slots` system property) | Bounds how many task bodies run concurrently — the size of the fair semaphore in §7. An invalid value fails the build. |
| `JlinkTargets` | `ALL_STAGED` | `ALL_STAGED` / `HOST_ONLY` (`--jlink-host-only`) — whether `jlink` builds an image for every discovered target platform or just the host. |
| `NetworkAccess` | `ONLINE` | `ONLINE`/`OFFLINE` — governs whether artifact resolution is allowed to hit the network. |
| `OperatingSystem` | auto-detected | Used for OS-specific packaging/linking decisions. |
| `ReuseExternalBuildOutput` | `DISABLED` | `ENABLED`/`DISABLED` — trust an existing Maven `target/classes` / Gradle output as equivalent to spin's own `.build/` output. |
| `Root` | — | Repeatable (`--root`); each value adds another physical directory root to a single federated `Workspace`. |
| `ServerMode` | `DISABLED` | `ENABLED`/`DISABLED` — whether `spin --server` mode is active. |
| `ServerPort` | `8686` | TCP port used by `spin --server`. |
| `Verbose` | `DISABLED` | `ENABLED`/`DISABLED` — extra telemetry output. |

### Extension points, by "I want to..."

- **...add a new build capability (a family of tasks):** implement `Plugin`, add `public static`
  inner classes implementing `Task<T>`, register the `Plugin.MetaClass` via
  `provides build.spin.Extension$MetaClass with ...` in `module-info.java`.
- **...make a task depend on another task's output:** `@From(OtherTask.class)` parameter. See §6.
- **...make a task always run:** `@Automatic`.
- **...group tasks so a pattern can select many at once:** `@Category("name")`.
- **...bundle a task that must silently wrap another (no independent scheduling):** `@PreProcess` /
  `@PostProcess`. See §6.
- **...add a project-wide value injectable everywhere:** implement `Service`; add `@Bootstrap` to its
  `MetaClass` if project discovery itself needs it to already be running.
- **...add something scoped to one project and its children:** implement `Resource`.
- **...enforce cross-project task ordering derived from your own project-graph logic (not `@From`):**
  override `Task#dependencies()` on the task class. See §11.

## 13. Quick-reference glossary

For when you just need the one-liner:

- **Extension** — anything pluggable spin discovers via `ServiceLoader`. Umbrella term.
- **Service** — a workspace-wide `Extension`, detected once, injectable everywhere.
- **Resource** — a per-project `Extension`, detected per directory, injectable there and in children.
- **Server** / **Daemon** — a `Service`/`Resource` that also runs a background process.
- **Plugin** — a per-project `Extension` that declares `Task`s.
- **Task** — one unit of work; a `public static` inner class of a `Plugin`.
- **Invocable** — metadata about a `Task` class in a specific `Project`, before anything runs.
- **Reference** — the `(Project, Task class)` address used as a cache key and in dependency lists.
- **Instruction** — an `Invocable` with its real graph edges resolved, once it's part of a `Program`.
- **Program** — the fully-resolved, ready-to-execute set of `Instruction`s for a requested pattern.
- **Engine** — discovers `Extension`s, builds the `Workspace`/`Project` tree, creates `Program`s.
- **Project** — a directory spin considers meaningful.
- **Workspace** — the root `Project`.
- **Asset** — the immutable result of running one `Task`.
- **AssetCache** — where `Asset`s are stored, keyed by `Reference`.
- **Telemetry** — a typed observable event (`Information`, `Warning`, `Progress`, …); spin's only
  output mechanism, in place of a logging framework.
- **TelemetryRecorder** — the per-`Engine`/`Program`/`Task` handle code calls to publish `Telemetry`.
