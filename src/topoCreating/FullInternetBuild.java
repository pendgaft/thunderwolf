package topoCreating;

import java.util.*;
import java.io.*;

import router.*;

public class FullInternetBuild {

	
	private static final String BASE_AS_REL_FILE = "/scratch/waterhouse/schuch/asData/current-as-rel";
	private static final String BASE_IP_FILE = "/scratch/waterhouse/schuch/asData/current-ip-count";
	private static final String OUT_FILE = "whole-internet";
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws IOException{
		
		ASTopoParser topParse = new ASTopoParser(FullInternetBuild.BASE_AS_REL_FILE, null, false);
		HashMap<Integer, BGPSpeaker> startingMap = topParse.doNetworkBuild(false);
		FullInternetBuild.incorpIPCount(BASE_IP_FILE, topParse.getUnpruned());

		
		//HashMap<Integer, AS> asMap = new HashMap<Integer, AS>();
		//for(int tASN: startingMap.keySet()){
		//	asMap.put(tASN, startingMap.get(tASN).getASObject());
		//}
		
		//HashMap<Integer, AS> prunedMap = FullInternetBuild.pruneTopo(asMap);
		//HashMap<Integer, AS> secondPruneMap = FullInternetBuild.pruneTopo(prunedMap);
		FullInternetBuild.dumpToFile(topParse.getUnpruned());
	}
	
	private static void dumpToFile(HashMap<Integer, AS> asMap) throws IOException{
		BufferedWriter outFile = new BufferedWriter(new FileWriter(FullInternetBuild.OUT_FILE + "-rel.txt"));
		
		for(AS tAS: asMap.values()){
			for(int tNeighbor: tAS.getNeighbors()){
				outFile.write("" + tAS.getASN() + "|" + tNeighbor + "|" + tAS.getRel(tNeighbor) + "\n");
			}
		}
		outFile.close();
		
		outFile = new BufferedWriter(new FileWriter(FullInternetBuild.OUT_FILE + "-ip.txt"));
		for(AS tAS: asMap.values()){
			outFile.write("" + tAS.getASN() + " " + tAS.getCIDRSize() + "\n");
		}
		outFile.close();
		
	}

	
	private static HashMap<Integer, AS> pruneTopo(HashMap<Integer, AS> startingMap){
		HashSet<Integer> toRemove = new HashSet<Integer>();
		
		/*cidrCount
		 * find all the ASNs of the ASes we want to prune
		 */
		for(AS tAS: startingMap.values()){
			if(tAS.getCustomers().size() == 0){
				toRemove.add(tAS.getASN());
			}
		}
		
		/*
		 * create the new AS objects, need all of them done before adding relationships
		 */
		HashMap<Integer, AS> retMap = new HashMap<Integer, AS>();
		for(AS tAS: startingMap.values()){
			if(toRemove.contains(tAS.getASN())){
				continue;
			}
			
			AS alteredAS = new AS(tAS.getASN(), tAS.getCIDRSize());
			retMap.put(alteredAS.getASN(), alteredAS);
		}
		
		/*
		 * add in the relationships
		 */
		for(AS tAS: retMap.values()){
			AS oldAS = startingMap.get(tAS.getASN());
			
			for(int tCust: oldAS.getCustomers()){
				if(toRemove.contains(tCust)){
					continue;
				}
				
				tAS.addCustomer(startingMap.get(tCust));
			}
			for(int tPeer: oldAS.getPeers()){
				if(toRemove.contains(tPeer)){
					continue;
				}
				
				tAS.addPeer(startingMap.get(tPeer));
			}
			for(int tProv: oldAS.getProviders()){
				tAS.addProvider(startingMap.get(tProv));
			}
		}
		
		return retMap;
	}
	
	private static void incorpIPCount(String ipCountFile, HashMap<Integer, AS> topo) throws IOException{
		int noASCount = 0;
		int totalIPCount = 0;
		HashSet<Integer> seenASes = new HashSet<Integer>();
		
		BufferedReader fBuff = new BufferedReader(new FileReader(ipCountFile));
		while(fBuff.ready()){
			String line = fBuff.readLine();
			/*
			 * Ignore comments like a baws
			 */
			if(line.startsWith("#")){
				continue;
			}
			
			StringTokenizer tokens = new StringTokenizer(line, ",");
			int asn = Integer.parseInt(tokens.nextToken());
			int ipCount = Integer.parseInt(tokens.nextToken());			
			
			totalIPCount += ipCount;
			if(!topo.containsKey(asn)){
				noASCount += ipCount;
				continue;
			}
			
			topo.get(asn).setCIDRSize(ipCount);
			seenASes.add(asn);
		}
		
		Set<Integer> keySet = topo.keySet();
		keySet.removeAll(seenASes);
		
		System.out.println("IP Integration Summary:");
		System.out.println("Total IP blocks: " + totalIPCount);
		System.out.println("Assigned IP blocks: " + (totalIPCount - noASCount));
		System.out.println("Un-assigned IP blocks: " + noASCount);
		System.out.println("Non adjusted ASes: " + keySet.size());
		
	}
}
