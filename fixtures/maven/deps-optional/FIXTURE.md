# deps-optional

## What this tests

A dependency declared `<optional>true</optional>`, which is available in the declaring project but not transitively inherited by consumers of that project — with the declaring project (`lib`) and its consumer both genuinely modular (real `module-info.java`, not spin-synthesized).

## Why it is interesting

Maven's dependency resolution never inspects `module-info.class`. `optional` propagation is decided entirely from POM metadata, identically whether the artifact is a JPMS module or not. A build tool that derives what a consumer sees from the sibling's own `module-info.java` `requires` clauses instead of from that sibling's POM (scope/optional/exclusions) will diverge from Maven the moment a modular library has an optional dependency it genuinely needs to compile against.

`lib`'s `module-info.java` declares `requires static com.google.common;` — `requires static` is JPMS's own compile-time-only requirement, the correct pairing for a Maven `optional` dependency in a modular project: without `static`, the JVM refuses to even boot anything depending on `lib` once guava is (correctly) excluded downstream, regardless of the POM's `optional` flag.

Two modules make both halves of the contract checkable:

- `lib` declares guava as `<optional>true</optional>` and uses it internally (`Calculator.sum()`).
- `consumer` depends on `lib` only (not on guava) and asserts guava is unreachable.

## Expected behavior

`lib`: guava compiles and is available at test runtime.

`consumer`: `Class.forName("com.google.common.collect.ImmutableList")` throws `ClassNotFoundException` — guava did not propagate from `lib`'s optional dependency, even though `lib` is a real JPMS module that itself requires guava.
