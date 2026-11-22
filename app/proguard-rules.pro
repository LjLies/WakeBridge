# The open SDK's android.jar (LjLies/android-development) still names some of the framework's
# hidden classes in its signatures, and R8 stops at them as "missing". They are part of Android
# itself, present on every device, never shipped in the APK, so there is nothing to keep.
-dontwarn android.**

# No renaming: the app is open source, so renaming hides nothing, and readable names and line
# numbers make crash reports usable. Shrinking and optimization stay on.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable
