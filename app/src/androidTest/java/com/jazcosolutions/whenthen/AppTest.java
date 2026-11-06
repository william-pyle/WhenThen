package com.jazcosolutions.whenthen;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppTest {
  @org.junit.Rule
  public ActivityTestRule<MainActivity> activity =
      new ActivityTestRule<>(MainActivity.class, false, false);

  Context context;
  Store store;

  @Before
  public void clean() throws Exception {
    assertTrue(
        "Destructive test fixtures are emulator-only",
        Build.FINGERPRINT.contains("generic")
            || Build.MODEL.contains("sdk")
            || Build.MODEL.contains("Emulator"));
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.stopService(new Intent(context, MonitorService.class));
    Thread.sleep(250);
    store = new Store(context);
    store.save(new ArrayList<>());
    store.clearHistory();
    store.prefs().edit().clear().commit();
    store.engineState(new RuleEngine.State());
  }

  Rule sample() {
    Rule r = new Rule();
    r.name = "Text rescue";
    r.when.type = "sms_matches";
    r.when.set("contact", "+1 312 555 0100").set("matchText", " HELP ");
    r.then.add(Rule.action("show_message").set("message", "Check in"));
    return r;
  }

  @Test
  public void nativeBuilderSavesAndReopens() {
    activity.launchActivity(null);
    onView(withText("+  Build a rule")).perform(scrollTo(), click());
    onView(withContentDescription("Rule name")).perform(scrollTo(), replaceText("Call home"));
    onView(withContentDescription("Contact phone number"))
        .perform(scrollTo(), replaceText("3125550100"));
    onView(withContentDescription("Message to show"))
        .perform(scrollTo(), replaceText("Please call home"));
    onView(withText("Save my rule")).perform(scrollTo(), click());
    assertEquals(1, store.rules().size());
    assertFalse(store.rules().get(0).enabled);
    onView(withText("Edit blocks")).perform(scrollTo(), click());
    onView(withContentDescription("Rule name")).check(matches(withText("Call home")));
    assertEquals("Please call home", store.rules().get(0).then.get(0).get("message"));
  }

  @Test
  public void invalidBuilderExplainsMissingPieces() {
    activity.launchActivity(null);
    onView(withText("+  Build a rule")).perform(scrollTo(), click());
    onView(withText("Save my rule")).perform(scrollTo(), click());
    onView(withText("Let's finish these pieces")).check(matches(isDisplayed()));
    assertTrue(store.rules().isEmpty());
  }

  @Test
  public void templateContainsOrderedEmergencyActions() {
    activity.launchActivity(null);
    onView(withText("Explore templates")).perform(scrollTo(), click());
    onView(withText("☎  Get through in an emergency")).perform(click());
    onView(withContentDescription("Contact phone number"))
        .perform(scrollTo(), replaceText("3125550100"));
    onView(withText("Save my rule")).perform(scrollTo(), click());
    Rule r = store.rules().get(0);
    assertEquals("call_count", r.when.type);
    assertEquals(10, r.priority);
    assertEquals("emergency_loud", r.then.get(0).type);
    assertEquals("show_message", r.then.get(1).type);
  }

  @Test
  public void reorderMovesWholeActionOptions() {
    Rule r = sample();
    r.then.add(Rule.action("set_volume").set("percent", 42).set("channels", "media"));
    store.save(List.of(r));
    activity.launchActivity(null);
    onView(withText("Edit blocks")).perform(scrollTo(), click());
    onView(withContentDescription("Move action 2 up")).perform(scrollTo(), click());
    onView(withText("Save my rule")).perform(scrollTo(), click());
    Rule saved = store.rules().get(0);
    assertEquals("set_volume", saved.then.get(0).type);
    assertEquals("42", saved.then.get(0).get("percent"));
    assertEquals("Check in", saved.then.get(1).get("message"));
  }

  @Test
  public void simulationRunsMatcherWithoutDispatch() {
    store.save(List.of(sample()));
    activity.launchActivity(null);
    onView(withText("✦  Try it")).perform(click());
    onView(withText("▶  Simulate this flow")).perform(scrollTo(), click());
    onView(withText("Your flow, step by step")).check(matches(isDisplayed()));
    assertEquals("simulation", store.history().get(0).status);
    assertTrue(store.history().get(0).detail.contains("WHEN matched"));
    assertNull(MonitorService.active);
  }

  @Test
  public void backupRoundTripDisablesRules() {
    Rule r = sample();
    r.enabled = true;
    store.save(List.of(r));
    List<Rule> parsed = store.parseImport(store.exportRules());
    assertFalse(parsed.get(0).enabled);
    assertEquals(" HELP ", parsed.get(0).when.get("matchText"));
    assertEquals("+1 312 555 0100", parsed.get(0).when.get("contact"));
  }

  @Test
  public void invalidImportIsAtomic() {
    store.save(List.of(sample()));
    try {
      store.parseImport("{\"version\":1,\"rules\":[{\"name\":\"bad\"}]}");
      fail("Invalid import accepted");
    } catch (IllegalArgumentException expected) {
    }
    assertEquals("Text rescue", store.rules().get(0).name);
  }

  @Test
  public void duplicateImportIdsRejected() {
    Rule a = sample(), b = Store.copy(a);
    try {
      Store.validate(List.of(a, b));
      fail("Duplicate ID accepted");
    } catch (IllegalArgumentException expected) {
      assertTrue(expected.getMessage().contains("Duplicate"));
    }
  }

  @Test
  public void ruleSurvivesActivityRecreation() throws Exception {
    store.save(List.of(sample()));
    activity.launchActivity(null);
    activity.getActivity().runOnUiThread(() -> activity.getActivity().recreate());
    Thread.sleep(600);
    assertEquals("Text rescue", store.rules().get(0).name);
    onView(withText("Your building blocks")).perform(scrollTo()).check(matches(isDisplayed()));
  }

  @Test
  public void historyIsBoundedAndNewestFirst() {
    for (int i = 0; i < 310; i++) store.log("Event " + i, "Detail", "observed");
    assertEquals(300, store.history().size());
    assertEquals("Event 309", store.history().get(0).title);
  }

  @Test
  public void notificationImageRejectsInaccessibleFile() {
    Rule r = sample();
    r.then = List.of(Rule.action("show_image").set("uri", "content://missing-provider/image"));
    assertTrue(
        Readiness.missing(context, r).stream().anyMatch(e -> e.startsWith("File access expired")));
  }

  @Test
  public void setupAndActivityScreensOpen() {
    activity.launchActivity(null);
    onView(withText("⚙  Setup")).perform(click());
    onView(withText("Phone setup")).check(matches(isDisplayed()));
    onView(withText("◷  Activity")).perform(click());
    onView(withText("Activity")).check(matches(isDisplayed()));
  }
}
