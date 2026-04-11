# ============================================================================
# R8 rules for the cpp-ide app.
#
# Most deps (Compose, Room, kotlinx-coroutines, AndroidX lifecycle, LiteRT-LM)
# ship their own consumer rules and need nothing here. The rules below cover
# the cases R8 cannot figure out from bytecode alone — reflection-based
# frameworks and JNI entry points.
# ============================================================================

# Preserve stack traces in Logcat / bug reports. Without these the mapping
# file is still required but at least file:line stays visible.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Reflection metadata needed by Gson (used internally by LSP4J and tm4e)
# and by any generics-aware deserializer.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# ---------------------------------------------------------------------------
# LSP4J — JSON-RPC client for clangd.
#
# LSP4J deserializes LSP protocol messages with Gson reflection over *fields*.
# Obfuscating or stripping those fields silently breaks clangd integration at
# runtime (completion/hover/diagnostics just stop arriving, no stacktrace).
# ---------------------------------------------------------------------------
-keep class org.eclipse.lsp4j.** { *; }
-keep interface org.eclipse.lsp4j.** { *; }
-keep class com.google.gson.** { *; }
-keep interface com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---------------------------------------------------------------------------
# Sora editor — uses reflection to load TextMate grammars and themes from
# assets/textmate. The language-textmate AAR bundles Eclipse tm4e under
# org.eclipse.tm4e.** — its raw grammar and theme model classes are Gson-
# deserialized, and R8 class-merging turns them into abstract classes that
# Gson cannot instantiate (crash: "Abstract classes can't be instantiated!"
# from GrammarRegistry.doLoadGrammar at app startup).
#
# Keep both packages wholesale, including no-arg constructors required by
# Gson's ConstructorConstructor. Also keep org.joni (regex engine used by
# tm4e for grammar pattern matching — emits an R8 warning otherwise).
# ---------------------------------------------------------------------------
-keep class io.github.rosemoe.sora.** { *; }
-keep interface io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep interface org.eclipse.tm4e.** { *; }
-keepclassmembers class org.eclipse.tm4e.** {
    <init>(...);
}
-keep class org.joni.** { *; }
-dontwarn io.github.rosemoe.sora.**
-dontwarn org.eclipse.tm4e.**
-dontwarn org.joni.**

# ---------------------------------------------------------------------------
# LiteRT-LM — JNI-heavy on-device LLM runtime. Ships consumer rules but
# belt-and-suspenders in case a future version drops them.
# ---------------------------------------------------------------------------
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# ---------------------------------------------------------------------------
# JNI — :core already has a consumer rule for NativeBridge, but a blanket
# rule for any class that declares native methods protects us if more JNI
# surfaces show up later.
# ---------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# ---------------------------------------------------------------------------
# Compose ViewModels are instantiated reflectively via ViewModelProvider.
# Compose's own consumer rules cover androidx.lifecycle.ViewModel subclasses
# that are referenced from code, but keep our own as a safety net.
# ---------------------------------------------------------------------------
-keep class dev.cppide.** extends androidx.lifecycle.ViewModel { <init>(...); }

# ---------------------------------------------------------------------------
# Strip debug logging from release builds. Tiny space savings but more
# importantly keeps internal log strings out of the shipped APK.
# ---------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
