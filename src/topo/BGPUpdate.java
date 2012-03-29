package topo;

public class BGPUpdate {
	
	private int withdrawlDest;
	private AS withrdawlSource;
	private BGPPath path;
	private boolean withdrawl;
	
	public BGPUpdate(BGPPath path) {
		this.path = path;
		this.withdrawl = false;
	}
	
	public BGPUpdate(int dest, AS src){
		this.withdrawlDest = dest;
		this.withrdawlSource = src;
		this.withdrawl = true;
	}
	
	public boolean isWithdrawl(){
		return this.withdrawl;
	}

	public BGPPath getPath(){
		return this.path;
	}
	
	public int getWithdrawnDest(){
		return this.withdrawlDest;
	}
	
	public AS getWithdrawer(){
		return this.withrdawlSource;
	}
}
