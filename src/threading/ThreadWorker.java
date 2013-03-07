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
				//System.out.println("Pulled: "  + task.toString() + " my id " + this.myID);
				task.handleEvent(this.workSource.getLoggingHook());
				this.workSource.reportWorkDone(task);
				//System.out.println("Returned: "  + task.toString() + " my id " + this.myID);
			}
		} catch (InterruptedException e) {
			System.out.println("Slave thread dying.");
		}

	}

}