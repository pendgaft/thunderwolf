package sim;

import java.util.concurrent.*;

import events.BGPEvent;

public class BGPMasterEventDecomp extends BGPMaster{

	//FIXME this might be saner to be priority
	private ConcurrentLinkedQueue<BGPEvent> workQueue;
	private Semaphore workAvailSem;
	
	public BGPMasterEventDecomp(int threadCount) {
		super(threadCount);
		
		this.workQueue = new ConcurrentLinkedQueue<BGPEvent>();
		this.workAvailSem = new Semaphore(0);
	}

	@Override
	public BGPEvent getWork(int threadID) throws InterruptedException {
		this.workAvailSem.acquire();
		return this.workQueue.poll();
	}

	@Override
	protected void makeWorkReady(BGPEvent readyEvent) {
		// TODO Auto-generated method stub
		
	}

}
