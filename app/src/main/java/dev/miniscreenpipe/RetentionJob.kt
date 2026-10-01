package dev.miniscreenpipe

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors

class RetentionJob:JobService() {
    private val worker=Executors.newSingleThreadExecutor()
    private val generation=java.util.concurrent.atomic.AtomicInteger()
    override fun onStartJob(params:JobParameters):Boolean {
        val token=generation.incrementAndGet()
        worker.execute { var failed=false;try { HistoryDb.get(this).prune(Prefs(this).retention) } catch(_:Exception) { failed=true } finally { if(generation.get()==token)jobFinished(params,failed) } }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { generation.incrementAndGet();return true }
    override fun onDestroy() { generation.incrementAndGet();worker.shutdown();super.onDestroy() }
    companion object {
        fun schedule(context:Context) {
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(37,ComponentName(context,RetentionJob::class.java)).setPeriodic(15*60*1000L).setPersisted(true).build())
        }
    }
}
