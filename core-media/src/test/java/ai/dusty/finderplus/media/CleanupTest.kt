package ai.dusty.finderplus.media

import ai.dusty.finderplus.model.MediaKind
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import java.util.Random
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Clean-up proposes deletions, so what it calls a duplicate must be one, and the copy it suggests
 * keeping must be the one a person would keep.
 */
class CleanupTest {

    private fun file(id: Long, size: Long, rel: String, born: Long, name: String = "IMG_$id.jpg", w: Int = 0, h: Int = 0) =
        MediaFile(id, "content://media/$id", MediaKind.IMAGE, name, size, rel, born, born, born, w, h)

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Same shape as the app's fingerprints: head+tail for PARTIAL, the whole file for FULL. */
    private fun fingerprints(content: Map<Long, ByteArray>): suspend (MediaFile, DuplicateFinder.Stage) -> String? = { f, stage ->
        content[f.id]?.let { b ->
            when (stage) {
                DuplicateFinder.Stage.PARTIAL -> sha(b.copyOfRange(0, minOf(4, b.size)) + b.copyOfRange(maxOf(0, b.size - 4), b.size))
                DuplicateFinder.Stage.FULL -> sha(b)
            }
        }
    }

    @Test fun onlyByteIdenticalFilesAreDuplicates(): Unit = runBlocking<Unit> {
        val a = ByteArray(1000) { it.toByte() }
        val sameEnds = a.copyOf().also { it[500] = 99 } // same size, same head and tail, different middle
        val files = listOf(
            file(1, 1000, "DCIM/Camera/", 100), file(2, 1000, "Download/", 200),
            file(3, 1000, "Pictures/", 300), file(4, 2000, "Pictures/", 400),
        )
        val content = mapOf(1L to a, 2L to a.copyOf(), 3L to sameEnds, 4L to ByteArray(2000))
        val groups = DuplicateFinder.find(files, fingerprints(content))
        assertThat(groups).hasSize(1)
        assertThat(groups[0].files.map { it.id }).containsExactly(1L, 2L)
    }

    @Test fun unreadableFilesAreNeverProposed(): Unit = runBlocking<Unit> {
        val files = listOf(file(1, 10, "A/", 1), file(2, 10, "B/", 2))
        val groups = DuplicateFinder.find(files, fingerprint = { _, _ -> null })
        assertThat(groups).isEmpty()
    }

    @Test fun theCameraOriginalIsKeptOverAnyCopy() {
        val (keep, reason) = DuplicateFinder.chooseOriginal(listOf(
            file(1, 5, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/", 10),
            file(2, 5, "DCIM/Camera/", 50),
            file(3, 5, "Download/", 5),
        ))
        assertThat(keep.id).isEqualTo(2L)
        assertThat(reason).contains("camera")
    }

    @Test fun withoutACameraCopyANonDownloadCopyWins() {
        val (keep, _) = DuplicateFinder.chooseOriginal(listOf(
            file(1, 5, "Download/", 1), file(2, 5, "Pictures/Edited/", 9),
        ))
        assertThat(keep.id).isEqualTo(2L)
    }

    @Test fun otherwiseTheOldestWins() {
        val (keep, _) = DuplicateFinder.chooseOriginal(listOf(
            file(1, 5, "Pictures/", 30), file(2, 5, "Pictures/", 10, name = "IMG (1).jpg"), file(3, 5, "Pictures/", 20),
        ))
        assertThat(keep.id).isEqualTo(2L)
    }

    @Test fun groupsAreSortedByReclaimableSpace(): Unit = runBlocking<Unit> {
        val small = ByteArray(10) { 1 }; val big = ByteArray(100) { 2 }
        val files = listOf(file(1, 10, "A/", 1), file(2, 10, "B/", 2), file(3, 100, "A/", 1), file(4, 100, "B/", 2), file(5, 100, "C/", 3))
        val content = mapOf(1L to small, 2L to small, 3L to big, 4L to big, 5L to big)
        val groups = DuplicateFinder.find(files, fingerprints(content))
        assertThat(groups.map { it.reclaimable }).containsExactly(200L, 10L).inOrder()
    }

    private fun unit(v: FloatArray): FloatArray { val n = Math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(v.size) { v[it] / n } }

    @Test fun lookAlikesFindNearCopiesAndNothingElse() {
        val rnd = Random(1)
        val dim = 256
        val base = List(40) { unit(FloatArray(dim) { rnd.nextGaussian().toFloat() }) }
        // A slightly perturbed copy of items 0 and 1 (cos ≈ 0.99), everything else independent (cos ≈ 0).
        fun nudged(v: FloatArray) = unit(FloatArray(dim) { v[it] + 0.01f * rnd.nextGaussian().toFloat() })
        val vectors = base.mapIndexed { i, v -> i.toLong() to v } + listOf(100L to nudged(base[0]), 101L to nudged(base[1]))
        val groups = LookAlikeFinder.groups(vectors).map { it.sorted() }
        assertThat(groups).containsExactly(listOf(0L, 100L), listOf(1L, 101L))
    }

    @Test fun theSharpestLookAlikeIsKept() {
        val (keep, reason) = LookAlikeFinder.chooseBest(listOf(
            file(1, 300_000, "Android/media/com.whatsapp/", 5, w = 1600, h = 1200),
            file(2, 4_000_000, "DCIM/Camera/", 1, w = 4000, h = 3000),
        ))
        assertThat(keep.id).isEqualTo(2L)
        assertThat(reason).contains("resolution")
    }
}
