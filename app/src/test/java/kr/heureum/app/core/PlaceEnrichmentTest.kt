package kr.heureum.app.core

import org.junit.Assert.*
import org.junit.Test

/** Shapes taken from real Kakao Local responses around Handong Global University (2026-10-07). */
class PlaceEnrichmentTest {
    private fun ring(lat: Double, lon: Double, size: Double = .0002) = listOf(MapPoint(lat - size, lon - size), MapPoint(lat - size, lon + size), MapPoint(lat + size, lon + size), MapPoint(lat + size, lon - size), MapPoint(lat - size, lon - size))
    private val campus = MapPlace("osm:way:1", "한동대학교", "campus", MapPoint(36.103, 129.389), listOf(ring(36.103, 129.389, .003)), aliases = listOf("한동대학교"))
    private val iHouse = MapPlace("osm:way:2", "아이하우스", "dorm", MapPoint(36.10177, 129.39085), listOf(ring(36.10177, 129.39085)), aliases = listOf("아이하우스"))
    private val unnamed = MapPlace("osm:way:3", "건물", "building", MapPoint(36.1046, 129.3895), listOf(ring(36.1046, 129.3895)))
    private val changjo = MapPlace("osm:way:4", "창조관", "building", MapPoint(36.10256, 129.39144), listOf(ring(36.10256, 129.39144)), aliases = listOf("창조관"))
    private fun poi(name: String, lat: Double, lon: Double, category: String = "교육,학문 > 학교부속시설", group: String = "") = KakaoPoi(name, name, group, category, lat, lon)

    @Test fun shortNameDropsCampusPrefixIncludingSpacedVariant() {
        val names = listOf("한동대학교")
        assertEquals("글로벌하우스", PlaceEnrichment.shortName("한동대학교 글로벌하우스", names))
        assertEquals("올네이션스홀", PlaceEnrichment.shortName("한동 대학교 올네이션스홀", names))
        assertNull(PlaceEnrichment.shortName("한동대학교", names))
    }
    @Test fun facilityNameBecomesAliasButNeverDowngradesAnOsmDorm() {
        val merged = PlaceEnrichment.merge(listOf(campus, iHouse), listOf(poi("한동대학교 글로벌하우스", 36.10177, 129.39070)))
        val p = merged.first { it.id == iHouse.id }
        assertEquals("아이하우스", p.name); assertEquals("dorm", p.kind); assertTrue("글로벌하우스" in p.aliases)
    }
    @Test fun unnamedBuildingTakesFacilityNameAndBecomesClassroom() {
        val p = PlaceEnrichment.merge(listOf(campus, unnamed), listOf(poi("한동대학교 언어교육원", 36.10467, 129.38950))).first { it.id == unnamed.id }
        assertEquals("언어교육원", p.name); assertEquals("classroom", p.kind)
    }
    @Test fun kakaoDormitoryCategorySetsDorm() {
        val p = PlaceEnrichment.merge(listOf(campus, changjo), listOf(poi("한동대학교 창조관", 36.10256, 129.39144, "부동산 > 주거시설 > 기숙사,기숙시설"))).first { it.id == changjo.id }
        assertEquals("dorm", p.kind)
    }
    @Test fun twoDifferentFacilitiesInOneFootprintAreIgnored() {
        val p = PlaceEnrichment.merge(listOf(campus, unnamed), listOf(poi("한동대학교 A관", 36.1046, 129.3895), poi("한동대학교 B관", 36.10462, 129.3895))).first { it.id == unnamed.id }
        assertEquals(unnamed, p)
    }
    @Test fun restaurantsAndCafesBecomeLabelPointsWithoutDuplicates() {
        val existing = MapPlace("osm:node:9", "빽다방", "food", MapPoint(36.10208, 129.39049), emptyList(), aliases = listOf("빽다방"))
        val merged = PlaceEnrichment.merge(listOf(campus, existing), listOf(
            poi("빽다방 포항한동대점", 36.102084, 129.390495, "음식점 > 카페", "CE7"),
            poi("샐러디 포항한동대점", 36.10226, 129.39006, "음식점 > 샐러드", "FD6"),
            poi("한동대학교 테니스장", 36.10336, 129.39038, "스포츠,레저 > 테니스 > 테니스장")))
        assertEquals(1, merged.count { it.name.contains("빽다방") })
        assertEquals("food", merged.single { it.name.startsWith("샐러디") }.kind)
        assertTrue(merged.none { it.name.contains("테니스") })
    }
    @Test fun participantCorrectionRenamesAndRetypesButKeepsOldNameForTimetableMatching() {
        val fixed = PlaceCorrections.apply(listOf(iHouse), mapOf(iHouse.id to PlaceCorrection(iHouse.id, "SW타운", "classroom"))).single()
        assertEquals("SW타운", fixed.name); assertEquals("classroom", fixed.kind); assertTrue("아이하우스" in fixed.aliases)
        assertEquals(iHouse, PlaceCorrections.apply(listOf(iHouse), emptyMap()).single())
        assertEquals("dorm", PlaceCorrections.apply(listOf(iHouse), mapOf(iHouse.id to PlaceCorrection(iHouse.id, null, "spaceship"))).single().kind)
    }
    @Test fun correctedBuildingChangesTheEstimate() {
        val now = java.time.ZonedDateTime.of(2026, 10, 7, 15, 0, 0, 0, STUDY_ZONE).toInstant().toEpochMilli()
        val fixes = listOf(GeoSample(36.10177, 129.39085, 15f, now - 180_000, 1), GeoSample(36.10177, 129.39085, 15f, now, 1))
        val before = InferenceEngine.predict(now, emptyList(), null, fixes, mapPlaces = listOf(campus, iHouse))
        val after = InferenceEngine.predict(now, emptyList(), null, fixes, mapPlaces = PlaceCorrections.apply(listOf(campus, iHouse), mapOf(iHouse.id to PlaceCorrection(iHouse.id, "SW타운", "classroom"))))
        assertEquals("휴식", before.activity); assertEquals("공부", after.activity); assertEquals("SW타운", after.place)
    }
}
