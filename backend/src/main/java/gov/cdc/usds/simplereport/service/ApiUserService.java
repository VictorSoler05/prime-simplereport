package gov.cdc.usds.simplereport.service;

import com.okta.sdk.resource.model.UserStatus;
import gov.cdc.usds.simplereport.api.ApiUserContextHolder;
import gov.cdc.usds.simplereport.api.CurrentAccountRequestContextHolder;
import gov.cdc.usds.simplereport.api.WebhookContextHolder;
import gov.cdc.usds.simplereport.api.apiuser.ManageUsersPageWrapper;
import gov.cdc.usds.simplereport.api.model.ApiUserWithStatus;
import gov.cdc.usds.simplereport.api.model.Role;
import gov.cdc.usds.simplereport.api.model.errors.ConflictingUserException;
import gov.cdc.usds.simplereport.api.model.errors.IllegalGraphqlArgumentException;
import gov.cdc.usds.simplereport.api.model.errors.MisconfiguredUserException;
import gov.cdc.usds.simplereport.api.model.errors.NonexistentUserException;
import gov.cdc.usds.simplereport.api.model.errors.OktaAccountUserException;
import gov.cdc.usds.simplereport.api.model.errors.PrivilegeUpdateFacilityAccessException;
import gov.cdc.usds.simplereport.api.model.errors.RestrictedAccessUserException;
import gov.cdc.usds.simplereport.api.model.errors.UnidentifiedFacilityException;
import gov.cdc.usds.simplereport.api.model.errors.UnidentifiedUserException;
import gov.cdc.usds.simplereport.api.pxp.CurrentPatientContextHolder;
import gov.cdc.usds.simplereport.config.AuthorizationConfiguration;
import gov.cdc.usds.simplereport.config.FeatureFlagsConfig;
import gov.cdc.usds.simplereport.config.authorization.OrganizationRole;
import gov.cdc.usds.simplereport.config.authorization.OrganizationRoleClaims;
import gov.cdc.usds.simplereport.config.authorization.PermissionHolder;
import gov.cdc.usds.simplereport.db.model.ApiUser;
import gov.cdc.usds.simplereport.db.model.Facility;
import gov.cdc.usds.simplereport.db.model.IdentifiedEntity;
import gov.cdc.usds.simplereport.db.model.Organization;
import gov.cdc.usds.simplereport.db.model.Person;
import gov.cdc.usds.simplereport.db.model.auxiliary.PersonName;
import gov.cdc.usds.simplereport.db.repository.ApiUserRepository;
import gov.cdc.usds.simplereport.idp.repository.OktaRepository;
import gov.cdc.usds.simplereport.idp.repository.PartialOktaUser;
import gov.cdc.usds.simplereport.service.model.IdentityAttributes;
import gov.cdc.usds.simplereport.service.model.IdentitySupplier;
import gov.cdc.usds.simplereport.service.model.OrganizationRoles;
import gov.cdc.usds.simplereport.service.model.UserInfo;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.ScopeNotActiveException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

@Service
@Transactional
@Slf4j
public class ApiUserService {

  @Autowired private AuthorizationService _authService;

  @Autowired private ApiUserRepository _apiUserRepo;

  @Autowired private IdentitySupplier _supplier;

  @Autowired private OrganizationService _orgService;

  @Autowired private OktaRepository _oktaRepo;

  @Autowired private TenantDataAccessService _tenantService;

  @Autowired private CurrentPatientContextHolder _patientContextHolder;

  @Autowired private CurrentAccountRequestContextHolder _accountRequestContextHolder;

  @Autowired private WebhookContextHolder _webhookContextHolder;

  @Autowired private ApiUserContextHolder _apiUserContextHolder;

  @Autowired private DbAuthorizationService _dbAuthService;

  @Autowired private DbOrgRoleClaimsService _dbOrgRoleClaimsService;

  @Autowired private FeatureFlagsConfig _featureFlagsConfig;

  private void createUserUpdatedAuditLog(Object authorId, Object updatedUserId) {
    log.info("User with id={} updated by user with id={}", authorId, updatedUserId);
  }

  public boolean userExists(String username) {
    Optional<ApiUser> found =
        _apiUserRepo.findByLoginEmailIncludeArchived(username.toLowerCase().strip());
    return found.isPresent();
  }

  public UserInfo createUser(
      String username,
      PersonName name,
      String organizationExternalId,
      Role role,
      boolean accessAllFacilities,
      Set<UUID> facilities) {
    Organization org = _orgService.getOrganization(organizationExternalId);
    return createUserHelper(username, name, org, role, accessAllFacilities, facilities);
  }

