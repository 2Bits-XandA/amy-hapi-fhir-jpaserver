package ca.uhn.fhir.jpa.starter.security;

import ca.uhn.fhir.context.FhirContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecurityConfig {
	@Bean
	public RoleBasedAuthorizationInterceptor authInterceptor(FhirContext fhirContext) {
		return new RoleBasedAuthorizationInterceptor(fhirContext);
	}
}
