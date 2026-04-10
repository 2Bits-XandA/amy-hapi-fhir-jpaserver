package ca.uhn.fhir.jpa.starter.security;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.security.jwks.JwtValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfig {
	@Bean
	public RoleBasedAuthorizationInterceptor authInterceptor(JwtValidator jwtValidator, FhirContext fhirContext) {
		return new RoleBasedAuthorizationInterceptor(jwtValidator, fhirContext);
	}
}
