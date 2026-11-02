# R8 rules for the Pocket TTS engine.

# JNI: libpockettts_jni binds these natives by their Java_* symbol names.
-keepclasseswithmembernames,includedescriptorclasses class org.pockettts.android.engine.** {
    native <methods>;
}
-keep class org.pockettts.android.engine.NativePocketTts { *; }
-keep class org.pockettts.android.engine.NativeSpeechEffects { *; }

# jni_bridge.cpp calls AudioSink.onAudio([F)Z via GetMethodID on the sink's runtime class.
-keep interface org.pockettts.android.engine.NativePocketTts$AudioSink { *; }
-keep class * implements org.pockettts.android.engine.NativePocketTts$AudioSink {
    boolean onAudio(float[]);
}

# Keep readable stack traces in bug reports.
-keepattributes SourceFile,LineNumberTable
