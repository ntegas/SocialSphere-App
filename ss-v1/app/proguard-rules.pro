# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ── Socialsphere ProGuard Rules ──────────────────────────────

# Android-компоненты инстанцируются системой рефлексией по имени из манифеста.
# R8 не видит этих обращений и без явного keep удаляет классы из dex →
# ClassNotFoundException в рантайме (BootReceiver на ACTION_BOOT_COMPLETED).
-keep class com.aistudio.socialsphere.crmlxb.** extends android.content.BroadcastReceiver { *; }
-keep public class * extends android.content.BroadcastReceiver { *; }
-keep public class * extends android.app.Service { *; }
-keep public class * extends android.app.Activity { *; }
-keep public class * extends android.app.Application { *; }
-keep public class * extends android.content.ContentProvider { *; }

# Room — не обфусцировать Entity классы
-keep class com.aistudio.socialsphere.crmlxb.data.local.*Entity { *; }
-keep class com.aistudio.socialsphere.crmlxb.data.local.*Dao { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# Moshi — JSON сериализация
-keep class com.aistudio.socialsphere.crmlxb.model.** { *; }
-keepclassmembers class com.aistudio.socialsphere.crmlxb.model.** { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}

# Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Google Maps
-keep class com.google.android.gms.maps.** { *; }
-keep interface com.google.android.gms.maps.** { *; }

# Enums — важно для Room
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Метаданные Kotlin + дженерики/аннотации — нужны Moshi (рефлексия) и Retrofit,
# иначе на release-сборке падает (де)сериализация JSON-бэкапа.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-keep class kotlin.Metadata { *; }
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn org.codehaus.mojo.**
