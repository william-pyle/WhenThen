package com.jazcosolutions.whenthen;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.os.Build;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class InterfaceTest {
  @org.junit.Rule
  public ActivityTestRule<MainActivity> activity =
      new ActivityTestRule<>(MainActivity.class, false, false);

  @org.junit.Rule
  public GrantPermissionRule notifications =
      GrantPermissionRule.grant(
          Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.SEND_SMS);

  Context context;
  Store store;
  Rule rule;

  @Before
  public void prepare() throws Exception {
    assertTrue(
        "Emulator fixtures only", Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"));
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Monitoring.stop(context);
    context.stopService(new Intent(context, MonitorService.class));
    Thread.sleep(300);
    store = new Store(context);
    store.clearHistory();
    store.prefs().edit().clear().putBoolean("monitorWanted", false).commit();
    store.engineState(new RuleEngine.State());
    rule = new Rule();
    rule.name = "My test block";
    rule.when.type = "sms_matches";
    rule.when.set("contact", "15555550100").set("matchText", "Private keyword");
    rule.then.add(Rule.action("show_message").set("message", "First test message"));
    rule.then.add(Rule.action("show_message").set("message", "Second test message"));
    store.save(List.of(rule));
    context.getSystemService(NotificationManager.class).cancelAll();
  }

  @After
  public void finish() {
    Monitoring.stop(context);
    context.stopService(new Intent(context, MonitorService.class));
  }

  private void blocks() {
    onView(withContentDescription("Blocks")).perform(click());
  }

  private void menu() {
    onView(withText("More")).perform(scrollTo(), click());
  }

  private void awaitDispatch() throws Exception {
    for (int i = 0; i < 40; i++) {
      if (store.history().stream().anyMatch(e -> "dispatched".equals(e.status))) return;
      Thread.sleep(100);
    }
    fail("Manual test did not dispatch its real actions");
  }

  @Test
  public void overviewKeepsHeroButMovesSavedFlowsToBlocks() {
    activity.launchActivity(null);
    onView(withContentDescription("Overview")).check(matches(isDisplayed()));
    onView(withText("THEN  do something")).check(matches(isDisplayed()));
    onView(withText("Your building blocks")).check(doesNotExist());
    onView(withText("My test block")).check(doesNotExist());
    blocks();
    onView(withText("Add Block")).check(matches(isDisplayed()));
    onView(withText("Explore Templates")).check(matches(isDisplayed()));
    onView(withText(FlowSummary.when(rule.when))).check(matches(isDisplayed()));
    onView(withText(FlowSummary.then(rule.then.get(1))))
        .perform(scrollTo())
        .check(matches(isDisplayed()));
    onView(withText("Edit")).perform(scrollTo(), click());
    onView(withContentDescription("Rule name")).check(matches(withText(rule.name)));
  }

  @Test
  public void cancellingRealTestDoesNotDispatch() {
    activity.launchActivity(null);
    blocks();
    menu();
    onView(withText("Test")).perform(click());
    onView(withText("Run a real test?")).check(matches(isDisplayed()));
    onView(withText("Cancel")).perform(click());
    assertFalse(
        store.history().stream()
            .anyMatch(e -> "test".equals(e.status) || "dispatched".equals(e.status)));
  }

  @Test
  public void realTestRunsActionsOnceWhileDisabledAndMonitoringStaysPaused() throws Exception {
    String rulesBefore = store.exportRules(), engineBefore = Store.json(store.engineState());
    activity.launchActivity(null);
    blocks();
    menu();
    onView(withText("Test")).perform(click());
    onView(withText("Run test")).perform(click());
    awaitDispatch();
    Thread.sleep(300);
    assertEquals(rulesBefore, store.exportRules());
    assertEquals(engineBefore, Store.json(store.engineState()));
    assertFalse(Monitoring.wanted(context));
    assertNull(MonitorService.active);
    List<String> actions = new ArrayList<>();
    for (Store.Entry e : store.history())
      if (e.title.equals(rule.name + " · Show a message") && e.status.equals("observed"))
        actions.add(e.detail);
    assertEquals(2, actions.size());
    Set<String> texts = new HashSet<>();
    for (android.service.notification.StatusBarNotification n :
        context.getSystemService(NotificationManager.class).getActiveNotifications())
      texts.add(n.getNotification().extras.getString(Notification.EXTRA_TEXT));
    assertTrue(texts.contains("First test message"));
    assertTrue(texts.contains("Second test message"));
  }

  @Test
  public void realTestUsesActiveMonitorWithoutChangingTriggerHistory() throws Exception {
    activity.launchActivity(null);
    Monitoring.resume(context, "Test setup", false);
    Thread.sleep(650);
    MonitorService before = MonitorService.active;
    assertNotNull(before);
    String engine = Store.json(store.engineState());
    activity
        .getActivity()
        .runOnUiThread(
            () ->
                context.startForegroundService(
                    new Intent(context, MonitorService.class)
                        .setAction("TEST")
                        .putExtra("ruleId", rule.id)));
    awaitDispatch();
    assertSame(before, MonitorService.active);
    assertEquals(engine, Store.json(store.engineState()));
  }

  @Test
  public void disablingAnEnabledBlockPersistsWithoutRunningIt() {
    rule.enabled = true;
    store.save(List.of(rule));
    activity.launchActivity(null);
    blocks();
    onView(withContentDescription("Enable " + rule.name)).perform(scrollTo(), click());
    assertFalse(store.rules().get(0).enabled);
    onView(withText("Disabled")).check(matches(isDisplayed()));
    assertFalse(store.history().stream().anyMatch(e -> e.status.equals("test")));
  }

  @Test
  public void setupHasCompactPermissionRowsAndNoManualChecklist() {
    activity.launchActivity(null);
    onView(withContentDescription("Setup")).perform(click());
    onView(withContentDescription("Review Notifications")).check(matches(isDisplayed()));
    onView(withText("Your real-phone checklist")).check(doesNotExist());
    onView(withText("Calls & caller ID")).perform(click());
    onView(withText("Phone state plus call log. Your installer may restrict call-log access."))
        .check(matches(isDisplayed()));
  }

  @Test
  public void failureReportKeepsStackTraceAndRedactsPrivateFields() throws Exception {
    store.failure(
        rule.name,
        new IllegalStateException(
            "Private keyword content://media/private.mp3", new SecurityException("Denied")),
        "Audio playback",
        rule);
    activity.launchActivity(null);
    onView(withContentDescription("Activity")).perform(click());
    onView(withText("Report on GitHub")).perform(scrollTo(), click());
    onView(withContentDescription("Issue details"))
        .check(
            matches(
                allOf(
                    withText(containsString("SecurityException")),
                    withText(
                        containsString(
                            BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")),
                    not(withText(containsString("Private keyword"))),
                    not(withText(containsString("private.mp3"))))));
    onView(withText("Copy details")).perform(click());
    ClipboardManager clip = context.getSystemService(ClipboardManager.class);
    assertTrue(
        clip.getPrimaryClip().getItemAt(0).getText().toString().contains("SecurityException"));
    IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
    filter.addDataScheme("https");
    Instrumentation.ActivityMonitor monitor =
        InstrumentationRegistry.getInstrumentation()
            .addMonitor(
                filter, new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
    try {
      onView(withText("Open GitHub")).perform(click());
      assertEquals(1, monitor.getHits());
    } finally {
      InstrumentationRegistry.getInstrumentation().removeMonitor(monitor);
    }
    onView(withText("Close")).perform(click());
  }

  @Test
  public void longFailureReportsUseFullCopyInsteadOfTruncatingEvidence() {
    store.failure(rule.name, new IllegalStateException("A failure"), "Audio playback", rule);
    activity.launchActivity(null);
    onView(withContentDescription("Activity")).perform(click());
    onView(withText("Report on GitHub")).perform(scrollTo(), click());
    String longReport = "Diagnostic line\n".repeat(900);
    onView(withContentDescription("Issue details")).perform(replaceText(longReport));
    onView(withText("Open GitHub")).perform(click());
    onView(withText("Full report copied")).check(matches(isDisplayed()));
    assertEquals(
        longReport,
        context
            .getSystemService(ClipboardManager.class)
            .getPrimaryClip()
            .getItemAt(0)
            .getText()
            .toString());
    onView(withText("Stay here")).perform(click());
    onView(withText("Close")).perform(click());
  }

  @Test
  public void failedActionRecordsTheExceptionAndLetsOtherActionsRun() throws Exception {
    // A media URI that cannot exist fails in the normal runner; a later notification still runs.
    Rule r = Store.copy(rule);
    r.then.clear();
    r.then.add(
        Rule.action("show_image")
            .set("uri", "content://missing.example/private-image")
            .set("message", "Private caption"));
    r.then.add(Rule.action("show_message").set("message", "Independent action"));
    ActionRunner runner = new ActionRunner(context);
    try {
      runner.run(r);
    } finally {
      runner.close();
    }
    Store.Entry failure =
        store.history().stream().filter(e -> "failed".equals(e.status)).findFirst().orElseThrow();
    assertNotNull(failure.diagnostic);
    assertTrue(failure.diagnostic.trace.contains("ActionRunner"));
    assertFalse(failure.diagnostic.trace.contains("private-image"));
    assertTrue(
        store.history().stream()
            .anyMatch(e -> "observed".equals(e.status) && e.title.endsWith("Show a message")));
  }

  @Test
  public void manualTestRetainsSmsRepeatProtectionWithoutSending() throws Exception {
    rule.then.clear();
    rule.then.add(
        Rule.action("send_sms")
            .set("recipient", "15555550101")
            .set("message", "Never send this fixture"));
    store.save(List.of(rule));
    store
        .prefs()
        .edit()
        .putLong(
            "sms_limit_" + rule.id + "_" + Rule.phone("15555550101"), System.currentTimeMillis())
        .commit();
    activity.launchActivity(null);
    activity
        .getActivity()
        .runOnUiThread(
            () ->
                context.startForegroundService(
                    new Intent(context, MonitorService.class)
                        .setAction("TEST")
                        .putExtra("ruleId", rule.id)));
    for (int i = 0;
        i < 40 && store.history().stream().noneMatch(e -> e.status.equals("partial"));
        i++) Thread.sleep(100);
    assertTrue(
        store.history().stream()
            .anyMatch(
                e -> e.status.equals("failed") && e.detail.startsWith("SMS repeat protection")));
    assertFalse(store.history().stream().anyMatch(e -> e.detail.contains("handed to the modem")));
  }

  @Test
  public void aNewManualRunnerCannotBypassEmergencySoundProtection() {
    store.prefs().edit().putLong("emergencyUntil", System.currentTimeMillis() + 60000).commit();
    rule.then.clear();
    rule.then.add(Rule.action("set_volume").set("channels", "media").set("percent", 10));
    ActionRunner runner = new ActionRunner(context);
    try {
      runner.run(rule);
    } finally {
      runner.close();
    }
    assertTrue(
        store.history().stream()
            .anyMatch(
                e ->
                    e.status.equals("failed")
                        && e.detail.startsWith("Emergency sound protection")));
  }
}
