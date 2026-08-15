package com.adimn.autocraft.util;

import com.adimn.autocraft.config.Config;

import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 轻量文件日志。
 *
 * 默认启用（Config.logEnabled，默认 true）。
 * 日志文件位置：<整合包版本文件夹（游戏目录）>/logs/autocraft.log
 *   —— 等价于各启动器（CurseForge / Prism 等）每个实例独立的 .minecraft/logs。
 * 同时回显到 System.out，会一并进入 MC 自身的 logs/latest.log，即便文件写失败也有记录。
 * 写入失败或被禁用时绝不抛出，不影响游戏主线程。
 */
public final class Log {
    private static final String FILE_NAME = "autocraft.log";
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static BufferedWriter writer;
    private static boolean initFailed = false;

    private Log() {}

    /** 整合包版本文件夹 = 游戏目录（每个实例独立的 .minecraft）。 */
    private static Path resolveLogPath() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("logs");
        return dir.resolve(FILE_NAME);
    }

    private static void ensureOpen() {
        if (writer != null || initFailed) {
            return;
        }
        synchronized (Log.class) {
            if (writer != null || initFailed) {
                return;
            }
            try {
                Path p = resolveLogPath();
                Files.createDirectories(p.getParent());
                writer = Files.newBufferedWriter(p, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Throwable t) {
                initFailed = true;
                System.err.println("[EMI-AutoCraft] 无法打开日志文件，日志仅输出到控制台: " + t);
            }
        }
    }

    public static void info(String msg) {
        write("INFO", msg);
    }

    public static void warn(String msg) {
        write("WARN", msg);
    }

    public static void error(String msg) {
        write("ERROR", msg);
    }

    public static void error(String msg, Throwable t) {
        write("ERROR", msg + " :: " + t);
    }

    /** DEBUG 级：默认关闭（Config.logDebug），仅当显式开启才记录，避免日常刷屏。 */
    public static void debug(String msg) {
        if (!Config.debugLog()) {
            return;
        }
        write("DEBUG", msg);
    }

    private static void write(String level, String msg) {
        if (!Config.logEnabled()) {
            return;
        }
        String line = "[" + LocalDateTime.now().format(TS) + "] [" + level + "] " + msg;
        // 先回显控制台（进 MC latest.log），再落文件；文件不可用时仍有记录。
        System.out.println("[EMI-AutoCraft] " + line);
        ensureOpen();
        BufferedWriter w = writer;
        if (w == null) {
            return;
        }
        try {
            w.write(line);
            w.newLine();
            w.flush();
        } catch (IOException e) {
            System.err.println("[EMI-AutoCraft] 写日志失败: " + e);
        }
    }
}
