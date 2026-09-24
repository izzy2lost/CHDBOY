# The JNI entry points are resolved by name at runtime, so R8 must not rename
# the class or its external methods -- there is no reference to them from Java
# for the shrinker to follow, and a renamed one fails with UnsatisfiedLinkError
# only once a conversion is actually started.
-keep class com.izzy2lost.chdboy.core.Native { *; }

# Keep line numbers in stack traces, and hide the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
