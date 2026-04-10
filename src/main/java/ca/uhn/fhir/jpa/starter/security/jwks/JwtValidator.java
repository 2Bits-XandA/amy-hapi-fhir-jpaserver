package ca.uhn.fhir.jpa.starter.security.jwks;

import org.jose4j.jwt.consumer.InvalidJwtException;

import java.io.IOException;

public interface JwtValidator {
	JwtPayload parseAndValidate(String token) throws InvalidJwtException, IOException;
}
