package threading;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import networkConfig.NetworkSeeder;
import events.*;
import router.BGPSpeaker;
import logging.SimLogger;

public class BGPMaster implements Runnable {

	private int workOut;
	private Semaphore workSem;
	private Semaphore completeSem;

	private ConcurrentLinkedQueue<SimEvent> readyToRunQueue;

	private HashMap<Integer, BGPSpeaker> topo;

	private Queue<MRAIFireEvent> mraiQueue;
	private HashMap<Integer, Long> asnRunTo;
	private SimLogger logMaster;

	private static final int NUM_THREADS = 10;

	public static void driveSim(HashMap<Integer, BGPSpeaker> routingTopo,
			NetworkSeeder netSeed) throws IOException {
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
		this.workSem = new Semaphore(0);
		this.completeSem = new Semaphore(0);
		this.readyToRunQueue = new ConcurrentLinkedQueue<SimEvent>();
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

		try {
			// TODO make this file name configurable
			this.logMaster = new SimLogger("test", this.topo);
			this.logMaster.setupStatPush();
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Logger setup failed, aborting.");
			System.exit(2);
		}
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

		try {
			this.logMaster.doneLogging();
		} catch (IOException e) {
			e.printStackTrace();
			System.out
					.println("Error closing logs, might be bad, but trying to muddle through.");
		}
	}

	public void addMRAIFire(MRAIFireEvent inEvent) {
		synchronized (this.mraiQueue) {
			this.mraiQueue.add(inEvent);
		}
	}

	public SimEvent getWork() throws InterruptedException {
		this.workSem.acquire();
		return this.readyToRunQueue.poll();
	}

	public void reportWorkDone() {
		this.completeSem.release();
	}

	public SimLogger getLoggingHook() {
		return this.logMaster;
	}

	/**
	 * Function that actually drives the worker threads. This will run in a
	 * "round" based fashion. One round will deal with the MRAI expire, i.e. the
	 * sending of updates. The round after will deal with the processing of said
	 * updates. This will go back and forth.
	 * 
	 * @param mraiRound
	 * @param runNextRound
	 * @param currentTime
	 */
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
				/*
				 * This has logic to deal with multiple routers having MRAIs at
				 * the same time, shouldn't happen, but the logic is here if
				 * needed in the future again
				 */
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

					this.readyToRunQueue.add(tEvent);
					this.workSem.release();
					this.workOut++;
				}
			}

		} else {
			if (runNextRound.isEmpty()) {
				runNextRound.addAll(this.topo.keySet());
			}

			for (int tASN : runNextRound) {
				long timeHorizon = this.computeNextAdjMRAI(tASN);
				if (timeHorizon > this.asnRunTo.get(tASN)) {
					this.asnRunTo.put(tASN, timeHorizon);
					this.readyToRunQueue.add(new ProcessEvent(currentTime,
							timeHorizon, this.topo.get(tASN)));
					this.workSem.release();
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
}