  @AuthorizationConfiguration.RequirePermissionManageUsers
  public UserInfo createUserInCurrentOrg(
      String username,
      PersonName name,
      Role role,
      boolean accessAllFacilities,
      Set<UUID> facilities) {
    Organization org = _orgService.getCurrentOrganization();

    Optional<ApiUser> found = _apiUserRepo.findByLoginEmailIncludeArchived(username.toLowerCase());
    return found
        .map(apiUser -> reprovisionUser(apiUser, name, org, role, accessAllFacilities, facilities))
        .orElseGet(
            () -> createUserHelper(username, name, org, role, accessAllFacilities, facilities));
  }

  @AuthorizationConfiguration.RequireGlobalAdminUser
  public ApiUser createApiUserNoOkta(String email, PersonName name) {
    Optional<ApiUser> found = _apiUserRepo.findByLoginEmailIncludeArchived(email.toLowerCase());
    if (found.isPresent()) {
      throw new IllegalGraphqlArgumentException("User already exists");
    } else {
      IdentityAttributes userIdentity = new IdentityAttributes(email, name);
      ApiUser apiUser = _apiUserRepo.save(new ApiUser(email, userIdentity));
      log.info(
          "User with id={} created by user with id={}",
          apiUser.getInternalId(),
          getCurrentApiUser().getInternalId().toString());

      return apiUser;
    }
  }

  private UserInfo reprovisionUser(
      ApiUser apiUser,
      PersonName name,
      Organization org,
      Role role,
      boolean accessAllFacilities,
      Set<UUID> facilities) {
    if (!apiUser.getIsDeleted()) {
      // an enabled user with this email address exists (in some org)
      throw new ConflictingUserException();
    }

    String currentOrgExternalId = getOrgExternalId(apiUser);

    if (!org.getExternalId().equals(currentOrgExternalId)) {
      throw new ConflictingUserException();
    }

    // re-provision the user
    IdentityAttributes userIdentity = new IdentityAttributes(apiUser.getLoginEmail(), name);
    _oktaRepo.reprovisionUser(userIdentity);

    Set<OrganizationRole> roles = getOrganizationRoles(role, accessAllFacilities);
    Set<Facility> facilitiesFound = getFacilitiesToGiveAccess(org, roles, facilities);
    Optional<OrganizationRoles> updatedOrgRoles;

    apiUser.setNameInfo(name);
    apiUser.setIsDeleted(false);
    apiUser.setFacilities(facilitiesFound);
    apiUser.setRoles(roles, org);

    Optional<OrganizationRoleClaims> oktaClaims =
        _oktaRepo.updateUserPrivileges(apiUser.getLoginEmail(), org, facilitiesFound, roles);
    updatedOrgRoles = oktaClaims.map(c -> _orgService.getOrganizationRoles(c));

    if (_featureFlagsConfig.isOktaMigrationEnabled()) {
      updatedOrgRoles = Optional.ofNullable(getOrgRolesFromDB(apiUser));
    }

    UserInfo user = new UserInfo(apiUser, updatedOrgRoles, false);

    log.info(
        "User with id={} re-provisioned by user with id={}",
        apiUser.getInternalId(),
        getCurrentApiUser().getInternalId());

    return user;
  }

  private UserInfo createUserHelper(
      String username,
      PersonName name,
      Organization org,
      Role role,
      boolean accessAllFacilities,
      Set<UUID> facilities)
      throws ConflictingUserException {
    IdentityAttributes userIdentity = new IdentityAttributes(username, name);
    ApiUser apiUser = _apiUserRepo.save(new ApiUser(username, userIdentity));
    boolean active = org.getIdentityVerified();

    Set<OrganizationRole> roles = getOrganizationRoles(role, accessAllFacilities);
    Set<Facility> facilitiesFound = getFacilitiesToGiveAccess(org, roles, facilities);
    apiUser.setFacilities(facilitiesFound);
    apiUser.setRoles(roles, org);

    Optional<OrganizationRoleClaims> oktaClaims =
        _oktaRepo.createUser(userIdentity, org, facilitiesFound, roles, active);
    Optional<OrganizationRoles> orgRoles = oktaClaims.map(c -> _orgService.getOrganizationRoles(c));

    if (_featureFlagsConfig.isOktaMigrationEnabled()) {
      orgRoles = Optional.ofNullable(getOrgRolesFromDB(apiUser));
    }

    UserInfo user = new UserInfo(apiUser, orgRoles, false);

    log.info(
        "User with id={} created by user with id={}",
        apiUser.getInternalId(),
        getCurrentApiUser().getInternalId().toString());

    return user;
  }

  @AuthorizationConfiguration.RequirePermissionManageTargetUser
  public UserInfo updateUser(UUID userId, PersonName name) {
    ApiUser apiUser = getApiUser(userId);
    String username = apiUser.getLoginEmail();

    PersonName nameInfo = apiUser.getNameInfo();
    nameInfo.setFirstName(name.getFirstName());
    nameInfo.setMiddleName(name.getMiddleName());
    nameInfo.setLastName(name.getLastName());
    nameInfo.setSuffix(name.getSuffix());
    apiUser = _apiUserRepo.save(apiUser);

    IdentityAttributes userIdentity = new IdentityAttributes(username, name);

    Optional<OrganizationRoleClaims> oktaRoleClaims = _oktaRepo.updateUser(userIdentity);

    UserInfo user = consolidateUser(apiUser, oktaRoleClaims);

    createUserUpdatedAuditLog(
        apiUser.getInternalId(), getCurrentApiUser().getInternalId().toString());

    return user;
  }

