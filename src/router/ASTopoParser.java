package router;

import java.util.*;
import java.io.*;

import events.SimEvent;

/**
 * Class containing a static method for construction of the topology. This class
 * ASSUMES that the topology files are pre-pruned, and that any AS found in the
 * relationship file is also found in the cidr count file.
 * 
 * @author pendgaft
 * 
 */
public class ASTopoParser {

	private String asRelFileName;
	private String ipCountFileName;
	private HashMap<Integer, AS> unprunedTopo;
	private HashMap<Integer, AS> prunedTopo;
	private boolean hardFail;

	public static void main(String args[]) throws IOException {
		ASTopoParser test = new ASTopoParser("whole-internet-rel.txt", "whole-internet-ip.txt", true);
		HashMap<Integer, BGPSpeaker> resultOfBuild = test.doNetworkBuild(1);
		System.out.println("size: " + resultOfBuild.size());
	}

	public ASTopoParser(String asRelFile, String ipFile, boolean simRun) {
		this.asRelFileName = asRelFile;
		this.ipCountFileName = ipFile;
		this.unprunedTopo = null;
		this.hardFail = simRun;
	}

	public HashMap<Integer, AS> getUnpruned() {
		return this.unprunedTopo;
	}

	/**
	 * Static function that does the actual building of BGPSpeaker objects. It
	 * of course builds the AS objects first, then creates the BGP Speakers
	 * 
	 * @return - an ASN to BGPSpeaker mapping
	 * @throws IOException
	 *             - if there is an error reading either the relationship or
	 *             cidr file
	 */
	public HashMap<Integer, BGPSpeaker> doNetworkBuild(int numberOfPrunes) throws IOException {
		this.unprunedTopo = this.parseFile(this.asRelFileName, this.ipCountFileName);
		System.out.println("unpruned size: " + this.unprunedTopo.size());

		/*
		 * If we are suppose to do a prune of the ASes do it here please
		 */
		this.prunedTopo = this.unprunedTopo;
		for(int counter = 0; counter < numberOfPrunes; counter++){
			this.prunedTopo = this.doNetworkPrune();
		}
		System.out.println("pruned size: " + this.prunedTopo.size());

		/*
		 * Build the actual routers, pass a reference to the router map itself
		 */
		HashMap<Integer, BGPSpeaker> routerMap = new HashMap<Integer, BGPSpeaker>();
		HashSet<Double> mraiUnique = new HashSet<Double>();
		Random rng = new Random();
		for (AS tAS : this.prunedTopo.values()) {
			double jitter;
			double mraiValue = 0;
			while (mraiValue == 0) {
				jitter = rng.nextDouble() * (30.0 * SimEvent.SECOND_MULTIPLIER);
				mraiValue = 30.0 * SimEvent.SECOND_MULTIPLIER + jitter;
				/*
				 * Odds of a collision are stupid low, but be cautious
				 */
				if (mraiUnique.contains(mraiValue)) {
					mraiValue = 0;
				}
			}
			mraiUnique.add(mraiValue);
			routerMap.put(tAS.getASN(), new BGPSpeaker(tAS, routerMap, mraiValue));
		}
		for (BGPSpeaker tSpeaker : routerMap.values()) {
			tSpeaker.setupSharedQueues();
		}

		return routerMap;
	}

