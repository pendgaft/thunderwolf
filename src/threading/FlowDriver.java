package threading;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.concurrent.*;

import logging.SimLogger;
import router.BGPSpeaker;
import events.*;

public class FlowDriver implements Runnable {

	private HashMap<Integer, BGPSpeaker> topo;
	private PriorityBlockingQueue<SimEvent> eventQueue;

	private double timeToMoveTo;

	private Semaphore blockOnChildSem;
	private Semaphore runForwardSem;
	private Semaphore scanQueueSem;
	private Semaphore eventUpdateSem;
	private Semaphore processUpdateSem;

	private SimLogger logMaster;

	private static int NUMBER_OF_THREADS = 1;
	private static final double MAX_SIM_TIME = 120000.0;
	private static final boolean DEBUG_TABLES = false;
	private static final boolean DEBUG_EVENTS = false;
	private static final boolean MULTI_THREADING = true;

	private static final long REPORTING_WINDOW = 600000;

	//XXX consider saner way to pass this in
	public static int SIM_END_MODE = FlowDriver.WORK_SIM_END;

	public static final int TIMED_SIM_END = 1;
	public static final int WORK_SIM_END = 2;

	public FlowDriver(HashMap<Integer, BGPSpeaker> routingTopology, SimLogger logs) {
		try {
			if (InetAddress.getLocalHost().getHostName().equals("minerva.cs.umn.edu")) {
				FlowDriver.NUMBER_OF_THREADS = 10;
			} else {
				if(FlowDriver.MULTI_THREADING){
					FlowDriver.NUMBER_OF_THREADS = 4;
				}
			}
		} catch (UnknownHostException e) {
			e.printStackTrace();
			System.exit(-1);
		}
		System.out.println("Building flow driver with " + FlowDriver.NUMBER_OF_THREADS + " theads.");

		this.topo = routingTopology;
		this.eventQueue = new PriorityBlockingQueue<SimEvent>();
		this.timeToMoveTo = 0.0;

		this.blockOnChildSem = new Semaphore(0);
		this.runForwardSem = new Semaphore(0);
		this.scanQueueSem = new Semaphore(0);
		this.eventUpdateSem = new Semaphore(0);
		this.processUpdateSem = new Semaphore(0);
		this.logMaster = logs;

		this.seedInitialEvents();
		this.buildChildren();
	}

	/**
	 * Populates the event queue with the initial logging event and each
	 * router's initial Queue finish event and MRAI event
	 */
	private void seedInitialEvents() {
		this.eventQueue.put(new LoggingEvent(SimLogger.LOG_EPOCH));
		for (BGPSpeaker tRouter : this.topo.values()) {
			this.eventQueue.put(tRouter.getNextMRAI());
			this.eventQueue.put(tRouter.getNextProcessEvent());
		}
	}

	private void buildChildren() {

		ThreadWorker[] tChildren = new ThreadWorker[FlowDriver.NUMBER_OF_THREADS];
		for (int counter = 0; counter < FlowDriver.NUMBER_OF_THREADS; counter++) {
			tChildren[counter] = new ThreadWorker(this, counter);
			Thread tThread = new Thread(tChildren[counter]);
			tThread.setName("Child worker number: " + counter);
			tThread.setDaemon(true);
			tThread.start();
		}

		int threadPos = 0;
		for (BGPSpeaker tRouter : this.topo.values()) {
			tChildren[threadPos].giveChild(tRouter);
			threadPos = (threadPos + 1) % FlowDriver.NUMBER_OF_THREADS;
		}
	}

