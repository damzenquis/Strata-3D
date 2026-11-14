# No reflection-based libraries are used; defaults from proguard-android-optimize.txt are sufficient.

# Openize Drako (Draco decoder for glTF) looks up some of its own classes reflectively; keep it intact.
-keep class com.openize.drako.** { *; }
-dontwarn com.openize.drako.**
