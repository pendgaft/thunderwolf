package topoCreating;

import java.util.*;
import java.io.*;

import router.*;

public class FullInternetBuild {

	
	private static final String BASE_FILE = "full-internet";
	private static final String OUT_FILE = "pruned-internet";
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws IOException{
		HashMap<Integer, BGPSpeaker> startingMap = ASTopoParser.doNetworkBuild(FullInternetBuild.BASE_FILE);

		
		HashMap<Integer, AS> asMap = new HashMap<Integer, AS>();
		for(int tASN: startingMap.keySet()){
			asMap.put(tASN, startingMap.get(tASN).getASObject());
		}
		
		HashMap<Integer, AS> prunedMap = FullInternetBuild.pruneTopo(asMap);
		FullInternetBuild.dumpToFile(prunedMap);
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
		
		/*
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
				
				tAS.addRelation(retMap.get(tCust), AS.CUSTOMER_CODE);
			}
			for(int tPeer: oldAS.getPeers()){
				if(toRemove.contains(tPeer)){
					continue;
				}
				
				tAS.addRelation(retMap.get(tPeer), AS.PEER_CODE);
			}
			for(int tProv: oldAS.getProviders()){
				tAS.addRelation(retMap.get(tProv), AS.PROIVDER_CODE);
			}
		}
		
		return retMap;
	}
}
