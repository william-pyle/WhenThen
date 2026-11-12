package com.jazcosolutions.whenthen;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;
import static org.junit.Assert.*;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.*;
import android.os.Build;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.rule.GrantPermissionRule;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.junit.*;
import org.junit.runner.RunWith;

/** Isolated-emulator visual fixtures; never launch this against an owner's phone. */
@RunWith(AndroidJUnit4.class)
public class FlatBrickUiTest {
  @org.junit.Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class, false, false);
  @org.junit.Rule public GrantPermissionRule permissions = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECEIVE_SMS);
  private Context context;
  private Store store;

  @Before public void prepare() throws Exception {
    assertTrue("Emulator only", Build.MODEL.contains("sdk") || Build.MODEL.contains("Emulator"));
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    Monitoring.stop(context);
    context.stopService(new Intent(context, MonitorService.class));
    Thread.sleep(300);
    store = new Store(context);
    store.prefs().edit().clear().putBoolean("monitorWanted", false).commit();
    store.clearHistory();
    store.engineState(new RuleEngine.State());
  }

  @After public void finish() {
    Monitoring.stop(context);
    context.stopService(new Intent(context, MonitorService.class));
  }

  private Rule fixture(boolean enabled, int actions) {
    Rule rule = new Rule();
    rule.name = enabled ? "My enabled rule" : "My disabled rule";
    rule.enabled = enabled;
    rule.when.type = "sms_received";
    rule.when.set("contact", "15555550100");
    rule.then.add(Rule.action("show_message").set("message", "Remember your keys"));
    if (actions > 1) rule.then.add(Rule.action("show_message").set("message", "Take your bag"));
    return rule;
  }

  private void capture(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    Thread.sleep(700);
    Bitmap bitmap = InstrumentationRegistry.getInstrumentation().getUiAutomation().takeScreenshot();
    assertNotNull(bitmap);
    try (FileOutputStream file = new FileOutputStream(new File(context.getFilesDir(), name + ".png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
    }
  }

  @Test public void enabledTwoThenClausesAndLiveToggleKeepTheirData() throws Exception {
    Rule rule = fixture(true, 2); store.save(List.of(rule));
    activity.launchActivity(null);
    onView(withText("THEN  do something")).check(matches(isDisplayed()));
    capture("clean-overview");
    onView(withContentDescription("Blocks")).perform(click());
    onView(withText("Add Block")).check((view, error) -> {
      if (error != null) throw error;
      assertFalse("Standard button must not draw studs", view.getBackground() instanceof BlockDrawable);
    });
    onView(withText("THEN 2")).perform(scrollTo()).check(matches(isDisplayed()));
    onView(withContentDescription("Enable " + rule.name)).perform(scrollTo());
    capture("clean-blocks-enabled");
    onView(withContentDescription("Enable " + rule.name)).perform(click());
    assertFalse(store.rules().get(0).enabled);
    assertEquals(2, store.rules().get(0).then.size());
    assertTrue(store.history().stream().noneMatch(entry -> "test".equals(entry.status)));
    onView(withText("Disabled")).check(matches(isDisplayed()));
    capture("clean-blocks-toggled");
  }

  @Test public void disabledSingleThenAndOtherScreensRemainEditable() throws Exception {
    Rule rule=fixture(false,1);store.save(List.of(rule));activity.launchActivity(null);
    onView(withContentDescription("Blocks")).perform(click());
    onView(withText("Edit")).perform(scrollTo());capture("clean-blocks-disabled");
    onView(withText("More")).perform(click());capture("clean-more");
    androidx.test.espresso.Espresso.pressBack();
    onView(withText("Edit")).perform(click());
    onView(withContentDescription("Rule name")).check(matches(withText(rule.name)));capture("clean-builder");
    onView(withText("Save my rule")).perform(scrollTo(),click());
    assertFalse(store.rules().get(0).enabled);
    Thread.sleep(2400);
    onView(withContentDescription("Blocks")).perform(click());
    onView(withText("Explore Templates")).perform(click());capture("clean-templates");
    onView(withContentDescription("Setup")).perform(click());capture("clean-setup");
    onView(withContentDescription("Activity")).perform(click());capture("clean-activity");
  }

  @Test public void drawableHasFlatFillBlackEdgesAndTransparentStudGaps() {
    for(int color:new int[]{BrickStyle.WHEN,BrickStyle.THEN,BrickStyle.BLUE,BrickStyle.ENABLED,BrickStyle.DISABLED}) {
      Bitmap image=Bitmap.createBitmap(280,90,Bitmap.Config.ARGB_8888);
      BlockDrawable drawable=new BlockDrawable(color,1);drawable.setBounds(0,0,280,90);drawable.draw(new Canvas(image));
      assertEquals(Color.TRANSPARENT,image.getPixel(2,2));
      assertEquals(color,image.getPixel(140,50));
      assertEquals(color,image.getPixel(90,80));
      assertEquals(Color.BLACK,image.getPixel(140,89));
      int studs=0;boolean previous=false;
      for(int x=0;x<280;x++){boolean filled=Color.alpha(image.getPixel(x,5))>200;if(filled&&!previous)studs++;previous=filled;}
      assertEquals("Responsive rectangular studs",5,studs);
    }
    Bitmap icon=BitmapFactory.decodeResource(context.getResources(),R.drawable.icon_art);
    assertEquals(Color.TRANSPARENT,icon.getPixel(0,0));
    assertEquals(Color.TRANSPARENT,icon.getPixel(icon.getWidth()-1,icon.getHeight()-1));
  }
}
