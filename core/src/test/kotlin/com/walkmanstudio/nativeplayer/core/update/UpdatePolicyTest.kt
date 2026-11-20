// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.core.update

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val data=byteArrayOf(1,2,3)
    private val url="https://github.com/${UpdateTrust.REPOSITORY}/releases/download/stage11/walkman-native-test.apk"
    private fun artifact(bytes:ByteArray=data)=UpdateArtifact(107,"1.3.1",26,bytes.size.toLong(),hash(bytes),url)
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun valid()=ApkIdentity(UpdateTrust.APP_ID,107,26,setOf(UpdateTrust.CERTIFICATE))
    private fun verify(identity:ApkIdentity=valid(),installed:Long=106,certs:Set<String> = setOf(UpdateTrust.CERTIFICATE),sdk:Int=35)=UpdateValidation.identity(identity,artifact(),installed,certs,sdk)

    @Test fun acceptsOnlyCanonicalRepositoryReleaseUrl(){assertTrue(UpdateTrust.releaseUrl(url))}
    @Test fun rejectsOtherRepository(){assertFalse(UpdateTrust.releaseUrl(url.replace("walkman-studio-native/","walkman-player/")))}
    @Test fun rejectsCleartext(){assertFalse(UpdateTrust.releaseUrl(url.replace("https:","http:")))}
    @Test fun rejectsCredentialsInUrls(){assertFalse(UpdateTrust.releaseUrl(url.replace("github.com","user@github.com")))}
    @Test fun rejectsInitialQueryAndFragment(){assertFalse(UpdateTrust.releaseUrl(url+"?token=x"));assertFalse(UpdateTrust.releaseUrl(url+"#apk"))}
    @Test fun rejectsEncodedPathEscapes(){assertFalse(UpdateTrust.releaseUrl(url.replace("stage11","stage11%2f..")))}
    @Test fun allowsGithubCdnOnlyAsRedirect(){val cdn="https://release-assets.githubusercontent.com/a?signature=x";assertFalse(UpdateTrust.requestUrl(cdn));assertTrue(UpdateTrust.requestUrl(cdn,true))}
    @Test fun rejectsArbitraryRedirectHost(){assertFalse(UpdateTrust.requestUrl("https://example.com/update.apk",true));assertFalse(UpdateTrust.requestUrl("https://release-assets.githubusercontent.com.attacker.net/a",true))}
    @Test fun rejectsOversizedAndEmptyApks(){assertThrows(IllegalArgumentException::class.java){artifact().copy(bytes=UpdateTrust.MAX_APK_BYTES+1)};assertThrows(IllegalArgumentException::class.java){artifact().copy(bytes=0)}}
    @Test fun rejectsMalformedDigest(){assertThrows(IllegalArgumentException::class.java){artifact().copy(sha256="abcd")}}
    @Test fun acceptsExactNewerIdentityAndSigner(){verify()}
    @Test fun rejectsDifferentApplication(){assertThrows(IllegalArgumentException::class.java){verify(valid().copy(packageName="com.other.player"))}}
    @Test fun rejectsVersionDifferentFromManifest(){assertThrows(IllegalArgumentException::class.java){verify(valid().copy(versionCode=108))}}
    @Test fun rejectsRollbackAndReinstall(){assertThrows(IllegalArgumentException::class.java){verify(installed=108)};assertThrows(IllegalArgumentException::class.java){verify(installed=107)}}
    @Test fun rejectsDifferentDownloadedSigner(){assertThrows(IllegalArgumentException::class.java){verify(valid().copy(certificates=setOf("0".repeat(64))))}}
    @Test fun rejectsUnofficialInstalledSigner(){assertThrows(IllegalArgumentException::class.java){verify(certs=setOf("0".repeat(64)))}}
    @Test fun rejectsIncompatibleSdk(){assertThrows(IllegalArgumentException::class.java){verify(valid().copy(minSdk=36))}}
    @Test fun rejectsAdditionalSigners(){assertThrows(IllegalArgumentException::class.java){verify(valid().copy(certificates=setOf(UpdateTrust.CERTIFICATE,"1".repeat(64))))}}
    @Test fun copiesExactBytesAndVerifiesDigest()=runBlocking {
        val output=ByteArrayOutputStream();assertEquals(3L,UpdateTransfer.copy(ByteArrayInputStream(data),output,artifact()));assertArrayEquals(data,output.toByteArray())
    }
    @Test fun rejectsTruncatedDownload(){assertThrows(IllegalArgumentException::class.java){runBlocking{UpdateTransfer.copy(ByteArrayInputStream(byteArrayOf(1)),ByteArrayOutputStream(),artifact())}}}
    @Test fun rejectsOversizedResponse(){assertThrows(IllegalArgumentException::class.java){runBlocking{UpdateTransfer.copy(ByteArrayInputStream(byteArrayOf(1,2,3,4)),ByteArrayOutputStream(),artifact())}}}
    @Test fun rejectsBitFlipsWithSameLength(){assertThrows(IllegalArgumentException::class.java){runBlocking{UpdateTransfer.copy(ByteArrayInputStream(byteArrayOf(1,2,4)),ByteArrayOutputStream(),artifact())}}}
    @Test fun cancellationStopsStreaming(){
        val bytes=ByteArray(200_000){it.toByte()};val output=ByteArrayOutputStream()
        assertThrows(CancellationException::class.java){runBlocking{UpdateTransfer.copy(ByteArrayInputStream(bytes),output,artifact(bytes)){currentCoroutineContext().cancel()}}}
        assertTrue(output.size()<bytes.size)
    }
    @Test fun toleratesOneZeroLengthRead()=runBlocking {
        val source=object:ByteArrayInputStream(data){var first=true;override fun read(b:ByteArray,off:Int,len:Int):Int{if(first){first=false;return 0};return super.read(b,off,len)}}
        assertEquals(3L,UpdateTransfer.copy(source,ByteArrayOutputStream(),artifact()))
    }
    @Test fun downloadUsesBoundedReadWindowsAndReportsFullProgress()=runBlocking {
        val bytes=ByteArray(2*1024*1024){(it*17).toByte()};var largest=0;var progress=0L
        val source=object:ByteArrayInputStream(bytes){override fun read(b:ByteArray,off:Int,len:Int):Int{largest=maxOf(largest,len);return super.read(b,off,len)}}
        UpdateTransfer.copy(source,ByteArrayOutputStream(),artifact(bytes)){progress=it}
        assertTrue(largest<=64*1024);assertEquals(bytes.size.toLong(),progress)
    }
}
