# 若重新开启 minify，下列规则为播放/网络最小 keep

# data / 模型（Gson + 播放）
-keep class com.blyen.ytv.data.** { *; }
-keep class com.blyen.ytv.models.** { *; }
-keep class com.blyen.ytv.requests.** { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 密文源解码（仍可能读到 hex 缓存）
-keep class com.blyen.ytv.SourceDecoder { *; }

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
-dontwarn com.google.gson.**

# OkHttp（完整保留，避免 release 拉流失败）
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.**
-dontwarn okio.**

# Media3 / 播放
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 协程
-keep class kotlin.coroutines.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.blyen.ytv.databinding.*Binding { *; }
-keepclassmembers class com.blyen.ytv.databinding.*Binding {
    public <methods>;
    <init>(...);
}

# 起播关键类（重新开 minify 时防被内联/裁剪）
-keep class com.blyen.ytv.PlayerFragment { *; }
-keep class com.blyen.ytv.MainViewModel { *; }
-keep class com.blyen.ytv.MainActivity { *; }
-keep class com.blyen.ytv.YTVApplication { *; }
-keep class com.blyen.ytv.SP { *; }
-keep class com.blyen.ytv.requests.HttpClient { *; }

# 仅剥 verbose/debug 日志；保留 w/e 便于真机排障
-assumenosideffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
