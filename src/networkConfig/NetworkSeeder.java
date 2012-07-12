package networkConfig;

import java.util.*;

import router.BGPSpeaker;

public abstract class NetworkSeeder {

	protected HashMap<Integer, BGPSpeaker> topoMap;
	
	public NetworkSeeder(HashMap<Integer, BGPSpeaker> activeTopo){
		this.topoMap = activeTopo;
	}
	
	public abstract void initialSeed();
}
