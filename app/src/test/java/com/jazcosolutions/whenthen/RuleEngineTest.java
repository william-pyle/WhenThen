package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import com.google.gson.Gson;
import java.util.*;
import org.junit.Test;

public class RuleEngineTest {
  final long now = 1800000000000L;

  Rule rule(String type) {
    Rule r = new Rule();
    r.name = "Test";
    r.enabled = true;
    r.when.type = type;
    r.when
        .set("contact", "+1 (312) 555-0100")
        .set("count", 3)
        .set("minutes", 5)
        .set("ssid", "Home")
        .set("matchText", " HELP ")
        .set("waypoint", "Home")
        .set("latitude", 40)
        .set("longitude", -88)
        .set("radiusFeet", 250);
    r.then.add(Rule.action("show_message").set("message", "Hello"));
    return r;
  }

  RuleEngine.Event event(String type, int offset) {
    RuleEngine.Event e = new RuleEngine.Event(type + offset, type, now + offset);
    e.contact = "3125550100";
    e.ssid = "Home";
    e.text = " HELP ";
    return e;
  }

  List<Rule> accept(RuleEngine engine, Rule rule, RuleEngine.Event event) {
    return engine.accept(List.of(rule), event, event.at);
  }

  @Test
  public void validatesAllTriggerTypes() {
    for (String type : Rule.TRIGGERS) assertEquals(type, List.of(), rule(type).errors());
  }

  @Test
  public void disabledRulesNeverFire() {
    Rule r = rule("call_incoming");
    r.enabled = false;
    assertTrue(accept(new RuleEngine(), r, event("call_incoming", 0)).isEmpty());
  }

  @Test
  public void matchesNormalizedPhone() {
    assertEquals(
        1, accept(new RuleEngine(), rule("call_incoming"), event("call_incoming", 0)).size());
  }

  @Test
  public void unknownCallerDoesNotMatch() {
    RuleEngine.Event e = event("call_incoming", 0);
    e.contact = "";
    assertTrue(accept(new RuleEngine(), rule("call_incoming"), e).isEmpty());
  }

  @Test
  public void internationalNumbersNotMatchedByArbitrarySuffix() {
    assertFalse(Rule.samePhone("+44 20 1234 5678", "+33 20 1234 5678"));
    assertFalse(Rule.samePhone("12345678", "442012345678"));
  }

  @Test
  public void missedIsDistinctFromIncoming() {
    assertTrue(accept(new RuleEngine(), rule("call_missed"), event("call_incoming", 0)).isEmpty());
    assertEquals(1, accept(new RuleEngine(), rule("call_missed"), event("call_missed", 0)).size());
  }

