package sim;

import java.util.Set;
import topo.AS;

public class BGPSlave implements Runnable {

	private BGPMaster workSource;

	public BGPSlave(BGPMaster daBoss) {
		this.workSource = daBoss;
	}

	@Override
	public void run() {
		try {
			while (true) {

				/*
				 * Fetch work from master
				 */
				Set<AS> workSet = this.workSource.getWork();

				/*
				 * there is work to do, please do it
				 */
				for (AS tAS : workSet) {
					tAS.handleAdvertisement();
				}
				
				this.workSource.reportWorkDone();
			}
		} catch (InterruptedException e) {
			/*
			 * Done w/ work, nothing to report here, just die
			 */
		}

	}

}
