package gov.cdc.usds.simplereport.service;

import static gov.cdc.usds.simplereport.test_util.TestDataBuilder.getAddress;
import static graphql.Assert.assertNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import gov.cdc.usds.simplereport.api.model.FacilityStats;
import gov.cdc.usds.simplereport.api.model.Role;
import gov.cdc.usds.simplereport.api.model.errors.IllegalGraphqlArgumentException;
import gov.cdc.usds.simplereport.api.model.errors.OrderingProviderRequiredException;
import gov.cdc.usds.simplereport.config.FeatureFlagsConfig;
import gov.cdc.usds.simplereport.config.simplereport.DemoUserConfiguration;
import gov.cdc.usds.simplereport.db.model.DeviceType;
import gov.cdc.usds.simplereport.db.model.Facility;
import gov.cdc.usds.simplereport.db.model.Organization;
import gov.cdc.usds.simplereport.db.model.PatientSelfRegistrationLink;
import gov.cdc.usds.simplereport.db.model.Person;
import gov.cdc.usds.simplereport.db.model.auxiliary.PersonName;
import gov.cdc.usds.simplereport.db.model.auxiliary.StreetAddress;
import gov.cdc.usds.simplereport.db.model.auxiliary.TestResult;
import gov.cdc.usds.simplereport.db.repository.ApiUserRepository;
import gov.cdc.usds.simplereport.db.repository.DeviceTypeRepository;
import gov.cdc.usds.simplereport.db.repository.FacilityRepository;
import gov.cdc.usds.simplereport.db.repository.OrganizationRepository;
import gov.cdc.usds.simplereport.db.repository.PatientRegistrationLinkRepository;
import gov.cdc.usds.simplereport.db.repository.PersonRepository;
import gov.cdc.usds.simplereport.db.repository.ProviderRepository;
import gov.cdc.usds.simplereport.idp.repository.OktaRepository;
import gov.cdc.usds.simplereport.service.email.EmailService;
import gov.cdc.usds.simplereport.test_util.SliceTestConfiguration.WithSimpleReportOrgAdminUser;
import gov.cdc.usds.simplereport.test_util.SliceTestConfiguration.WithSimpleReportSiteAdminUser;
import gov.cdc.usds.simplereport.test_util.SliceTestConfiguration.WithSimpleReportStandardUser;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.access.AccessDeniedException;

@EnableAsync
class OrganizationServiceTest extends BaseServiceTest<OrganizationService> {

  @Autowired private PatientRegistrationLinkRepository patientRegistrationLinkRepository;
  @Autowired @SpyBean private FacilityRepository facilityRepository;
  @Autowired @SpyBean private OrganizationRepository organizationRepository;
  @Autowired private DeviceTypeRepository deviceTypeRepository;
  @Autowired @SpyBean private OktaRepository oktaRepository;
  @Autowired @SpyBean private PersonRepository personRepository;
  @Autowired @SpyBean private ProviderRepository providerRepository;
  @Autowired ApiUserRepository _apiUserRepo;
  @Autowired private DemoUserConfiguration userConfiguration;
  @Autowired @SpyBean private EmailService emailService;
  @Autowired @SpyBean private DbAuthorizationService dbAuthorizationService;
  @Autowired @MockBean private FeatureFlagsConfig featureFlagsConfig;

  @BeforeEach
  void setupData() {
    initSampleData();
  }

  @Test
  void getCurrentOrg_success() {
    Organization org = _service.getCurrentOrganization();
    assertNotNull(org);
    assertEquals("DIS_ORG", org.getExternalId());
  }

  @Test
  void getOrganizationById_success() {
    Organization createdOrg = _dataFactory.saveValidOrganization();
    Organization foundOrg = _service.getOrganizationById(createdOrg.getInternalId());
    assertNotNull(foundOrg);
    assertEquals(createdOrg.getExternalId(), foundOrg.getExternalId());
  }

