package com.arseniy.ghostbot;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

public class MainActivity extends AppCompatActivity {

    private EditText editIp, editPort, editName;
    private TextView textStatus, textLogs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Logger.init(getApplicationContext());

        editIp = findViewById(R.id.editIp);
        editPort = findViewById(R.id.editPort);
        editName = findViewById(R.id.editName);
        textStatus = findViewById(R.id.textStatus);
        textLogs = findViewById(R.id.textLogs);

        Button btnStart = findViewById(R.id.btnStart);
        Button btnStop = findViewById(R.id.btnStop);
        Button btnLogs = findViewById(R.id.btnLogs);
        Button btnShareLogs = findViewById(R.id.btnShareLogs);

        btnStart.setOnClickListener(v -> {
            String ip = editIp.getText().toString().trim();
            String portStr = editPort.getText().toString().trim();
            String name = editName.getText().toString().trim();

            if (ip.isEmpty()) {
                textStatus.setText("Статус: укажи IP сервера");
                return;
            }
            int port = portStr.isEmpty() ? 19132 : Integer.parseInt(portStr);
            if (name.isEmpty()) name = "GhostBot";

            Intent intent = new Intent(this, BotService.class);
            intent.putExtra("ip", ip);
            intent.putExtra("port", port);
            intent.putExtra("name", name);
            startForegroundService(intent);
            textStatus.setText("Статус: подключение...");
            Logger.i("MainActivity", "Пользователь нажал 'Подключиться': " + ip + ":" + port + " как " + name);
        });

        btnStop.setOnClickListener(v -> {
            stopService(new Intent(this, BotService.class));
            textStatus.setText("Статус: отключено");
            Logger.i("MainActivity", "Пользователь нажал 'Отключиться'");
        });

        btnLogs.setOnClickListener(v -> {
            textLogs.setText(Logger.readAll());
        });

        btnShareLogs.setOnClickListener(v -> {
            if (Logger.getFile() == null || !Logger.getFile().exists()) {
                Toast.makeText(this, "Логов пока нет", Toast.LENGTH_SHORT).show();
                return;
            }
            Uri uri = FileProvider.getUriForFile(this,
                    "com.arseniy.ghostbot.fileprovider", Logger.getFile());
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Поделиться логом"));
        });
    }
}
