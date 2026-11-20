// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.playback.dsd

import java.io.Closeable
import java.nio.ByteBuffer

/** Native library is identical for Android and the host JNI tests; no test-specific production branch. */
class NativeDsdFir(ratio:Int,lsbFirst:Boolean):Closeable {
    private var handle=create(ratio,lsbFirst)
    @Synchronized fun decode(input:ByteBuffer,stride:Int,channels:Int,frames:Int,output:ByteBuffer) {
        check(handle!=0L){"DSD decoder closed"};require(input.isDirect && output.isDirect)
        decodeNative(handle,input,stride,channels,frames,output)
    }
    @Synchronized override fun close(){if(handle!=0L){destroy(handle);handle=0}}
    private external fun create(ratio:Int,lsb:Boolean):Long
    private external fun decodeNative(handle:Long,input:ByteBuffer,stride:Int,channels:Int,frames:Int,output:ByteBuffer)
    private external fun destroy(handle:Long)
    companion object {init{System.loadLibrary("walkman_dsd")}}
}