  @Test
  public void duplicateEventsDoNotFireTwice() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("call_incoming");
    RuleEngine.Event v = event("call_incoming", 0);
    assertEquals(1, accept(e, r, v).size());
    assertTrue(e.accept(List.of(r), v, now + 120000).isEmpty());
  }

  @Test
  public void cooldownEnforced() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("sms_received");
    assertEquals(1, accept(e, r, event("sms_received", 0)).size());
    assertTrue(accept(e, r, event("sms_received", 30000)).isEmpty());
    assertEquals(1, accept(e, r, event("sms_received", 60000)).size());
  }

  @Test
  public void exactSmsPreservesCaseAndWhitespace() {
    Rule r = rule("sms_matches");
    assertEquals(1, accept(new RuleEngine(), r, event("sms_received", 0)).size());
    RuleEngine.Event e = event("sms_received", 1);
    e.text = "HELP";
    assertTrue(accept(new RuleEngine(), r, e).isEmpty());
    e.text = " help ";
    assertTrue(accept(new RuleEngine(), r, e).isEmpty());
  }

  @Test
  public void countFiresAtThresholdNotEveryCall() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("call_count");
    assertTrue(accept(e, r, event("call_incoming", 0)).isEmpty());
    assertTrue(accept(e, r, event("call_incoming", 10000)).isEmpty());
    assertEquals(1, accept(e, r, event("call_incoming", 20000)).size());
    assertTrue(accept(e, r, event("call_incoming", 80000)).isEmpty());
  }

  @Test
  public void countRearmsAfterWindow() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("call_count");
    for (int i = 0; i < 3; i++) accept(e, r, event("call_incoming", i * 10000));
    assertTrue(accept(e, r, event("call_incoming", 400000)).isEmpty());
    assertTrue(accept(e, r, event("call_incoming", 410000)).isEmpty());
    assertEquals(1, accept(e, r, event("call_incoming", 420000)).size());
  }

  @Test
  public void duplicateCallDoesNotIncreaseCount() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("call_count");
    for (int i = 0; i < 8; i++) assertTrue(accept(e, r, event("call_incoming", 0)).isEmpty());
    assertTrue(accept(e, r, event("call_incoming", 10000)).isEmpty());
    assertEquals(1, accept(e, r, event("call_incoming", 20000)).size());
  }

  @Test
  public void wrongContactDoesNotContribute() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("call_count");
    for (int i = 0; i < 2; i++) {
      RuleEngine.Event v = event("call_incoming", i);
      v.contact = "5559999";
      accept(e, r, v);
    }
    assertTrue(accept(e, r, event("call_incoming", 1000)).isEmpty());
  }

  @Test
  public void wifiIsExactCaseSensitiveAndTypeSpecific() {
    Rule r = rule("wifi_connected");
    RuleEngine.Event e = event("wifi_connected", 0);
    assertEquals(1, accept(new RuleEngine(), r, e).size());
    e.ssid = "home";
    assertTrue(accept(new RuleEngine(), r, e).isEmpty());
    assertTrue(accept(new RuleEngine(), r, event("wifi_disconnected", 1)).isEmpty());
  }

  @Test
  public void staleAndFutureEventsRejected() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("call_incoming");
    assertTrue(e.accept(List.of(r), event("call_incoming", -120001), now).isEmpty());
    assertTrue(e.accept(List.of(r), event("call_incoming", 5001), now).isEmpty());
  }

  RuleEngine.Event loc(int offset, boolean inside) {
    RuleEngine.Event e = event("location", offset);
    e.latitude = inside ? 40 : 40.003;
    e.longitude = -88;
    e.accuracy = 5;
    return e;
  }

  @Test
  public void locationEstablishesBaselineWithoutFiring() {
    assertTrue(accept(new RuleEngine(), rule("waypoint_enter"), loc(0, true)).isEmpty());
  }

  @Test
  public void locationNeedsTwoReliableReadingsTenSecondsApart() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("waypoint_enter");
    accept(e, r, loc(0, false));
    assertTrue(accept(e, r, loc(1000, true)).isEmpty());
    assertTrue(accept(e, r, loc(5000, true)).isEmpty());
    assertEquals(1, accept(e, r, loc(11000, true)).size());
    assertTrue(accept(e, r, loc(90000, true)).isEmpty());
  }

  @Test
  public void locationExitWorks() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("waypoint_exit");
    accept(e, r, loc(0, true));
    accept(e, r, loc(15000, false));
    assertEquals(1, accept(e, r, loc(30000, false)).size());
  }

  @Test
  public void poorAccuracyIgnored() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("waypoint_enter");
    accept(e, r, loc(0, false));
    RuleEngine.Event v = loc(15000, true);
    v.accuracy = 100;
    assertTrue(accept(e, r, v).isEmpty());
    assertTrue(accept(e, r, loc(30000, true)).isEmpty());
  }

  @Test
  public void jitterResetsConfirmation() {
    RuleEngine e = new RuleEngine();
    Rule r = rule("waypoint_enter");
    accept(e, r, loc(0, false));
    accept(e, r, loc(15000, true));
    accept(e, r, loc(20000, false));
    assertTrue(accept(e, r, loc(30000, true)).isEmpty());
    assertEquals(1, accept(e, r, loc(45000, true)).size());
  }

  @Test
  public void emergencyRunsLast() {
    Rule a = rule("call_incoming"), b = rule("call_incoming");
    a.priority = 10;
    assertEquals(a, new RuleEngine().accept(List.of(a, b), event("call_incoming", 0), now).get(1));
  }

  @Test
  public void stateRoundTripRetainsCooldownAndDedupe() {
    Rule r = rule("call_incoming");
    RuleEngine e = new RuleEngine();
    accept(e, r, event("call_incoming", 0));
    RuleEngine reload =
        new RuleEngine(new Gson().fromJson(new Gson().toJson(e.state), RuleEngine.State.class));
    assertTrue(accept(reload, r, event("call_incoming", 1000)).isEmpty());
    assertTrue(accept(reload, r, event("call_incoming", 0)).isEmpty());
  }

  @Test
  public void invalidNumbersRejected() {
    Rule r = rule("call_count");
    for (String invalid : new String[] {"NaN", "Infinity", "0", "1.5", "1000", ""}) {
      r.when.set("count", invalid);
      assertFalse(invalid, r.errors().isEmpty());
    }
  }

  @Test
  public void invalidCoordinatesRejected() {
    Rule r = rule("waypoint_enter");
    r.when.set("latitude", 91);
    assertFalse(r.errors().isEmpty());
    r.when.set("latitude", "NaN");
    assertFalse(r.errors().isEmpty());
  }

  @Test
  public void actionsValidateRequiredOptions() {
    for (String t : Rule.ACTIONS) {
      Rule r = rule("call_incoming");
      Rule.Piece a =
          Rule.action(t)
              .set("message", "hello")
              .set("recipient", "3125550101")
              .set("uri", "content://test/file");
      r.then = List.of(a);
      assertTrue(t, r.errors().isEmpty());
    }
    Rule r = rule("call_incoming");
    r.then = List.of(Rule.action("play_audio").set("uri", "file:///private/key"));
    assertFalse(r.errors().isEmpty());
  }

  @Test
  public void unknownAndEmptyActionsRejected() {
    Rule r = rule("call_incoming");
    r.then.clear();
    assertFalse(r.errors().isEmpty());
    r.then.add(new Rule.Piece("unknown"));
    assertFalse(r.errors().isEmpty());
  }

  @Test
  public void maximumVolumeValidated() {
    Rule r = rule("call_incoming");
    r.then = List.of(Rule.action("set_volume").set("percent", 101));
    assertFalse(r.errors().isEmpty());
  }

  @Test
  public void immutableSimulationCopy() {
    Rule r = rule("sms_matches");
    Rule copy = new Gson().fromJson(new Gson().toJson(r), Rule.class);
    copy.when.set("matchText", "other");
    assertEquals(" HELP ", r.when.get("matchText"));
  }

  @Test
  public void distanceSanity() {
    assertEquals(0, RuleEngine.distanceMeters(40, -88, 40, -88), 0.001);
    assertEquals(111195, RuleEngine.distanceMeters(0, 0, 1, 0), 100);
  }
}
