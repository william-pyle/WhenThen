package com.jazcosolutions.whenthen;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.app.Activity;
import android.content.*;
import android.os.Build;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.lifecycle.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MapEditorTest {
  @org.junit.Rule
  public ActivityTestRule<MainActivity> activity =
      new ActivityTestRule<>(MainActivity.class, false, false);

  Store store;

  @Before
  public void before() throws Exception {
    assertTrue(
        "Emulator fixtures only", Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"));
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Monitoring.stop(context);
    Thread.sleep(300);
    store = new Store(context);
    Rule rule = new Rule();
    rule.name = "Park reminder";
    rule.when.type = "waypoint_exit";
    rule.when
        .set("waypoint", "Original park")
        .set("latitude", 38.8895)
        .set("longitude", -77.0353)
        .set("radiusFeet", 250);
    rule.then.add(Rule.action("show_message").set("message", "Remember the keys"));
    store.save(List.of(rule));
    activity.launchActivity(null);
    onView(withContentDescription("Blocks")).perform(click());
    onView(withText("Edit")).perform(scrollTo(), click());
  }

  private void openPicker() throws Exception {
    onView(withText("◎  Change location on map")).perform(scrollTo(), click());
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              for (Activity a :
                  ActivityLifecycleMonitorRegistry.getInstance()
                      .getActivitiesInStage(Stage.RESUMED))
                if (a instanceof MapPickerActivity)
                  ((MapPickerActivity) a).map.getSettings().setBlockNetworkLoads(true);
            });
    Thread.sleep(500);
  }

  @After
  public void after() {
    Monitoring.stop(InstrumentationRegistry.getInstrumentation().getTargetContext());
  }

  private void awaitEditor() throws Exception {
    for (int i = 0; i < 50; i++) {
      java.util.concurrent.atomic.AtomicBoolean resumed =
          new java.util.concurrent.atomic.AtomicBoolean();
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () -> {
                Collection<Activity> active =
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                resumed.set(
                    active.contains(activity.getActivity())
                        && active.stream().noneMatch(a -> a instanceof MapPickerActivity));
              });
      if (resumed.get()) {
        onView(withText("Save my rule")).check(matches(isEnabled()));
        return;
      }
      Thread.sleep(100);
    }
    fail("The map picker did not return to the rule editor");
  }

  @Test
  public void acceptedMapSelectionUpdatesDraftThenPersistsOnSave() throws Exception {
    onView(withContentDescription("Latitude")).check(doesNotExist());
    onView(withContentDescription("Longitude")).check(doesNotExist());
    openPicker();
    onView(withContentDescription("Place name")).perform(replaceText("Chosen park"));
    onView(withContentDescription("Radius in feet")).perform(replaceText("500"));
    onView(isAssignableFrom(WebView.class)).perform(click());
    onView(withText("Use this location")).perform(click());
    awaitEditor();
    onView(withContentDescription("Place name")).check(matches(withText("Chosen park")));
    onView(withContentDescription("Radius in feet")).check(matches(withText("500")));
    assertEquals("250", store.rules().get(0).when.get("radiusFeet"));
    onView(withText("Save my rule")).perform(scrollTo(), click());
    Rule saved = store.rules().get(0);
    assertEquals("Chosen park", saved.when.get("waypoint"));
    assertEquals("500", saved.when.get("radiusFeet"));
    assertTrue(saved.errors().isEmpty());
    assertFalse(saved.enabled);
    onView(withContentDescription("Blocks")).perform(click());
    onView(withText("Edit")).perform(scrollTo(), click());
    openPicker();
    onView(withContentDescription("Place name")).check(matches(withText("Chosen park")));
    onView(withContentDescription("Radius in feet")).check(matches(withText("500")));
    onView(withText("Cancel")).perform(click());
    awaitEditor();
  }

  @Test
  public void cancelledPickerLeavesTheEditorAndStoredRuleUntouched() throws Exception {
    openPicker();
    onView(withContentDescription("Place name")).perform(replaceText("Discard this"));
    onView(withContentDescription("Radius in feet")).perform(replaceText("750"));
    onView(withText("Cancel")).perform(click());
    awaitEditor();
    onView(withContentDescription("Place name")).check(matches(withText("Original park")));
    onView(withContentDescription("Radius in feet")).check(matches(withText("250")));
    onView(withText("Save my rule")).perform(scrollTo(), click());
    Rule rule = store.rules().get(0);
    assertEquals("Original park", rule.when.get("waypoint"));
    assertEquals("250", rule.when.get("radiusFeet"));
    assertEquals(38.8895, rule.when.number("latitude", 0), 0.000001);
  }
}
