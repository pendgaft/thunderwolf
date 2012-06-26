package events;

import router.BGPSpeaker;

public class MRAIFireEvent extends SimEvent{
	
	public MRAIFireEvent(long time, BGPSpeaker self){
		super(time, SimEvent.MRAI_EVENT, self);
	}

	public void handleEvent() {
		this.getOwner().mraiExpire(this.getEventTime());
	}
}
