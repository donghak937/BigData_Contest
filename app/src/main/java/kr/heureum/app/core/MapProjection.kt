package kr.heureum.app.core

import kotlin.math.*

object MapProjection {
    const val MAX_LAT = 85.05112878
    fun worldSize(zoom: Int) = 256.0 * 2.0.pow(zoom)
    fun point(latitude: Double, longitude: Double, zoom: Int): Pair<Double,Double> {
        val lat=Math.toRadians(latitude.coerceIn(-MAX_LAT,MAX_LAT))
        val size=worldSize(zoom)
        return (longitude+180)/360*size to (1-ln(tan(lat)+1/cos(lat))/PI)/2*size
    }
    fun coordinates(x: Double, y: Double, zoom: Int): Pair<Double,Double> {
        val size=worldSize(zoom)
        val wrapped=((x%size)+size)%size
        return Math.toDegrees(atan(sinh(PI*(1-2*y.coerceIn(0.0,size)/size)))) to (wrapped/size*360-180)
    }
    fun metersPerPixel(latitude: Double, zoom: Int) = 40_075_016.686*cos(Math.toRadians(latitude.coerceIn(-MAX_LAT,MAX_LAT)))/worldSize(zoom)
}
