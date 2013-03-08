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

	/*
	 * These guys are for individual event handling w/ worker threads
	 */
	private int taskOut;
	private Semaphore taskSem;
	private Semaphore taskCompleteSem;

	/*
	 * These guys are for work nodes handed out (task groupings)
	 */
	private Semaphore workCompleteSem;
	private ConcurrentLinkedQueue<WorkNode> completedNodes;

	private ConcurrentLinkedQueue<SimEvent> readyToRunQueue;

	private HashMap<Integer, BGPSpeaker> topo;
	private WorkGraph workGraph;

	private long nextWall;
	private boolean runningFromWall;
	private HashMap<Integer, WorkNode> eventToWorkNode;

	/**
	 * Stores the time up to which an AS's processing has been computed i.e.
	 * what time this AS thinks it is
	 */
	private HashMap<Integer, Long> asnRunTo;
	private SimLogger logMaster;

	private static final int NUM_THREADS = 10;
	public static final boolean THREAD_DEBUG = false;

	public static void driveSim(HashMap<Integer, BGPSpeaker> routingTopo, NetworkSeeder netSeed) throws IOException {
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
		HashSet<Long> mraiUnique = new HashSet<Long>();
		for (BGPSpeaker tAS : routingTopo.values()) {
			long jitter;
			long mraiValue = 0;
			while (mraiValue == 0) {
				jitter = rng.nextLong() % (30 * SimEvent.SECOND_MULTIPLIER);
				mraiValue = 30 * SimEvent.SECOND_MULTIPLIER + jitter;
				if (mraiUnique.contains(mraiValue) || mraiValue % SimEvent.SECOND_MULTIPLIER == 0) {
					mraiValue = 0;
				}
			}
			mraiUnique.add(mraiValue);
			tAS.setOpeningMRAI(mraiValue);
		}

		/*
		 * Spin the slaves up
		 */
		for (Thread tThread : slaveThreads) {
			tThread.setDaemon(true);
			tThread.start();
		}

		self.operateSim();
	}

	public BGPMaster(HashMap<Integer, BGPSpeaker> routingTopo) {

		this.taskOut = 0;
		this.taskSem = new Semaphore(0);
		this.taskCompleteSem = new Semaphore(0);
		this.readyToRunQueue = new ConcurrentLinkedQueue<SimEvent>();

		this.nextWall = 0;
		this.eventToWorkNode = new HashMap<Integer, WorkNode>();

		this.topo = routingTopo;

		this.workCompleteSem = new Semaphore(0);
		this.completedNodes = new ConcurrentLinkedQueue<WorkNode>();

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

		/*
		 * Build logging mechanisms, these are done here since we need to freeze
		 * the simulator to actually do the logging.
		 */
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

	}

	public void operateSim() {
		boolean stillRunning = true;
		long bgpStartTime = System.currentTimeMillis();
		System.out.println("Starting up the BGP processing.");

		while (stillRunning) {
			boolean toTheWall = false;

			/*
			 * Do Run-up to next MRAI from current wall, then update the wall
			 */
			this.runningFromWall = true;
			this.runFromWall(this.nextWall, this.logMaster.getNextLogTime());
			this.nextWall = this.logMaster.getNextLogTime();
			this.runningFromWall = false;

			/*
			 * Rebuild the work graph
			 */
			this.workGraph = new WorkGraph(this.topo);
			if (BGPMaster.THREAD_DEBUG) {
				System.out.println("Build work graph.");
				System.out.println(this.workGraph);
			}

			while (!toTheWall) {

				/*
				 * Reset the done status, fetch the nodes we start w/ and start
				 * them
				 */
				this.workGraph.resetDoneStatus();
				this.eventToWorkNode.clear();
				Set<WorkNode> roots = this.workGraph.getRoots();
				if (BGPMaster.THREAD_DEBUG) {
					System.out.println("Firing up " + roots.size() + " roots.");
				}
				for (WorkNode tNode : roots) {
					this.firstStepWorkNodeRun(tNode);
				}

				/*
				 * While we still have work nodes running, wait for nodes to
				 * complete, and then spin up any children that might be good to
				 * run
				 */
				for (int counter = 0; counter < this.workGraph.size(); counter++) {
					try {
						this.workCompleteSem.acquire();
					} catch (InterruptedException e) {
						e.printStackTrace();
						System.exit(2);
					}

					if (BGPMaster.THREAD_DEBUG) {
						System.out.println("A node is done!");
					}
					WorkNode doneNode = this.completedNodes.poll();
					Set<WorkNode> goodToGo = doneNode.toggleRan();
					if (BGPMaster.THREAD_DEBUG) {
						System.out.println("Spinning up: " + goodToGo.size() + " new nodes.");
					}
					for (WorkNode tNode : goodToGo) {
						this.firstStepWorkNodeRun(tNode);
					}
				}

				/*
				 * Check to see if we've reached the wall
				 */
				if (BGPMaster.THREAD_DEBUG) {
					System.out.println("Check if to the wall.");
				}
				toTheWall = true;
				for (long tTime : this.asnRunTo.values()) {
					if (tTime < this.nextWall) {
						toTheWall = false;
						break;
					}
				}
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

			/*
			 * Evaluate if we're still running at the end of a round
			 */
			stillRunning = false;
			for (BGPSpeaker tRouter : this.topo.values()) {
				if (!tRouter.isDone()) {
					stillRunning = true;
					break;
				}
			}
		}

		/*
		 * Spit out some end of simulation info
		 */
		bgpStartTime = System.currentTimeMillis() - bgpStartTime;
		System.out.println("BGP done, this took: " + (bgpStartTime / 60000) + " minutes.");
		System.out.println("Current step is: " + this.nextWall);

		try {
			this.logMaster.doneLogging();
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("Error closing logs, might be bad, but trying to muddle through.");
		}
	}

	private void runFromWall(long wallTime, long nextWall) {
		this.taskCompleteSem.drainPermits();
		this.taskOut = 0;
		for (int tASN : this.topo.keySet()) {
			this.queueCPUEvent(tASN, wallTime, nextWall, null);
		}

		this.wallOnTasks();
	}

	private void queueCPUEvent(int asn, long currentTime, long theWall, WorkNode linkedWorkNode) {
		/*
		 * You're clear to move up to either the next MRAI you'll see or the
		 * logging horizon, which ever is first obviously, this really shouldn't
		 * happen outside of TINY logging windows (Log Window < MRAI to be
		 * exact)
		 */
		long timeHorizon = Math.min(this.computeNextAdjMRAI(asn), theWall);

		/*
		 * Sanity check that we have not ran past the window, if we have not,
		 * then please proceed
		 */
		if (timeHorizon >= this.asnRunTo.get(asn)) {
			this.asnRunTo.put(asn, timeHorizon);
			ProcessEvent theEvent = new ProcessEvent(currentTime, timeHorizon, this.topo.get(asn));
			this.readyToRunQueue.add(theEvent);
			this.eventToWorkNode.put(asn, linkedWorkNode);
			this.taskSem.release();
			this.taskOut++;
		} else {
			/*
			 * If we processed past this window that would be bad, we can have
			 * already processed up to this time (edge condition when logging
			 * horizon == mrai)
			 */
			throw new RuntimeException("There is a node ahead of the current time, but it has a processing event!");
		}
	}

	private void wallOnTasks() {
		for (int counter = 0; counter < this.taskOut; counter++) {
			try {
				this.taskCompleteSem.acquire();
			} catch (InterruptedException e) {
				e.printStackTrace();
				System.exit(2);
			}
		}

		this.taskOut = 0;
	}

	private void firstStepWorkNodeRun(WorkNode taskGroup) {
		if (BGPMaster.THREAD_DEBUG) {
			System.out.println("First stage go: " + taskGroup);
		}

		BGPSpeaker advRouter = this.topo.get(taskGroup.getAdvertiser());
		MRAIFireEvent tEvent = new MRAIFireEvent(advRouter.getNextMRAI(), advRouter);
		this.eventToWorkNode.put(taskGroup.getAdvertiser(), taskGroup);
		this.readyToRunQueue.add(tEvent);
		this.taskSem.release();
	}

	private void secondStepWorkNodeRun(WorkNode taskGroup) {
		if (BGPMaster.THREAD_DEBUG) {
			System.out.println("Second stage go.");
		}
		this.queueCPUEvent(taskGroup.getAdvertiser(), this.asnRunTo.get(taskGroup.getAdvertiser()), this.nextWall,
				taskGroup);
		for (int tASN : taskGroup.getAdjacent()) {
			this.queueCPUEvent(tASN, this.asnRunTo.get(tASN), this.nextWall, taskGroup);
		}
	}

	public SimEvent getWork() throws InterruptedException {
		this.taskSem.acquire();
		return this.readyToRunQueue.poll();
	}

	public void reportWorkDone(SimEvent completedEvent) {
		if (BGPMaster.THREAD_DEBUG) {
			System.out.println("Incoming complete event: " + completedEvent.toString());
		}

		if (completedEvent.getEventType() == SimEvent.MRAI_EVENT) {
			this.secondStepWorkNodeRun(this.eventToWorkNode.get(completedEvent.getOwner().getASN()));
		} else if (!this.runningFromWall) {
			WorkNode tNode = this.eventToWorkNode.get(completedEvent.getOwner().getASN());
			if (tNode.decrimentOutstandingSubTasks() == 0) {
				this.completedNodes.add(tNode);
				this.workCompleteSem.release();
			}
		} else {
			/*
			 * This case happens when we're just running from a wall up to
			 * everyone's next mrai with CPU events, in other words, when
			 * runningFromWall is true, all we need to do is release a sem
			 * ticket to move the call to wall forward
			 */
			this.taskCompleteSem.release();
		}
	}

	public SimLogger getLoggingHook() {
		return this.logMaster;
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
