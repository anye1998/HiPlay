# Names the native libraries look up by hand.
#
# libhicarauth and libnetiface resolve their Kotlin counterparts through JNI's name-based lookup
# (Java_<package>_<class>_<method>), which survives nothing: a renamed class or method fails at the
# first call, not at build time.
-keep class com.shilapi.xcertplay.hicarauth.HiChainNative { native <methods>; }
-keep class com.shilapi.xcertplay.hicarauth.NetInterfaceMac { native <methods>; }
