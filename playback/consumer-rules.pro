# JNI exported names must survive release shrinking/obfuscation.
-keepclasseswithmembernames,includedescriptorclasses class com.walkmanstudio.nativeplayer.playback.dsd.NativeDsdFir { native <methods>; }
