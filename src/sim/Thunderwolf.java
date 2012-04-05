package sim;

import java.util.HashMap;
import java.io.IOException;

import topo.*;

public class Thunderwolf {

	/**
	 * @param args
	 */
	public static void main(String[] args) throws IOException{
		HashMap<Integer, AS>[] topoArray = BGPMaster.buildBGPConnection();
		HashMap<Integer, AS> liveTopo = topoArray[0];
		HashMap<Integer, AS> prunedTopo = topoArray[1];
		System.out.println("Topo built and BGP converged.");

	}

}
