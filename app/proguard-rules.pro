# finder+ — release ProGuard/R8 rules.
# Room, Hilt, and WorkManager ship their own consumer rules; add app-specific keeps here as the UI
# and native (whisper.cpp / ONNX) integrations land.
# JNI resolves methods by the mangled Java name (Java_ai_rightone_finderplus_speech_AsrNative_*),
# so every class declaring native methods must keep its exact name and those members.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keepclassmembers class ai.rightone.finderplus.speech.WhisperNative { *; }
