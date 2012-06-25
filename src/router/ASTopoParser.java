package router;

import java.util.*;
import java.io.*;


/**
 * Class containing a static method for construction of the topology. This class
 * ASSUMES that the topology files are pre-pruned, and that any AS found in the
 * relationship file is also found in the cidr count file.
 * 
 * @author pendgaft
 * 
 */
public class ASTopoParser {

	private static final String AS_REL_FILE = "as-rel.txt";
	private static final String AS_IP_FILE = "ip-count.txt";

	/**
	 * Static function that does the actual building of BGPSpeaker objects. It
	 * of course builds the AS objects first, then creates the BGP Speakers
	 * 
	 * @return - an ASN to BGPSpeaker mapping
	 * @throws IOException
	 *             - if there is an error reading either the relationship or
	 *             cidr file
	 */
	public static HashMap<Integer, BGPSpeaker> doNetworkBuild() throws IOException {
		HashMap<Integer, AS> asMap = ASTopoParser.parseFile(ASTopoParser.AS_REL_FILE, ASTopoParser.AS_IP_FILE);

		/*
		 * Build the actual routers, pass a reference to the router map itself
		 */
		HashMap<Integer, BGPSpeaker> routerMap = new HashMap<Integer, BGPSpeaker>();
		for (AS tAS : asMap.values()) {
			routerMap.put(tAS.getASN(), new BGPSpeaker(tAS, routerMap));
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
	private static HashMap<Integer, AS> parseFile(String asRelFile, String cidrCountFile) throws IOException {

		HashMap<Integer, AS> retMap = new HashMap<Integer, AS>();
		int noCIDRCount = 0;

		String pollString;
		StringTokenizer pollToks;
		int lhsASN, rhsASN, rel;

		/*
		 * Parse the cidr count file and use it to create the AS objects
		 */
		BufferedReader fBuff = new BufferedReader(new FileReader(cidrCountFile));
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
			 * Actually add the relation, we only need to call this for one
			 * object and it handles symmetry enforcement
			 */
			if(!retMap.containsKey(lhsASN)){
				retMap.put(lhsASN, new AS(lhsASN, 1));
				noCIDRCount++;
			}
			if(!retMap.containsKey(rhsASN)){
				retMap.put(rhsASN, new AS(rhsASN, 1));
				noCIDRCount++;
			}
			retMap.get(lhsASN).addRelation(retMap.get(rhsASN), rel);
		}
		fBuff.close();

		System.out.println("ASes without CIDR mapping: " + noCIDRCount);
		
		return retMap;
	}
}
