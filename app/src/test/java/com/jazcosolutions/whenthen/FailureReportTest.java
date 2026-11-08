package com.jazcosolutions.whenthen;

import static org.junit.Assert.*;

import java.net.URLDecoder;
import java.util.*;
import org.junit.Test;

public class FailureReportTest {
  @Test
  public void stripsRuleValuesUrisPathsPhonesAndCredentialsButKeepsStackFrames() {
    Rule r = new Rule();
    r.name = "Home emergency";
    r.when.set("ssid", "FamilyWifi").set("contact", "3125550199");
    r.then.add(Rule.action("show_message").set("message", "Private message"));
    String report =
        FailureReport.sanitize(
            "Home emergency FamilyWifi 3125550199 Private message content://media/private.jpg"
                + " /storage/emulated/0/Photo.jpg user@example.com +1 (212) 555-0198 38.889300"
                + " ghp_privateTestToken\n"
                + " at com.jazcosolutions.whenthen.ActionRunner.run(ActionRunner.java:75)",
            FailureReport.privateValues(List.of(r)));
    for (String privateValue :
        List.of(
            "Home emergency",
            "FamilyWifi",
            "3125550199",
            "Private message",
            "private.jpg",
            "Photo.jpg",
            "user@example.com",
            "555-0198",
            "38.889300",
            "ghp_privateTestToken")) assertFalse(privateValue, report.contains(privateValue));
    assertTrue(report.contains("ActionRunner.java:75"));
  }

  @Test
  public void olderEntriesRemainReportableWithoutInventingDiagnostics() {
    Store.Entry e = new Store.Entry("Private title", "Old failure", "failed");
    String body = FailureReport.body(e, List.of());
    assertTrue(body.contains("Old failure"));
    assertTrue(body.contains("were not recorded"));
    assertFalse(body.contains("Private title"));
  }

  @Test
  public void includesCapturedTechnicalEvidenceAndPermissionState() {
    Store.Entry e = new Store.Entry("Rule name", "raw detail", "failed");
    e.diagnostic = new FailureReport.Details();
    e.diagnostic.version = "1.0.4 (5)";
    e.diagnostic.android = "17 / API 37";
    e.diagnostic.device = "Test phone";
    e.diagnostic.component = "Play audio";
    e.diagnostic.summary = "Access denied";
    e.diagnostic.trace = "java.lang.SecurityException\n at ActionRunner.java:75";
    e.diagnostic.flow = "sms_received → play_audio";
    e.diagnostic.access.put("SEND_SMS", false);
    String body = FailureReport.body(e, List.of());
    for (String value :
        List.of(
            "1.0.4 (5)", "API 37", "Play audio", "SecurityException", "SEND_SMS: not granted/off"))
      assertTrue(body.contains(value));
    assertFalse(body.contains("raw detail"));
    assertFalse(body.contains("Rule name"));
  }

  @Test
  public void issueLinkEncodesQueryCharactersInsteadOfAddingParameters() throws Exception {
    String title = "Failure & something", body = "A + B # C\nUnicode: café";
    String url = FailureReport.url(title, body);
    assertTrue(url.startsWith(FailureReport.ISSUES + "?title="));
    String[] fields = url.substring(url.indexOf('?') + 1).split("&");
    assertEquals(2, fields.length);
    assertEquals(title, URLDecoder.decode(fields[0].substring(6), "UTF-8"));
    assertEquals(body, URLDecoder.decode(fields[1].substring(5), "UTF-8"));
  }

  @Test
  public void onlyProblemsGetReportButtons() {
    for (String s : List.of("failed", "partial", "blocked"))
      assertTrue(FailureReport.reportable(s));
    for (String s : List.of("observed", "test", "simulation", "dispatched"))
      assertFalse(FailureReport.reportable(s));
    assertFalse(FailureReport.reportable(null));
  }
}
