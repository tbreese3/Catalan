package org.engine;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

public class Main {
    static {
        installCrashLogger();
    }

	public static void main(String[] args) throws Exception {
		if (args != null && args.length > 0 && args[0].equalsIgnoreCase("pgo")) {
			UCI.runPgoMovetime(15000);
			return;
		}
		if (args != null && args.length > 0 && args[0].equalsIgnoreCase("bench")) {
			int depth = 4;
			if (args.length > 1) try { depth = Integer.parseInt(args[1]); } catch (Exception ignored) {}
			Bench.run(depth);
			return;
		}
		UCI.main(args);
	}

    private static void installCrashLogger() {
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                writeCrashLog(thread, throwable);
            } catch (Throwable loggingFailure) {
                try {
                    System.err.println("[CRASH] Failed to write crash log: " + loggingFailure);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void writeCrashLog(Thread thread, Throwable throwable) {
        Path baseDir = resolveLogDirectory();
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                .format(LocalDateTime.now());
        String fileName = "crash-" + timestamp + ".log";
        Path logFile = baseDir.resolve(fileName);

        try {
            Files.createDirectories(baseDir);
        } catch (IOException ignored) {}

        try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8)) {
            // Header
            writer.write("===== Crash Report =====\n");
            writer.write("Timestamp: " + LocalDateTime.now() + "\n");
            writer.write("Thread: " + safeThreadName(thread) + " (id=" + thread.getId() + ")\n");
            writer.write("LogDir: " + safeString(baseDir.toAbsolutePath().toString()) + "\n");
            writer.write("WorkingDir: " + safeString(System.getProperty("user.dir", "")) + "\n");
            writer.write("LogFile: " + safeString(logFile.toAbsolutePath().toString()) + "\n\n");

            // Exception section
            writer.write("--- Uncaught Exception ---\n");
            try (PrintWriter pw = new PrintWriter(writer)) {
                throwable.printStackTrace(pw);
                pw.flush();
            }
            writer.write("\n");

            // JVM and OS info
            writer.write("--- Environment ---\n");
            RuntimeMXBean rmx = ManagementFactory.getRuntimeMXBean();
            writer.write("JavaVersion: " + System.getProperty("java.version", "") + "\n");
            writer.write("JavaVendor: " + System.getProperty("java.vendor", "") + "\n");
            writer.write("JavaHome: " + System.getProperty("java.home", "") + "\n");
            writer.write("OS: " + System.getProperty("os.name", "") + " " + System.getProperty("os.version", "") +
                    " (" + System.getProperty("os.arch", "") + ")\n");
            writer.write("PID: " + safePid(rmx.getName()) + "\n");
            writer.write("UptimeMs: " + rmx.getUptime() + "\n");
            writer.write("InputArgs: " + String.join(" ", rmx.getInputArguments()) + "\n");
            writer.write("ClassPath: " + System.getProperty("java.class.path", "") + "\n\n");

            // Memory stats
            Runtime rt = Runtime.getRuntime();
            writer.write("--- Memory (bytes) ---\n");
            writer.write("Max: " + rt.maxMemory() + ", Total: " + rt.totalMemory() + ", Free: " + rt.freeMemory() +
                    ", Used: " + (rt.totalMemory() - rt.freeMemory()) + "\n\n");

            // System properties
            writer.write("--- System Properties ---\n");
            Properties props = System.getProperties();
            for (Map.Entry<Object, Object> e : props.entrySet()) {
                writer.write(safeString(String.valueOf(e.getKey())) + "=" + safeString(String.valueOf(e.getValue())) + "\n");
            }
            writer.write("\n");

            // Environment variables
            writer.write("--- Environment Variables ---\n");
            for (Map.Entry<String, String> e : System.getenv().entrySet()) {
                writer.write(safeString(e.getKey()) + "=" + safeString(e.getValue()) + "\n");
            }
            writer.write("\n");

            // Thread dump
            writer.write("--- Thread Dump ---\n");
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
                Thread th = entry.getKey();
                writer.write("Thread: " + safeThreadName(th) + " (id=" + th.getId() + ", state=" + th.getState() + ")\n");
                for (StackTraceElement ste : entry.getValue()) {
                    writer.write("    at " + ste.toString() + "\n");
                }
                writer.write("\n");
            }

            writer.write("===== End of Report =====\n");
            writer.flush();
        } catch (IOException io) {
            try {
                System.err.println("[CRASH] Unable to write crash log to " + logFile + ": " + io);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Path resolveLogDirectory() {
        try {
            return Paths.get("C:\\catalanlogs");
        } catch (Throwable ignored) {
            try {
                String userDir = System.getProperty("user.dir");
                return Paths.get(userDir == null ? "." : userDir);
            } catch (Throwable ignored2) {
                return Paths.get(".");
            }
        }
    }

    private static String safeThreadName(Thread t) {
        try {
            return t == null ? "<null>" : String.valueOf(t.getName());
        } catch (Throwable ignored) {
            return "<unavailable>";
        }
    }

    private static String safeString(String s) {
        try {
            return s == null ? "" : s;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String safePid(String mxName) {
        try {
            if (mxName == null) return "";
            int at = mxName.indexOf('@');
            return at > 0 ? mxName.substring(0, at) : mxName;
        } catch (Throwable ignored) {
            return "";
        }
    }
}