# ExecuTorch's native side calls back into these by name through fbjni.
-keep class org.pytorch.executorch.** { *; }
-keep class com.facebook.jni.** { *; }

# The MediaTek runtime (libexecutorch_pd_jni.so) streams tokens to this method, found by name.
-keep class org.experimentalmachines.execuserve.executorch.NeuroPilotTokens {
    boolean onToken(java.lang.String);
}

# fbjni (ExecuTorch) annotates with javax.annotation, which Android does not ship; annotations only.
-dontwarn javax.annotation.**
