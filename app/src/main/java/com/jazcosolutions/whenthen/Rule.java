package com.jazcosolutions.whenthen;

import java.util.*;

public class Rule {
  public String id = UUID.randomUUID().toString();
  public String name = "";
  public boolean enabled = false;
  public int priority = 0;
  public int cooldownSeconds = 60;
  public Piece when = new Piece("call_incoming");
  public List<Piece> then = new ArrayList<>();

  public static class Piece {
    public String type;
    public Map<String, String> options = new LinkedHashMap<>();

    public Piece() {}

    public Piece(String type) {
      this.type = type;
    }

    public String get(String key) {
      return options == null ? "" : options.getOrDefault(key, "");
    }

    public Piece set(String key, Object value) {
      options.put(key, String.valueOf(value));
      return this;
    }

    public double number(String key, double fallback) {
      try {
        double v = Double.parseDouble(get(key));
        return Double.isFinite(v) ? v : fallback;
      } catch (Exception e) {
        return fallback;
      }
    }
  }

  public static final String[] TRIGGERS = {
    "call_incoming",
    "call_missed",
    "call_count",
    "sms_received",
    "sms_matches",
    "waypoint_enter",
    "waypoint_exit",
    "wifi_connected",
    "wifi_disconnected"
  };
  public static final String[] TRIGGER_NAMES = {
    "A contact calls",
    "A call is missed",
    "Repeated calls",
    "A text arrives",
    "A text matches",
    "Enter a place",
    "Leave a place",
    "Wi-Fi connects",
    "Wi-Fi disconnects"
  };
  public static final String[] ACTIONS = {
    "mute",
    "unmute",
    "set_volume",
    "emergency_loud",
    "play_audio",
    "show_message",
    "show_image",
    "send_sms"
  };
  public static final String[] ACTION_NAMES = {
    "Mute sounds",
    "Restore sounds",
    "Set volume",
    "Emergency loud",
    "Play audio",
    "Show a message",
    "Show an image",
    "Send a text"
  };
  public static final String[] CHANNELS = {"ring", "media", "alarm", "all"};
  public static final String[] CHANNEL_NAMES = {
    "Ring & notifications", "Media", "Alarms", "All four channels"
  };

  public static String label(String type) {
    int i = Arrays.asList(TRIGGERS).indexOf(type);
    if (i >= 0) return TRIGGER_NAMES[i];
    i = Arrays.asList(ACTIONS).indexOf(type);
    return i < 0 ? type : ACTION_NAMES[i];
  }

  public static String phone(String s) {
    return s == null ? "" : s.replaceAll("[^0-9]", "");
  }

  public static boolean samePhone(String a, String b) {
    String x = phone(a), y = phone(b);
    return !x.isEmpty()
        && (x.equals(y)
            || (x.length() == 11 && x.startsWith("1") && x.substring(1).equals(y))
            || (y.length() == 11 && y.startsWith("1") && y.substring(1).equals(x)));
  }

  private static void required(Piece p, String key, String message, List<String> errors) {
    if (p.get(key).trim().isEmpty()) errors.add(message);
  }

  private static void range(
      Piece p, String key, double low, double high, boolean integer, List<String> e) {
    double n = p.number(key, Double.NaN);
    if (!Double.isFinite(n) || n < low || n > high || (integer && n != Math.rint(n)))
      e.add(
          key
              + " must be "
              + (integer ? "a whole number " : "")
              + "from "
              + low
              + " to "
              + high
              + ".");
  }

  public List<String> errors() {
    List<String> e = new ArrayList<>();
    if (id == null || id.isBlank() || id.length() > 100) e.add("Invalid rule ID.");
    if (name == null || name.isBlank() || name.length() > 120)
      e.add("Give this rule a name (up to 120 characters).");
    if (cooldownSeconds < 10 || cooldownSeconds > 86400)
      e.add("Pause between runs must be 10–86400 seconds.");
    if (priority != 0 && priority != 10) e.add("Choose normal or emergency priority.");
    if (when == null || !Arrays.asList(TRIGGERS).contains(when.type)) {
      e.add("Choose a WHEN block.");
      return e;
    }
    if (when.type.startsWith("call") || when.type.startsWith("sms")) {
      required(when, "contact", "Choose a contact or enter their phone number.", e);
      if (phone(when.get("contact")).length() < 3) e.add("Enter a valid contact number.");
    }
    if (when.type.equals("call_count")) {
      range(when, "count", 1, 999, true, e);
      range(when, "minutes", 1, 10080, true, e);
    }
    if (when.type.equals("sms_matches")) required(when, "matchText", "Enter the text to match.", e);
    if (when.type.startsWith("wifi")) required(when, "ssid", "Enter the exact Wi-Fi name.", e);
    if (when.type.startsWith("waypoint")) {
      required(when, "waypoint", "Name this place.", e);
      if (!MapSelection.validPoint(
          when.number("latitude", Double.NaN), when.number("longitude", Double.NaN)))
        e.add("Choose a location on the map and tap Use this location.");
      range(when, "radiusFeet", 50, 100000, true, e);
    }
    if (then == null || then.isEmpty() || then.size() > 20) {
      e.add("Add 1–20 THEN blocks.");
      return e;
    }
    for (Piece p : then) {
      if (p == null || !Arrays.asList(ACTIONS).contains(p.type)) {
        e.add("Choose a valid THEN action.");
        continue;
      }
      switch (p.type) {
        case "mute":
        case "unmute":
        case "set_volume":
        case "emergency_loud":
          if (!Arrays.asList(CHANNELS).contains(p.get("channels"))) e.add("Choose sound channels.");
          if (p.type.equals("set_volume")) range(p, "percent", 0, 100, true, e);
          break;
        case "play_audio":
          required(p, "uri", "Choose an audio file.", e);
          range(p, "seconds", 1, 300, true, e);
          break;
        case "show_image":
          required(p, "uri", "Choose an image.", e);
          break;
        case "show_message":
          required(p, "message", "Write a message.", e);
          break;
        case "send_sms":
          required(p, "message", "Write the outgoing text.", e);
          required(p, "recipient", "Choose a text recipient.", e);
          if (phone(p.get("recipient")).length() < 3) e.add("Enter a valid recipient number.");
          if (p.get("message").length() > 1000) e.add("Texts can be up to 1000 characters.");
          range(p, "subscription", -1, Integer.MAX_VALUE, true, e);
          break;
      }
      if ((p.type.equals("play_audio") || p.type.equals("show_image"))
          && !p.get("uri").startsWith("content://")) e.add("Pick a file from this phone.");
    }
    return e;
  }

  public static Piece action(String type) {
    Piece p = new Piece(type);
    p.set("channels", "all");
    p.set("percent", 100);
    p.set("seconds", 30);
    p.set("subscription", -1);
    return p;
  }
}
