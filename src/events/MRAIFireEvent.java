package events;

import router.BGPSpeaker;

public class MRAIFireEvent extends SimEvent{

	
	private BGPSpeaker owner;
	
	public MRAIFireEvent(long time, BGPSpeaker self){
		super(time, SimEvent.MRAI_EVENT);
		this.owner = self;
	}

	public void handleEvent() {
		this.owner.mraiExpire(this.getEventTime());
	}
}
