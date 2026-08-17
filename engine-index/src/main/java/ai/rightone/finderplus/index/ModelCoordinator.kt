package ai.rightone.finderplus.index

import ai.rightone.finderplus.model.RequiredModel
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

    suspend fun <T> withModel(model: RequiredModel, block: suspend () -> T): T {
        if (model == RequiredModel.NONE || model == RequiredModel.MLKIT) return block()
        return mutex.withLock {
            resident = model
            block()
            // Model stays resident for affinity; unloaded lazily under memory/thermal pressure.
        }
    }

    /** Called by memory/thermal guards to drop the resident heavy model at the next safe boundary. */
    fun onPressure() {
        resident = RequiredModel.NONE
        // Actual OrtSession/whisper ctx teardown is owned by the analyzers; they observe this hint.
    }
}
