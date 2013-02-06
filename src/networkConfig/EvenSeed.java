package networkConfig;

import java.util.*;

import bgp.BGPRoute;

import router.BGPSpeaker;

/**
 * Creates one network for each AS that has 1 CIDR inside it. This is used for
 * testing purposes for the most part.
 * 
 * @author pendgaft
 * 
 */
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
