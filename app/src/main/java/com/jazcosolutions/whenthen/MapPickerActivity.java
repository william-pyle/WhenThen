package com.jazcosolutions.whenthen;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;

import android.location.*;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.webkit.*;
import android.widget.*;
import com.google.gson.Gson;
import java.io.*;
import java.util.*;

/** A separate, cancellable draft picker. Only Use this location returns changes to the editor. */
public final class MapPickerActivity extends Activity {
  static final String URL = "https://appassets.androidplatform.net/map/index.html";
  private static final Set<String> ASSETS =
      Set.of("index.html", "picker.js", "picker.css", "leaflet.js", "leaflet.css");
  WebView map;
  AddressSearch addressSearch;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private EditText query, name, radiusInput;
  private TextView status;
  private Button accept, search, fit;
  private boolean selected, mapReady, destroyed;
  private double latitude, longitude, centerLat = 20, centerLng = 0;
  private int zoom = 2, searchGeneration, locationGeneration;
  private CancellationSignal locationRequest;
  private Runnable searchTimeout, locationTimeout;

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    addressSearch = new AddressSearch.Android(this);
    Bundle state = saved == null ? getIntent().getExtras() : saved;
    if (state == null) state = new Bundle();
    latitude = state.getDouble("latitude", Double.NaN);
    longitude = state.getDouble("longitude", Double.NaN);
    selected = MapSelection.validPoint(latitude, longitude);
    centerLat = state.getDouble("centerLat", selected ? latitude : 20);
    centerLng = state.getDouble("centerLng", selected ? longitude : 0);
    zoom = state.getInt("zoom", selected ? 16 : 2);
    String radius = state.getString("radiusFeet", "250");
    getWindow().setStatusBarColor(MainActivity.BG);
    getWindow().setNavigationBarColor(MainActivity.BG);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout root = column();
    root.setBackgroundColor(MainActivity.BG);
    root.setPadding(dp(12), 0, dp(12), 0);
    setContentView(root);
    LinearLayout header = row();
    TextView title = label("Choose a place", 21);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(button("Cancel", () -> finish()));
    root.addView(header);
    LinearLayout searchRow = row();
    query = input("Search an address or place", state.getString("query", ""), 1);
    query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    searchRow.addView(query, new LinearLayout.LayoutParams(0, dp(48), 1));
    search = button("Search", this::findAddress);
    searchRow.addView(search);
    root.addView(searchRow);
    query.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_SEARCH) {
            findAddress();
            return true;
          }
          return false;
        });
    query.addTextChangedListener(
        watcher(
            () -> {
              searchGeneration++;
              if (searchTimeout != null) handler.removeCallbacks(searchTimeout);
              search.setEnabled(true);
            }));
    status = label("", 13);
    status.setPadding(0, dp(6), 0, dp(6));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    root.addView(status);
    map = new WebView(this);
    map.setContentDescription("Location map");
    root.addView(map, new LinearLayout.LayoutParams(-1, 0, 1));
    configureMap();
    LinearLayout tools = row();
    tools.addView(
        button("My location", this::currentLocation), new LinearLayout.LayoutParams(0, dp(56), 1));
    fit = button("Fit circle", () -> js("picker.fit()"));
    tools.addView(fit, new LinearLayout.LayoutParams(0, dp(56), 1));
    tools.addView(
        button("Retry map", () -> js("picker.retry()")),
        new LinearLayout.LayoutParams(0, dp(56), 1));
    root.addView(tools);
    name = input("Place name", state.getString("name", ""), 1);
    root.addView(name, new LinearLayout.LayoutParams(-1, dp(48)));
    name.setFilters(new InputFilter[] {new InputFilter.LengthFilter(120)});
    LinearLayout radiusRow = row();
    radiusRow.addView(label("Radius (feet)", 14));
    radiusInput = input("Radius in feet", radius, android.text.InputType.TYPE_CLASS_NUMBER);
    radiusRow.addView(radiusInput, new LinearLayout.LayoutParams(0, dp(48), 1));
    root.addView(radiusRow);
    radiusInput.addTextChangedListener(
        watcher(
            () -> {
              int feet = MapSelection.radius(radiusInput.getText().toString());
              radiusInput.setError(feet < 0 ? "Use 50–100,000 whole feet" : null);
              if (feet > 0) js("picker.radius(" + feet + ")");
              updateSelectionStatus();
            }));
    accept = button("Use this location", this::confirm);
    BrickStyle.button(accept, BrickStyle.WHEN);
    root.addView(accept, new LinearLayout.LayoutParams(-1, dp(56)));
    TextView privacy =
        label(
            "Maps use OpenStreetMap. Address searches use your device’s search provider. Internet"
                + " required.",
            11);
    privacy.setPadding(0, dp(5), 0, dp(5));
    root.addView(privacy);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          android.graphics.Insets bars =
              insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
          v.setPadding(dp(12) + bars.left, bars.top, dp(12) + bars.right, bars.bottom);
          // Keep the boundary visible while typing on a small screen. These controls
          // return as soon as the keyboard closes; selection and acceptance stay visible.
          boolean compactTyping =
              insets.isVisible(WindowInsets.Type.ime())
                  && v.getHeight() - bars.top - bars.bottom < dp(500);
          header.setVisibility(compactTyping ? View.GONE : View.VISIBLE);
          tools.setVisibility(compactTyping ? View.GONE : View.VISIBLE);
          privacy.setVisibility(compactTyping ? View.GONE : View.VISIBLE);
          return insets;
        });
    root.requestApplyInsets();
    updateSelectionStatus();
    map.loadUrl(URL);
  }

  @android.annotation.SuppressLint(
      "SetJavaScriptEnabled") // Bundled Leaflet only; strict CSP, resource allowlist and blocked
  // navigation.
  private void configureMap() {
    WebSettings settings = map.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setGeolocationEnabled(false);
    settings.setDomStorageEnabled(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setCacheMode(WebSettings.LOAD_DEFAULT);
    settings.setUserAgentString(
        settings.getUserAgentString()
            + " WhenThen/"
            + BuildConfig.VERSION_NAME
            + " (+https://github.com/william-pyle/WhenThen)");
    map.addJavascriptInterface(new Bridge(), "WhenThenMap");
    map.setWebViewClient(
        new WebViewClient() {
          @Override
          public WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if ("https".equals(uri.getScheme())
                && "appassets.androidplatform.net".equals(uri.getHost())
                && uri.getPath() != null
                && uri.getPath().startsWith("/map/")) {
              String file = uri.getPath().substring(5);
              if (ASSETS.contains(file))
                try {
                  String mime =
                      file.endsWith(".js")
                          ? "text/javascript"
                          : file.endsWith(".css") ? "text/css" : "text/html";
                  return new WebResourceResponse(mime, "UTF-8", getAssets().open("map/" + file));
                } catch (IOException ignored) {
                }
            }
            if ("GET".equals(request.getMethod())
                && "https".equals(uri.getScheme())
                && "tile.openstreetmap.org".equals(uri.getHost())
                && uri.getPath() != null
                && uri.getPath().matches("/[0-9]{1,2}/[0-9]+/[0-9]+\\.png")
                && uri.getQuery() == null) return null;
            return new WebResourceResponse(
                "text/plain",
                "UTF-8",
                403,
                "Blocked",
                Map.of(),
                new ByteArrayInputStream(new byte[0]));
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if ("https://www.openstreetmap.org/copyright".equals(request.getUrl().toString())) {
              try {
                startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
              } catch (ActivityNotFoundException ignored) {
              }
            }
            return true;
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            if (!URL.equals(url)) return;
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("selected", selected);
            state.put("latitude", selected ? latitude : 0);
            state.put("longitude", selected ? longitude : 0);
            state.put("centerLat", Math.max(-85, Math.min(85, centerLat)));
            state.put("centerLng", centerLng);
            state.put("zoom", zoom);
            int feet = MapSelection.radius(radiusInput.getText().toString());
            state.put("radius", feet > 0 ? feet : 250);
            view.evaluateJavascript("picker.init(" + new Gson().toJson(state) + ")", null);
          }
        });
  }

  private final class Bridge {
    @JavascriptInterface
    public void ready() {
      handler.post(
          () -> {
            if (!destroyed) {
              mapReady = true;
              if (selected) js("picker.select(" + latitude + "," + longitude + ",false)");
              updateSelectionStatus();
            }
          });
    }

    @JavascriptInterface
    public void selected(double lat, double lng) {
      handler.post(
          () -> {
            if (destroyed || !MapSelection.validPoint(lat, lng)) return;
            cancelLocation();
            searchGeneration++;
            if (searchTimeout != null) handler.removeCallbacks(searchTimeout);
            search.setEnabled(true);
            latitude = lat;
            longitude = lng;
            selected = true;
            updateSelectionStatus();
          });
    }

    @JavascriptInterface
    public void viewport(double lat, double lng, int level) {
      handler.post(
          () -> {
            if (!destroyed && MapSelection.validPoint(lat, lng)) {
              centerLat = lat;
              centerLng = lng;
              zoom = Math.max(2, Math.min(19, level));
            }
          });
    }

    @JavascriptInterface
    public void tileState(boolean loaded) {
      handler.post(
          () -> {
            if (!destroyed && !loaded)
              status.setText("Map images unavailable. Check your connection, then Retry map.");
          });
    }
  }

  private void js(String code) {
    if (mapReady && !destroyed) map.evaluateJavascript(code, null);
  }

  private void updateSelectionStatus() {
    if (accept == null) return;
    int feet = MapSelection.radius(radiusInput.getText().toString());
    accept.setEnabled(selected && feet > 0 && mapReady);
    fit.setEnabled(selected && mapReady);
    status.setText(
        feet < 0
            ? "Enter a radius from 50 to 100,000 feet."
            : selected
                ? "Pin selected · circle reaches " + feet + " ft from the pin."
                : "Search an address or tap the map to place a pin. Pinch or use + / − to zoom.");
  }

  private void choose(AddressSearch.Place place) {
    if (!MapSelection.validPoint(place.latitude, place.longitude)) return;
    cancelLocation();
    latitude = place.latitude;
    longitude = place.longitude;
    selected = true;
    centerLat = latitude;
    centerLng = longitude;
    if (!mapReady) zoom = 16;
    if (name.getText().toString().isBlank())
      name.setText(place.label.substring(0, Math.min(120, place.label.length())));
    js("picker.select(" + latitude + "," + longitude + ",true)");
    updateSelectionStatus();
  }

  private void findAddress() {
    String text = query.getText().toString().trim();
    if (text.length() < 3) {
      query.setError("Enter an address, city, or place name");
      return;
    }
    hideKeyboard();
    cancelLocation();
    int generation = ++searchGeneration;
    search.setEnabled(false);
    status.setText("Searching for that address…");
    if (searchTimeout != null) handler.removeCallbacks(searchTimeout);
    searchTimeout =
        () -> {
          if (!destroyed && generation == searchGeneration) {
            searchGeneration++;
            search.setEnabled(true);
            status.setText("Search timed out. Try again or choose a point on the map.");
          }
        };
    handler.postDelayed(searchTimeout, 20000);
    addressSearch.search(
        text,
        (places, error) ->
            handler.post(
                () -> {
                  if (destroyed || isFinishing() || generation != searchGeneration) return;
                  handler.removeCallbacks(searchTimeout);
                  search.setEnabled(true);
                  if (error != null) {
                    status.setText(error);
                    return;
                  }
                  if (places.isEmpty()) {
                    status.setText("No places found. Try a fuller address, including the city.");
                    return;
                  }
                  if (places.size() == 1) {
                    choose(places.get(0));
                    return;
                  }
                  new BrickDialogBuilder(this)
                      .setTitle("Choose the matching address")
                      .setItems(
                          places.stream().map(p -> p.label).toArray(String[]::new),
                          (d, n) -> choose(places.get(n)))
                      .setNegativeButton("Cancel", null)
                      .show();
                  status.setText("Choose an address, then adjust its pin if needed.");
                }));
  }

  private void currentLocation() {
    searchGeneration++;
    if (searchTimeout != null) handler.removeCallbacks(searchTimeout);
    search.setEnabled(true);
    if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
      requestPermissions(
          new String[] {
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
          },
          1);
      return;
    }
    LocationManager manager = getSystemService(LocationManager.class);
    if (!manager.isLocationEnabled()) {
      status.setText("Turn on Location in phone settings, or search an address.");
      return;
    }
    cancelLocation();
    int generation = locationGeneration;
    locationRequest = new CancellationSignal();
    status.setText("Finding your current location…");
    locationTimeout =
        () -> {
          if (!destroyed && generation == locationGeneration) {
            cancelLocation();
            status.setText("No location fix yet. Search an address or tap the map.");
          }
        };
    handler.postDelayed(locationTimeout, 20000);
    String provider =
        manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            ? LocationManager.GPS_PROVIDER
            : LocationManager.NETWORK_PROVIDER;
    try {
      manager.getCurrentLocation(
          provider,
          locationRequest,
          getMainExecutor(),
          location -> {
            if (destroyed || generation != locationGeneration) return;
            handler.removeCallbacks(locationTimeout);
            if (location == null) {
              status.setText("No location fix yet. Search an address or tap the map.");
              return;
            }
            choose(
                new AddressSearch.Place(
                    "My selected place", location.getLatitude(), location.getLongitude()));
          });
    } catch (SecurityException | IllegalArgumentException e) {
      cancelLocation();
      status.setText("Location is unavailable. Search an address or tap the map.");
    }
  }

  private void cancelLocation() {
    locationGeneration++;
    if (locationRequest != null) locationRequest.cancel();
    locationRequest = null;
    if (locationTimeout != null) handler.removeCallbacks(locationTimeout);
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 1) {
      if (Readiness.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) currentLocation();
      else status.setText("You can search an address or choose a point without location access.");
    }
  }

  private void confirm() {
    int feet = MapSelection.radius(radiusInput.getText().toString());
    if (!selected || feet < 0 || !mapReady) return;
    String label = name.getText().toString().trim();
    if (label.isBlank()) label = "Selected place";
    setResult(
        RESULT_OK,
        new Intent()
            .putExtra("latitude", latitude)
            .putExtra("longitude", longitude)
            .putExtra("name", label)
            .putExtra("radiusFeet", Integer.toString(feet)));
    finish();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    if (selected) {
      state.putDouble("latitude", latitude);
      state.putDouble("longitude", longitude);
    }
    state.putDouble("centerLat", centerLat);
    state.putDouble("centerLng", centerLng);
    state.putInt("zoom", zoom);
    state.putString("name", name.getText().toString());
    state.putString("query", query.getText().toString());
    state.putString("radiusFeet", radiusInput.getText().toString());
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (map != null) map.onResume();
  }

  @Override
  protected void onPause() {
    if (map != null) map.onPause();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    cancelLocation();
    searchGeneration++;
    handler.removeCallbacksAndMessages(null);
    addressSearch.close();
    map.removeJavascriptInterface("WhenThenMap");
    map.stopLoading();
    map.destroy();
    super.onDestroy();
  }

  private void hideKeyboard() {
    getSystemService(InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(), 0);
    View focused = getCurrentFocus();
    if (focused != null) focused.clearFocus();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private TextView label(String value, int size) {
    TextView v = new TextView(this);
    v.setText(value);
    v.setTextSize(size);
    v.setTextColor(MainActivity.INK);
    return v;
  }

  private EditText input(String hint, String value, int type) {
    EditText v = new EditText(this);
    v.setSingleLine(true);
    v.setInputType(type);
    v.setHint(hint);
    v.setContentDescription(hint);
    v.setText(value);
    v.setTextSize(15);
    v.setBackground(BrickStyle.panel(this, Color.WHITE));
    v.setPadding(dp(8), dp(6), dp(8), dp(6));
    v.setTextColor(MainActivity.INK);
    v.setImeOptions(EditorInfo.IME_ACTION_DONE);
    v.setOnEditorActionListener(
        (view, action, event) -> {
          if (action == EditorInfo.IME_ACTION_DONE) {
            hideKeyboard();
            return true;
          }
          return false;
        });
    return v;
  }

  private Button button(String text, Runnable action) {
    Button b = new Button(this);
    b.setText(text);
    BrickStyle.button(b, BrickStyle.BLUE);
    b.setTextSize(12);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private TextWatcher watcher(Runnable action) {
    return new TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

      public void onTextChanged(CharSequence s, int start, int before, int count) {
        action.run();
      }

      public void afterTextChanged(Editable e) {}
    };
  }
}
