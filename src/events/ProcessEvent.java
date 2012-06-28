package events;

import router.BGPSpeaker;

public class ProcessEvent extends SimEvent {
	
	private long endTime;

	public ProcessEvent(long startTime, long endTime, BGPSpeaker owner) {
		super(startTime, SimEvent.ROUTER_PROCESS, owner);
		this.endTime = endTime;
	}

	public void handleEvent() {
		this.getOwner().runForwardTo(this.getEventTime(), this.endTime);
	}

	public boolean equals(Object rhs) {
		ProcessEvent rhsEvent;

		/*
		 * It's going to be thrown around in a queue of mixed event types, so
		 * sanity check that these two things are even the same event type
		 */
		try {
			rhsEvent = (ProcessEvent) rhs;
		} catch (ClassCastException e) {
			return false;
		}

		return this.getEventTime() == rhsEvent.getEventTime()
				&& this.getOwner().getASN() == rhsEvent.getOwner().getASN();
	}
}
