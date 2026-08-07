# Preserve generic signatures and annotations used by dependency injection,
# Kotlin metadata, and generated serializers after R8 optimization.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault

# Keep Kotlin metadata so stack traces and reflective Android framework access
# retain accurate declarations in optimized release builds.
-keep class kotlin.Metadata { *; }