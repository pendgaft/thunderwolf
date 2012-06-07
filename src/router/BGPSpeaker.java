package router;

import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

import bgp.BGPRoute;
import bgp.BGPUpdate;

/**
 * Class that deals with the actual BGP processing, along with update queue
 * mgmt, etc. This wraps around the AS class, which stores topology information.
 * 
 * @author pendgaft
 * 
 */
public class BGPSpeaker {

	private AS myAS;

	private HashMap<Integer, BGPSpeaker> peers;

	private HashMap<Integer, List<BGPRoute>> adjInRib;
	private HashMap<Integer, List<BGPRoute>> inRib;
	private HashMap<Integer, Set<BGPSpeaker>> adjOutRib;
	private HashMap<Integer, BGPRoute> locRib;
	private HashSet<Integer> dirtyDest;

	private Queue<BGPUpdate> incUpdateQueue;

	/**
	 * Constructor which sets up a BGP speaker.
	 * 
	 * @param asObj
	 *            - the AS object that stores topology information for this
	 *            speaker
	 * @param routerMap
	 *            - the global ASN to AS object mapping
	 */
	public BGPSpeaker(AS asObj, HashMap<Integer, BGPSpeaker> routerMap) {
		this.myAS = asObj;
		this.peers = routerMap;

		this.adjInRib = new HashMap<Integer, List<BGPRoute>>();
		this.inRib = new HashMap<Integer, List<BGPRoute>>();
		this.adjOutRib = new HashMap<Integer, Set<BGPSpeaker>>();
		this.locRib = new HashMap<Integer, BGPRoute>();

		this.incUpdateQueue = new LinkedBlockingQueue<BGPUpdate>();
		this.dirtyDest = new HashSet<Integer>();
	}

	/**
	 * Public interface to force the router to handle one message in it's update
	 * queue. This IS safe if the update queue is empty (the function) returns
	 * immediately. This handles the removal of routes, calculation of best
	 * paths, tolerates the loss of all routes, etc. It marks routes as dirty,
	 * but does not send advertisements, as that is handled at the time of MRAI
	 * expiration.
	 */
	public void handleAdvertisement() {
		BGPUpdate nextUpdate = this.incUpdateQueue.poll();
		/*
		 * No work? Go to sleep.
		 */
		if (nextUpdate == null) {
			return;
		}

		/*
		 * Fetch some fields in the correct form
		 */
		int advPeer, dest;
		if (nextUpdate.isWithdrawal()) {
			advPeer = nextUpdate.getWithdrawer();
			dest = nextUpdate.getWithdrawnDest();
		} else {
			advPeer = nextUpdate.getAdvertisedRoute().getNextHop();
			dest = nextUpdate.getAdvertisedRoute().getDest();
		}

		/*
		 * Setup some objects if this the first time seeing a peer/dest
		 */
		if (this.adjInRib.get(advPeer) == null) {
			this.adjInRib.put(advPeer, new ArrayList<BGPRoute>());
		}
		if (this.inRib.get(dest) == null) {
			this.inRib.put(dest, new ArrayList<BGPRoute>());
		}

		/*
		 * Hunt for an existing route in the adjInRib. If it's a withdrawal we
		 * want to remove it, and if it is an adv and a route already exists we
		 * then have an implicit withdrawal
		 */
		boolean routeRemoved = false;
		List<BGPRoute> advRibList = this.adjInRib.get(advPeer);
		for (int counter = 0; counter < advRibList.size(); counter++) {
			if (advRibList.get(counter).getDest() == dest) {
				advRibList.remove(counter);
				routeRemoved = true;
				break;
			}
		}

		/*
		 * If there was a rotue to remove from the adjInRib, clean up the inRib
		 * as well
		 */
		List<BGPRoute> destRibList = this.inRib.get(dest);
		if (routeRemoved) {
			for (int counter = 0; counter < destRibList.size(); counter++) {
				if (destRibList.get(counter).getNextHop() == advPeer) {
					destRibList.remove(counter);
					break;
				}
			}
		}

		/*
		 * If it is a loop don't add it to ribs
		 */
		if ((!nextUpdate.isWithdrawal()) && (!nextUpdate.getAdvertisedRoute().containsLoop(this.getASN()))) {
			advRibList.add(nextUpdate.getAdvertisedRoute());
			destRibList.add(nextUpdate.getAdvertisedRoute());
		}

		recalcBestPath(dest);
	}

