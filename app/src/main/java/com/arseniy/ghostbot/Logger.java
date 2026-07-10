package com.arseniy.ghostbot;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Простой файловый логгер. Пишет всё в logs.txt внутри приложения (доступно без ПК/adb),
 * плюс дублирует в стандартный Logcat на случай если кто-то всё же подключит adb.
 *
 * Использование:
 *   Logger.init(context);      // один раз, например в MainActivity.onCreate / BotService.onCreate
 *   Logger.i("BotService", "Подключаюсь к " + ip);
 *   Logger.e("BotService", "Ошибка", exception);
 */
public class Logger {

    private static final String FILE_NAME = "logs.txt";
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
    private static File logFile;

    public static synchronized void init(Context context) {
        if (logFile == null) {
            logFile = new File(context.getFilesDir(), FILE_NAME);
        }
    }

    public static void i(String tag, String message) {
        write("I", tag, message, null);
        Log.i(tag, message);
    }

    public static void d(String tag, String message) {
        write("D", tag, message, null);
        Log.d(tag, message);
    }

    public static void e(String tag, String message) {
        write("E", tag, message, null);
        Log.e(tag, message);
    }

    public static void e(String tag, String message, Throwable t) {
        write("E", tag, message, t);
        Log.e(tag, message, t);
    }

    private static synchronized void write(String level, String tag, String message, Throwable t) {
        if (logFile == null) return; // Logger.init() ещё не вызывали
        String line = "[" + TIME_FORMAT.format(new Date()) + "] " + level + "/" + tag + ": " + message;
        try (FileWriter fw = new FileWriter(logFile, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(line);
            if (t != null) {
                StringWriter sw = new StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                pw.println(sw.toString());
            }
        } catch (IOException e) {
            Log.e("Logger", "Не смог записать лог в файл", e);
        }
    }

    public static synchronized String readAll() {
        if (logFile == null || !logFile.exists()) return "Логов пока нет.";
        try {
            return new String(java.nio.file.Files.readAllBytes(logFile.toPath()));
        } catch (IOException e) {
            return "Не удалось прочитать лог-файл: " + e.getMessage();
        }
    }

    public static synchronized void clear() {
        if (logFile != null && logFile.exists()) {
            logFile.delete();
        }
    }

    public static File getFile() {
        return logFile;
    }
}
