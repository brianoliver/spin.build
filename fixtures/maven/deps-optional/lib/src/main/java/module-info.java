module build.spin.fixtures.optional.lib {
    // static: guava is compile-time-only for this module, matching the pom's <optional>true</optional> —
    // JPMS's own analogue of "not a hard runtime dependency." A plain (non-static) requires here would
    // make the JVM refuse to even boot anything depending on this module once guava is correctly excluded
    // downstream, regardless of what the pom's optional flag says.
    requires static com.google.common;

    exports build.spin.fixtures.optional;
}
