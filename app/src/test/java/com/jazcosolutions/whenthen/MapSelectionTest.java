package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import org.junit.Test;

public class MapSelectionTest {
  @Test
  public void acceptsOnlySupportedWholeFootBoundaries() {
    for (String valid : new String[] {"50", "250", " 500 ", "100000"})
      assertTrue(MapSelection.radius(valid) > 0);
    for (String invalid :
        new String[] {null, "", "49", "100001", "NaN", "250.5", "-250", "999999999999"})
      assertEquals(-1, MapSelection.radius(invalid));
  }

  @Test
  public void rejectsMissingOrImpossiblePinsButAcceptsZero() {
    assertTrue(MapSelection.validPoint(0, 0));
    assertTrue(MapSelection.validPoint(-90, 180));
    assertFalse(MapSelection.validPoint(Double.NaN, 0));
    assertFalse(MapSelection.validPoint(0, Double.POSITIVE_INFINITY));
    assertFalse(MapSelection.validPoint(91, 0));
    assertFalse(MapSelection.validPoint(0, -181));
  }

  @Test
  public void unselectedRuleExplainsTheMapInsteadOfCoordinateEntry() {
    Rule rule = new Rule();
    rule.name = "Leave the park";
    rule.when.type = "waypoint_exit";
    rule.when.set("waypoint", "Park").set("radiusFeet", "250");
    rule.then.add(Rule.action("show_message").set("message", "Keys?"));
    assertEquals(1, rule.errors().size());
    assertTrue(rule.errors().get(0).contains("map"));
    rule.when.set("latitude", 40).set("longitude", -88);
    assertTrue(rule.errors().isEmpty());
  }
}
