package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import org.junit.Test;

public class FlowSummaryTest {
  @Test
  public void requirementIncludesCountWindowAndExactMessageWhitespace() {
    Rule.Piece p =
        new Rule.Piece("call_count")
            .set("contact", "15555550100")
            .set("count", 3)
            .set("minutes", 5);
    assertTrue(FlowSummary.when(p).contains("3 times within 5 minutes"));
    p.type = "sms_matches";
    p.set("matchText", " HELP ");
    assertTrue(FlowSummary.when(p).contains("“ HELP ”"));
  }

  @Test
  public void placeSummaryUsesNameAndFeetWithoutCoordinates() {
    Rule.Piece p =
        new Rule.Piece("waypoint_exit")
            .set("waypoint", "Park")
            .set("radiusFeet", 250)
            .set("latitude", 38.123456);
    String s = FlowSummary.when(p);
    assertTrue(s.contains("Leave Park"));
    assertTrue(s.contains("250 ft radius"));
    assertFalse(s.contains("38.123456"));
  }

  @Test
  public void confirmationNamesRealSmsRecipientAndPreservesActionOrder() {
    Rule r = new Rule();
    r.when.type = "sms_received";
    r.when.set("contact", "15555550100");
    r.then.add(Rule.action("show_message").set("message", "First"));
    r.then.add(Rule.action("send_sms").set("recipient", "15555550101").set("message", "Second"));
    String s = FlowSummary.testDescription(r);
    assertTrue(s.indexOf("First") < s.indexOf("Second"));
    assertTrue(s.contains("15555550101"));
    assertTrue(s.contains("real actions"));
    assertFalse(r.enabled);
  }
}
