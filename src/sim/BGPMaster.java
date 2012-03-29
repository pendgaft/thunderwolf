package sim;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import topo.AS;
import topo.ASTopoParser;
import topo.BGPPath;

public class BGPMaster {

	private int blockCount;
	private Semaphore workSem;
	private Semaphore completeSem;
	private Queue<Set<AS>> workQueue;

	private static final int NUM_THREADS = 8;
	private static final int WORK_BLOCK_SIZE = 40;

	@SuppressWarnings("unchecked")
	public static HashMap<Integer, AS>[] buildBGPConnection(
			int chinaAvoidanceSize) throws IOException {

		/*
		 * Build AS map
		 */
		HashMap<Integer, AS> usefulASMap = ASTopoParser.doNetworkBuild();
		HashMap<Integer, AS> prunedASMap = ASTopoParser
				.doNetworkPrune(usefulASMap);

		/*
		 * Give everyone their self network
		 */
		for (AS tAS : usefulASMap.values()) {
			tAS.advPath(new BGPPath(tAS.getASN()));
		}

		/*
		 * dole out ases into blocks
		 */
		List<Set<AS>> asBlocks = new LinkedList<Set<AS>>();
		int currentBlockSize = 0;
		Set<AS> currentSet = new HashSet<AS>();
		for (AS tAS : usefulASMap.values()) {
			currentSet.add(tAS);
			currentBlockSize++;

			/*
			 * if it's a full block, send it to the list
			 */
			if (currentBlockSize >= BGPMaster.WORK_BLOCK_SIZE) {
				asBlocks.add(currentSet);
				currentSet = new HashSet<AS>();
				currentBlockSize = 0;
			}
		}
		/*
		 * add the partial set at the end if it isn't empty
		 */
		if (currentSet.size() > 0) {
			asBlocks.add(currentSet);
		}

		/*
		 * build the master and slaves, spin the slaves up
		 */
		BGPMaster self = new BGPMaster(asBlocks.size());
		List<Thread> slaveThreads = new LinkedList<Thread>();
		for (int counter = 0; counter < BGPMaster.NUM_THREADS; counter++) {
			slaveThreads.add(new Thread(new BGPSlave(self)));
		}
		for (Thread tThread : slaveThreads) {
			tThread.setDaemon(true);
			tThread.start();
		}

		long bgpStartTime = System.currentTimeMillis();
		System.out.println("Starting up the BGP processing.");

		int stepCounter = 0;
		boolean stuffToDo = true;
		boolean skipToMRAI = false;
		while (stuffToDo) {
			stuffToDo = false;

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

		BGPMaster.verifyConnected(usefulASMap);

		//self.tellDone();
		HashMap<Integer, AS>[] retArray = new HashMap[2];
		retArray[0] = usefulASMap;
		retArray[1] = prunedASMap;
		return retArray;
	}

	public BGPMaster(int blockCount) {
		this.blockCount = blockCount;
		this.workSem = new Semaphore(0);
		this.completeSem = new Semaphore(0);
		this.workQueue = new LinkedBlockingQueue<Set<AS>>();
	}

	public void addWork(Set<AS> workSet) {
		this.workQueue.add(workSet);
		this.workSem.release();
	}

	public Set<AS> getWork() throws InterruptedException {

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

	private static void verifyConnected(HashMap<Integer, AS> transitAS) {
		long startTime = System.currentTimeMillis();
		System.out.println("Starting connection verification");

		double examinedPaths = 0.0;
		double workingPaths = 0.0;
		for (AS tAS : transitAS.values()) {
			for (AS tDest : transitAS.values()) {
				if (tDest.getASN() == tAS.getASN()) {
					continue;
				}

				examinedPaths++;
				if (tAS.getPath(tDest.getASN()) != null) {
					workingPaths++;
				}
			}
		}

		startTime = System.currentTimeMillis() - startTime;
		System.out.println("Verification done in: " + startTime);
		System.out.println("Paths exist for " + workingPaths + " of "
				+ examinedPaths + " possible ("
				+ (workingPaths / examinedPaths * 100.0) + "%)");
	}

	//	private void tellDone() {
	//		this.workSem.notifyAll();
	//	}

}
