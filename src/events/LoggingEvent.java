package events;

import java.io.IOException;

import logging.SimLogger;

public class LoggingEvent extends SimEvent {

	public LoggingEvent(double time) {
		super(time, SimEvent.LOGGING_EVENT, null);
	}

	@Override
	public void handleEvent(SimLogger theLogger) {
		try {
			theLogger.processLogging();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-2);
		}
	}

	public SimEvent repopulate() {
		return new LoggingEvent(this.getEventTime() + SimLogger.LOG_EPOCH);
	}

}
