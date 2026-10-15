package com.artillexstudios.axtrade.utils;

import com.artillexstudios.axapi.utils.file.FileUtils;
import com.artillexstudios.axtrade.AxTrade;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
public class HistoryUtils {
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Path logsDir;
    private static final ExecutorService FILE_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(256),
            runnable -> {
                Thread thread = new Thread(runnable, "AxTrade-history");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    static {
        logsDir = FileUtils.getInstance().getFolder().toPath().resolve("logs");
        try {
            Files.createDirectories(logsDir);
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

    public static void writeToHistory(String text) {
        if (AxTrade.getInstance() instanceof AxTrade current && current.getStorage() != null) {
            current.getStorage().recordHistory(text);
        }
        ZonedDateTime now = ZonedDateTime.now();
        Path logFile = logsDir.resolve("%s.log".formatted(now.format(FILE_FORMAT)));
        String line = "[%s] %s%s".formatted(now.format(TIME_FORMAT), text, System.lineSeparator());
        try {
            FILE_EXECUTOR.execute(() -> {
                try {
                    Files.writeString(logFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    if (AxTrade.getInstance() != null) {
                        AxTrade.getInstance().getLogger().warning("Could not write trade history file: " + ex.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            if (AxTrade.getInstance() != null) {
                AxTrade.getInstance().getLogger().warning("Trade history queue is full; dropping file entry");
            }
        }
    }

    public static void close() {
        FILE_EXECUTOR.shutdown();
        try {
            if (!FILE_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) FILE_EXECUTOR.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            FILE_EXECUTOR.shutdownNow();
        }
    }
}
