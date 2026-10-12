# Keep the JNI entry points. NativeServer's methods are only ever called from Kotlin, so
# R8 cannot see the reference from the Java side and would otherwise strip them — leaving a
# build that installs and then throws UnsatisfiedLinkError on the first call.
-keepclasseswithmembernames class com.dev2go.runtime.NativeServer {
    native <methods>;
}
-keep class com.dev2go.runtime.NativeServer { *; }

# llama.cpp's server and the NDK's JNI glue both rely on names that survive only because
# nothing references them from Java.
-keep class com.dev2go.runtime.** { *; }