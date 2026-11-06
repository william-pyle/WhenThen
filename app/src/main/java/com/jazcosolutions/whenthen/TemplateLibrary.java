package com.jazcosolutions.whenthen;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class TemplateLibrary {
  private final Context context;

  TemplateLibrary(Context context) {
    this.context = context.getApplicationContext();
  }

  List<RuleTemplate> all() {
    try (InputStream in = context.getAssets().open("templates.json")) {
      List<RuleTemplate> templates = RuleTemplate.decode(read(in));
      templates.addAll(custom());
      return templates;
    } catch (IOException e) {
      throw new IllegalStateException("Could not read starter templates.", e);
    }
  }

  private AtomicFile file() {
    return new AtomicFile(new File(context.getFilesDir(), "templates.json"));
  }

  private List<RuleTemplate> custom() {
    try {
      String json = new String(file().readFully(), StandardCharsets.UTF_8);
      // Empty custom storage is valid; public import documents must contain a template.
      if (json.trim().equals("[]")) return new ArrayList<>();
      return RuleTemplate.decode(json);
    } catch (FileNotFoundException e) {
      return new ArrayList<>();
    } catch (Exception e) {
      throw new IllegalStateException(
          "Could not read saved templates. The original file is preserved.", e);
    }
  }

  int add(List<RuleTemplate> imported) {
    List<RuleTemplate> additions = RuleTemplate.decode(RuleTemplate.encode(imported));
    List<RuleTemplate> all = all(), custom = custom();
    int count = 0;
    for (RuleTemplate template : additions) {
      RuleTemplate existing =
          all.stream().filter(t -> t.id.equals(template.id)).findFirst().orElse(null);
      if (existing != null) {
        if (RuleTemplate.encode(List.of(existing)).equals(RuleTemplate.encode(List.of(template))))
          continue;
        template.id = "custom-" + UUID.randomUUID();
      }
      custom.add(template);
      all.add(template);
      count++;
    }
    if (all.size() > 100)
      throw new IllegalArgumentException("The library can hold up to 100 templates.");
    if (RuleTemplate.encode(all).getBytes(StandardCharsets.UTF_8).length > 1_000_000)
      throw new IllegalArgumentException("The complete template library must fit within 1 MB.");
    save(custom);
    return count;
  }

  boolean isCustom(String id) {
    return custom().stream().anyMatch(t -> t.id.equals(id));
  }

  void remove(String id) {
    List<RuleTemplate> templates = custom();
    templates.removeIf(t -> t.id.equals(id));
    save(templates);
  }

  private void save(List<RuleTemplate> templates) {
    AtomicFile file = file();
    FileOutputStream output = null;
    try {
      output = file.startWrite();
      output.write(
          (templates.isEmpty() ? "[]" : RuleTemplate.encode(templates))
              .getBytes(StandardCharsets.UTF_8));
      file.finishWrite(output);
    } catch (IOException e) {
      if (output != null) file.failWrite(output);
      throw new IllegalStateException("Could not save templates; existing rules are unchanged.", e);
    }
  }

  static String read(InputStream input) throws IOException {
    if (input == null) throw new IOException("Could not open the selected file.");
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (bytes.size() + count > 1_000_000)
        throw new IOException("Template file is too large (maximum 1 MB).");
      bytes.write(buffer, 0, count);
    }
    return bytes.toString(StandardCharsets.UTF_8.name());
  }
}
