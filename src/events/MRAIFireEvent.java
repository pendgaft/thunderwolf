package events;

import router.BGPSpeaker;
import logging.SimLogger;

public class MRAIFireEvent extends SimEvent{
	
	public MRAIFireEvent(double time, BGPSpeaker self){
		super(time, SimEvent.MRAI_EVENT, self);
	}

	public void handleEvent(SimLogger theLogger) {
		this.getOwner().mraiExpire();
	}
	
	public SimEvent repopulate(){
		return this.getOwner().getNextMRAI();
	}
}
