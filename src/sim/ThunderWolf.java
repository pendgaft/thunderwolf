package sim;

import java.util.*;
import java.io.*;

import router.BGPSpeaker;
import router.ASTopoParser;

public class ThunderWolf {

	public static final boolean DEBUG = true;

	public static void main(String[] args) throws IOException {

		long start, end;

		/*
		 * Create topology
		 */
		System.out.println("Creating router topology.");
		start = System.currentTimeMillis();
		HashMap<Integer, BGPSpeaker> routerMap = ASTopoParser.doNetworkBuild();
		end = System.currentTimeMillis();
		System.out.println("Topology created in: " + (end - start) / 1000 + " seconds.\n");

		/*
		 * Debug, dump topo info
		 */
		if (DEBUG) {
			System.out.println("Topo size is: " + routerMap.size());
			for (BGPSpeaker tRouter : routerMap.values()) {
				System.out.println(tRouter.printDebugString());
			}
		}
	}
}
