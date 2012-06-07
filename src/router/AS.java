package router;

import java.util.HashSet;
import java.util.Set;

import bgp.BGPException;

/**
 * Class that stores topology information.
 * 
 * @author pendgaft
 * 
 */
public class AS {

	private int asn;
	private Set<Integer> customers;
	private Set<Integer> peers;
	private Set<Integer> providers;
	private int numberOfCIDRs;

	/**
	 * constant for provider relationship (i.e. the other AS is a provider)
	 */
	public static final int PROIVDER_CODE = -1;
	
	/**
	 * constant for peer relationship
	 */
	public static final int PEER_CODE = 0;
	
	/**
	 * constant for customer relationship (i.e. the other AS is a customer)
	 */
	public static final int CUSTOMER_CODE = 1;

	public AS(int asn, int cidrCount) {
		this.asn = asn;
		this.numberOfCIDRs = cidrCount;
		this.customers = new HashSet<Integer>();
		this.peers = new HashSet<Integer>();
		this.providers = new HashSet<Integer>();
	}

	/**
	 * Method that adds a relationship between two ASes. This function ensures
	 * symm and is safe to accidently be called twice.
	 * 
	 * @param otherAS
	 *            - the AS this AS has a relationship with
	 * @param myRelationToThem
	 *            -
	 */
	public void addRelation(AS otherAS, int myRelationToThem) {
		if (myRelationToThem == AS.PROIVDER_CODE) {
			this.customers.add(otherAS.getASN());
			otherAS.providers.add(this.getASN());
		} else if (myRelationToThem == AS.PEER_CODE) {
			this.peers.add(otherAS.getASN());
			otherAS.peers.add(this.getASN());
		} else if (myRelationToThem == AS.CUSTOMER_CODE) {
			this.providers.add(otherAS.getASN());
			otherAS.customers.add(this.getASN());
		} else if (myRelationToThem == 3) {
			// ignore - comes from sib relationship in the file
		} else {
			throw new BGPException("Bad relation passed to add relation: " + myRelationToThem);
		}
	}

	/**
	 * Getter for the ASNs of customers
	 * 
	 * @return - the set of ASNs for customers
	 */
	public Set<Integer> getCustomers() {
		return customers;
	}

	/**
	 * Getter for the ASNs of peers
	 * 
	 * @return - the set of ASNs for peers
	 */
	public Set<Integer> getPeers() {
		return peers;
	}

	/**
	 * Getter for the ASNs of provider
	 * 
	 * @return - the set of ASN
	 */
	public Set<Integer> getProviders() {
		return providers;
	}

	/**
	 * Getter for the ASN of the AS
	 * 
	 * @return - the ASN
	 */
	public int getASN() {
		return this.asn;
	}
	
	/**
	 * Getter for the number of CIDRs this AS has.
	 * 
	 * @return - the number of CIDRs this AS originates
	 */
	public int getCIDRSize(){
		return this.numberOfCIDRs;
	}

	/**
	 * Method that returns the relationship between two ASes.
	 * 
	 * @param asn
	 *            - the ASN of the other AS
	 * @return - relationship code that matches what the other AS is in relation
	 *         to this AS
	 */
	public int getRel(int asn) {

		if (this.providers.contains(asn)) {
			return AS.PROIVDER_CODE;
		}
		if (this.peers.contains(asn)) {
			return AS.PEER_CODE;
		}
		if (this.customers.contains(asn)) {
			return AS.CUSTOMER_CODE;
		}

		if (asn == this.asn) {
			return 2;
		}

		throw new BGPException("Asked for relation on non-adj/non-self asn!  Me: " + this.asn + " Them: " + asn);
	}
	
	/**
	 * compact toString, spits out asn, for more detail go to printDebugString()
	 */
	public String toString(){
		return "ASN " + this.asn;
	}
	
	/**
	 * Dumps out full relationship data
	 * 
	 * @return - asn, size, and all relationships as a string
	 */
	public String printDebugString(){
		String outStr = "AS: " + this.asn + " (size : " + this.numberOfCIDRs + ")\n";
		outStr += "Customers: " + this.customers + "\n";
		outStr += "Providers: " + this.providers + "\n";
		outStr += "Peers: " + this.peers + "\n";
		
		return outStr;
	}
}
