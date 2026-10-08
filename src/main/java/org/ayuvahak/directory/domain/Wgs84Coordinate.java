package org.ayuvahak.directory.domain;

/** Geographic coordinate with explicit longitude-then-latitude WGS84 order. */
public record Wgs84Coordinate(double longitude, double latitude) {

  public Wgs84Coordinate {
    if (!Double.isFinite(longitude)
        || longitude < -180.0
        || longitude > 180.0
        || !Double.isFinite(latitude)
        || latitude < -90.0
        || latitude > 90.0) {
      throw new IllegalArgumentException("coordinate must be finite WGS84 longitude and latitude");
    }
  }
}
