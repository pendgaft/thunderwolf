package topo;

public class BGPUpdate {
	
	private int withdrawlDest;
	private AS withrdawlSource;
	private BGPPath path;
	private boolean withdrawl;
	
	private double fractionDone;
	
	public BGPUpdate(BGPPath path) {
		this.path = path;
		this.withdrawl = false;
		this.fractionDone = 0.0;
	}
	
	public BGPUpdate(int dest, AS src){
		this.withdrawlDest = dest;
		this.withrdawlSource = src;
		this.withdrawl = true;
		this.fractionDone = 0.0;
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
	
	public double getFractionDone(){
		return this.fractionDone;
	}
	
	public boolean advanceDone(double completedFraction){
		this.fractionDone += completedFraction;
		return this.fractionDone >= 1.0;
	}
	
	//TODO at some point this needs to be actually a real value, but this works for now
	public long getRuntime(){
		return 1000;
	}
	
	
}
