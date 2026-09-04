package online.lisikha.probe

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class ProbeWorker(appContext:Context,params:WorkerParameters):CoroutineWorker(appContext,params){
    override suspend fun doWork():Result{
        val cfg=ReportingConfigStore(applicationContext)
        val s=cfg.load()
        if(!s.enabled)return Result.success()
        val token=cfg.botToken()
        if(token.isBlank() || s.chatId.isBlank())return Result.retry()
        return runCatching{
            val reports=NetworkProbeEngine(applicationContext).runMatrix(s.host)
            val store=ProbeReportStore(applicationContext)
            store.append(reports)
            val excel=if(s.attachExcel)store.excelFile() else null
            TelegramReporter().send(token,s.chatId,reports,excel)
            Result.success()
        }.getOrElse{Result.retry()}
    }

    companion object{
        private const val NAME="lisikha-periodic-probe"
        fun schedule(context:Context,minutes:Long){
            val m=minutes.coerceAtLeast(15)
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val req=PeriodicWorkRequestBuilder<ProbeWorker>(m,TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME,ExistingPeriodicWorkPolicy.UPDATE,req)
        }
        fun cancel(context:Context)=WorkManager.getInstance(context).cancelUniqueWork(NAME)
    }
}
