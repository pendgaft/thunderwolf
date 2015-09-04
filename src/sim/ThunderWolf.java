package sim;

import java.util.*;
import java.io.*;

import logging.SimLogger;
import router.BGPSpeaker;
import router.ASTopoParser;
import networkConfig.*;
import threading.FlowDriver;
import net.sourceforge.argparse4j.inf.*;
import net.sourceforge.argparse4j.*;

public class ThunderWolf {

	public static final boolean DEBUG = false;

	/**
	 * This flag turns on a somewhat ghetto hack when dealing with our
	 * relationship to "ourself"
	 */
	//TODO look into what this does deeply, as I don't fucking like it
	public static final boolean FASTANDLOOSE = true;

	private Mode myMode = null;
	private String topoBase = null;
	protected HashMap<Integer, BGPSpeaker> routerMap = null;
	private NetworkSeeder netSeeder = null;
	private SimLogger logMaster = null;

	private Namespace ns = null;

	protected enum Mode {
		EVEN, INJECTOR, REAL
	}

	public static void main(String[] args) throws IOException {

		ArgumentParser argParse = ArgumentParsers.newArgumentParser("nightwing");
		argParse.addArgument("-m", "--mode").help("sim mode").required(true).type(ThunderWolf.Mode.class);
		argParse.addArgument("-t", "--topo").help("topo file base name").required(true);

		/*
		 * Actually parse
		 */
		Namespace ns = null;
		try {
			ns = argParse.parseArgs(args);
		} catch (ArgumentParserException e1) {
			argParse.handleError(e1);
			System.exit(-1);
		}

		/*
		 * Build the ThunderWolf Object and run the simulation
		 */
		ThunderWolf me = new ThunderWolf(ns);
		me.runSimulation();
	}

	@SuppressWarnings("unused")
	private ThunderWolf() {
		/*
		 * Hidden since default constructor makes no sense
		 */
	}

	protected ThunderWolf(Namespace ns) throws IOException {

		this.ns = ns;
		this.topoBase = this.ns.getString("topo");
		this.myMode = ns.get("mode");

		/*
		 * Longs used to store wall clock times for speed reporting
		 */
		long start, end;

		/*
		 * Create topology
		 */
		System.out.println("Creating router topology.");
		start = System.currentTimeMillis();
		ASTopoParser topoParse = new ASTopoParser(this.topoBase + "-rel.txt", this.topoBase + "-ip.txt", true);

		if (this.myMode == Mode.REAL) {
			//XXX currently the real file is pruned
			//this.routerMap = topoParse.doNetworkBuild(true);
			this.routerMap = topoParse.doNetworkBuild(0);
		} else {
			this.routerMap = topoParse.doNetworkBuild(0);
		}
		end = System.currentTimeMillis();
		System.out.println("Topology created in: " + (end - start) / 1000 + " seconds.\n");

		/*
		 * Setup network seeder
		 */
		if (this.myMode == Mode.EVEN) {
			this.netSeeder = new EvenSeed(routerMap);
		} else if (this.myMode == Mode.INJECTOR) {
			//this.netSeeder = new SingleInjector(routerMap, 1, 50000, 10);
			HashSet<Integer> targetSet = new HashSet<Integer>();
			targetSet.add(3);
			this.netSeeder = new MultiInjector(routerMap, targetSet, 50000, 10);
		} else if (this.myMode == Mode.REAL) {
			this.netSeeder = new RealSeeder(routerMap, topoParse.getUnpruned());
		} else {
			throw new RuntimeException("Passed a mode to ThunderWolf Constructor that we do not know how to seed!");
		}
		this.netSeeder.initialSeed();

		/*
		 * Build logging mechanisms
		 */
		try {
			this.logMaster = new SimLogger(this.buildLogFileBase(), this.routerMap);
			this.logMaster.setupStatPush();
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Logger setup failed, aborting.");
			System.exit(2);
		}
	}

	private String buildLogFileBase() {
		String logFile = Constants.BASE_LOG_DIR;
		logFile += this.topoBase + "-";

		if (this.myMode == Mode.EVEN) {
			logFile += "even";
		} else if (this.myMode == Mode.INJECTOR) {
			logFile += "injector";
		} else if (this.myMode == Mode.REAL) {
			logFile += "real";
		} else {
			throw new RuntimeException("Mode not recognized in log file base builder.");
		}

		int counter = 1;
		File tLogFile = new File(logFile + counter);
		while (tLogFile.exists()) {
			counter++;
			tLogFile = new File(logFile + counter);
		}
		tLogFile.mkdirs();
		return tLogFile.getAbsolutePath(); 
	}

	protected void runSimulation() throws IOException {
		System.out.println("Firing Sim Trigger");
		FlowDriver simDriver = new FlowDriver(this.routerMap, this.logMaster);
		simDriver.run();
	}

}
