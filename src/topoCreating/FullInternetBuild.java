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
		System.out.println("original parse: " + topParse.getUnpruned().size());
		HashMap<Integer, AS> adjusted = FullInternetBuild.incorpIPCount(BASE_IP_FILE, topParse.getUnpruned());
		System.out.println("sent back: " + adjusted.size());
		
		//HashMap<Integer, AS> asMap = new HashMap<Integer, AS>();
		//for(int tASN: startingMap.keySet()){
		//	asMap.put(tASN, startingMap.get(tASN).getASObject());
		//}
		
		//HashMap<Integer, AS> prunedMap = FullInternetBuild.pruneTopo(asMap);
		//HashMap<Integer, AS> secondPruneMap = FullInternetBuild.pruneTopo(prunedMap);
		FullInternetBuild.dumpToFile(adjusted);
	}
	
	private static void dumpToFile(HashMap<Integer, AS> asMap) throws IOException{
		BufferedWriter outFile = new BufferedWriter(new FileWriter(FullInternetBuild.OUT_FILE + "-rel.txt"));
		
		long count = 0;
		for(AS tAS: asMap.values()){
			for(int tNeighbor: tAS.getNeighbors()){
				/*
				 * THE SIGN FLIP IS CORRECT.  getRel returns their relationship to us, we want our relationship to them!!!!
				 */
				outFile.write("" + tAS.getASN() + "|" + tNeighbor + "|" + (tAS.getRel(tNeighbor) * -1) + "\n");
				count++;
			}
		}
		outFile.close();
		System.out.println("line count: " + count);
		
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
				}void
				
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
		}ger> keySet = topo.keySet();
		keySet.r
		
		return retMap;
	}
	
	private static HashMap<Integer, AS> incorpIPCount(String ipCountFile, HashMap<Integer, AS> topo) throws IOException{
		HashMap<Integer, AS> retMap = new HashMap<Integer, AS>();
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
			
			AS tAS = topo.get(asn);
			tAS.setCIDRSize(ipCount);
			seenASes.add(asn);
			retMap.put(tAS.getASN(), tAS);
		}
		
		Set<Integer> keySet = topo.keySet();
		keySet.removeAll(seenASes);
		
		for(int tASN: keySet){
			retMap.put(tASN, topo.get(tASN));
		}
		
		System.out.println("IP Integration Summary:");
		System.out.println("Total IP blocks: " + totalIPCount);
		System.out.println("Assigned IP blocks: " + (totalIPCount - noASCount));
		System.out.println("Un-assigned IP blocks: " + noASCount);
		System.out.println("Non adjusted ASes: " + keySet.size());
		
		return retMap;
	}
}
