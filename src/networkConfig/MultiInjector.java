package networkConfig;

import java.util.HashMap;
import java.util.HashSet;

import bgp.BGPException;
import bgp.BGPRoute;

import router.BGPSpeaker;
import sim.ThunderWolf;

public class MultiInjector extends NetworkSeeder {

	private HashSet<Integer> targetASN;
	private int numberOfRoutes;
	private int pathLen;

	public MultiInjector(HashMap<Integer, BGPSpeaker> topoMap, HashSet<Integer> nonInjector,
			int number, int pathSize) {
		super(topoMap);

		this.targetASN = nonInjector;
		this.numberOfRoutes = number;
		this.pathLen = pathSize;

		if (!ThunderWolf.FASTANDLOOSE) {
			throw new BGPException(
					"BGP is going to blow up unless you're playing this in fast and loose mode.");
		}
	}

	@Override
	public void initialSeed() {

		int routeOffset = 0;
		
		for (BGPSpeaker tRouter : this.topoMap.values()) {
			
			/*
			 * Don't give the targets their own routes
			 */
			if(this.targetASN.contains(tRouter.getASN())){
				continue;
			}
			
			for (int routeCounter = 0; routeCounter < this.numberOfRoutes; routeCounter++) {
				routeOffset++;
				BGPRoute tRoute = new BGPRoute(routeOffset, 1);
				for (int pathCounter = 0; pathCounter < this.pathLen - 1; pathCounter++) {
					tRoute.appendASToPath(15000 + pathCounter);
				}
				tRouter.selfInstallPath(tRoute, 0);
			}
		}

	}
}
