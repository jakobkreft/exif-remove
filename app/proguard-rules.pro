# The metadata engine enumerates ExifInterface TAG_* constants via reflection
# to copy "all other" EXIF attributes. Keep the fields so reflection works in
# release builds.
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    public static final java.lang.String TAG_*;
}

# kotlinx.serialization
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class si.jakobkreft.exifremove.** {
    *** Companion;
}
-keepclasseswithmembers class si.jakobkreft.exifremove.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The engine disables thumbnail write-back via reflection (see
# ExifProcessor.disableThumbnailWriteback).
-keepclassmembers class androidx.exifinterface.media.ExifInterface {
    boolean mHasThumbnail;
}
