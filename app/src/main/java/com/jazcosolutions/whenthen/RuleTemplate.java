package com.jazcosolutions.whenthen;

import com.google.gson.*;
import java.util.*;

/** Portable, validated drafts. Importing a template never enables or executes a rule. */
public final class RuleTemplate {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  public static final String SCHEMA = "whenthen-templates/1";
  public String id, name, description = "";
  public Rule rule;

  public static class Document {
    public String schema = SCHEMA;
    public List<RuleTemplate> templates = new ArrayList<>();
  }

  public Rule instantiate() {
    Rule copy = JSON.fromJson(JSON.toJson(rule), Rule.class);
    copy.id = UUID.randomUUID().toString();
    copy.name = name;
    copy.enabled = false;
    return copy;
  }

  public static RuleTemplate fromRule(Rule source, String name) {
    RuleTemplate template = new RuleTemplate();
    template.id = "custom-" + UUID.randomUUID();
    template.name = name.trim();
    template.description = "Choose your contacts, places and files before enabling this flow.";
    template.rule = JSON.fromJson(JSON.toJson(source), Rule.class);
    template.rule.id = "template";
    template.rule.enabled = false;
    template.rule.name = template.name;
    for (Rule.Piece p : pieces(template.rule))
      for (String key :
          List.of(
              "contact",
              "contactName",
              "recipient",
              "ssid",
              "waypoint",
              "latitude",
              "longitude",
              "uri",
              "fileName",
              "subscription")) p.options.remove(key);
    validate(template);
    return template;
  }

  public static String encode(List<RuleTemplate> templates) {
    Document document = new Document();
    document.templates.addAll(templates);
    return JSON.toJson(document);
  }

  public static List<RuleTemplate> decode(String text) {
    if (text == null || text.length() > 1_000_000)
      throw new IllegalArgumentException("Template file is too large (maximum 1 MB).");
    try {
      JsonObject object = JsonParser.parseString(text).getAsJsonObject();
      if (!object.has("schema") || !SCHEMA.equals(object.get("schema").getAsString()))
        throw new IllegalArgumentException("Choose a WhenThen template library (" + SCHEMA + ").");
      JsonArray items = object.getAsJsonArray("templates");
      if (items == null || items.isEmpty() || items.size() > 100)
        throw new IllegalArgumentException("A template file must contain 1–100 templates.");
      List<RuleTemplate> result = new ArrayList<>();
      Set<String> ids = new HashSet<>();
      for (JsonElement item : items) {
        RuleTemplate template = JSON.fromJson(item, RuleTemplate.class);
        validate(template);
        if (!ids.add(template.id)) throw new IllegalArgumentException("Duplicate template ID.");
        result.add(template);
      }
      return result;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new IllegalArgumentException("Invalid template JSON. Your library has not changed.", e);
    }
  }

  private static List<Rule.Piece> pieces(Rule rule) {
    List<Rule.Piece> pieces = new ArrayList<>();
    pieces.add(rule.when);
    pieces.addAll(rule.then);
    return pieces;
  }

  private static void validate(RuleTemplate template) {
    if (template == null
        || template.id == null
        || !template.id.matches("[A-Za-z0-9_-]{1,80}")
        || template.name == null
        || template.name.isBlank()
        || template.name.length() > 120
        || template.description == null
        || template.description.length() > 600)
      throw new IllegalArgumentException(
          "Each template needs an ID, a name up to 120 characters, and a short description.");
    Rule rule = template.rule;
    if (rule == null
        || rule.when == null
        || rule.then == null
        || rule.then.isEmpty()
        || rule.then.size() > 20)
      throw new IllegalArgumentException("Each template needs one WHEN and 1–20 THEN blocks.");
    for (Rule.Piece p : pieces(rule)) {
      if (p == null || p.type == null || p.options == null || p.options.size() > 30)
        throw new IllegalArgumentException("Invalid template block.");
      for (Map.Entry<String, String> option : p.options.entrySet())
        if (option.getKey() == null
            || option.getKey().length() > 60
            || option.getValue() == null
            || option.getValue().length() > 4000)
          throw new IllegalArgumentException("Template options must be short text values.");
    }
    rule.id = "template";
    rule.name = template.name;
    rule.enabled = false;
    rule.when.options.putIfAbsent("count", "3");
    rule.when.options.putIfAbsent("minutes", "5");
    rule.when.options.putIfAbsent("radiusFeet", "250");
    for (Rule.Piece p : rule.then) Rule.action(p.type).options.forEach(p.options::putIfAbsent);
    // Personal fields can be left blank in a template; all other rule validation still applies.
    Rule check = template.instantiate();
    for (Rule.Piece p : pieces(check)) {
      placeholder(p, "contact", "15555550100");
      placeholder(p, "recipient", "15555550100");
      placeholder(p, "ssid", "Choose Wi-Fi");
      placeholder(p, "waypoint", "Choose a place");
      placeholder(p, "latitude", "0");
      placeholder(p, "longitude", "0");
      placeholder(p, "uri", "content://template/choose-file");
    }
    List<String> errors = check.errors();
    if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
  }

  private static void placeholder(Rule.Piece p, String key, String value) {
    if (p.get(key).isBlank()) p.set(key, value);
  }
}