  @AuthorizationConfiguration.RequirePermissionManageTargetUserNotSelf
  public UserInfo updateUserPrivileges(
      UUID userId, boolean accessAllFacilities, Set<UUID> facilities, Role role) {
    ApiUser apiUser = getApiUser(userId);
    String username = apiUser.getLoginEmail();

    String orgExternalId = getOrgExternalId(apiUser);
    Organization org = _orgService.getOrganization(orgExternalId);
    Set<OrganizationRole> roles = getOrganizationRoles(role, accessAllFacilities);
    Set<Facility> facilitiesFound = getFacilitiesToGiveAccess(org, roles, facilities);
    Optional<OrganizationRoleClaims> newOrgClaims =
        _oktaRepo.updateUserPrivileges(username, org, facilitiesFound, roles);
    Optional<OrganizationRoles> orgRoles =
        newOrgClaims.map(c -> _orgService.getOrganizationRoles(org, c));
    UserInfo user = new UserInfo(apiUser, orgRoles, false);

    apiUser.setFacilities(facilitiesFound);
    apiUser.setRoles(roles, org);

    createUserUpdatedAuditLog(apiUser.getInternalId(), getCurrentApiUser().getInternalId());

    return user;
  }

  @AuthorizationConfiguration.RequirePermissionManageTargetUser
  public UserInfo updateUserEmail(UUID userId, String email) {
    ApiUser apiUser = getApiUser(userId);
    String username = apiUser.getLoginEmail();
    IdentityAttributes userIdentity = new IdentityAttributes(username, apiUser.getNameInfo());

    // Check to make sure the changed email doesn't already exist in the system.
    Optional<ApiUser> foundUser = _apiUserRepo.findByLoginEmail(email);
    if (foundUser.isPresent()) {
      throw new ConflictingUserException();
    }

    apiUser.setLoginEmail(email);
    apiUser = _apiUserRepo.save(apiUser);

    Optional<OrganizationRoleClaims> oktaRoleClaims =
        _oktaRepo.updateUserEmail(userIdentity, email);

    createUserUpdatedAuditLog(
        apiUser.getInternalId(), getCurrentApiUser().getInternalId().toString());

    return consolidateUser(apiUser, oktaRoleClaims);
  }

  @AuthorizationConfiguration.RequirePermissionManageTargetUser
  public UserInfo resetUserPassword(UUID userId) {
    ApiUser apiUser = getApiUser(userId);
    String username = apiUser.getLoginEmail();
    _oktaRepo.resetUserPassword(username);
    OrganizationRoleClaims oktaClaims =
        _oktaRepo
            .getOrganizationRoleClaimsForUser(username)
            .orElseThrow(MisconfiguredUserException::new);

    return consolidateUser(apiUser, Optional.ofNullable(oktaClaims));
  }

  @AuthorizationConfiguration.RequirePermissionManageTargetUser
  public UserInfo resetUserMfa(UUID userId) {
    ApiUser apiUser = getApiUser(userId);
    String username = apiUser.getLoginEmail();
    _oktaRepo.resetUserMfa(username);
    OrganizationRoleClaims oktaClaims =
        _oktaRepo
            .getOrganizationRoleClaimsForUser(username)
            .orElseThrow(MisconfiguredUserException::new);
    return consolidateUser(apiUser, Optional.ofNullable(oktaClaims));
  }

  @AuthorizationConfiguration.RequirePermissionManageTargetUserNotSelf
  public UserInfo setIsDeleted(UUID userId, boolean deleted) {
    ApiUser apiUser = getApiUser(userId, !deleted);
    apiUser.setIsDeleted(deleted);
    apiUser = _apiUserRepo.save(apiUser);
    _oktaRepo.setUserIsActive(apiUser.getLoginEmail(), !deleted);
    return new UserInfo(apiUser, Optional.empty(), false);
  }

  @AuthorizationConfiguration.RequirePermissionManageTargetUser
  public UserInfo reactivateUserAndResetPassword(UUID userId) {
    UserInfo reactivatedUser = reactivateUser((userId));
    return resetUserPassword(reactivatedUser.getId());
  }

