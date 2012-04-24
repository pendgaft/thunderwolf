package sim;

import events.BGPEvent;

public class BGPSlave implements Runnable {

	private BGPMaster workSource;
	private int id;

	public BGPSlave(BGPMaster daBoss, int myID) {
		this.workSource = daBoss;
		this.id = myID;
	}

	@Override
	public void run() {
		try {
			while (true) {

				/*
				 * Fetch work from master
				 */
				BGPEvent event = this.workSource.getWork(this.id);

				/*
				 * there is work to do, please do it
				 */
				event.runEvent();
				
				/*
				 * Report work done, needed for bookkeeping
				 */
				this.workSource.reportWorkDone(event);
			}
		} catch (InterruptedException e) {
			/*
			 * Done w/ work, nothing to report here, just die
			 */
		}

	}
}