	/**
	 * Currently exposed interface which triggers an expiration of THIS ROUTER'S
	 * MRAI timer, resulting in updates being sent to this router's peers.
	 */
	public void mraiExpire() {
		for (int tDest : this.dirtyDest) {
			this.sendUpdate(tDest);
		}
		this.dirtyDest.clear();
	}

	/**
	 * Public interface to be used by OTHER BGP Speakers to advertise a change
	 * in a route to a destination.
	 * 
	 * @param incRoute
	 *            - the route being advertised
	 */
	public void advPath(BGPRoute incRoute) {
		this.incUpdateQueue.add(BGPUpdate.buildAdvertisement(incRoute));
	}

	/**
	 * Public interface to be used by OTHER BGPSpeakers to withdraw a route to
	 * this router.
	 * 
	 * @param peer
	 *            - the peer sending the withdrawl
	 * @param dest
	 *            - the destination of the route withdrawn
	 */
	public void withdrawPath(BGPSpeaker peer, int dest) {
		this.incUpdateQueue.add(BGPUpdate.buildWithdrawal(dest, peer.getASN()));
	}

	/**
	 * Predicate to test if the incoming work queue is empty or not, used to
	 * accelerate the simulation.
	 * 
	 * @return true if items are in the incoming work queue, false otherwise
	 */
	public boolean hasWorkToDo() {
		return !this.incUpdateQueue.isEmpty();
	}

	/**
	 * Predicate to test if this speaker needs to send advertisements when the
	 * MRAI fires.
	 * 
	 * @return - true if there are advertisements that need to be send, false
	 *         otherwise
	 */
	public boolean hasDirtyPrefixes() {
		return !this.dirtyDest.isEmpty();
	}

	/**
	 * Fetches the number of bgp updates that have yet to be processed.
	 * 
	 * @return the number of pending BGP messages
	 */
	public int getPendingMessageCount() {
		return this.incUpdateQueue.size();
	}

	/**
	 * Function that forces the router to recalculate what our current valid and
	 * best path is. This should be called when a route for the given
	 * destination has changed in any way.
	 * 
	 * @param dest
	 *            - the destination network that has had a route change
	 */
	private void recalcBestPath(int dest) {
		boolean changed;

		List<BGPRoute> possList = this.inRib.get(dest);
		BGPRoute currentBest = this.pathSelection(possList);

		BGPRoute currentInstall = this.locRib.get(dest);
		changed = (currentInstall == null || !currentBest.equals(currentInstall));
		this.locRib.put(dest, currentBest);

		/*
		 * If we have a new path, mark that we have a dirty destination
		 */
		if (changed) {
			this.dirtyDest.add(dest);
		}
	}

	/**
	 * Method that handles actual BGP path selection. Slightly abbreviated, does
	 * AS relation, path length, then tie break.
	 * 
	 * @param possList
	 *            - the possible valid routes
	 * @return - the "best" of the valid routes by usual BGP metrics
	 */
	private BGPRoute pathSelection(List<BGPRoute> possList) {
		BGPRoute currentBest = null;
		int currentRel = -4;
		for (BGPRoute tPath : possList) {
			if (currentBest == null) {
				currentBest = tPath;
				currentRel = this.myAS.getRel(currentBest.getNextHop());
				continue;
			}

			int newRel = this.myAS.getRel(tPath.getNextHop());
			if (newRel > currentRel) {
				currentBest = tPath;
				currentRel = newRel;
				continue;
			}

			if (newRel == currentRel) {
				if (currentBest.getPathLength() > tPath.getPathLength()
						|| (currentBest.getPathLength() == tPath.getPathLength() && tPath.getNextHop() < currentBest
								.getNextHop())) {
					currentBest = tPath;
					currentRel = newRel;
				}
			}
		}

		return currentBest;
	}

