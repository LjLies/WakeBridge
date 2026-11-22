# The open SDK's android.jar (LuccoJ/android-development) still names some of the framework's
# hidden classes in its signatures, and R8 stops at them as "missing". They are part of Android
# itself, present on every device, never shipped in the APK, so there is nothing to keep.
-dontwarn android.**
