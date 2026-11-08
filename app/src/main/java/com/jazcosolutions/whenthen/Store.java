package com.jazcosolutions.whenthen;

import android.content.*;
import android.util.AtomicFile;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Store {
  private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
  private final Context context;

  public static class Document {
    public int version = 1;
    public List<Rule> rules = new ArrayList<>();
  }

  public static class Entry {
    public long at = System.currentTimeMillis();
    public String title, detail, status;
    public FailureReport.Details diagnostic;

    public Entry(String title, String detail, String status) {
      this.title = title;
      this.detail = detail;
      this.status = status;
    }
  }

  public Store(Context c) {
    context = c.getApplicationContext();
  }

  public SharedPreferences prefs() {
    return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
  }

  private <T> T read(String name, Class<T> type, T fallback) {
    try {
      return JSON.fromJson(
          new String(
              new AtomicFile(new File(context.getFilesDir(), name)).readFully(),
              StandardCharsets.UTF_8),
          type);
    } catch (FileNotFoundException e) {
      return fallback;
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot read " + name + ". Restore a backup; the saved file has been preserved.", e);
    }
  }

  private void write(String name, Object value) {
    AtomicFile f = new AtomicFile(new File(context.getFilesDir(), name));
    FileOutputStream out = null;
    try {
      out = f.startWrite();
      out.write(JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
      f.finishWrite(out);
    } catch (Exception e) {
      if (out != null) f.failWrite(out);
      throw new IllegalStateException("Could not save " + name, e);
    }
  }

  public List<Rule> rules() {
    synchronized (Store.class) {
      Document d = read("rules.json", Document.class, new Document());
      if (d == null || d.rules == null) throw new IllegalStateException("Invalid saved rules.");
      return d.rules;
    }
  }

  public void save(List<Rule> rules) {
    synchronized (Store.class) {
      validate(rules);
      Document d = new Document();
      d.rules = rules;
      write("rules.json", d);
    }
  }

  public static void validate(List<Rule> rules) {
    if (rules == null || rules.size() > 100)
      throw new IllegalArgumentException("A backup can contain up to 100 rules.");
    Set<String> ids = new HashSet<>();
    for (Rule r : rules) {
      if (r == null || !r.errors().isEmpty())
        throw new IllegalArgumentException(
            r == null ? "Invalid rule." : String.join("\n", r.errors()));
      if (!ids.add(r.id)) throw new IllegalArgumentException("Duplicate rule ID in backup.");
    }
  }

  public String exportRules() {
    Document d = new Document();
    d.rules = rules();
    return JSON.toJson(d);
  }

  public List<Rule> parseImport(String text) {
    if (text.length() > 2_000_000) throw new IllegalArgumentException("Backup is too large.");
    Document d = JSON.fromJson(text, Document.class);
    if (d == null || d.version != 1)
      throw new IllegalArgumentException("This is not a WhenThen version 1 backup.");
    validate(d.rules);
    for (Rule r : d.rules) r.enabled = false;
    return d.rules;
  }

  public void log(String title, String detail, String status) {
    record(title, detail, status, null, "Runtime", null);
  }

  public void failure(String title, Throwable error, String component, Rule rule) {
    record(
        title,
        error.getMessage() == null ? error.toString() : error.getMessage(),
        "failed",
        error,
        component,
        rule);
  }

  private void record(
      String title, String detail, String status, Throwable error, String component, Rule rule) {
    synchronized (Store.class) {
      try {
        List<Entry> entries = history();
        Entry entry = new Entry(title, detail, status);
        if (FailureReport.reportable(status))
          entry.diagnostic = FailureReport.capture(context, entry, component, error, rule);
        entries.add(0, entry);
        while (entries.size() > 300) entries.remove(entries.size() - 1);
        write("history.json", entries);
      } catch (Exception e) {
        android.util.Log.e("WhenThen", "Cannot record local history", e);
      }
    }
  }

  public List<Entry> history() {
    synchronized (Store.class) {
      Entry[] data = read("history.json", Entry[].class, new Entry[0]);
      return new ArrayList<>(Arrays.asList(data));
    }
  }

  public String exportHistory() {
    return JSON.toJson(history());
  }

  public void clearHistory() {
    synchronized (Store.class) {
      write("history.json", new ArrayList<>());
    }
  }

  public RuleEngine.State engineState() {
    synchronized (Store.class) {
      return read("engine.json", RuleEngine.State.class, new RuleEngine.State());
    }
  }

  public void engineState(RuleEngine.State state) {
    synchronized (Store.class) {
      write("engine.json", state);
    }
  }

  public static Rule copy(Rule r) {
    return JSON.fromJson(JSON.toJson(r), Rule.class);
  }

  public static String json(Object o) {
    return JSON.toJson(o);
  }
}
