package router.data;

public class RouterSendCapacity {

	private long cpuTimeGiven;
	/**
	 * This is the buffer space that a router has to accept incoming updates in
	 * terms of UPDATES not actual raw bytes (since that is how CISCO does it)
	 * there is a bit of a pain here since we can then fall back to a network
	 * buffer, which IS done in terms of raw bytes
	 */
	private int freeBufferSpace;
	private boolean zeroWindowing;

	public RouterSendCapacity(long cpuTime, int freeBufferSpace, boolean zeroWindowing) {
		super();
		this.cpuTimeGiven = cpuTime;
		this.freeBufferSpace = freeBufferSpace;
		this.zeroWindowing = zeroWindowing;
	}

	public long getCPUTime() {
		return this.cpuTimeGiven;
	}

	public int getFreeBufferSpace() {
		return freeBufferSpace;
	}

	public boolean isZeroWindowing() {
		return zeroWindowing;
	}

	public void sendUpdate(long ttc, int size) {
		boolean processed = this.cpuTimeGiven > 0;

		if (processed) {
			if (this.cpuTimeGiven > ttc) {
				this.cpuTimeGiven -= ttc;
			} else {
				processed = false;
				this.cpuTimeGiven = 0;
			}
		}

		if (!processed) {
			this.freeBufferSpace -= size;
		}
	}
}
