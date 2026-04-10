package ca.uhn.fhir.jpa.starter.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;

public class RollingAccessToken {
	private String currentToken = generateToken();
	private String previousToken = generateToken();

	private final Timer timer = new Timer(true);
	private static final Logger logger = LoggerFactory.getLogger(RollingAccessToken.class);

	public RollingAccessToken() {
		timer.scheduleAtFixedRate(
				new TimerTask() {
					@Override
					public void run() {
						changeToken();
					}
				},
				5 * 60 * 1000,
				5 * 60 * 1000);
		changeToken();
	}

	public boolean isInternalToken(String token) {
		return currentToken.equals(token) || previousToken.equals(token);
	}

	public String currentInternalToken() {
		return currentToken;
	}

	private String generateToken() {
		return java.util.UUID.randomUUID().toString();
	}

	private synchronized void changeToken() {
		previousToken = currentToken;
		currentToken = generateToken();
		logger.trace("Changing Internal Token: {} - and still accept {} as well", currentToken, previousToken);
	}

	public void cleanup() {
		timer.cancel();
	}
}
