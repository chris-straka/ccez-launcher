# UniFFI / JNA: the generated bindings call the Rust core by name.
-keep class uk.ccez.keys.core.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-dontwarn java.awt.**
