package threading;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import networkConfig.NetworkSeeder;
import events.*;
import router.BGPSpeaker;
import logging.SimLogger;

//TODO add a flag to fix the time that this runs (killing the simulation at that point even though there still are events
public class BGPMaster implements Runnable {

	private int workOut;
	private Semaphore workSem;
	private Semaphore completeSem;

	private ConcurrentLinkedQueue<SimEvent> readyToRunQueue;

	private HashMap<Integer, BGPSpeaker> topo;

	private Queue<MRAIFireEvent> mraiQueue;

	/**
	 * Stores the time up to which an AS's processing has been computed i.e.
	 * what time this AS thinks it is
	 */
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
		// XXX is there a reason we do this now rather than earlier?
		netSeed.initialSeed();

		/*
		 * We need the initial MRAI fire events in here
		 */
		for (BGPSpeaker tAS : routingTopo.values()) {
			long jitter = rng.nextLong() % (30 * SimEvent.SECOND_MULTIPLIER);
			long mraiValue = 30 * SimEvent.SECOND_MULTIPLIER + jitter;
			tAS.setOpeningMRAI(mraiValue);
			self.mraiQueue.add(new MRAIFireEvent(mraiValue, tAS));
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
		long nextLogTime = this.logMaster.getNextLogTime();
		long bgpStartTime = System.currentTimeMillis();
		System.out.println("Starting up the BGP processing.");

		HashSet<Integer> runNextRound = new HashSet<Integer>();

		while (stillRunning) {
			if (mraiRound) {
				long nextMRAI = this.mraiQueue.peek().getEventTime();
				
				/*
				 * Time to log, as everyone is ran up to the logging horizon
				 */
				if (nextMRAI >= nextLogTime) {
					/*
					 * The simulator is up to date up to the logging horizon
					 */
					currentTime = nextLogTime;

					/*
					 * Do the logging and update the logging horizon
					 */
					try {
						this.logMaster.processLogging();
					} catch (IOException e) {
						System.err.println("Logging mechanism failed, dying violently!");
						e.printStackTrace();
						System.exit(-1);
					}
					nextLogTime = this.logMaster.getNextLogTime();

					/*
					 * We can't process the next MRAI yet, since no one is
					 * actually there, they are just up to the old logging
					 * horizon, so let people run past this wall to their next
					 * MRAI (or concievably the next logging horizon if we're
					 * doing really tight logging)
					 */
					mraiRound = false;
					runNextRound.clear();
				} else {
					currentTime = this.mraiQueue.peek().getEventTime();
				}
				
				/*
				 * Do our last logging, since nothing is changing
				 */
				try {
					this.logMaster.processLogging();
				} catch (IOException e) {
					System.err.println("Logging mechanism failed, dying violently!");
					e.printStackTrace();
					System.exit(-1);
				}
			}
			
			this.spinUpWork(mraiRound, runNextRound, currentTime, nextLogTime);
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
	 * @param nextLogTime
	 */
	private void spinUpWork(boolean mraiRound, HashSet<Integer> runNextRound,
			long currentTime, long nextLogTime) {

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
			/*
			 * This is an edge case for the FIRST round of the simulator or when
			 * we get done with a logging pass and concievably all nodes need to
			 * move forward to their next mrai
			 */
			if (runNextRound.isEmpty()) {
				runNextRound.addAll(this.topo.keySet());
			}

			for (int tASN : runNextRound) {
				/*
				 * You're clear to move up to either the next MRAI you'll see or
				 * the logging horizon, which ever is first obviously
				 */
				long timeHorizon = Math.min(this.computeNextAdjMRAI(tASN),
						nextLogTime);
				/*
				 * Sanity check that we have not ran past the window, if we have
				 * not, then please proceed
				 */
				if (timeHorizon > this.asnRunTo.get(tASN)) {
					this.asnRunTo.put(tASN, timeHorizon);
					this.readyToRunQueue.add(new ProcessEvent(currentTime,
							timeHorizon, this.topo.get(tASN)));
					this.workSem.release();
					this.workOut++;
				} else {
					/*
					 * If we processed past this window that would be bad, we
					 * can have already processed up to this time (edge
					 * condition when logging horizon == mrai)
					 */
					if (timeHorizon < this.asnRunTo.get(tASN)) {
						throw new RuntimeException(
								"There is a node ahead of the current time, but it has a processing event!");
					}
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