  // This method is used to reactivate users that have been suspended due to inactivity
  @AuthorizationConfiguration.RequirePermissionManageTargetUser
  public UserInfo reactivateUser(UUID userId) {
    ApiUser apiUser = getApiUser(userId);
    String username = apiUser.getLoginEmail();
    _oktaRepo.reactivateUser(username);
    OrganizationRoleClaims oktaClaims =
        _oktaRepo
            .getOrganizationRoleClaimsForUser(username)
            .orElseThrow(MisconfiguredUserException::new);
    return consolidateUser(apiUser, Optional.ofNullable(oktaClaims));
  }

  // This method is to re-send the invitation email to join SimpleReport
  @AuthorizationConfiguration.RequirePermissionManageTargetUser
  public UserInfo resendActivationEmail(UUID userId) {
    ApiUser apiUser = getApiUser(userId);
    String username = apiUser.getLoginEmail();
    _oktaRepo.resendActivationEmail(username);
    OrganizationRoleClaims oktaClaims =
        _oktaRepo
            .getOrganizationRoleClaimsForUser(username)
            .orElseThrow(MisconfiguredUserException::new);
    return consolidateUser(apiUser, Optional.ofNullable(oktaClaims));
  }

  /**
   * Clear a user's roles and facilities - intended to be used on site admin users only,
   * non-site-admin users will be in a misconfigured state without roles and facilities
   *
   * @param username
   * @return ApiUser
   */
  @AuthorizationConfiguration.RequireGlobalAdminUser
  public ApiUser clearUserRolesAndFacilities(String username) {
    ApiUser foundUser =
        _apiUserRepo.findByLoginEmail(username).orElseThrow(NonexistentUserException::new);
    foundUser.clearRolesAndFacilities();
    return foundUser;
  }

  private ApiUser getApiUser(UUID id) {
    return getApiUser(id, false);
  }

  private ApiUser getApiUser(UUID id, boolean includeArchived) {
    Optional<ApiUser> found =
        includeArchived ? _apiUserRepo.findByIdIncludeArchived(id) : _apiUserRepo.findById(id);
    if (found.isEmpty()) {
      throw new NonexistentUserException();
    }
    return found.get();
  }

  private Set<OrganizationRole> getOrganizationRoles(Role role, boolean accessAllFacilities) {
    Set<OrganizationRole> result =
        EnumSet.of(role.toOrganizationRole(), OrganizationRole.getDefault());
    if (accessAllFacilities) {
      result.add(OrganizationRole.ALL_FACILITIES);
    }

    return result;
  }

  // Creating separate getCurrentApiUser() methods because the auditing use case and
  // sample data initialization require contained transactions, while Sonar requires
  // a non-Transactional version to be called from other methods in the same class
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ApiUser getCurrentApiUserInContainedTransaction() {
    return getCurrentApiUser();
  }

  private ApiUser getPatientApiUser() {
    Person patient = _patientContextHolder.getPatient();
    if (patient == null) {
      throw new UnidentifiedUserException();
    }

    String username = getPatientIdEmail(patient);
    Optional<ApiUser> found = _apiUserRepo.findByLoginEmail(username);

    if (found.isPresent()) {
      log.debug("Patient has logged in before: retrieving user record.");
      ApiUser user = found.get();
      user.updateLastSeen();
      user = _apiUserRepo.save(user);
      return user;
    } else {
      log.info("Initial login for patient: creating user record.");
      ApiUser user = new ApiUser(username, patient.getNameInfo());
      user.updateLastSeen();
      user = _apiUserRepo.save(user);

      log.info(
          "Patient user with id={} self-created from link {}",
          user.getInternalId(),
          _patientContextHolder.getPatientLink().getInternalId());
      return user;
    }
  }

  private static final String NOREPLY = "-noreply@simplereport.gov";
  private static final String PATIENT_SELF_REGISTRATION_EMAIL =
      "patient-self-registration" + NOREPLY;
  private static final String ACCOUNT_REQUEST_EMAIL = "account-request" + NOREPLY;
  private static final String WEBHOOK_EMAIL = "webhook" + NOREPLY;
  private static final String ANONYMOUS_EMAIL = "anonymous-user" + NOREPLY;

  private String getPatientIdEmail(Person patient) {
    return patient.getInternalId() + NOREPLY;
  }

  /**
   * The Patient Registration User should <em>always</em> exist. Originally it was created in a
   * migration, but the app shouldn't crash if that row is missing. Instead, create it!
   */
  private ApiUser getPatientSelfRegistrationApiUser() {
    Optional<ApiUser> found = _apiUserRepo.findByLoginEmail(PATIENT_SELF_REGISTRATION_EMAIL);
    return found.orElseGet(
        () -> {
          ApiUser magicUser =
              new ApiUser(
                  PATIENT_SELF_REGISTRATION_EMAIL,
                  new PersonName("", "", "Patient Self-Registration User", ""));
          log.info(
              "Magic patient registration user not found. Created Person={}",
              magicUser.getInternalId());
          _apiUserRepo.save(magicUser);
          return magicUser;
        });
  }

