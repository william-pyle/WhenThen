package com.jazcosolutions.whenthen;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MapPickerTest {
  private MapPickerActivity target;

  @org.junit.Rule
  public ActivityTestRule<MapPickerActivity> activity =
      new ActivityTestRule<>(MapPickerActivity.class, false, false);

  private void main(Runnable action) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
  }

  @Before
  public void emulatorOnly() {
    assertTrue(
        "Emulator fixtures only", Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"));
  }

  private Intent existing() {
    return new Intent()
        .putExtra("latitude", 38.8895)
        .putExtra("longitude", -77.0353)
        .putExtra("name", "My park")
        .putExtra("radiusFeet", "250");
  }

  private void launch(Intent intent) throws Exception {
    activity.launchActivity(intent);
    target = activity.getActivity();
    // Deterministic native UI tests use no external map images or geocoding requests.
    main(() -> activity.getActivity().map.getSettings().setBlockNetworkLoads(true));
    for (int i = 0; i < 40; i++) {
      try {
        if (js("typeof picker !== 'undefined' && !!picker.snapshot().zoom").getAsBoolean()) return;
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    fail("Bundled map did not initialize");
  }

  private JsonElement js(String code) throws Exception {
    AtomicReference<String> value = new AtomicReference<>();
    CountDownLatch done = new CountDownLatch(1);
    main(
        () ->
            target.map.evaluateJavascript(
                code,
                result -> {
                  value.set(result);
                  done.countDown();
                }));
    assertTrue(done.await(5, TimeUnit.SECONDS));
    return JsonParser.parseString(value.get());
  }

  private JsonObject snapshot() throws Exception {
    return js("picker.snapshot()").getAsJsonObject();
  }

  @Test
  public void existingPinAndFeetCircleResizeAndReturnOnlyOnAccept() throws Exception {
    launch(existing());
    assertEquals(76.2, snapshot().get("radiusMeters").getAsDouble(), 0.001);
    onView(withContentDescription("Radius in feet")).perform(replaceText("500"));
    assertEquals(152.4, snapshot().get("radiusMeters").getAsDouble(), 0.001);
    onView(withText("Use this location")).perform(click());
    assertEquals(Activity.RESULT_OK, activity.getActivityResult().getResultCode());
    Intent result = activity.getActivityResult().getResultData();
    assertEquals("500", result.getStringExtra("radiusFeet"));
    assertEquals("My park", result.getStringExtra("name"));
    assertEquals(38.8895, result.getDoubleExtra("latitude", 0), 0.000001);
  }

  @Test
  public void tappingMapSelectsPinWithoutLocationPermissionOrCoordinates() throws Exception {
    launch(new Intent());
    onView(withText("Use this location")).check(matches(not(isEnabled())));
    onView(isAssignableFrom(WebView.class)).perform(click());
    Thread.sleep(250);
    assertTrue(snapshot().has("latitude"));
    onView(withText("Use this location")).check(matches(isEnabled()));
    onView(withText("Use this location")).perform(click());
    assertEquals(Activity.RESULT_OK, activity.getActivityResult().getResultCode());
    assertEquals(
        "Selected place", activity.getActivityResult().getResultData().getStringExtra("name"));
  }

  @Test
  public void cancelDiscardsEditedRadiusAndPoint() throws Exception {
    launch(existing());
    onView(withContentDescription("Radius in feet")).perform(replaceText("1000"));
    onView(isAssignableFrom(WebView.class)).perform(click());
    onView(withText("Cancel")).perform(click());
    assertEquals(Activity.RESULT_CANCELED, activity.getActivityResult().getResultCode());
  }

  @Test
  public void malformedRadiusCannotBeAccepted() throws Exception {
    launch(existing());
    onView(withContentDescription("Radius in feet")).perform(replaceText("0"));
    onView(withText("Use this location")).check(matches(not(isEnabled())));
    assertEquals(76.2, snapshot().get("radiusMeters").getAsDouble(), 0.001);
  }

  @Test
  public void addressResultsMoveThePinAndPreserveCustomName() throws Exception {
    launch(existing());
    main(
        () ->
            activity.getActivity().addressSearch =
                (q, c) ->
                    c.complete(
                        List.of(
                            new AddressSearch.Place("Museum, Chicago", 41.8796, -87.6237),
                            new AddressSearch.Place("Museum, Washington", 38.8913, -77.0199)),
                        null));
    onView(withContentDescription("Search an address or place")).perform(replaceText("Museum"));
    onView(withText("Search")).perform(click());
    onView(withText("Museum, Chicago")).perform(click());
    assertEquals(41.8796, snapshot().get("latitude").getAsDouble(), 0.000001);
    onView(withContentDescription("Place name")).check(matches(withText("My park")));
    onView(withText("Use this location")).perform(click());
    assertEquals(
        -87.6237,
        activity.getActivityResult().getResultData().getDoubleExtra("longitude", 0),
        0.000001);
  }

  @Test
  public void failedSearchKeepsPriorPointAndAllowsRetry() throws Exception {
    launch(existing());
    main(
        () ->
            activity.getActivity().addressSearch =
                (q, c) -> c.complete(List.of(), "Address search unavailable"));
    onView(withContentDescription("Search an address or place")).perform(replaceText("Test place"));
    onView(withText("Search")).perform(click());
    onView(withText("Address search unavailable")).check(matches(isDisplayed()));
    assertEquals(38.8895, snapshot().get("latitude").getAsDouble(), 0.000001);
    onView(withText("Search")).check(matches(isEnabled()));
  }

  @Test
  public void obsoleteSearchCannotReplaceNewerChoice() throws Exception {
    launch(existing());
    AtomicReference<AddressSearch.Callback> pending = new AtomicReference<>();
    main(() -> activity.getActivity().addressSearch = (q, c) -> pending.set(c));
    onView(withContentDescription("Search an address or place"))
        .perform(replaceText("Slow search"));
    onView(withText("Search")).perform(click());
    onView(isAssignableFrom(WebView.class)).perform(click());
    Thread.sleep(200);
    double lat = snapshot().get("latitude").getAsDouble();
    pending.get().complete(List.of(new AddressSearch.Place("Late result", 1, 1)), null);
    Thread.sleep(200);
    assertEquals(lat, snapshot().get("latitude").getAsDouble(), 0.000001);
    onView(withText("Search")).check(matches(isEnabled()));
  }

  @Test
  public void zoomAndFitShowTheSelectedBoundary() throws Exception {
    launch(existing());
    double before = snapshot().get("zoom").getAsDouble();
    js("document.querySelector('.leaflet-control-zoom-in').click()");
    Thread.sleep(400);
    assertEquals(before + 1, snapshot().get("zoom").getAsDouble(), 0);
    onView(withContentDescription("Radius in feet")).perform(replaceText("10000"));
    onView(withText("Fit circle")).perform(click());
    assertTrue(snapshot().get("zoom").getAsDouble() < before);
    assertEquals(3048, snapshot().get("radiusMeters").getAsDouble(), 0.001);
  }

  @Test
  public void recreationKeepsChosenPointNameAndBoundary() throws Exception {
    launch(existing());
    onView(withContentDescription("Place name")).perform(replaceText("My edited park"));
    onView(withContentDescription("Radius in feet")).perform(replaceText("750"));
    main(() -> target.recreate());
    Thread.sleep(500);
    main(
        () -> {
          for (Activity a :
              ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))
            if (a instanceof MapPickerActivity) {
              target = (MapPickerActivity) a;
              target.map.getSettings().setBlockNetworkLoads(true);
            }
        });
    for (int i = 0; i < 30; i++) {
      try {
        if (snapshot().has("radiusMeters")) break;
      } catch (Exception ignored) {
      }
      Thread.sleep(100);
    }
    assertEquals(228.6, snapshot().get("radiusMeters").getAsDouble(), 0.001);
    assertEquals(38.8895, snapshot().get("latitude").getAsDouble(), 0.000001);
    onView(withContentDescription("Place name")).check(matches(withText("My edited park")));
    onView(withText("Cancel")).perform(click());
  }

  @Test
  public void realDeviceAddressProviderCanLocateAPublicLandmark() throws Exception {
    Assume.assumeTrue(
        "Opt-in live lookup only",
        InstrumentationRegistry.getArguments().getString("liveMapSearch", "false").equals("true"));
    launch(new Intent());
    onView(withContentDescription("Search an address or place"))
        .perform(replaceText("Lincoln Memorial, Washington DC"));
    onView(withText("Search")).perform(click());
    for (int i = 0; i < 60; i++) {
      Thread.sleep(250);
      JsonObject state = snapshot();
      if (state.has("latitude")) {
        assertEquals(38.8893, state.get("latitude").getAsDouble(), 0.03);
        return;
      }
    }
    fail("Live device search did not select the public landmark; inspect provider state");
  }
}
