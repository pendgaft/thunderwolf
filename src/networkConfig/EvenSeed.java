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

    private static final int NET_SIZE = 100;

	public EvenSeed(HashMap<Integer, BGPSpeaker> activeTopo) {
		super(activeTopo);
	}

	@Override
	public void initialSeed() {
		for (BGPSpeaker tAS : this.topoMap.values()) {
			tAS.selfInstallPath(new BGPRoute(tAS.getASN(), EvenSeed.NET_SIZE));
		}
	}

}
