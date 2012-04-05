package sim;

import java.util.*;
import java.util.concurrent.*;

import events.BGPEvent;

public abstract class BGPMaster implements Runnable {

	private List<Thread> slaveThreads;
	//FIXME must be prority queue
	protected PriorityQueue<BGPEvent> eventQueue;

	//TODO lock this when used
	private PriorityQueue<Long> runningEventTimes;
	private Semaphore runningSem;

	public static final int MINERVA_THREADS = 10;
	public static final long NET_TIME = 10000;

	public BGPMaster(int threadCount) {

		/*
		 * Well, while work queues are different, event queues are very much the
		 * same
		 */
		this.eventQueue = new PriorityQueue<BGPEvent>();

		/*
		 * Build slave threads and store them
		 */
		this.slaveThreads = new LinkedList<Thread>();
		for (int counter = 0; counter < threadCount; counter++) {
			BGPSlave tObj = new BGPSlave(this, counter);
			this.slaveThreads.add(new Thread(tObj));
		}

		/*
		 * Objects used to compute window of events that can be run
		 */
		this.runningEventTimes = new PriorityQueue<Long>();
		this.runningSem = new Semaphore(1);
	}

	protected void startThreads() {
		for (Thread tThread : this.slaveThreads) {
			tThread.start();
		}
	}

	protected int getThreadCount() {
		return this.slaveThreads.size();
	}

	public void addEvent(BGPEvent inEvent) {
		long raWindow = this.getRunahead();

		/*
		 * If the new event is inside the run ahead window, send it off to be
		 * done, otherwise, store it until it is valid
		 */
		if (inEvent.getEventTime() < raWindow) {
			this.makeWorkReady(inEvent);
		} else {
			synchronized (this.eventQueue) {
				this.eventQueue.add(inEvent);
			}
		}
	}

	public abstract BGPEvent getWork(int threadID) throws InterruptedException;

	protected abstract void makeWorkReady(BGPEvent readyEvent);

	public void reportWorkDone(BGPEvent event) {
		//currently does nothing, needed for run ahead?

		/*
		 * Tell me (the master) to check if we can move the run ahead window
		 * forward
		 */
		this.runningSem.release();
	}

	public void run() {
		/*
		 * Step one, start up slaves
		 */
		this.startThreads();

		try {
			while (true) {
				this.runningSem.acquire();

			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	private long getRunahead() {
		long currentRunahead = 0;

		synchronized (this.runningEventTimes) {
			currentRunahead = this.runningEventTimes.peek();
		}

		return currentRunahead + BGPMaster.NET_TIME;
	}
}
