package org.engine;

public class Main {
	public static void main(String[] args) throws Exception {
		if (args != null && args.length > 0 && args[0].equalsIgnoreCase("bench")) {
			int depth = 4;
			if (args.length > 1) try { depth = Integer.parseInt(args[1]); } catch (Exception ignored) {}
			Bench.run(depth);
			return;
		}
		UCI.main(args);
	}
}