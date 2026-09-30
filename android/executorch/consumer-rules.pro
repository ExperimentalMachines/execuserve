# ExecuTorch's native side calls back into these by name through fbjni.
-keep class org.pytorch.executorch.** { *; }
-keep class com.facebook.jni.** { *; }

# fbjni (ExecuTorch) annotates with javax.annotation, which Android does not ship; annotations only.
-dontwarn javax.annotation.**