  /** The Account Request User should <em>always</em> exist. */
  private ApiUser getAccountRequestApiUser() {
    Optional<ApiUser> found = _apiUserRepo.findByLoginEmail(ACCOUNT_REQUEST_EMAIL);
    return found.orElseGet(
        () -> {
          ApiUser magicUser =
              new ApiUser(
                  ACCOUNT_REQUEST_EMAIL,
                  new PersonName("", "", "Account Request Self-Registration User", ""));
          _apiUserRepo.save(magicUser);
          log.info(
              "Magic account request self-registration user not found. Created Person={}",
              magicUser.getInternalId());
          return magicUser;
        });
  }

  /** The SMS Webhook API User should <em>always</em> exist. */
  public ApiUser getWebhookApiUser() {
    Optional<ApiUser> found = _apiUserRepo.findByLoginEmail(WEBHOOK_EMAIL);
    return found.orElseGet(
        () -> {
          ApiUser magicUser =
              new ApiUser(WEBHOOK_EMAIL, new PersonName("", "", "Webhook User", ""));
          _apiUserRepo.save(magicUser);
          log.info(
              "Magic account SMS webhook user not found. Created Person={}",
              magicUser.getInternalId());
          return magicUser;
        });
  }

  /** Only used for audit logging. */
  public ApiUser getAnonymousApiUser() {
    Optional<ApiUser> found = _apiUserRepo.findByLoginEmail(ANONYMOUS_EMAIL);
    return found.orElseGet(
        () -> {
          ApiUser magicUser =
              new ApiUser(ANONYMOUS_EMAIL, new PersonName("", "", "Anonymous User", ""));
          _apiUserRepo.save(magicUser);
          log.info(
              "Magic account anonymous user not found. Created Person={}",
              magicUser.getInternalId());
          return magicUser;
        });
  }

  private Optional<ApiUser> getCurrentNonOktaUser(IdentityAttributes userIdentity) {
    if (userIdentity == null) {
      if (_patientContextHolder.hasPatientLink()) {
        return Optional.of(getPatientApiUser());
      }
      if (_patientContextHolder.isPatientSelfRegistrationRequest()) {
        return Optional.of(getPatientSelfRegistrationApiUser());
      }
      if (_accountRequestContextHolder.isAccountRequest()) {
        return Optional.of(getAccountRequestApiUser());
      }
      if (_webhookContextHolder.isWebhook()) {
        return Optional.of(getWebhookApiUser());
      }
      throw new UnidentifiedUserException();
    }
    return Optional.empty();
  }

  private ApiUser getCurrentApiUserFromIdentity(IdentityAttributes userIdentity) {
    Optional<ApiUser> found = _apiUserRepo.findByLoginEmail(userIdentity.getUsername());
    if (found.isPresent()) {
      log.debug("User has logged in before: retrieving user record.");
      ApiUser user = found.get();
      user.updateLastSeen();
      user = _apiUserRepo.save(user);
      return user;
    } else {
      // Assumes user already has a corresponding Okta entity; otherwise, they couldn't log in :)
      log.info("Initial login for user: creating user record.");
      ApiUser user = new ApiUser(userIdentity.getUsername(), userIdentity);
      user.updateLastSeen();
      user = _apiUserRepo.save(user);

      log.info("User with id={} self-created", user.getInternalId());

      return user;
    }
  }

  private ApiUser getCurrentApiUser() {
    if (RequestContextHolder.getRequestAttributes() == null) {
      // short-circuit in the event this is called from outside a request
      return getCurrentApiUserNoCache();
    }

    try {
      if (_apiUserContextHolder.hasBeenPopulated()) {
        log.debug("Retrieving user from request context");
        return _apiUserContextHolder.getCurrentApiUser();
      }
      ApiUser user = getCurrentApiUserNoCache();
      _apiUserContextHolder.setCurrentApiUser(user);
      return user;
    } catch (ScopeNotActiveException e) {
      return getCurrentApiUserNoCache();
    }
  }

  private ApiUser getCurrentApiUserNoCache() {
    IdentityAttributes userIdentity = _supplier.get();
    Optional<ApiUser> nonOktaUser = getCurrentNonOktaUser(userIdentity);
    return nonOktaUser.orElseGet(() -> getCurrentApiUserFromIdentity(userIdentity));
  }

  /*
   `getCurrentUserInfoForWhoAmI()` can be removed and replaced with `getCurrentUserInfo()` as part of #7602 or whenever we stop migrating users over from Okta
  */
  public UserInfo getCurrentUserInfoForWhoAmI() {
    ApiUser currentUser = getCurrentApiUser();

    Optional<OrganizationRoles> currentOrgRoles = _orgService.getCurrentOrganizationRoles();
    boolean isAdmin = _authService.isSiteAdmin();
    if (!isAdmin) {
      if (currentOrgRoles.isPresent()) {
        PartialOktaUser oktaUser = _oktaRepo.findUser(currentUser.getLoginEmail());
        return consolidateUser(currentUser, oktaUser);
      } else {
        log.info("No org roles for User ID: {}", currentUser.getInternalId());
      }
    }
    return new UserInfo(currentUser, currentOrgRoles, isAdmin);
  }

