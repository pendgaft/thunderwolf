package threading;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import bgp.BGPRoute;
import events.*;
import router.BGPSpeaker;
import util.Stats;

public class BGPMaster implements Runnable {

	private int workOut;
	private Semaphore workSem;
	private Semaphore completeSem;

	private ReentrantLock workQueueLock;
	private PriorityQueue<SimEvent> workQueue;

	private ConcurrentLinkedQueue<SimEvent> readyToRunQueue;

	private HashMap<Integer, BGPSpeaker> topo;
	private long lastEpoch;

	private BufferedWriter ribOut;
	private BufferedWriter memOut;
	private BufferedWriter statOut;
	private List<Integer> asnIterList;

	private static final int NUM_THREADS = 2;

	private static final long EPOCH_TIME = 1000;

	private static final String LOG = "logs/";

	public static void driveSim(HashMap<Integer, BGPSpeaker> routingTopo)
			throws IOException {
		Random rng = new Random();

		/*
		 * build the master and slaves
		 */
		BGPMaster self = new BGPMaster(routingTopo);
		List<Thread> slaveThreads = new LinkedList<Thread>();
		for (int counter = 0; counter < BGPMaster.NUM_THREADS; counter++) {
			slaveThreads.add(new Thread(new ThreadWorker(self)));
		}

		/*
		 * Give everyone their self network, this will trigger events being
		 * placed into the sim queue for CPU finished
		 */
		for (BGPSpeaker tAS : routingTopo.values()) {
			// TODO this size needs to be configured
			tAS.advPath(new BGPRoute(tAS.getASN(), 1), 1);
		}

		/*
		 * We need the initial MRAI fire events in here
		 */
		for (BGPSpeaker tAS : routingTopo.values()) {
			long jitter = rng.nextLong() % 30000;
			self.addWork(new MRAIFireEvent(30000 + jitter, tAS));
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
		this.workQueueLock = new ReentrantLock(true);
		this.workQueue = new PriorityQueue<SimEvent>();
		this.readyToRunQueue = new ConcurrentLinkedQueue<SimEvent>();
		this.lastEpoch = 0;

		this.topo = routingTopo;
		/*
		 * Give each BGP speaker a reference to the BGPMaster object (needed to
		 * hand events to the sim)
		 */
		for (BGPSpeaker tRouter : this.topo.values()) {
			tRouter.registerBGPMaster(this);
		}

		this.asnIterList = this.buildOrderedASNList();
		this.setupStatPush();
	}

	public void run() {
		boolean stillRunning = true;
		long bgpStartTime = System.currentTimeMillis();
		System.out.println("Starting up the BGP processing.");

		while (stillRunning) {
			this.spinUpWork();

			try {
				this.wall();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			this.workQueueLock.lock();
			if (this.workQueue.peek().getEventTime() >= this.lastEpoch
					+ BGPMaster.EPOCH_TIME) {
				this.lastEpoch += BGPMaster.EPOCH_TIME;

				this.statPush(this.lastEpoch);

				stillRunning = false;
				for (BGPSpeaker tRouter : this.topo.values()) {
					if (!tRouter.isDone()) {
						stillRunning = true;
						break;
					}
				}
			}
			this.workQueueLock.unlock();
		}

		bgpStartTime = System.currentTimeMillis() - bgpStartTime;
		System.out.println("BGP done, this took: " + (bgpStartTime / 60000)
				+ " minutes.");
		System.out.println("Current step is: "
				+ this.workQueue.peek().getEventTime());

		this.doneLogging();
	}

	/**
	 * Public interface to hand an event to the simulator.
	 * 
	 * @param addedEvents
	 */
	public void addWork(SimEvent addedEvent) {
		this.workQueueLock.lock();
		this.workQueue.add(addedEvent);
		this.workQueueLock.unlock();
	}

	/**
	 * Interface used to update the time of an event.
	 * 
	 * @param oldEvent
	 *            - the old event
	 * @param newEvent
	 *            - the new event at the new time
	 */
	public void swapWork(SimEvent oldEvent, SimEvent newEvent) {
		this.workQueueLock.lock();
		this.workQueue.remove(oldEvent);
		this.workQueue.add(newEvent);
		this.workQueueLock.unlock();
	}

	public SimEvent getWork() throws InterruptedException {
		this.workSem.acquire();
		return this.readyToRunQueue.poll();
	}

	public void reportWorkDone() {
		this.completeSem.release();
	}

	// TODO this has no runahead at this point...
	private void spinUpWork() {

		this.workQueueLock.lock();
		long nextTime = this.workQueue.peek().getEventTime();
		this.workOut = 0;

		while (!this.workQueue.isEmpty()
				&& this.workQueue.peek().getEventTime() == nextTime) {
			this.readyToRunQueue.add(this.workQueue.poll());
			this.workSem.release();
			this.workOut++;
		}

		this.workQueueLock.unlock();
	}

	public void wall() throws InterruptedException {
		for (int counter = 0; counter < this.workOut; counter++) {
			this.completeSem.acquire();
		}
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

			this.statOut.write("" + currentTime + "," + Stats.mean(memList) + "," + Stats.median(memList) + "," + Stats.max(memList));
			
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

	private List<Integer> buildOrderedASNList() {
		List<Integer> retList = new ArrayList<Integer>();
		retList.addAll(this.topo.keySet());
		Collections.sort(retList);
		return retList;
	}
}
