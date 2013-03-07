package threading;

import events.SimEvent;

public class ThreadWorker implements Runnable {

	private BGPMaster workSource;
	private int myID;

	public ThreadWorker(BGPMaster daBoss, int id) {
		this.workSource = daBoss;
		this.myID = id;
	}
	
	@Override
	public void run() {
		try {
			while (true) {

				/*
				 * Fetch work from master, do it, report back
				 */
				SimEvent task = this.workSource.getWork();
				task.handleEvent(this.workSource.getLoggingHook());
				this.workSource.reportWorkDone(task);
			}
		} catch (InterruptedException e) {
			System.out.println("Slave thread dying.");
		}

	}

}