package com.jazcosolutions.whenthen;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import com.google.gson.Gson;

public final class FailureReportActivity extends Activity {
  private EditText title, report;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    Store.Entry entry = new Gson().fromJson(getIntent().getStringExtra("entry"), Store.Entry.class);
    if (entry == null) {
      finish();
      return;
    }
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(MainActivity.BG);
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          android.graphics.Insets b =
              i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
          v.setPadding(dp(16) + b.left, b.top + dp(8), dp(16) + b.right, b.bottom + dp(8));
          return i;
        });
    TextView heading = new TextView(this);
    heading.setText("Report a failure");
    heading.setTextSize(23);
    heading.setTextColor(MainActivity.INK);
    root.addView(heading);
    TextView help = new TextView(this);
    help.setText(
        "Review the details, then open GitHub to submit a public issue. Sign in there if needed."
            + " Nothing is posted automatically.");
    help.setTextColor(MainActivity.MUTED);
    root.addView(help);
    title = new EditText(this);
    title.setSingleLine(true);
    title.setBackground(BrickStyle.panel(this, android.graphics.Color.WHITE));
    title.setPadding(dp(8), dp(8), dp(8), dp(8));
    title.setContentDescription("Issue title");
    title.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(160)});
    title.setText(saved == null ? FailureReport.title(entry) : saved.getString("title"));
    root.addView(title);
    report = new EditText(this);
    report.setGravity(Gravity.TOP);
    report.setBackground(BrickStyle.panel(this, android.graphics.Color.WHITE));
    report.setPadding(dp(8), dp(8), dp(8), dp(8));
    report.setTextSize(13);
    report.setTypeface(Typeface.MONOSPACE);
    report.setContentDescription("Issue details");
    report.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
    java.util.List<Rule> rules = java.util.List.of();
    try {
      rules = new Store(this).rules();
    } catch (RuntimeException ignored) {
    }
    report.setText(saved == null ? FailureReport.body(entry, rules) : saved.getString("report"));
    root.addView(report, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout buttons = new LinearLayout(this);
    addButton(buttons, "Copy details", this::copy);
    addButton(buttons, "Open GitHub", this::openGithub);
    root.addView(buttons);
    Button close = new Button(this);
    close.setText("Close");
    BrickStyle.button(close, BrickStyle.BLUE);
    close.setOnClickListener(v -> finish());
    root.addView(close);
  }

  private void addButton(LinearLayout row, String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    BrickStyle.button(b, BrickStyle.BLUE);
    b.setOnClickListener(v -> action.run());
    row.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
  }

  private int dp(int n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private void copy() {
    getSystemService(ClipboardManager.class)
        .setPrimaryClip(
            ClipData.newPlainText("WhenThen failure report", report.getText().toString()));
    Toast.makeText(this, "Full report copied", Toast.LENGTH_SHORT).show();
  }

  private void openGithub() {
    String body = report.getText().toString(),
        url = FailureReport.url(title.getText().toString(), body);
    if (url.length() > FailureReport.MAX_URL) {
      copy();
      new BrickDialogBuilder(this)
          .setTitle("Full report copied")
          .setMessage(
              "This report is too long for a web link. Paste it into GitHub’s description before"
                  + " submitting.")
          .setPositiveButton(
              "Continue",
              (d, w) ->
                  launch(
                      FailureReport.url(
                          title.getText().toString(),
                          "Paste the full report copied by WhenThen here before submitting.")))
          .setNegativeButton("Stay here", null)
          .show();
    } else launch(url);
  }

  private void launch(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      Toast.makeText(
              this,
              "No browser available. Copy the details and open the WhenThen GitHub Issues page.",
              Toast.LENGTH_LONG)
          .show();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("title", title.getText().toString());
    state.putString("report", report.getText().toString());
  }
}
