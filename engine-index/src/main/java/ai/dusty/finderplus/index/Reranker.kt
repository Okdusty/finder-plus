package ai.dusty.finderplus.index

import android.content.Context
import android.graphics.Bitmap
import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.RequiredModel
import ai.dusty.finderplus.model.SearchResult
import ai.dusty.finderplus.settings.DeepSearchSettings
import ai.dusty.finderplus.settings.RerankWith
import ai.dusty.finderplus.settings.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Deep search: a vision-language model looks at each top result and scores how well it fits the
 * search, as the deep search settings say. The on-phone model runs under the model lock, so it waits
 * for an indexing step to finish rather than sharing memory with it.
 */
@Singleton
class Reranker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localJudge: LocalJudge,
    private val cloudJudge: CloudJudge,
    private val coordinator: ModelCoordinator,
) {
    /** Whether deep search loaded the on-phone model since it was last released. */
    @Volatile private var loaded = false

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun judge(): Judge =
        if (Settings[DeepSearchSettings.WITH] == RerankWith.ASSISTANT) cloudJudge else localJudge

    fun isReady(): Boolean = judge().isReady()

    /**
     * Asks about the first results of [results] for [query], calling [onReply] with each item's reply,
     * or null when it could not be judged, as soon as it is known.
     */
    suspend fun ask(query: String, results: List<SearchResult>, onReply: suspend (Long, String?) -> Unit) {
        val judge = judge()
        if (!judge.isReady()) return
        val prompt = Settings[DeepSearchSettings.PROMPT].replace(DeepSearchSettings.QUERY_SLOT, query)
        val maxTokens = Settings[DeepSearchSettings.MAX_TOKENS].toInt()
        val edge = Settings[DeepSearchSettings.IMAGE_EDGE].toInt()
        for (r in results.take(Settings[DeepSearchSettings.TOP].toInt())) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val image = withContext(Dispatchers.IO) { picture(r, edge) }
            if (image == null) { onReply(r.item.id, null); continue }
            val reply = withContext(Dispatchers.IO) {
                runCatching {
                    if (judge === localJudge) coordinator.withModel(RequiredModel.VLM) { loaded = true; judge.ask(image, prompt, maxTokens) }
                    else judge.ask(image, prompt, maxTokens)
                }.getOrNull()
            }
            image.recycle()
            onReply(r.item.id, reply)
        }
    }

    /**
     * Frees the on-phone model if deep search loaded it. Under the model lock, because the review
     * assistant shares the model and may be using it.
     */
    fun release() {
        if (!loaded) return
        loaded = false
        scope.launch { coordinator.withModel(RequiredModel.VLM) { localJudge.release() } }
    }

    /** What the model is shown: a photo, or a video's frame at the match. Recordings have no picture. */
    private fun picture(r: SearchResult, edge: Int): Bitmap? = runCatching {
        when (r.item.kind) {
            MediaKind.IMAGE -> ai.dusty.finderplus.media.Bitmaps.decodeDownscaled(context, r.item.uri, edge)
            MediaKind.VIDEO -> r.hits.firstNotNullOfOrNull { it.startMs }?.let { MediaThumbs.frameAt(context, r.item.uri, it, edge) }
                ?: MediaThumbs.load(context, r.item.uri, edge)
            MediaKind.AUDIO -> null
        }
    }.getOrNull()
}
