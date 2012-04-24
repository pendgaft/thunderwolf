package topo;

import java.util.*;

/*
 * Notes to turn into docs
 * 
 * This deals with BGP side
 * and in the future the IP
 * 
 * The decoy side in handled by decoryAS, which
 * should nest this class
 */
public class AS {

	private int asn;
	private Set<AS> customers;
	private Set<AS> peers;
	private Set<AS> providers;

	private HashMap<Integer, List<BGPPath>> adjInRib;
	private HashMap<Integer, List<BGPPath>> inRib;
	private HashMap<Integer, Set<AS>> adjOutRib;
	private HashMap<Integer, BGPPath> locRib;
	private HashSet<Integer> dirtyDest;

	private HashMap<Integer, BGPUpdateQueue> incUpdates;
	private long furthestCPUReady;

	public static final int PROIVDER_CODE = -1;
	public static final int PEER_CODE = 0;
	public static final int CUSTOMER_CODE = 1;

	public AS(int myASN) {
		this.asn = myASN;
		this.customers = new HashSet<AS>();
		this.peers = new HashSet<AS>();
		this.providers = new HashSet<AS>();

		this.adjInRib = new HashMap<Integer, List<BGPPath>>();
		this.inRib = new HashMap<Integer, List<BGPPath>>();
		this.adjOutRib = new HashMap<Integer, Set<AS>>();
		this.locRib = new HashMap<Integer, BGPPath>();

		//TODO do we need to add an "administrative" (i.e. simulator) AS 0 queue?
		this.incUpdates = new HashMap<Integer, BGPUpdateQueue>();
		this.furthestCPUReady = -1;
		this.dirtyDest = new HashSet<Integer>();
	}

	public static HashSet<Integer> buildASNSet(HashSet<AS> asSet) {
		HashSet<Integer> outSet = new HashSet<Integer>();
		for (AS tAS : asSet) {
			outSet.add(tAS.getASN());
		}
		return outSet;
	}

	public void addRelation(AS otherAS, int myRelationToThem) {
		/*
		 * Generate update queues
		 */
		if (!this.incUpdates.containsKey(otherAS.getASN())) {
			this.incUpdates.put(otherAS.getASN(), new BGPUpdateQueue());
		}
		if (!otherAS.incUpdates.containsKey(this.getASN())) {
			otherAS.incUpdates.put(this.getASN(), new BGPUpdateQueue());
		}

		if (myRelationToThem == AS.PROIVDER_CODE) {
			this.customers.add(otherAS);
			otherAS.providers.add(this);
		} else if (myRelationToThem == AS.PEER_CODE) {
			this.peers.add(otherAS);
			otherAS.peers.add(this);
		} else if (myRelationToThem == AS.CUSTOMER_CODE) {
			this.providers.add(otherAS);
			otherAS.customers.add(this);
		} else if (myRelationToThem == 3) {
			// ignore
		} else {
			System.err.println("WTF bad relation: " + myRelationToThem);
			System.exit(-1);
		}
	}

	/**
	 * Remove all references to this as object from other AS objects
	 */
	public void purgeRelations() {
		for (AS tCust : this.customers) {
			tCust.providers.remove(this);
		}
		for (AS tProv : this.providers) {
			tProv.customers.remove(this);
		}
		for (AS tPeer : this.peers) {
			tPeer.peers.remove(this);
		}
	}

	/*
	 * ASSUMPTION: this code pops each time a SINGLE queue is ready to go
	 */
	//XXX we can get multiple "done" events for the same time (issue?  fix?)
	//XXX do above via a "schedule" (next scheduled?) var?
	public long tendQueues(long currentTime) {
		/*
		 * Step 1) advance queues, deal w/ finished items
		 */
		double cpuSplit = this.processorEvenSplit();
		for (BGPUpdateQueue tQueue : this.incUpdates.values()) {
			if (tQueue.advanceTime(currentTime, cpuSplit)) {
				this.handleAdvertisement(tQueue);
			}
		}
		
		/*
		 * Step 2) estimate next ttc
		 */
		cpuSplit = this.processorEvenSplit();
		long soonest = Long.MAX_VALUE;
		for(int tASN: this.incUpdates.keySet()){
			soonest = Math.min(soonest, this.incUpdates.get(tASN).getTTC(cpuSplit));
		}
		
		return soonest;
	}
	
