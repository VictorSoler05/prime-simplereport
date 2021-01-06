package gov.cdc.usds.simplereport.service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import gov.cdc.usds.simplereport.api.model.errors.IllegalGraphqlArgumentException;
import gov.cdc.usds.simplereport.config.authorization.OrganizationRoles;
import gov.cdc.usds.simplereport.db.model.DeviceType;
import gov.cdc.usds.simplereport.db.model.Facility;
import gov.cdc.usds.simplereport.db.model.Organization;
import gov.cdc.usds.simplereport.db.model.Provider;
import gov.cdc.usds.simplereport.db.model.auxiliary.PersonName;
import gov.cdc.usds.simplereport.db.model.auxiliary.StreetAddress;
import gov.cdc.usds.simplereport.db.repository.FacilityRepository;
import gov.cdc.usds.simplereport.db.repository.OrganizationRepository;
import gov.cdc.usds.simplereport.db.repository.ProviderRepository;
import gov.cdc.usds.simplereport.service.model.DeviceTypeHolder;

@Service
@Transactional(readOnly=false)
public class OrganizationService {

    private OrganizationRepository _repo;
    private FacilityRepository _facilityRepo;
    private ProviderRepository _providerRepo;
    private ApiUserService _apiUserService;
    private AuthorizationService _authService;


    public OrganizationService(OrganizationRepository repo,
            FacilityRepository facilityRepo,
            AuthorizationService authService,
            ProviderRepository providerRepo,
            ApiUserService apiUserService) {
        _repo = repo;
        _facilityRepo = facilityRepo;
        _authService = authService;
        _providerRepo = providerRepo;
        _apiUserService = apiUserService;
    }

    public Organization getCurrentOrganization() {
        List<OrganizationRoles> orgRoles = _authService.findAllOrganizationRoles();
        List<String> candidateExternalIds = orgRoles.stream()
                .map(OrganizationRoles::getOrganizationExternalId)
                .collect(Collectors.toList());
        List<Organization> validOrgs = _repo.findAllByExternalId(candidateExternalIds);
        if (validOrgs.size() == 1) {
            return validOrgs.get(0);
        } else {
            throw new RuntimeException("Expected one non-archived organization, but found " + validOrgs.size());
        }
    }

    public void assertFacilityNameAvailable(String testingFacilityName) {
        Organization org = this.getCurrentOrganization();
        _facilityRepo.findByOrganizationAndFacilityName(org, testingFacilityName)
            .ifPresent(f->{throw new IllegalGraphqlArgumentException("A facility with that name already exists");})
        ;
    }

    @Transactional(readOnly=true)
    public List<Facility> getFacilities(Organization org) {
        return _facilityRepo.findByOrganizationOrderByFacilityName(org);
    }

    @Transactional(readOnly=true)
    public Facility getFacilityInCurrentOrg(UUID facilityId) {
        Organization org = getCurrentOrganization();
        return _facilityRepo.findByOrganizationAndInternalId(org, facilityId)
                .orElseThrow(()->new IllegalGraphqlArgumentException("facility could not be found"));
    }

    public Facility updateFacility(
        UUID facilityId,
        String testingFacilityName,
        String cliaNumber,
        String street,
        String streetTwo,
        String city,
        String county,
        String state,
        String zipCode,
        String phone,
        String email,
        String orderingProviderFirstName,
        String orderingProviderMiddleName,
        String orderingProviderLastName,
        String orderingProviderSuffix,
        String orderingProviderNPI,
        String orderingProviderStreet,
        String orderingProviderStreetTwo,
        String orderingProviderCity,
        String orderingProviderCounty,
        String orderingProviderState,
        String orderingProviderZipCode,
        String orderingProviderTelephone,
        List<DeviceType> devices,
        DeviceType defaultDeviceType
    ) {
        Facility facility = this.getFacilityInCurrentOrg(facilityId);
        facility.setFacilityName(testingFacilityName);
        facility.setCliaNumber(cliaNumber);
        facility.setTelephone(phone);
        facility.setEmail(email);
        facility.addDefaultDeviceType(defaultDeviceType);
        StreetAddress af = facility.getAddress() == null ? new StreetAddress(
            street,
            streetTwo,
            city,
            county,
            state,
            zipCode
         ) : facility.getAddress();
         af.setStreet(street, streetTwo);
         af.setCity(city);
         af.setCounty(county);
         af.setState(state);
         af.setPostalCode(zipCode);
        facility.setAddress(af);

        Provider p = facility.getOrderingProvider();
        p.getNameInfo().setFirstName(orderingProviderFirstName);
        p.getNameInfo().setMiddleName(orderingProviderMiddleName);
        p.getNameInfo().setLastName(orderingProviderLastName);
        p.getNameInfo().setSuffix(orderingProviderSuffix);
        p.setProviderId(orderingProviderNPI);
        p.setTelephone(orderingProviderTelephone);

        StreetAddress a = p.getAddress() == null ? new StreetAddress(
            orderingProviderStreet,
            orderingProviderStreetTwo,
            orderingProviderCity,
            orderingProviderState,
            orderingProviderZipCode,
            orderingProviderCounty
         ) : p.getAddress();
        a.setStreet(orderingProviderStreet, orderingProviderStreetTwo);
        a.setCity(orderingProviderCity);
        a.setCounty(orderingProviderCounty);
        a.setState(orderingProviderState);
        a.setPostalCode(orderingProviderZipCode);
        p.setAddress(a);

        // remove all existing devices
        for(DeviceType d : facility.getDeviceTypes()) {
            facility.removeDeviceType(d);
        }

        // add new devices
        for(DeviceType d : devices) {
            facility.addDeviceType(d);
        }
        facility.setDefaultDeviceType(defaultDeviceType);
        return _facilityRepo.save(facility);
    }

    public Organization createOrganization(String name, String externalId) {
        _apiUserService.isAdminUser();
        return _repo.save(new Organization(name, externalId));
    }

    public Organization updateOrganization(String name) {
        Organization org = this.getCurrentOrganization();
        org.setOrganizationName(name);
        return _repo.save(org);
    }

    public Facility createFacility(String testingFacilityName, String cliaNumber, StreetAddress facilityAddress, String phone, String email,
            DeviceTypeHolder deviceTypes,
            PersonName providerName, StreetAddress providerAddress, String providerTelephone, String providerNPI) {
        Provider orderingProvider = _providerRepo.save(
                new Provider(providerName, providerNPI, providerAddress, providerTelephone));
        Facility facility = new Facility(getCurrentOrganization(),
            testingFacilityName, cliaNumber,
            facilityAddress, phone, email,
            orderingProvider,
            deviceTypes.getDefaultDeviceType(), deviceTypes.getConfiguredDeviceTypes());
        return _facilityRepo.save(facility);
    }
}
