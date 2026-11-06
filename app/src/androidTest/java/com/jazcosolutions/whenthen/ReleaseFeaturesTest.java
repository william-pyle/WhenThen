package com.jazcosolutions.whenthen;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ReleaseFeaturesTest {
  @org.junit.Rule
  public ActivityTestRule<MainActivity> activity =
      new ActivityTestRule<>(MainActivity.class, false, false);

  @org.junit.Rule
  public GrantPermissionRule permissions =
      GrantPermissionRule.grant(
          Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECEIVE_SMS);

  private Context context;
  private Store store;

  @Before
  public void before() throws Exception {
    assertTrue(
        "Emulator-only fixtures", Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"));
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Monitoring.stop(context);
    Thread.sleep(350);
    context.stopService(new Intent(context, MonitorService.class));
    store = new Store(context);
    store.save(new ArrayList<>());
    store.prefs().edit().clear().commit();
    store.clearHistory();
    store.engineState(new RuleEngine.State());
    context.deleteFile("templates.json");
  }

  @After
  public void after() throws Exception {
    Monitoring.stop(context);
    Thread.sleep(250);
    context.deleteFile("templates.json");
  }

  private Rule sample() {
    Rule rule = new Rule();
    rule.name = "Keep my enabled rule";
    rule.enabled = true;
    rule.when.type = "sms_received";
    rule.when.set("contact", "15555550100");
    rule.then.add(Rule.action("show_message").set("message", "Before edit"));
    return rule;
  }

  @Test
  public void firstLaunchAutomaticallyMonitorsEvenBeforeFirstRule() throws Exception {
    activity.launchActivity(null);
    Thread.sleep(800);
    assertNotNull(MonitorService.active);
    assertTrue(Monitoring.wanted(context));
    assertTrue(store.rules().isEmpty());
  }

  @Test
  public void stopSurvivesReopenButUpdateRestarts() throws Exception {
    activity.launchActivity(null);
    Thread.sleep(650);
    Monitoring.stop(context);
    Thread.sleep(350);
    activity.finishActivity();
    activity.launchActivity(null);
    Thread.sleep(650);
    assertNull(MonitorService.active);
    new BootReceiver().onReceive(context, new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));
    Thread.sleep(800);
    assertNotNull(MonitorService.active);
    assertTrue(store.history().stream().anyMatch(e -> e.detail.startsWith("App updated")));
  }

  private void editWhile(boolean running) throws Exception {
    Rule saved = sample();
    store.save(List.of(saved));
    activity.launchActivity(null);
    Thread.sleep(700);
    if (!running) {
      Monitoring.stop(context);
      Thread.sleep(350);
    }
    onView(withText("Edit blocks")).perform(scrollTo(), click());
    onView(withContentDescription("Message to show"))
        .perform(scrollTo(), replaceText("After edit"));
    onView(withText("Save my rule")).perform(scrollTo(), click());
    Thread.sleep(800);
    assertNotNull(MonitorService.active);
    Rule current = store.rules().get(0);
    assertTrue(current.enabled);
    assertEquals(saved.id, current.id);
    assertEquals("After edit", current.then.get(0).get("message"));
    RuleEngine.Event event =
        new RuleEngine.Event("edited-rule", "sms_received", System.currentTimeMillis());
    event.contact = "15555550100";
    MonitorService.deliver(context, event);
    Thread.sleep(500);
    assertTrue(
        store.history().stream()
            .anyMatch(e -> e.title.equals(saved.name) && e.status.equals("dispatched")));
  }

  @Test
  public void editEnabledRuleWhileRunningAppliesNewRuleAutomatically() throws Exception {
    editWhile(true);
  }

  @Test
  public void editEnabledRuleWhileStoppedRestartsAutomatically() throws Exception {
    editWhile(false);
  }

  @Test
  public void libraryRoundtripPreservesRulesAndRejectsBrokenImportAtomically() throws Exception {
    Rule saved = sample();
    store.save(List.of(saved));
    TemplateLibrary library = new TemplateLibrary(context);
    RuleTemplate template = RuleTemplate.fromRule(saved, "My reusable alert");
    assertEquals(1, library.add(List.of(template)));
    assertEquals(5, new TemplateLibrary(context).all().size());
    assertEquals(0, library.add(RuleTemplate.decode(RuleTemplate.encode(library.all()))));
    assertEquals(5, library.all().size());
    assertThrows(IllegalArgumentException.class, () -> library.add(RuleTemplate.decode("{}")));
    assertEquals(5, library.all().size());
    assertTrue(store.rules().get(0).enabled);
    assertEquals(saved.id, store.rules().get(0).id);
    Rule draft = library.all().get(4).instantiate();
    draft.then.clear();
    assertEquals(1, library.all().get(4).rule.then.size());
  }

  @Test
  public void combinedImportsCannotMakeTheLibraryUnreadable() {
    TemplateLibrary library = new TemplateLibrary(context);
    List<RuleTemplate> large = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      Rule rule = sample();
      rule.then.clear();
      for (int j = 0; j < 20; j++)
        rule.then.add(Rule.action("show_message").set("message", "\u65e5".repeat(4000)));
      large.add(RuleTemplate.fromRule(rule, "Large portable flow " + i));
    }
    assertEquals(3, library.add(large.subList(0, 3)));
    assertThrows(IllegalArgumentException.class, () -> library.add(large.subList(3, 5)));
    assertEquals(7, new TemplateLibrary(context).all().size());
    assertEquals(7, RuleTemplate.decode(RuleTemplate.encode(library.all())).size());
  }

  @Test
  public void saveAsTemplateThroughUiKeepsOriginalRule() throws Exception {
    Rule saved = sample();
    store.save(List.of(saved));
    activity.launchActivity(null);
    onView(withText("More")).perform(scrollTo(), click());
    onView(withText("Save as template")).perform(click());
    onView(withContentDescription("Template name")).perform(replaceText("UI reusable flow"));
    onView(withText("Save template")).perform(click());
    assertEquals(5, new TemplateLibrary(context).all().size());
    assertEquals(saved.id, store.rules().get(0).id);
    assertEquals("15555550100", store.rules().get(0).when.get("contact"));
  }

  @Test
  public void documentImportAndExportActuallyReadAndWriteJson() throws Exception {
    TemplateLibrary library = new TemplateLibrary(context);
    RuleTemplate template = RuleTemplate.fromRule(sample(), "Imported without a rebuild");
    ContentValues values = new ContentValues();
    values.put(MediaStore.Downloads.DISPLAY_NAME, "WhenThen-template-test.json");
    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
    Uri uri =
        context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    assertNotNull(uri);
    try {
      try (var output = context.getContentResolver().openOutputStream(uri, "wt")) {
        output.write(RuleTemplate.encode(List.of(template)).getBytes(StandardCharsets.UTF_8));
      }
      activity.launchActivity(null);
      Thread.sleep(650);
      InstrumentationRegistry.getInstrumentation()
          .runOnMainSync(
              () ->
                  activity
                      .getActivity()
                      .onActivityResult(104, Activity.RESULT_OK, new Intent().setData(uri)));
      onView(withText("Add templates")).inRoot(isDialog()).perform(click());
      assertEquals(5, library.all().size());
      assertTrue(store.rules().isEmpty());
      var instrumentation = InstrumentationRegistry.getInstrumentation();
      IntentFilter createDocument = new IntentFilter(Intent.ACTION_CREATE_DOCUMENT);
      createDocument.addCategory(Intent.CATEGORY_OPENABLE);
      createDocument.addDataType("application/json");
      var monitor =
          instrumentation.addMonitor(
              createDocument,
              new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
              true);
      try {
        onView(withText("Export library")).perform(scrollTo(), click());
        onView(withText("Export")).inRoot(isDialog()).perform(click());
        assertEquals(1, monitor.getHits());
        instrumentation.runOnMainSync(
            () ->
                activity
                    .getActivity()
                    .onActivityResult(102, Activity.RESULT_OK, new Intent().setData(uri)));
        try (var input = context.getContentResolver().openInputStream(uri)) {
          assertEquals(5, RuleTemplate.decode(TemplateLibrary.read(input)).size());
        }
      } finally {
        instrumentation.removeMonitor(monitor);
      }
    } finally {
      context.getContentResolver().delete(uri, null, null);
    }
  }
}
