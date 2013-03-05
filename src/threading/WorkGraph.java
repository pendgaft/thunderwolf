package threading;

import java.io.IOException;
import java.util.*;

import router.AS;
import router.ASTopoParser;
import router.BGPSpeaker;
import util.Assertions;

public class WorkGraph {

	private HashSet<WorkNode> topLvlNodes;
	private List<WorkNode> allNodes;
	private List<Integer> mraiOrderList;

	public WorkGraph(HashMap<Integer, BGPSpeaker> topoMap) {

		this.topLvlNodes = new HashSet<WorkNode>();
		this.allNodes = new LinkedList<WorkNode>();

		/*
		 * Line the ASes up in order of MRAI fire and walk through building
		 * nodes
		 */
		this.mraiOrderList = this.buildMRAIOrder(topoMap);
		for (int counter = 0; counter < this.mraiOrderList.size(); counter++) {
			int currAS = this.mraiOrderList.get(counter);
			Set<Integer> currNeighbors = topoMap.get(currAS).getASObject()
					.getNeighbors();
			WorkNode newNode = new WorkNode(currAS, currNeighbors);

			Set<WorkNode> hasVisited = new HashSet<WorkNode>();

			/*
			 * Consider all nodes, finding dependencies, we can skip some tests,
			 * as I'm dependent on all of my parent's parents
			 */
			for(int innerCounter = this.allNodes.size() - 1; innerCounter >= 0; innerCounter--){
				WorkNode currNode = this.allNodes.get(innerCounter);
				
				if(hasVisited.contains(currNode)){
					continue;
				}

				/*
				 * If the node in question overlapps with the new node then
				 * build the dependency links
				 */
				if (currNode.contains(currAS)
						|| currNode.contains(currNeighbors)) {
					newNode.addParent(currNode);
					currNode.addChild(newNode);

					/*
					 * Time to pull parents out of this mess since we're
					 * trivially dependent on grandparents
					 */
					hasVisited.addAll(this.parentTraversal(currNode));
				}
				
				hasVisited.add(currNode);
			}

			/*
			 * Add references to the new node in the master data structure
			 */
			this.allNodes.add(newNode);
		}
	}
	
	/**
	 * Called into first to get the work nodes that can be spun up at each pass
	 * @return
	 */
	public Set<WorkNode> getRoots(){
		return this.topLvlNodes;
	}
	
	public void resetDoneStatus(){
		for(WorkNode tNode: this.allNodes){
			tNode.resetRan();
		}
	}

	private List<Integer> buildMRAIOrder(HashMap<Integer, BGPSpeaker> topo) {
		List<Integer> outList = new ArrayList<Integer>();
		HashSet<Integer> used = new HashSet<Integer>();

		while (used.size() < topo.size()) {
			long min = Long.MAX_VALUE;
			int winner = 0;

			for (BGPSpeaker tRouter : topo.values()) {
				if (used.contains(tRouter.getASN())) {
					continue;
				}

				if (tRouter.getNextMRAI() < min) {
					min = tRouter.getNextMRAI();
					winner = tRouter.getASN();
				}
			}

			used.add(winner);
			outList.add(winner);
		}

		return outList;
	}
	
	private Set<WorkNode> parentTraversal(WorkNode root){
		List<WorkNode> toTraverse = new LinkedList<WorkNode>();
		toTraverse.add(root);
		Set<WorkNode> parents = new HashSet<WorkNode>();
		
		while(toTraverse.size() > 0){
			WorkNode currNode = toTraverse.remove(0);
			Set<WorkNode> currParents = currNode.getParents();
			for(WorkNode tNode: currParents){
				if(parents.contains(tNode)){
					continue;
				}
				parents.add(tNode);
				toTraverse.add(tNode);
			}
		}
		
		return parents;
	}
	
	public static void main(String args[]) throws IOException{
		HashMap<Integer, BGPSpeaker> topoMap = new HashMap<Integer, BGPSpeaker>();
		HashMap<Integer, AS> asMap = new HashMap<Integer, AS>();
		for(int counter = 1; counter < 9; counter++){
			asMap.put(counter, new AS(counter, 1));
		}
		asMap.get(1).addRelation(asMap.get(6), 1);
		asMap.get(1).addRelation(asMap.get(4), -1);
		asMap.get(2).addRelation(asMap.get(8), 1);
		asMap.get(3).addRelation(asMap.get(6), -1);
		asMap.get(3).addRelation(asMap.get(7), -1);
		asMap.get(4).addRelation(asMap.get(7), 1);
		asMap.get(4).addRelation(asMap.get(8), -1);
		asMap.get(5).addRelation(asMap.get(8), -1);
		
		for(int counter = 1; counter < 9; counter++){
			topoMap.put(counter, new BGPSpeaker(asMap.get(counter), topoMap));
			topoMap.get(counter).setOpeningMRAI(counter);
		}
		
		WorkGraph theGraph = new WorkGraph(topoMap);
		for(WorkNode tNode: theGraph.allNodes){
			System.out.println(tNode.toString());
		}
	}
}
