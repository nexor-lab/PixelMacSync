package it.luigi.macsync.notifytest;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Receives the inline reply text that Android injects from the Mac (via
 * PixelMacSync's RemoteInput forwarding) and surfaces it in the UI + a
 * notification, so the round-trip is observable on the phone.
 */
public class ReplyReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Bundle results = RemoteInput.getResultsFromIntent(intent);
        CharSequence text = results != null
                ? results.getCharSequence(MainActivity.KEY_REPLY_TEXT)
                : null;
        String reply = text != null ? text.toString() : "(null)";
        Log.i("NotifyTest", "REPLY received: " + reply);

        MainActivity.onReplyReceived(reply);

        Notification n = new Notification.Builder(context, MainActivity.CHANNEL_CHAT)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("已收到 Mac 回复")
                .setContentText(reply)
                .setAutoCancel(true)
                .build();
        context.getSystemService(NotificationManager.class).notify(999, n);
    }
}
