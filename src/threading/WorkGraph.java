package threading;

import java.util.*;
import router.BGPSpeaker;

public class WorkGraph {

	private HashSet<WorkNode> topLvlNodes;
	private HashSet<WorkNode> allNodes;
	private HashSet<WorkNode> leafNodes;

	public WorkGraph(HashMap<Integer, BGPSpeaker> topoMap) {

		this.topLvlNodes = new HashSet<WorkNode>();
		this.allNodes = new HashSet<WorkNode>();
		this.leafNodes = new HashSet<WorkNode>();

		/*
		 * Line the ASes up in order of MRAI fire and walk through building
		 * nodes
		 */
		List<Integer> mraiOrderList = this.buildMRAIOrder(topoMap);
		for (int counter = 0; counter < mraiOrderList.size(); counter++) {
			int currAS = mraiOrderList.get(counter);
			Set<Integer> currNeighbors = topoMap.get(currAS).getASObject()
					.getNeighbors();
			WorkNode newNode = new WorkNode(currAS, currNeighbors);

			List<WorkNode> needToVisit = new LinkedList<WorkNode>();
			Set<WorkNode> hasVisited = new HashSet<WorkNode>();
			needToVisit.addAll(this.leafNodes);

			/*
			 * Consider all nodes, finding dependencies, we can skip some tests,
			 * as I'm dependent on all of my parent's parents
			 */
			while (hasVisited.size() != this.allNodes.size()) {
				WorkNode currNode = needToVisit.remove(0);
				
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
					if (this.leafNodes.contains(currNode)) {
						this.leafNodes.remove(currNode);
					}

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
			this.leafNodes.add(newNode);
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
}
