package events;

import topo.AS;

public class BGPEvent implements Comparable<BGPEvent>{

	public static final int PROCESS_TYPE = 1;
	
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
		if(this.type == PROCESS_TYPE){
			
		}
		else{
			System.err.println("Bad event type: " + this.type);
			System.exit(2);
		}
	}
	
}
