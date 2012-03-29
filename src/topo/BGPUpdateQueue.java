package topo;

import java.util.*;

public class BGPUpdateQueue {
	
	private boolean isRunning;
	private long ttc;
	private Queue<BGPUpdate> incUpdateQueue;

	public BGPUpdateQueue(){
		this.incUpdateQueue = new LinkedList<BGPUpdate>();
		this.ttc = 0;
		this.isRunning = false;
	}
	
	public synchronized boolean addUpdate(BGPUpdate incUpdate){
		this.incUpdateQueue.add(incUpdate);
		if(!this.isRunning){
			this.isRunning = true;
			return true;
		}
		
		return false;
	}
	
	public boolean isRunning(){
		return this.isRunning;
	}
	
	public long getTTC(){
		return this.ttc;
	}
	
	public void setTTC(long newTTC){ 
		this.ttc = newTTC;
	}
	
	public BGPUpdate getNextUpdate(){
		
	}
}
