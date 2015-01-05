package bgp;

import java.util.List;
import java.util.LinkedList;

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
	//TODO at some point it would be more realistic to pack multiple withdraws into one msg
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

	private boolean bgpProcessed;
	//TODO sendRate is a terrible named, please refactor
	private double sendRate;
	private double estCompletion;

	private double availToSendSize;
	private double completedSize;
	private double totalSize;

	private BGPUpdate parentUpdate;
	private List<BGPUpdate> childUpdates;

	/**
	 * Static method to create an advertisement update, used when a viable path
	 * still exists
	 * 
	 * @param advRoute
	 *            - the route being advertised (this can be an implicit
	 *            withdrawal).
	 * @return - the update object that represents this
	 */
	public static BGPUpdate buildAdvertisement(BGPRoute advRoute) {
		return new BGPUpdate(advRoute);
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
	public static BGPUpdate buildWithdrawal(int withDest, int updateSrc, int size) {
		return new BGPUpdate(withDest, updateSrc, size);
	}

	/**
	 * Constructor used to build an advertisement message.
	 * 
	 * @param path
	 *            - the path we're advertising
	 */
	private BGPUpdate(BGPRoute path) {
		this.advRoute = path;
		this.withdrawal = false;

		this.totalSize = (double) path.getSize();
		this.availToSendSize = 0.0;
		this.completedSize = 0.0;

		this.parentUpdate = null;
		this.childUpdates = null;
		this.bgpProcessed = false;
		this.sendRate = 0.0;
		this.estCompletion = Double.MAX_VALUE;
	}

	/**
	 * Constructor used to build a withdrawal update message.
	 * 
	 * @param withdrawalDest
	 * @param updateSrc
	 */
	private BGPUpdate(int withdrawalDest, int updateSrc, int size) {
		this.withdrawalDest = withdrawalDest;
		this.withrdawalSource = updateSrc;
		this.withdrawal = true;

		this.totalSize = (double) size;
		this.availToSendSize = 0.0;
		this.completedSize = 0.0;

		this.parentUpdate = null;
		this.childUpdates = null;
		this.bgpProcessed = false;
		this.sendRate = 0.0;
		this.estCompletion = Double.MAX_VALUE;
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

	public void updateSendRate(double newSendRate) {
		if (!this.bgpProcessed) {
			throw new RuntimeException("Can't set a send rate when we've not bgp processed!");
		}

		this.sendRate = newSendRate;
	}

	private void pushAvailState(double newStateRcvd) {
		if (newStateRcvd < 0.0) {
			throw new RuntimeException("Can't add a negetive amount of state.");
		}

		this.availToSendSize += newStateRcvd;
	}

	public void advanceUpdate(double time) {
		double stateSent = time * this.sendRate;
		stateSent = Math.min(stateSent, this.availToSendSize);
		this.availToSendSize -= stateSent;
		this.completedSize += stateSent;

		/*
		 * If we've not advanced any state we can stop any recursive calls right
		 * here...
		 */
		if (stateSent == 0.0) {
			return;
		}

		if (this.childUpdates != null) {
			for (BGPUpdate tChild : this.childUpdates) {
				tChild.pushAvailState(stateSent);
				tChild.advanceUpdate(time);
			}
		}
	}

	public double getEstimatedCompletionTime() {
		return this.estCompletion;
	}

	public void updateEstCompletion() {
		if (this.sendRate == 0.0) {
			this.estCompletion = Double.MAX_VALUE;
		} else {
			double myEstComp = (this.totalSize - this.completedSize) / this.sendRate;
			if (this.isDependancyRoot()) {
				this.estCompletion = myEstComp;
			} else {
				this.estCompletion = Math.max(myEstComp, this.parentUpdate.getEstimatedCompletionTime());
			}
		}

		if (this.childUpdates != null) {
			for (BGPUpdate tDependant : this.childUpdates) {
				tDependant.updateEstCompletion();
			}
		}
	}

	public void fakeFinishedInternalUpdate() {
		this.completedSize = this.totalSize;
	}

	public boolean finished() {
		/*
		 * Non-zero chance there is an odd edge condition where we could
		 * overrun, make sure we handle that..
		 */
		return this.completedSize >= this.totalSize;
	}

	/*
	 * Functions dealing with BGPUpdate dependancy graph
	 */
	public void setParent(BGPUpdate parent) {
		if (parent != null && parent.finished()) {
			parent = null;
		}

		this.parentUpdate = parent;

		if (parent != null) {
			parent.addChild(this);
		}
	}

	public boolean isDependancyRoot() {
		return this.parentUpdate == null;
	}

	private void addChild(BGPUpdate child) {
		if (this.childUpdates == null) {
			this.childUpdates = new LinkedList<BGPUpdate>();
		}

		this.childUpdates.add(child);
	}

	public void orphanChildren() {
		if (this.childUpdates != null) {
			for (BGPUpdate tChild : this.childUpdates) {
				tChild.setParent(null);
			}
		}
	}

	/**
	 * Computes the size this update will take up when in I/O buffers. Currently
	 * this does NOT add on packet headers. I _think_ that is an ok assumption,
	 * as in general updates get packed as tightly as they can and the header
	 * overhead should only be about 4%.
	 * 
	 * @return the size of the update in bytes when in an I/O buffer or on the
	 *         wire
	 */
	public long getWireSize() {
		if (this.isWithdrawal()) {
			return 27;
		} else {
			return 44 + this.getAdvertisedRoute().getPathLength() * 4;
		}
	}

	public boolean hasBeenProcessed() {
		return this.bgpProcessed;
	}

	public void markAsProcessed() {
		if (this.bgpProcessed) {
			throw new RuntimeException("Marked and already processed update as processsed....");
		}

		this.bgpProcessed = true;
	}
}
