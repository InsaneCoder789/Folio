package com.rohanc.ocrscanner.core

import org.junit.Assert.*
import org.junit.Test

class GeometryTest {
    @Test fun fullImageIsValid(){assertEquals(9801f,Quad.full(100,100).validate(100,100).area(),.01f)}
    @Test(expected=IllegalArgumentException::class)fun crossingCornersAreRejected(){Quad(listOf(Point(0f,0f),Point(99f,99f),Point(99f,0f),Point(0f,99f))).validate(100,100)}
    @Test(expected=IllegalArgumentException::class)fun outsideCornerIsRejected(){Quad.full(100,100).validate(80,100)}
    @Test(expected=IllegalArgumentException::class)fun tinyQuadIsRejected(){Quad.full(5,5).validate(100,100)}
    @Test(expected=IllegalArgumentException::class)fun nanIsRejected(){Quad(listOf(Point(Float.NaN,0f),Point(99f,0f),Point(99f,99f),Point(0f,99f))).validate(100,100)}
    @Test fun documentAspectRatioAndMarginsArePreserved(){
        val sheet=PageLayout.sheets(listOf(LayoutRegion("a",100,200)),false,"Stacked","A4",36f).single()
        val p=sheet.placements.single();assertEquals(.5f,p.width/p.height,.001f);assertTrue(p.x>=36 && p.y>=36);assertTrue(p.x+p.width<=sheet.width-36.0f+.01f)
    }
    @Test fun gridPaginatesAndDoesNotOverlap(){
        val sheets=PageLayout.sheets((1..7).map{LayoutRegion("$it",200,120)},true,"Grid","Letter",18f)
        assertEquals(listOf(4,3),sheets.map{it.placements.size})
        for(s in sheets)for(a in s.placements)for(b in s.placements)if(a!=b)assertTrue(a.x+a.width<=b.x || b.x+b.width<=a.x || a.y+a.height<=b.y || b.y+b.height<=a.y)
    }
    @Test fun pairedUnitsStayOnOneSheet(){
        val cards=listOf(LayoutRegion("s",100,60),LayoutRegion("b",100,60,CardSide.BACK,"ID"),LayoutRegion("f",100,60,CardSide.FRONT,"ID"))
        val sheets=PageLayout.sheets(cards,true,"Side by side","A4",36f)
        assertEquals(listOf(listOf("s"),listOf("f","b")),sheets.map{it.placements.map{p->p.regionId}})
    }
    @Test(expected=IllegalArgumentException::class)fun impossibleMarginsAreRejected(){PageLayout.sheets(listOf(LayoutRegion("x",1,1)),false,"Stacked","A4",300f)}
    @Test(expected=IllegalArgumentException::class)fun emptyLayoutIsRejected(){PageLayout.sheets(emptyList(),false,"Stacked","A4",36f)}
}
