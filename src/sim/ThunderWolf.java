package sim;

import java.util.*;
import java.io.*;

import router.BGPSpeaker;
import router.ASTopoParser;
import networkConfig.*;
import threading.BGPMaster;

public class ThunderWolf {

	public static final boolean DEBUG = false;

	/**
	 * This flag turns on a somewhat ghetto hack when dealing with our
	 * relationship to "ourself"
	 */
	//TODO look into what this does deeply, as I don't fucking like it
	public static final boolean FASTANDLOOSE = true;

	protected HashMap<Integer, BGPSpeaker> routerMap = null;
	private NetworkSeeder netSeeder = null;

	protected enum Mode {
		EVEN, INJECTOR, REAL
	}

	public static void main(String[] args) throws IOException {

		/*
		 * Determine mode type
		 */
		Mode theMode;
		if (args[1].equalsIgnoreCase("even")) {
			theMode = Mode.EVEN;
		} else if (args[1].equalsIgnoreCase("injector")) {
			theMode = Mode.INJECTOR;
		} else if (args[1].equalsIgnoreCase("realistic")) {
			theMode = Mode.REAL;
		} else {
			System.err.println("Bad mode given: " + args[1]);
			return;
		}

		/*
		 * Build the ThunderWolf Object and run the simulation
		 */
		ThunderWolf me = new ThunderWolf(args[0], theMode);
		me.runSimulation();
	}

	@SuppressWarnings("unused")
	private ThunderWolf() {
		/*
		 * Hidden since default constructor makes no sense
		 */
	}

	protected ThunderWolf(String topologyFile, Mode myMode) throws IOException {

		/*
		 * Longs used to store wall clock times for speed reporting
		 */
		long start, end;

		/*
		 * Create topology
		 */
		System.out.println("Creating router topology.");
		start = System.currentTimeMillis();
		ASTopoParser topoParse = new ASTopoParser(topologyFile + "-rel.txt", topologyFile + "-ip.txt", true);
		if (myMode == Mode.REAL) {
			//XXX currently the real file is pruned
			//this.routerMap = topoParse.doNetworkBuild(true);
			this.routerMap = topoParse.doNetworkBuild(false);
		} else {
			this.routerMap = topoParse.doNetworkBuild(false);
		}
		end = System.currentTimeMillis();
		System.out.println("Topology created in: " + (end - start) / 1000 + " seconds.\n");

		/*
		 * Setup network seeder
		 */
		if (myMode == Mode.EVEN) {
			this.netSeeder = new EvenSeed(routerMap);
		} else if (myMode == Mode.INJECTOR) {
			//this.netSeeder = new SingleInjector(routerMap, 1, 50000, 10);
			HashSet<Integer> targetSet = new HashSet<Integer>();
			targetSet.add(3);
			this.netSeeder = new MultiInjector(routerMap, targetSet, 50000, 10);
		} else if (myMode == Mode.REAL) {
			this.netSeeder = new RealSeeder(routerMap, topoParse.getUnpruned());
		} else {
			throw new RuntimeException("Passed a mode to ThunderWolf Constructor that we do not know how to seed!");
		}
	}

	protected void runSimulation() throws IOException {
		System.out.println("Firing Sim Trigger");
		BGPMaster.driveSim(this.routerMap, this.netSeeder);
	}

}
