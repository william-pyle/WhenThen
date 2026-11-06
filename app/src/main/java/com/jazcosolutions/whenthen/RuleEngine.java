package com.jazcosolutions.whenthen;

import java.util.*;

/** Pure deterministic matching; no phone actions. All observations carry a stable identity. */
public final class RuleEngine {
  public static class Event {
    public String id, type, contact = "", text = "", ssid = "";
    public long at;
    public double latitude, longitude, accuracy;

    public Event(String id, String type, long at) {
      this.id = id;
      this.type = type;
      this.at = at;
    }
  }

  public static class Boundary {
    public Boolean inside, candidate;
    public long candidateAt;
  }

  public static class State {
    public LinkedHashMap<String, Long> seen = new LinkedHashMap<>();
    public Map<String, List<Long>> calls = new HashMap<>();
    public Map<String, Long> lastRuns = new HashMap<>();
    public Map<String, Boundary> boundaries = new HashMap<>();
  }

  public State state;

  public RuleEngine() {
    this(new State());
  }

  public RuleEngine(State state) {
    this.state = state;
  }

  public List<Rule> accept(List<Rule> rules, Event event, long now) {
    List<Rule> matches = new ArrayList<>();
    if (event == null
        || event.id == null
        || event.type == null
        || event.at > now + 5000
        || event.at < now - 120000
        || state.seen.containsKey(event.id)) return matches;
    state.seen.put(event.id, now);
    state.seen.entrySet().removeIf(e -> e.getValue() < now - 86400000L);
    while (state.seen.size() > 2048) state.seen.remove(state.seen.keySet().iterator().next());
    for (List<Long> times : state.calls.values()) times.removeIf(t -> t < now - 604800000L);
    state.calls.entrySet().removeIf(e -> e.getValue().isEmpty());
    for (Rule rule : rules) {
      if (!rule.enabled || !rule.errors().isEmpty()) continue;
      Rule.Piece p = rule.when;
      boolean hit = false;
      switch (p.type) {
        case "call_incoming":
        case "call_missed":
        case "sms_received":
        case "sms_matches":
          hit =
              (p.type.equals(event.type)
                      || (p.type.equals("sms_matches") && event.type.equals("sms_received")))
                  && Rule.samePhone(p.get("contact"), event.contact)
                  && (!p.type.equals("sms_matches") || p.get("matchText").equals(event.text));
          break;
        case "call_count":
          if (event.type.equals("call_incoming")
              && Rule.samePhone(p.get("contact"), event.contact)) {
            long start = now - (long) (p.number("minutes", 5) * 60000);
            int before = 0;
            for (Map.Entry<String, List<Long>> entry : state.calls.entrySet())
              if (Rule.samePhone(entry.getKey(), event.contact))
                for (long t : entry.getValue()) if (t >= start) before++;
            int threshold = (int) p.number("count", 3);
            hit = before < threshold && before + 1 >= threshold;
          }
          break;
        case "wifi_connected":
        case "wifi_disconnected":
          hit = p.type.equals(event.type) && p.get("ssid").equals(event.ssid);
          break;
        case "waypoint_enter":
        case "waypoint_exit":
          if (event.type.equals("location")) hit = boundary(rule, event, now);
          break;
      }
      long last = state.lastRuns.getOrDefault(rule.id, 0L);
      if (hit && (last == 0 || now - last >= rule.cooldownSeconds * 1000L)) {
        state.lastRuns.put(rule.id, now);
        matches.add(rule);
      }
    }
    if (event.type.equals("call_incoming") && !Rule.phone(event.contact).isEmpty()) {
      List<Long> calls =
          state.calls.computeIfAbsent(Rule.phone(event.contact), k -> new ArrayList<>());
      calls.add(now);
      while (calls.size() > 2000) calls.remove(0);
    }
    // High-priority rules run last, so their volume changes win within this event batch.
    matches.sort(Comparator.comparingInt(r -> r.priority));
    return matches;
  }

  private boolean boundary(Rule r, Event e, long now) {
    Rule.Piece p = r.when;
    double radius = p.number("radiusFeet", 250) * 0.3048;
    if (!Double.isFinite(e.accuracy)
        || e.accuracy < 0
        || e.accuracy > Math.min(50, radius / 2)
        || !Double.isFinite(e.latitude)
        || !Double.isFinite(e.longitude)) return false;
    double distance =
        distanceMeters(e.latitude, e.longitude, p.number("latitude", 0), p.number("longitude", 0));
    Boundary b = state.boundaries.computeIfAbsent(r.id, k -> new Boundary());
    Boolean inside =
        distance + e.accuracy < radius ? true : distance - e.accuracy > radius ? false : null;
    if (inside == null) {
      b.candidate = null;
      return false;
    }
    if (b.inside == null) {
      b.inside = inside;
      return false;
    }
    if (inside.equals(b.inside)) {
      b.candidate = null;
      return false;
    }
    if (!inside.equals(b.candidate)) {
      b.candidate = inside;
      b.candidateAt = now;
      return false;
    }
    if (now - b.candidateAt < 10000) return false;
    b.inside = inside;
    b.candidate = null;
    return inside ? r.when.type.equals("waypoint_enter") : r.when.type.equals("waypoint_exit");
  }

  public static double distanceMeters(double a, double b, double c, double d) {
    double x = Math.toRadians(c - a), y = Math.toRadians(d - b);
    double v =
        Math.sin(x / 2) * Math.sin(x / 2)
            + Math.cos(Math.toRadians(a))
                * Math.cos(Math.toRadians(c))
                * Math.sin(y / 2)
                * Math.sin(y / 2);
    return 6371000 * 2 * Math.atan2(Math.sqrt(Math.min(1, v)), Math.sqrt(Math.max(0, 1 - v)));
  }
}
