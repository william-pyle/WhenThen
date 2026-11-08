package com.jazcosolutions.whenthen;

/** Shared limits for the picker and its returned result. */
final class MapSelection {
  static boolean validPoint(double latitude, double longitude) {
    return Double.isFinite(latitude)
        && Double.isFinite(longitude)
        && latitude >= -90
        && latitude <= 90
        && longitude >= -180
        && longitude <= 180;
  }

  static int radius(String text) {
    try {
      int value = Integer.parseInt(text.trim());
      return value >= 50 && value <= 100000 ? value : -1;
    } catch (RuntimeException e) {
      return -1;
    }
  }
}
