package threading;

import java.util.*;

public class WorkNode {
	
	private int advertiser;
	private Set<Integer> adjacentAS;
	private int outstandingSubtasks;
	
	private Set<WorkNode> parents;
	private Set<WorkNode> children;
	private boolean ran;
	
	public WorkNode(int adv, Set<Integer> adjacent){
		this.advertiser = adv;
		this.adjacentAS = adjacent;
		
		this.parents = new HashSet<WorkNode>();
		this.children = new HashSet<WorkNode>();
		this.ran = false;
		this.outstandingSubtasks = adjacent.size() + 1;
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
	//FIXME holy shit this needs some locking protection!
	public Set<WorkNode> toggleRan(){
		this.ran = true;
		
		Set<WorkNode> readyToGo = new HashSet<WorkNode>();
		for(WorkNode tChild: this.children){
			if(tChild.isReady()){
				readyToGo.add(tChild);
			}
		}
		
		return readyToGo;
	}
	
	public void resetRan(){
		this.ran = false;
		this.outstandingSubtasks = this.adjacentAS.size() + 1;
	}
	
	public synchronized int decrimentOutstandingSubTasks(){
		this.outstandingSubtasks--;
		return this.outstandingSubtasks;
	}
	
	public boolean hasRan(){
		return this.ran;
	}
	
	public boolean isReady(){
		for(WorkNode tParent: this.parents){
			if(!tParent.hasRan()){
				return false;
			}
		}
		return true;
	}

	public int getAdvertiser(){
		return this.advertiser;
	}
	
	public Set<Integer> getAdjacent(){
		return this.adjacentAS;
	}
	
	public String toString(){
		String out = "" + this.advertiser + " parents:";
		for(WorkNode tParent: this.parents){
			out += " " + tParent.advertiser;
		}
		out += " children:";
		for(WorkNode tChild: this.children){
			out += " " + tChild.advertiser;
		}
		
		return out;
	}
}
