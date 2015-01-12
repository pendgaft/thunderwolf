package router;

import java.util.*;

import events.*;
import bgp.BGPRoute;
import bgp.BGPUpdate;

/**
 * Class that deals with the actual BGP processing, along with update queue
 * mgmt, etc. This wraps around the AS class, which stores topology information.
 * 
 * @author pendgaft
 * 
 */
//FIXME thread saftey!
public class BGPSpeaker {

	private AS myAS;

	private HashMap<Integer, BGPSpeaker> peers;

	private HashMap<Integer, HashMap<Integer, BGPRoute>> adjInRib;
	private HashMap<Integer, List<BGPRoute>> inRib;
	private HashMap<Integer, BGPRoute> outRib;
	private HashMap<Integer, Set<BGPSpeaker>> adjOutRib;

	private HashMap<Integer, BGPRoute> locRib;
	private HashMap<Integer, BGPUpdate> locRibDependents;

	private HashMap<Integer, HashSet<Integer>> dirtyDests;

	private HashMap<Integer, LinkedList<BGPUpdate>> incUpdateQueues;
	private HashMap<Integer, LinkedList<BGPUpdate>> outgoingUpdateQueues;
	private double nextMRAI;
	private ProcessEvent nextProcessEvent;
	private int nextProcessQueue;

	private boolean isConfederation;
	private HashMap<Integer, HashSet<Integer>> routerBindings = null;
	private HashMap<Integer, Integer> asToRouterGroup = null;