	public void run() {

		long simStartTime = System.currentTimeMillis();
		long lastReport = System.currentTimeMillis();
		while (!this.simFinished()) {
			long currentTime = System.currentTimeMillis();
			if (currentTime - lastReport >= FlowDriver.REPORTING_WINDOW) {
				lastReport = currentTime;
				this.logMaster.printToConsole(lastReport - simStartTime, this.timeToMoveTo);
			}

			SimEvent nextEvent = this.eventQueue.poll();

			if (FlowDriver.DEBUG_EVENTS) {
				System.out.println(nextEvent.toString());
				int headRoot = 0;
				int root = 0;
				for (BGPSpeaker tRouter : this.topo.values()) {
					tRouter.printHeadOfQueues();
					headRoot += tRouter.countRootAtHead();
					root += tRouter.countDepRoots();
				}
				System.out.println("Roots: " + root + " head roots: " + headRoot);
			}

			/*
			 * Find out when we get to run forward to, and release the children
			 */
			if (nextEvent.getEventTime() < this.timeToMoveTo) {
				throw new RuntimeException("Attempted to time travel.");
			}
			this.timeToMoveTo = nextEvent.getEventTime();
			this.runForwardSem.release(FlowDriver.NUMBER_OF_THREADS);
			this.blockOnChildren();

			this.scanQueueSem.release(FlowDriver.NUMBER_OF_THREADS);
			this.blockOnChildren();

			/*
			 * Deal with any special event activity, and then repopoulate the
			 * event
			 */
			nextEvent.handleEvent(this.logMaster);
			this.eventQueue.put(nextEvent.repopulate());
			
			this.eventUpdateSem.release(FlowDriver.NUMBER_OF_THREADS);
			this.blockOnChildren();

			this.processUpdateSem.release(FlowDriver.NUMBER_OF_THREADS);
			this.blockOnChildren();
		}

		/*
		 * We're finished with the simulation, do any cleanup
		 */
		if (FlowDriver.DEBUG_TABLES) {
			for (BGPSpeaker tRouter : this.topo.values()) {
				System.out.println(tRouter.printBGPString(false));
				System.out.println("active count for " + tRouter.getASN() + " is " + tRouter.countActiveQueues(-1));
			}
		}
		System.out.println("Simulation ran to: " + this.timeToMoveTo + " simulated wall time.");
		System.out
				.println("This took: " + (double) (System.currentTimeMillis() - simStartTime) / 60000.0 + " minutes.");
	}

	private boolean simFinished() {
		if (FlowDriver.SIM_END_MODE == FlowDriver.TIMED_SIM_END) {
			return this.timeSimFinished();
		} else if (FlowDriver.SIM_END_MODE == FlowDriver.WORK_SIM_END) {
			return this.workSimFinished();
		} else {
			throw new RuntimeException("Bad simulation end mode given: " + FlowDriver.SIM_END_MODE);
		}
	}

	private void blockOnChildren() {
		try {
			this.blockOnChildSem.acquire(FlowDriver.NUMBER_OF_THREADS);
		} catch (InterruptedException e) {
			e.printStackTrace();
			System.exit(-2);
		}
	}

	private boolean timeSimFinished() {
		return this.timeToMoveTo >= FlowDriver.MAX_SIM_TIME;
	}

	private boolean workSimFinished() {
		for (BGPSpeaker tRouter : this.topo.values()) {
			if (!tRouter.isDone()) {
				return false;
			}
		}
		return true;
	}

	public void replaceProcessEvent(ProcessEvent oldEvent, ProcessEvent newEvent) {
		this.eventQueue.remove(oldEvent);
		this.eventQueue.add(newEvent);
	}

	public double getNextTimeAdvnace() throws InterruptedException {
		this.runForwardSem.acquire();
		return this.timeToMoveTo;
	}

	public void waitForScanQueues() throws InterruptedException {
		this.scanQueueSem.acquire();
	}

	public void waitForEventAdjust() throws InterruptedException {
		this.eventUpdateSem.acquire();
	}

	public void waitForProcessEventUpdate() throws InterruptedException {
		this.processUpdateSem.acquire();
	}

	public void reportWorkDone() {
		this.blockOnChildSem.release();
	}
}
