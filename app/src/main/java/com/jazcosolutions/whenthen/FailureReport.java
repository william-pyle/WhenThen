package com.jazcosolutions.whenthen;

import android.Manifest;
import android.content.Context;
import android.os.Build;
import java.io.*;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.*;

/** A user-reviewed issue draft; no credentials and no automatic publishing. */
final class FailureReport {
  static final String ISSUES = "https://github.com/william-pyle/WhenThen/issues/new";
  static final int MAX_URL = 7000;

  static final class Details {
    String component, version, android, device, summary, trace, flow;
    Map<String, Boolean> access = new LinkedHashMap<>();
  }

  static boolean reportable(String status) {
    return "failed".equals(status) || "blocked".equals(status) || "partial".equals(status);
  }

  static Details capture(
      Context c, Store.Entry entry, String component, Throwable error, Rule rule) {
    Details d = new Details();
    List<Rule> rules = new ArrayList<>();
    try {
      rules.addAll(new Store(c).rules());
    } catch (RuntimeException ignored) {
    }
    if (rule != null) rules.add(rule);
    List<String> privateValues = privateValues(rules);
    d.component = component;
    d.version = BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    d.android = Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT;
    d.device = Build.MANUFACTURER + " " + Build.MODEL;
    d.summary = sanitize(entry.detail, privateValues);
    if (error != null) {
      StringWriter trace = new StringWriter();
      error.printStackTrace(new PrintWriter(trace));
      d.trace = sanitize(trace.toString(), privateValues);
      if (d.trace.length() > 24000)
        d.trace = d.trace.substring(0, 24000) + "\n[Stack trace capped at 24,000 characters]";
    }
    if (rule != null)
      d.flow =
          rule.when.type
              + " → "
              + String.join(", ", rule.then.stream().map(p -> p.type).toArray(String[]::new));
    for (String permission :
        new String[] {
          Manifest.permission.POST_NOTIFICATIONS,
          Manifest.permission.READ_PHONE_STATE,
          Manifest.permission.READ_CALL_LOG,
          Manifest.permission.RECEIVE_SMS,
          Manifest.permission.SEND_SMS,
          Manifest.permission.ACCESS_FINE_LOCATION,
          Manifest.permission.ACCESS_BACKGROUND_LOCATION
        })
      d.access.put(
          permission.substring(permission.lastIndexOf('.') + 1), Readiness.has(c, permission));
    d.access.put("Location enabled", Readiness.locationOn(c));
    d.access.put("DND access", Readiness.dndAccess(c));
    d.access.put("Battery exemption", Readiness.exempt(c));
    return d;
  }

  static List<String> privateValues(List<Rule> rules) {
    List<String> values = new ArrayList<>();
    Set<String> safe =
        Set.of("count", "minutes", "radiusFeet", "seconds", "percent", "channels", "subscription");
    for (Rule r : rules) {
      values.add(r.name);
      List<Rule.Piece> pieces = new ArrayList<>();
      if (r.when != null) pieces.add(r.when);
      if (r.then != null) pieces.addAll(r.then);
      for (Rule.Piece p : pieces)
        if (p.options != null)
          for (Map.Entry<String, String> option : p.options.entrySet())
            if (!safe.contains(option.getKey())) values.add(option.getValue());
    }
    values.removeIf(v -> v == null || v.isBlank() || v.length() < 3);
    values.sort(Comparator.comparingInt(String::length).reversed());
    return values;
  }

  static String sanitize(String raw, List<String> privateValues) {
    String s = raw == null ? "" : raw;
    for (String value : privateValues)
      if (value != null && value.length() >= 3) s = s.replace(value, "[private value]");
    s = s.replaceAll("(?i)(?:https?|content|file|tel|mailto)://[^\\s<>]+", "[private URI]");
    s = s.replaceAll("(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", "[email]");
    s = s.replaceAll("(?<![\\w.])\\+?\\d[\\d ()-]{7,}\\d(?![\\w.])", "[phone/identifier]");
    s = s.replaceAll("(?i)(?:/storage/|/sdcard/|/data/(?:user|data)/)[^\\s<>]+", "[private path]");
    s = s.replaceAll("(?<![\\w.])-?\\d{1,3}\\.\\d{4,}(?![\\w.])", "[coordinate]");
    s =
        s.replaceAll(
            "(?i)(?:gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|Bearer\\s+[^\\s]+)",
            "[credential]");
    return s;
  }

  static String title(Store.Entry entry) {
    return "Possible issue: "
        + (entry.diagnostic == null || entry.diagnostic.component == null
            ? "app failure"
            : entry.diagnostic.component);
  }

  static String body(Store.Entry entry, List<Rule> rules) {
    Details d = entry.diagnostic;
    String time =
        new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date(entry.at));
    StringBuilder s =
        new StringBuilder(
                "## What happened\n"
                    + "Please describe what you were doing and what you expected.\n\n"
                    + "## Failure details\n")
            .append("- Time: ")
            .append(time)
            .append("\n- Status: ")
            .append(entry.status)
            .append('\n');
    if (d == null)
      s.append(
          "- Older history entry: version, device snapshot and stack trace were not recorded.\n");
    else {
      s.append("- App: ")
          .append(d.version)
          .append("\n- Android: ")
          .append(d.android)
          .append("\n- Device: ")
          .append(d.device)
          .append("\n- Component: ")
          .append(d.component)
          .append('\n');
      if (d.flow != null) s.append("- Flow types: ").append(d.flow).append('\n');
    }
    s.append("\n```text\n")
        .append(d == null ? sanitize(entry.detail, privateValues(rules)) : d.summary)
        .append("\n```\n");
    if (d != null && d.trace != null)
      s.append("\n## Exception and stack trace\n```text\n").append(d.trace).append("\n```\n");
    if (d != null && d.access != null) {
      s.append("\n## Access at failure\n");
      d.access.forEach(
          (key, value) ->
              s.append("- ")
                  .append(key)
                  .append(": ")
                  .append(value ? "granted/on" : "not granted/off")
                  .append('\n'));
    }
    return s.append(
            "\n"
                + "Known rule names, numbers, messages, places, network names and file paths have"
                + " been removed. Review before posting; this issue will be public.\n")
        .toString();
  }

  static String url(String title, String body) {
    try {
      return ISSUES
          + "?title="
          + URLEncoder.encode(title, "UTF-8")
          + "&body="
          + URLEncoder.encode(body, "UTF-8");
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
