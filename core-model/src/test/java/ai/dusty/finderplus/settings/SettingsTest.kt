package ai.dusty.finderplus.settings

import ai.dusty.finderplus.model.MediaKind
import ai.dusty.finderplus.model.Pass
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class SettingsTest {

    private lateinit var store: MemoryStore

    @Before fun install() { store = MemoryStore(); Settings.store = store }
    @After fun restore() { Settings.store = DefaultsStore }

    @Test fun keysAreUnique() {
        val keys = SettingsCatalog.all.map { it.key }
        assertThat(keys).containsNoDuplicates()
    }

    @Test fun everyDefaultIsAValueTheSettingAccepts() {
        for (s in SettingsCatalog.all.filterIsInstance<Amount>()) {
            assertThat(s.accepts(s.default)).isTrue()
        }
        for (s in SettingsCatalog.all.filterIsInstance<Pick<*>>()) {
            assertThat(s.choices().map { it.first }).contains(s.currentName(DefaultsStore))
        }
    }

    @Test fun minusOneMeansSomethingOnlyWhereTheSettingSaysSo() {
        assertThat(HeatSettings.BATTERY_STOP.accepts(-1.0)).isTrue()
        assertThat(ProcessingSettings.THREADS.accepts(-1.0)).isTrue()
        assertThat(ProcessingSettings.BATCH_MINUTES.accepts(-1.0)).isFalse()
        assertThat(ProcessingSettings.BATCH_MINUTES.accepts(10.0)).isFalse()
    }

    @Test fun aLimitSetToMinusOneReadsAsNone() {
        Settings[HeatSettings.BATTERY_STOP] = -1.0
        assertThat(Settings.valueOrNull(HeatSettings.BATTERY_STOP)).isNull()
        Settings[HeatSettings.BATTERY_STOP] = 42.0
        assertThat(Settings.valueOrNull(HeatSettings.BATTERY_STOP)).isEqualTo(42.0)
    }

    @Test fun withNothingChangedEveryPassOfThePipelineRuns() {
        for (kind in MediaKind.entries) assertThat(Pass.forKind(kind)).isEqualTo(Pass.pipelineFor(kind))
    }

    @Test fun aPassSwitchedOffForOneKindStillRunsForTheOthers() {
        Settings[MediaSettings.VIDEO_DESCRIBE] = false
        assertThat(Pass.forKind(MediaKind.VIDEO)).doesNotContain(Pass.OBJECTS)
        assertThat(Pass.forKind(MediaKind.IMAGE)).contains(Pass.OBJECTS)
        // Switching a pass off is not retiring it.
        assertThat(Pass.retired).doesNotContain(Pass.OBJECTS)
    }

    @Test fun metadataCannotBeSwitchedOff() {
        for (kind in MediaKind.entries) assertThat(Pass.forKind(kind)).contains(Pass.METADATA)
    }

    @Test fun processorOnlyOverridesEveryModelChoice() {
        Settings[AccelerationSettings.VISUAL] = ModelHardware.NNAPI
        assertThat(AccelerationSettings.hardwareFor(AccelerationSettings.VISUAL)).isEqualTo(ModelHardware.NNAPI)
        Settings[AccelerationSettings.PROCESSOR_ONLY] = true
        assertThat(AccelerationSettings.hardwareFor(AccelerationSettings.VISUAL)).isEqualTo(ModelHardware.CPU)
        assertThat(AccelerationSettings.nativeGpu()).isFalse()
    }

    @Test fun anUnknownStoredChoiceReadsAsTheDefault() {
        assertThat(HeatSettings.STATUS_STOP.parse("NO_SUCH_LEVEL")).isEqualTo(HeatSettings.STATUS_STOP.default)
    }

    @Test fun labelRulesRenameMergeAndHide() {
        val rules = LabelRules.parse("Cat -> pet\ndog -> pet\nclutter ->\nnot a rule\n -> nothing")
        assertThat(LabelRules.apply("cat", rules)).isEqualTo("pet")
        assertThat(LabelRules.apply("Dog", rules)).isEqualTo("pet")
        assertThat(LabelRules.apply("clutter", rules)).isNull()
        assertThat(LabelRules.apply("tree", rules)).isEqualTo("tree")
        assertThat(rules).hasSize(3)
    }

    @Test fun labelRulesSurviveARoundTrip() {
        val rules = mapOf("a" to "b", "c" to null)
        assertThat(LabelRules.parse(LabelRules.format(rules))).isEqualTo(rules)
    }

    @Test fun aLabelSourceSwitchedOffLeavesSearch() {
        assertThat(LabelSettings.searches(ai.dusty.finderplus.model.TagSource.OBJECT)).isTrue()
        Settings[LabelSettings.OBJECT_NAMES] = false
        assertThat(LabelSettings.searches(ai.dusty.finderplus.model.TagSource.OBJECT)).isFalse()
        assertThat(LabelSettings.searches(ai.dusty.finderplus.model.TagSource.USER)).isTrue()
    }

    @Test fun everythingIsIndexedUntilTheUserSkipsSomething() {
        assertThat(ScopeSettings.includes(MediaKind.IMAGE, "any", "image/png", 10, 10, null)).isTrue()
    }

    @Test fun skippedFoldersTypesAndSizesAreLeftOut() {
        Settings[ScopeSettings.SKIP_FOLDERS] = "First\n second "
        Settings[ScopeSettings.SKIP_TYPES] = "image/gif, audio/*"
        Settings[ScopeSettings.MIN_IMAGE_EDGE] = 100.0
        Settings[ScopeSettings.MIN_VIDEO_S] = 3.0
        assertThat(ScopeSettings.includes(MediaKind.IMAGE, "first", "image/jpeg", 800, 600, null)).isFalse()
        assertThat(ScopeSettings.includes(MediaKind.IMAGE, "Second", "image/jpeg", 800, 600, null)).isFalse()
        assertThat(ScopeSettings.includes(MediaKind.IMAGE, "third", "image/gif", 800, 600, null)).isFalse()
        assertThat(ScopeSettings.includes(MediaKind.AUDIO, "third", "audio/mpeg", null, null, 60_000)).isFalse()
        assertThat(ScopeSettings.includes(MediaKind.IMAGE, "third", "image/jpeg", 64, 48, null)).isFalse()
        assertThat(ScopeSettings.includes(MediaKind.VIDEO, "third", "video/mp4", 1920, 1080, 2_000)).isFalse()
        assertThat(ScopeSettings.includes(MediaKind.VIDEO, "third", "video/mp4", 1920, 1080, 5_000)).isTrue()
        // A size MediaStore does not report never excludes a file.
        assertThat(ScopeSettings.includes(MediaKind.IMAGE, "third", "image/jpeg", null, null, null)).isTrue()
    }
}
