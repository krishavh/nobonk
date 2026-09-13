package ai.genwhy.nobonk.ml

import ai.genwhy.nobonk.model.Detection
import ai.genwhy.nobonk.model.NormBox
import org.junit.Assert.*
import org.junit.Test

class PersonConfirmationTest {
    private val left = NormBox(0f, 0f, 0.3f, 1f)
    private val right = NormBox(0.7f, 0f, 1f, 1f)
    private fun person(id: String, box: NormBox = left) = Detection(id, box, 0.9f, 1f, "person", 0)

    @Test fun `isolated frame never confirms a person`() {
        val policy = PersonConfirmation()
        assertTrue(policy.update(listOf(person("one")), 0).isEmpty())
        assertTrue(policy.update(emptyList(), 100).isEmpty())
    }
    @Test fun `matching person confirms on second frame despite changing detector id`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("first")), 0)
        assertEquals(setOf("second"), policy.update(listOf(person("second")), 100))
        assertEquals(setOf("third"), policy.update(listOf(person("third")), 200))
    }
    @Test fun `disjoint person cannot inherit confirmation or reused id`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("one")), 0)
        assertTrue(policy.update(listOf(person("one", right)), 100).isEmpty())
    }
    @Test fun `gap beyond maximum resets but exact limit is accepted`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("one")), 0)
        assertEquals(setOf("one"), policy.update(listOf(person("one")), 750))
        assertTrue(policy.update(listOf(person("one")), 1501).isEmpty())
    }
    @Test fun `missing frame and explicit reset break continuity`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("one")), 0)
        policy.update(emptyList(), 100)
        assertTrue(policy.update(listOf(person("one")), 200).isEmpty())
        policy.reset()
        assertTrue(policy.update(listOf(person("one")), 300).isEmpty())
    }
    @Test fun `two people confirm independently regardless of ordering`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("left", left), person("right", right)), 0)
        assertEquals(setOf("r2", "l2"), policy.update(listOf(person("r2", right), person("l2", left)), 100))
    }
    @Test fun `one prior person cannot confirm two overlapping candidates and best overlap wins`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("prior")), 0)
        val weaker = NormBox(0.1f, 0f, 0.4f, 1f)
        assertEquals(setOf("exact"), policy.update(listOf(person("weaker", weaker), person("exact")), 100))
    }
    @Test fun `duplicates blank ids and nonpersons cannot create shared confirmation`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("seed")), 0)
        assertTrue(policy.update(listOf(person("duplicate"), person("duplicate"), person("")), 100).isEmpty())
        val dog = person("dog").copy(classId = 16, className = "dog")
        assertTrue(policy.update(listOf(dog), 200).isEmpty())
        assertTrue(policy.update(listOf(person("person")), 300).isEmpty())
    }
    @Test fun `same timestamp cannot confirm a single observation twice`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("one")), 100)
        assertTrue(policy.update(listOf(person("one")), 100).isEmpty())
        assertEquals(setOf("one"), policy.update(listOf(person("one")), 200))
    }
    @Test fun `regressed time resets without accepting stale frame`() {
        val policy = PersonConfirmation()
        policy.update(listOf(person("one")), 100)
        assertTrue(policy.update(listOf(person("one")), 99).isEmpty())
        assertTrue(policy.update(listOf(person("one")), 200).isEmpty())
    }
    @Test fun `accepted slow phone frames can still confirm people`() {
        val policy = PersonConfirmation(maxGapMs = 5000)
        assertTrue(policy.update(listOf(person("one")), 1000).isEmpty())
        assertEquals(setOf("one"), policy.update(listOf(person("one")), 2200))
        assertEquals(setOf("one"), policy.update(listOf(person("one")), 3400))
        assertTrue(policy.update(listOf(person("one")), 8401).isEmpty())
    }
}
