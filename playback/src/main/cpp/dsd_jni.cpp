// SPDX-License-Identifier: GPL-3.0-or-later
#include "dsd_fir.h"
#include <jni.h>
#include <stdexcept>
namespace {
void reject(JNIEnv* env,const char* message){env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"),message);}
}
extern "C" JNIEXPORT jlong JNICALL Java_com_walkmanstudio_nativeplayer_playback_dsd_NativeDsdFir_create(JNIEnv* env,jobject,jint ratio,jboolean lsb){
 try{return reinterpret_cast<jlong>(new walkman::DsdFir(ratio,lsb));}catch(const std::exception& error){reject(env,error.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_com_walkmanstudio_nativeplayer_playback_dsd_NativeDsdFir_decodeNative(JNIEnv* env,jobject,jlong handle,jobject input,jint stride,jint channels,jint frames,jobject output){
 auto* fir=reinterpret_cast<walkman::DsdFir*>(handle);
 auto* bytes=static_cast<uint8_t*>(env->GetDirectBufferAddress(input));auto* pcm=static_cast<float*>(env->GetDirectBufferAddress(output));
 const jlong capacity=env->GetDirectBufferCapacity(input),out_capacity=env->GetDirectBufferCapacity(output);
 if(!fir || !bytes || !pcm || stride<1 || channels<1 || channels>2 || frames<1 || frames>8192 || capacity<static_cast<jlong>(stride)*channels || out_capacity<static_cast<jlong>(frames)*channels*4){reject(env,"Invalid direct PCM buffer");return;}
 try{fir->decode(bytes,stride,channels,frames,pcm);}catch(const std::exception& error){reject(env,error.what());}
}
extern "C" JNIEXPORT void JNICALL Java_com_walkmanstudio_nativeplayer_playback_dsd_NativeDsdFir_destroy(JNIEnv*,jobject,jlong handle){delete reinterpret_cast<walkman::DsdFir*>(handle);}
