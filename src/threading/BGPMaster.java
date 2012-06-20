package threading;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import bgp.BGPRoute;
import events.*;
import router.BGPSpeaker;

public class BGPMaster implements Runnable {

	private int workOut;
	private Semaphore workSem;
	private Semaphore completeSem;

	private ReentrantLock workQueueLock;
	private PriorityQueue<SimEvent> workQueue;

	private ConcurrentLinkedQueue<SimEvent> readyToRunQueue;

	private static final int NUM_THREADS = 2;
	
	private static final long GOAL_TIME = 600000;

	// TODO sanity check that we're not "time warping" or does that even make
	// sense w/ good run ahead?

	public static void driveSim(HashMap<Integer, BGPSpeaker> routingTopo)
			throws IOException {

		/*
		 * build the master and slaves
		 */
		BGPMaster self = new BGPMaster();
		List<Thread> slaveThreads = new LinkedList<Thread>();
		for (int counter = 0; counter < BGPMaster.NUM_THREADS; counter++) {
			slaveThreads.add(new Thread(new ThreadWorker(self)));
		}

		/*
		 * Give each BGP speaker a reference to the BGPMaster object (needed to
		 * hand events to the sim)
		 */
		for (BGPSpeaker tRouter : routingTopo.values()) {
			tRouter.registerBGPMaster(self);
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
			// TODO add jitter plox
			self.addWork(new MRAIFireEvent(30000, tAS));
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

	public BGPMaster() {
		this.workOut = 0;
		this.workSem = new Semaphore(0);
		this.completeSem = new Semaphore(0);
		this.workQueueLock = new ReentrantLock(true);
		this.workQueue = new PriorityQueue<SimEvent>();
		this.readyToRunQueue = new ConcurrentLinkedQueue<SimEvent>();
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
			if(this.workQueue.peek().getEventTime() >= BGPMaster.GOAL_TIME){
				stillRunning = false;
			}
			this.workQueueLock.unlock();
		}

		bgpStartTime = System.currentTimeMillis() - bgpStartTime;
		System.out.println("BGP done, this took: " + (bgpStartTime / 60000)
				+ " minutes.");

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

		//System.out.println("time " + nextTime);
		
		while (!this.workQueue.isEmpty() && this.workQueue.peek().getEventTime() == nextTime) {
			this.readyToRunQueue.add(this.workQueue.poll());
			this.workSem.release();
			this.workOut++;
		}

		this.workQueueLock.unlock();
		
		//System.out.println("events " + workOut);
	}

	public void wall() throws InterruptedException {
		for (int counter = 0; counter < this.workOut; counter++) {
			this.completeSem.acquire();
		}
	}
}
