package networkConfig;

import java.util.*;

import bgp.BGPException;
import bgp.BGPRoute;
import router.BGPSpeaker;
import sim.ThunderWolf;

public class SingleInjector extends NetworkSeeder {

	private int injectingASN;
	private int numberOfRoutes;
	private int pathLen;

	public SingleInjector(HashMap<Integer, BGPSpeaker> topoMap,
			int injectingASN, int number, int pathSize) {
		super(topoMap);
		this.injectingASN = injectingASN;
		this.numberOfRoutes = number;
		this.pathLen = pathSize;

		if (!ThunderWolf.FASTANDLOOSE) {
			throw new BGPException(
					"BGP is going to blow up unless you're playing this in fast and loose mode.");
		}
	}

	@Override
	public void initialSeed() {
		BGPSpeaker injectorTarget = this.topoMap.get(this.injectingASN);

		for (int routeCounter = 0; routeCounter < this.numberOfRoutes; routeCounter++) {
			BGPRoute tRoute = new BGPRoute(routeCounter + 1, 1);
			for (int pathCounter = 0; pathCounter < this.pathLen - 1; pathCounter++) {
				tRoute.appendASToPath(15000 + pathCounter);
			}
			injectorTarget.selfInstallPath(tRoute, 0);
		}

	}

}
