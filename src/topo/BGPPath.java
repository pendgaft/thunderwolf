package topo;

import java.util.*;

public class BGPPath {
	
	private int destASN;
	private LinkedList<Integer> path;

	public BGPPath(int dest){
		this.destASN = dest;
		this.path = new LinkedList<Integer>();
	}
	
	public boolean containsAnyOf(HashSet<Integer> testASNs){
		for(int tHop: this.path){
			if(testASNs.contains(tHop)){
				return true;
			}
		}
		
		return false;
	}
	
	public int getPathLength(){
		return this.path.size();
	}
	
	public List<Integer> getPath(){
		return this.path;
	}
	
	public void appendASToPath(int frontASN){
		this.path.addFirst(frontASN);
	}
	
	public boolean containsLoop(int testASN){
		for(int tASN: this.path){
			if(tASN == testASN){
				return true;
			}
		}
		return false;
	}
	
	public int getNextHop(){
		/*
		 * hack for paths to ourself
		 */
		if(this.path.size() == 0){
			return this.destASN;
		}
		
		return this.path.getFirst();
	}
	
	public int getDest(){
		return this.destASN;
	}
	
	public boolean equals(BGPPath rhs){
		if(rhs.path.size() != this.path.size() || rhs.destASN != this.destASN){
			return false;
		}
		
		for(int counter = 0; counter < this.path.size(); counter++){
			if (this.path.get(counter) != rhs.path.get(counter)){
				return false;
			}
		}
		
		return true;
	}
	
	public BGPPath deepCopy(){
		BGPPath newPath = new BGPPath(this.destASN);
		for(int counter = 0; counter < this.path.size(); counter++){
			newPath.path.addLast(this.path.get(counter));
		}
		return newPath;
	}
	
	public String toString(){
		String base = "dst: " + this.destASN + " path:";
		for(int tAS: this.path){
			base = base + " " + tAS;
		}
		return base;
	}
	
	public int hashCode(){
		return this.toString().hashCode();
	}
	
	//TODO these need to be actually populated
	public long getTimeRequired(){
		return 1;
	}
	
	public long getTransmitTime(){
		return 1;
	}
	
	public long getMemRequired(){
		return 1;
	}
}
