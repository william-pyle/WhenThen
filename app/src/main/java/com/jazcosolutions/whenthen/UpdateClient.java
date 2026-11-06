package com.jazcosolutions.whenthen;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

class UpdateClient {
  static final String LATEST = "https://api.github.com/repos/william-pyle/WhenThen/releases/latest";

  UpdateRelease latest() throws Exception {
    String json = text(LATEST, true);
    if (json == null) return null; // There is no published stable release yet.
    JsonObject release = JsonParser.parseString(json).getAsJsonObject();
    if (release.get("draft").getAsBoolean() || release.get("prerelease").getAsBoolean())
      return null;
    String tag = release.get("tag_name").getAsString();
    if (!tag.matches("v[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}"))
      throw new IOException("The latest release has an unsupported version.");
    String expected = UpdateRelease.REPOSITORY + "/releases/download/" + tag + "/update.json";
    boolean ready = false;
    for (JsonElement asset : release.getAsJsonArray("assets")) {
      JsonObject value = asset.getAsJsonObject();
      if ("update.json".equals(value.get("name").getAsString())
          && expected.equals(value.get("browser_download_url").getAsString())
          && "uploaded".equals(value.get("state").getAsString())) ready = true;
    }
    if (!ready) throw new IOException("The new release is still being prepared. Try again later.");
    return UpdateRelease.parse(text(expected, false), tag);
  }

  static HttpURLConnection connect(String address) throws IOException {
    for (int redirects = 0; redirects <= 5; redirects++) {
      if (!UpdateRelease.allowedDownloadUrl(address))
        throw new IOException("Unexpected download server.");
      HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
      connection.setConnectTimeout(10000);
      connection.setReadTimeout(20000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent", "WhenThen/" + BuildConfig.VERSION_NAME);
      connection.setRequestProperty("Accept", "application/json, application/octet-stream");
      int code = connection.getResponseCode();
      if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
        String location = connection.getHeaderField("Location");
        connection.disconnect();
        if (location == null) throw new IOException("Invalid download redirect.");
        address = new URL(new URL(address), location).toString();
      } else return connection;
    }
    throw new IOException("Too many download redirects.");
  }

  static String text(String url, boolean allowNotFound) throws IOException {
    HttpURLConnection connection = connect(url);
    try {
      int code = connection.getResponseCode();
      if (code == 404 && allowNotFound) return null;
      if (code == 403 || code == 429)
        throw new IOException("GitHub is limiting requests. Please try again later.");
      if (code != 200)
        throw new IOException("GitHub is unavailable (HTTP " + code + "). Try again later.");
      try (InputStream stream = connection.getInputStream();
          ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
          if (output.size() + count > 1024 * 1024)
            throw new IOException("Release information is too large.");
          output.write(buffer, 0, count);
        }
        return output.toString(StandardCharsets.UTF_8.name());
      }
    } finally {
      connection.disconnect();
    }
  }

  File download(
      Context context, UpdateRelease release, AtomicBoolean cancelled, IntConsumer progress)
      throws Exception {
    File folder = new File(context.getCacheDir(), "updates");
    if (!folder.isDirectory() && !folder.mkdirs())
      throw new IOException("Cannot prepare update storage.");
    File part = new File(folder, "download-staging.apk");
    File apk = new File(folder, "WhenThen.apk");
    HttpURLConnection connection = connect(release.apkUrl());
    try {
      if (connection.getResponseCode() != 200)
        throw new IOException("The APK could not be downloaded.");
      if (connection.getContentLengthLong() > release.size)
        throw new IOException("Unexpected download size.");
      try (InputStream input = connection.getInputStream();
          OutputStream output = new FileOutputStream(part)) {
        byte[] buffer = new byte[32768];
        int count, last = -1;
        long total = 0;
        while ((count = input.read(buffer)) != -1) {
          if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new CancellationException();
          total += count;
          if (total > release.size) throw new IOException("Unexpected download size.");
          output.write(buffer, 0, count);
          int percent = (int) (total * 100 / release.size);
          if (percent != last) {
            progress.accept(percent);
            last = percent;
          }
        }
      }
      verify(context, part, release);
      if (cancelled.get()) throw new CancellationException();
      Files.move(part.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING);
      return apk;
    } finally {
      connection.disconnect();
      Files.deleteIfExists(part.toPath());
    }
  }

  static void verify(Context context, File file, UpdateRelease release) throws Exception {
    if (file.length() != release.size) throw new IOException("The APK download is incomplete.");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[32768];
      int count;
      while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    StringBuilder hash = new StringBuilder();
    for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
    if (!hash.toString().equals(release.sha256))
      throw new IOException("The APK checksum does not match. Please download it again.");
    PackageManager manager = context.getPackageManager();
    PackageInfo candidate =
        manager.getPackageArchiveInfo(
            file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
    PackageInfo installed =
        manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
    if (candidate == null
        || !context.getPackageName().equals(candidate.packageName)
        || candidate.getLongVersionCode() != release.versionCode
        || !release.versionName.equals(candidate.versionName)
        || candidate.getLongVersionCode() <= installed.getLongVersionCode())
      throw new IOException("This APK is not a newer version of WhenThen.");
    if (candidate.signingInfo == null
        || installed.signingInfo == null
        || !signers(candidate.signingInfo.getApkContentsSigners())
            .equals(signers(installed.signingInfo.getApkContentsSigners())))
      throw new IOException("The APK was not signed with this app's signing key.");
  }

  private static Set<String> signers(Signature[] signatures) {
    Set<String> result = new HashSet<>();
    for (Signature signature : signatures) result.add(signature.toCharsString());
    return result;
  }
}
