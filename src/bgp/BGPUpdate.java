package bgp;

/**
 * Class that represents a BGP update message. In C this would be a struct that
 * contains a union based on if it is an explicit withdrawal or an advertisement
 * 
 * @author pendgaft
 * 
 */
public class BGPUpdate {

	/**
	 * Boolean flag to tell if this is a route bearing update or if it is an
	 * explicit withdrawal
	 */
	private boolean withdrawal;

	/**
	 * The "destination network" (see BGPRoute) that is being withdrawn
	 */
	private int withdrawalDest;

	/**
	 * The peer that is sending the withdrawal, advertisements have the peer
	 * contained as the next hop in the route
	 */
	private int withrdawalSource;

	/**
	 * The Route being advertised if this is NOT an explicit withdrawal message
	 */
	private BGPRoute advRoute;
	
	private long completedRuntime;
	private long totalRuntime;

	/**
	 * Static method to create an advertisement update, used when a viable path
	 * still exists
	 * 
	 * @param advRoute
	 *            - the route being advertised (this can be an implicit
	 *            withdrawal).
	 * @return - the update object that represents this
	 */
	public static BGPUpdate buildAdvertisement(BGPRoute advRoute, long runtime) {
		return new BGPUpdate(advRoute, runtime);
	}

	/**
	 * Static method to create an explicit withdrawal update
	 * 
	 * @param withDest
	 *            - the destiantion AS that we no longer have a route to
	 * @param updateSrc
	 *            - our (the withdrawaler)'s ASN
	 * @return - the update object that represents this
	 */
	public static BGPUpdate buildWithdrawal(int withDest, int updateSrc, long runtime) {
		return new BGPUpdate(withDest, updateSrc, runtime);
	}

	/**
	 * Constructor used to build an advertisement message.
	 * 
	 * @param path
	 *            - the path we're advertising
	 */
	private BGPUpdate(BGPRoute path, long runtime) {
		this.advRoute = path;
		this.withdrawal = false;
		this.totalRuntime = runtime;
	}

	/**
	 * Constructor used to build a withdrawal update message.
	 * 
	 * @param withdrawalDest
	 * @param updateSrc
	 */
	private BGPUpdate(int withdrawalDest, int updateSrc, long runtime) {
		this.withdrawalDest = withdrawalDest;
		this.withrdawalSource = updateSrc;
		this.withdrawal = true;
		this.totalRuntime = runtime;
	}

	/**
	 * Predicate to test if this is a withdrawal message or advertisement
	 * 
	 * @return - true if this is an explicit withdrawal message, false if this
	 *         is advertising a new prefix
	 */
	public boolean isWithdrawal() {
		return this.withdrawal;
	}

	/**
	 * Fetches the route being advertised. This functions so long as this is not
	 * an explicit withdrawal message.
	 * 
	 * @return - the BGP route being advertised
	 */
	public BGPRoute getAdvertisedRoute() {
		/*
		 * Sanity check that this isn't an explicit withdrawal message
		 */
		if (this.isWithdrawal()) {
			throw new BGPException("Attempted to fetch path from explcit withdrawal!");
		}

		return this.advRoute;
	}

	/**
	 * Fetches the destination that the update is reporting a loss of route to.
	 * 
	 * @return - the ASN of the networks we lost all routes to
	 */
	public int getWithdrawnDest() {
		/*
		 * Sanity check that this isn't an advertisement message
		 */
		if (!this.isWithdrawal()) {
			throw new BGPException("Attempted to fetch withdrawal dest from an advertisement bearing update!");
		}

		return this.withdrawalDest;
	}

	/**
	 * Fetches the ASN that is reporting the loss of a route (which peer sent
	 * this message).
	 * 
	 * @return - the ASN of the peer that advertised this explicit withdrawal to
	 *         us
	 */
	public int getWithdrawer() {
		/*
		 * Sanity check that this isn't an advertisement message
		 */
		if (!this.isWithdrawal()) {
			throw new BGPException("Attempted to fetch withdrawal dest from an advertisement bearing update!");
		}

		return this.withrdawalSource;
	}
	
	public boolean runTimeAhead(long time, int numberRunning){
		long fractionOfTime = (long)Math.ceil(time / numberRunning);
		
		this.completedRuntime += fractionOfTime;
		return this.completedRuntime - this.totalRuntime >= 0;
	}
	
	public long estTimeToComplete(int numberRunning){
		long timeLeft = this.totalRuntime - this.completedRuntime;
		
		return timeLeft * numberRunning;
	}
}
