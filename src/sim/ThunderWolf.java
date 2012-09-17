package sim;

import java.util.*;
import java.io.*;

import router.BGPSpeaker;
import router.ASTopoParser;
import networkConfig.*;
import threading.BGPMaster;

public class ThunderWolf {

	public static final boolean DEBUG = false;
	public static final boolean FASTANDLOOSE = true;

	public static void main(String[] args) throws IOException {

		long start, end;

		/*
		 * Create topology
		 */
		System.out.println("Creating router topology.");
		start = System.currentTimeMillis();
		HashMap<Integer, BGPSpeaker> routerMap = ASTopoParser.doNetworkBuild(args[0]);
		end = System.currentTimeMillis();
		System.out.println("Topology created in: " + (end - start) / 1000
				+ " seconds.\n");

		/*
		 * Debug, dump topo info
		 */
		if (DEBUG) {
			System.out.println("Topo size is: " + routerMap.size());
			for (BGPSpeaker tRouter : routerMap.values()) {
				System.out.println(tRouter.printDebugString());
			}
		}
		
		/*
		 * Setup network seeder
		 */
		NetworkSeeder netSeed = null;
		if(args[1].equalsIgnoreCase("even")){
			netSeed = new EvenSeed(routerMap);
		} else if(args[1].equalsIgnoreCase("injector")){
			netSeed = new SingleInjector(routerMap, 1, 350000, 10);
		} else{
			System.err.println("Bad mode given: " + args[1]);
			return;
		}
		
		System.out.println("Firing Sim Trigger");
		BGPMaster.driveSim(routerMap, netSeed);

		if (DEBUG) {
			System.out
					.println("Tables look like..........\n*******************");
			for (BGPSpeaker tRouter : routerMap.values()) {
				System.out.println(tRouter.printBGPString(true));
			}
		}
	}
}
