package org.engine;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Map;

public class Main {
	public static void main(String[] args) {
		try {
			if (args != null && args.length > 0 && args[0].equalsIgnoreCase("bench")) {
				int depth = 4;
				if (args.length > 1) try { depth = Integer.parseInt(args[1]); } catch (Exception ignored) {}
				Bench.run(depth);
				return;
			}
			UCI.main(args);
		} catch (Throwable t) {
			String logPath = writeCrashLog(t, args);
			System.err.println("Catalan crashed. Crash log written to: " + logPath);
		}
	}

	private static String writeCrashLog(Throwable throwable, String[] args) {
		LocalDateTime now = LocalDateTime.now();
		String timestamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
		String fileName = "Catalan-crash-" + timestamp + ".log";

		String userHome = System.getProperty("user.home", ".");
		Path dir = Paths.get(userHome, "Documents", "Catalan", "logs");
		try {
			Files.createDirectories(dir);
			Path target = dir.resolve(fileName);
			try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(target))) {
				writeCrashReport(out, throwable, args);
			}
			return target.toAbsolutePath().toString();
		} catch (IOException ignored) {
			// Fall back to stderr if file writing failed
			throwable.printStackTrace();
			return dir.resolve(fileName).toAbsolutePath().toString();
		}
	}

	private static void writeCrashReport(PrintWriter out, Throwable throwable, String[] args) {
		LocalDateTime now = LocalDateTime.now();
		Runtime rt = Runtime.getRuntime();
		RuntimeMXBean mx = ManagementFactory.getRuntimeMXBean();

		out.println("==== Catalan Crash Report ====");
		out.println("Timestamp: " + now);
		out.println("Working Dir: " + System.getProperty("user.dir"));
		out.println("User Home: " + System.getProperty("user.home"));
		out.println("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ") " + System.getProperty("java.vm.name"));
		out.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
		out.println("PID: " + getPid(mx));
		out.println("CPUs: " + rt.availableProcessors());
		out.println("Memory (bytes): total=" + rt.totalMemory() + ", free=" + rt.freeMemory() + ", max=" + rt.maxMemory());
		out.print("Args: ");
		if (args == null || args.length == 0) out.println("<none>"); else {
			for (int i = 0; i < args.length; i++) {
				if (i > 0) out.print(' ');
				out.print('"');
				out.print(args[i]);
				out.print('"');
			}
			out.println();
		}
		out.println();
		out.println("-- Exception --");
		throwable.printStackTrace(out);
		out.println();
		out.println("-- All Threads --");
		for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
			Thread th = e.getKey();
			out.println("Thread: name=\"" + th.getName() + "\" id=" + th.getId() + " state=" + th.getState());
			for (StackTraceElement el : e.getValue()) {
				out.println("\tat " + el);
			}
			out.println();
		}
	}

	private static String getPid(RuntimeMXBean mx) {
		try {
			String name = mx.getName(); // format: pid@hostname
			int idx = name.indexOf('@');
			return idx > 0 ? name.substring(0, idx) : name;
		} catch (Exception ignored) { return "unknown"; }
	}
}