	//TODO odd way to do this scheduling, but yeah
	public long getFurthestCPUReadyTime(){
		return this.furthestCPUReady;
	}
	public void updateCPUReadyTime(long newTime){
		this.furthestCPUReady = newTime;
	}
	
	/**
	 * Computes the fraction of the CPU each queue is getting assuming even split
	 */
	private double processorEvenSplit(){
		double running = 0.0;
		for(BGPUpdateQueue tQueue: this.incUpdates.values()){
			if(tQueue.isRunning()){
				running += 1.0;
			}
		}
		
		if(running != 0.0){
			return 1.0 / running;
		}
		
		return 1.0;
	}

	private void handleAdvertisement(BGPUpdateQueue poppedQueue) {
		BGPUpdate nextUpdate = poppedQueue.getPoppedUpdate();
		if (nextUpdate == null) {
			return;
		}

		/*
		 * Fetch some fields in the correct form
		 */
		int advPeer, dest;
		if (nextUpdate.isWithdrawl()) {
			advPeer = nextUpdate.getWithdrawer().asn;
			dest = nextUpdate.getWithdrawnDest();
		} else {
			advPeer = nextUpdate.getPath().getNextHop();
			dest = nextUpdate.getPath().getDest();
		}

		/*
		 * Setup some objects if this the first time seeing a peer/dest
		 */
		if (this.adjInRib.get(advPeer) == null) {
			this.adjInRib.put(advPeer, new ArrayList<BGPPath>());
		}
		if (this.inRib.get(dest) == null) {
			this.inRib.put(dest, new ArrayList<BGPPath>());
		}

		/*
		 * Hunt for an existing route in the adjInRib. If it's a withdrawl we
		 * want to remove it, and if it is an adv and a route already exists we
		 * then have an implicit withdrawl
		 */
		boolean routeRemoved = false;
		List<BGPPath> advRibList = this.adjInRib.get(advPeer);
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
		List<BGPPath> destRibList = this.inRib.get(dest);
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
		if ((!nextUpdate.isWithdrawl()) && (!nextUpdate.getPath().containsLoop(this.asn))) {
			advRibList.add(nextUpdate.getPath());
			destRibList.add(nextUpdate.getPath());
		}

		recalcBestPath(dest);
	}

	//TODO this needs to be CPU time liminted, but for now, go nuts (works but unrealistic)
	public void mraiExpire(long currentTime) {
		for (int tDest : this.dirtyDest) {
			this.sendUpdate(tDest, currentTime);
		}
		this.dirtyDest.clear();
	}

	//TODO this is a little inefficent, a saner refactoring of tend queues would remove duplicate comps
	public void spinUp(long currentTime) {
		/*
		 * Update queues to this point in time
		 */
		this.tendQueues(currentTime);
		
		/*
		 * "switch on" queues
		 */
		for(BGPUpdateQueue tQueue: this.incUpdates.values()){
			tQueue.switchOn(currentTime);
		}
		
		/*
		 * Compute new TTC, add said event to event queue
		 */
		long nextTTC = this.tendQueues(currentTime);
		//TODO launch next event
	}

	public void advPath(BGPPath incPath, long currentTime) {
		if (this.incUpdates.get(incPath.getNextHop()).addUpdate(new BGPUpdate(incPath))) {
			this.spinUp(currentTime);
		}
	}

	public void withdrawPath(AS peer, int dest, long currentTime) {
		if (this.incUpdates.get(peer.getASN()).addUpdate(new BGPUpdate(dest, peer))) {
			this.spinUp(currentTime);
		}
	}

	public boolean hasDirtyPrefixes() {
		return !this.dirtyDest.isEmpty();
	}

