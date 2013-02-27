package router.data;

import bgp.BGPUpdate;

public class RouterSendCapacity {

	private long cpuTimeGiven;
	private long freeBufferSpace;
	private boolean zeroWindowing;

	public RouterSendCapacity(long cpuTime, long freeBufferSpace,
			boolean zeroWindowing) {
		super();
		this.cpuTimeGiven = cpuTime;
		this.freeBufferSpace = freeBufferSpace;
		this.zeroWindowing = zeroWindowing;
	}

	public long getCPUTime() {
		return this.cpuTimeGiven;
	}

	public long getFreeBufferSpace() {
		return freeBufferSpace;
	}

	public boolean isZeroWindowing() {
		return zeroWindowing;
	}

	public void sendUpdate(BGPUpdate update) {
		boolean processed = this.cpuTimeGiven > 0;

		if (processed) {
			/*
			 * ghetto hack to compute the base TTC
			 */
			long routeTTC = update.estTimeToComplete(1, 1.0);
			if (this.cpuTimeGiven > routeTTC) {
				this.cpuTimeGiven -= routeTTC;
			} else {
				processed = false;
				this.cpuTimeGiven = 0;
			}
		}

		if (!processed) {
			this.freeBufferSpace -= update.getWireSize();
		}
	}
}
