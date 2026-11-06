package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class RuleTemplateTest {
  private String starters() throws Exception {
    try (var input = getClass().getClassLoader().getResourceAsStream("templates.json")) {
      assertNotNull(input);
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  @Test
  public void shipsFourExistingStartersWithOriginalDefaults() throws Exception {
    List<RuleTemplate> templates = RuleTemplate.decode(starters());
    assertEquals(4, templates.size());
    Rule calls = templates.get(0).instantiate();
    assertEquals("Get through to me", calls.name);
    assertEquals("3", calls.when.get("count"));
    assertEquals("5", calls.when.get("minutes"));
    assertEquals(10, calls.priority);
    assertEquals("emergency_loud", calls.then.get(0).type);
    assertEquals("Someone is trying to reach you.", calls.then.get(1).get("message"));
    assertEquals("HELP", templates.get(2).rule.when.get("matchText"));
    assertEquals("250", templates.get(3).rule.when.get("radiusFeet"));
  }

  @Test
  public void instancesAreIndependentPausedDrafts() throws Exception {
    RuleTemplate t = RuleTemplate.decode(starters()).get(0);
    Rule first = t.instantiate(), second = t.instantiate();
    first.when.set("contact", "5551234");
    first.then.clear();
    assertNotEquals(first.id, second.id);
    assertEquals("", second.when.get("contact"));
    assertEquals(2, t.rule.then.size());
    assertFalse(first.enabled);
  }

  @Test
  public void exportImportRoundtripPreservesFlowAndDefaults() throws Exception {
    List<RuleTemplate> templates = RuleTemplate.decode(starters());
    String exported = RuleTemplate.encode(templates);
    assertEquals(exported, RuleTemplate.encode(RuleTemplate.decode(exported)));
  }

  @Test
  public void savingTemplateClearsPersonalFieldsWithoutChangingRule() {
    Rule r = new Rule();
    r.name = "Private rule";
    r.enabled = true;
    r.when.set("contact", "5551234").set("contactName", "Private name");
    r.then.add(Rule.action("send_sms").set("recipient", "5557890").set("message", "Hello"));
    RuleTemplate t = RuleTemplate.fromRule(r, "Reusable flow");
    assertEquals("", t.rule.when.get("contact"));
    assertEquals("", t.rule.then.get(0).get("recipient"));
    assertEquals("Hello", t.rule.then.get(0).get("message"));
    assertEquals("5551234", r.when.get("contact"));
    assertTrue(r.enabled);
  }

  @Test
  public void importNeverRetainsEnabledState() throws Exception {
    JsonObject document = JsonParser.parseString(starters()).getAsJsonObject();
    document
        .getAsJsonArray("templates")
        .get(0)
        .getAsJsonObject()
        .getAsJsonObject("rule")
        .addProperty("enabled", true);
    assertFalse(RuleTemplate.decode(document.toString()).get(0).rule.enabled);
  }

  @Test
  public void rejectsUnknownActions() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> RuleTemplate.decode(starters().replace("emergency_loud", "run_shell")));
  }

  @Test
  public void rejectsFutureSchemaAndDuplicateIds() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> RuleTemplate.decode(starters().replace("templates/1", "templates/2")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RuleTemplate.decode(
                starters().replace("starter-welcome-home", "starter-repeated-calls")));
  }

  @Test
  public void rejectsInvalidNumbersAndMalformedOptions() throws Exception {
    assertThrows(
        IllegalArgumentException.class,
        () -> RuleTemplate.decode(starters().replace("\"count\": \"3\"", "\"count\": \"NaN\"")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RuleTemplate.decode(starters().replace("\"channels\": \"all\"", "\"channels\": null")));
  }

  @Test
  public void rejectsOversizedAndEmptyDocuments() {
    assertThrows(IllegalArgumentException.class, () -> RuleTemplate.decode("x".repeat(1_000_001)));
    assertThrows(
        IllegalArgumentException.class,
        () -> RuleTemplate.decode("{\"schema\":\"whenthen-templates/1\",\"templates\":[]}"));
  }

  @Test
  public void malformedImportCannotBecomeExecutableRule() {
    for (String value :
        List.of(
            "null",
            "[]",
            "{}",
            "{bad json",
            "{\"schema\":\"whenthen-templates/1\",\"templates\":[null]}"))
      assertThrows(IllegalArgumentException.class, () -> RuleTemplate.decode(value));
  }
}
