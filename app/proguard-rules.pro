# ---------------------------------------------------------------------------
# LibreTV-TV R8 / ProGuard 规则
# 本工程没有 Class.forName / getDeclaredMethod 之类的反射调用，唯一「R8 看不见」
# 的入口是 WebView 通过 JS 字符串调用的 @JavascriptInterface 方法，必须保留。
# ---------------------------------------------------------------------------

# 保留运行时注解：WebView 在 addJavascriptInterface 时靠反射找 @JavascriptInterface
-keepattributes *Annotation*

# 保留所有带 @JavascriptInterface 的方法及其原名（exo.play / liveBridge.play /
# liveBridge.setup / appHost.exit ...）
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 桥接对象是 MainActivity 的匿名内部类，整体保留，避免被改名或优化掉
-keep class com.ilatv.tv.MainActivity$* { *; }

# Manifest 里声明的四大组件（R8 会依据 Manifest 自动保留，这里仅作防御）
-keep public class * extends android.app.Activity
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Service

# 本应用自身代码不混淆，崩溃日志里还能看到真实类名；第三方库仍可被压缩/混淆
-keepnames class com.ilatv.tv.** { *; }

# ---------------------------------------------------------------------------
# SMB（jcifs-ng / BouncyCastle）
# jcifs 会用 ServiceLoader 与字符串反射加载部分实现，R8 看不见这些入口；
# 保留整个 jcifs 包，其余第三方库继续按可达性裁剪以控制 APK 体积。
# ---------------------------------------------------------------------------
-keep class jcifs.** { *; }
-dontwarn jcifs.**
# BC 只被 NTLM / SMB3 密钥派生静态引用，未用到的 provider 照样让 R8 裁掉
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.asn1.ASN1ObjectIdentifier { *; }
-keep class org.bouncycastle.asn1.ASN1Primitive { *; }
-keep class org.bouncycastle.util.encoders.** { *; }
-keep class org.bouncycastle.crypto.digests.SHA256Digest { *; }
-keep class org.bouncycastle.crypto.macs.HMac { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**
