package ca.uhn.fhir.jpa.starter.security;

import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.exceptions.AuthenticationException;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.interceptor.InterceptorAdapter;
import ca.uhn.fhir.rest.server.interceptor.auth.AuthorizationConstants;

import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

@Interceptor(order = AuthorizationConstants.ORDER_CONVERTER_INTERCEPTOR)
public class PatientCreateInterceptor extends InterceptorAdapter {
	private static final Logger logger = LoggerFactory.getLogger(PatientCreateInterceptor.class);

	@Override
	public void incomingRequestPreHandled(RestOperationTypeEnum theOperation, RequestDetails theRequestDetails) {
		logger.trace("incomingRequestPreHandled running {} for {}", theRequestDetails.getRequestType().name(), theRequestDetails.getResourceName());
		// 1) Nur POST /Patient
		process(theRequestDetails);
	}

	private static void process(RequestDetails theRequestDetails) {
		if (theRequestDetails.getRequestType().name().equals("POST")
			&& "Patient".equals(theRequestDetails.getResourceName())) {

			logger.debug("preProcess for PATIENT POST is running");
			// PractitionerId aus dem Request holen
			IIdType practitionerId = (IIdType) theRequestDetails.getUserData().get("practitionerId");
			if (practitionerId == null) {
				logger.error("No practitioner ID found in request context");
				throw new AuthenticationException("Kein Practitioner im Kontext");
			}

			// Resource aus dem Request
			IBaseResource resource = theRequestDetails.getResource();
			if (!(resource instanceof Patient p)) {
				throw new InvalidRequestException("Must be a Patient Resource");
			}

			// Enforce GeneralPractitioner
			List<Reference> list = new ArrayList<>();
			Reference reference = new Reference(practitionerId.toUnqualifiedVersionless());
			reference.addExtension(LinkType.owner.toExtension());
			list.add(reference);
			p.setGeneralPractitioner(list);


		}
	}
}
