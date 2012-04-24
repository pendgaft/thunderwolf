package sim;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import events.BGPEvent;

public class BGPMasterASDecomp extends BGPMaster {

	//FIXME this might be saner to be prority queue
	private HashMap<Integer, ConcurrentLinkedQueue<BGPEvent>> workQueues;
	private HashMap<Integer, Semaphore> workControls;

	private HashMap<Integer, HashSet<Integer>> workSlices;

	public BGPMasterASDecomp(int threadCount, HashSet<Integer> asns) {
		super(threadCount);

		this.workQueues = new HashMap<Integer, ConcurrentLinkedQueue<BGPEvent>>();
		this.workControls = new HashMap<Integer, Semaphore>();
		this.workSlices = new HashMap<Integer, HashSet<Integer>>();

		/*
		 * Setup work queues
		 */
		for (int counter = 0; counter < threadCount; counter++) {
			this.workQueues.put(counter, new ConcurrentLinkedQueue<BGPEvent>());
			this.workControls.put(counter, new Semaphore(0));
			this.workSlices.put(counter, new HashSet<Integer>());
		}

		/*
		 * decompose the work to threads
		 */
		int pos = 0;
		for (Integer tASN : asns) {
			this.workSlices.get(pos % threadCount).add(tASN);
			pos++;
		}
	}

	@Override
	public BGPEvent getWork(int threadID) throws InterruptedException {
		this.workControls.get(threadID).acquire();
		return this.workQueues.get(threadID).poll();
	}

	@Override
	protected void makeWorkReady(BGPEvent readyEvent) {
		// TODO Auto-generated method stub
		
	}

}
