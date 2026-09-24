#!/bin/bash
# Explicitly bash, not /bin/sh: the `read -r -d ''` loops below are a bash-ism
# and silently no-op under dash (/bin/sh on Ubuntu) instead of erroring, since
# POSIX exempts a while-loop's condition test from `set -e`.
# spin₂ builds spin₃, then verify spin₂ == spin₃.
set -e

case "$(uname -s)" in Darwin) HOST_OS=mac ;; Linux) HOST_OS=linux ;; *) HOST_OS=other ;; esac
case "$(uname -m)" in arm64|aarch64) HOST_ARCH=aarch64 ;; x86_64|amd64) HOST_ARCH=x86_64 ;; *) HOST_ARCH=other ;; esac
HOST_DIR=".build/spin-${HOST_OS}-${HOST_ARCH}"
rm -rf /tmp/spin2 /tmp/spin2-modules.txt /tmp/spin3-modules.txt /tmp/spin2-extmodules.txt /tmp/spin3-extmodules.txt /tmp/spin2-classpath.txt /tmp/spin3-classpath.txt

# move spin₂ aside: both spin₂ and spin₃ write to the host's own target directory, so spin₂ must vacate before spin₃ can be built
cp -r "$HOST_DIR" /tmp/spin2

# for every workspace project: purge its own .build/ dir left over from spin₁'s
# build of spin₂ above, and its Maven target/classes from this reactor's earlier
# per-module compile. Both are candidate "already built" signals a sibling's
# Task#dependencies() checks before deciding whether to force that sibling's
# Compile task to run -- that decision is made once, up front, when the
# instruction graph is constructed, before any task (including spin₂'s own
# "clean" argument below) actually executes. Left in place, either signal makes
# every sibling look already-built and skips spin-recompiling them here,
# defeating the point of this bootstrap step. This module's own target/classes
# is skipped: nothing else depends on it as a sibling, and it's still needed
# as-is by this module's own package phase. Its own .build/ dir is still
# purged below (harmless: already backed up to /tmp/spin2 above).
REPO_ROOT="$(cd .. && pwd)"
find "$REPO_ROOT" -mindepth 2 -maxdepth 3 -type d -name .build -print0 | while IFS= read -r -d '' BUILD_DIR; do
  PROJECT_DIR="$(dirname "$BUILD_DIR")"
  echo "purging $BUILD_DIR (project: $PROJECT_DIR)"
  rm -rf "$BUILD_DIR"
  if [ "$PROJECT_DIR" != "$(pwd)" ]; then
    echo "removing $PROJECT_DIR/target/classes (project: $PROJECT_DIR)"
    rm -rf "$PROJECT_DIR/target/classes"
  fi
done

/tmp/spin2/bin/spin.sh clean jlink

# spin₂ has now genuinely recompiled every sibling from source into its own
# .build/main/target; copy each one back into Maven's target/classes so
# anything downstream that still expects it (this module's own javadoc
# generation, later reactor phases, an IDE) sees normal build output again.
find "$REPO_ROOT" -mindepth 2 -maxdepth 3 -type d -name .build -print0 | while IFS= read -r -d '' BUILD_DIR; do
  PROJECT_DIR="$(dirname "$BUILD_DIR")"
  if [ "$PROJECT_DIR" != "$(pwd)" ] && [ -d "$PROJECT_DIR/.build/main/target" ]; then
    echo "copying $PROJECT_DIR/.build/main/target to $PROJECT_DIR/target/classes (project: $PROJECT_DIR)"
    cp -r "$PROJECT_DIR/.build/main/target" "$PROJECT_DIR/target/classes"
  fi
done

# structural equivalence check (launcher + linked/external module sets); jar contents are not diffed as jars embed build timestamps
diff /tmp/spin2/bin/spin.sh "$HOST_DIR"/bin/spin.sh
/tmp/spin2/bin/java --list-modules | sort > /tmp/spin2-modules.txt
"$HOST_DIR"/bin/java --list-modules | sort > /tmp/spin3-modules.txt
diff /tmp/spin2-modules.txt /tmp/spin3-modules.txt
# modules/ and classpath/ are only written when non-empty (see AbstractJavaLinker) — tolerate either being absent
{ [ -d /tmp/spin2/modules ] && ls /tmp/spin2/modules | sort || true; } > /tmp/spin2-extmodules.txt
{ [ -d "$HOST_DIR"/modules ] && ls "$HOST_DIR"/modules | sort || true; } > /tmp/spin3-extmodules.txt
diff /tmp/spin2-extmodules.txt /tmp/spin3-extmodules.txt
{ [ -d /tmp/spin2/classpath ] && ls /tmp/spin2/classpath | sort || true; } > /tmp/spin2-classpath.txt
{ [ -d "$HOST_DIR"/classpath ] && ls "$HOST_DIR"/classpath | sort || true; } > /tmp/spin3-classpath.txt
diff /tmp/spin2-classpath.txt /tmp/spin3-classpath.txt

rm -rf /tmp/spin2 /tmp/spin2-modules.txt /tmp/spin3-modules.txt /tmp/spin2-extmodules.txt /tmp/spin3-extmodules.txt /tmp/spin2-classpath.txt /tmp/spin3-classpath.txt
