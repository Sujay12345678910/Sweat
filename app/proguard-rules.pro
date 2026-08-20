# JSch (mwiede fork) resolves cipher/kex/mac implementations by class name
# via reflection — keep everything so minification doesn't strip a class
# that's only ever referenced by string.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
