package threading;

import java.util.*;

public class WorkNode {
	
	private int advertiser;
	private Set<Integer> adjacentAS;
	
	private Set<WorkNode> parents;
	private Set<WorkNode> children;
	private boolean ran;
	
	public WorkNode(int adv, Set<Integer> adjacent){
		this.advertiser = adv;
		this.adjacentAS = adjacent;
		
		this.parents = new HashSet<WorkNode>();
		this.children = new HashSet<WorkNode>();
		this.ran = false;
	}
	
	
	public void addChild(WorkNode newChild){
		this.children.add(newChild);
	}
	
	public void addParent(WorkNode newParent){
		this.parents.add(newParent);
	}
	
	public Set<WorkNode> getParents(){
		return this.parents;
	}
	
	public boolean contains(Set<Integer> values){
		for(int tInt: values){
			if(this.contains(tInt)){
				return true;
			}
		}
		
		return false;
	}
	
	public boolean contains(int value){
		return this.adjacentAS.contains(value) || this.advertiser == value;
	}
	
	public void toggleRan(){
		this.ran = true;
	}
	
	public void resetRan(){
		this.ran = false;
	}
	
	public boolean hasRan(){
		return this.ran;
	}

}
