package com.mojang.minecraftpe;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Local notification dispatch/queue for the vendored Minecraft engine wrapper.
 *
 * This originally extended Firebase's messaging service to receive Xbox Live
 * push notifications and fetch a device registration token. Firebase/Google
 * Services has been removed from this fork, so remote push delivery and real
 * token retrieval are gone; the public API below is preserved unchanged so
 * existing callers (MainActivity, Interop) keep working, but token requests
 * now resolve to an empty string and no remote push will ever arrive.
 */
public class NotificationListenerService {
    private static final String PREFERENCES = "minecraft_xbox_notifications";
    private static final String PENDING_KEY = "pending_notifications";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile Context applicationContext;
    private static volatile NotificationListenerService activeService;
    private static volatile boolean nativeReady;

    public native void nativePushNotificationReceived(int type, String title, String body, String payload);

    public static void initialize(Context context) {
        if (context == null) {
            return;
        }
        applicationContext = context.getApplicationContext();
    }

    public static void initialize(
            Context context,
            String appId,
            String apiKey,
            String projectId,
            String senderId
    ) {
        // Remote push configuration is a no-op now that Firebase has been removed.
        initialize(context);
    }

    public static String getDeviceRegistrationToken() {
        return "";
    }

    public static void requestDeviceRegistrationToken(TokenCallback callback) {
        EXECUTOR.execute(() -> callback.onToken(""));
    }

    public static void setNativeReady(boolean ready) {
        nativeReady = ready;
        if (ready) {
            flushPendingNotifications();
        }
    }

    public static void onMinecraftForeground(Context context) {
        if (context != null) {
            applicationContext = context.getApplicationContext();
        }
        nativeReady = true;
        EXECUTOR.execute(NotificationListenerService::flushPendingNotifications);
    }

    public static void refreshDeviceRegistrationToken(Context context) {
        if (context != null) {
            applicationContext = context.getApplicationContext();
        }
        // No-op: there is no remote token to refresh without Firebase.
    }

    private static boolean dispatch(NotificationData notification) {
        MainActivity activity = MainActivity.mInstance;
        if (!nativeReady && activity == null) {
            return false;
        }
        NotificationListenerService service = activeService;
        if (service == null) {
            service = new NotificationListenerService();
        }
        NotificationListenerService target = service;
        AtomicBoolean delivered = new AtomicBoolean(false);
        CountDownLatch completed = new CountDownLatch(1);
        Runnable delivery = () -> {
            try {
                target.nativePushNotificationReceived(
                        notification.type,
                        notification.title,
                        notification.body,
                        notification.payload
                );
                nativeReady = true;
                delivered.set(true);
            } catch (UnsatisfiedLinkError | RuntimeException error) {
                nativeReady = false;
            } finally {
                completed.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            delivery.run();
        } else {
            Handler handler = activity != null
                    ? new Handler(activity.getMainLooper())
                    : new Handler(Looper.getMainLooper());
            handler.post(delivery);
            try {
                if (!completed.await(5L, TimeUnit.SECONDS)) {
                    return false;
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return delivered.get();
    }

    private static synchronized void enqueue(NotificationData notification) {
        if (applicationContext == null) {
            return;
        }
        JSONArray pending = readPending();
        pending.put(notification.toJson());
        while (pending.length() > 16) {
            JSONArray reduced = new JSONArray();
            for (int index = pending.length() - 16; index < pending.length(); index++) {
                reduced.put(pending.opt(index));
            }
            pending = reduced;
        }
        preferences().edit().putString(PENDING_KEY, pending.toString()).apply();
    }

    private static synchronized void flushPendingNotifications() {
        if (!nativeReady || applicationContext == null) {
            return;
        }
        JSONArray pending = readPending();
        JSONArray remaining = new JSONArray();
        for (int index = 0; index < pending.length(); index++) {
            JSONObject object = pending.optJSONObject(index);
            if (object == null) {
                continue;
            }
            NotificationData notification = NotificationData.fromJson(object);
            if (!dispatch(notification)) {
                remaining.put(object);
                for (int rest = index + 1; rest < pending.length(); rest++) {
                    remaining.put(pending.opt(rest));
                }
                break;
            }
        }
        preferences().edit().putString(PENDING_KEY, remaining.toString()).apply();
    }

    private static JSONArray readPending() {
        try {
            return new JSONArray(preferences().getString(PENDING_KEY, "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private static SharedPreferences preferences() {
        return applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    public interface TokenCallback {
        void onToken(String token);
    }

    private static final class NotificationData {
        private final int type;
        private final String title;
        private final String body;
        private final String payload;

        private NotificationData(int type, String title, String body, String payload) {
            this.type = type;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.payload = payload == null ? "" : payload;
        }

        private JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("type", type);
                object.put("title", title);
                object.put("body", body);
                object.put("payload", payload);
            } catch (JSONException ignored) {
            }
            return object;
        }

        private static NotificationData fromJson(JSONObject object) {
            return new NotificationData(
                    object.optInt("type", 2),
                    object.optString("title", ""),
                    object.optString("body", ""),
                    object.optString("payload", "")
            );
        }
    }
}
