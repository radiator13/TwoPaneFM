# Proguard rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# JNI: native methods are registered by symbol name from libfileops.so
-keepclasseswithmembernames class com.twopane.fm.util.NativeFileOps {
    native <methods>;
}
