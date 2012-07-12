package networkConfig;

import java.util.*;

import bgp.BGPRoute;

import router.BGPSpeaker;

public class EvenSeed extends NetworkSeeder {

	public EvenSeed(HashMap<Integer, BGPSpeaker> activeTopo) {
		super(activeTopo);
	}

	@Override
	public void initialSeed() {
		for (BGPSpeaker tAS : this.topoMap.values()) {
			tAS.advPath(new BGPRoute(tAS.getASN(), 1), 0);
		}
	}

}
