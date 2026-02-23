# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# Room Rules
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.Entity
-keep class * extends androidx.room.Dao
-keep class * implements androidx.room.RoomDatabase
-dontwarn androidx.room.**

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.coroutines.android.HandlerContext {
    private final android.os.Handler handler;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase