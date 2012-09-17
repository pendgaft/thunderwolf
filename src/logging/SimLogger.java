package logging;

import java.io.*;
import java.util.*;

import router.BGPSpeaker;

public class SimLogger {

	private BufferedWriter memOut;
	private BufferedWriter tableSizeOut;

	private List<Integer> orderedASNList;
	private HashMap<Integer, HashMap<Long, Double>> asnMemMap;

	public static final long LOG_EPOCH = 30000;
	private static final String LOG_DIR = "logs/";

	private static final String MEM_STUB = "-mem.csv";
	private static final String TABLE_STUB = "-table.csv";

	public SimLogger(String fileBase, HashMap<Integer, BGPSpeaker> topo)
			throws IOException {
		this.memOut = new BufferedWriter(new FileWriter(SimLogger.LOG_DIR
				+ fileBase + SimLogger.MEM_STUB));
		this.tableSizeOut = new BufferedWriter(new FileWriter(SimLogger.LOG_DIR
				+ fileBase + SimLogger.TABLE_STUB));

		this.orderedASNList = this.buildOrderedASNList(topo);
		this.setupMaps();
	}

	public void setupStatPush() throws IOException {

		for (int counter = 0; counter < this.orderedASNList.size(); counter++) {
			this.tableSizeOut.write("time," + this.orderedASNList.get(counter)
					+ ",");
			this.memOut.write("time," + this.orderedASNList.get(counter) + ",");
		}
		this.tableSizeOut.newLine();
		this.memOut.newLine();
	}

	public void doneLogging() throws IOException {
		//TODO this in the future needs to be done on a more regular basis
		this.flushLogs();

		this.memOut.close();
		this.tableSizeOut.close();
	}

	public void reportMemLoad(int asn, long currentTime, long memLoad) {
		this.asnMemMap.get(asn).put(currentTime, memLoad / 1000000.0);
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

	private void setupMaps() {
		this.asnMemMap = new HashMap<Integer, HashMap<Long, Double>>();

		for (int tASN : this.orderedASNList) {
			this.asnMemMap.put(tASN, new HashMap<Long, Double>());
		}
	}

	private void flushLogs() {
		HashMap<Integer, List<Long>> timeList = new HashMap<Integer, List<Long>>();

		/*
		 * Build time lists
		 */
		for (int tASN : this.asnMemMap.keySet()) {
			List<Long> tempList = new ArrayList<Long>();
			tempList.addAll(this.asnMemMap.get(tASN).keySet());
			Collections.sort(tempList);
			timeList.put(tASN, tempList);
		}

		int size = 0;
		for (List<Long> tList : timeList.values()) {
			size = Math.max(size, tList.size());
		}
		
		for (int counter = 0; counter < size; counter++) {			
			String writeStr = "";
			for (int asPos = 0; asPos < this.orderedASNList.size(); asPos++) {
				int currentAS = this.orderedASNList.get(asPos);
				List<Long> currentList = timeList.get(currentAS);
				if (currentList.size() > counter) {
					long tempTime = currentList.get(counter);
					writeStr += tempTime + ","
							+ this.asnMemMap.get(currentAS).get(tempTime) + ","; 
				} else {
					writeStr += ",,";
				}
			}
			try {
				this.memOut.write(writeStr);
				this.memOut.newLine();
			} catch (IOException e) {
				e.printStackTrace();
				System.out.println("Error printing to log, trying to muddle through.");
			}
		}
	}
}
