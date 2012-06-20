package threading;

import events.SimEvent;

public class ThreadWorker implements Runnable {

	private BGPMaster workSource;

	public ThreadWorker(BGPMaster daBoss) {
		this.workSource = daBoss;
	}

	@Override
	public void run() {
		try {
			while (true) {

				/*
				 * Fetch work from master, do it, report back
				 */
				SimEvent task = this.workSource.getWork();
				task.handleEvent();
				this.workSource.reportWorkDone();
			}
		} catch (InterruptedException e) {
			System.out.println("Slave thread dying.");
		}

	}

}