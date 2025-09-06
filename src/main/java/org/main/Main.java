package org.main;

import org.core.UCI;
import org.core.Bench;

public class Main {
	public static void main(String[] args) throws Exception {
		if (args != null && args.length > 0 && "bench".equalsIgnoreCase(args[0])) {
			int depth = 4;
			if (args.length > 1) {
				try { depth = Integer.parseInt(args[1]); } catch (Exception ignored) {}
			}
			Bench.run(depth);
			return;
		}

		System.out.println("Catalan Chess Engine");
		UCI.main(args);
	}
}