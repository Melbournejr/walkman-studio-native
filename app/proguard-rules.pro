# Room/WorkManager supply their own consumer rules.

# Persisted WorkManager requests must retain their worker class across OTA updates.
-keep class com.walkmanstudio.nativeplayer.update.ApplicationUpdateWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }
