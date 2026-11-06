# Integration contract (stage1 reference; later user instructions take precedence)

Update: user accepted stage1 on Xiaomi/SAF and authorized stages2–7 continuously.
Each stage must pass build/test/lint before its one commit + tag stageN. On gate
failure stop and report. SAF only; no MediaStore, broad storage, INTERNET or
crossfade. One final APK + SHA256 + docs/FINAL-REPORT.md. No inter-stage approval.

Root package `com.walkmanstudio.nativeplayer`, minSdk26 compileSdk36 targetSdk35,
Java21 compile JVM17, Kotlin2.2.21 Koin4.1.1. Modules core JVM, library Android,
app Compose. No playback/tag/DSD yet; stage1 only. Root owns all Gradle + manifest.
Owners: core agent core/src and core/README; library agent library/src;
app agent app/src/main/java + res + app/src/test; docs agent docs review.

## Exact core signatures (plain Kotlin)
```kotlin
data class SourceRoot(val id: String, val uri: String, val displayName: String)
data class SourceDocument(val id: String, val uri: String, val parentId: String?, val name: String,
 val mimeType: String, val size: Long?, val lastModified: Long?, val isDirectory: Boolean)
interface SourceHandle : java.io.Closeable {
 val size: Long?; val seekable: Boolean
 fun read(buffer: ByteArray, offset: Int, length: Int): Int
 fun seek(position: Long)
}
interface FileSource {
 suspend fun root(root: SourceRoot): SourceDocument
 fun children(root: SourceRoot, directoryId: String): kotlinx.coroutines.flow.Flow<SourceDocument>
 suspend fun open(root: SourceRoot, document: SourceDocument): SourceHandle
}
data class IndexedFile(val rootId: String, val documentId: String, val uri: String,
 val parentId: String?, val name: String, val relativePath: String, val mimeType: String,
 val size: Long?, val lastModified: Long?, val naturalSortKey: String)
data class ScanProgress(val visited: Long=0, val audioFiles: Long=0, val changed: Long=0,
 val unchanged: Long=0, val directories: Long=0, val removed: Long=0)
data class ScanResult(val generation: Long, val progress: ScanProgress)
interface ScanStore {
 suspend fun begin(root: SourceRoot): Long
 suspend fun applyBatch(rootId: String, generation: Long, files: List<IndexedFile>, force: Boolean): Int
 suspend fun complete(rootId: String, generation: Long, progress: ScanProgress): Long
 suspend fun interrupted(rootId: String, generation: Long, reason: String)
}
class LibraryScanner(source: FileSource, store: ScanStore, batchSize: Int=256) {
 suspend fun scan(root: SourceRoot, force: Boolean=false,
  onProgress: suspend (ScanProgress)->Unit={}): ScanResult
}
fun naturalSortKey(value: String): String
```
Streaming children cursors, one open at a time, bounded256/512 file batches, no hard
file limit/no full100k list, iterative traversal cycle guard, normalize invalid
size<0/mtime<=0 to null. Unknown metadata always conservatively changed. Compare
size/mtime and name/path/etc. complete prune only after success, interruption never
prunes. Cancellation propagates; bookkeeping NonCancellable. Music never modified.

## Exact Android library signatures/public API
All types package `com.walkmanstudio.nativeplayer.library`.
```kotlin
data class LibraryRootEntity(val id:String,val uri:String,val displayName:String,
 val addedAt:Long,val lastScanAt:Long?=null,val permissionLost:Boolean=false)
data class TrackEntity(/* numeric id, rootId,documentId,uri,parentId,name,relativePath,
 mimeType,size,lastModified,naturalSortKey,generation; ALL public */)
abstract class LibraryDatabase : RoomDatabase {
 abstract fun libraryDao():LibraryDao
 companion object { fun getInstance(context:Context):LibraryDatabase }
}
interface LibraryDao {
 fun observeRoots():Flow<List<LibraryRootEntity>>
 fun observeTrackCount():Flow<Long>
 fun pagingTracks(rootId:String?,query:String):PagingSource<Int,TrackEntity>
 suspend fun getRoot(id:String):LibraryRootEntity?
}
data class ScanStatus(val rootId:String,val running:Boolean,val state:String,
 val visited:Long,val audioFiles:Long,val changed:Long,val message:String?=null)
class LibraryRepository(context:Context,database:LibraryDatabase) {
 val roots:Flow<List<LibraryRootEntity>>
 val trackCount:Flow<Long>
 val scans:Flow<List<ScanStatus>>
 fun tracks(rootId:String?=null,query:String=""):Flow<PagingData<TrackEntity>>
 suspend fun addTree(uri:Uri):LibraryRootEntity
 fun rescan(rootId:String,force:Boolean=false)
 fun cancelScan(rootId:String)
 suspend fun removeRoot(rootId:String)
}
```
Room2.8.4 KSP, Work2.10.3, Paging3.3.6, coroutines1.10.2.
Room tracks unique(rootId,documentId), indexes root/naturalSort; stable numeric ids.
DAO paging query root branch when possible no OR destroying index. No blobartwork.
Store generation guard plus root existence in transaction protects delete/rescan
race. Remove only index+ownpersistreadgrant, never delete source. SAF only content
tree permissions, read-only, validate tree/doc scope. FileHandle PFD owns resources,
FileChannel seek, reopen+bufferedskip fallback if nonseekable, no entire file RAM.
WorkManager default WorkerFactory CoroutineWorker obtains same DB singleton.
UniqueWork KEEP per root; safe cancellation + lostPermission flag only security
failure. Expose WorkInfo stopReason. Normal worker, no FGS permissions in stage1;
10min/runtime/quota constraints documented; safe restart reenumerates index, do
not claim persisted providercursor checkpoint. Batchupsert returns changedcount.
Scans progress reported batched. Tests real Room + scanner under Robolectric4.16.1.
Benchmark file-backed DB with100k fake documents, first/unchanged/1%changed; JSON
path systemproperty walkman.benchmark.output. State exactly hostsimulation notphone
SAF benchmark. Test cancel/failure no prune, unknownmetadata,deletedroot race.

## UI
Compose BOM2025.10.01 M3, activity1.11.0 lifecycle2.9.4 Koin4.1.1. Application
WalkmanStudioApplication config Koin DB/repository/VM. MainActivity. Stage1 only
library+folder tabs, add SAF localtree, rootfilter/searchPaged, progress+cancel+
retry+remove confirmation, permissionlost/errors, details originalfilename/URI.
No fake tags/play controls. Textfilename16sp, row72dp,touch48dp,contrast4.5,
TalkBack semantic labels, lightdark/system, rotation SavedStateHandle. Root handles
manifest icon@drawable/ic_launcher theme@style/Theme.WalkmanStudioNative. Safe
picker EXTRA_LOCAL_ONLY flags read+persist+prefix; Repository retains read grant.

## User constraints
100000 minimum test, no hard ceiling. Xiaomi14Pro Snapdragon8Gen3 Android16 HyperOS.
Internal storage noDAC now. No INTERNET,allowBackupfalse. Crossfade afterGĐ7 only.
GPL3orlater. Every PR Not verified + Cách tôi tự kiểm trên máy thật. No stage2 until
user approval. No claim deviceverified without physical device.
