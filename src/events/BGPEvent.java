package events;

import topo.AS;

public class BGPEvent implements Comparable<BGPEvent>{

	public static final int QUEUE_DONE_TYPE = 1;
	public static final int MRAI_TYPE = 2;
	
	private long eventTime;
	private AS parent;
	private int type;
	
	public BGPEvent(long time, AS owner, int etype){
		this.eventTime = time;
		this.parent = owner;
		this.type = etype;
	}
	
	@Override
	public int compareTo(BGPEvent o) {
		long fom = this.eventTime - o.eventTime;
		if(fom < 0){
			return -1;
		} else if(fom == 0){
			return 0;
		} else{
			return 1;
		}
	}
	
	public boolean equals(Object rhs){
		BGPEvent rhsEvent = (BGPEvent)rhs;
		return this.eventTime == rhsEvent.eventTime && this.type == rhsEvent.type && this.parent.getASN() == rhsEvent.parent.getASN();
	}
	
	public long getEventTime(){
		return this.eventTime;
	}
	
	public AS getParent(){
		return this.parent;
	}
	
	public void runEvent(){
		if(this.type == QUEUE_DONE_TYPE){
			long nextQueuePop = this.parent.tendQueues(this.eventTime);
			if(nextQueuePop != Long.MAX_VALUE){
				//TODO input next queue done event
			}
		}
		else if(this.type == MRAI_TYPE){
			this.parent.mraiExpire(this.eventTime);
			//TODO this needs to add another mrai event to the queue
		}
		else{
			System.err.println("Bad event type: " + this.type);
			System.exit(2);
		}
	}
	
}
