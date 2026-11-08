package com.jazcosolutions.whenthen;

import android.content.Context;
import android.location.*;
import android.os.Build;
import java.util.*;
import java.util.concurrent.*;

/** Uses Android's configured geocoding provider; no app API key or public search proxy. */
interface AddressSearch {
  final class Place {
    final String label;
    final double latitude, longitude;

    Place(String label, double latitude, double longitude) {
      this.label = label;
      this.latitude = latitude;
      this.longitude = longitude;
    }
  }

  interface Callback {
    void complete(List<Place> places, String error);
  }

  void search(String query, Callback callback);

  default void close() {}

  final class Android implements AddressSearch {
    private final Geocoder geocoder;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    Android(Context context) {
      geocoder = new Geocoder(context, Locale.getDefault());
    }

    private void deliver(List<Address> addresses, Callback callback) {
      List<Place> places = new ArrayList<>();
      if (addresses != null)
        for (Address address : addresses) {
          if (!address.hasLatitude()
              || !address.hasLongitude()
              || !MapSelection.validPoint(address.getLatitude(), address.getLongitude())) continue;
          String label = address.getAddressLine(0);
          if (label == null || label.isBlank()) label = address.getFeatureName();
          if (label == null || label.isBlank()) label = "Search result";
          places.add(new Place(label, address.getLatitude(), address.getLongitude()));
        }
      callback.complete(places, null);
    }

    public void search(String query, Callback callback) {
      if (!Geocoder.isPresent()) {
        callback.complete(
            List.of(),
            "Address search is unavailable on this device. You can still choose a point on the"
                + " map.");
        return;
      }
      try {
        if (Build.VERSION.SDK_INT >= 33) {
          geocoder.getFromLocationName(
              query,
              5,
              new Geocoder.GeocodeListener() {
                public void onGeocode(List<Address> addresses) {
                  deliver(addresses, callback);
                }

                public void onError(String error) {
                  callback.complete(
                      List.of(),
                      "Address search could not connect. Check your internet connection and try"
                          + " again.");
                }
              });
        } else
          worker.execute(
              () -> {
                try {
                  deliver(geocoder.getFromLocationName(query, 5), callback);
                } catch (Exception e) {
                  callback.complete(
                      List.of(),
                      "Address search could not connect. Check your internet connection and try"
                          + " again.");
                }
              });
      } catch (RuntimeException e) {
        callback.complete(
            List.of(),
            "Address search is unavailable right now. Try again or choose a point on the map.");
      }
    }

    public void close() {
      worker.shutdownNow();
    }
  }
}
