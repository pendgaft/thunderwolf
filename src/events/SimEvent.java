package events;

import router.BGPSpeaker;
import logging.SimLogger;

public abstract class SimEvent implements Comparable<SimEvent> {

	private long eventTime;

	private int eventType;
	
	private BGPSpeaker myOwner;

	public static final int ROUTER_PROCESS = 1;
	public static final int MRAI_EVENT = 2;
	
	public static final long SECOND_MULTIPLIER = 1000;

	public SimEvent(long eTime, int eType, BGPSpeaker owner) {
		this.eventTime = eTime;
		this.eventType = eType;
		this.myOwner = owner;
	}

	public abstract void handleEvent(SimLogger theLogger);

	public long getEventTime() {
		return this.eventTime;
	}

	public int getEventType() {
		return this.eventType;
	}
	
	public BGPSpeaker getOwner(){
		return this.myOwner;
	}

	public int compareTo(SimEvent rhs) {
		long diff = this.eventTime - rhs.eventTime;
		if (diff < 0) {
			return -1;
		} else if (diff == 0) {
			return 0;
		} else {
			return 1;
		}
	}
	
	public String toString(){
		StringBuilder strBuilder = new StringBuilder();
		strBuilder.append("Event type: " );
		
		if(this.eventType == SimEvent.MRAI_EVENT){
			strBuilder.append("MRAI");
		} else if(this.eventType == SimEvent.ROUTER_PROCESS){
			strBuilder.append("Process");
		}
		
		strBuilder.append(" Owner: ");
		strBuilder.append(Integer.toString(this.myOwner.getASN()));
		return strBuilder.toString();
	}
}
