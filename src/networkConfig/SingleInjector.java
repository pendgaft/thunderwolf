package networkConfig;

import java.util.*;

import bgp.BGPRoute;
import router.BGPSpeaker;

public class SingleInjector extends NetworkSeeder {
	
	private int injectingASN;
	private int numberOfRoutes;
	
	public SingleInjector(HashMap<Integer, BGPSpeaker> topoMap, int injectingASN, int number){
		super(topoMap);
		this.injectingASN = injectingASN;
		this.numberOfRoutes = number;
	}

	@Override
	public void initialSeed() {
		BGPSpeaker injectorTarget = this.topoMap.get(this.injectingASN);
		
		for(int counter = 0; counter < this.numberOfRoutes; counter++){
			injectorTarget.advPath(new BGPRoute(counter + 1, 1), 0);
		}

	}

}
