# spin
A modular Java build system that infers what to do by inspecting project structure — no build scripts required.

[![CI](https://github.com/Workday/spin.build/actions/workflows/main-pull-request.yml/badge.svg)](https://github.com/Workday/spin.build/actions/workflows/main-pull-request.yml)
[![Maven Central](https://img.shields.io/maven-central/v/build.spin/spin)](https://central.sonatype.com/artifact/build.spin/spin)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

## Overview

`spin` discovers pluggable Extensions via `ServiceLoader`, each of which auto-detects what it applies
to and declares task dependencies via annotations. It then executes a dependency-ordered graph of
tasks to compile, test, package, and deploy modular Java applications. See
[docs/CONCEPTS.md](docs/CONCEPTS.md) for a deeper look at the design and its vocabulary.

## Modules

| Module | Purpose |
|--------|---------|
| `spin-api` | All public interfaces and annotations |
| `spin-common` | `DefaultProgram`: program inference and execution |
| `spin-engine` | `DefaultEngine`: ServiceLoader discovery, workspace and project tree |
| `spin` | `Spin.main()` CLI entry point; produces jlink runtime image |
| `spin-modules` | Pluggable extension modules: java, maven, junit, git, config, and more |
| `spin-testing` | `WorkspaceDiscovery` JUnit 5 extension for integration tests |
| `spin-collider` | Subprocess launcher for integration tests requiring a running server |

## Requirements

- Java 8 and Java 25 (both required — see note below)
- Maven (wrapper included — no separate install needed)
- `~/.m2/settings.xml` configured with a Maven repository (e.g. Maven Central)

> `spin` is written in Java 25 and built and tested against Java 25. It is also designed to build
> Java 8 projects, so a Java 8 JDK must be installed for the test suite to pass. `spin` will locate
> both JDKs automatically but will not install them for you. We recommend
> [Azul Zulu](https://www.azul.com/downloads/zulu-community/?package=jdk) builds for both.

## Using this Library

Add individual modules as dependencies. All modules share the same version:

```xml
<dependency>
    <groupId>build.spin</groupId>
    <artifactId>spin-api</artifactId>
    <version>VERSION</version>
</dependency>
```

Replace `VERSION` with the latest version shown in the Maven Central badge above.

## Building from Source

Bootstrap using the Maven wrapper (required before `spin` can build itself):

```bash
./mvnw clean install
```

This produces a distributable zip at `spin/target/spin-<version>-bin.zip`. To install it locally and
add `spin` to your PATH in one step:

```bash
./install-dev.sh
```

Or to rebuild and install in one go:

```bash
./install-dev.sh --build
```

## Contributing

Code style is enforced by Checkstyle: no tabs, no star imports, final locals and parameters, braces
required on all blocks, no `assert` statements. Import order: third-party, standard Java, then
static. IntelliJ configuration is at `config/intellij/CodeStyle.xml`.

Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/).

## License

Apache 2.0 — see [LICENSE](LICENSE)
