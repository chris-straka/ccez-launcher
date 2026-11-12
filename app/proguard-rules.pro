# UniFFI / JNA: the generated bindings call the Rust core by name.
-keep class uk.ccez.keys.core.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-dontwarn java.awt.**

# The log names menu actions by class (MainActivity's "menu" event): kept
# readable, not "q11".
-keepnames class uk.ccez.launcher.MenuAction
-keepnames class uk.ccez.launcher.MenuAction$*
