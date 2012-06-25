package router;

import java.util.*;

import threading.BGPMaster;
import events.ProcessEvent;
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
	private HashMap<Integer, Set<BGPSpeaker>> adjOutRib;
	private HashMap<Integer, BGPRoute> locRib;

	private HashSet<Integer> dirtyDest;

	private HashMap<Integer, Queue<BGPUpdate>> incUpdateQueues;
	private long lastUpdateTime;
	private ProcessEvent currentProcessEvent;

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
		this.adjOutRib = new HashMap<Integer, Set<BGPSpeaker>>();
		this.locRib = new HashMap<Integer, BGPRoute>();

		this.incUpdateQueues = new HashMap<Integer, Queue<BGPUpdate>>();

		/*
		 * Setup the queues, including the odd "internal" queue
		 */
		for (int tASN : this.myAS.getNeighbors()) {
			this.incUpdateQueues.put(tASN, new LinkedList<BGPUpdate>());
		}
		this.incUpdateQueues.put(this.getASN(), new LinkedList<BGPUpdate>());
		this.lastUpdateTime = 0;
		this.currentProcessEvent = null;

		this.dirtyDest = new HashSet<Integer>();
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
	public void mraiExpire(long currentTime) {

		synchronized (this.dirtyDest) {
			for (int tDest : this.dirtyDest) {
				this.sendUpdate(tDest, currentTime);
			}
			this.dirtyDest.clear();
		}

		if (DEBUG) {
			System.out.println("MRAI fire at " + this.getASN());
		}

		// TODO configure this somehow in the future (mrai)
		this.simMaster.addWork(new MRAIFireEvent(currentTime + 30000, this));
	}

	/**
	 * Public interface to be used by OTHER BGP Speakers to advertise a change
	 * in a route to a destination.
	 * 
	 * @param incRoute
	 *            - the route being advertised
	 */
	public synchronized void advPath(BGPRoute incRoute, long currentTime) {
		boolean spinningUpQueue = false;

		if (this.incUpdateQueues.get(incRoute.getNextHop()).isEmpty()) {
			this.updateRuntimes(currentTime);
			spinningUpQueue = true;
		}
		this.incUpdateQueues.get(incRoute.getNextHop()).add(
				BGPUpdate.buildAdvertisement(incRoute, this
						.calcTotalRuntime(incRoute.getSize())));

		/*
		 * So if we're spinning up a queue (adding work to an empty queue), then
		 * we'll need to adjust our scheduled time
		 */
		if (spinningUpQueue) {
			this.reschedule();
		}
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
	public synchronized void withdrawPath(int withdrawingAS, int dest,
			long currentTime) {
		boolean spinningUpQueue = false;

		if (this.incUpdateQueues.get(withdrawingAS).isEmpty()) {
			this.updateRuntimes(currentTime);
			spinningUpQueue = true;
		}
		this.incUpdateQueues.get(withdrawingAS).add(
				BGPUpdate.buildWithdrawal(dest, withdrawingAS, this
						.calcTotalRuntime(this.adjInRib.get(withdrawingAS).get(
								dest).getSize())));

		/*
		 * So if we're spinning up a queue (adding work to an empty queue), then
		 * we'll need to adjust our scheduled time
		 */
		if (spinningUpQueue) {
			this.reschedule();
		}
	}

	/**
	 * Public interface used by the simulator to tell the bgp speaker that one
	 * of it's queues should be done.
	 * 
	 * @param currentTime
	 */
	public synchronized void fireProcessTimer(long currentTime) {
		this.updateRuntimes(currentTime);
		this.reschedule();
	}

	private long calcTotalRuntime(int size) {
		return (long) size * 10;
	}

	/**
	 * Internal function that handles the book-keeping with the BGP processing
	 * queues.
	 * 
	 * @param currentTime
	 * @return
	 */
	private int updateRuntimes(long currentTime) {
		long timeDelta = currentTime - this.lastUpdateTime;
		this.lastUpdateTime = currentTime;
		int runningCount = 0;

		/*
		 * Count the number of currently running queues
		 */
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (!tQueue.isEmpty()) {
				runningCount++;
			}
		}

		/*
		 * Advance queues, actually do the processing if we have completed that
		 * update
		 */
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (tQueue.isEmpty()) {
				continue;
			}

			if (tQueue.peek().runTimeAhead(timeDelta, runningCount)) {
				this.handleAdvertisement(tQueue);
			}
		}

		return runningCount;
	}

	/**
	 * Internal function that handles the computation of the next queue to
	 * finish it's current event and re-schedules us with the simulator as
	 * needed
	 */
	private void reschedule() {
		int runningCount = 0;

		/*
		 * count the number of non-empty (running) queues
		 */
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (!tQueue.isEmpty()) {
				runningCount++;
			}
		}

		/*
		 * Short circuit to bail out if we've got no work
		 */
		if (runningCount == 0) {
			return;
		}

		/*
		 * Step through the non-empty queues looking for the one that is going
		 * to finish the quickest
		 */
		long nextTime = Long.MAX_VALUE;
		for (Queue<BGPUpdate> tQueue : this.incUpdateQueues.values()) {
			if (tQueue.isEmpty()) {
				continue;
			}

			nextTime = Math.min(nextTime, tQueue.peek().estTimeToComplete(
					runningCount));
		}

		nextTime += this.lastUpdateTime;

		/*
		 * If there isn't an event scheduled, push a new one in, if the time is
		 * a new one, then we're going to build a new one and swap out the old
		 * one in the sim queue
		 */
		if (this.currentProcessEvent == null) {
			this.currentProcessEvent = new ProcessEvent(nextTime, this);
			this.simMaster.addWork(this.currentProcessEvent);
		} else if (this.currentProcessEvent.getEventTime() != nextTime) {
			ProcessEvent newEvent = new ProcessEvent(nextTime, this);
			this.simMaster.swapWork(this.currentProcessEvent, newEvent);
			this.currentProcessEvent = newEvent;
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
			synchronized (this.dirtyDest) {
				this.dirtyDest.add(dest);
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
	private void sendUpdate(int dest, long currentTime) {
		Set<BGPSpeaker> prevAdvedTo = this.adjOutRib.get(dest);
		Set<BGPSpeaker> newAdvTo = new HashSet<BGPSpeaker>();
		BGPRoute pathOfMerit = this.locRib.get(dest);

		if (pathOfMerit != null) {
			BGPRoute pathToAdv = pathOfMerit.deepCopy();
			pathToAdv.appendASToPath(this.getASN());
			for (int tCust : this.myAS.getCustomers()) {
				this.peers.get(tCust).advPath(pathToAdv, currentTime);
				newAdvTo.add(this.peers.get(tCust));
			}
			if (pathOfMerit.getDest() == this.getASN()
					|| (this.myAS.getRel(pathOfMerit.getNextHop()) == 1)) {
				for (int tPeer : this.myAS.getPeers()) {
					this.peers.get(tPeer).advPath(pathToAdv, currentTime);
					newAdvTo.add(this.peers.get(tPeer));
				}
				for (int tProv : this.myAS.getProviders()) {
					this.peers.get(tProv).advPath(pathToAdv, currentTime);
					newAdvTo.add(this.peers.get(tProv));
				}
			}
		}

		if (prevAdvedTo != null) {
			prevAdvedTo.removeAll(newAdvTo);
			for (BGPSpeaker tAS : prevAdvedTo) {
				tAS.withdrawPath(this.getASN(), dest, currentTime);
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

		return this.dirtyDest.isEmpty();
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
				memCount += (tRoute.getPathLength() * 4 + 20) * tRoute.getSize();
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
}