  public UserInfo getCurrentUserInfo() {
    ApiUser currentUser = getCurrentApiUser();
    Optional<OrganizationRoles> currentOrgRoles = _orgService.getCurrentOrganizationRoles();
    boolean isAdmin = _authService.isSiteAdmin();
    return new UserInfo(currentUser, currentOrgRoles, isAdmin);
  }

  @AuthorizationConfiguration.RequirePermissionManageUsers
  public List<ApiUser> getUsersInCurrentOrg() {
    Organization org = _orgService.getCurrentOrganization();
    List<ApiUser> usersInOrg;
    if (_featureFlagsConfig.isOktaMigrationEnabled()) {
      usersInOrg = _dbAuthService.getUsersInOrganization(org);
    } else {
      final Set<String> orgUserEmails = _oktaRepo.getAllUsersForOrganization(org);
      usersInOrg = _apiUserRepo.findAllByLoginEmailInOrderByName(orgUserEmails);
    }
    return usersInOrg;
  }

  @AuthorizationConfiguration.RequirePermissionManageUsers
  public ManageUsersPageWrapper getPagedUsersAndStatusInCurrentOrg(
      int pageNumber, int pageSize, String searchQuery) {
    List<ApiUserWithStatus> allUsers = getUsersAndStatusInCurrentOrg();

    List<ApiUserWithStatus> filteredUsers = allUsers;

    if (!searchQuery.isBlank()) {
      filteredUsers =
          allUsers.stream()
              .filter(
                  u -> {
                    String cleanedSearchQuery = searchQuery.replace(",", " ");
                    List<String> querySubstringList =
                        Arrays.stream(cleanedSearchQuery.toLowerCase().split("\\s+")).toList();

                    String fullName =
                        u.getNameInfo().getFirstName()
                            + " "
                            + u.getNameInfo().getMiddleName()
                            + " "
                            + u.getNameInfo().getLastName();

                    return querySubstringList.stream().allMatch(fullName.toLowerCase()::contains);
                  })
              .toList();
    }

    int totalSearchResults = filteredUsers.size();

    int startIndex = pageNumber * pageSize;

    boolean onlyOnePageOfResults = totalSearchResults <= pageSize;
    boolean requestedPageOutOfBounds = startIndex > totalSearchResults;

    if (onlyOnePageOfResults || requestedPageOutOfBounds) {
      startIndex = 0;
    }

    int endIndex = Math.min((startIndex + pageSize), totalSearchResults);

    List<ApiUserWithStatus> filteredSublist = filteredUsers.subList(startIndex, endIndex);

    Page<ApiUserWithStatus> pageContent =
        new PageImpl<>(filteredSublist, PageRequest.of(pageNumber, pageSize), totalSearchResults);

    return new ManageUsersPageWrapper(pageContent, allUsers.size());
  }

  // To be addressed in #8108
  @AuthorizationConfiguration.RequirePermissionManageUsers
  public List<ApiUserWithStatus> getUsersAndStatusInCurrentOrg() {
    Organization org = _orgService.getCurrentOrganization();
    final Map<String, UserStatus> emailsToStatus =
        _oktaRepo.getAllUsersWithStatusForOrganization(org);
    List<ApiUser> users = _apiUserRepo.findAllByLoginEmailInOrderByName(emailsToStatus.keySet());
    return users.stream()
        .map(u -> new ApiUserWithStatus(u, emailsToStatus.get(u.getLoginEmail())))
        .collect(Collectors.toList());
  }

  @AuthorizationConfiguration.RequirePermissionManageTargetUser
  public UserInfo getUser(final UUID userId) {
    final Optional<ApiUser> optApiUser = _apiUserRepo.findById(userId);
    if (optApiUser.isEmpty()) {
      throw new UnidentifiedUserException();
    }
    final ApiUser apiUser = optApiUser.get();

    PartialOktaUser oktaUser = _oktaRepo.findUser(apiUser.getLoginEmail());
    return consolidateUser(apiUser, oktaUser);
  }

  @AuthorizationConfiguration.RequireGlobalAdminUser
  public UserInfo setCurrentUserTenantDataAccess(
      String organizationExternalID, String justification) {
    if (organizationExternalID == null) {
      return cancelCurrentUserTenantDataAccess();
    }

    if (justification == null) {
      throw new IllegalGraphqlArgumentException("A justification for this access is required.");
    }
    ApiUser apiUser = getCurrentApiUser();
    Organization org = _orgService.getOrganization(organizationExternalID);

    Optional<OrganizationRoleClaims> roleClaims =
        _tenantService.addTenantDataAccess(apiUser, org, justification);
    Optional<OrganizationRoles> orgRoles = roleClaims.map(_orgService::getOrganizationRoles);

    return new UserInfo(apiUser, orgRoles, false);
  }

