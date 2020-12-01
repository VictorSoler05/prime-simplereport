package gov.cdc.usds.simplereport.test_util;

import java.time.LocalDate;
import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import gov.cdc.usds.simplereport.db.model.DeviceType;
import gov.cdc.usds.simplereport.db.model.Organization;
import gov.cdc.usds.simplereport.db.model.Person;
import gov.cdc.usds.simplereport.db.model.Provider;
import gov.cdc.usds.simplereport.db.model.StreetAddress;
import gov.cdc.usds.simplereport.db.model.auxiliary.PersonRole;
import gov.cdc.usds.simplereport.db.repository.DeviceTypeRepository;
import gov.cdc.usds.simplereport.db.repository.OrganizationRepository;
import gov.cdc.usds.simplereport.db.repository.PersonRepository;
import gov.cdc.usds.simplereport.db.repository.ProviderRepository;

@Component
public class TestDataFactory {

	@Autowired
	private OrganizationRepository _orgRepo;
	@Autowired
	private PersonRepository _personRepo;
	@Autowired
	private ProviderRepository _providerRepo;
	@Autowired
	private DeviceTypeRepository _deviceRepo;

	public Organization createValidOrg() {
		DeviceType dev = _deviceRepo.save(new DeviceType("Acme SuperFine", "Acme", "SFN", "54321-BOOM"));
		StreetAddress addy = new StreetAddress(Collections.singletonList("Moon Base"), "Luna City", "THE MOON", "", "");
		Provider doc = _providerRepo.save(new Provider("Doctor", "", "Doom", "", "DOOOOOOM", addy, "1-900-CALL-FOR-DOC")); 
		return _orgRepo.save(new Organization("The Mall", "MALLRAT", "123456", dev, doc));
	}

	public Person createMinimalPerson(Organization org) {
		return _personRepo.save(new Person("John", "Brown", "Boddie", "Jr.", org));
	}

	public Person createFullPerson(Organization org) {
		Person p = new Person(
			org, "HELLOTHERE", "Fred", null, "Astaire", null, LocalDate.of(1899, 5, 10),
			new StreetAddress("1 Central Park West", null, "New York", "NY", "11000", "New Yawk"), "202-123-4567",
			PersonRole.RESIDENT, null,
			Collections.singletonList("W"), null, "M", false, false
		);
		return _personRepo.save(p);
	}
}
