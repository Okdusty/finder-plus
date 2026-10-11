package ai.dusty.finderplus.vision

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Output shapes the shipped PP-OCRv5 models return (the same under ONNX Runtime 1.20 and 1.31). */
class OcrTensorsTest {

    @Test fun detectorMapUnwrapsBatchAndChannel() {
        val out = arrayOf(arrayOf(Array(4) { y -> FloatArray(5) { x -> (y * 5 + x).toFloat() } })) // [1,1,4,5]
        val map = OcrTensors.probabilityMap(out)!!
        assertThat(map.size).isEqualTo(4)
        assertThat(map[3][4]).isEqualTo(19f)
    }

    @Test fun recognizerSequenceUnwrapsBatch() {
        val out = arrayOf(Array(40) { FloatArray(838) }) // [1,40,838]
        assertThat(OcrTensors.sequence(out)!!.size).isEqualTo(40)
    }

    @Test fun aRealBatchIsNotCollapsed() {
        val out = arrayOf(arrayOf(FloatArray(3)), arrayOf(FloatArray(3))) // [2,1,3]
        assertThat(OcrTensors.probabilityMap(out)).isNull()
    }
}
