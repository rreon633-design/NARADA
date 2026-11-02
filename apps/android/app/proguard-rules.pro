# No blanket keep rules. Room/Hilt/Compose supply consumer rules.
# Release shrinking, signing and installation require separate native validation.

# JNI uses this exact class/method name; not a blanket application keep rule.
-keep class com.narada.connection.NativeOlmBridge { *; }

-keep class com.narada.connection.NativeAgeBridge { *; }

# Exact JNI entry point for the filesystem-only no-replace publication primitive.
-keep class com.narada.connection.NativeStorageBridge { *; }
