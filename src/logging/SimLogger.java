package logging;

import java.io.*;
import java.util.*;

import router.BGPSpeaker;

public class SimLogger {

	private BufferedWriter memOut;
	private BufferedWriter tableSizeOut;

	private List<Integer> orderedASNList;
	private HashMap<Integer, BGPSpeaker> topology;

	private long nextLoggingHorizon;
	//TODO make this configurable in the future
	public static final long LOG_EPOCH = 120000;

	private static final String LOG_DIR = "logs/";
	private static final String MEM_STUB = "-mem.csv";
	private static final String TABLE_STUB = "-table.csv";

	public SimLogger(String fileBase, HashMap<Integer, BGPSpeaker> topo)
			throws IOException {
		this.memOut = new BufferedWriter(new FileWriter(SimLogger.LOG_DIR
				+ fileBase + SimLogger.MEM_STUB));
		this.tableSizeOut = new BufferedWriter(new FileWriter(SimLogger.LOG_DIR
				+ fileBase + SimLogger.TABLE_STUB));

		this.topology = topo;
		this.orderedASNList = this.buildOrderedASNList(topo);
		this.nextLoggingHorizon = SimLogger.LOG_EPOCH;
	}

	/**
	 * Getter for the next time we want to take logging measurements.
	 * 
	 * @return - the simulated time we want to log from
	 */
	public long getNextLogTime() {
		return this.nextLoggingHorizon;
	}

	/**
	 * Public function to tell the sim logger to setup all required objects for
	 * logging. After this function is called calls to processLogging are
	 * expected to function. This MUST be called before processLogging is
	 * called.
	 * 
	 * @throws IOException - if there is an error from internal functions doing setup
	 */
	public void setupStatPush() throws IOException {
		this.setupLoggingHeader(memOut);
		this.setupLoggingHeader(this.tableSizeOut);
	}

	/**
	 * Public method to tell the logger that all nodes are ready to have logging
	 * done for the current logging wall. This will trigger the fetching of
	 * stats directly from the BGPSpeaker objects and cause the corrisponding
	 * data to be written to output files.
	 * 
	 * @throws IOException
	 *             - if there is an error writting to any of the log files
	 */
	public void processLogging() throws IOException {

		HashMap<Integer, Double> memMap = new HashMap<Integer, Double>();
		HashMap<Integer, Double> tableSize = new HashMap<Integer, Double>();

		/*
		 * Fetch all of the memory loads and table sizes
		 */
		//TODO make this configurable in the future (as to what stats we're tracking)
		for (int tASN : this.topology.keySet()) {
			memMap.put(tASN, (double) this.topology.get(tASN).memLoad());
			tableSize.put(tASN, (double) this.topology.get(tASN)
					.calcTotalRouteCount());
		}

		/*
		 * Actually do the logging, these can throw IOExceptions, that should be
		 * handled by the calling class
		 */
		this
				.writeToLog(memMap, this.nextLoggingHorizon, this.memOut,
						1000000.0);
		this.writeToLog(tableSize, this.nextLoggingHorizon, this.tableSizeOut,
				1000.0);

		/*
		 * Last thing, since we're done w/ this window, scoot the time horizon
		 * up
		 */
		this.nextLoggingHorizon += SimLogger.LOG_EPOCH;
	}

	/**
	 * Public method to tell the logging object that we're done and that it
	 * should close all of the logging buffers.
	 * 
	 * @throws IOException
	 *             - if there is an error closing files
	 */
	public void doneLogging() throws IOException {
		this.memOut.close();
		this.tableSizeOut.close();
	}

	/**
	 * Function that builds a list of ASNs in ASN order. This is used in order
	 * to report stats across a csv in a consistent manner.
	 * 
	 * @return a list of ASNs in the active topo in ascending order
	 */
	private List<Integer> buildOrderedASNList(HashMap<Integer, BGPSpeaker> topo) {
		List<Integer> retList = new ArrayList<Integer>();
		retList.addAll(topo.keySet());
		Collections.sort(retList);
		return retList;
	}

	/**
	 * Interal function to setup the header for a log file. Simple writes the
	 * word time, and then sets up the ASNs in the correct columns so that
	 * subsequent calls to writeToLog will record the correct values. This
	 * should be called exactly once for each stream.
	 * 
	 * @param stream
	 *            - the stream we're going to be writting data to eventually.
	 * @throws IOException
	 *             - if there is an error writting to the file
	 */
	private void setupLoggingHeader(BufferedWriter stream) throws IOException {

		stream.write("time");
		for (int counter = 0; counter < this.orderedASNList.size(); counter++) {
			stream.write("," + this.orderedASNList.get(counter));
		}
		stream.newLine();
	}

	/**
	 * Internal function to flush values to a log file. Takes a mapping between
	 * ASNs and a value that sadly must be a double. The value can have a
	 * universal scalling applied to it if desired.
	 * 
	 * @param values
	 *            - the mapping from ASNs to values, values must be doubles
	 * @param currentTime
	 *            - the simulated time that this data is taken from
	 * @param outputStream
	 *            - the buffer where the data should be written
	 * @param scaleFactor
	 *            - a fixed value to scale all values stored in the map by, set
	 *            this to 1.0 if you just want the values
	 * @throws IOException
	 *             - if anything breaks writing to the file
	 */
	private void writeToLog(HashMap<Integer, Double> values, long currentTime,
			BufferedWriter outputStream, double scaleFactor) throws IOException {

		/*
		 * First, get the time recorded, convert to seconds
		 */
		outputStream.write("" + currentTime / 1000);

		/*
		 * Now write the correct data in the correct order
		 */
		int currentAS;
		for (int counter = 0; counter < this.orderedASNList.size(); counter++) {
			currentAS = this.orderedASNList.get(counter);
			outputStream.write("," + values.get(currentAS) / scaleFactor);
		}

		/*
		 * Last, terminate the line
		 */
		outputStream.newLine();
	}
}
