package threading;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

import bgp.BGPRoute;
import events.SimEvent;
import router.BGPSpeaker;

public class BGPMaster {

	private Semaphore workSem;
	private Semaphore completeSem;

	private ReentrantLock workQueueLock;
	private PriorityQueue<SimEvent> workQueue;

	private static final int NUM_THREADS = 8;

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
		 * Give everyone their self network
		 */
		for (BGPSpeaker tAS : routingTopo.values()) {
			//TODO this size needs to be configured
			tAS.advPath(new BGPRoute(tAS.getASN(), 1));
		}
		
		/*
		 * Spin the slaves up
		 */
		long bgpStartTime = System.currentTimeMillis();
		System.out.println("Starting up the BGP processing.");
		for (Thread tThread : slaveThreads) {
			tThread.setDaemon(true);
			tThread.start();
		}

		//XXX here down is the old event pumps
		int stepCounter = 0;
		boolean stuffToDo = true;
		boolean skipToMRAI = false;
		while (stuffToDo) {
			stuffToDo = false;
			return this.workQueue.poll();

			/*
			 * dole out work to slaves
			 */
			for (Set<AS> tempBlock : asBlocks) {
				self.addWork(tempBlock);
			}

			/*
			 * Wait till this round is done
			 */
			try {
				self.wall();
			} catch (InterruptedException e) {
				e.printStackTrace();
				System.exit(-2);
			}

			/*
			 * check if nodes still have stuff to do
			 */
			for (AS tAS : usefulASMap.values()) {
				if (tAS.hasWorkToDo()) {
					stuffToDo = true;
				}
				if (tAS.hasDirtyPrefixes()) {
					skipToMRAI = true;
				}
			}

			/*
			 * If we have no pending BGP messages, release all pending updates,
			 * this is slightly different from a normal MRAI, but it gets the
			 * point
			 */
			if (!stuffToDo && skipToMRAI) {
				for (AS tAS : usefulASMap.values()) {
					tAS.mraiExpire();
				}
				skipToMRAI = false;
				stuffToDo = true;
			}

			/*
			 * A tiny bit of logging
			 */
			//			stepCounter++;
			//			if (stepCounter % 1000 == 0) {
			//				System.out.println("" + (stepCounter / 1000) + " (1k msgs)");
			//			}
		}

		bgpStartTime = System.currentTimeMillis() - bgpStartTime;
		System.out.println("BGP done, this took: " + (bgpStartTime / 60000)
				+ " minutes.");
	}

	public BGPMaster() {
		this.workSem = new Semaphore(0);
		this.completeSem = new Semaphore(0);
		this.workQueueLock = new ReentrantLock(true);
		this.workQueue = new PriorityQueue<SimEvent>();
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
		return this.workQueue.poll();
	}

	public void reportWorkDone() {
		this.completeSem.release();
	}

	public void wall() throws InterruptedException {
		for (int counter = 0; counter < this.blockCount; counter++) {
			this.completeSem.acquire();
		}
	}
}
