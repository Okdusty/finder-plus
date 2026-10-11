package ai.dusty.finderplus.index

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThermalZonesTest {
    @Test fun millidegreesAndDegreesBothRead() {
        assertThat(ThermalZones.celsius(43_500)).isEqualTo(43.5f)
        assertThat(ThermalZones.celsius(43)).isEqualTo(43f)
    }

    @Test fun impossibleReadingsAreDropped() {
        assertThat(ThermalZones.celsius(0)).isNull()
        assertThat(ThermalZones.celsius(-273_000)).isNull()
        assertThat(ThermalZones.celsius(null)).isNull()
    }
}
