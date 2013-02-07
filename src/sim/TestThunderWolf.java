package sim;

import java.io.IOException;
import java.util.List;
import java.util.LinkedList;

import bgp.BGPRoute;

import util.Assertions;

public class TestThunderWolf extends ThunderWolf {

	private Assertions tester;
	
	private static final String TEST_TOPO_FILE = "5node";
	
	public TestThunderWolf(String topologyFile) throws IOException {
		super(topologyFile, ThunderWolf.Mode.EVEN);
		this.tester = new Assertions();
	}
	
	public void runTheTests() throws IOException{
		/*
		 * Step one, run the simulation.
		 */
		super.runSimulation();
		
		/*
		 * Test size of tables
		 */
		this.tester.assertEqual(super.routerMap.get(1).calcTotalRouteCount(), 7, "FIB size AS 1");
		this.tester.assertEqual(super.routerMap.get(2).calcTotalRouteCount(), 5, "FIB size AS 2");
		this.tester.assertEqual(super.routerMap.get(3).calcTotalRouteCount(), 7, "FIB size AS 3");
		this.tester.assertEqual(super.routerMap.get(4).calcTotalRouteCount(), 9, "FIB size AS 4");
		this.tester.assertEqual(super.routerMap.get(5).calcTotalRouteCount(), 9, "FIB size AS 5");
		
		/*
		 * Test best paths
		 */
		int[] paths1 = {1, 2, 3, 2, 2};
		this.tester.assertEqualInOrder(this.buildBestPathList(1), Assertions.autoBoxIntArray(paths1), "Next hops AS 1");
		int[] paths2 = {1, 2, 1, 4, 5};
		this.tester.assertEqualInOrder(this.buildBestPathList(2), Assertions.autoBoxIntArray(paths2), "Next hops AS 2");
		int[] paths3 = {1, 1, 3, 4, 5};
		this.tester.assertEqualInOrder(this.buildBestPathList(3), Assertions.autoBoxIntArray(paths3), "Next hops AS 3");
		int[] paths4 = {2, 2, 3, 4, 2};
		this.tester.assertEqualInOrder(this.buildBestPathList(4), Assertions.autoBoxIntArray(paths4), "Next hops AS 4");
		int[] paths5 = {2, 2, 3, 2, 5};
		this.tester.assertEqualInOrder(this.buildBestPathList(5), Assertions.autoBoxIntArray(paths5), "Next hops AS 5");
		
		/*
		 * Test available paths
		 */
		List<BGPRoute> pathList = null;
		pathList = super.routerMap.get(3).getAllPathsTo(4);
		this.tester.assertEqual(pathList.size(), 2, "Number of paths from 3 to 4");
		for(BGPRoute tRoute: pathList){
			if(tRoute.getPathLength() == 1){
				int[] path = {4};
				this.tester.assertEqualInOrder(Assertions.arrayHelperInt(tRoute.fetchRawPath()), Assertions.autoBoxIntArray(path), "Direct path to 4");
			}else if(tRoute.getPathLength() == 3){
				int[] path = {1, 2, 4};
				this.tester.assertEqualInOrder(Assertions.arrayHelperInt(tRoute.fetchRawPath()), Assertions.autoBoxIntArray(path), "Long path to 4");
			}else{
				this.tester.recordOutsideAssertion(false, "unexpected size on path in table: " + tRoute.getPathLength());
			}
		}
		
		
		/*
		 * Time to print results
		 */
		System.out.println(" ");
		tester.printReport(true, System.out);
	}
	
	private Integer[] buildBestPathList(int asn){
		List<Integer> nextHops = new LinkedList<Integer>();
		
		for(int counter = 1; counter < 6; counter++){
			nextHops.add(super.routerMap.get(asn).getPath(counter).getNextHop(asn));
		}
		
		return Assertions.arrayHelperInt(nextHops);
	}


	/**
	 * Derpy little main function, not args needed, he knows what to do.
	 * 
	 * @param args - not needed, ignored
	 * @throws IOException - protip: if you see this the tests failed....
	 */
	public static void main(String[] args) throws IOException{
		TestThunderWolf me = new TestThunderWolf(TestThunderWolf.TEST_TOPO_FILE);
		me.runTheTests();
	}

}
