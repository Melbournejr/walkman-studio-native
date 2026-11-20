// SPDX-License-Identifier: GPL-3.0-or-later
package com.walkmanstudio.nativeplayer.update

import android.app.Application
import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.walkmanstudio.nativeplayer.core.update.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class UpdateFlowTest {
    private lateinit var context:Context
    private lateinit var store:UpdateStore
    private lateinit var folder:File
    private val data=byteArrayOf(1,2,3)
    private val url="https://github.com/${UpdateTrust.REPOSITORY}/releases/download/stage11/walkman-native-test.apk"
    private val manifestUrl="https://github.com/${UpdateTrust.REPOSITORY}/releases/download/stage11/update.json"
    private fun hash(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun candidate()=UpdateArtifact(107,"1.3.1",26,data.size.toLong(),hash(data),url,"Cải thiện cập nhật.")
    private fun identity(version:Long=107,certificate:String=UpdateTrust.CERTIFICATE)=ApkIdentity(UpdateTrust.APP_ID,version,26,setOf(certificate))
    @Before fun prepare(){
        context=ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("application-updates",Context.MODE_PRIVATE).edit().clear().commit()
        store=UpdateStore(context);folder=File(context.filesDir,"updates");folder.deleteRecursively();folder.mkdirs()
    }
    private class Transport(private val payloads:Map<String,ByteArray>):UpdateTransport {
        val calls=mutableListOf<String>();var closed=0
        override fun open(url:String):UpdateBody {calls+=url;val bytes=payloads[url]?:throw IOException("offline");return UpdateBody(ByteArrayInputStream(bytes),bytes.size.toLong()){closed++}}
    }
    private fun release(draft:Boolean=false,prerelease:Boolean=false,size:Long=3,digest:String="sha256:"+hash(data),manifest:String=manifestUrl)=JSONObject().apply {
        put("draft",draft);put("prerelease",prerelease)
        put("assets",JSONArray().put(JSONObject().put("name","update.json").put("browser_download_url",manifest))
            .put(JSONObject().put("name","walkman-native-test.apk").put("browser_download_url",url).put("size",size).put("digest",digest)))
    }
    private fun catalog(release:JSONObject=release(),manifest:String=GithubUpdateCatalog.encode(candidate())):Pair<GithubUpdateCatalog,Transport> {
        val t=Transport(mapOf(UpdateTrust.LATEST to release.toString().toByteArray(),manifestUrl to manifest.toByteArray()))
        return GithubUpdateCatalog(t) to t
    }
    private fun files(cert:String=UpdateTrust.CERTIFICATE)=VerifiedUpdateFiles(folder,ArchiveIdentityReader{identity(certificate=cert)},{identity(106)},35)
    private fun runner(t:Transport=Transport(mapOf(url to data)),filePolicy:VerifiedUpdateFiles=files(),artifact:UpdateArtifact?=candidate(),notify:()->Unit={}):UpdateRunner =
        UpdateRunner(store,folder,{artifact},t,filePolicy,106,35,clock={5_000_000L},notifyReady=notify)

    @Test fun defaultsUseAutomaticUnmeteredUpdates(){assertTrue(store.read().auto);assertFalse(store.read().mobile)}
    @Test fun settingsSurviveRepositoryRecreation(){store.preferences(auto=false,mobile=true);val s=UpdateStore(context).read();assertFalse(s.auto);assertTrue(s.mobile);store.preferences(auto=true);val changed=UpdateStore(context).read();assertTrue(changed.auto);assertTrue("Updating auto must preserve the mobile-data preference",changed.mobile)}
    @Test fun verifiedReadyStateSurvivesRestart(){store.ready(candidate());val s=UpdateStore(context).read();assertTrue(s.ready);assertEquals(candidate(),s.candidate);assertEquals(3L,s.downloaded)}
    @Test fun progressCannotUndoCancellationOrOverwriteNewerCandidate(){store.downloading(candidate());store.cancel();store.progress(107,3);assertEquals(UpdateStatus.CANCELED,store.read().status);assertEquals(0L,store.read().downloaded);store.downloading(candidate().copy(versionCode=108));store.progress(107,3);assertEquals(0L,store.read().downloaded)}
    @Test fun clearsOnlyInstalledUpdateMetadata(){store.ready(candidate());store.clearInstalled(106);assertTrue(store.read().ready);store.clearInstalled(107);assertFalse(store.read().ready);assertNull(store.read().candidate)}
    @Test fun damagedPersistedMetadataNeverBecomesInstallable(){context.getSharedPreferences("application-updates",0).edit().putString("candidate","broken").putLong("readyVersion",107).commit();assertFalse(store.read().ready)}
    @Test fun manifestRoundTripsWithoutLosingIdentity(){assertEquals(candidate(),GithubUpdateCatalog.decode(JSONObject(GithubUpdateCatalog.encode(candidate()))))}
    @Test fun catalogIgnoresDraftAndPrerelease(){assertNull(catalog(release(draft=true)).first.latest());assertNull(catalog(release(prerelease=true)).first.latest())}
    @Test fun catalogRejectsExternalManifestHost(){assertThrows(IllegalArgumentException::class.java){catalog(release(manifest="https://attacker.net/update.json")).first.latest()}}
    @Test fun catalogRejectsManifestSignerChange(){val j=JSONObject(GithubUpdateCatalog.encode(candidate())).put("certificateSha256","0".repeat(64));assertThrows(IllegalArgumentException::class.java){catalog(manifest=j.toString()).first.latest()}}
    @Test fun catalogRejectsSizeDifferentFromReleaseAsset(){assertThrows(IllegalArgumentException::class.java){catalog(release(size=4)).first.latest()}}
    @Test fun catalogRejectsDigestDifferentFromReleaseAsset(){assertThrows(IllegalArgumentException::class.java){catalog(release(digest="sha256:"+"0".repeat(64))).first.latest()}}
    @Test fun catalogRejectsOversizedManifestAndClosesBodies(){val pair=catalog(manifest=" ".repeat(70*1024));assertThrows(IllegalArgumentException::class.java){pair.first.latest()};assertEquals(2,pair.second.closed)}
    @Test fun validCatalogMatchesManifestAndReleasesAllStreams(){val pair=catalog();assertEquals(candidate(),pair.first.latest());assertEquals(2,pair.second.closed)}
    @Test fun validDownloadAtomicallyBecomesReadyAndNotifiesOnce()=runBlocking {
        var notifications=0;val t=Transport(mapOf(url to data));val run=runner(t,notify={notifications++})
        run.run(true);assertTrue(store.read().ready);assertArrayEquals(data,File(folder,candidate().fileName).readBytes());assertFalse(File(folder,candidate().fileName+".part").exists());assertEquals(1,notifications)
        run.run(true);assertEquals(1,t.calls.size);assertEquals(1,notifications)
    }
    @Test fun corruptTransferIsDeletedAndNeverMarkedReady(){val t=Transport(mapOf(url to byteArrayOf(1,2,4)));assertThrows(IllegalArgumentException::class.java){runBlocking{runner(t).run(true)}};assertFalse(store.read().ready);assertTrue(folder.listFiles().orEmpty().isEmpty())}
    @Test fun wrongSignerIsDeletedBeforePublishingReady(){assertThrows(IllegalArgumentException::class.java){runBlocking{runner(filePolicy=files("0".repeat(64))).run(true)}};assertFalse(store.read().ready);assertTrue(folder.listFiles().orEmpty().isEmpty())}
    @Test fun rollbackNeverDownloads()=runBlocking {val t=Transport(emptyMap());runner(t,artifact=candidate().copy(versionCode=106)).run(true);assertTrue(t.calls.isEmpty());assertFalse(store.read().ready)}
    @Test fun canceledVersionIsNotAutomaticallyDownloadedAgain()=runBlocking {store.downloading(candidate());store.cancel();val t=Transport(emptyMap());runner(t).run(false);assertTrue(t.calls.isEmpty());assertEquals(UpdateStatus.CANCELED,store.read().status)}
    @Test fun explicitManualRetryCanOverrideCanceledVersion()=runBlocking {store.downloading(candidate());store.cancel();runner().run(true);assertTrue(store.read().ready)}
    @Test fun canceledWorkerDeletesPartialFile(){assertThrows(CancellationException::class.java){runBlocking{runner().run(true){_,_->currentCoroutineContext().cancel()}}};assertFalse(store.read().ready);assertTrue(folder.listFiles().orEmpty().isEmpty())}
    @Test fun tamperingWithReadyFileIsRejectedBeforeInstall() {runBlocking {runner().run(true);val file=File(folder,candidate().fileName);file.writeBytes(byteArrayOf(3,2,1));assertThrows(IllegalArgumentException::class.java){files().verify(file,candidate())}}}
    @Test fun verifierCannotInstallAFileOutsidePrivateUpdateFolder(){val elsewhere=File(context.filesDir,"elsewhere.apk");elsewhere.writeBytes(data);assertThrows(IllegalArgumentException::class.java){files().verify(elsewhere,candidate())}}
    @Test fun fileProviderExposesOnlyPrivateUpdates(){
        val apk=File(folder,"test.apk").apply{writeBytes(data)}
        assertEquals("content",FileProvider.getUriForFile(context,context.packageName+".updates",apk).scheme)
        val music=File(context.filesDir,"song.flac").apply{writeBytes(data)}
        assertThrows(IllegalArgumentException::class.java){FileProvider.getUriForFile(context,context.packageName+".updates",music)}
    }
}
