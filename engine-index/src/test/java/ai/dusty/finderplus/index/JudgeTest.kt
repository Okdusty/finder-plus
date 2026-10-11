package ai.dusty.finderplus.index

import ai.dusty.finderplus.model.TagSource
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins the judge's two safety properties: strict verdict parsing, and machine provenance.
 *
 * Both exist because their failure modes are silent. A permissive parser would map "it might be a
 * dog" to NO and delete real labels; a judge writing USER tags would forge supervision — the exact
 * confusion that once made 3,782 machine answers look human until traced.
 */
class JudgeTest {

    @Test fun onlyCommittedAnswersCount() {
        assertThat(parseVerdict("Yes")).isEqualTo(Verdict.YES)
        assertThat(parseVerdict("yes, it is a scooter")).isEqualTo(Verdict.YES)
        assertThat(parseVerdict("No.")).isEqualTo(Verdict.NO)
        assertThat(parseVerdict("  no ")).isEqualTo(Verdict.NO)
    }

    @Test fun hedgesStayWithTheHuman() {
        // Anything that does not open by committing goes back to the queue, never guessed away.
        assertThat(parseVerdict("It could be a scooter")).isEqualTo(Verdict.UNSURE)
        assertThat(parseVerdict("I think so")).isEqualTo(Verdict.UNSURE)
        assertThat(parseVerdict("")).isEqualTo(Verdict.UNSURE)
        assertThat(parseVerdict("Maybe not")).isEqualTo(Verdict.UNSURE)
        // "not sure" starts with "no"-adjacent text but not the word — still strict prefix on tokens.
        assertThat(parseVerdict("unknown")).isEqualTo(Verdict.UNSURE)
    }

    @Test fun judgeProvenanceIsMachineNotHuman() {
        // The judge writes VLM (5), never USER (4). If these ordinals ever move, every provenance
        // assumption in the pipeline moves with them — fail loudly here first.
        assertThat(TagSource.VLM.ordinal).isEqualTo(5)
        assertThat(TagSource.USER.ordinal).isEqualTo(4)
        assertThat(TagSource.VLM).isNotEqualTo(TagSource.USER)
    }
}

/**
 * The batched reply parser carries the whole speed win (one prefill per item), so its failure mode
 * must be pinned: anything unparseable stays UNSURE — silently mapping garbage to NO would delete
 * labels at batch speed.
 */
class BatchJudgementTest {

    @Test fun wellFormedRepliesParsePerLine() {
        val j = parseJudgement("1: yes\n2: no\n3: unsure\nC: a dog on a beach.", 3)
        org.junit.Assert.assertEquals(
            listOf(Verdict.YES, Verdict.NO, Verdict.UNSURE), j.verdicts,
        )
        org.junit.Assert.assertEquals("a dog on a beach.", j.caption)
    }

    @Test fun modelsEchoingTheQuestionFormatStillParse() {
        // "1. yes" and "1) yes" are how models often answer numbered lists.
        val j = parseJudgement("1. Yes\n2) no, it is a cat\nc: two cats.", 2)
        org.junit.Assert.assertEquals(listOf(Verdict.YES, Verdict.NO), j.verdicts)
        org.junit.Assert.assertEquals("two cats.", j.caption)
    }

    @Test fun missingMalformedAndOutOfRangeLinesStayUnsure() {
        // Answer 2 missing entirely; answer 5 out of range; prose line ignored.
        val j = parseJudgement("1: yes\nI think the rest are unclear\n5: yes", 3)
        org.junit.Assert.assertEquals(
            listOf(Verdict.YES, Verdict.UNSURE, Verdict.UNSURE), j.verdicts,
        )
        org.junit.Assert.assertNull(j.caption)
    }

    @Test fun emptyReplyIsAllUnsureNotAllNo() {
        val j = parseJudgement("", 4)
        org.junit.Assert.assertTrue(j.verdicts.all { it == Verdict.UNSURE })
    }

    @Test fun promptNumbersEveryLabelAndAsksForCaption() {
        val prompt = batchPrompt(listOf("dog", "beach"))
        org.junit.Assert.assertTrue(prompt.contains("1. Does this image show \"dog\"?"))
        org.junit.Assert.assertTrue(prompt.contains("2. Does this image show \"beach\"?"))
        org.junit.Assert.assertTrue(prompt.contains("C:"))
    }

    @Test fun decodeBudgetScalesWithQuestions() {
        // Enough for N terse answers + one sentence, never unbounded.
        org.junit.Assert.assertTrue(batchMaxTokens(1) in 40..80)
        org.junit.Assert.assertTrue(batchMaxTokens(6) in 80..140)
    }
}
