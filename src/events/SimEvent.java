package events;

import router.BGPSpeaker;
import logging.SimLogger;

public abstract class SimEvent implements Comparable<SimEvent> {

	private long eventTime;

	private int eventType;
	
	private BGPSpeaker myOwner;

	public static final int ROUTER_PROCESS = 1;
	public static final int MRAI_EVENT = 2;

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
}
