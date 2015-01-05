package threading;

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
	private Semaphore eventUpdateSem;
	private Semaphore processUpdateSem;

	private SimLogger logMaster;

	private static final int NUMBER_OF_THREADS = 2;
	private static final double MAX_SIM_TIME = 600000.0;
	private static final boolean DEBUG_TABLES = true;
	private static final boolean DEBUG_EVENTS = false;

	public FlowDriver(HashMap<Integer, BGPSpeaker> routingTopology, SimLogger logs) {
		this.topo = routingTopology;
		this.eventQueue = new PriorityBlockingQueue<SimEvent>();
		this.timeToMoveTo = 0.0;

		this.blockOnChildSem = new Semaphore(0);
		this.runForwardSem = new Semaphore(0);
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

		while (!this.simFinished()) {
			SimEvent nextEvent = this.eventQueue.poll();

			if(FlowDriver.DEBUG_EVENTS){
				System.out.println(nextEvent.toString());
			}
			
			/*
			 * Find out when we get to run forward to, and release the children
			 */
			if(nextEvent.getEventTime() < this.timeToMoveTo){
				throw new RuntimeException("Attempted to time travel.");
			}
			this.timeToMoveTo = nextEvent.getEventTime();
			this.runForwardSem.release(FlowDriver.NUMBER_OF_THREADS);

			/*
			 * Wait until the children are done
			 */
			try {
				this.blockOnChildSem.acquire(FlowDriver.NUMBER_OF_THREADS);
			} catch (InterruptedException e) {
				e.printStackTrace();
				System.exit(-2);
			}

			/*
			 * Deal with any special event activity, and then repopoulate the
			 * event
			 */
			nextEvent.handleEvent(this.logMaster);
			this.eventQueue.put(nextEvent.repopulate());

			/*
			 * Again, release the children, wait till they are done..
			 */
			this.eventUpdateSem.release(FlowDriver.NUMBER_OF_THREADS);
			try {
				this.blockOnChildSem.acquire(FlowDriver.NUMBER_OF_THREADS);
			} catch (InterruptedException e) {
				e.printStackTrace();
				System.exit(-2);
			}

			this.processUpdateSem.release(FlowDriver.NUMBER_OF_THREADS);
			try {
				this.blockOnChildSem.acquire(FlowDriver.NUMBER_OF_THREADS);
			} catch (InterruptedException e) {
				e.printStackTrace();
				System.exit(-2);
			}
		}

		/*
		 * We're finished with the simulation, do any cleanup
		 */
		if (FlowDriver.DEBUG_TABLES) {
			for (BGPSpeaker tRouter : this.topo.values()) {
				System.out.println(tRouter.printBGPString(false));
			}
		}
	}

	//TODO at some point this should be smarter, for now run to a fixed simulated time
	private boolean simFinished() {
		return this.timeToMoveTo >= FlowDriver.MAX_SIM_TIME;
	}

	public void replaceProcessEvent(ProcessEvent oldEvent, ProcessEvent newEvent) {
		this.eventQueue.remove(oldEvent);
		this.eventQueue.add(newEvent);
	}

	public double getNextTimeAdvnace() throws InterruptedException {
		this.runForwardSem.acquire();
		return this.timeToMoveTo;
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
