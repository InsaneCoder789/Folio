package com.rohanc.ocrscanner.core

/** Coordinates belong to the upright working image, never to the screen. */
data class Point(val x: Float, val y: Float)
data class Word(val text: String, val polygon: List<Point>)
data class OcrResult(val text: String, val words: List<Word>, val width: Int, val height: Int)
data class ImageInput(val path: String, val width: Int, val height: Int, val revision: Long)
interface OcrEngine { suspend fun recognize(input: ImageInput): OcrResult }

class StaleRevision : IllegalStateException("This document changed. Reopen it and try again.")

object RevisionRules {
    fun requireCurrent(expected: Long, actual: Long) {
        if (expected != actual) throw StaleRevision()
    }
    fun reordered(requested: List<String>, existing: List<String>): List<String> {
        require(requested.size == existing.size && requested.toSet().size == requested.size &&
            requested.toSet() == existing.toSet()) { "Page order must contain every region exactly once." }
        return requested
    }
}

enum class CardSide { FRONT, BACK, SINGLE }
data class CardRegion(val id: String, val side: CardSide, val pairLabel: String = "")
data class CardLabelEdit(val id: String, val side: String, val label: String)

/** Packs whole confirmed pairs together; does not infer identity from OCR. */
object CardLayout {
    fun sheets(regions: List<CardRegion>, capacity: Int): List<List<CardRegion>> {
        require(capacity >= 2)
        require(regions.map { it.id }.distinct().size == regions.size) { "Duplicate region." }
        val groups = regions.filter { it.side != CardSide.SINGLE }.groupBy { it.pairLabel.trim() }
        groups.forEach { (label, group) ->
            require(label.isNotBlank() && group.size == 2 &&
                group.count { it.side == CardSide.FRONT } == 1 &&
                group.count { it.side == CardSide.BACK } == 1) { "Confirm one front and one back per pair." }
        }
        val seen = mutableSetOf<String>()
        val pages = mutableListOf<MutableList<CardRegion>>()
        regions.forEach { region ->
            if (!seen.add(region.id)) return@forEach
            val unit = if (region.side == CardSide.SINGLE) listOf(region) else
                groups.getValue(region.pairLabel.trim()).sortedBy { it.side.ordinal }
            seen.addAll(unit.map { it.id })
            if (pages.isEmpty() || pages.last().size + unit.size > capacity) pages.add(mutableListOf())
            pages.last().addAll(unit)
        }
        return pages
    }
}
