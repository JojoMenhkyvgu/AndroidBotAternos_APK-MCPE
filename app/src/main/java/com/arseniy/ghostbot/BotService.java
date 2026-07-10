package com.arseniy.ghostbot;

import android.app.*;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.net.InetSocketAddress;

import org.cloudburstmc.protocol.bedrock.BedrockClient;
import org.cloudburstmc.protocol.bedrock.BedrockPong;

public class BotService extends Service {

    private static final String TAG = "BotService";
    private static final String CHANNEL_ID = "ghostbot_channel";
    private BedrockClient client;
    private Thread networkThread;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(getApplicationContext());
        Logger.i(TAG, "Сервис создан");
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification("Запуск..."));

        String ip = intent.getStringExtra("ip");
        int port = intent.getIntExtra("port", 19132);
        String name = intent.getStringExtra("name");

        Logger.i(TAG, "Запуск подключения: ip=" + ip + " port=" + port + " name=" + name);

        networkThread = new Thread(() -> connectToServer(ip, port, name));
        networkThread.setUncaughtExceptionHandler((t, e) ->
                Logger.e(TAG, "Необработанное исключение в сетевом потоке", e));
        networkThread.start();

        return START_STICKY;
    }

    private void connectToServer(String ip, int port, String name) {
        try {
            InetSocketAddress bind = new InetSocketAddress("0.0.0.0", 0);
            client = new BedrockClient(bind);
            Logger.d(TAG, "Биндим локальный UDP-сокет...");
            client.bind().join();
            Logger.i(TAG, "Локальный сокет забинжен");

            InetSocketAddress target = new InetSocketAddress(ip, port);
            Logger.i(TAG, "Пингую сервер " + ip + ":" + port + " перед подключением...");
            try {
                BedrockPong pong = client.ping(target).get();
                Logger.i(TAG, "Пинг ответ: motd=" + pong.getMotd()
                        + " players=" + pong.getPlayerCount() + "/" + pong.getMaximumPlayerCount()
                        + " protocol=" + pong.getProtocolVersion());
            } catch (Exception pingError) {
                Logger.e(TAG, "Пинг не удался (сервер оффлайн или недоступен?)", pingError);
            }

            BotPacketHandler handler = new BotPacketHandler(name, this::updateStatus);

            Logger.i(TAG, "Подключаюсь к серверу...");
            client.connect(target).whenComplete((session, error) -> {
                if (error != null) {
                    Logger.e(TAG, "Ошибка подключения (RakNet)", error);
                    updateStatus("Ошибка подключения: " + error.getMessage());
                    return;
                }
                Logger.i(TAG, "RakNet-соединение установлено, начинаю логин");
                updateStatus("Соединение установлено, логин...");
                session.setPacketHandler(handler);
                handler.attachSession(session);
            }).join();

        } catch (Exception e) {
            Logger.e(TAG, "Общая ошибка подключения", e);
            updateStatus("Ошибка: " + e.getMessage());
        }
    }

    private void updateStatus(String status) {
        Logger.i(TAG, "Статус: " + status);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(1, buildNotification(status));
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("GhostBot")
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.presence_online)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "GhostBot Status", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Logger.i(TAG, "Сервис остановлен");
        if (client != null) client.close();
        if (networkThread != null) networkThread.interrupt();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
