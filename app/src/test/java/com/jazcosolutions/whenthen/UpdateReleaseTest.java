package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import org.junit.Test;

public class UpdateReleaseTest {
  private JsonObject manifest() {
    JsonObject value = new JsonObject();
    value.addProperty("schema", 1);
    value.addProperty("applicationId", "com.jazcosolutions.whenthen");
    value.addProperty("versionName", "1.0.2");
    value.addProperty("versionCode", 3);
    value.addProperty("size", 1000);
    value.addProperty("sha256", "a".repeat(64));
    value.addProperty(
        "apkUrl", "https://github.com/william-pyle/WhenThen/releases/download/v1.0.2/WhenThen.apk");
    return value;
  }

  private void rejects(JsonObject value) {
    assertThrows(
        IllegalArgumentException.class, () -> UpdateRelease.parse(value.toString(), "v1.0.2"));
  }

  @Test
  public void comparesNumericVersionsWithoutDowngrades() {
    UpdateRelease release = UpdateRelease.parse(manifest().toString(), "v1.0.2");
    assertTrue(release.newerThan(2));
    assertFalse(release.newerThan(3));
    assertFalse(release.newerThan(4));
  }

  @Test
  public void rejectsOtherApplications() {
    JsonObject value = manifest();
    value.addProperty("applicationId", "com.example.other");
    rejects(value);
  }

  @Test
  public void rejectsMismatchedReleaseTag() {
    assertThrows(
        IllegalArgumentException.class, () -> UpdateRelease.parse(manifest().toString(), "v1.0.3"));
  }

  @Test
  public void rejectsFractionalAndOverflowedVersionCodes() {
    JsonObject value = manifest();
    value.addProperty("versionCode", 3.5);
    rejects(value);
    value.addProperty("versionCode", 2147483648L);
    rejects(value);
    value.addProperty("versionCode", 0);
    rejects(value);
  }

  @Test
  public void rejectsCorruptChecksums() {
    JsonObject value = manifest();
    value.addProperty("sha256", "not-a-hash");
    rejects(value);
  }

  @Test
  public void rejectsOversizedAndEmptyDownloads() {
    JsonObject value = manifest();
    value.addProperty("size", 0);
    rejects(value);
    value.addProperty("size", UpdateRelease.MAX_APK_BYTES + 1);
    rejects(value);
  }

  @Test
  public void rejectsArbitraryApkLocationsAndOtherRepositories() {
    JsonObject value = manifest();
    value.addProperty("apkUrl", "https://example.com/WhenThen.apk");
    rejects(value);
    value.addProperty(
        "apkUrl", "https://github.com/other/WhenThen/releases/download/v1.0.2/WhenThen.apk");
    rejects(value);
  }

  @Test
  public void rejectsMissingAndMalformedMetadata() {
    JsonObject value = manifest();
    value.remove("versionCode");
    rejects(value);
    assertThrows(IllegalArgumentException.class, () -> UpdateRelease.parse("{", "v1.0.2"));
  }

  @Test
  public void boundsReleaseNotes() {
    JsonObject value = manifest();
    value.addProperty("notes", "x".repeat(5000));
    assertTrue(UpdateRelease.parse(value.toString(), "v1.0.2").notes.length() <= 1801);
  }

  @Test
  public void permitsOnlyHttpsGitHubAssetRedirects() {
    assertTrue(
        UpdateRelease.allowedDownloadUrl(
            "https://release-assets.githubusercontent.com/github-production-release-asset/a?x=y"));
    assertFalse(UpdateRelease.allowedDownloadUrl("http://github.com/a"));
    assertFalse(UpdateRelease.allowedDownloadUrl("https://github.com.evil.example/a"));
    assertFalse(UpdateRelease.allowedDownloadUrl("https://github.com@evil.example/a"));
    assertFalse(UpdateRelease.allowedDownloadUrl("https://github.com:444/a"));
    assertFalse(UpdateRelease.allowedDownloadUrl("file:///tmp/a"));
  }
}
