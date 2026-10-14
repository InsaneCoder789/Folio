package com.rohanc.ocrscanner.core

import org.junit.Assert.*
import org.junit.Test

class RevisionAndLayoutTest {
    @Test fun matchingRevisionIsAccepted() { RevisionRules.requireCurrent(4, 4) }
    @Test(expected = StaleRevision::class) fun oldResultIsRejected() { RevisionRules.requireCurrent(3, 4) }
    @Test fun reorderingPreservesEveryRegion() {
        assertEquals(listOf("b", "a"), RevisionRules.reordered(listOf("b", "a"), listOf("a", "b")))
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateOrderIsRejected() { RevisionRules.reordered(listOf("a", "a"), listOf("a", "b")) }
    @Test(expected = IllegalArgumentException::class) fun missingRegionIsRejected() { RevisionRules.reordered(listOf("a"), listOf("a", "b")) }
    @Test fun emptyInputCreatesNoSheets() { assertTrue(CardLayout.sheets(emptyList(), 4).isEmpty()) }
    @Test fun overflowCreatesAnotherSheet() {
        val singles = (1..6).map { CardRegion("$it", CardSide.SINGLE) }
        assertEquals(listOf(4, 2), CardLayout.sheets(singles, 4).map { it.size })
    }
    @Test fun pairIsNotSplitAndFrontComesFirst() {
        val cards = listOf(CardRegion("s", CardSide.SINGLE), CardRegion("b", CardSide.BACK, "A"), CardRegion("f", CardSide.FRONT, " A "))
        assertEquals(listOf(listOf("s"), listOf("f", "b")), CardLayout.sheets(cards, 2).map { page -> page.map { it.id } })
    }
    @Test(expected = IllegalArgumentException::class) fun missingBackRequiresReview() { CardLayout.sheets(listOf(CardRegion("f", CardSide.FRONT, "A")), 2) }
    @Test(expected = IllegalArgumentException::class) fun duplicateFrontsAreRejected() { CardLayout.sheets(listOf(CardRegion("1", CardSide.FRONT, "A"), CardRegion("2", CardSide.FRONT, "A")), 2) }
    @Test(expected = IllegalArgumentException::class) fun blankPairLabelIsRejected() { CardLayout.sheets(listOf(CardRegion("f", CardSide.FRONT), CardRegion("b", CardSide.BACK)), 2) }
    @Test(expected = IllegalArgumentException::class) fun duplicateRegionIsRejected() { CardLayout.sheets(listOf(CardRegion("x", CardSide.SINGLE), CardRegion("x", CardSide.SINGLE)), 4) }
    @Test(expected = IllegalArgumentException::class) fun tooSmallSheetIsRejected() { CardLayout.sheets(emptyList(), 1) }
}
