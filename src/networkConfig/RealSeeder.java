package networkConfig;

import java.util.*;

import bgp.BGPRoute;
import router.AS;
import router.BGPSpeaker;

/**
 * Class that does realistic seeding of IP blocks. This includes the multiple IP
 * blocks that an AS owns as well as the IP blocks that its pruned children own
 * 
 * 
 */
public class RealSeeder extends NetworkSeeder {

	private HashMap<Integer, AS> fullTopo;

	public RealSeeder(HashMap<Integer, BGPSpeaker> activeTopo, HashMap<Integer, AS> unprunedTopo) {
		super(activeTopo);
		this.fullTopo = unprunedTopo;
	}

	public void initialSeed() {
		/*
		 * Handles handing out the correct number of IP blocks that each AS
		 * owns, assuming of course that those numbers are accurate
		 */
		for (BGPSpeaker tAS : this.topoMap.values()) {
			tAS.advPath(new BGPRoute(tAS.getASN(), tAS.getASObject().getCIDRSize()), 0);
		}

		/*
		 * Now we need to look for all ASes that got dropped from the topology,
		 * and add their networks to the mix, this will be a little bit
		 * ad-hoc-ish but it should be close enough without killing us in the
		 * the memory department
		 */
		//XXX turned off for now to sanity check mem usage
//		for(AS tAS: this.fullTopo.values()){
//			/*
//			 * If we're in the active topo skip this part
//			 */
//			if(super.topoMap.containsKey(tAS.getASN())){
//				continue;
//			}
//			
//			for(Integer tProv: tAS.getProviders()){
//				super.topoMap.get(tProv).advPath(new BGPRoute(tAS.getASN(), tAS.getCIDRSize()), 0);
//			}
//		}
	}
}