  @AuthorizationConfiguration.RequireGlobalAdminUser
  public List<ApiUser> getAllUsersByOrganization(Organization organization) {
    Set<String> usernames = _oktaRepo.getAllUsersForOrganization(organization);
    return usernames.stream()
        .map(username -> _apiUserRepo.findByLoginEmailIncludeArchived(username).orElse(null))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private UserInfo cancelCurrentUserTenantDataAccess() {
    ApiUser apiUser = getCurrentApiUser();
    _tenantService.removeAllTenantDataAccess(apiUser);
    _orgService.resetOrganizationRolesContext();
    return getCurrentUserInfo();
  }

  @AuthorizationConfiguration.RequireGlobalAdminUser
  public Set<String> getTenantDataAccessAuthoritiesForCurrentUser() {
    ApiUser apiUser = getCurrentApiUser();
    return _tenantService.getTenantDataAccessAuthorities(apiUser);
  }

  @AuthorizationConfiguration.RequireGlobalAdminUser
  public UserInfo getUserByLoginEmail(String loginEmail) {
    Optional<ApiUser> foundUser = _apiUserRepo.findByLoginEmailIncludeArchived(loginEmail);

    ApiUser apiUser = foundUser.orElseThrow(NonexistentUserException::new);

    try {
      // We hit the okta API just once and resolve the info of status, isAdmin and organization
      // roles with the same user object. It was not possible to move the entire
      // consolidateUserInformation logic in the okta repo because the reference to the orgService
      // (necessary to create the org roles) creates a cycle dependency
      PartialOktaUser oktaUser = _oktaRepo.findUser(apiUser.getLoginEmail());

      if (oktaUser.isSiteAdmin()) {
        throw new RestrictedAccessUserException();
      }

      return consolidateUser(apiUser, oktaUser);
    } catch (IllegalGraphqlArgumentException | UnidentifiedUserException e) {
      throw new OktaAccountUserException();
    }
  }

  private OrganizationRoles getOrganizationRoles(
      Optional<OrganizationRoleClaims> oktaClaims, ApiUser apiUser, boolean isSiteAdmin) {
    OrganizationRoles orgRoles = null;
    if (oktaClaims.isPresent()) {
      orgRoles = _orgService.getOrganizationRoles(oktaClaims.get());
    }

    if (!isSiteAdmin) {
      if (_featureFlagsConfig.isOktaMigrationEnabled()) {
        orgRoles = getOrgRolesFromDB(apiUser);
      } else {
        setRolesAndFacilities(Optional.ofNullable(orgRoles), apiUser);
      }
    }

    return orgRoles;
  }

  private UserInfo consolidateUser(ApiUser apiUser, PartialOktaUser oktaUser) {
    boolean isSiteAdmin = oktaUser.isSiteAdmin();
    UserStatus userStatus = oktaUser.getStatus();
    OrganizationRoleClaims oktaClaims =
        oktaUser.getOrganizationRoleClaims().orElseThrow(UnidentifiedUserException::new);
    OrganizationRoles orgRoles =
        getOrganizationRoles(Optional.ofNullable(oktaClaims), apiUser, isSiteAdmin);

    try {
      _dbOrgRoleClaimsService.checkOrgRoleClaimsEquality(
          List.of(oktaClaims),
          List.of(_dbOrgRoleClaimsService.getOrganizationRoleClaims(apiUser)),
          apiUser.getLoginEmail());
    } catch (MisconfiguredUserException e) {
      log.error("Misconfigured organizations in DB for User ID: {}", apiUser.getInternalId());
    }
    return new UserInfo(apiUser, Optional.of(orgRoles), isSiteAdmin, userStatus);
  }

  private UserInfo consolidateUser(ApiUser apiUser, Optional<OrganizationRoleClaims> oktaClaims) {
    OrganizationRoles orgRoles = getOrganizationRoles(oktaClaims, apiUser, false);
    return new UserInfo(apiUser, Optional.of(orgRoles), false);
  }

  @AuthorizationConfiguration.RequireGlobalAdminUser
  public void updateUserPrivilegesAndGroupAccess(
      String username, String orgExternalId, boolean allFacilitiesAccess, Role role) {
    updateUserPrivilegesAndGroupAccess(
        username, orgExternalId, allFacilitiesAccess, List.of(), role);
  }

  @AuthorizationConfiguration.RequireGlobalAdminUser
  public void updateUserPrivilegesAndGroupAccess(
      String username,
      String orgExternalId,
      boolean allFacilitiesAccess,
      List<UUID> facilities,
      Role role)
      throws IllegalGraphqlArgumentException {

    Organization newOrg = _orgService.getOrganization(orgExternalId);
    Set<OrganizationRole> roles = getOrganizationRoles(role, allFacilitiesAccess);
    Optional<ApiUser> foundUser = _apiUserRepo.findByLoginEmail(username);
    ApiUser apiUser = foundUser.orElseThrow(NonexistentUserException::new);

    Set<Facility> facilitiesToGiveAccessTo =
        getFacilitiesToGiveAccess(newOrg, roles, new HashSet<>(facilities));
    apiUser.setFacilities(facilitiesToGiveAccessTo);
    apiUser.setRoles(roles, newOrg);

    _oktaRepo.updateUserPrivilegesAndGroupAccess(
        username, newOrg, facilitiesToGiveAccessTo, role.toOrganizationRole(), allFacilitiesAccess);
  }

  /*
  Given a list of facility UUIDs, validate that they belong in the given org, and return the list of facility entities
  that the user should be given access to.

  If a user can access all facilities, we do not persist relationships to any facilities.
   */
  private Set<Facility> getFacilitiesToGiveAccess(
      Organization org, Set<OrganizationRole> roles, Set<UUID> facilities) {
    boolean accessAllFacilitiesPermission = PermissionHolder.grantsAllFacilityAccess(roles);
    if (!accessAllFacilitiesPermission && facilities.isEmpty()) {
      throw new PrivilegeUpdateFacilityAccessException();
    }

    Set<UUID> facilityIdsToGiveAccess =
        accessAllFacilitiesPermission
            // use an empty set of facilities if user can access all facilities
            ? Set.of()
            : facilities;

    Set<Facility> facilitiesToGiveAccessTo =
        _orgService.getFacilities(org, facilityIdsToGiveAccess);

    if (facilitiesToGiveAccessTo.size() != facilityIdsToGiveAccess.size()) {
      Set<UUID> facilityIdDiff =
          dedupeFoundAndPassedInFacilityIds(facilitiesToGiveAccessTo, facilityIdsToGiveAccess);
      throw new UnidentifiedFacilityException(facilityIdDiff, org.getExternalId());
    }

    return facilitiesToGiveAccessTo;
  }

  private Set<UUID> dedupeFoundAndPassedInFacilityIds(
      Set<Facility> facilitiesToGiveAccessTo, Set<UUID> facilityIdsToGiveAccess) {
    Set<UUID> facilityIdsFound =
        facilitiesToGiveAccessTo.stream()
            .map(IdentifiedEntity::getInternalId)
            .collect(Collectors.toSet());
    return facilityIdsToGiveAccess.stream()
        .filter(id -> !facilityIdsFound.contains(id))
        .collect(Collectors.toSet());
  }

  /**
   * Save a user's role and facility permissions in the DB as part of the Okta migration work
   *
   * @param orgRoles
   * @param apiUser
   * @return ApiUser
   */
  private ApiUser setRolesAndFacilities(Optional<OrganizationRoles> orgRoles, ApiUser apiUser) {
    try {
      if (orgRoles.isEmpty()) {
        throw new MisconfiguredUserException();
      }
      Organization org = orgRoles.get().getOrganization();
      Set<OrganizationRole> roles = orgRoles.get().getGrantedRoles();
      List<UUID> facilitiesInternalIds =
          orgRoles.get().getFacilities().stream()
              .map(IdentifiedEntity::getInternalId)
              .collect(Collectors.toList());
      Set<Facility> facilitiesToGiveAccessTo =
          facilitiesInternalIds.isEmpty()
              ? new HashSet<>()
              : getFacilitiesToGiveAccess(org, roles, new HashSet<>(facilitiesInternalIds));
      apiUser.setFacilities(facilitiesToGiveAccessTo);
      apiUser.setRoles(roles, org);
    } catch (MisconfiguredUserException e) {
      log.warn(
          "Could not migrate roles and facilities for user with id={}", apiUser.getInternalId());
    }
    return apiUser;
  }

  private OrganizationRoles getOrgRolesFromDB(ApiUser apiUser) {
    OrganizationRoleClaims orgRoleClaims =
        _dbOrgRoleClaimsService.getOrganizationRoleClaims(apiUser);
    return _orgService.getOrganizationRoles(orgRoleClaims);
  }

  private String getOrgExternalId(ApiUser apiUser) {
    String orgExternalId;
    if (_featureFlagsConfig.isOktaMigrationEnabled()) {
      Optional<Organization> org = apiUser.getOrganizations().stream().findFirst();
      if (org.isPresent()) {
        orgExternalId = org.get().getExternalId();
      } else {
        throw new MisconfiguredUserException();
      }
    } else {
      OrganizationRoleClaims claims =
          _oktaRepo
              .getOrganizationRoleClaimsForUser(apiUser.getLoginEmail())
              .orElseThrow(MisconfiguredUserException::new);
      orgExternalId = claims.getOrganizationExternalId();
    }
    return orgExternalId;
  }
}
