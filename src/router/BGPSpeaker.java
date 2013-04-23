package router;

import java.util.*;

import router.data.RouterSendCapacity;
import events.SimEvent;
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

	private boolean isConfederation;
	private HashMap<Integer, HashSet<Integer>> routerBindings = null;
	private HashMap<Integer, Integer> asToRouterGroup = null;

	private static boolean DEBUG = false;
	private static final long MRAI_LENGTH = 30 * SimEvent.SECOND_MULTIPLIER;
	private static final long QUEUE_SIZE = 10000;
	private static final int MAX_ROUTER_SIZE = 8;

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

		/*
		 * Deal with confederations of routers if we need to
		 */
		this.isConfederation = (this.myAS.getNeighbors().size() <= BGPSpeaker.MAX_ROUTER_SIZE);
		if (this.isConfederation) {
			this.routerBindings = new HashMap<Integer, HashSet<Integer>>();
			this.asToRouterGroup = new HashMap<Integer, Integer>();
			int numberOfRouters = (int) Math.ceil((double) this.myAS.getNeighbors().size()
					/ (double) BGPSpeaker.MAX_ROUTER_SIZE);
			for (int counter = 0; counter < numberOfRouters; counter++) {
				this.routerBindings.put(counter, new HashSet<Integer>());
			}

			int pos = 0;
			for (int tASN : this.myAS.getNeighbors()) {
				this.routerBindings.get(pos).add(tASN);
				this.asToRouterGroup.put(tASN, pos);
				pos++;
				pos = pos % this.routerBindings.size();
			}
		}
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
			advPeer = nextUpdate.getAdvertisedRoute().getNextHop(this.getASN());
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
				if (destRibList.get(counter).getNextHop(this.getASN()) == advPeer) {
					destRibList.remove(counter);
					break;
				}
			}
		}

		/*
		 * If it is a loop don't add it to ribs
		 */
		if ((!nextUpdate.isWithdrawal()) && (!nextUpdate.getAdvertisedRoute().containsLoop(this.getASN()))) {
			advRibList.put(nextUpdate.getAdvertisedRoute().getDest(), nextUpdate.getAdvertisedRoute());
			destRibList.add(nextUpdate.getAdvertisedRoute());
		}

		recalcBestPath(dest);
	}

	public RouterSendCapacity computeSendCap(int sendingASN) {
		/*
		 * XXX so, here is the slight issue/abstraction with this. We don't
		 * actually take into account queues that will start or finish
		 * processing in this time window. Either could happen, so solutions for
		 * this... Well, we could get around the "start up issue" by only
		 * advertising till the next window in the neighborhood fires. (Holy
		 * shit prob murder our performace.) The other one we would have to
		 * compute if a queue finishes (pre-compute & save? compute twice?, and
		 * then if so _when_ it finishes, and adjust accordingly).
		 */

		/*
		 * Figure out exactly how much CPU time this queue is gettting, based on
		 * currently running queues.
		 */
		long fractionOfTimeMine = BGPSpeaker.MRAI_LENGTH;
		int runningQueues;
		if (this.isConfederation) {
			runningQueues = this.countActiveQueues(this.asToRouterGroup.get(sendingASN));
		} else {
			runningQueues = this.countActiveQueues(-1);
		}
		if (this.incUpdateQueues.get(sendingASN).isEmpty()) {
			runningQueues++;
		}
		fractionOfTimeMine = (long) Math.floor(fractionOfTimeMine / runningQueues);

		// TODO think on this when you're not on a plane
		/*
		 * Ohhhkay, shitty approximation time gogo, so we can compute (and waste
		 * a ton of CPU cycles doing it, exactly how long it will take to clear
		 * the buffer, subtract that, from the process time, then fill the
		 * buffer, etc, etc. Alternatively, we can play this game, we don't
		 * worry about the state now, and we let the buffer state be how it is,
		 * don't worry about it. And each time will fill the buffer as full as
		 * we can. This works out correctly in a steady state. Again, check
		 * this, and in the non-steady state case we lag behind by one MRAI. Not
		 * sure how much that will change things in the long run, but it is a
		 * quite larger perf improvement.
		 */
		long memSpace = 0;
		LinkedList<BGPUpdate> queueToListGo = (LinkedList<BGPUpdate>) this.incUpdateQueues.get(sendingASN);
		for (BGPUpdate tUpdate : queueToListGo) {
			memSpace += tUpdate.getWireSize();
		}
		memSpace = BGPSpeaker.QUEUE_SIZE - memSpace;

		// TODO determine if we're bouncing between a zero window and not
		return new RouterSendCapacity(fractionOfTimeMine, memSpace, false);
	}

	/**
	 * Currently exposed interface which triggers an expiration of THIS ROUTER'S
	 * MRAI timer, resulting in updates being sent to this router's peers.
	 */
	public synchronized void mraiExpire(long currentTime) {

		synchronized (this.dirtyDests) {
			for (int tPeer : this.dirtyDests.keySet()) {
				RouterSendCapacity sendCap = this.peers.get(tPeer).computeSendCap(this.myAS.getASN());
				HashSet<Integer> handled = new HashSet<Integer>();

				for (int tDest : this.dirtyDests.get(tPeer)) {
					handled.add(tDest);
					this.sendUpdate(tDest, tPeer, currentTime);

					if (sendCap.getCPUTime() == 0 && sendCap.getFreeBufferSpace() <= 0) {
						break;
					}
				}

				this.dirtyDests.get(tPeer).removeAll(handled);
			}
		}

		if (DEBUG) {
			System.out.println("MRAI fire at " + this.getASN() + " time " + currentTime);
		}

		/*
		 * Update the MRAI time
		 */
		this.nextMRAI = currentTime + BGPSpeaker.MRAI_LENGTH;
	}

	public void setOpeningMRAI(long time) {
		this.nextMRAI = time;
	}

	public long getNextMRAI() {
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
		Queue<BGPUpdate> incQueue = this.incUpdateQueues.get(incRoute.getNextHop(this.getASN()));
		incQueue.add(BGPUpdate.buildAdvertisement(incRoute, this.calcTotalRuntime(incRoute.getSize())));

		// TODO base this off of route size
		return incQueue.size() < BGPSpeaker.QUEUE_SIZE;
	}

	/**
	 * Public interface to be used by THIS BGP Speaker to advertise a route to
	 * itself. This would be from a route handed over internally from a
	 * configuration or internal. This can also be used by an injector to handle
	 * faking AS paths.
	 * 
	 * @param incRoute
	 *            - the route being advertised
	 */
	public boolean selfInstallPath(BGPRoute incRoute, long currentTime) {
		Queue<BGPUpdate> incQueue = this.incUpdateQueues.get(this.myAS.getASN());
		incQueue.add(BGPUpdate.buildAdvertisement(incRoute, this.calcTotalRuntime(incRoute.getSize())));

		return true;
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
		incQueue.add(BGPUpdate.buildWithdrawal(dest, withdrawingAS,
				this.calcTotalRuntime(this.adjInRib.get(withdrawingAS).get(dest).getSize())));

		return incQueue.size() < BGPSpeaker.QUEUE_SIZE;
	}

	private long calcTotalRuntime(int size) {
		return 2 * SimEvent.SECOND_MULTIPLIER / 1000;
	}

	public void runForwardTo(long startTime, long stopTime) {
		this.internalRunForwardTo(startTime, stopTime, -1);
		this.lastUpdateTime = stopTime;
	}

	private void internalRunForwardTo(long startTime, long stopTime, int routerGroup) {

		/*
		 * This should never happen, make sure it does not
		 */
		if (startTime != this.lastUpdateTime) {
			throw new RuntimeException("Time gap in cpu calc!\nASN: " + this.getASN() + " start time: " + startTime
					+ " last update: " + this.lastUpdateTime);
		}

		long currentTime = startTime;
		while (currentTime < stopTime) {
			int activeQueues = this.countActiveQueues(routerGroup);

			/*
			 * Just break out of the loop if we're done
			 */
			if (activeQueues == 0) {
				currentTime = stopTime;
				break;
			}

			/*
			 * Run ahead until we either hit the time horizon or a queue
			 * finishes
			 */
			long testTime = Math.min(this.computeNearestTTC(activeQueues, routerGroup), stopTime - currentTime);
			this.runQueuesAhead(testTime, activeQueues, routerGroup);
			currentTime += testTime;
		}
	}

	private int countActiveQueues(int routerGroup) {
		int active = 0;
		Set<Integer> peers = null;
		if (routerGroup == -1) {
			peers = this.incUpdateQueues.keySet();
		} else {
			peers = this.routerBindings.get(routerGroup);
		}

		for (int tASN : peers) {
			if (!this.incUpdateQueues.get(tASN).isEmpty()) {
				active++;
			}
		}

		return active;
	}

	private long computeNearestTTC(int numberRunning, int routerGroup) {
		long smallestLeft = Long.MAX_VALUE;

		/*
		 * We can have an overhead penalty if we're advertising at the same time
		 */
		double overhead = 1.0;
		if (!this.dirtyDests.isEmpty()) {
			overhead = 1.5;
		}

		Set<Integer> peers = null;
		if (routerGroup == -1) {
			peers = this.incUpdateQueues.keySet();
		} else {
			peers = this.routerBindings.get(routerGroup);
		}

		for (int tASN : peers) {
			Queue<BGPUpdate> tQueue = this.incUpdateQueues.get(tASN);
			if (tQueue.isEmpty()) {
				continue;
			}

			smallestLeft = Math.min(tQueue.peek().estTimeToComplete(numberRunning, overhead), smallestLeft);
		}

		return smallestLeft;
	}

	private void runQueuesAhead(long timeDelta, int activeQueues, int routerGroup) {
		Set<Integer> peers = null;
		if (routerGroup == -1) {
			peers = this.incUpdateQueues.keySet();
		} else {
			peers = this.routerBindings.get(routerGroup);
		}

		for (int tASN : peers) {
			Queue<BGPUpdate> tQueue = this.incUpdateQueues.get(tASN);
			/*
			 * Don't run empty queues obvi
			 */
			if (tQueue.isEmpty()) {
				continue;
			}

			/*
			 * Advance non-empty queues, if they finish handle the advertise at
			 * the head
			 */
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
		changed = (currentInstall == null || !currentBest.equals(currentInstall));
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
				currentRel = this.myAS.getRel(currentBest.getNextHop(this.getASN()));
				continue;
			}

			int newRel = this.myAS.getRel(tPath.getNextHop(this.getASN()));
			if (newRel > currentRel) {
				currentBest = tPath;
				currentRel = newRel;
				continue;
			}

			if (newRel == currentRel) {
				if (currentBest.getPathLength() > tPath.getPathLength()
						|| (currentBest.getPathLength() == tPath.getPathLength() && tPath.getNextHop(this.getASN()) < currentBest
								.getNextHop(this.getASN()))) {
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
		if (this.adjOutRib.get(dest) == null) {
			this.adjOutRib.put(dest, new HashSet<BGPSpeaker>());
		}

		boolean prevAdvedTo = this.adjOutRib.get(dest).contains(peer);
		boolean newAdvTo = false;
		boolean okToAdvMore = true;
		BGPRoute pathToAdv = this.outRib.get(dest);

		if (pathToAdv != null) {
			int nextHop = this.locRib.get(dest).getNextHop(this.getASN());

			if (this.myAS.getCustomers().contains(peer) || dest == this.getASN() || (this.myAS.getRel(nextHop) == 1)) {
				okToAdvMore = this.peers.get(peer).advPath(pathToAdv, currentTime);
				newAdvTo = true;

				if (DEBUG) {
					System.out.println("adving: " + dest + " to " + peer);
				}
			}
		}

		if (prevAdvedTo && !newAdvTo) {
			this.adjOutRib.get(dest).remove(peer);
			okToAdvMore = this.peers.get(peer).withdrawPath(this.getASN(), dest, currentTime);
		}

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

	public long getWorkRemaining() {
		long updatesPending = 0;

		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			updatesPending += tQueue.size();
		}

		return updatesPending;
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
				memCount += (tRoute.getPathLength() * 15) + (405 * tRoute.getSize());
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