	private static boolean DEBUG = false;
	private static final double MRAI_LENGTH = 30.0 * SimEvent.SECOND_MULTIPLIER;

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
	public BGPSpeaker(AS asObj, HashMap<Integer, BGPSpeaker> routerMap, double openingMRAI) {
		this.myAS = asObj;
		this.peers = routerMap;

		this.adjInRib = new HashMap<Integer, HashMap<Integer, BGPRoute>>();
		this.inRib = new HashMap<Integer, List<BGPRoute>>();
		this.outRib = new HashMap<Integer, BGPRoute>();
		this.adjOutRib = new HashMap<Integer, Set<BGPSpeaker>>();
		this.locRib = new HashMap<Integer, BGPRoute>();
		this.locRibDependents = new HashMap<Integer, BGPUpdate>();

		this.incUpdateQueues = new HashMap<Integer, LinkedList<BGPUpdate>>();
		this.outgoingUpdateQueues = new HashMap<Integer, LinkedList<BGPUpdate>>();
		this.dirtyDests = new HashMap<Integer, HashSet<Integer>>();

		/*
		 * Setup the queues, including the odd "internal" queue
		 */
		for (int tASN : this.myAS.getNeighbors()) {
			this.dirtyDests.put(tASN, new HashSet<Integer>());
		}
		this.incUpdateQueues.put(this.getASN(), new LinkedList<BGPUpdate>());
		this.nextMRAI = openingMRAI;
		this.nextProcessEvent = new ProcessEvent(Long.MAX_VALUE, this);
		this.nextProcessQueue = -1;

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

	public void setupSharedQueues() {
		for (int tASN : this.myAS.getNeighbors()) {
			LinkedList<BGPUpdate> myQueueToHim = new LinkedList<BGPUpdate>();
			this.outgoingUpdateQueues.put(tASN, myQueueToHim);
			this.peers.get(tASN).incUpdateQueues.put(this.myAS.getASN(), myQueueToHim);
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
	private void handleAdvertisement(BGPUpdate nextUpdate) {
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

		if (this.recalcBestPath(dest)) {
			this.locRibDependents.put(dest, nextUpdate);
		}
	}

	/**
	 * Currently exposed interface which triggers an expiration of THIS ROUTER'S
	 * MRAI timer, resulting in updates being sent to this router's peers.
	 */
	public synchronized void mraiExpire() {

		synchronized (this.dirtyDests) {
			for (int tPeer : this.dirtyDests.keySet()) {
				for (int tDest : this.dirtyDests.get(tPeer)) {
					this.sendUpdate(tDest, tPeer);
				}

				this.dirtyDests.get(tPeer).clear();
			}
		}

		if (DEBUG) {
			System.out.println("MRAI fire at " + this.getASN() + " time " + this.nextMRAI);
		}

		/*
		 * Update the MRAI time
		 */
		this.nextMRAI += BGPSpeaker.MRAI_LENGTH;
	}

	public MRAIFireEvent getNextMRAI() {
		return new MRAIFireEvent(this.nextMRAI, this);
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
	public boolean selfInstallPath(BGPRoute incRoute) {
		Queue<BGPUpdate> incQueue = this.incUpdateQueues.get(this.myAS.getASN());
		BGPUpdate selfUpdate = BGPUpdate.buildAdvertisement(incRoute);
		this.handleAdvertisement(selfUpdate);
		selfUpdate.fakeFinishedInternalUpdate();
		incQueue.add(selfUpdate);

		return true;
	}

	public void queueAdvance(double startTime, double endTime) {
		this.runQueuesAhead(endTime - startTime, -1);
	}

	public void handleIncomingQueueCleanup() {
		//TODO at some point we should actually re-visit router groups, now isn't the time though
		this.prepQueues(-1);
		this.setQueueSpeeds(-1);
	}

	public void updateEstimatedCompletionTimes() {
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (tQueue.isEmpty()) {
				continue;
			}

			if (tQueue.peek().isDependancyRoot()) {
				tQueue.peek().updateEstCompletion();
			}
		}
	}

	public ProcessEvent checkIfProcessingEventNeedsUpdating(double currentTime) {
		ProcessEvent evict = null;
		double timeDelta = this.nextProcessEvent.getEventTime() - currentTime;

		/*
		 * Update if our current next to process has slowed down
		 */
		if (this.nextProcessQueue != -1) {
			/*
			 * There is a non-zero chance we end up here with the
			 * "soonest event" not existing (odd edge case with theshholding I
			 * _think_), handle it instead of eatting a null pointer..
			 */
			if (this.incUpdateQueues.get(this.nextProcessQueue).isEmpty()) {
				evict = this.nextProcessEvent;
				timeDelta = Double.MAX_VALUE;
			} else if (timeDelta < this.incUpdateQueues.get(this.nextProcessQueue).peek().getEstimatedCompletionTime()) {
				evict = this.nextProcessEvent;
				//FIXME null pointer here...
				this.nextProcessEvent = new ProcessEvent(this.incUpdateQueues.get(this.nextProcessQueue).peek()
						.getEstimatedCompletionTime()
						+ currentTime, this);
			}
		}

		/*
		 * Find out if there is a sooner to complete queue
		 */
		for (int tASN : this.incUpdateQueues.keySet()) {
			Queue<BGPUpdate> tQueue = this.incUpdateQueues.get(tASN);

			if (tQueue.isEmpty()) {
				continue;
			}

			/*
			 * If this queue is actually sooner make a new event
			 */
			if (timeDelta > tQueue.peek().getEstimatedCompletionTime()) {
				if (evict == null) {
					evict = this.nextProcessEvent;
				}
				this.nextProcessEvent = new ProcessEvent(tQueue.peek().getEstimatedCompletionTime() + currentTime, this);
				this.nextProcessQueue = tASN;
			}
		}

		return evict;
	}

	//TODO these three functions can be killed at the end of testing
	public void printHeadOfQueues() {
		String logStr = "I am " + this.getASN();
		for (int tPeer : this.incUpdateQueues.keySet()) {
			if (!this.incUpdateQueues.get(tPeer).isEmpty()) {
				logStr += " " + tPeer + "," + this.incUpdateQueues.get(tPeer).peek().getEstimatedCompletionTime();
			}
		}
		System.out.println(logStr);
	}

	public int countDepRoots() {
		int number = 0;
		for (LinkedList<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			for (BGPUpdate tUpdate : tQueue) {
				if (tUpdate.isDependancyRoot()) {
					number++;
				}
			}
		}

		return number;

	}

	public int countRootAtHead() {
		int number = 0;
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (tQueue.isEmpty()) {
				continue;
			}
			if (tQueue.peek().isDependancyRoot()) {
				number++;
			}
		}
		return number;
	}

	public ProcessEvent getNextProcessEvent() {
		return this.nextProcessEvent;
	}

	/**
	 * Handles when we've finished a processing event, essentially it resets the
	 * processing event to the end of the world and then uses existing machinery
	 * to compute when the real next event is
	 */
	public void handleProcessingEventCompleted(double currentTime) {
		this.nextProcessEvent = new ProcessEvent(Long.MAX_VALUE, this);
		this.nextProcessQueue = -1;
		this.checkIfProcessingEventNeedsUpdating(currentTime);
	}

	private void prepQueues(int routerGroup) {
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

			BGPUpdate headOfQueue = tQueue.peek();

			/*
			 * Check if the head of queue is finished, if so murder it
			 */
			if (headOfQueue.finished()) {
				/*
				 * Orphan all of the children, as their dependancy is finished
				 */
				tQueue.peek().orphanChildren();

				/*
				 * If it's in the local rib dependancy, remove it, as it's
				 * finished now
				 */
				int dest = -1;
				if (headOfQueue.isWithdrawal()) {
					dest = headOfQueue.getWithdrawnDest();
				} else {
					dest = headOfQueue.getAdvertisedRoute().getDest();
				}

				if (this.locRibDependents.get(dest) != null && this.locRibDependents.get(dest).equals(headOfQueue)) {
					this.locRibDependents.remove(dest);
				}

				tQueue.poll();

				/*
				 * If the queue is now empty we can move on to the next queue
				 */
				if (tQueue.isEmpty()) {
					continue;
				}
			}

			headOfQueue = tQueue.peek();
			if (!headOfQueue.hasBeenProcessed()) {
				this.handleAdvertisement(tQueue.peek());
				tQueue.peek().markAsProcessed();
			}
		}
	}

	private void setQueueSpeeds(int routerGroup) {
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

			tQueue.peek().updateSendRate(this.computeSendRate(tASN));
		}
	}

