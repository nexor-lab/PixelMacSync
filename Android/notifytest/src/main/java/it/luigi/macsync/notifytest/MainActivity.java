package it.luigi.macsync.notifytest;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * NotifyTest — a tiny harness to exercise the PixelMacSync notification pipeline
 * without waiting for real app traffic.
 *
 * It can post:
 *   1) a plain "X-like" notification (no reply action) -> the Mac banner must
 *      NOT offer a reply field (canReply=false);
 *   2) a "chat-like" notification exposing a free-form RemoteInput reply action
 *      (canReply=true) -> the Mac banner offers a reply field; the reply comes
 *      back to {@link ReplyReceiver}, which shows it here and as a notification.
 *
 * Automation: launch with `am start ... --es cmd post_x|post_chat|clear`.
 */
public class MainActivity extends android.app.Activity {

    static final String CHANNEL_X = "test_x";
    static final String CHANNEL_CHAT = "test_chat";
    static final String KEY_REPLY_TEXT = "reply_text";

    static final String CHAT_ID = "notifytest_chat";

    static volatile String lastReply = "";

    private static WeakReference<MainActivity> instance = new WeakReference<>(null);

    private TextView tvLastReply;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = new WeakReference<>(this);

        createChannels();
        setContentView(buildUi());
        requestNotificationPermission();

        handleCommand(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCommand(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderLastReply();
    }

    private void handleCommand(Intent intent) {
        if (intent == null) return;
        String cmd = intent.getStringExtra("cmd");
        if (cmd == null) return;
        switch (cmd) {
            case "post_x":     postPlain(); break;
            case "post_chat":  postChat();  break;
            case "clear":      clearAll();  break;
            default: break;
        }
    }

    // --- UI -----------------------------------------------------------------

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("NotifyTest — PixelMacSync 通知测试");
        title.setTextSize(20);
        title.setGravity(Gravity.START);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("先在 PixelSync 的“通知”列表里勾选本应用，再开始测试。");
        hint.setPadding(0, 16, 0, 24);
        root.addView(hint);

        addButton(root, "1. 授权通知权限", v -> requestNotificationPermission());
        addButton(root, "2. 模拟普通通知（无回复动作）", v -> postPlain());
        addButton(root, "3. 模拟聊天通知（可 RemoteInput 回复）", v -> postChat());
        addButton(root, "4. 清除全部通知", v -> clearAll());

        tvLastReply = new TextView(this);
        tvLastReply.setPadding(0, 24, 0, 0);
        tvLastReply.setTextSize(16);
        root.addView(tvLastReply);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private interface VClick { void onClick(View v); }

    private void addButton(LinearLayout parent, String text, VClick listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener::onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 8, 0, 8);
        parent.addView(b, lp);
    }

    // --- Notifications ------------------------------------------------------

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel x = new NotificationChannel(
                CHANNEL_X, "Test X", NotificationManager.IMPORTANCE_HIGH);
        x.setDescription("Simulated X notifications");
        NotificationChannel chat = new NotificationChannel(
                CHANNEL_CHAT, "Test Chat", NotificationManager.IMPORTANCE_HIGH);
        chat.setDescription("Simulated chat notifications with inline reply");
        nm.createNotificationChannel(x);
        nm.createNotificationChannel(chat);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    private PendingIntent selfActivity(int requestCode) {
        Intent i = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(this, requestCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Plain simulated notification with no reply action (like X): the Mac banner
     * should NOT offer a reply field (canReply=false).
     */
    private void postPlain() {
        Notification n = new Notification.Builder(this, CHANNEL_X)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("X")
                .setContentText("New post (no reply action)")
                .setAutoCancel(true)
                .setContentIntent(selfActivity(1))
                .build();
        notify(101, n);
        Log.i("NotifyTest", "POST plain (no reply action)");
    }

    /** Simulated chat message with a real free-form RemoteInput reply action. */
    private void postChat() {
        RemoteInput remoteInput = new RemoteInput.Builder(KEY_REPLY_TEXT)
                .setLabel("Reply")
                .build();

        Intent replyIntent = new Intent(this, ReplyReceiver.class);
        PendingIntent replyPending = PendingIntent.getBroadcast(this, 2, replyIntent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Action replyAction = new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_send),
                "Reply",
                replyPending)
                .addRemoteInput(remoteInput)
                .build();

        Notification n = new Notification.Builder(this, CHANNEL_CHAT)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("测试联系人")
                .setContentText("请从 Mac 回复这条消息（聊天风格通知）")
                .addAction(replyAction)
                .setAutoCancel(true)
                .setContentIntent(selfActivity(3))
                .build();

        notifManager().notify(CHAT_ID, 102, n);
        Log.i("NotifyTest", "POST chat with RemoteInput reply action");
    }

    private void clearAll() {
        notifManager().cancelAll();
        renderLastReply();
    }

    private NotificationManager notifManager() {
        return getSystemService(NotificationManager.class);
    }

    private void notify(int id, Notification n) {
        notifManager().notify(id, n);
    }

    // --- Reply plumbing -----------------------------------------------------

    static void onReplyReceived(String text) {
        lastReply = text;
        MainActivity a = instance.get();
        if (a != null) a.runOnUiThread(a::renderLastReply);
    }

    private void renderLastReply() {
        if (tvLastReply == null) return;
        tvLastReply.setText("最近收到的 Mac 回复：\n" + (lastReply.isEmpty() ? "（暂无）" : lastReply));
    }
}
