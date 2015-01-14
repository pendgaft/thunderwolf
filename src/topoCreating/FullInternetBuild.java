package topoCreating;

import java.util.*;
import java.io.*;
import java.util.regex.*;

import router.*;

public class FullInternetBuild {

	//private static final String BASE_AS_REL_FILE = "/scratch/waterhouse/schuch/asData/current-as-rel";
	private static final String BASE_AS_REL_FILE = "20150101.as-rel.txt";
	//private static final String BASE_IP_FILE = "/scratch/waterhouse/schuch/asData/current-ip-count";
	private static final String BASE_IP_FILE = "20150101-ip.txt";
	private static final String OUT_FILE = "pruned-weighted-internet";

	/**
	 * @param args
	 */
	public static void main(String[] args) throws IOException {
	
		ASTopoParser topParse = new ASTopoParser(FullInternetBuild.BASE_AS_REL_FILE, FullInternetBuild.BASE_IP_FILE, false);
		HashMap<Integer, BGPSpeaker> startingMap = topParse.doNetworkBuild(true);
		System.out.println("original parse: " + topParse.getUnpruned().size());
		System.out.println("pruned size: " + startingMap.keySet().size());

		HashMap<Integer, AS> asMap = new HashMap<Integer, AS>();
		for(int tASN: startingMap.keySet()){
			asMap.put(tASN, startingMap.get(tASN).getASObject());
		}
		//HashMap<Integer, AS> prunedMap = FullInternetBuild.pruneTopo(asMap);
		//HashMap<Integer, AS> secondPruneMap = FullInternetBuild.pruneTopo(prunedMap);
		FullInternetBuild.dumpToFile(asMap);
	}

	private static void dumpToFile(HashMap<Integer, AS> asMap) throws IOException {
		BufferedWriter outFile = new BufferedWriter(new FileWriter(FullInternetBuild.OUT_FILE + "-rel.txt"));

		long count = 0;
		for (AS tAS : asMap.values()) {
			for (int tNeighbor : tAS.getNeighbors()) {
				/*
				 * THE SIGN FLIP IS CORRECT. getRel returns their relationship
				 * to us, we want our relationship to them!!!!
				 */
				outFile.write("" + tAS.getASN() + "|" + tNeighbor + "|" + tAS.getMyRelationshipTo(tNeighbor) + "\n");
				count++;
			}
		}
		outFile.close();
		System.out.println("line count: " + count);

		outFile = new BufferedWriter(new FileWriter(FullInternetBuild.OUT_FILE + "-ip.txt"));
		for (AS tAS : asMap.values()) {
			outFile.write("" + tAS.getASN() + " " + tAS.getCIDRSize() + "\n");
		}
		outFile.close();

	}

	private static HashMap<Integer, AS> incorpIPCount(String ipCountFile, HashMap<Integer, AS> fullTopo,
			HashMap<Integer, BGPSpeaker> prunedMap) throws IOException {
		HashMap<Integer, AS> retMap = new HashMap<Integer, AS>();
		int noASCount = 0;
		int totalIPCount = 0;
		HashSet<Integer> seenASes = new HashSet<Integer>();

		BufferedReader fBuff = new BufferedReader(new FileReader(ipCountFile));
		while (fBuff.ready()) {
			String line = fBuff.readLine();
			/*
			 * Ignore comments like a baws
			 */
			if (line.startsWith("#")) {
				continue;
			}

			StringTokenizer tokens = new StringTokenizer(line, " ");
			int asn = Integer.parseInt(tokens.nextToken());
			int ipCount = Integer.parseInt(tokens.nextToken());

			/*
			 * Sanity check that the AS in question actually exists
			 */
			totalIPCount += ipCount;
			if (!fullTopo.containsKey(asn)) {
				noASCount += ipCount;
				continue;
			}

			/*
			 * Pull the AS, and update its CIDR count
			 */
			AS tAS = fullTopo.get(asn);
			tAS.setCIDRSize(ipCount);
			seenASes.add(asn);
			retMap.put(tAS.getASN(), tAS);
		}

		Set<Integer> keySet = fullTopo.keySet();
		keySet.removeAll(seenASes);

		/*
		 * Copy over all ASes that we didn't see in our file
		 */
		for (int tASN : keySet) {
			retMap.put(tASN, fullTopo.get(tASN));
		}

		/*
		 * Dump some stats
		 */
		System.out.println("IP Integration Summary:");
		System.out.println("Total IP blocks: " + totalIPCount);
		System.out.println("Assigned IP blocks: " + (totalIPCount - noASCount));
		System.out.println("Un-assigned IP blocks: " + noASCount);
		System.out.println("Non adjusted ASes: " + keySet.size());

		/*
		 * Now merge pruned ASes since it looks like we can't do this the simple
		 * way because of memory issues, first add all existing ASes, then fold
		 * in the pruned ASes to their providers
		 */
		HashMap<Integer, AS> finalPrunedMap = new HashMap<Integer, AS>();
		for (int tASN : prunedMap.keySet()) {
			AS tAS = prunedMap.get(tASN).getASObject();
			tAS.setCIDRSize(retMap.get(tASN).getCIDRSize());
			finalPrunedMap.put(tASN, tAS);
		}
		for (int tASN : retMap.keySet()) {
			if (finalPrunedMap.containsKey(tASN)) {
				continue;
			}

			AS tAS = retMap.get(tASN);
			for (int tProv : tAS.getProviders()) {
				finalPrunedMap.get(tProv).setCIDRSize(finalPrunedMap.get(tProv).getCIDRSize() + tAS.getCIDRSize());
			}
		}

		return finalPrunedMap;
	}

	public static void convertNewIPFileToCountFile(String inFileName, String outFileName) throws IOException {
		HashMap<Integer, Integer> sizeMapping = new HashMap<Integer, Integer>();
		Pattern newFormatPattern = Pattern.compile("\\S+\\s+\\S+\\s+(\\S+)");
		
		BufferedReader newFormatBuff = new BufferedReader(new FileReader(inFileName));
		int failedMatch = 0;
		int coalitionMatch = 0;
		int longASCount = 0;
		while(newFormatBuff.ready()){
			Matcher lineMatch = newFormatPattern.matcher(newFormatBuff.readLine());
			if(!lineMatch.find()){
				failedMatch++;
				continue;
			}
			
			if(lineMatch.group(1).contains("_") || lineMatch.group(1).contains(",")){
				coalitionMatch++;
				continue;
			}
			if(Long.parseLong(lineMatch.group(1)) > (long)Integer.MAX_VALUE){
				longASCount++;
				continue;
			}
			Integer asn = Integer.parseInt(lineMatch.group(1));
			if(!sizeMapping.containsKey(asn)){
				sizeMapping.put(asn, 0);
			}
			sizeMapping.put(asn, sizeMapping.get(asn) + 1);
		}
		newFormatBuff.close();
		System.out.println("failed count: " + failedMatch);
		System.out.println("coalition count: " + coalitionMatch);
		System.out.println("8 byte asn count: " + longASCount);
		
		BufferedWriter outBuff = new BufferedWriter(new FileWriter(outFileName));
		for(Integer asn: sizeMapping.keySet()){
			outBuff.write("" + asn + " " + sizeMapping.get(asn) + "\n");
		}
		outBuff.close();
		
		int totalDFZSize = 0;
		int maxSize = 0;
		for(int size: sizeMapping.values()){
			totalDFZSize += size;
			maxSize = Math.max(maxSize, size);
		}
		System.out.println("total size: " + totalDFZSize);
		System.out.println("max as size: " + maxSize);
	}
}