	//TODO router groups?
	private double computeSendRate(int destPeerASN) {
		int myActiveCount = this.countActiveQueues(-1);
		int hisActiveCount = this.peers.get(destPeerASN).countActiveQueues(-1);

		//TODO use active values in future?
		return 0.5;
	}

	public int countActiveQueues(int routerGroup) {
		int active = 0;
		Set<Integer> peers = null;
		if (routerGroup == -1) {
			peers = this.incUpdateQueues.keySet();
		} else {
			peers = this.routerBindings.get(routerGroup);
		}

		for (int tASN : peers) {
			//TODO isEmpty vs finished vs something else?
			//FIXME thread safety issue in general with scanning queues and the queues themselves
			if (!this.incUpdateQueues.get(tASN).isEmpty()) {
				active++;
			}
		}

		return active;
	}

	private void runQueuesAhead(double timeDelta, int routerGroup) {
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
			 * If the queue is a dependancy root, run it ahead, that will handle
			 * all of our other queues
			 */
			if (tQueue.peek().isDependancyRoot()) {
				tQueue.peek().advanceUpdate(timeDelta);
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
	private boolean recalcBestPath(int dest) {
		boolean changed;

		List<BGPRoute> possList = this.inRib.get(dest);
		BGPRoute currentBest = this.pathSelection(possList);

		BGPRoute currentInstall = this.locRib.get(dest);
		changed = (currentInstall == null || !currentInstall.equals(currentBest));
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

		return changed;
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
		int currentRel = Integer.MAX_VALUE;
		for (BGPRoute tPath : possList) {
			if (currentBest == null) {
				currentBest = tPath;
				currentRel = this.myAS.getMyRelationshipTo(currentBest.getNextHop(this.getASN()));
				continue;
			}

			int newRel = this.myAS.getMyRelationshipTo(tPath.getNextHop(this.getASN()));
			if (newRel < currentRel) {
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
	private BGPRoute sendUpdate(int dest, int peer) {
		if (this.adjOutRib.get(dest) == null) {
			this.adjOutRib.put(dest, new HashSet<BGPSpeaker>());
		}

		boolean prevAdvedTo = this.adjOutRib.get(dest).contains(peer);
		boolean newAdvTo = false;
		BGPRoute pathToAdv = this.outRib.get(dest);

		if (pathToAdv != null) {
			int nextHop = this.locRib.get(dest).getNextHop(this.getASN());

			if (this.myAS.getCustomers().contains(peer) || dest == this.getASN()
					|| this.myAS.getCustomers().contains(nextHop)) {
				BGPUpdate outUpdate = BGPUpdate.buildAdvertisement(pathToAdv);
				outUpdate.setParent(this.locRibDependents.get(dest));
				this.outgoingUpdateQueues.get(peer).add(outUpdate);
				newAdvTo = true;

				if (DEBUG) {
					System.out.println("adving: " + dest + " to " + peer);
				}
			}
		}

		if (prevAdvedTo && !newAdvTo) {
			this.adjOutRib.get(dest).remove(peer);
			BGPUpdate outUpdate = BGPUpdate.buildWithdrawal(dest, this.getASN(), this.peers.get(dest).getASObject()
					.getCIDRSize());
			outUpdate.setParent(this.locRibDependents.get(dest));
			this.outgoingUpdateQueues.get(peer).add(outUpdate);
		}

		return pathToAdv;
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
	public String printASString() {
		return this.myAS.printDebugString();
	}

	/**
	 * Prints out debugging information on the state of the BGP daemon
	 * (verbose).
	 * 
	 * @return
	 */
	public String printBGPString(boolean detailed) {
		StringBuilder strFactory = new StringBuilder();
		strFactory.append(this.toString());

		strFactory.append("\nLocal RIB is:");
		for (BGPRoute tRoute : this.locRib.values()) {
			strFactory.append("\n");
			strFactory.append(tRoute.toString());
		}

		if (detailed) {
			strFactory.append("\nIN RIB is:");
			for (int tDest : this.inRib.keySet()) {
				strFactory.append("\n  dest: ");
				strFactory.append(tDest);
				for (BGPRoute tRoute : this.inRib.get(tDest)) {
					strFactory.append("\n");
					strFactory.append(tRoute.toString());
				}
			}
		}

		return strFactory.toString();
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
