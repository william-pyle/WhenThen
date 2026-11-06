package com.jazcosolutions.whenthen;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;
import java.util.function.Consumer;

public final class MainActivity extends Activity {
  public static final int BG = 0xFFF7F6FC,
      INK = 0xFF25213D,
      MUTED = 0xFF6C6581,
      ORANGE = 0xFFB74715,
      PURPLE = 0xFF6B20D0,
      TEAL = 0xFF087D70;
  private static final int[] OPTIONS = {0xFFFFF0CC, 0xFFE4F7EF, 0xFFFFE7EF, 0xFFE5EFFF};
  private LinearLayout root, body, nav;
  private TextView updateStatus;
  private LinearLayout monitorCard;
  private TextView monitorTitle, monitorDetail, monitorHealth;
  private Button monitorButton;
  private ScrollView scroll;
  private Store store;
  private TemplateLibrary templateLibrary;
  AppUpdater updates;
  private String page = "rules";
  private Rule draft;
  private String exportText;
  private int pickIndex = -2;
  private String pickKey = "";
  private boolean restoring;
  private final Handler handler = new Handler(Looper.getMainLooper());

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    store = new Store(this);
    templateLibrary = new TemplateLibrary(this);
    updates =
        new AppUpdater(
            this,
            () -> draft == null,
            () -> {
              if (updateStatus != null) updateStatus.setText(updates.status());
            });
    getWindow().setStatusBarColor(BG);
    getWindow().setNavigationBarColor(BG);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    if (state != null) {
      page = state.getString("page", "rules");
      String saved = state.getString("draft");
      if (saved != null) draft = new Gson().fromJson(saved, Rule.class);
      pickIndex = state.getInt("pickIndex", -2);
      pickKey = state.getString("pickKey", "");
      exportText = state.getString("exportText");
    }
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
    render();
    showIntent(getIntent());
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("page", page);
    if (draft != null) b.putString("draft", Store.json(draft));
    b.putInt("pickIndex", pickIndex);
    b.putString("pickKey", pickKey);
    b.putString("exportText", exportText);
  }

  @Override
  protected void onResume() {
    super.onResume();
    Monitoring.onOpen(this);
    if (root != null && draft == null) render();
    refreshMonitorStatus();
    if (updates != null) updates.onResume();
  }

  @Override
  protected void onPause() {
    if (updates != null) updates.onPause();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    handler.removeCallbacksAndMessages(null);
    if (updates != null) updates.close();
    super.onDestroy();
  }

  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    showIntent(i);
  }

  @android.annotation.SuppressLint(
      "GestureBackNavigation") // API 33+ is registered with OnBackInvokedDispatcher; this path
  // supports Android 12.
  @Override
  public void onBackPressed() {
    handleBack();
  }

  private void handleBack() {
    if (draft != null) {
      new AlertDialog.Builder(this)
          .setTitle("Leave this builder?")
          .setMessage("Your unsaved changes will be discarded.")
          .setPositiveButton(
              "Leave",
              (d, w) -> {
                draft = null;
                page = "rules";
                render();
              })
          .setNegativeButton("Keep building", null)
          .show();
    } else if (!page.equals("rules")) {
      page = "rules";
      render();
    } else finish();
  }

  private int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private GradientDrawable round(int color, int radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(dp(radius));
    return g;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    l.setOrientation(LinearLayout.HORIZONTAL);
    return l;
  }

  private TextView text(String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setTypeface(
        Typeface.create(
            bold ? "sans-serif-medium" : "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
    t.setLineSpacing(dp(3), 1);
    return t;
  }

  private void add(LinearLayout parent, View view, int top) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = dp(top);
    parent.addView(view, lp);
  }

  private void space(LinearLayout l, int height) {
    View v = new View(this);
    l.addView(v, new LinearLayout.LayoutParams(1, dp(height)));
  }

  private Button button(String label, int color, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(15);
    b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    b.setTextColor(Color.WHITE);
    b.setBackground(round(color, 15));
    b.setMinHeight(dp(50));
    b.setPadding(dp(14), dp(10), dp(14), dp(10));
    b.setOnClickListener(v -> action.run());
    b.setStateListAnimator(null);
    return b;
  }

  private Button quiet(String label, Runnable action) {
    Button b = button(label, 0xFFECE8F8, action);
    b.setTextColor(PURPLE);
    return b;
  }

  private void pair(LinearLayout parent, View a, View b) {
    LinearLayout r = row();
    LinearLayout.LayoutParams x = new LinearLayout.LayoutParams(0, -2, 1);
    x.setMargins(0, 0, dp(5), 0);
    r.addView(a, x);
    LinearLayout.LayoutParams y = new LinearLayout.LayoutParams(0, -2, 1);
    y.leftMargin = dp(5);
    r.addView(b, y);
    add(parent, r, 10);
  }

  private LinearLayout card(int color) {
    LinearLayout l = column();
    l.setPadding(dp(18), dp(18), dp(18), dp(18));
    l.setBackground(round(color, 21));
    return l;
  }

  private LinearLayout block(int color) {
    LinearLayout l = column();
    l.setPadding(dp(16), dp(42), dp(16), dp(22));
    l.setBackground(new BlockDrawable(color, getResources().getDisplayMetrics().density));
    return l;
  }

  private void connectedBlock(LinearLayout parent, View view, boolean when, int gap) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.leftMargin = dp(when ? 0 : 18);
    params.rightMargin = dp(when ? 18 : 0);
    params.topMargin = dp(gap);
    parent.addView(view, params);
  }

  private TextView pill(String label, int color, int fg) {
    TextView t = text(label, 12, fg, true);
    t.setBackground(round(color, 12));
    t.setPadding(dp(10), dp(5), dp(10), dp(5));
    return t;
  }

  private void title(String eyebrow, String heading, String description) {
    add(body, text(eyebrow.toUpperCase(Locale.US), 11, PURPLE, true), 6);
    add(body, text(heading, 30, INK, true), 5);
    if (!description.isEmpty()) add(body, text(description, 15, MUTED, false), 6);
  }

  private void render() {
    updateStatus = null;
    monitorCard = null;
    root = column();
    root.setBackgroundColor(BG);
    root.setFitsSystemWindows(true);
    root.setPadding(0, 0, 0, 0);
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets bars =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
          v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          return insets;
        });
    LinearLayout brand = row();
    brand.setPadding(dp(20), dp(12), dp(20), dp(10));
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.mipmap.ic_launcher);
    icon.setContentDescription("WhenThen interlocking blocks logo");
    brand.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
    TextView name = text("  WhenThen", 21, INK, true);
    name.setSingleLine(true);
    brand.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    android.content.res.Configuration configuration = getResources().getConfiguration();
    if (configuration.screenWidthDp / configuration.fontScale >= 400) {
      brand.addView(pill("YOUR PHONE. YOUR RULES.", 0xFFEDE8FA, PURPLE));
    }
    root.addView(brand);
    scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    body = column();
    body.setPadding(dp(20), dp(6), dp(20), dp(26));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    nav = row();
    nav.setPadding(dp(10), dp(8), dp(10), dp(10));
    nav.setBackgroundColor(Color.WHITE);
    String[] tabs = {"rules", "lab", "activity", "setup"};
    String[] labels = {"▦  Rules", "✦  Try it", "◷  Activity", "⚙  Setup"};
    for (int n = 0; n < tabs.length; n++) {
      String dest = tabs[n];
      Button b = quiet(labels[n], () -> navigate(dest));
      b.setTextSize(12);
      b.setPadding(0, dp(8), 0, dp(8));
      b.setBackground(round(page.equals(dest) ? 0xFFEDE6FC : Color.WHITE, 14));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
      p.setMargins(dp(3), 0, dp(3), 0);
      nav.addView(b, p);
    }
    root.addView(nav);
    try {
      if (draft != null) editor();
      else
        switch (page) {
          case "setup":
            setup();
            break;
          case "activity":
            activity();
            break;
          case "lab":
            lab();
            break;
          case "templates":
            templatePage();
            break;
          default:
            home();
        }
    } catch (Exception e) {
      title(
          "Something needs attention",
          "We kept your data",
          e.getMessage() == null ? e.toString() : e.getMessage());
      add(
          body,
          quiet(
              "Open Setup",
              () -> {
                draft = null;
                page = "setup";
                render();
              }),
          14);
    }
  }

  private void navigate(String target) {
    if (draft != null) {
      new AlertDialog.Builder(this)
          .setTitle("Leave unsaved rule?")
          .setPositiveButton(
              "Leave",
              (d, w) -> {
                draft = null;
                page = target;
                render();
              })
          .setNegativeButton("Stay", null)
          .show();
      return;
    }
    page = target;
    render();
  }

  private void home() {
    LinearLayout hero = card(0xFF1D0874);
    add(hero, text("MAKE LIFE CLICK", 11, 0xFFB8A5FF, true), 0);
    add(hero, text("Small blocks.\nBig possibilities.", 29, Color.WHITE, true), 7);
    add(
        hero,
        text(
            "Pick a moment. Snap on an action.\nLet your phone connect the dots.",
            15,
            0xFFDDD7F1,
            false),
        8);
    LinearLayout labels = column();
    LinearLayout whenBrick = block(0xFFFFA233), thenBrick = block(0xFFB88AF0);
    add(whenBrick, text("WHEN  something happens", 13, 0xFF442004, true), 0);
    add(thenBrick, text("THEN  make it happen", 13, 0xFF291143, true), 0);
    connectedBlock(labels, whenBrick, true, 16);
    connectedBlock(labels, thenBrick, false, -6);
    add(hero, labels, 0);
    add(body, hero, 6);
    List<Rule> rules = store.rules();
    long enabled = rules.stream().filter(r -> r.enabled).count();
    boolean active = MonitorService.active != null;
    LinearLayout status = card(active ? 0xFFE0F5EC : 0xFFFFF0D9);
    monitorCard = status;
    monitorTitle = text("", 17, TEAL, true);
    monitorDetail = text("", 13, MUTED, false);
    monitorButton =
        button(
            "",
            TEAL,
            () -> {
              if (MonitorService.active != null) stopMonitor();
              else startMonitor();
            });
    monitorHealth = text("", 14, ORANGE, true);
    add(status, monitorTitle, 0);
    add(status, monitorDetail, 5);
    add(status, monitorButton, 12);
    add(status, monitorHealth, 10);
    updateMonitoringCard();
    add(body, status, 16);
    pair(
        body,
        button("+  Build a rule", PURPLE, this::newRule),
        quiet("Explore templates", this::templates));
    add(body, text("Your building blocks", 21, INK, true), 24);
    if (rules.isEmpty()) {
      LinearLayout empty = card(Color.WHITE);
      add(empty, text("Your first little superpower", 19, INK, true), 0);
      add(
          empty,
          text(
              "Try: when someone calls repeatedly, turn up the sound. Start with a template or"
                  + " build your own.",
              15,
              MUTED,
              false),
          8);
      add(empty, quiet("Choose a starter", this::templates), 16);
      add(body, empty, 12);
    }
    for (Rule r : rules) {
      LinearLayout c = card(Color.WHITE);
      LinearLayout heading = row();
      TextView name = text(r.name, 18, INK, true);
      heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      Switch sw = new Switch(this);
      sw.setContentDescription("Enable " + r.name);
      sw.setChecked(r.enabled);
      sw.setOnCheckedChangeListener(
          (v, checked) -> {
            if (checked) {
              List<String> missing = Readiness.missing(this, r);
              if (!missing.isEmpty()) {
                sw.setChecked(false);
                alert("Finish setup first", String.join("\n", missing));
                return;
              }
            }
            r.enabled = checked;
            saveChangedRule(r);
          });
      heading.addView(sw);
      add(c, heading, 0);
      add(c, pill("WHEN   " + Rule.label(r.when.type), 0xFFFFE9DA, ORANGE), 10);
      for (Rule.Piece a : r.then)
        add(c, pill("THEN   " + Rule.label(a.type), 0xFFEFE8FF, PURPLE), 5);
      add(
          c,
          text(
              r.enabled
                  ? "Enabled · device verification still required"
                  : "Paused · ready to customize",
              12,
              MUTED,
              false),
          10);
      pair(
          c,
          quiet(
              "Edit blocks",
              () -> {
                draft = Store.copy(r);
                editorPage();
              }),
          quiet("More", () -> ruleMenu(r)));
      add(body, c, 12);
    }
  }

  private void startMonitor() {
    Monitoring.resume(this, "Started in the app", false);
    refreshMonitorStatus();
  }

  private void stopMonitor() {
    Monitoring.stop(this);
    refreshMonitorStatus();
  }

  private void refreshMonitorStatus() {
    handler.postDelayed(
        () -> {
          if (!isFinishing() && !isDestroyed()) updateMonitoringCard();
        },
        500);
  }

  private void updateMonitoringCard() {
    if (monitorCard == null) return;
    boolean active = MonitorService.active != null;
    List<Rule> rules = store.rules();
    long enabled = rules.stream().filter(r -> r.enabled).count();
    monitorCard.setBackground(round(active ? 0xFFE0F5EC : 0xFFFFF0D9, 21));
    monitorTitle.setText(active ? "●  Monitoring is on" : "○  Monitoring is paused");
    monitorTitle.setTextColor(active ? TEAL : 0xFF7D4A10);
    monitorDetail.setText(
        getString(
            R.string.monitor_counts,
            enabled,
            rules.size(),
            (Monitoring.wanted(this) && !active
                ? "\nStarting automatically; review Setup if it stays paused."
                : active && enabled == 0
                    ? "\nReady for your first enabled rule."
                    : !active
                        ? "\nA saved rule change, reboot or update will resume monitoring."
                        : "")));
    monitorButton.setText(active ? "Pause monitoring" : "Start monitoring");
    monitorButton.setBackground(round(active ? TEAL : PURPLE, 15));
    monitorHealth.setText(
        active && !MonitorService.health.isEmpty()
            ? "Some access needs attention. Open Setup or Activity for details."
            : "");
    monitorHealth.setVisibility(monitorHealth.getText().length() == 0 ? View.GONE : View.VISIBLE);
  }

  private void resumeForChanges() {
    Monitoring.rulesChanged(this);
    refreshMonitorStatus();
  }

  private void saveChangedRule(Rule rule) {
    List<Rule> rules = store.rules();
    for (int i = 0; i < rules.size(); i++) if (rules.get(i).id.equals(rule.id)) rules.set(i, rule);
    store.save(rules);
    resumeForChanges();
  }

  private void ruleMenu(Rule r) {
    new AlertDialog.Builder(this)
        .setTitle(r.name)
        .setItems(
            new String[] {"Duplicate", "Try in simulator", "Save as template", "Delete"},
            (d, n) -> {
              if (n == 0) {
                Rule copy = Store.copy(r);
                copy.id = UUID.randomUUID().toString();
                copy.name = r.name + " copy";
                copy.enabled = false;
                draft = copy;
                editorPage();
              } else if (n == 1) {
                page = "lab";
                render();
              } else if (n == 2) saveAsTemplate(r);
              else
                new AlertDialog.Builder(this)
                    .setTitle("Delete this rule?")
                    .setMessage(r.name)
                    .setPositiveButton(
                        "Delete",
                        (x, y) -> {
                          List<Rule> all = store.rules();
                          all.removeIf(a -> a.id.equals(r.id));
                          store.save(all);
                          resumeForChanges();
                          render();
                        })
                    .setNegativeButton("Keep", null)
                    .show();
            })
        .show();
  }

  private void templates() {
    page = "templates";
    render();
  }

  private void newRule() {
    draft = new Rule();
    draft.when.set("count", 3).set("minutes", 5).set("radiusFeet", 250);
    draft.then.add(Rule.action("show_message"));
    editorPage();
  }

  private void templatePage() {
    title(
        "REUSABLE BUILDING BLOCKS",
        "Your template library",
        "Pick a starting point, customize its pieces, and save your own rule.");
    pair(
        body,
        button(
            "Import templates",
            PURPLE,
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json"),
                    104)),
        quiet("Export library", () -> exportTemplates(templateLibrary.all())));
    add(
        body,
        text(
            "Templates never run on their own. New rules start paused until you enable them.",
            13,
            MUTED,
            false),
        12);
    for (RuleTemplate template : templateLibrary.all()) {
      LinearLayout tile = card(Color.WHITE);
      add(tile, text(template.name, 20, INK, true), 0);
      add(tile, text(template.description, 14, MUTED, false), 6);
      add(tile, pill("WHEN  " + Rule.label(template.rule.when.type), 0xFFFFE4CA, ORANGE), 12);
      for (Rule.Piece action : template.rule.then)
        add(tile, pill("THEN  " + Rule.label(action.type), 0xFFEDE3FF, PURPLE), 4);
      add(
          tile,
          button(
              "Use " + template.name,
              PURPLE,
              () -> {
                draft = template.instantiate();
                editorPage();
              }),
          14);
      pair(
          tile,
          quiet("Export template", () -> exportTemplates(List.of(template))),
          quiet(
              templateLibrary.isCustom(template.id) ? "Remove template" : "Starter template",
              () -> {
                if (!templateLibrary.isCustom(template.id)) {
                  toast("The four starter templates are always available.");
                  return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Remove this template?")
                    .setMessage("Your saved rules are kept.")
                    .setNegativeButton("Keep", null)
                    .setPositiveButton(
                        "Remove",
                        (d, w) -> {
                          try {
                            templateLibrary.remove(template.id);
                            render();
                          } catch (Exception e) {
                            alert("Could not remove", e.getMessage());
                          }
                        })
                    .show();
              }));
      add(body, tile, 16);
    }
  }

  private void saveAsTemplate(Rule rule) {
    EditText name = new EditText(this);
    name.setText(rule.name);
    name.setContentDescription("Template name");
    new AlertDialog.Builder(this)
        .setTitle("Save as template")
        .setView(name)
        .setMessage(
            "Contacts, Wi-Fi names, places and file selections are cleared in the template. Message"
                + " text is kept. Your rule stays unchanged.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Save template",
            (d, w) -> {
              try {
                templateLibrary.add(
                    List.of(RuleTemplate.fromRule(rule, name.getText().toString())));
                toast("Saved in your template library.");
              } catch (Exception e) {
                alert("Could not save template", e.getMessage());
              }
            })
        .show();
  }

  private void exportTemplates(List<RuleTemplate> templates) {
    new AlertDialog.Builder(this)
        .setTitle("Export templates?")
        .setMessage(
            "Templates may include message text and details from imported files. Review the JSON"
                + " before sharing it.")
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Export", (d, w) -> export(RuleTemplate.encode(templates), "WhenThen-templates.json"))
        .show();
  }

  private void editorPage() {
    page = "rules";
    render();
  }

  private EditText input(
      LinearLayout parent, String label, String value, int inputType, Consumer<String> change) {
    LinearLayout option = card(OPTIONS[parent.getChildCount() % OPTIONS.length]);
    option.setPadding(dp(12), dp(10), dp(12), dp(8));
    add(option, text(label, 12, INK, true), 0);
    EditText field = new EditText(this);
    field.setSingleLine(
        inputType
            != (android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE));
    field.setTextSize(16);
    field.setTextColor(INK);
    field.setHintTextColor(MUTED);
    field.setInputType(inputType);
    field.setText(value);
    field.setContentDescription(label);
    field.setPadding(dp(2), dp(7), dp(2), dp(7));
    field.setMinimumHeight(dp(48));
    field.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            change.accept(s.toString());
          }

          public void afterTextChanged(Editable e) {}
        });
    add(option, field, 0);
    add(parent, option, 10);
    return field;
  }

  private void field(LinearLayout p, Rule.Piece piece, String key, String label, int kind) {
    input(p, label, piece.get(key), kind, v -> piece.set(key, v));
  }

  private void select(
      LinearLayout p,
      String label,
      String[] names,
      String[] values,
      String selected,
      Consumer<String> change) {
    LinearLayout option = card(0xFFEDEBFF);
    option.setPadding(dp(10), dp(9), dp(10), dp(6));
    add(option, text(label, 12, INK, true), 0);
    Spinner spinner = new Spinner(this);
    spinner.setContentDescription(label);
    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names);
    spinner.setAdapter(adapter);
    int n = Arrays.asList(values).indexOf(selected);
    spinner.setSelection(Math.max(0, n));
    spinner.setMinimumHeight(dp(48));
    spinner.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
            change.accept(values[pos]);
          }

          public void onNothingSelected(AdapterView<?> a) {}
        });
    add(option, spinner, 0);
    add(p, option, 10);
  }

  private void editor() {
    title(
        "SNAP SOMETHING TOGETHER",
        "Build your flow",
        "Tap each block to choose what happens. Options fit right inside.");
    input(body, "Rule name", draft.name, 1, v -> draft.name = v);
    LinearLayout when = block(0xFFFFA233);
    add(when, text("01  /  WHEN", 12, 0xFF542A13, true), 0);
    add(when, text(Rule.label(draft.when.type), 23, 0xFF3F210F, true), 7);
    add(when, quiet("Change WHEN block", () -> choosePiece(true, -1)), 12);
    whenOptions(when, draft.when);
    connectedBlock(body, when, true, 22);
    for (int i = 0; i < draft.then.size(); i++) {
      int index = i;
      Rule.Piece piece = draft.then.get(i);
      LinearLayout then = block(0xFFB88AF0);
      add(then, text(String.format(Locale.US, "%02d  /  THEN", i + 2), 12, 0xFF352458, true), 0);
      add(then, text(Rule.label(piece.type), 23, 0xFF2B2046, true), 7);
      add(then, quiet("Change THEN block", () -> choosePiece(false, index)), 12);
      thenOptions(then, piece, index);
      LinearLayout controls = row();
      Button up =
          quiet(
              "↑",
              () -> {
                Collections.swap(draft.then, index, index - 1);
                renderAt(index);
              });
      up.setContentDescription("Move action " + (i + 1) + " up");
      up.setEnabled(i > 0);
      Button down =
          quiet(
              "↓",
              () -> {
                Collections.swap(draft.then, index, index + 1);
                renderAt(index);
              });
      down.setContentDescription("Move action " + (i + 1) + " down");
      down.setEnabled(i < draft.then.size() - 1);
      Button remove =
          quiet(
              "Remove",
              () -> {
                draft.then.remove(index);
                render();
              });
      for (Button b : new Button[] {up, down, remove}) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        controls.addView(b, lp);
      }
      add(then, controls, 14);
      connectedBlock(body, then, false, -6);
    }
    add(
        body,
        quiet(
            "+  Snap on another THEN",
            () -> {
              if (draft.then.size() < 20) {
                draft.then.add(Rule.action("show_message"));
                renderAt(draft.then.size() - 1);
              } else toast("A rule can have up to 20 actions.");
            }),
        16);
    LinearLayout settings = card(Color.WHITE);
    select(
        settings,
        "Priority",
        new String[] {"Everyday rule", "Emergency · protect sound for 60 seconds"},
        new String[] {"0", "10"},
        "" + draft.priority,
        v -> draft.priority = Integer.parseInt(v));
    input(
        settings,
        "Pause between runs (seconds)",
        "" + draft.cooldownSeconds,
        2,
        v -> {
          try {
            draft.cooldownSeconds = Integer.parseInt(v);
          } catch (Exception e) {
            draft.cooldownSeconds = -1;
          }
        });
    add(
        settings,
        text(
            "Actions run from top to bottom. SMS has an additional five-minute repeat limit. New"
                + " and imported rules start paused.",
            13,
            MUTED,
            false),
        12);
    add(body, settings, 18);
    pair(
        body,
        quiet("Cancel", this::onBackPressed),
        button("Save my rule", PURPLE, this::saveDraft));
  }

  private void renderAt(int i) {
    render();
    handler.post(
        () ->
            scroll.smoothScrollTo(0, Math.max(0, body.getHeight() - scroll.getHeight() - dp(230))));
  }

  private void choosePiece(boolean trigger, int index) {
    String[] names = trigger ? Rule.TRIGGER_NAMES : Rule.ACTION_NAMES;
    String[] values = trigger ? Rule.TRIGGERS : Rule.ACTIONS;
    new AlertDialog.Builder(this)
        .setTitle(trigger ? "Choose your WHEN" : "Choose your THEN")
        .setItems(
            names,
            (d, n) -> {
              if (trigger) {
                draft.when.type = values[n];
                draft
                    .when
                    .set("count", draft.when.get("count").isEmpty() ? "3" : draft.when.get("count"))
                    .set(
                        "minutes",
                        draft.when.get("minutes").isEmpty() ? "5" : draft.when.get("minutes"))
                    .set(
                        "radiusFeet",
                        draft.when.get("radiusFeet").isEmpty()
                            ? "250"
                            : draft.when.get("radiusFeet"));
              } else draft.then.set(index, Rule.action(values[n]));
              render();
            })
        .show();
  }

  private void contact(LinearLayout parent, Rule.Piece p, String key, String label, int index) {
    field(parent, p, key, label, android.text.InputType.TYPE_CLASS_PHONE);
    add(
        parent,
        quiet(
            "Pick from contacts",
            () -> {
              pickIndex = index;
              pickKey = key;
              try {
                startActivityForResult(
                    new Intent(
                        Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI),
                    100);
              } catch (Exception e) {
                alert("Contact picker unavailable", "Enter the phone number manually.");
              }
            }),
        7);
  }

  private void whenOptions(LinearLayout p, Rule.Piece w) {
    String t = w.type;
    if (t.startsWith("call") || t.startsWith("sms"))
      contact(p, w, "contact", "Contact phone number", -1);
    if (t.equals("call_count")) {
      field(p, w, "count", "Number of calls", 2);
      field(p, w, "minutes", "Within this many minutes", 2);
    }
    if (t.equals("sms_matches")) {
      field(
          p,
          w,
          "matchText",
          "Exact message text",
          android.text.InputType.TYPE_CLASS_TEXT
              | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
      add(
          p,
          text(
              "Matches the complete SMS, including case and spaces. RCS is not SMS.",
              13,
              0xFF542A13,
              false),
          8);
    }
    if (t.startsWith("wifi")) {
      field(p, w, "ssid", "Wi-Fi name (SSID)", 1);
      add(
          p,
          text(
              "Use the exact name. An internet outage alone is not a disconnection.",
              13,
              0xFF542A13,
              false),
          8);
    }
    if (t.startsWith("waypoint")) {
      field(p, w, "waypoint", "Place name", 1);
      add(p, quiet("◎  Use my current location", this::captureLocation), 10);
      field(p, w, "latitude", "Latitude", 8194 | 4096);
      field(p, w, "longitude", "Longitude", 8194 | 4096);
      field(p, w, "radiusFeet", "Radius in feet", 2);
      add(
          p,
          text(
              "Starts at 250 feet. Two accurate readings confirm a crossing; GPS can delay small"
                  + " boundaries.",
              13,
              0xFF542A13,
              false),
          8);
    }
  }

  private void thenOptions(LinearLayout p, Rule.Piece a, int index) {
    switch (a.type) {
      case "mute":
      case "unmute":
      case "set_volume":
      case "emergency_loud":
        select(
            p,
            "Sound channels",
            Rule.CHANNEL_NAMES,
            Rule.CHANNELS,
            a.get("channels"),
            v -> a.set("channels", v));
        if (a.type.equals("set_volume")) field(p, a, "percent", "Volume percent (0–100)", 2);
        if (a.type.equals("emergency_loud"))
          add(
              p,
              text(
                  "Requests maximum volume. DND and Bluetooth may still affect what you hear.",
                  13,
                  0xFF352458,
                  false),
              10);
        break;
      case "play_audio":
        filePicker(p, a, index, "audio/*", "Choose audio file");
        field(p, a, "seconds", "Play for up to (seconds, 1–300)", 2);
        break;
      case "show_image":
        filePicker(p, a, index, "image/*", "Choose image");
        field(p, a, "message", "Image caption (optional)", 1);
        break;
      case "show_message":
        field(
            p,
            a,
            "message",
            "Message to show",
            android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        break;
      case "send_sms":
        contact(p, a, "recipient", "Recipient phone number", index);
        field(
            p,
            a,
            "message",
            "Text to send",
            android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        simPicker(p, a);
        add(
            p,
            text(
                "Sends an actual SMS when enabled. Carrier charges may apply. Set a default SMS SIM"
                    + " in phone Settings.",
                13,
                0xFF352458,
                false),
            8);
        break;
    }
  }

  private void simPicker(LinearLayout parent, Rule.Piece action) {
    List<String> names = new ArrayList<>(), ids = new ArrayList<>();
    names.add("Phone's default text SIM");
    ids.add("-1");
    if (Readiness.has(this, Manifest.permission.READ_PHONE_STATE))
      try {
        List<android.telephony.SubscriptionInfo> sims =
            getSystemService(android.telephony.SubscriptionManager.class)
                .getActiveSubscriptionInfoList();
        if (sims != null)
          for (android.telephony.SubscriptionInfo sim : sims) {
            names.add("SIM " + (sim.getSimSlotIndex() + 1) + " ? " + sim.getDisplayName());
            ids.add(String.valueOf(sim.getSubscriptionId()));
          }
      } catch (SecurityException ignored) {
      }
    if (!action.get("subscription").isEmpty() && !ids.contains(action.get("subscription"))) {
      names.add("Saved SIM is unavailable ? choose a SIM");
      ids.add(action.get("subscription"));
    }
    select(
        parent,
        "Send using",
        names.toArray(new String[0]),
        ids.toArray(new String[0]),
        action.get("subscription"),
        v -> action.set("subscription", v));
  }

  private void filePicker(LinearLayout p, Rule.Piece a, int index, String type, String label) {
    add(
        p,
        quiet(
            label,
            () -> {
              pickIndex = index;
              pickKey = "uri";
              Intent i =
                  new Intent(Intent.ACTION_OPEN_DOCUMENT)
                      .setType(type)
                      .addCategory(Intent.CATEGORY_OPENABLE)
                      .addFlags(
                          Intent.FLAG_GRANT_READ_URI_PERMISSION
                              | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
              startActivityForResult(i, 101);
            }),
        10);
    if (!a.get("uri").isEmpty()) add(p, text("✓  " + a.get("fileName"), 13, 0xFF352458, false), 8);
  }

  private void saveDraft() {
    List<String> e = draft.errors();
    if (!e.isEmpty()) {
      alert("Let's finish these pieces", String.join("\n", e));
      return;
    }
    List<Rule> rules = store.rules();
    int existing = -1;
    for (int i = 0; i < rules.size(); i++) if (rules.get(i).id.equals(draft.id)) existing = i;
    // An existing rule keeps its switch setting; new/template rules are already paused.
    if (existing >= 0) rules.set(existing, draft);
    else rules.add(draft);
    try {
      store.save(rules);
      resumeForChanges();
      draft = null;
      page = "rules";
      render();
      toast("Saved. Monitoring resumes automatically; enable new rules after Setup.");
    } catch (Exception ex) {
      alert("Could not save", ex.getMessage());
    }
  }

  private void captureLocation() {
    if (!Readiness.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          200);
      toast("Allow precise location, then tap again.");
      return;
    }
    LocationManager lm = getSystemService(LocationManager.class);
    if (!lm.isLocationEnabled()) {
      open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
      return;
    }
    String provider =
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ? LocationManager.GPS_PROVIDER
            : LocationManager.NETWORK_PROVIDER;
    CancellationSignal signal = new CancellationSignal();
    toast("Getting a fresh location…");
    handler.postDelayed(signal::cancel, 30000);
    try {
      lm.getCurrentLocation(
          provider,
          signal,
          getMainExecutor(),
          l -> {
            if (l == null) {
              toast("No fix yet. Try outdoors or enter coordinates.");
              return;
            }
            if (draft != null) {
              draft.when.set("latitude", l.getLatitude()).set("longitude", l.getLongitude());
              render();
              toast(
                  "Location captured · accuracy " + Math.round(l.getAccuracy() * 3.28084) + " ft");
            }
          });
    } catch (SecurityException e) {
      alert("Location access changed", "Grant precise location and try again.");
    }
  }

  @android.annotation.SuppressLint(
      "BatteryLife") // Personal emergency/task-automation use case, with explicit owner-directed
  // setup.
  private void setup() {
    title(
        "GET YOUR PIECES READY",
        "Phone setup",
        "Grant only the access your rules need. Green access checks are not a completed emergency"
            + " test.");
    LinearLayout updateCard = card(0xFFEDE6FC);
    add(updateCard, text("App updates", 20, INK, true), 0);
    updateStatus = text(updates.status(), 14, MUTED, false);
    add(updateCard, updateStatus, 8);
    add(
        updateCard,
        text(
            "Checks GitHub when you open the app. You choose when to install; your rules and"
                + " contacts are never uploaded.",
            14,
            MUTED,
            false),
        8);
    add(updateCard, quiet("Check for updates", () -> updates.check(true)), 12);
    add(body, updateCard, 14);
    setupItem(
        "Notifications",
        "Shows monitoring status and rule messages.",
        getSystemService(NotificationManager.class).areNotificationsEnabled(),
        () -> {
          if (Build.VERSION.SDK_INT >= 33
              && !Readiness.has(this, Manifest.permission.POST_NOTIFICATIONS))
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 200);
          else
            open(
                new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
        });
    setupItem(
        "Calls & caller identity",
        "Phone state plus call log. Your installer may restrict call-log access.",
        Readiness.has(this, Manifest.permission.READ_PHONE_STATE)
            && Readiness.has(this, Manifest.permission.READ_CALL_LOG),
        () ->
            requestPermissions(
                new String[] {
                  Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG
                },
                200));
    setupItem(
        "Receive texts",
        "Incoming SMS only; RCS and chat apps use different systems.",
        Readiness.has(this, Manifest.permission.RECEIVE_SMS),
        () -> requestPermissions(new String[] {Manifest.permission.RECEIVE_SMS}, 200));
    setupItem(
        "Send texts",
        "Automatic outgoing SMS for enabled Send a text blocks.",
        Readiness.has(this, Manifest.permission.SEND_SMS),
        () -> requestPermissions(new String[] {Manifest.permission.SEND_SMS}, 200));
    setupItem(
        "Precise location & Wi-Fi names",
        "Used for places and SSID identity. Turn Location on too.",
        Readiness.has(this, Manifest.permission.ACCESS_FINE_LOCATION) && Readiness.locationOn(this),
        () -> {
          if (!Readiness.has(this, Manifest.permission.ACCESS_FINE_LOCATION))
            requestPermissions(
                new String[] {
                  Manifest.permission.ACCESS_FINE_LOCATION,
                  Manifest.permission.ACCESS_COARSE_LOCATION
                },
                200);
          else open(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
        });
    setupItem(
        "Background location",
        "Choose Allow all the time for automatic Wi-Fi and place monitoring after reboot. Grant"
            + " precise location first.",
        Readiness.has(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
        () -> {
          if (!Readiness.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            toast("Grant precise location first.");
            return;
          }
          open(
              new Intent(
                  Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                  Uri.parse("package:" + getPackageName())));
        });
    setupItem(
        "Do Not Disturb access",
        "Allows Android-permitted ringer changes. Other DND rules remain in effect.",
        Readiness.dndAccess(this),
        () -> open(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)));
    setupItem(
        "Battery unrestricted",
        "Avoid battery optimization for your monitoring session.",
        Readiness.exempt(this),
        () ->
            open(
                new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()))));
    LinearLayout samsung = card(0xFFFFF0D9);
    add(samsung, text("One more step on Samsung", 18, INK, true), 0);
    add(
        samsung,
        text(
            "Settings → Battery → Background usage limits. Remove WhenThen from Sleeping and Deep"
                + " sleeping apps, then add it to Never sleeping apps (labels may vary). After"
                + " first installation, open WhenThen once and complete Setup. Reboot and update"
                + " restart monitoring automatically. After an Android force-stop, open the app"
                + " again.",
            14,
            MUTED,
            false),
        8);
    add(
        samsung,
        quiet(
            "Open app settings",
            () ->
                open(
                    new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())))),
        12);
    add(body, samsung, 14);
    LinearLayout verification = card(Color.WHITE);
    add(verification, text("Your real-phone checklist", 20, INK, true), 0);
    add(
        verification,
        text(
            "Verify with a trusted test contact before relying on an emergency rule. Checks below"
                + " record your observations; permission grants alone never mark these passed.",
            14,
            MUTED,
            false),
        8);
    String[] checks = {
      "Incoming, repeated & missed calls",
      "SMS receive and outgoing delivery",
      "Locked-screen speaker audio",
      "DND, silent mode & Bluetooth",
      "Home Wi-Fi transitions",
      "Place entry and exit",
      "Extended idle & battery saver",
      "Reboot / interruption recovery"
    };
    for (int n = 0; n < checks.length; n++) {
      String key = "verified_" + n;
      CheckBox box = new CheckBox(this);
      box.setText(checks[n]);
      box.setTextColor(INK);
      box.setChecked(store.prefs().getBoolean(key, false));
      box.setOnCheckedChangeListener(
          (v, checked) ->
              store
                  .prefs()
                  .edit()
                  .putBoolean(key, checked)
                  .putLong(key + "_at", System.currentTimeMillis())
                  .apply());
      add(verification, box, 6);
    }
    add(body, verification, 14);
    LinearLayout backup = card(0xFFE7F4F1);
    add(backup, text("Keep your creations", 20, INK, true), 0);
    add(
        backup,
        text(
            "Backups contain your contacts, message text and rules. Store them privately. Imports"
                + " are paused, and files must be chosen again on another phone.",
            14,
            MUTED,
            false),
        8);
    pair(
        backup,
        quiet("Export rules", () -> export(store.exportRules(), "WhenThen-rules.json")),
        quiet(
            "Import rules",
            () ->
                startActivityForResult(
                    new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("application/json")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    103)));
    add(body, backup, 14);
    add(
        body,
        text(
            "WhenThen "
                + BuildConfig.VERSION_NAME
                + " · personal edition\nLocal storage · no account · no analytics\nAndroid "
                + Build.VERSION.RELEASE
                + " / API "
                + Build.VERSION.SDK_INT
                + " · "
                + Build.MANUFACTURER
                + " "
                + Build.MODEL,
            12,
            MUTED,
            false),
        20);
  }

  private void setupItem(String heading, String detail, boolean granted, Runnable action) {
    LinearLayout l = card(Color.WHITE);
    add(l, text((granted ? "✓  " : "○  ") + heading, 18, granted ? TEAL : INK, true), 0);
    add(l, text(detail, 14, MUTED, false), 7);
    add(l, quiet(granted ? "Review access" : "Set up", action), 12);
    add(body, l, 12);
  }

  private void activity() {
    title(
        "SEE WHAT HAPPENED",
        "Activity",
        "A local record of matching rules, observed results, and anything that needs attention.");
    pair(
        body,
        quiet("Export history", () -> export(store.exportHistory(), "WhenThen-history.json")),
        quiet(
            "Clear history",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("Clear local history?")
                    .setPositiveButton(
                        "Clear",
                        (d, w) -> {
                          store.clearHistory();
                          render();
                        })
                    .setNegativeButton("Keep", null)
                    .show()));
    List<Store.Entry> list = store.history();
    if (list.isEmpty())
      add(
          body,
          text(
              "Nothing yet. Try a simulation, or start monitoring an enabled rule.",
              16,
              MUTED,
              false),
          24);
    for (Store.Entry e : list) {
      LinearLayout c = card(Color.WHITE);
      int color =
          e.status.equals("failed") || e.status.equals("blocked")
              ? ORANGE
              : e.status.equals("simulation") ? PURPLE : TEAL;
      add(c, pill(e.status.toUpperCase(Locale.US), 0xFFF0ECF8, color), 0);
      add(c, text(e.title, 17, INK, true), 10);
      add(c, text(e.detail, 14, MUTED, false), 7);
      add(
          c,
          text(
              DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                  .format(new Date(e.at)),
              12,
              MUTED,
              false),
          10);
      add(body, c, 12);
    }
  }

  private void lab() {
    title(
        "A SAFE LITTLE SANDBOX",
        "Try your blocks",
        "Simulate a matching event. No texts are sent, no volume changes, and no files play.");
    List<Rule> rules = store.rules();
    if (rules.isEmpty()) {
      add(body, button("Build your first rule", PURPLE, this::newRule), 20);
      return;
    }
    for (Rule r : rules) {
      LinearLayout c = block(0xFFB8E9DD);
      add(c, text(r.name, 22, INK, true), 0);
      add(
          c,
          text(
              "WHEN "
                  + Rule.label(r.when.type)
                  + "\nTHEN "
                  + r.then.size()
                  + " action"
                  + (r.then.size() == 1 ? "" : "s"),
              14,
              INK,
              false),
          8);
      add(c, button("▶  Simulate this flow", TEAL, () -> simulate(r)), 14);
      add(body, c, 16);
    }
    LinearLayout note = card(Color.WHITE);
    add(note, text("What simulation proves", 18, INK, true), 0);
    add(
        note,
        text(
            "It exercises the actual matcher and lists the planned actions. Phone permissions,"
                + " carrier delivery, GPS, Wi-Fi identity and audible output need separate device"
                + " tests.",
            14,
            MUTED,
            false),
        8);
    add(body, note, 18);
  }

  private void simulate(Rule original) {
    Rule r = Store.copy(original);
    r.enabled = true;
    RuleEngine engine = new RuleEngine();
    long now = System.currentTimeMillis();
    List<Rule> matched = new ArrayList<>();
    String type = r.when.type;
    int count = type.equals("call_count") ? (int) r.when.number("count", 3) : 1;
    for (int i = 0; i < count; i++) {
      RuleEngine.Event e =
          new RuleEngine.Event(
              "sim-" + i,
              type.equals("call_count")
                  ? "call_incoming"
                  : type.equals("sms_matches") ? "sms_received" : type,
              now + i * 1000);
      e.contact = r.when.get("contact");
      e.text = r.when.get("matchText");
      e.ssid = r.when.get("ssid");
      if (type.startsWith("waypoint")) break;
      matched = engine.accept(Collections.singletonList(r), e, now + i * 1000);
    }
    if (type.startsWith("waypoint")) {
      boolean enter = type.equals("waypoint_enter");
      for (int i = 0; i < 3; i++) {
        RuleEngine.Event e = new RuleEngine.Event("loc-" + i, "location", now + i * 15000);
        boolean inside = i == 0 ? !enter : enter;
        e.latitude =
            r.when.number("latitude", 0)
                + (inside ? 0 : (r.when.number("radiusFeet", 250) * 0.3048 + 100) / 111000);
        e.longitude = r.when.number("longitude", 0);
        e.accuracy = 2;
        matched = engine.accept(Collections.singletonList(r), e, e.at);
      }
    }
    StringBuilder result =
        new StringBuilder(
            matched.isEmpty()
                ? "No match. Check this rule's configuration."
                : "✓ WHEN matched\n\n");
    if (!matched.isEmpty())
      for (int i = 0; i < r.then.size(); i++)
        result
            .append(i + 1)
            .append(". Would ")
            .append(Rule.label(r.then.get(i).type).toLowerCase(Locale.US))
            .append("\n");
    result.append("\nSimulation only. No phone actions ran.");
    store.log(r.name, result.toString(), "simulation");
    alert("Your flow, step by step", result.toString());
  }

  private void export(String value, String name) {
    exportText = value;
    startActivityForResult(
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, name),
        102);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    try {
      if (request == 100 && draft != null) {
        Rule.Piece p = pickIndex < 0 ? draft.when : draft.then.get(pickIndex);
        try (Cursor cursor =
            getContentResolver()
                .query(
                    uri,
                    new String[] {
                      ContactsContract.CommonDataKinds.Phone.NUMBER,
                      ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    },
                    null,
                    null,
                    null)) {
          if (cursor != null && cursor.moveToFirst()) {
            p.set(pickKey, cursor.getString(0));
            p.set("contactName", cursor.getString(1));
          }
        }
        render();
      }
      if (request == 101 && draft != null && pickIndex >= 0) {
        getContentResolver()
            .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Rule.Piece p = draft.then.get(pickIndex);
        p.set("uri", uri.toString());
        String name = "Selected file";
        try (Cursor cursor =
            getContentResolver()
                .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
          if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        p.set("fileName", name);
        render();
      }
      if (request == 102 && exportText != null) {
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
          if (out == null) throw new IOException("Could not open destination.");
          out.write(exportText.getBytes(StandardCharsets.UTF_8));
        }
        toast("JSON file saved.");
        exportText = null;
      }
      if (request == 103) {
        String text;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
          if (in == null) throw new IOException("Could not open backup.");
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          byte[] chunk = new byte[8192];
          int length;
          while ((length = in.read(chunk)) != -1) {
            if (bytes.size() + length > 2_000_000) throw new IOException("Backup is too large.");
            bytes.write(chunk, 0, length);
          }
          text = bytes.toString(StandardCharsets.UTF_8.name());
        }
        List<Rule> imported = store.parseImport(text);
        new AlertDialog.Builder(this)
            .setTitle("Import " + imported.size() + " rules?")
            .setMessage(
                "This replaces your current rules. Export a backup first if you want to keep them."
                    + " Every imported rule starts paused.")
            .setPositiveButton(
                "Replace rules",
                (d, w) -> {
                  try {
                    store.save(imported);
                    resumeForChanges();
                    render();
                    toast("Imported with every rule paused.");
                  } catch (Exception ex) {
                    alert("Import failed", ex.getMessage());
                  }
                })
            .setNegativeButton("Cancel", null)
            .show();
      }
      if (request == 104) {
        List<RuleTemplate> templates;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
          templates = RuleTemplate.decode(TemplateLibrary.read(input));
        }
        new AlertDialog.Builder(this)
            .setTitle("Import " + templates.size() + " templates?")
            .setMessage(
                "Adds reusable drafts to your library. Existing rules and templates are kept."
                    + " Nothing is enabled or run.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton(
                "Add templates",
                (d, w) -> {
                  try {
                    int added = templateLibrary.add(templates);
                    page = "templates";
                    render();
                    toast(added + " templates added.");
                  } catch (Exception e) {
                    alert("Import failed", e.getMessage());
                  }
                })
            .show();
      }
    } catch (Exception e) {
      alert("Couldn't finish that", e.getMessage() == null ? e.toString() : e.getMessage());
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    Monitoring.onOpen(this);
    if (draft == null) render();
  }

  private void showIntent(Intent i) {
    if (i == null) return;
    String detail = i.getStringExtra("detail"), image = i.getStringExtra("image");
    if ((detail == null || detail.isEmpty()) && (image == null || image.isEmpty())) return;
    LinearLayout content = column();
    content.setPadding(dp(20), dp(10), dp(20), dp(10));
    if (detail != null) add(content, text(detail, 18, INK, false), 0);
    if (image != null && !image.isEmpty())
      try {
        ImageView view = new ImageView(this);
        view.setImageBitmap(Notices.bitmap(this, Uri.parse(image)));
        view.setAdjustViewBounds(true);
        view.setContentDescription("Rule image");
        add(content, view, 12);
      } catch (Exception e) {
        add(content, text("Image unavailable. Choose it again in the rule.", 14, MUTED, false), 8);
      }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(content);
    new AlertDialog.Builder(this)
        .setTitle("WhenThen")
        .setView(scroll)
        .setPositiveButton("Close", null)
        .show();
    i.removeExtra("detail");
    i.removeExtra("image");
  }

  private void open(Intent i) {
    try {
      startActivity(i);
    } catch (Exception e) {
      alert(
          "Open phone Settings",
          "This shortcut is unavailable on this device. Use Android Settings → Apps → WhenThen.");
    }
  }

  private void alert(String title, String message) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("Got it", null)
        .show();
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }
}
