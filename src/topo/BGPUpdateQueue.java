package topo;

import java.util.concurrent.ConcurrentLinkedQueue;

public class BGPUpdateQueue {
	
	private BGPUpdate currentlyRunning;
	private BGPUpdate completedUpdate;
	private ConcurrentLinkedQueue<BGPUpdate> incUpdateQueue;
	
	private long lastTimeUpdated;
	
	//TODO need a last updated var?

	public BGPUpdateQueue(){
		this.incUpdateQueue = new ConcurrentLinkedQueue<BGPUpdate>();
		this.currentlyRunning = null;
		this.completedUpdate = null;
		this.lastTimeUpdated = 0;
	}
	
	public boolean addUpdate(BGPUpdate incUpdate){
		this.incUpdateQueue.add(incUpdate);
		if(!this.isRunning()){
			return true;
		}
		
		return false;
	}
	
	public boolean isRunning(){
		return this.currentlyRunning  != null;
	}
	
	public void switchOn(long currentTime){
		if(!this.isRunning() && !this.incUpdateQueue.isEmpty()){
			this.currentlyRunning = this.incUpdateQueue.poll();
		}
		this.lastTimeUpdated = currentTime;
	}
	
	public long getTTC(double cpuFraction){
		if(!this.isRunning()){
			return Long.MAX_VALUE;
		}
		
		/*
		 * get the fraction left, multiply by total run time, scale for cpu fraction
		 */
		double fracLeft = 1.0 - this.currentlyRunning.getFractionDone();
		double runTime = (double)this.currentlyRunning.getRuntime() * fracLeft;
		return (long)Math.ceil(runTime / cpuFraction);
	}
	
	
	public BGPUpdate getPoppedUpdate(){
		BGPUpdate ret = this.completedUpdate;
		this.completedUpdate = null;
		return ret;
	}
	
	public boolean advanceTime(long time, double cpuFraction){
		/*
		 * If everyone has been advanced, just move on with life
		 */
		if(time == this.lastTimeUpdated || !this.isRunning()){
			return false;
		}
		
		double timeRan = Math.ceil((time - this.lastTimeUpdated) * cpuFraction);
		double fracAdded = timeRan / this.currentlyRunning.getRuntime();
		if(this.currentlyRunning.advanceDone(fracAdded)){
			this.completedUpdate = this.currentlyRunning;
			this.currentlyRunning = this.incUpdateQueue.poll();
		}
		
		this.lastTimeUpdated = time;
		return this.completedUpdate != null;
	}

}
