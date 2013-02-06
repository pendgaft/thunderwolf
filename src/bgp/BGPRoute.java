package bgp;

import java.util.*;

/**
 * Class that represents a BPG route in a RIB/Update message.
 * 
 * @author pendgaft
 * 
 */
//XXX does the dest need to be a "random value" so we can support multiple networks per host?
public class BGPRoute {

	/**
	 * The ASN of the network family this route reaches, in theory this would be
	 * a large number of CIDRs, for many different routes
	 */
	private int destASN;

	/**
	 * Abstract representation of the number of CIDRs that are hidden behind the
	 * "destASN" abstraction.
	 */
	private int size;

	/**
	 * The ASes that sit on the path the route uses. This CAN be an empty list
	 * at the AS that originates the route (it will be advertised out with
	 * itself as part of the path, but internally it will have an empty path)
	 */
	private LinkedList<Integer> path;

	/**
	 * Builds a new route object for the given destination
	 * 
	 * @param dest
	 *            - the asn of the destination network
	 * @param size
	 *            - the number of CIDRs found behind that ASN
	 */
	public BGPRoute(int dest, int size) {
		this.destASN = dest;
		this.size = size;
		this.path = new LinkedList<Integer>();
	}

	/**
	 * Creates a deep copy of the given BGP route.
	 * 
	 * @return - a copy of the BGP route with copies of all class vars
	 */
	public BGPRoute deepCopy() {
		BGPRoute newPath = new BGPRoute(this.destASN, this.size);
		for (int counter = 0; counter < this.path.size(); counter++) {
			newPath.path.addLast(this.path.get(counter));
		}
		return newPath;
	}

	/**
	 * Returns the path length in ASes
	 * 
	 * @return - length of the path
	 */
	public int getPathLength() {
		return this.path.size();
	}

	/**
	 * Appends the given ASN to the path, used to extend paths for
	 * advertisement.
	 * 
	 * @param frontASN
	 *            - the ASN to be added to the front of the path
	 */
	public void appendASToPath(int frontASN) {
		this.path.addFirst(frontASN);
	}

	/**
	 * Predicate that tests if the given ASN is found in the path.
	 * 
	 * @param testASN
	 *            - the ASN to check for looping to
	 * @return - true if the ASN appears in the path already, false otherwise
	 */
	public boolean containsLoop(int testASN) {
		for (int tASN : this.path) {
			if (tASN == testASN) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Predicate that tests if any of a set of ASNs are found in the path.
	 * 
	 * This was from Nightwing, not sure if it is still needed here.
	 * 
	 * param testASNs - the ASNs to check for existence in the path
	 * 
	 * @return - true if at least one of the ASNs found in testASNs is in the
	 *         path, false otherwise
	 */
	public boolean containsAnyOf(HashSet<Integer> testASNs) {
		for (int tHop : this.path) {
			if (testASNs.contains(tHop)) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Fetches the next hop used in the route.
	 * 
	 * @return - the next hop in the route, ourself if we're the originating AS
	 */
	public int getNextHop(int myASN) {
		/*
		 * hack for paths to ourself
		 */
		if (this.path.size() == 0) {
			return myASN;
		}

		return this.path.getFirst();
	}

	/**
	 * Fetches the destination network.
	 * 
	 * @return - the ASN of the AS that originated the route
	 */
	public int getDest() {
		return this.destASN;
	}

	/**
	 * Fetches the size the destination ASN is abstracting. In other words this
	 * fetches the actual number of routes this one route object represents.
	 * 
	 * @return - the number of CIDRs this route covers
	 */
	public int getSize() {
		return this.size;
	}

	/**
	 * Predicate to test if two routes are the same route. This tests that the
	 * destinations are identical and that the paths used are identical. All
	 * comparisons are done based off of ASN.
	 * 
	 * @param rhs
	 *            - the second route to test against
	 * @return - true if the routes have the same destination and path, false
	 *         otherwise
	 */
	public boolean equals(BGPRoute rhs) {
		/*
		 * Fast O(1) checks for dest and size
		 */
		if (rhs.path.size() != this.path.size() || rhs.destASN != this.destASN) {
			return false;
		}

		/*
		 * Slow O(n) check for path equality
		 */
		for (int counter = 0; counter < this.path.size(); counter++) {
			if (this.path.get(counter) != rhs.path.get(counter)) {
				return false;
			}
		}

		return true;
	}

	//XXX update with string builder to save on memory/get speedup (hash code uses this...)?
	public String toString() {
		String base = "dst: " + this.destASN + " path:";
		for (int tAS : this.path) {
			base = base + " " + tAS;
		}
		return base;
	}

	/**
	 * Hash code based on hash code of the print string
	 */
	public int hashCode() {
		return this.toString().hashCode();
	}
}
