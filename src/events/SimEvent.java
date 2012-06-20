package events;

public abstract class SimEvent implements Comparable<SimEvent> {

	private long eventTime;

	private int eventType;

	public static final int ROUTER_PROCESS = 1;
	public static final int MRAI_EVENT = 2;

	public SimEvent(long eTime, int eType) {
		this.eventTime = eTime;
		this.eventType = eType;
	}

	public abstract void handleEvent();

	public long getEventTime() {
		return this.eventTime;
	}

	public int getEventType() {
		return this.eventType;
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
