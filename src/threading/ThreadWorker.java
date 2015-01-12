package threading;

import java.util.*;

import router.BGPSpeaker;
import events.ProcessEvent;

public class ThreadWorker implements Runnable {

	private FlowDriver workSource;
	private int myID;
	private double lastTimeAdvance;
	private Set<BGPSpeaker> ownedNodes;

	public ThreadWorker(FlowDriver daBoss, int id) {
		this.workSource = daBoss;
		this.myID = id;
		this.lastTimeAdvance = 0.0;
		this.ownedNodes = new HashSet<BGPSpeaker>();
	}

	public void giveChild(BGPSpeaker ownedNode) {
		this.ownedNodes.add(ownedNode);
	}

	@Override
	public void run() {
		try {
			while (true) {

				/*
				 * Wait for master to tell us we're moving forward, advance all
				 * nodes...
				 */
				double nextTimePoint = this.workSource.getNextTimeAdvnace();
				for (BGPSpeaker tChild : this.ownedNodes) {
					tChild.queueAdvance(this.lastTimeAdvance, nextTimePoint);
				}

				/*
				 * Update where we advanced to, phone home to say we're done
				 */
				this.lastTimeAdvance = nextTimePoint;
				this.workSource.reportWorkDone();
				
				this.workSource.waitForScanQueues();
				for(BGPSpeaker tChild: this.ownedNodes){
					tChild.handleIncomingQueueCleanup();
				}
				this.workSource.reportWorkDone();

				this.workSource.waitForEventAdjust();
				for (BGPSpeaker tChild : this.ownedNodes) {
					tChild.updateEstimatedCompletionTimes();
				}
				this.workSource.reportWorkDone();

				this.workSource.waitForProcessEventUpdate();
				for (BGPSpeaker tChild : this.ownedNodes) {
					ProcessEvent evictEvent = tChild.checkIfProcessingEventNeedsUpdating(this.lastTimeAdvance);
					if (evictEvent != null) {
						this.workSource.replaceProcessEvent(evictEvent, tChild.getNextProcessEvent());
					}
				}
				this.workSource.reportWorkDone();
			}
		} catch (InterruptedException e) {
			System.err.println("Slave thread " + this.myID + " dying.");
		}

	}

}