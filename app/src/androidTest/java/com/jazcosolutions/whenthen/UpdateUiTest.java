package com.jazcosolutions.whenthen;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import com.google.gson.JsonObject;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class UpdateUiTest {
  @org.junit.Rule
  public ActivityTestRule<MainActivity> activity =
      new ActivityTestRule<>(MainActivity.class, false, false);

  private Context context;

  @Before
  public void prepare() {
    assertTrue(
        "Updater UI fixtures are emulator-only",
        Build.FINGERPRINT.contains("generic")
            || Build.MODEL.contains("sdk")
            || Build.MODEL.contains("Emulator"));
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context
        .getSharedPreferences("app_updates", 0)
        .edit()
        .clear()
        .putLong("last_attempt", System.currentTimeMillis())
        .commit();
    activity.launchActivity(null);
  }

  @After
  public void cleanup() {
    context
        .getSharedPreferences("app_updates", 0)
        .edit()
        .clear()
        .putLong("last_attempt", System.currentTimeMillis())
        .commit();
  }

  private UpdateRelease release() {
    JsonObject value = new JsonObject();
    value.addProperty("schema", 1);
    value.addProperty("applicationId", context.getPackageName());
    value.addProperty("versionName", "9.0.0");
    value.addProperty("versionCode", 9999);
    value.addProperty("size", 1000);
    value.addProperty("sha256", "a".repeat(64));
    value.addProperty(
        "apkUrl", UpdateRelease.REPOSITORY + "/releases/download/v9.0.0/WhenThen.apk");
    value.addProperty("notes", "Updater test release");
    return UpdateRelease.parse(value.toString(), "v9.0.0");
  }

  private void controller(UpdateClient client) throws Exception {
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(
            () -> {
              MainActivity screen = activity.getActivity();
              screen.updates.close();
              screen.updates = new AppUpdater(screen, () -> true, () -> {}, client);
              screen.updates.onResume();
              screen.updates.check(true);
            });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    Thread.sleep(400);
  }

  @Test
  public void laterDefersWithoutChangingRulesAndManualCheckOverrides() throws Exception {
    String before = new Store(context).exportRules();
    UpdateClient client =
        new UpdateClient() {
          @Override
          UpdateRelease latest() {
            return release();
          }
        };
    controller(client);
    onView(withText("WhenThen 9.0.0 is available")).check(matches(isDisplayed()));
    onView(withText("Later")).perform(click());
    assertEquals(9999, context.getSharedPreferences("app_updates", 0).getLong("later_version", 0));
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> activity.getActivity().updates.onResume());
    onView(withText("WhenThen 9.0.0 is available")).check(doesNotExist());
    InstrumentationRegistry.getInstrumentation()
        .runOnMainSync(() -> activity.getActivity().updates.check(true));
    Thread.sleep(400);
    onView(withText("Update now")).check(matches(isDisplayed()));
    assertEquals(before, new Store(context).exportRules());
  }

  @Test
  public void updateExplainsRequiredInstallPermission() throws Exception {
    Assume.assumeFalse(context.getPackageManager().canRequestPackageInstalls());
    controller(
        new UpdateClient() {
          @Override
          UpdateRelease latest() {
            return release();
          }
        });
    onView(withText("Update now")).perform(click());
    onView(withText("Allow WhenThen updates")).check(matches(isDisplayed()));
    onView(withText("Open settings")).check(matches(isDisplayed()));
    onView(withText("Later")).perform(click());
  }

  @Test
  public void offlineCheckKeepsExistingRules() throws Exception {
    String before = new Store(context).exportRules();
    controller(
        new UpdateClient() {
          @Override
          UpdateRelease latest() throws IOException {
            throw new IOException("Offline test");
          }
        });
    onView(withText("Couldn't check for updates")).check(matches(isDisplayed()));
    assertEquals(before, new Store(context).exportRules());
  }

  @Test
  public void noReleaseShowsCurrentVersion() throws Exception {
    controller(
        new UpdateClient() {
          @Override
          UpdateRelease latest() {
            return null;
          }
        });
    onView(withText("You're up to date")).check(matches(isDisplayed()));
  }

  @Test
  public void updateProviderCannotWriteOrReadOtherPaths() throws Exception {
    ContentResolver resolver = context.getContentResolver();
    Uri other = Uri.parse("content://" + context.getPackageName() + ".updates/../files/rules.json");
    assertThrows(FileNotFoundException.class, () -> resolver.openFileDescriptor(other, "r"));
    Uri apk = Uri.parse("content://" + context.getPackageName() + ".updates/WhenThen.apk");
    assertThrows(FileNotFoundException.class, () -> resolver.openFileDescriptor(apk, "w"));
  }
}
