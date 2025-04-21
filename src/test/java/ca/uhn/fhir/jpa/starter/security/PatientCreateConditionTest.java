package ca.uhn.fhir.jpa.starter.security;

import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatientCreateConditionTest {

    @Test
    void isReferenceForId_shouldReturnTrue_whenReferenceAndPractitionerIdMatch() {
        // Arrange
        IIdType mockPractitionerId = mock(IIdType.class);
        Reference mockReference = mock(Reference.class);
        IIdType mockReferenceElement = mock(IIdType.class);

        when(mockPractitionerId.getValue()).thenReturn("Practitioner/123");
        when(mockReference.getReferenceElement()).thenReturn(mockReferenceElement);
        when(mockReferenceElement.getValue()).thenReturn("Practitioner/123");

        // Act
        boolean result = PatientLinkedLoader.isReferenceForId(mockReference, mockPractitionerId);

        // Assert
        assertTrue(result);
    }

    @Test
    void isReferenceForId_shouldReturnFalse_whenReferenceAndPractitionerIdDoNotMatch() {
        // Arrange
        IIdType mockPractitionerId = mock(IIdType.class);
        Reference mockReference = mock(Reference.class);
        IIdType mockReferenceElement = mock(IIdType.class);

        when(mockPractitionerId.getValue()).thenReturn("Practitioner/123");
        when(mockReference.getReferenceElement()).thenReturn(mockReferenceElement);
        when(mockReferenceElement.getValue()).thenReturn("Practitioner/456");

        // Act
        boolean result = PatientLinkedLoader.isReferenceForId(mockReference, mockPractitionerId);

        // Assert
        assertFalse(result);
    }

    @Test
    void isReferenceForId_shouldReturnFalse_whenReferenceElementIsNull() {
        // Arrange
        IIdType mockPractitionerId = mock(IIdType.class);
        Reference mockReference = mock(Reference.class);

        when(mockPractitionerId.getValue()).thenReturn("Practitioner/123");
        when(mockReference.getReferenceElement()).thenReturn(null);

        // Act
        boolean result = PatientLinkedLoader.isReferenceForId(mockReference, mockPractitionerId);

        // Assert
        assertFalse(result);
    }

    @Test
    void isReferenceForId_shouldReturnFalse_whenPractitionerIdIsNull() {
        // Arrange
        Reference mockReference = mock(Reference.class);
        IIdType mockReferenceElement = mock(IIdType.class);

        when(mockReference.getReferenceElement()).thenReturn(mockReferenceElement);
        when(mockReferenceElement.getValue()).thenReturn("Practitioner/123");

        // Act
        boolean result = PatientLinkedLoader.isReferenceForId(mockReference, null);

        // Assert
        assertFalse(result);
    }

    @Test
    void isReferenceForId_shouldReturnFalse_whenReferenceIsNull() {
        // Arrange
        IIdType mockPractitionerId = mock(IIdType.class);

        when(mockPractitionerId.getValue()).thenReturn("Practitioner/123");

        // Act
        boolean result = PatientLinkedLoader.isReferenceForId(null, mockPractitionerId);

        // Assert
        assertFalse(result);
    }
}