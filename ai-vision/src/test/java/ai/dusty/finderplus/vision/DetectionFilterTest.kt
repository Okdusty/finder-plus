package ai.dusty.finderplus.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Both rules ignore what a class is called, so the labels here are placeholders. */
class DetectionFilterTest {

    private fun box(label: String, conf: Float, l: Int, t: Int, r: Int, b: Int) = DetectedRegion(label, conf, l, t, r, b)

    @Test fun oneRegionKeepsOnlyItsBestName() {
        val kept = DetectionFilter.classAgnosticNms(listOf(
            box("a", 0.57f, 10, 10, 400, 300),
            box("b", 0.66f, 12, 8, 402, 302),
            box("c", 0.47f, 9, 11, 398, 299),
        ))
        assertThat(kept.map { it.label }).containsExactly("b")
    }

    @Test fun nestedBoxesOfDifferentClassesBothSurvive() {
        // A small box inside a large one overlaps it by area, not by IoU.
        val kept = DetectionFilter.classAgnosticNms(listOf(
            box("a", 0.9f, 100, 50, 400, 600),
            box("b", 0.7f, 300, 300, 360, 370),
        ))
        assertThat(kept.map { it.label }).containsExactly("a", "b")
    }

    @Test fun twoInstancesOfOneClassStaySeparate() {
        val kept = DetectionFilter.classAgnosticNms(listOf(
            box("a", 0.9f, 0, 0, 100, 300), box("a", 0.8f, 200, 0, 300, 300),
        ))
        assertThat(kept).hasSize(2)
    }

    @Test fun onlyObjectsMatchingAGroundedPhraseAreKept() {
        val subject = box("a", 0.8f, 100, 100, 500, 500)
        val background = box("b", 0.9f, 0, 600, 1000, 1000)
        val part = box("c", 0.7f, 200, 200, 260, 260)          // inside the subject, far smaller
        val grounded = listOf(box("phrase", 1f, 110, 90, 510, 520))
        assertThat(DetectionFilter.groundedOnly(listOf(subject, background, part), grounded).map { it.label })
            .containsExactly("a")
    }

    @Test fun withNothingGroundedEveryObjectIsKept() {
        val objects = listOf(box("a", 0.8f, 0, 0, 10, 10), box("b", 0.8f, 20, 20, 30, 30))
        assertThat(DetectionFilter.groundedOnly(objects, emptyList())).isEqualTo(objects)
    }

    @Test fun wholeFrameIsMeasuredAgainstTheImage() {
        assertThat(DetectionFilter.isWholeFrame(box("a", 0.8f, 0, 0, 1000, 740), 1000, 750)).isTrue()    // 98.7 %
        assertThat(DetectionFilter.isWholeFrame(box("a", 0.9f, 120, 40, 760, 590), 1000, 750)).isFalse() // 46.9 %
    }
}
