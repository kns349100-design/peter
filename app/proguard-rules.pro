# ============================================================================
# AutoDialer R8 / ProGuard rules (release builds, minifyEnabled true)
# ============================================================================

# --- Crash reports: keep file/line info, hide the original file name ---------
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile

# --- Logging: strip debug/verbose/info logcat calls from release -------------
# (the app only logs through SafeLog, which scrubs phone numbers; this removes even those)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# --- Enums whose NAMES are written to disk (valueOf(name) on restore) --------
# Obfuscating them would make every saved session unreadable after an update.
-keepclassmembers enum com.autodialer.app.CallSequencer$Status { *; }
-keepclassmembers enum com.autodialer.app.CallSequencer$Phase { *; }
-keepclassmembers enum com.autodialer.app.JournalType { *; }
-keepclassmembers enum com.autodialer.app.OutcomeTag { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Services / entry points referenced from the manifest are kept automatically by AGP.

# --- Razorpay checkout (vendor-documented rules) ------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-dontwarn com.razorpay.**
-keep class com.razorpay.** { *; }
-optimizations !method/inlining/*
-keepclasseswithmembers class * {
    public void onPayment*(...);
}

# --- Google ML Kit text recognition / Play services ship their own consumer rules.
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.**

# --- Kotlin ------------------------------------------------------------------
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
