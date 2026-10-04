package top.pmh13.mctier.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** An in-place update can introduce WorkManager before the user next opens the UI. */
class QuarkUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val completion = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withTimeout(8_000) { QuarkSupport.get(context.applicationContext).restoreAfterUpdate() }
            } catch (_: Exception) {
                // Preserve credentials on I/O/Keystore/scheduler failure; normal launch retries.
                Log.w("QuarkSupport", "升级后后台任务恢复暂未完成，下次启动会重试")
            } finally {
                completion.finish()
            }
        }
    }
}
