package events;

import router.BGPSpeaker;
import logging.SimLogger;

public class ProcessEvent extends SimEvent {
	
	
	public ProcessEvent(double eventTime, BGPSpeaker owner) {
		super(eventTime, SimEvent.ROUTER_PROCESS, owner);
	}

	public void handleEvent(SimLogger theLogger) {
		this.getOwner().radiateCleanup(1);
	}
	
	public SimEvent repopulate(){
		this.getOwner().handleProcessingEventCompleted();
		return this.getOwner().getNextProcessEvent();
	}
}
