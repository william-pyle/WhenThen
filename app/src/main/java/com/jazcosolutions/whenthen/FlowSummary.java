package com.jazcosolutions.whenthen;

import java.util.*;

/** Short, readable descriptions for saved blocks and the real-action test confirmation. */
final class FlowSummary {
  private FlowSummary() {}

  static String when(Rule.Piece p) {
    String contact = value(p, "contact", "Selected contact");
    switch (p.type) {
      case "call_incoming":
        return contact + " calls";
      case "call_missed":
        return "Miss a call from " + contact;
      case "call_count":
        return contact
            + " calls "
            + p.get("count")
            + " times within "
            + p.get("minutes")
            + " minutes";
      case "sms_received":
        return "A text arrives from " + contact;
      case "sms_matches":
        return contact + " texts exactly “" + p.get("matchText") + "”";
      case "wifi_connected":
        return "Connect to “" + p.get("ssid") + "”";
      case "wifi_disconnected":
        return "Disconnect from “" + p.get("ssid") + "”";
      case "waypoint_enter":
      case "waypoint_exit":
        return (p.type.endsWith("enter") ? "Enter " : "Leave ")
            + value(p, "waypoint", "Selected place")
            + " · "
            + p.get("radiusFeet")
            + " ft radius";
      default:
        return Rule.label(p.type);
    }
  }

  static String then(Rule.Piece p) {
    switch (p.type) {
      case "show_message":
        return "Show “" + p.get("message") + "”";
      case "send_sms":
        return "Text " + p.get("recipient") + ": “" + p.get("message") + "”";
      case "show_image":
        return "Show "
            + value(p, "fileName", "selected image")
            + (p.get("message").isBlank() ? "" : " · " + p.get("message"));
      case "play_audio":
        return "Play "
            + value(p, "fileName", "selected audio")
            + " · up to "
            + p.get("seconds")
            + " seconds";
      case "set_volume":
        return "Set " + channels(p) + " volume to " + p.get("percent") + "%";
      case "mute":
        return "Mute " + channels(p);
      case "unmute":
        return "Restore " + channels(p) + " sound";
      case "emergency_loud":
        return "Maximum " + channels(p) + " volume";
      default:
        return Rule.label(p.type);
    }
  }

  static String testDescription(Rule r) {
    StringBuilder s =
        new StringBuilder("Treat this WHEN as satisfied once:\n")
            .append(when(r.when))
            .append("\n\nRun these real actions:\n");
    for (int i = 0; i < r.then.size(); i++)
      s.append(i + 1).append(". ").append(then(r.then.get(i))).append('\n');
    return s.append(
            "\n"
                + "This can send texts, change sound, play files, or show notifications. The"
                + " block’s enabled switch stays unchanged. SMS limits and emergency sound"
                + " protection still apply.")
        .toString();
  }

  private static String channels(Rule.Piece p) {
    int index = Arrays.asList(Rule.CHANNELS).indexOf(p.get("channels"));
    return index < 0 ? "selected" : Rule.CHANNEL_NAMES[index].toLowerCase(Locale.US);
  }

  private static String value(Rule.Piece p, String key, String fallback) {
    return p.get(key).isBlank() ? fallback : p.get(key);
  }
}
