package com.rohanc.ocrscanner.core

import kotlin.math.*

data class Quad(val points: List<Point>) {
    init { require(points.size == 4) }
    fun validate(width: Int, height: Int): Quad {
        require(width > 1 && height > 1)
        require(points.all { it.x.isFinite() && it.y.isFinite() && it.x in 0f..(width - 1f) && it.y in 0f..(height - 1f) }) { "Keep corners inside the image." }
        val crosses = points.indices.map { i ->
            val a = points[i]; val b = points[(i + 1) % 4]; val c = points[(i + 2) % 4]
            (b.x-a.x)*(c.y-b.y) - (b.y-a.y)*(c.x-b.x)
        }
        require(crosses.all { it > 1f }) { "Corners must form a clockwise convex page." }
        require(area() >= 100f) { "Selected region is too small." }
        return this
    }
    fun area(): Float = abs(points.indices.sumOf { i ->
        val a = points[i]; val b = points[(i+1)%4]; (a.x*b.y - b.x*a.y).toDouble()
    }.toFloat()) / 2f
    fun outputSize(): Pair<Int, Int> {
        fun distance(a: Point,b: Point) = hypot(a.x-b.x,a.y-b.y)
        return max(distance(points[0],points[1]),distance(points[3],points[2])).roundToInt().coerceAtLeast(2) to
            max(distance(points[0],points[3]),distance(points[1],points[2])).roundToInt().coerceAtLeast(2)
    }
    companion object {
        fun full(width: Int,height: Int) = Quad(listOf(Point(0f,0f),Point(width-1f,0f),Point(width-1f,height-1f),Point(0f,height-1f)))
    }
}
enum class Enhancement { COLOR, ORIGINAL, GRAYSCALE, BLACK_WHITE }
data class EditRecipe(val quad: Quad, val rotation: Int = 0, val enhancement: Enhancement = Enhancement.COLOR,
    val brightness: Float = 0f, val contrast: Float = 1f) {
    init { require(rotation in 0..3 && brightness in -60f..60f && contrast in .5f..1.8f) }
}
data class ProcessedImage(val path: String, val width: Int, val height: Int)
data class Placement(val regionId: String, val x: Float, val y: Float, val width: Float, val height: Float)
data class Sheet(val width: Float, val height: Float, val placements: List<Placement>)
data class LayoutRegion(val id: String,val width: Int,val height: Int,val side: CardSide = CardSide.SINGLE,val label: String = "")
object PageLayout {
    fun sheets(regions: List<LayoutRegion>, cards: Boolean, template: String, paper: String, margin: Float): List<Sheet> {
        require(regions.isNotEmpty()) { "Add a page first." }
        require(regions.all { it.width > 0 && it.height > 0 })
        require(template in listOf("Stacked","Side by side","Grid"))
        val (w,h) = when(paper) { "A4" -> 595f to 842f; "Letter" -> 612f to 792f; else -> error("Unsupported paper size.") }
        require(margin in 18f..60f)
        val cols = if(cards && template != "Stacked") 2 else 1
        val rows = if(!cards || template == "Side by side") 1 else 2
        val groups = if(cards) CardLayout.sheets(regions.map { CardRegion(it.id,it.side,it.label) },cols*rows).map { group -> group.map { card -> regions.single { it.id==card.id } } } else regions.map { listOf(it) }
        val gap = if(cards) 16f else 0f
        val cw = (w-2*margin-gap*(cols-1))/cols; val ch = (h-2*margin-gap*(rows-1))/rows
        return groups.map { group -> Sheet(w,h,group.mapIndexed { i,r ->
            val scale = min(cw/r.width,ch/r.height)
            val rw=r.width*scale; val rh=r.height*scale
            Placement(r.id,margin+(i%cols)*(cw+gap)+(cw-rw)/2,margin+(i/cols)*(ch+gap)+(ch-rh)/2,rw,rh)
        }) }
    }
}
