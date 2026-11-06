package com.jazcosolutions.whenthen;

import android.content.*;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class SmsReceiver extends BroadcastReceiver {
  @Override
  public void onReceive(Context c, Intent i) {
    if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(i.getAction())) return;
    SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(i);
    if (parts == null || parts.length == 0) return;
    StringBuilder body = new StringBuilder();
    for (SmsMessage part : parts) body.append(part.getMessageBody());
    String number = parts[0].getOriginatingAddress();
    String identity = number + ":" + parts[0].getTimestampMillis() + ":" + body;
    RuleEngine.Event e =
        new RuleEngine.Event(
            "sms:" + UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)),
            "sms_received",
            System.currentTimeMillis());
    e.contact = number;
    e.text = body.toString();
    MonitorService.deliver(c, e);
  }
}