  @Test
  void getOrganizationById_failure() {
    UUID fakeUUID = UUID.randomUUID();
    IllegalGraphqlArgumentException caught =
        assertThrows(
            IllegalGraphqlArgumentException.class, () -> _service.getOrganizationById(fakeUUID));
    assertEquals(
        "An organization with internal_id=" + fakeUUID + " does not exist", caught.getMessage());
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getOrganizationWithExternalIdAsSiteAdmin_success() {
    Organization createdOrg = _dataFactory.saveValidOrganization();
    Organization foundOrg = _service.getOrganizationById(createdOrg.getInternalId());
    assertNotNull(foundOrg);
    assertEquals(createdOrg.getExternalId(), foundOrg.getExternalId());
  }

  @Test
  void createOrganizationAndFacility_success() {
    // GIVEN
    PersonName orderingProviderName = new PersonName("Bill", "Foo", "Nye", "");

    // WHEN
    Organization org =
        _service.createOrganizationAndFacility(
            "Tim's org",
            "k12",
            "d6b3951b-6698-4ee7-9d63-aaadee85bac0",
            "Facility 1",
            "12345",
            getAddress(),
            "123-456-7890",
            "test@foo.com",
            List.of(getDeviceConfig().getInternalId()),
            orderingProviderName,
            getAddress(),
            "123-456-7890",
            "547329472");
    // THEN
    assertEquals("Tim's org", org.getOrganizationName());
    assertFalse(org.getIdentityVerified());
    assertEquals("d6b3951b-6698-4ee7-9d63-aaadee85bac0", org.getExternalId());
    List<Facility> facilities = _service.getFacilities(org);
    assertNotNull(facilities);
    assertEquals(1, facilities.size());

    Facility fac = facilities.get(0);
    assertEquals("Facility 1", fac.getFacilityName());
    assertNull(fac.getDefaultDeviceType());

    PatientSelfRegistrationLink orgLink =
        patientRegistrationLinkRepository.findByOrganization(org).get();
    PatientSelfRegistrationLink facLink =
        patientRegistrationLinkRepository.findByFacility(fac).get();
    assertEquals(5, orgLink.getLink().length());
    assertEquals(5, facLink.getLink().length());
  }

  private DeviceType getDeviceConfig() {
    return _dataFactory.createDeviceType("Abbott ID Now", "Abbott", "1");
  }

  @Test
  void createOrganizationAndFacility_orderingProviderRequired_failure() {
    // GIVEN
    PersonName orderProviderName = new PersonName("Bill", "Foo", "Nye", "");
    StreetAddress mockAddress = getAddress();
    List<UUID> deviceTypeIds = List.of(getDeviceConfig().getInternalId());

    // THEN
    assertThrows(
        OrderingProviderRequiredException.class,
        () ->
            _service.createOrganizationAndFacility(
                "Adam's org",
                "urgent_care",
                "d6b3951b-6698-4ee7-9d63-aaadee85bac0",
                "Facility 1",
                "12345",
                mockAddress,
                "123-456-7890",
                "test@foo.com",
                deviceTypeIds,
                orderProviderName,
                mockAddress,
                null,
                null));
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getOrganizationsAndFacility_filterByIdentityVerified_success() {
    // GIVEN
    Organization verifiedOrg = _dataFactory.saveValidOrganization();
    Organization unverifiedOrg = _dataFactory.saveUnverifiedOrganization();

    // WHEN
    List<Organization> allOrgs = _service.getOrganizations(null);
    List<Organization> verifiedOrgs = _service.getOrganizations(true);
    List<Organization> unverifiedOrgs = _service.getOrganizations(false);

    // THEN
    assertTrue(allOrgs.size() >= 2);
    Set<String> allOrgIds =
        allOrgs.stream().map(Organization::getExternalId).collect(Collectors.toSet());
    assertTrue(allOrgIds.contains(verifiedOrg.getExternalId()));
    assertTrue(allOrgIds.contains(unverifiedOrg.getExternalId()));

    assertTrue(verifiedOrgs.size() >= 1);
    Set<String> verifiedOrgIds =
        verifiedOrgs.stream().map(Organization::getExternalId).collect(Collectors.toSet());
    assertTrue(verifiedOrgIds.contains(verifiedOrg.getExternalId()));
    assertFalse(verifiedOrgIds.contains(unverifiedOrg.getExternalId()));

    assertEquals(1, unverifiedOrgs.size());
    Set<String> unverifiedOrgIds =
        unverifiedOrgs.stream().map(Organization::getExternalId).collect(Collectors.toSet());
    assertFalse(unverifiedOrgIds.contains(verifiedOrg.getExternalId()));
    assertTrue(unverifiedOrgIds.contains(unverifiedOrg.getExternalId()));
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getFacilitiesIncludeArchived_includeArchived_success() {
    Organization org = _dataFactory.saveValidOrganization();
    Facility deletedFacility = _dataFactory.createArchivedFacility(org, "Delete me");
    _dataFactory.createValidFacility(org, "Not deleted");

    Set<Facility> archivedFacilities = _service.getFacilitiesIncludeArchived(org, true);

    assertEquals(1, archivedFacilities.size());
    assertTrue(
        archivedFacilities.stream()
            .anyMatch(f -> f.getInternalId().equals(deletedFacility.getInternalId())));
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getFacilitiesIncludeArchived_excludeArchived_success() {
    Organization org = _dataFactory.saveValidOrganization();
    _dataFactory.createArchivedFacility(org, "Delete me");
    Facility activeFacility = _dataFactory.createValidFacility(org, "Not deleted");

    Set<Facility> facilities = _service.getFacilitiesIncludeArchived(org, false);

    assertEquals(1, facilities.size());
    assertTrue(
        facilities.stream()
            .anyMatch(f -> f.getInternalId().equals(activeFacility.getInternalId())));
  }

  @Test
  @WithSimpleReportOrgAdminUser
  void viewArchivedFacilities_success() {
    Organization org = _dataFactory.saveValidOrganization();
    Facility deletedFacility = _dataFactory.createArchivedFacility(org, "Delete me");

    Set<Facility> archivedFacilities = _service.getArchivedFacilities(org);

    assertTrue(
        archivedFacilities.stream()
            .anyMatch(f -> f.getInternalId().equals(deletedFacility.getInternalId())));
  }

  @Test
  @WithSimpleReportStandardUser
  void viewArchivedFacilities_standardUser_failure() {
    Organization org = _dataFactory.saveValidOrganization();
    _dataFactory.createArchivedFacility(org, "Delete me");

    assertThrows(AccessDeniedException.class, () -> _service.getArchivedFacilities());
  }

  @Test
  @DisplayName("it should allow global admins to mark facility as deleted")
  @WithSimpleReportSiteAdminUser
  void deleteFacilityTest_successful() {
    // GIVEN
    Organization verifiedOrg = _dataFactory.saveValidOrganization();
    Facility mistakeFacility =
        _dataFactory.createValidFacility(verifiedOrg, "This facility is a mistake");
    // WHEN
    Facility deletedFacility =
        _service.markFacilityAsDeleted(mistakeFacility.getInternalId(), true);
    // THEN
    assertThat(deletedFacility.getIsDeleted()).isTrue();
  }

  @Test
  @DisplayName("it should not delete nonexistent facilities")
  @WithSimpleReportSiteAdminUser
  void deletedFacilityTest_throwsErrorWhenFacilityNotFound() {
    UUID orgId = UUID.randomUUID();
    IllegalGraphqlArgumentException caught =
        assertThrows(
            IllegalGraphqlArgumentException.class,
            // fake UUID
            () -> _service.markFacilityAsDeleted(orgId, true));
    assertEquals("Facility not found.", caught.getMessage());
  }

  @Test
  @DisplayName("it should allow global admins to mark organizations as deleted")
  @WithSimpleReportSiteAdminUser
  void deleteOrganizationTest_successful() {
    // GIVEN
    Organization verifiedOrg = _dataFactory.saveValidOrganization();
    // WHEN
    Organization deletedOrganization =
        _service.markOrganizationAsDeleted(verifiedOrg.getInternalId(), true);
    // THEN
    assertThat(deletedOrganization.getIsDeleted()).isTrue();
  }

  @Test
  @DisplayName("it should not delete nonexistent organizations")
  @WithSimpleReportSiteAdminUser
  void deletedOrganizationTest_throwsErrorWhenOrganizationyNotFound() {
    UUID orgId = UUID.randomUUID();

    IllegalGraphqlArgumentException caught =
        assertThrows(
            IllegalGraphqlArgumentException.class,
            // fake UUID
            () -> _service.markOrganizationAsDeleted(orgId, true));
    assertEquals("Organization not found.", caught.getMessage());
  }

  @Test
  @WithSimpleReportOrgAdminUser
  void adminUpdateOrganization_not_allowed() {
    assertSecurityError(() -> _service.updateOrganization("Foo org", "k12"));
  }

  @Test
  void verifyOrganizationNoPermissions_noUser_withOktaMigrationDisabled_success() {
    Organization org = _dataFactory.saveUnverifiedOrganization();
    _service.verifyOrganizationNoPermissions(org.getExternalId());

    org = _service.getOrganization(org.getExternalId());

    verify(dbAuthorizationService, times(0)).getOrgAdminUsers(org);
    verify(oktaRepository, times(1)).activateOrganizationWithSingleUser(org);
    assertTrue(org.getIdentityVerified());
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void verifyOrganizationNoPermissions_noUser_withOktaMigrationEnabled_throws() {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(true);
    Organization org = _dataFactory.saveUnverifiedOrganization();
    String orgExternalId = org.getExternalId();

    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> _service.verifyOrganizationNoPermissions(orgExternalId));
    assertEquals("Organization does not have any org admins.", e.getMessage());
    verify(dbAuthorizationService, times(1)).getOrgAdminUsers(org);
    verify(oktaRepository, times(0)).activateOrganizationWithSingleUser(org);
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void verifyOrganizationNoPermissions_withUsers_withOktaMigrationEnabled_success() {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(true);
    Organization org = _dataFactory.saveUnverifiedOrganizationWithUser("fake@example.com");

    _service.verifyOrganizationNoPermissions(org.getExternalId());
    verify(dbAuthorizationService, times(1)).getOrgAdminUsers(org);
    verify(oktaRepository, times(1)).activateUser("fake@example.com");
    verify(oktaRepository, times(0)).activateOrganizationWithSingleUser(org);

    org = _service.getOrganization(org.getExternalId());
    assertTrue(org.getIdentityVerified());
  }

  @Test
  void verifyOrganizationNoPermissions_orgAlreadyVerified_failure() {
    Organization org = _dataFactory.saveValidOrganization();
    String orgExternalId = org.getExternalId();
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> _service.verifyOrganizationNoPermissions(orgExternalId));

    assertEquals("Organization is already verified.", e.getMessage());
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void setIdentityVerified_withOktaMigrationDisabled_success() {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(false);
    Organization unverifiedOrg = _dataFactory.saveUnverifiedOrganization();

    boolean status = _service.setIdentityVerified(unverifiedOrg.getExternalId(), true);
    verify(dbAuthorizationService, times(0)).getOrgAdminUsers(unverifiedOrg);
    verify(oktaRepository, times(1)).activateOrganization(unverifiedOrg);
    assertTrue(status);
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void setIdentityVerified_withOktaMigrationEnabled_success() {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(true);
    Organization unverifiedOrg =
        _dataFactory.saveUnverifiedOrganizationWithUser("fake@example.com");

    boolean status = _service.setIdentityVerified(unverifiedOrg.getExternalId(), true);
    verify(dbAuthorizationService, times(1)).getOrgAdminUsers(unverifiedOrg);
    verify(oktaRepository, times(0)).activateOrganization(unverifiedOrg);
    assertTrue(status);
  }

  @Test
  @WithSimpleReportStandardUser
  void getPermissibleOrgId_allowsAccessToCurrentOrg() {
    var actual = _service.getPermissibleOrgId(_service.getCurrentOrganization().getInternalId());
    assertEquals(actual, _service.getCurrentOrganization().getInternalId());
  }

  @Test
  @WithSimpleReportStandardUser
  void getPermissibleOrgId_nullIdFallsBackToCurrentOrg() {
    var actual = _service.getPermissibleOrgId(null);
    assertEquals(actual, _service.getCurrentOrganization().getInternalId());
  }

  @Test
  @WithSimpleReportStandardUser
  void getPermissibleOrgId_throwsAccessDeniedForInaccessibleOrg() {
    var inaccessibleOrgId = UUID.randomUUID();
    assertThrows(
        AccessDeniedException.class, () -> _service.getPermissibleOrgId(inaccessibleOrgId));
  }

  @Test
  @WithSimpleReportOrgAdminUser
  void getFacilityStats_notAuthorizedError() {
    UUID facilityId = UUID.randomUUID();
    assertThrows(AccessDeniedException.class, () -> _service.getFacilityStats(facilityId));
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getFacilityStats_argumentMissingError() {
    assertThrows(IllegalGraphqlArgumentException.class, () -> _service.getFacilityStats(null));
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getFacilityStats_facilityNotFoundError() {
    UUID facilityId = UUID.randomUUID();
    doReturn(Optional.empty()).when(this.facilityRepository).findById(facilityId);
    assertThrows(IllegalGraphqlArgumentException.class, () -> _service.getFacilityStats(null));
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getFacilityStats_withOktaMigrationDisabled_success() {
    UUID facilityId = UUID.randomUUID();
    Facility mockFacility = mock(Facility.class);
    doReturn(Optional.of(mockFacility)).when(this.facilityRepository).findById(facilityId);
    doReturn(2).when(oktaRepository).getUsersCountInSingleFacility(mockFacility);
    doReturn(1).when(personRepository).countByFacilityAndIsDeleted(mockFacility, false);
    FacilityStats stats = _service.getFacilityStats(facilityId);

    verify(dbAuthorizationService, times(0)).getUsersWithSingleFacilityAccessCount(mockFacility);
    assertEquals(2, stats.getUsersSingleAccessCount());
    assertEquals(1, stats.getPatientsSingleAccessCount());
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getFacilityStats_withOktaMigrationEnabled_success() {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(true);
    UUID facilityId = UUID.randomUUID();
    Facility mockFacility = mock(Facility.class);
    doReturn(Optional.of(mockFacility)).when(this.facilityRepository).findById(facilityId);
    doReturn(4).when(dbAuthorizationService).getUsersWithSingleFacilityAccessCount(mockFacility);
    doReturn(2).when(personRepository).countByFacilityAndIsDeleted(mockFacility, false);
    FacilityStats stats = _service.getFacilityStats(facilityId);

    verify(oktaRepository, times(0)).getUsersCountInSingleFacility(mockFacility);
    assertEquals(4, stats.getUsersSingleAccessCount());
    assertEquals(2, stats.getPatientsSingleAccessCount());
  }

  @Nested
  @DisplayName("When updating a facility")
  class UpdateFacilityTest {
    private Facility facility;
    private StreetAddress newFacilityAddress;
    private StreetAddress newOrderingProviderAddress;

    @BeforeEach
    void beforeEach() {
      // GIVEN
      List<Organization> disOrgs = organizationRepository.findAllByName("Dis Organization");
      assertThat(disOrgs).hasSize(1);
      Organization disOrg = disOrgs.get(0);

      facility =
          facilityRepository.findByOrganizationAndFacilityName(disOrg, "Injection Site").get();
      assertThat(facility).isNotNull();
      List<DeviceType> devices = deviceTypeRepository.findAll();

      newFacilityAddress = new StreetAddress("0", "1", "2", "3", "4", "5");
      newOrderingProviderAddress = new StreetAddress("6", "7", "8", "9", "10", "11");
      PersonName orderingProviderName = new PersonName("Bill", "Foo", "Nye", "Jr.");

      // WHEN
      _service.updateFacility(
          facility.getInternalId(),
          "new name",
          "new clia",
          newFacilityAddress,
          "817-555-6666",
          "facility@dis.org",
          orderingProviderName,
          newOrderingProviderAddress,
          "npi",
          "817-555-7777",
          List.of(devices.get(0).getInternalId(), devices.get(1).getInternalId()));
    }

    @Test
    @DisplayName("it should update the facility with new values")
    @WithSimpleReportOrgAdminUser
    void updateFacilityTest() {
      // THEN
      Facility updatedFacility = facilityRepository.findById(facility.getInternalId()).get();

      assertThat(updatedFacility).isNotNull();
      assertThat(updatedFacility.getFacilityName()).isEqualTo("new name");
      assertThat(updatedFacility.getCliaNumber()).isEqualTo("new clia");
      assertThat(updatedFacility.getTelephone()).isEqualTo("817-555-6666");
      assertThat(updatedFacility.getEmail()).isEqualTo("facility@dis.org");
      assertThat(updatedFacility.getAddress()).isEqualTo(newFacilityAddress);

      assertThat(updatedFacility.getOrderingProvider().getNameInfo().getFirstName())
          .isEqualTo("Bill");
      assertThat(updatedFacility.getOrderingProvider().getNameInfo().getMiddleName())
          .isEqualTo("Foo");
      assertThat(updatedFacility.getOrderingProvider().getNameInfo().getLastName())
          .isEqualTo("Nye");
      assertThat(updatedFacility.getOrderingProvider().getNameInfo().getSuffix()).isEqualTo("Jr.");
      assertThat(updatedFacility.getOrderingProvider().getProviderId()).isEqualTo("npi");
      assertThat(updatedFacility.getOrderingProvider().getAddress())
          .isEqualTo(newOrderingProviderAddress);

      assertThat(updatedFacility.getDeviceTypes()).hasSize(2);
    }
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getOrgAdminUserIds_success() {
    Organization createdOrg = _dataFactory.saveValidOrganization();
    List<String> adminUserEmails = oktaRepository.fetchAdminUserEmail(createdOrg);

    List<UUID> expectedIds =
        adminUserEmails.stream()
            .map(email -> _apiUserRepo.findByLoginEmail(email).get().getInternalId())
            .collect(Collectors.toList());

    List<UUID> adminIds = _service.getOrgAdminUserIds(createdOrg.getInternalId());
    assertThat(adminIds).isEqualTo(expectedIds);
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getOrgAdminUserIds_returnsEmptyList_forNonExistentOrg() {
    UUID mismatchedUUID = UUID.fromString("5ebf893a-bb57-48ca-8fc2-1ef6b25e465b");
    List<UUID> adminIds = _service.getOrgAdminUserIds(mismatchedUUID);
    assertThat(adminIds).isEmpty();
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void getOrgAdminUserIds_skipsUser_forNonExistentUserInOrg() {
    Organization createdOrg = _dataFactory.saveValidOrganization();
    List<String> listWithAnExtraEmail = oktaRepository.fetchAdminUserEmail(createdOrg);
    listWithAnExtraEmail.add("nonexistent@example.com");

    when(oktaRepository.fetchAdminUserEmail(createdOrg)).thenReturn(listWithAnExtraEmail);
    List<UUID> expectedIds =
        listWithAnExtraEmail.stream()
            .filter(email -> !email.equals("nonexistent@example.com"))
            .map(email -> _apiUserRepo.findByLoginEmail(email).get().getInternalId())
            .collect(Collectors.toList());

    List<UUID> adminIds = _service.getOrgAdminUserIds(createdOrg.getInternalId());
    assertThat(adminIds).isEqualTo(expectedIds);
  }

  private void sendOrgAdminEmailCSVAsync_mnFacilities_test()
      throws ExecutionException, InterruptedException {
    when(oktaRepository.getOktaRateLimitSleepMs()).thenReturn(0);
    when(oktaRepository.getOktaOrgsLimit()).thenReturn(1);

    String type = "facilities";
    String mnExternalId = "747e341d-0467-45b8-b92f-a638da2bf1ee";
    UUID mnId = organizationRepository.findByExternalId(mnExternalId).get().getInternalId();
    List<String> mnEmails = _service.sendOrgAdminEmailCSVAsync(List.of(mnId), type, "MN").get();
    List<String> expectedMnEmails =
        List.of("mn-orgBadmin1@example.com", "mn-orgBadmin2@example.com");
    ArgumentCaptor<List<String>> arg1 = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> arg2 = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> arg3 = ArgumentCaptor.forClass(String.class);
    verify(emailService, times(1))
        .sendWithCSVAttachment(arg1.capture(), arg2.capture(), arg3.capture());
    assertEquals(expectedMnEmails, arg1.getValue());
    assertEquals("MN", arg2.getValue());
    assertEquals(type, arg3.getValue());
    assertThat(mnEmails).isEqualTo(expectedMnEmails);
  }

  private void sendOrgAdminEmailCSVAsync_paFacilities_test()
      throws ExecutionException, InterruptedException {
    when(oktaRepository.getOktaRateLimitSleepMs()).thenReturn(0);
    when(oktaRepository.getOktaOrgsLimit()).thenReturn(1);

    String type = "facilities";
    List<String> nonExistentOrgEmails =
        _service.sendOrgAdminEmailCSVAsync(List.of(), type, "PA").get();
    ArgumentCaptor<List<String>> arg1 = ArgumentCaptor.forClass(List.class);
    ArgumentCaptor<String> arg2 = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> arg3 = ArgumentCaptor.forClass(String.class);
    verify(emailService, times(1))
        .sendWithCSVAttachment(arg1.capture(), arg2.capture(), arg3.capture());
    assertEquals(nonExistentOrgEmails, arg1.getValue());
    assertEquals("PA", arg2.getValue());
    assertEquals(type, arg3.getValue());
    assertThat(nonExistentOrgEmails).isEmpty();
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void sendOrgAdminEmailCSVAsync_withEmails_withOktaMigrationDisabled_success()
      throws ExecutionException, InterruptedException {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(false);
    setupDataByFacility();
    sendOrgAdminEmailCSVAsync_mnFacilities_test();
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void sendOrgAdminEmailCSVAsync_withNoEmails_withOktaMigrationDisabled_success()
      throws ExecutionException, InterruptedException {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(false);
    setupDataByFacility();
    sendOrgAdminEmailCSVAsync_paFacilities_test();
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void sendOrgAdminEmailCSVAsync_withEmails_withOktaMigrationEnabled_success()
      throws ExecutionException, InterruptedException {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(true);
    setupDataByFacility();
    sendOrgAdminEmailCSVAsync_mnFacilities_test();
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void sendOrgAdminEmailCSVAsync_withNoEmails_withOktaMigrationEnabled_success()
      throws ExecutionException, InterruptedException {
    when(featureFlagsConfig.isOktaMigrationEnabled()).thenReturn(true);
    setupDataByFacility();
    sendOrgAdminEmailCSVAsync_paFacilities_test();
  }

  @Test
  @WithSimpleReportStandardUser
  void sendOrgAdminEmailCSV_accessDeniedException() {
    assertThrows(
        AccessDeniedException.class,
        () -> {
          _service.sendOrgAdminEmailCSV("facilities", "NM");
        });
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void sendOrgAdminEmailCSV_byFacilities_success() {
    setupDataByFacility();
    when(oktaRepository.getOktaRateLimitSleepMs()).thenReturn(0);
    when(oktaRepository.getOktaOrgsLimit()).thenReturn(1);

    boolean mnEmailSent = _service.sendOrgAdminEmailCSV("facilities", "MN");
    verify(facilityRepository, times(1)).findByFacilityState("MN");
    assertThat(mnEmailSent).isTrue();

    boolean njEmailSent = _service.sendOrgAdminEmailCSV("faCilities", "NJ");
    verify(facilityRepository, times(1)).findByFacilityState("NJ");
    assertThat(njEmailSent).isTrue();
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void sendOrgAdminEmailCSV_byPatients_success() {
    setupDataByPatient();
    when(oktaRepository.getOktaRateLimitSleepMs()).thenReturn(0);
    when(oktaRepository.getOktaOrgsLimit()).thenReturn(1);

    boolean caEmailSent = _service.sendOrgAdminEmailCSV("patients", "CA");
    verify(organizationRepository, times(1)).findAllByPatientStateWithTestEvents("CA");
    assertThat(caEmailSent).isTrue();

    boolean njEmailSent = _service.sendOrgAdminEmailCSV("PATIENTS", "NJ");
    verify(organizationRepository, times(1)).findAllByPatientStateWithTestEvents("NJ");
    assertThat(njEmailSent).isTrue();
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void sendOrgAdminEmailCSV_byUnsupportedType_success() {
    setupDataByPatient();
    when(oktaRepository.getOktaRateLimitSleepMs()).thenReturn(0);
    when(oktaRepository.getOktaOrgsLimit()).thenReturn(1);

    boolean unsupportedTypeEmailSent = _service.sendOrgAdminEmailCSV("Unsuported", "CA");
    verify(organizationRepository, times(0)).findAllByPatientStateWithTestEvents("CA");
    verify(facilityRepository, times(0)).findByFacilityState("CA");
    assertThat(unsupportedTypeEmailSent).isTrue();
  }

  @Test
  @WithSimpleReportSiteAdminUser
  void deleteE2EOktaOrganization_succeeds() {
    Organization createdOrg = _dataFactory.saveValidOrganization();
    Organization deletedOrg = _service.deleteE2EOktaOrganization(createdOrg.getExternalId());

    assertThat(deletedOrg).isEqualTo(createdOrg);
    verify(oktaRepository, times(1)).deleteOrganization(createdOrg);
  }

  private void setupDataByFacility() {
    StreetAddress orgAStreetAddress =
        new StreetAddress("123 Main Street", null, "Hackensack", "NJ", "07601", "Bergen");
    Organization orgA =
        _dataFactory.saveOrganization(
            new Organization("Org A", "k12", "d6b3951b-6698-4ee7-9d63-aaadee85bac0", true));
    _dataFactory.createValidFacility(orgA, "Org A Facility 1", orgAStreetAddress);
    _dataFactory.createValidFacility(orgA, "Org A Facility 2", orgAStreetAddress);
    _dataFactory.createValidApiUser("nj-orgAadmin1@example.com", orgA, Role.ADMIN);

    StreetAddress orgBStreetAddress =
        new StreetAddress("234 Red Street", null, "Minneapolis", "MN", "55407", "Hennepin");
    Organization orgB =
        _dataFactory.saveOrganization(
            new Organization("Org B", "airport", "747e341d-0467-45b8-b92f-a638da2bf1ee", true));
    _dataFactory.createValidFacility(orgB, "Org B Facility 1", orgBStreetAddress);
    _dataFactory.createValidApiUser("mn-orgBadmin1@example.com", orgB, Role.ADMIN);
    _dataFactory.createValidApiUser("mn-orgBadmin2@example.com", orgB, Role.ADMIN);
  }

  private void setupDataByPatient() {
    StreetAddress njStreetAddress =
        new StreetAddress("123 Main Street", null, "Hackensack", "NJ", "07601", "Bergen");
    StreetAddress caStreetAddress =
        new StreetAddress("456 Red Street", null, "Sunnyvale", "CA", "94086", "Santa Clara");
    StreetAddress mnStreetAddress =
        new StreetAddress("234 Red Street", null, "Minneapolis", "MN", "55407", "Hennepin");
    Organization orgA =
        _dataFactory.saveOrganization(
            new Organization(
                "Org A", "k12", "CA-org-a-5359aa13-93b2-4680-802c-9c90acb5d251", true));
    _dataFactory.createValidApiUser("ca-orgAadmin1@example.com", orgA, Role.ADMIN);
    Facility orgAFacility =
        _dataFactory.createValidFacility(orgA, "Org A Facility 1", caStreetAddress);

    // create patient in NJ with a test event for Org A
    Person orgAPatient1 =
        _dataFactory.createFullPersonWithAddress(orgA, njStreetAddress, "Joe", "Moe");
    _dataFactory.createTestEvent(orgAPatient1, orgAFacility, TestResult.POSITIVE);

    Organization orgB =
        _dataFactory.saveOrganization(
            new Organization(
                "Org B", "airport", "MN-org-b-3dddkv89-8981-421b-bd61-f293723284", true));
    _dataFactory.createValidApiUser("mn-orgBadmin1@example.com", orgB, Role.ADMIN);
    _dataFactory.createValidApiUser("mn-orgBuser@example.com", orgB, Role.USER);
    Facility orgBFacility =
        _dataFactory.createValidFacility(orgB, "Org B Facility 1", mnStreetAddress);
    // create patient in CA with a test event for Org A
    Person orgAPatient2 =
        _dataFactory.createFullPersonWithAddress(orgA, caStreetAddress, "Ed", "Eaves");
    _dataFactory.createTestEvent(orgAPatient2, orgBFacility, TestResult.UNDETERMINED);
    // create patient in CA with a test event for Org B
    Person orgBPatient1 =
        _dataFactory.createFullPersonWithAddress(orgB, caStreetAddress, "Mary", "Meade");
    _dataFactory.createTestEvent(orgBPatient1, orgBFacility, TestResult.NEGATIVE);
  }
}
