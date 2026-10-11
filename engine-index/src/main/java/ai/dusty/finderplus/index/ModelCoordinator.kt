package ai.dusty.finderplus.index

import ai.dusty.finderplus.model.RequiredModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enforces "one heavy model resident at a time", **process-wide**.
 *
 * It must be a singleton, not per-orchestrator. It was previously constructed inside
 * `IndexEngine.create`, so anything outside the indexing pipeline — the ASR probe, the encoder
 * benchmark — took no lock at all. Two ~1 GB ASR contexts then ran concurrently on one GPU and decode
 * collapsed from ~31 tok/s to 0.3-7.5 tok/s, which looked like the model being slow rather than two
 * jobs fighting.
 *
 * Original contract: NONE/MLKIT are lightweight and run without the lock;
 * CLIP/WHISPER/TEXT_EMB are serialized so two heavy models never sit in RAM together. The current
 * [residentCode] biases the ledger's claim query (affinity) so all units for the loaded model drain
 * before a swap. See docs/design/01-DB-ENGINE.md §9, docs/design/03-AI-PIPELINE.md §5.
 */
@javax.inject.Singleton
class ModelCoordinator @javax.inject.Inject constructor() {

    private val mutex = Mutex()

    @Volatile
    private var resident: RequiredModel = RequiredModel.NONE

    fun residentCode(): Int = resident.ordinal

    private val unloaders = HashMap<RequiredModel, MutableList<() -> Unit>>()

    @Volatile
    private var releaseRequested = false

    /**
     * How to free [model]'s memory. Called only while no work holds the model lock, so only models
     * used solely under [withModel] may register; those search also uses must not.
     */
    fun onUnload(model: RequiredModel, unload: () -> Unit) {
        synchronized(unloaders) { unloaders.getOrPut(model) { ArrayList() } += unload }
    }

    private fun unload(model: RequiredModel) {
        val hooks = synchronized(unloaders) { unloaders[model]?.toList() }.orEmpty()
        if (hooks.isNotEmpty()) android.util.Log.i("finderModels", "unloading $model")
        hooks.forEach { runCatching(it) }
    }

    suspend fun <T> withModel(model: RequiredModel, block: suspend () -> T): T {
        if (model == RequiredModel.NONE || model == RequiredModel.MLKIT) return block()
        return mutex.withLock {
            if (model != resident && resident != RequiredModel.NONE &&
                ai.dusty.finderplus.settings.Settings[ai.dusty.finderplus.settings.MemorySettings.ONE_MODEL]
            ) unload(resident)
            resident = model
            try {
                block()
            } finally {
                // Otherwise the model stays resident for affinity.
                if (releaseRequested) {
                    releaseRequested = false
                    unload(resident)
                    resident = RequiredModel.NONE
                }
            }
        }
    }

    /**
     * Memory is short: unload the resident model now if nothing holds it, else as soon as the work
     * that does finishes.
     */
    fun onPressure() {
        if (mutex.tryLock()) {
            try {
                unload(resident)
                resident = RequiredModel.NONE
            } finally {
                mutex.unlock()
            }
        } else {
            releaseRequested = true
        }
    }
}
