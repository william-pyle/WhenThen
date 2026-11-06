package com.jazcosolutions.whenthen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;

/** The small, public update manifest attached last to a completed release. */
public final class UpdateRelease {
  public static final String REPOSITORY = "https://github.com/william-pyle/WhenThen";
  public static final String APPLICATION_ID = "com.jazcosolutions.whenthen";
  public static final long MAX_APK_BYTES = 100L * 1024 * 1024;
  public final String versionName, sha256, notes, manifestJson;
  public final long versionCode, size;

  private UpdateRelease(
      String version, long code, long bytes, String hash, String notes, String json) {
    this.versionName = version;
    this.versionCode = code;
    this.size = bytes;
    this.sha256 = hash;
    this.notes = notes;
    this.manifestJson = json;
  }

  public String tag() {
    return "v" + versionName;
  }

  public String apkUrl() {
    return REPOSITORY + "/releases/download/" + tag() + "/WhenThen.apk";
  }

  public boolean newerThan(long installed) {
    return versionCode > installed;
  }

  public static UpdateRelease parse(String json, String expectedTag) {
    try {
      JsonObject value = JsonParser.parseString(json).getAsJsonObject();
      if (value.get("schema").getAsInt() != 1
          || !APPLICATION_ID.equals(value.get("applicationId").getAsString()))
        throw new IllegalArgumentException("Update is not for WhenThen.");
      String version = value.get("versionName").getAsString();
      if (!version.matches("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}")
          || !("v" + version).equals(expectedTag))
        throw new IllegalArgumentException("Release version does not match.");
      long code = value.get("versionCode").getAsBigDecimal().longValueExact();
      long size = value.get("size").getAsBigDecimal().longValueExact();
      String hash = value.get("sha256").getAsString();
      if (code < 1
          || code > Integer.MAX_VALUE
          || size < 1
          || size > MAX_APK_BYTES
          || !hash.matches("[a-f0-9]{64}"))
        throw new IllegalArgumentException("Invalid update metadata.");
      String notes =
          value.has("notes")
              ? value.get("notes").getAsString()
              : "Improvements and fixes for WhenThen.";
      if (notes.length() > 1800) notes = notes.substring(0, 1800) + "…";
      UpdateRelease release = new UpdateRelease(version, code, size, hash, notes, json);
      if (!release.apkUrl().equals(value.get("apkUrl").getAsString()))
        throw new IllegalArgumentException("Unexpected APK download location.");
      return release;
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "The release information is incomplete or invalid.", exception);
    }
  }

  static boolean allowedDownloadUrl(String value) {
    try {
      URI uri = URI.create(value);
      String host = uri.getHost();
      return "https".equalsIgnoreCase(uri.getScheme())
          && uri.getUserInfo() == null
          && (uri.getPort() == -1 || uri.getPort() == 443)
          && ("github.com".equals(host)
              || "api.github.com".equals(host)
              || "release-assets.githubusercontent.com".equals(host)
              || "objects.githubusercontent.com".equals(host));
    } catch (RuntimeException e) {
      return false;
    }
  }
}
