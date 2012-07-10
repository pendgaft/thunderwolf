package router;

import java.util.*;

import threading.BGPMaster;
import events.MRAIFireEvent;
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

	private HashMap<Integer, HashMap<Integer, BGPRoute>> adjInRib;
	private HashMap<Integer, List<BGPRoute>> inRib;
	private HashMap<Integer, BGPRoute> outRib;
	private HashMap<Integer, Set<BGPSpeaker>> adjOutRib;
	private HashMap<Integer, BGPRoute> locRib;

	private HashMap<Integer, HashSet<Integer>> dirtyDests;

	private HashMap<Integer, Queue<BGPUpdate>> incUpdateQueues;
	private long lastUpdateTime;
	private long nextMRAI;

	private BGPMaster simMaster;

	private static boolean DEBUG = false;

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

		this.adjInRib = new HashMap<Integer, HashMap<Integer, BGPRoute>>();
		this.inRib = new HashMap<Integer, List<BGPRoute>>();
		this.outRib = new HashMap<Integer, BGPRoute>();
		this.adjOutRib = new HashMap<Integer, Set<BGPSpeaker>>();
		this.locRib = new HashMap<Integer, BGPRoute>();

		this.incUpdateQueues = new HashMap<Integer, Queue<BGPUpdate>>();
		this.dirtyDests = new HashMap<Integer, HashSet<Integer>>();

		/*
		 * Setup the queues, including the odd "internal" queue
		 */
		for (int tASN : this.myAS.getNeighbors()) {
			this.incUpdateQueues.put(tASN, new LinkedList<BGPUpdate>());
			this.dirtyDests.put(tASN, new HashSet<Integer>());
		}
		this.incUpdateQueues.put(this.getASN(), new LinkedList<BGPUpdate>());
		this.lastUpdateTime = 0;
		this.nextMRAI = 0;
	}

	/**
	 * Function used to pass a reference to the BGP Master (sim driver) to the
	 * AS object
	 * 
	 * @param mast
	 */
	public void registerBGPMaster(BGPMaster mast) {
		this.simMaster = mast;
	}

	/**
	 * Public interface to force the router to handle one message in it's update
	 * queue. This IS safe if the update queue is empty (the function) returns
	 * immediately. This handles the removal of routes, calculation of best
	 * paths, tolerates the loss of all routes, etc. It marks routes as dirty,
	 * but does not send advertisements, as that is handled at the time of MRAI
	 * expiration.
	 */
	private void handleAdvertisement(Queue<BGPUpdate> queueToRun) {
		BGPUpdate nextUpdate = queueToRun.poll();

		if (DEBUG) {
			System.out.println("handling " + this.getASN());
		}

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
			this.adjInRib.put(advPeer, new HashMap<Integer, BGPRoute>());
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
		HashMap<Integer, BGPRoute> advRibList = this.adjInRib.get(advPeer);
		if (advRibList.containsKey(dest)) {
			advRibList.remove(dest);
			routeRemoved = true;
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
		if ((!nextUpdate.isWithdrawal())
				&& (!nextUpdate.getAdvertisedRoute()
						.containsLoop(this.getASN()))) {
			advRibList.put(nextUpdate.getAdvertisedRoute().getDest(),
					nextUpdate.getAdvertisedRoute());
			destRibList.add(nextUpdate.getAdvertisedRoute());
		}

		recalcBestPath(dest);
	}

	/**
	 * Currently exposed interface which triggers an expiration of THIS ROUTER'S
	 * MRAI timer, resulting in updates being sent to this router's peers.
	 */
	//FIXME jesus christ new system go!
	public synchronized void mraiExpire(long currentTime) {

		synchronized (this.dirtyDests) {
			for (int tPeer : this.dirtyDests.keySet()) {
				for (int tDest : this.dirtyDests.get(tPeer)) {
					this.sendUpdate(tDest, tPeer, currentTime);
				}
			}
			this.dirtyDest.clear();
		}

		if (DEBUG) {
			System.out.println("MRAI fire at " + this.getASN());
		}

		// TODO configure this somehow in the future (mrai)
		this.simMaster
				.addMRAIFire(new MRAIFireEvent(currentTime + 30000, this));
		this.nextMRAI = currentTime + 30000;

		if (DEBUG) {
			System.out.println("MRAI fire RETURN at " + this.getASN());
		}
	}

	public void setOpeningMRAI(long time) {
		this.nextMRAI = time;
	}

	public synchronized long getNextMRAI() {
		return this.nextMRAI;
	}

	/**
	 * Public interface to be used by OTHER BGP Speakers to advertise a change
	 * in a route to a destination.
	 * 
	 * @param incRoute
	 *            - the route being advertised
	 */
	public boolean advPath(BGPRoute incRoute, long currentTime) {
		Queue<BGPUpdate> incQueue = this.incUpdateQueues.get(incRoute
				.getNextHop());
		incQueue.add(BGPUpdate.buildAdvertisement(incRoute, this
				.calcTotalRuntime(incRoute.getSize())));
		
		return incQueue.size() < 1000;
	}

	/**
	 * addedEvent Public interface to be used by OTHER BGPSpeakers to withdraw a
	 * route to this router.
	 * 
	 * @param peer
	 *            - the peer sending the withdrawl
	 * @param dest
	 *            - the destination of the route withdrawn
	 */
	public boolean withdrawPath(int withdrawingAS, int dest, long currentTime) {
		Queue<BGPUpdate> incQueue = this.incUpdateQueues.get(withdrawingAS);
		incQueue.add(BGPUpdate.buildWithdrawal(dest, withdrawingAS, this
				.calcTotalRuntime(this.adjInRib.get(withdrawingAS).get(dest)
						.getSize())));

		return incQueue.size() < 1000;
	}

	private long calcTotalRuntime(int size) {
		return (long) size * 10;
	}

	public void runForwardTo(long startTime, long stopTime) {

		/*
		 * This should never happen, make sure it does not
		 */
		if (startTime != this.lastUpdateTime) {
			throw new RuntimeException("Time gap in cpu calc!");
		}

		long currentTime = startTime;
		while (currentTime < stopTime) {
			int activeQueues = this.countActiveQueues();

			if (activeQueues == 0) {
				currentTime = stopTime;
				break;
			}

			long testTime = Math.min(this.computeNearestTTC(activeQueues),
					stopTime - currentTime);
			this.runQueuesAhead(testTime, activeQueues);
			currentTime += testTime;
		}

		this.lastUpdateTime = currentTime;
	}

	private int countActiveQueues() {
		int active = 0;
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (!tQueue.isEmpty()) {
				active++;
			}
		}

		return active;
	}

	private long computeNearestTTC(int numberRunning) {
		long smallestLeft = Long.MAX_VALUE;

		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (tQueue.isEmpty()) {
				continue;
			}

			smallestLeft = Math.min(tQueue.peek().estTimeToComplete(
					numberRunning), smallestLeft);
		}

		return smallestLeft;
	}

	private void runQueuesAhead(long timeDelta, int activeQueues) {
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (tQueue.isEmpty()) {
				continue;
			}

			if (tQueue.peek().runTimeAhead(timeDelta, activeQueues)) {
				this.handleAdvertisement(tQueue);
			}
		}
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
		changed = (currentInstall == null || !currentBest
				.equals(currentInstall));
		this.locRib.put(dest, currentBest);

		/*
		 * If we have a new path, mark that we have a dirty destination
		 */
		if (changed) {
			synchronized (this.dirtyDests) {
				if (currentBest == null) {
					this.outRib.remove(dest);
				} else {
					BGPRoute pathToAdv = currentBest.deepCopy();
					pathToAdv.appendASToPath(this.getASN());
					this.outRib.put(dest, pathToAdv);
				}
				for (int tPeer : this.dirtyDests.keySet()) {
					this.dirtyDests.get(tPeer).add(dest);
				}
			}
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
						|| (currentBest.getPathLength() == tPath
								.getPathLength() && tPath.getNextHop() < currentBest
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
	private boolean sendUpdate(int dest, int peer, long currentTime) {
		boolean prevAdvedTo = this.adjOutRib.get(dest).contains(peer);
		boolean newAdvTo = false;
		boolean okToAdvMore = true;
		BGPRoute pathToAdv = this.outRib.get(dest);

		if (pathToAdv != null) {
			if (this.myAS.getCustomers().contains(peer)
					|| dest == this.getASN()
					|| (this.myAS.getRel(pathToAdv.getNextHop()) == 1)) {
				okToAdvMore = this.peers.get(peer).advPath(pathToAdv,
						currentTime);
				newAdvTo = true;
			}
		}

		if (prevAdvedTo && !newAdvTo) {
			this.adjOutRib.get(dest).remove(peer);
			okToAdvMore = this.peers.get(peer).withdrawPath(this.getASN(),
					dest, currentTime);
		}

		this.dirtyDests.get(peer).remove(dest);

		return okToAdvMore;
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
	 * Predicate to test if this node is done processing BGP items.
	 * 
	 * @return - true if our incoming update queues are empty and we have no
	 *         dirty routes, false otherwise
	 */
	public boolean isDone() {
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (!tQueue.isEmpty()) {
				return false;
			}
		}

		for (HashSet<Integer> tSet : this.dirtyDests.values()) {
			if (!tSet.isEmpty()) {
				return false;
			}
		}

		return true;
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
	// TODO str builder?
	public String printBGPString(boolean detailed) {
		String outStr = this.toString();

		outStr += "\nLocal RIB is:";
		for (BGPRoute tRoute : this.locRib.values()) {
			outStr += "\n" + tRoute.toString();
		}

		if (detailed) {
			outStr += "\nIN RIB is:";
			for (int tDest : this.inRib.keySet()) {
				outStr += "\n  dest: " + tDest;
				for (BGPRoute tRoute : this.inRib.get(tDest)) {
					outStr += "\n" + tRoute.toString();
				}
			}
		}

		return outStr;
	}

	/**
	 * Fetches the ASN of this router.
	 * 
	 * @return - the ASN of the router
	 */
	public int getASN() {
		return this.myAS.getASN();
	}

	/**
	 * Computes the total memory load of this BGP speaker.
	 * 
	 * @return - the memory consumed by this BGP speaker's in RIB in bytes
	 */
	public long memLoad() {
		long memCount = 0;

		for (int tDest : this.inRib.keySet()) {
			for (BGPRoute tRoute : this.inRib.get(tDest)) {
				memCount += (tRoute.getPathLength() * 4 + 20)
						* tRoute.getSize();
			}
		}

		return memCount;
	}

	public int calcTotalRouteCount() {
		int routeCount = 0;

		for (int tDest : this.inRib.keySet()) {
			for (BGPRoute tRoute : this.inRib.get(tDest)) {
				routeCount += tRoute.getSize();
			}
		}

		return routeCount;
	}

	public int calcDistinctDestCount() {
		int routeCount = 0;

		for (BGPRoute tRoute : this.locRib.values()) {
			routeCount += tRoute.getSize();
		}

		return routeCount;
	}

	public AS getASObject() {
		return this.myAS;
	}
}
