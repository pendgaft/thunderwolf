package threading;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import networkConfig.NetworkSeeder;
import events.*;
import router.BGPSpeaker;
import util.Stats;

public class BGPMaster implements Runnable {

	private int workOut;
	private HashMap<Integer, Semaphore> workSem;
	private Semaphore completeSem;

	private HashMap<Integer, ConcurrentLinkedQueue<SimEvent>> readyToRunQueue;

	private HashMap<Integer, BGPSpeaker> topo;
	private HashMap<Integer, Integer> asnToSlave;

	private Queue<MRAIFireEvent> mraiQueue;
	private HashMap<Integer, Long> asnRunTo;

	private BufferedWriter ribOut;
	private BufferedWriter memOut;
	private BufferedWriter statOut;
	private List<Integer> asnIterList;

	private static final int NUM_THREADS = 10;

	private static final String LOG = "logs/";

	public static void driveSim(HashMap<Integer, BGPSpeaker> routingTopo, NetworkSeeder netSeed)
			throws IOException {
		Random rng = new Random();

		/*
		 * build the master and slaves
		 */
		BGPMaster self = new BGPMaster(routingTopo);
		List<Thread> slaveThreads = new LinkedList<Thread>();
		for (int counter = 0; counter < BGPMaster.NUM_THREADS; counter++) {
			slaveThreads.add(new Thread(new ThreadWorker(self, counter)));
		}

		/*
		 * Give everyone their self network, this will trigger events being
		 * placed into the sim queue for CPU finished
		 */
		netSeed.initialSeed();

		/*
		 * We need the initial MRAI fire events in here
		 */
		for (BGPSpeaker tAS : routingTopo.values()) {
			long jitter = rng.nextLong() % 30000;
			tAS.setOpeningMRAI(30000 + jitter);
			self.mraiQueue.add(new MRAIFireEvent(jitter + 30000, tAS));
		}

		/*
		 * Spin the slaves up
		 */
		for (Thread tThread : slaveThreads) {
			tThread.setDaemon(true);
			tThread.start();
		}

		Thread masterThread = new Thread(self);
		masterThread.start();
		try {
			masterThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	public BGPMaster(HashMap<Integer, BGPSpeaker> routingTopo) {
		this.workOut = 0;
		this.workSem = new HashMap<Integer, Semaphore>();
		this.completeSem = new Semaphore(0);
		this.readyToRunQueue = new HashMap<Integer, ConcurrentLinkedQueue<SimEvent>>();
		this.mraiQueue = new PriorityQueue<MRAIFireEvent>();

		this.topo = routingTopo;
		/*
		 * Give each BGP speaker a reference to the BGPMaster object (needed to
		 * hand events to the sim)
		 */
		for (BGPSpeaker tRouter : this.topo.values()) {
			tRouter.registerBGPMaster(this);
		}
		this.asnRunTo = new HashMap<Integer, Long>();
		for (int tASN : this.topo.keySet()) {
			this.asnRunTo.put(tASN, (long) 0);
		}

		for (int counter = 0; counter < BGPMaster.NUM_THREADS; counter++) {
			this.workSem.put(counter, new Semaphore(0));
			this.readyToRunQueue.put(counter,
					new ConcurrentLinkedQueue<SimEvent>());
		}

		this.asnToSlave = new HashMap<Integer, Integer>();
		int counter = 0;
		for (BGPSpeaker tRouter : this.topo.values()) {
			this.asnToSlave.put(tRouter.getASN(), counter);
			counter++;
			counter = counter % BGPMaster.NUM_THREADS;
		}

		this.asnIterList = this.buildOrderedASNList();
		this.setupStatPush();
	}

	public void run() {
		boolean stillRunning = true;
		boolean mraiRound = false;
		long currentTime = 0;
		long bgpStartTime = System.currentTimeMillis();
		System.out.println("Starting up the BGP processing.");

		HashSet<Integer> runNextRound = new HashSet<Integer>();

		while (stillRunning) {
			if (mraiRound) {
				currentTime = this.mraiQueue.peek().getEventTime();
			}
			this.spinUpWork(mraiRound, runNextRound, currentTime);
			mraiRound = !mraiRound;

			try {
				this.wall();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			if (mraiRound) {
				//FIXME stats need to be pushed by nodes themseleves
				//this.statPush(this.mraiQueue.peek().getEventTime());

				stillRunning = false;
				for (BGPSpeaker tRouter : this.topo.values()) {
					if (!tRouter.isDone()) {
						stillRunning = true;
						break;
					}
				}
			}
		}

		bgpStartTime = System.currentTimeMillis() - bgpStartTime;
		System.out.println("BGP done, this took: " + (bgpStartTime / 60000)
				+ " minutes.");
		System.out.println("Current step is: " + currentTime);

		this.doneLogging();
	}

	public void addMRAIFire(MRAIFireEvent inEvent) {
		synchronized (this.mraiQueue) {
			this.mraiQueue.add(inEvent);
		}
	}

	public SimEvent getWork(int id) throws InterruptedException {
		this.workSem.get(id).acquire();
		return this.readyToRunQueue.get(id).poll();
	}

	public void reportWorkDone() {
		this.completeSem.release();
	}

	private void spinUpWork(boolean mraiRound, HashSet<Integer> runNextRound,
			long currentTime) {

		if (mraiRound) {

			/*
			 * Empty out the work set, as we'll be populating this now
			 */
			runNextRound.clear();

			/*
			 * remove all MRAI pops
			 */
			synchronized (this.mraiQueue) {
				while (!this.mraiQueue.isEmpty()
						&& this.mraiQueue.peek().getEventTime() == currentTime) {
					MRAIFireEvent tEvent = this.mraiQueue.poll();

					/*
					 * Add all of the neighbors to the run next round list, as
					 * they will be able to run ahead now
					 */
					runNextRound.addAll(tEvent.getOwner().getASObject()
							.getNeighbors());
					runNextRound.add(tEvent.getOwner().getASN());

					int slaveID = this.asnToSlave.get(tEvent.getOwner()
							.getASN());
					this.readyToRunQueue.get(slaveID).add(tEvent);
					this.workSem.get(slaveID).release();
					this.workOut++;
				}
			}

		} else {
			if(runNextRound.isEmpty()){
				runNextRound.addAll(this.topo.keySet());
			}
			
			for (int tASN : runNextRound) {
				long timeHorizon = this.computeNextAdjMRAI(tASN);
				if (timeHorizon > this.asnRunTo.get(tASN)) {
					this.asnRunTo.put(tASN, timeHorizon);
					int slaveID = this.asnToSlave.get(tASN);
					this.readyToRunQueue.get(slaveID).add(
							new ProcessEvent(currentTime, timeHorizon,
									this.topo.get(tASN)));
					this.workSem.get(slaveID).release();
					this.workOut++;
				}
			}
		}
	}

	public void wall() throws InterruptedException {
		for (int counter = 0; counter < this.workOut; counter++) {
			this.completeSem.acquire();
		}
		this.workOut = 0;
	}

	private long computeNextAdjMRAI(int asn) {
		Set<Integer> adjASes = this.topo.get(asn).getASObject().getNeighbors();
		adjASes.add(asn);

		long min = Long.MAX_VALUE;
		for (int tASN : adjASes) {
			min = Math.min(min, this.topo.get(tASN).getNextMRAI());
		}

		return min;
	}

	private void setupStatPush() {

		try {
			this.ribOut = new BufferedWriter(new FileWriter(BGPMaster.LOG
					+ "rib-full.csv"));
			this.memOut = new BufferedWriter(new FileWriter(BGPMaster.LOG
					+ "mem-full.csv"));
			this.statOut = new BufferedWriter(new FileWriter(BGPMaster.LOG
					+ "stats.csv"));

			for (int counter = 0; counter < this.asnIterList.size(); counter++) {
				this.ribOut.write("," + this.asnIterList.get(counter));
				this.memOut.write("," + this.asnIterList.get(counter));
			}
			this.ribOut.newLine();
			this.memOut.newLine();
			this.statOut.write(",avg mem,med mem");
			this.statOut.newLine();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private void statPush(long currentTime) {
		List<Long> memList = new ArrayList<Long>();

		try {
			this.ribOut.write("" + currentTime);
			this.memOut.write("" + currentTime);

			for (int counter = 0; counter < this.asnIterList.size(); counter++) {
				this.ribOut.write(","
						+ this.topo.get(this.asnIterList.get(counter))
								.calcTotalRouteCount());
				long memLoad = this.topo.get(this.asnIterList.get(counter))
						.memLoad();
				memList.add(memLoad);
				this.memOut.write("," + memLoad);
			}

			this.statOut.write("" + currentTime + "," + Stats.mean(memList)
					+ "," + Stats.median(memList) + "," + Stats.max(memList));

			this.ribOut.newLine();
			this.memOut.newLine();
			this.statOut.newLine();

			this.ribOut.flush();
			this.memOut.flush();
			this.statOut.flush();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	private void doneLogging() {
		try {
			this.ribOut.close();
			this.memOut.close();
			this.statOut.close();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	/**
	 * Function that builds a list of ASNs in ASN order. This is used in order
	 * to report stats across a csv in a consistent manner.
	 * 
	 * @return a list of ASNs in the active topo in ascending order
	 */
	private List<Integer> buildOrderedASNList() {
		List<Integer> retList = new ArrayList<Integer>();
		retList.addAll(this.topo.keySet());
		Collections.sort(retList);
		return retList;
	}
}
