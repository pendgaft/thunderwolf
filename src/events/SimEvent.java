package events;

import router.BGPSpeaker;
import logging.SimLogger;

public abstract class SimEvent implements Comparable<SimEvent> {

	private double eventTime;

	private int eventType;

	private BGPSpeaker myOwner;

	public static final int ROUTER_PROCESS = 1;
	public static final int MRAI_EVENT = 2;
	public static final int LOGGING_EVENT = 3;

	public static final double SECOND_MULTIPLIER = 1000.0;

	public SimEvent(double eTime, int eType, BGPSpeaker owner) {
		this.eventTime = eTime;
		this.eventType = eType;
		this.myOwner = owner;
	}

	public abstract void handleEvent(SimLogger theLogger);
	
	public abstract SimEvent repopulate();

	public double getEventTime() {
		return this.eventTime;
	}

	public int getEventType() {
		return this.eventType;
	}

	public BGPSpeaker getOwner() {
		return this.myOwner;
	}

	public int compareTo(SimEvent rhs) {
		double diff = this.eventTime - rhs.eventTime;
		if (diff < 0) {
			return -1;
		} else if (diff == 0) {
			return 0;
		} else {
			return 1;
		}
	}

	public String toString() {
		StringBuilder strBuilder = new StringBuilder();
		strBuilder.append("Event type: ");

		if (this.eventType == SimEvent.MRAI_EVENT) {
			strBuilder.append("MRAI");
		} else if (this.eventType == SimEvent.ROUTER_PROCESS) {
			strBuilder.append("Process");
		} else if (this.eventType == SimEvent.LOGGING_EVENT) {
			strBuilder.append("Logging");
		}

		strBuilder.append(" Owner: ");
		if (this.myOwner != null) {
			strBuilder.append(Integer.toString(this.myOwner.getASN()));
		} else {
			strBuilder.append("Simulator");
		}
		return strBuilder.toString();
	}

	public int hashCode() {
		int salt = -1;
		if(this.myOwner != null){
			salt = this.myOwner.getASN();
		}
		return salt + this.eventType * 100000 + (int) this.eventTime;
	}

	public boolean equals(Object rhs) {
		SimEvent rhsEvent = (SimEvent) rhs;
		boolean ownerTest = false;
		if(this.myOwner == null){
			ownerTest = (rhsEvent.myOwner == null);
		}else{
			ownerTest = rhsEvent.getOwner().getASN() == this.getOwner().getASN();
		}
		return ownerTest && this.eventType == rhsEvent.eventType
				&& this.eventTime == rhsEvent.eventTime;
	}
}
