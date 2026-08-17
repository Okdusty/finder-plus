package ai.dusty.finderplus.index

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.dusty.finderplus.model.Trigger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Backs the notification's **Stop** action. Requests the cooperative stop first (so the current
 * micro-batch commits its checkpoint), then cancels the work chain so no queued slice starts.
 */
class IndexControlReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun orchestrator(): IndexOrchestrator
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        when (intent.action) {
            ACTION_STOP -> {
                val pending = goAsync()
                val deps = EntryPointAccessors.fromApplication(appContext, Deps::class.java)
                CoroutineScope(Dispatchers.Default).launch {
                    try {
                        deps.orchestrator().requestStop()
                        IndexWorker.cancel(appContext)
                        appContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
                    } finally {
                        pending.finish()
                    }
                }
            }
            // Lets indexing be kicked off without taking over the screen (adb, automation, tests):
            //   adb shell am broadcast -a ai.dusty.finderplus.action.START_INDEX -p <pkg>
            ACTION_START -> IndexWorker.enqueue(appContext, Trigger.MANUAL)

            // Debug affordance: transcribe ONE A/V file now and log the result, so speech quality can
            // be judged without waiting for the TRANSCRIBE pass (priority 60) to be reached.
            //   adb shell am broadcast -a ...action.TEST_ASR -p <pkg> --es kind audio
            // Long-running: delegated to a foreground worker (a receiver would ANR).
            ACTION_TEST_ASR -> AsrProbeWorker.enqueue(
                appContext,
                kind = intent.getStringExtra("kind") ?: "audio",
                boost = intent.getBooleanExtra("boost", false),
                gpu = intent.getBooleanExtra("gpu", true),
                itemId = intent.getLongExtra("item", -1L),
            )

            // Reports the ggml compute devices actually registered, so GPU offload is verified rather
            // than assumed:
            //   adb shell am broadcast -a ...action.PROBE_BACKENDS -p <pkg>
            ACTION_PROBE_BACKENDS -> {
                ai.dusty.finderplus.speech.SpeechBackends.setVerboseLogging(true)
                val devices = ai.dusty.finderplus.speech.SpeechBackends.devices()
                if (devices.isEmpty()) {
                    android.util.Log.i(TAG, "backends: none (native library unavailable)")
                } else {
                    for (d in devices) {
                        android.util.Log.i(TAG, "backend: ${d.type} ${d.name} '${d.description}' ${d.totalMiB} MiB")
                    }
                    android.util.Log.i(TAG, "backends: gpu=${devices.any { it.isGpu }}")
                }
                ai.dusty.finderplus.speech.SpeechBackends.vulkanCapabilities().takeIf { it.isNotEmpty() }?.let {
                    android.util.Log.i(TAG, "vulkan: $it")
                }
            }

            //   adb shell am broadcast -a ...action.PROBE_CLIP -p <pkg>
            ACTION_PROBE_CLIP -> ClipProbeWorker.enqueue(appContext)
            ACTION_PROBE_CAPTION -> CaptionProbeWorker.enqueue(appContext)
            ACTION_JUDGE -> JudgeWorker.enqueue(appContext)

            //   adb shell am broadcast -a ...action.SEED_VOCAB -p <pkg>
            ACTION_SEED_VOCAB -> VocabularySeedWorker.enqueue(appContext)

            // Gallery vault: hide everything non-camera (dry-run with --ez dry true), or bring it all back.
            ACTION_VAULT_HIDE -> VaultWorker.enqueue(
                appContext,
                dry = intent.getBooleanExtra("dry", false),
                selfTest = intent.getBooleanExtra("selftest", false),
                only = intent.getStringExtra("only") ?: "",
                verify = intent.getBooleanExtra("verify", false),
                rotate = intent.getBooleanExtra("rotate", false),
                purgeMissing = intent.getBooleanExtra("purgeMissing", false),
            )
            ACTION_VAULT_RESTORE -> VaultWorker.enqueue(appContext, restore = true)

            //   adb shell am broadcast -a ...action.CLUSTER -p <pkg>
            ACTION_CLUSTER -> ClusterWorker.enqueue(appContext)

            // Times the encoder over real images without writing anything, so an acceleration change
            // can be A/B'd in a minute instead of by re-running a multi-hour index.
            //   adb shell am broadcast -a ...action.BENCH_CLIP -p <pkg> [--ez boost true] [--ei n 24]
            ACTION_BENCH_CLIP -> ClipBenchWorker.enqueue(
                appContext,
                n = intent.getIntExtra("n", 16),
                boost = intent.getBooleanExtra("boost", false),
                ep = intent.getStringExtra("ep"),
            )

            // Full rebuild. Keeps the user's own labels unless told otherwise, since those are the
            // only rows in the database that cannot be recomputed.
            //   adb shell am broadcast -a ...action.RESET_DB -p <pkg> [--ez keepLabels false]
            ACTION_RESET_DB -> ResetWorker.enqueue(
                appContext,
                keepLabels = intent.getBooleanExtra("keepLabels", true),
            )
        }
    }

    companion object {
        const val ACTION_STOP = "ai.dusty.finderplus.action.STOP_INDEX"
        const val ACTION_START = "ai.dusty.finderplus.action.START_INDEX"
        const val ACTION_TEST_ASR = "ai.dusty.finderplus.action.TEST_ASR"
        const val ACTION_VAULT_HIDE = "ai.dusty.finderplus.action.VAULT_HIDE"
        const val ACTION_VAULT_RESTORE = "ai.dusty.finderplus.action.VAULT_RESTORE"
        const val ACTION_PROBE_BACKENDS = "ai.dusty.finderplus.action.PROBE_BACKENDS"
        const val ACTION_PROBE_CLIP = "ai.dusty.finderplus.action.PROBE_CLIP"
        const val ACTION_PROBE_CAPTION = "ai.dusty.finderplus.action.PROBE_CAPTION"
        const val ACTION_JUDGE = "ai.dusty.finderplus.action.JUDGE"
        const val ACTION_SEED_VOCAB = "ai.dusty.finderplus.action.SEED_VOCAB"
        const val ACTION_CLUSTER = "ai.dusty.finderplus.action.CLUSTER"
        const val ACTION_RESET_DB = "ai.dusty.finderplus.action.RESET_DB"
        const val ACTION_BENCH_CLIP = "ai.dusty.finderplus.action.BENCH_CLIP"
        private const val TAG = "finderAsr"
        private const val NOTIF_ID = 4201
    }
}