	/**
	 * Internal function to deal with the sending of advertisements or explicit
	 * withdrawals of routes. Does valley free routing.
	 * 
	 * @param dest
	 *            - the destination of the route we need to advertise a change
	 *            in
	 */
	private void sendUpdate(int dest) {
		Set<BGPSpeaker> prevAdvedTo = this.adjOutRib.get(dest);
		Set<BGPSpeaker> newAdvTo = new HashSet<BGPSpeaker>();
		BGPRoute pathOfMerit = this.locRib.get(dest);

		if (pathOfMerit != null) {
			BGPRoute pathToAdv = pathOfMerit.deepCopy();
			pathToAdv.appendASToPath(this.getASN());
			for (int tCust : this.myAS.getCustomers()) {
				this.peers.get(tCust).advPath(pathToAdv);
				newAdvTo.add(this.peers.get(tCust));
			}
			if (pathOfMerit.getDest() == this.getASN() || (this.myAS.getRel(pathOfMerit.getNextHop()) == 1)) {
				for (int tPeer : this.myAS.getPeers()) {
					this.peers.get(tPeer).advPath(pathToAdv);
					newAdvTo.add(this.peers.get(tPeer));
				}
				for (int tProv : this.myAS.getProviders()) {
					this.peers.get(tProv).advPath(pathToAdv);
					newAdvTo.add(this.peers.get(tProv));
				}
			}
		}

		if (prevAdvedTo != null) {
			prevAdvedTo.removeAll(newAdvTo);
			for (BGPSpeaker tAS : prevAdvedTo) {
				tAS.withdrawPath(this, dest);
			}
		}
	}

	/**
	 * Fetches the currently installed best path for a given destination.
	 * 
	 * @param dest
	 *            - the destination "network" (AS) in question
	 * @return - the currently installed path for the network, or null if one is
	 *         not installed
	 */
	public BGPRoute getPath(int dest) {
		return this.locRib.get(dest);
	}

	/**
	 * Fetches all currently valid paths for a given destination.
	 * 
	 * @param dest
	 *            - the destination "network" (AS) in question
	 * @return - a list of all valid paths the router has to that network, an
	 *         empty list if none are known.
	 */
	public List<BGPRoute> getAllPathsTo(int dest) {
		if (!this.inRib.containsKey(dest)) {
			return new LinkedList<BGPRoute>();
		}
		return this.inRib.get(dest);
	}

	/**
	 * Hash code is simply the ASN (yay unique)
	 */
	public int hashCode() {
		return this.getASN();
	}

	/**
	 * Tests for equality based ONLY on the ASNs of the two routers
	 */
	public boolean equals(Object rhs) {
		BGPSpeaker rhsAS = (BGPSpeaker) rhs;
		return this.getASN() == rhsAS.getASN();
	}

	/**
	 * Simple toString that just calls through to the AS object's toString, to
	 * dump BGP details, call the printBGPString() method
	 */
	public String toString() {
		return this.myAS.toString();
	}

	/**
	 * Prints a debug string about the AS object. In the future this might give
	 * us some limited BGP information.
	 * 
	 * @return - a detailed debug string from the AS object
	 */
	public String printDebugString() {
		return this.myAS.printDebugString();
	}

	/**
	 * Prints out debugging information on the state of the BGP daemon
	 * (verbose).
	 * 
	 * @return
	 */
	//TODO implement this
	public String printBGPString() {
		return null;
	}

	/**
	 * Fetches the ASN of this router.
	 * 
	 * @return - the ASN of the router
	 */
	public int getASN() {
		return this.myAS.getASN();
	}
}