	/**
	 * Static function that does the actual parsing of two files to generate the
	 * AS level topology we're going to use.
	 * 
	 * @param asRelFile
	 *            - pipe separated file with as relationships, in the CAIDA
	 *            format
	 * @param cidrCountFile
	 *            - space separated file with the number of CIDRs each AS has
	 * @return - the global ASN to AS object map
	 * @throws IOException
	 *             - if there is an issue reading from either file
	 */
	private HashMap<Integer, AS> parseFile(String asRelFile, String cidrCountFile) throws IOException {

		HashMap<Integer, AS> retMap = new HashMap<Integer, AS>();
		int noCIDRCount = 0;

		String pollString;
		StringTokenizer pollToks;
		int lhsASN, rhsASN, rel;
		BufferedReader fBuff = null;

		/*
		 * Parse the cidr count file and use it to create the AS objects
		 */
		//TODO make this work with new format...
		if (cidrCountFile != null) {
			fBuff = new BufferedReader(new FileReader(cidrCountFile));
			while (fBuff.ready()) {
				pollString = fBuff.readLine().trim();

				/*
				 * ignore blanks
				 */
				if (pollString.length() == 0) {
					continue;
				}

				/*
				 * Ignore comments
				 */
				if (pollString.charAt(0) == '#') {
					continue;
				}

				/*
				 * Tokenize and go, the file is of the form <ASN> <CIDR COUNT>
				 */
				StringTokenizer tokens = new StringTokenizer(pollString, " ");
				int asn = Integer.parseInt(tokens.nextToken());
				retMap.put(asn, new AS(asn, Integer.parseInt(tokens.nextToken())));
			}
			fBuff.close();
		}

		/*
		 * Parse the relationship file and fill in accordingly
		 */
		fBuff = new BufferedReader(new FileReader(asRelFile));
		while (fBuff.ready()) {
			pollString = fBuff.readLine().trim();

			/*
			 * ignore blanks
			 */
			if (pollString.length() == 0) {
				continue;
			}

			/*
			 * Ignore comments
			 */
			if (pollString.charAt(0) == '#') {
				continue;
			}

			/*
			 * Read the ASNs and relations
			 */
			pollToks = new StringTokenizer(pollString, "|");
			lhsASN = Integer.parseInt(pollToks.nextToken());
			rhsASN = Integer.parseInt(pollToks.nextToken());
			rel = Integer.parseInt(pollToks.nextToken());

			/*
			 * Check if the AS object was not created in the process of parsing
			 * the CIDR count file, if not, add the AS and give it a single
			 * network
			 */
			if (!retMap.containsKey(lhsASN)) {
				retMap.put(lhsASN, new AS(lhsASN, 1));
				noCIDRCount++;
			}
			if (!retMap.containsKey(rhsASN)) {
				retMap.put(rhsASN, new AS(rhsASN, 1));
				noCIDRCount++;
			}

			/*
			 * Actually add the relation, we only need to call this for one
			 * object and it handles symmetry enforcement
			 */
			retMap.get(lhsASN).addParsedRelation(retMap.get(rhsASN), rel);
		}
		fBuff.close();

		System.out.println("ASes without CIDR mapping: " + noCIDRCount);

		return retMap;
	}

	private HashMap<Integer, AS> doNetworkPrune() {
		HashMap<Integer, AS> prunedMap = new HashMap<Integer, AS>();

		int counter = 0;
		for (AS tAS : this.prunedTopo.values()) {
			if (tAS.getCustomers().size() > 0) {
				counter++;
				prunedMap.put(tAS.getASN(), new AS(tAS.getASN(), tAS.getCIDRSize()));
			}
		}
		System.out.println("counter size: " + counter);

		/*
		 * Build the relations for the deep copy objects
		 */
		for (AS newAS : prunedMap.values()) {
			AS oldAS = this.prunedTopo.get(newAS.getASN());

			/*
			 * Populate providers, this will all exist by default since they
			 * can't be pruned by definition
			 */
			for (Integer provASN : oldAS.getProviders()) {
				newAS.addProvider(prunedMap.get(provASN));
			}

			/*
			 * Do the same with peers and customers, but we do need to ensure
			 * here that they exist in the topology
			 */
			for (Integer peerASN : oldAS.getPeers()) {
				if (!prunedMap.containsKey(peerASN)) {
					continue;
				}
				newAS.addPeer(prunedMap.get(peerASN));
			}
			for (Integer custASN : oldAS.getCustomers()) {
				if (!prunedMap.containsKey(custASN)) {
					/*
					 * If the AS has been pruned, roll the CIDRs up to the parent
					 */
					newAS.setCIDRSize(newAS.getCIDRSize() + this.prunedTopo.get(custASN).getCIDRSize());
					continue;
				}
				newAS.addCustomer(prunedMap.get(custASN));
			}
		}

		return prunedMap;
	}
}
