# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-dontpreverify
-dontwarn **
-verbose
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

# Do not optimize/shrink LibVLC, because of native code
-keep class org.videolan.libvlc.** { *; }
# Same for MediaLibrary
-keep class org.videolan.medialibrary.** { *; }

-keep public class * extends android.app.Activity {
    public <methods>;
}
-keep public class * extends android.app.Fragment {
    public <methods>;
}
-keep public class * extends androidx.fragment.app.Fragment {
    public <methods>;
}
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class com.android.vending.licensing.ILicensingService

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keepclassmembers class * extends android.app.Activity {
   public void *(android.view.View);
}

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# support design
-dontwarn android.support.design.**
-keep class android.support.design.** { *; }
-keep interface android.support.design.** { *; }
-keep public class android.support.design.R$* { *; }

-dontwarn android.support.**

# Kotlinx
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }
-keep class kotlin.Result { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
# Retrofit
# Retrofit does reflection on generic parameters. InnerClasses is required to use Signature and
# EnclosingMethod is required to use InnerClasses.
-keepattributes Signature, InnerClasses, EnclosingMethod

# Retrofit does reflection on method and parameter annotations.
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Retain service method parameters when optimizing.
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Ignore annotation used for build tooling.
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

# Ignore JSR 305 annotations for embedding nullability information.
-dontwarn javax.annotation.**

# Guarded by a NoClassDefFoundError try/catch and only used when on the classpath.
-dontwarn kotlin.Unit

# Top-level functions that can only be used by Kotlin.
-dontwarn retrofit2.-KotlinExtensions

# R8 full mode strips generic signatures from classes that are not kept. Retrofit
# reads the Continuation type argument of Kotlin suspend methods, so its signature
# must survive or every call fails with "Class cannot be cast to ParameterizedType".
# Shrinking and obfuscation stay allowed.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

-keep class com.google.android.material.** { *; }

# ---------------------------------------------------------------------------
# R8 keeps for reflection-based JSON (Moshi 1.8, no code generation)
# ---------------------------------------------------------------------------

# Default Moshi adapters read property names from the Java fields, so these
# classes must keep their fields unrenamed or the JSON keys change.
-keep class org.videolan.moviepedia.models.** { *; }
-keep class org.videolan.resources.opensubtitles.** { *; }
-keep class org.videolan.vlc.mediadb.models.** { *; }
-keep class org.videolan.vlc.util.VLCLocale { *; }
-keep class org.videolan.vlc.util.EqualizerExport { *; }
-keep class org.videolan.vlc.util.EqualizerState { *; }
-keep class org.videolan.vlc.gui.Licenses { *; }
-keep class org.videolan.vlc.gui.Library { *; }
-keep class org.videolan.vlc.gui.License { *; }
-keep class org.videolan.vlc.gui.preferences.widgets.SavedColors { *; }
-keep class org.videolan.vlc.gui.preferences.search.SettingsBackup { *; }
-keep class org.videolan.vlc.gui.preferences.search.SettingEntry { *; }

# KotlinJsonAdapterFactory resolves constructor parameters through Kotlin
# metadata and kotlin-reflect, so the discourse and bookmark models must stay.
-keep class org.videolan.vlc.discourse.** { *; }
-keep class org.videolan.vlc.bookmarks.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }
-dontwarn kotlin.reflect.**

# Enum constant names are persisted in SharedPreferences and JSON payloads,
# so valueOf()/name() must remain stable across app upgrades.
-keepclassmembers enum * { *; }