	/**
	 * Calculates the best path to a given destination
	 * 
	 * @param dest
	 */
	private void recalcBestPath(int dest) {
		boolean changed;

		List<BGPPath> possList = this.inRib.get(dest);
		BGPPath currentBest = this.pathSelection(possList);

		BGPPath currentInstall = this.locRib.get(dest);
		changed = (currentInstall == null || !currentBest.equals(currentInstall));
		this.locRib.put(dest, currentBest);

		/*
		 * If we have a new path, mark that we have a dirty destination
		 */
		if (changed) {
			this.dirtyDest.add(dest);
		}
	}

	/*
	 * Actual path selection, abreviated: relationship => path len => tie
	 * breaker
	 */
	private BGPPath pathSelection(List<BGPPath> possList) {
		BGPPath currentBest = null;
		int currentRel = -4;
		for (BGPPath tPath : possList) {
			if (currentBest == null) {
				currentBest = tPath;
				currentRel = this.getRel(currentBest.getNextHop());
				continue;
			}

			int newRel = this.getRel(tPath.getNextHop());
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

	private void sendUpdate(int dest, long currentTime) {
		Set<AS> prevAdvedTo = this.adjOutRib.get(dest);
		Set<AS> newAdvTo = new HashSet<AS>();
		BGPPath pathOfMerit = this.locRib.get(dest);

		if (pathOfMerit != null) {
			BGPPath pathToAdv = pathOfMerit.deepCopy();
			pathToAdv.appendASToPath(this.asn);
			for (AS tCust : this.customers) {
				tCust.advPath(pathToAdv, currentTime);
				newAdvTo.add(tCust);
			}
			if (pathOfMerit.getDest() == this.asn || (this.getRel(pathOfMerit.getNextHop()) == 1)) {
				for (AS tPeer : this.peers) {
					tPeer.advPath(pathToAdv, currentTime);
					newAdvTo.add(tPeer);
				}
				for (AS tProv : this.providers) {
					tProv.advPath(pathToAdv, currentTime);
					newAdvTo.add(tProv);
				}
			}
		}

		if (prevAdvedTo != null) {
			prevAdvedTo.removeAll(newAdvTo);
			for (AS tAS : prevAdvedTo) {
				tAS.withdrawPath(this, dest, currentTime);
			}
		}
	}

	private int getRel(int asn) {
		for (AS tAS : this.providers) {
			if (tAS.getASN() == asn) {
				return -1;
			}
		}
		for (AS tAS : this.peers) {
			if (tAS.getASN() == asn) {
				return 0;
			}
		}
		for (AS tAS : this.customers) {
			if (tAS.getASN() == asn) {
				return 1;
			}
		}

		if (asn == this.asn) {
			return 2;
		}

		System.err.println("asked for relation on non-adj/non-self asn, depending on sim "
				+ "this might be expected, if you're not, you should prob restart this sim...!");
		return 2;
	}

	public BGPPath getPath(int dest) {
		return this.locRib.get(dest);
	}

	public BGPPath getPathToPurged(List<Integer> hookASNs) {
		List<BGPPath> listPossPaths = new LinkedList<BGPPath>();
		for (Integer tHook : hookASNs) {
			listPossPaths.add(this.getPath(tHook));
		}
		return this.pathSelection(listPossPaths);
	}

	public List<BGPPath> getAllPathsTo(int dest) {
		if (!this.inRib.containsKey(dest)) {
			return new LinkedList<BGPPath>();
		}
		return this.inRib.get(dest);
	}

	public Set<AS> getCustomers() {
		return customers;
	}

	public Set<AS> getPeers() {
		return peers;
	}

	public Set<AS> getProviders() {
		return providers;
	}

	public int hashCode() {
		return this.asn;
	}

	public boolean equals(Object rhs) {
		AS rhsAS = (AS) rhs;
		return this.asn == rhsAS.asn;
	}

	public int getASN() {
		return this.asn;
	}

	public int getDegree() {
		return this.customers.size() + this.peers.size() + this.providers.size();
	}

	public int getCustomerCount() {
		return this.customers.size();
	}
}
