#!/usr/bin/env bash
# Optional helper for this workspace. Portable builds should use their own JDK/SDK.
TOOLS="${NARADA_ANDROID_TOOLS:-$HOME/.cache/narada-android-tools}"
if [[ -f "$TOOLS/java-home.txt" ]]; then export JAVA_HOME="$(cat "$TOOLS/java-home.txt")"; fi
export ANDROID_HOME="${ANDROID_HOME:-$TOOLS/sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$TOOLS/android-user}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.cache/narada-android-gradle}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$PATH"
export MALLOC_ARENA_MAX=2
