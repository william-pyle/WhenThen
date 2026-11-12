package com.jazcosolutions.whenthen;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;

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
  public static final int BG = BrickStyle.BACKGROUND,
      INK = BrickStyle.INK, MUTED = BrickStyle.MUTED,
      ORANGE = BrickStyle.DISABLED, PURPLE = BrickStyle.BLUE, TEAL = BrickStyle.ENABLED;
  private LinearLayout root, body, nav;
  private TextView updateStatus;
  private LinearLayout monitorCard;
  private TextView monitorTitle, monitorDetail, monitorHealth;
  private Button monitorButton;
  private ScrollView scroll;
  private Store store;
  private TemplateLibrary templateLibrary;
  AppUpdater updates;
  private String page = "overview";
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
      page = state.getString("page", "overview");
      if (page.equals("rules")) page = "overview";
      if (page.equals("lab")) page = "blocks";
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
      new BrickDialogBuilder(this)
          .setTitle("Leave this builder?")
          .setMessage("Your unsaved changes will be discarded.")
          .setPositiveButton(
              "Leave",
              (d, w) -> {
                draft = null;
                page = "blocks";
                render();
              })
          .setNegativeButton("Keep building", null)
          .show();
    } else if (!page.equals("overview")) {
      page = "overview";
      render();
    } else finish();
  }

  private int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
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
    BrickStyle.button(b, color);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private Button quiet(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    BrickStyle.secondaryButton(b);
    b.setOnClickListener(v -> action.run());
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
    l.setPadding(dp(16), dp(16), dp(16), dp(16));
    l.setBackground(BrickStyle.panel(this, color));
    l.setElevation(dp(2));
    return l;
  }

  private LinearLayout block(int color) {
    LinearLayout l = column();
    l.setPadding(dp(12), dp(30), dp(12), dp(18));
    l.setBackground(new BlockDrawable(color, getResources().getDisplayMetrics().density));
    return l;
  }

  private void connectedBlock(LinearLayout parent, View view, boolean when, int gap) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.leftMargin = dp(when ? 0 : 40);
    params.rightMargin = dp(when ? 40 : 0);
    params.topMargin = dp(gap);
    parent.addView(view, params);
  }

  private LinearLayout connectedStack() {
    // Lower bricks sit behind the brick above, so their studs enter its underside.
    return new LinearLayout(this) {
      {
        setOrientation(VERTICAL);
        setChildrenDrawingOrderEnabled(true);
      }

      @Override
      protected int getChildDrawingOrder(int count, int position) {
        return count - 1 - position;
      }
    };
  }

  private TextView pill(String label, int color, int fg) {
    TextView t = text(label, 12, fg, true);
    t.setBackground(BrickStyle.smallPanel(this, color));
    t.setPadding(dp(10), dp(7), dp(10), dp(7));
    return t;
  }

  private void title(String eyebrow, String heading, String description) {
    add(body, text(eyebrow.toUpperCase(Locale.US), 11, MUTED, true), 6);
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
    brand.setPadding(dp(24), dp(12), dp(24), dp(10));
    ImageView icon = new ImageView(this);
    icon.setImageResource(R.drawable.icon_art);
    icon.setContentDescription("WhenThen interlocking blocks logo");
    brand.addView(icon, new LinearLayout.LayoutParams(dp(38), dp(38)));
    TextView name = text("  WhenThen", 21, INK, true);
    name.setSingleLine(true);
    brand.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(brand);
    scroll = new ScrollView(this);
    scroll.setDefaultFocusHighlightEnabled(false);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    body = column();
    body.setPadding(dp(24), dp(6), dp(24), dp(26));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    nav = row();
    nav.setPadding(dp(12), dp(6), dp(12), dp(8));
    nav.setBackgroundColor(Color.WHITE);
    String[] tabs = {"overview", "blocks", "activity", "setup"};
    String[] labels = {"Overview", "Blocks", "Activity", "Setup"};
    for (int n = 0; n < tabs.length; n++) {
      String dest = tabs[n];
      Button b = new Button(this);
      b.setText(labels[n]);
      b.setOnClickListener(v -> navigate(dest));
      b.setTextSize(12);
      b.setAllCaps(false);
      b.setTextColor(page.equals(dest) ? PURPLE : MUTED);
      b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
      b.setContentDescription(labels[n]);
      b.setSelected(page.equals(dest));
      b.setPadding(0, 0, 0, 0);
      b.setBackground(BrickStyle.smallPanel(this, page.equals(dest) ? 0xFFE8EFFB : Color.WHITE));
      b.setStateListAnimator(null);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
      p.setMargins(dp(2), 0, dp(2), 0);
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
          case "blocks":
            blocks();
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
      new BrickDialogBuilder(this)
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
    LinearLayout hero = column();
    LinearLayout labels = connectedStack();
    LinearLayout whenBrick = block(BrickStyle.WHEN), thenBrick = block(BrickStyle.THEN);
    add(whenBrick, text("WHEN  something happens", 17, INK, true), 0);
    add(thenBrick, text("THEN  do something", 17, INK, true), 0);
    connectedBlock(labels, whenBrick, true, 0);
    connectedBlock(labels, thenBrick, false, -14);
    add(hero, labels, 0);
    add(body, hero, 8);

    boolean active = MonitorService.active != null;
    LinearLayout status = card(BrickStyle.SURFACE);
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
    monitorCard.setBackground(BrickStyle.panel(this, BrickStyle.SURFACE));
    monitorTitle.setText(active ? "●  Monitoring is on" : "○  Monitoring is paused");
    monitorTitle.setTextColor(INK);
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
    new BrickDialogBuilder(this)
        .setTitle(r.name)
        .setItems(
            new String[] {"Test", "Try in simulator", "Duplicate", "Save as template", "Delete"},
            (d, n) -> {
              if (n == 0) testRule(r);
              else if (n == 1) simulate(r);
              else if (n == 2) {
                Rule copy = Store.copy(r);
                copy.id = UUID.randomUUID().toString();
                copy.name = r.name + " copy";
                copy.enabled = false;
                draft = copy;
                editorPage();
              } else if (n == 3) saveAsTemplate(r);
              else
                new BrickDialogBuilder(this)
                    .setTitle("Delete this block?")
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

  private void testRule(Rule rule) {
    new BrickDialogBuilder(this)
        .setTitle("Run a real test?")
        .setMessage(FlowSummary.testDescription(rule))
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Run test",
            (d, w) -> {
              List<String> missing = Readiness.missingActions(this, rule);
              if (!missing.isEmpty()) {
                store.log(rule.name, String.join("\n", missing), "blocked");
                alert("Finish setup for these actions", String.join("\n", missing));
                return;
              }
              try {
                startForegroundService(
                    new Intent(this, MonitorService.class)
                        .setAction("TEST")
                        .putExtra("ruleId", rule.id));
                toast("Test started. See Activity for the results.");
                page = "activity";
                render();
                handler.postDelayed(
                    () -> {
                      if (draft == null && page.equals("activity") && !isFinishing()) render();
                    },
                    900);
              } catch (RuntimeException error) {
                store.failure(rule.name, error, "Manual test startup", rule);
                alert(
                    "Test could not start", "See Activity for details and a GitHub report button.");
              }
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
        "READY TO CUSTOMIZE",
        "Your template library",
        "Choose a starting point, adjust its details, and save your own rule.");
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
      add(tile, pill("WHEN  " + Rule.label(template.rule.when.type), 0xFFFFF4D5, INK), 12);
      for (Rule.Piece action : template.rule.then)
        add(tile, pill("THEN  " + Rule.label(action.type), 0xFFF0EBF8, INK), 4);
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
                new BrickDialogBuilder(this)
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
    new BrickDialogBuilder(this)
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
    new BrickDialogBuilder(this)
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
    page = "blocks";
    render();
  }

  private EditText input(
      LinearLayout parent, String label, String value, int inputType, Consumer<String> change) {
    LinearLayout option = card(parent == body ? BrickStyle.SURFACE : BrickStyle.SUBTLE);
    if (parent != body) option.setElevation(0);
    option.setPadding(dp(12), dp(12), dp(12), dp(8));
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
    LinearLayout option = card(BrickStyle.SUBTLE);
    option.setElevation(0);
    option.setPadding(dp(10), dp(12), dp(10), dp(6));
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
        "CREATE OR EDIT",
        "Build your rule",
        "Choose what starts the rule, then add the actions to run.");
    input(body, "Rule name", draft.name, 1, v -> draft.name = v);
    LinearLayout when = card(BrickStyle.SURFACE);
    add(when, text("WHEN", 12, PURPLE, true), 0);
    add(when, text(Rule.label(draft.when.type), 23, INK, true), 7);
    add(when, quiet("Change trigger", () -> choosePiece(true, -1)), 12);
    whenOptions(when, draft.when);
    add(body, when, 18);
    for (int i = 0; i < draft.then.size(); i++) {
      int index = i;
      Rule.Piece piece = draft.then.get(i);
      LinearLayout then = card(BrickStyle.SURFACE);
      add(then, text(String.format(Locale.US, "THEN %d", i + 1), 12, PURPLE, true), 0);
      add(then, text(Rule.label(piece.type), 23, INK, true), 7);
      add(then, quiet("Change action", () -> choosePiece(false, index)), 12);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        controls.addView(b, lp);
      }
      add(then, controls, 14);
      add(body, then, 12);
    }
    add(
        body,
        quiet(
            "+  Add another action",
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
    new BrickDialogBuilder(this)
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
              INK,
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
              INK,
              false),
          8);
    }
    if (t.startsWith("waypoint")) {
      field(p, w, "waypoint", "Place name", 1);
      boolean selected =
          MapSelection.validPoint(
              w.number("latitude", Double.NaN), w.number("longitude", Double.NaN));
      add(
          p,
          quiet(
              selected ? "◎  Change location on map" : "◎  Choose location on map",
              this::pickLocation),
          10);
      add(
          p,
          text(
              selected
                  ? "Location selected. Open the map to review the pin and boundary."
                  : "Search an address or tap the map, then use that location.",
              13,
              INK,
              false),
          8);
      field(p, w, "radiusFeet", "Radius in feet", 2);
      add(
          p,
          text(
              "Starts at 250 feet. Two accurate readings confirm a crossing; GPS can delay small"
                  + " boundaries.",
              13,
              INK,
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
                  INK,
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
                INK,
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
    if (!a.get("uri").isEmpty()) add(p, text("✓  " + a.get("fileName"), 13, INK, false), 8);
  }

  private void saveDraft() {
    List<String> e = draft.errors();
    if (!e.isEmpty()) {
      alert("Finish these rule details", String.join("\n", e));
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
      page = "blocks";
      render();
      toast("Saved. Monitoring resumes automatically; enable new rules after Setup.");
    } catch (Exception ex) {
      alert("Could not save", ex.getMessage());
    }
  }

  private void pickLocation() {
    if (draft == null) return;
    Rule.Piece place = draft.when;
    startActivityForResult(
        new Intent(this, MapPickerActivity.class)
            .putExtra("latitude", place.number("latitude", Double.NaN))
            .putExtra("longitude", place.number("longitude", Double.NaN))
            .putExtra("name", place.get("waypoint"))
            .putExtra("radiusFeet", place.get("radiusFeet")),
        105);
  }

  @android.annotation.SuppressLint(
      "BatteryLife") // Personal emergency/task-automation use case, with explicit owner-directed
  // setup.
  private void setup() {
    title(
        "APP ACCESS",
        "Phone setup",
        "A check means access is ready. Tap a permission for details, or its button to change it.");
    LinearLayout updateCard = card(BrickStyle.SURFACE);
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
        "Calls & caller ID",
        "Phone state plus call log. Your installer may restrict call-log access.",
        Readiness.has(this, Manifest.permission.READ_PHONE_STATE)
            && Readiness.has(this, Manifest.permission.READ_CALL_LOG),
        () ->
            permissionAccess(
                Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG));
    setupItem(
        "Receive texts",
        "Incoming SMS only; RCS and chat apps use different systems.",
        Readiness.has(this, Manifest.permission.RECEIVE_SMS),
        () -> permissionAccess(Manifest.permission.RECEIVE_SMS));
    setupItem(
        "Send texts",
        "Automatic outgoing SMS for enabled Send a text blocks.",
        Readiness.has(this, Manifest.permission.SEND_SMS),
        () -> permissionAccess(Manifest.permission.SEND_SMS));
    setupItem(
        "Location & Wi-Fi",
        "Used for places and SSID identity. Turn Location on too.",
        Readiness.has(this, Manifest.permission.ACCESS_FINE_LOCATION) && Readiness.locationOn(this),
        () -> {
          if (!Readiness.has(this, Manifest.permission.ACCESS_FINE_LOCATION))
            permissionAccess(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION);
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
        "Do Not Disturb",
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
    Button samsung =
        quiet(
            "Samsung battery help",
            () ->
                new BrickDialogBuilder(this)
                    .setTitle("Keep WhenThen awake")
                    .setMessage(
                        "On Samsung: Settings → Battery → Background usage limits. Add WhenThen to"
                            + " Never sleeping apps and remove it from Sleeping or Deep sleeping"
                            + " apps. Labels vary by phone. After an Android force-stop, open"
                            + " WhenThen again.")
                    .setPositiveButton(
                        "Open settings",
                        (d, w) ->
                            open(
                                new Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Close", null)
                    .show());
    add(body, samsung, 10);
    add(body, updateCard, 14);
    LinearLayout backup = card(BrickStyle.SURFACE);
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
    LinearLayout line = row();
    line.setPadding(dp(12), dp(17), dp(6), dp(4));
    line.setBackground(BrickStyle.panel(this, Color.WHITE));
    TextView check = text(granted ? "✓" : "○", 18, granted ? TEAL : ORANGE, true);
    check.setContentDescription(granted ? "Access ready" : "Access needed");
    line.addView(check, new LinearLayout.LayoutParams(dp(27), -2));
    TextView label = text(heading, 14, INK, true);
    label.setMaxLines(2);
    label.setMinHeight(dp(48));
    label.setGravity(Gravity.CENTER_VERTICAL);
    label.setContentDescription(heading + ". " + (granted ? "Ready. " : "Needs access. ") + detail);
    label.setOnClickListener(v -> alert(heading, detail));
    label.setFocusable(true);
    line.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    Button access = quiet(granted ? "Review" : "Allow", action);
    access.setTextSize(12);
    access.setMinHeight(dp(52));
    access.setPadding(dp(6), dp(6), dp(6), dp(6));
    access.setContentDescription((granted ? "Review " : "Allow ") + heading);
    line.addView(access, new LinearLayout.LayoutParams(dp(76), dp(52)));
    add(body, line, 5);
  }

  private void permissionAccess(String... permissions) {
    boolean granted = Arrays.stream(permissions).allMatch(p -> Readiness.has(this, p));
    boolean permanentlyDenied =
        Arrays.stream(permissions)
            .anyMatch(
                p ->
                    !Readiness.has(this, p)
                        && store.prefs().getBoolean("requested_" + p, false)
                        && !shouldShowRequestPermissionRationale(p));
    if (granted || permanentlyDenied) {
      open(
          new Intent(
              Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + getPackageName())));
    } else {
      for (String permission : permissions)
        store.prefs().edit().putBoolean("requested_" + permission, true).apply();
      requestPermissions(permissions, 200);
    }
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
                new BrickDialogBuilder(this)
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
              "Nothing yet. Test a block from its More menu, or enable it for automatic runs.",
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
      add(c, pill(e.status.toUpperCase(Locale.US),
          color == TEAL ? 0xFFE8F5EF : color == ORANGE ? 0xFFFCEDEC : 0xFFE8EFFB,
          color), 0);
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
      if (FailureReport.reportable(e.status))
        add(
            c,
            quiet(
                "Report on GitHub",
                () ->
                    startActivity(
                        new Intent(this, FailureReportActivity.class)
                            .putExtra("entry", Store.json(e)))),
            12);
      add(body, c, 12);
    }
  }

  private void blocks() {
    title("YOUR AUTOMATIONS", "Blocks", "");
    pair(
        body,
        button("Add Block", PURPLE, this::newRule),
        quiet("Explore Templates", this::templates));
    List<Rule> rules = store.rules();
    if (rules.isEmpty()) {
      add(body, text("No blocks yet. Add a block or start with a template.", 16, MUTED, false), 24);
      return;
    }
    for (Rule rule : rules) {
      LinearLayout ruleCard = card(BrickStyle.SURFACE);
      LinearLayout header = row();
      LinearLayout heading = column();
      TextView name = text(rule.name, 19, INK, true);
      name.setMaxLines(3);
      add(heading, name, 0);
      TextView stateLabel = text(rule.enabled ? "Enabled" : "Disabled", 13,
          rule.enabled ? TEAL : ORANGE, true);
      add(heading, stateLabel, 4);
      header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
      Switch enabled = new Switch(this);
      enabled.setContentDescription("Enable " + rule.name);
      enabled.setChecked(rule.enabled);
      BrickStyle.toggle(enabled, rule.enabled);
      header.addView(enabled, new LinearLayout.LayoutParams(-2, dp(48)));
      add(ruleCard, header, 0);
      summaryRow(ruleCard, "WHEN", FlowSummary.when(rule.when), 14);
      for (int i = 0; i < rule.then.size(); i++)
        summaryRow(ruleCard, "THEN " + (i + 1), FlowSummary.then(rule.then.get(i)), 8);
      pair(ruleCard,
          quiet("Edit", () -> { draft = Store.copy(rule); editorPage(); }),
          quiet("More", () -> ruleMenu(rule)));
      enabled.setOnCheckedChangeListener((view, checked) -> {
        if (checked == rule.enabled) return;
        if (checked) {
          List<String> missing = Readiness.missing(this, rule);
          if (!missing.isEmpty()) {
            enabled.setChecked(false);
            alert("Finish setup first", String.join("\n", missing));
            return;
          }
        }
        rule.enabled = checked;
        saveChangedRule(rule);
        stateLabel.setText(checked ? "Enabled" : "Disabled");
        stateLabel.setTextColor(checked ? TEAL : ORANGE);
      });
      add(body, ruleCard, 14);
    }
  }

  private void summaryRow(LinearLayout parent, String kind, String summary, int top) {
    LinearLayout item = column();
    item.setPadding(dp(12), dp(10), dp(12), dp(10));
    item.setBackground(BrickStyle.smallPanel(this, BrickStyle.SUBTLE));
    add(item, text(kind, 11, PURPLE, true), 0);
    add(item, text(summary, 14, INK, false), 4);
    add(parent, item, top);
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
    if (request == 105) {
      if (result == RESULT_OK
          && data != null
          && draft != null
          && draft.when.type.startsWith("waypoint")) {
        double latitude = data.getDoubleExtra("latitude", Double.NaN);
        double longitude = data.getDoubleExtra("longitude", Double.NaN);
        int radius = MapSelection.radius(data.getStringExtra("radiusFeet"));
        if (MapSelection.validPoint(latitude, longitude) && radius > 0) {
          String name = data.getStringExtra("name");
          draft
              .when
              .set("latitude", latitude)
              .set("longitude", longitude)
              .set("waypoint", name == null || name.isBlank() ? "Selected place" : name)
              .set("radiusFeet", radius);
          render();
        }
      }
      return;
    }
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
        new BrickDialogBuilder(this)
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
        new BrickDialogBuilder(this)
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
    scroll.setDefaultFocusHighlightEnabled(false);
    scroll.addView(content);
    new BrickDialogBuilder(this)
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
    new BrickDialogBuilder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("Got it", null)
        .show();
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_LONG).show();
  }
}
